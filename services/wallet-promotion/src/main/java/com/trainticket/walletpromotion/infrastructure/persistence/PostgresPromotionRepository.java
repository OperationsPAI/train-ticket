package com.trainticket.walletpromotion.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.walletpromotion.application.PromotionRepository;
import com.trainticket.walletpromotion.domain.BenefitRedemption;
import com.trainticket.walletpromotion.domain.BenefitType;
import com.trainticket.walletpromotion.domain.BusinessReason;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.PromotionStatus;
import com.trainticket.walletpromotion.domain.ReversalRecord;
import com.trainticket.walletpromotion.domain.WalletAccount;
import com.trainticket.walletpromotion.domain.WalletPromotionEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresPromotionRepository implements PromotionRepository {
    private final JdbcOperations jdbc;
    private final ObjectMapper mapper;
    private final OutboxAppender outbox;

    public PostgresPromotionRepository(DataSource dataSource, ObjectMapper mapper, OutboxAppender outbox) {
        this(new JdbcTemplate(dataSource), mapper, outbox);
    }

    PostgresPromotionRepository(JdbcOperations jdbc, ObjectMapper mapper, OutboxAppender outbox) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.outbox = outbox;
    }

    @Override
    public Optional<PromotionInstrument> findBenefit(String id) {
        return jdbc.query(
            "SELECT data::text FROM promotion_instrument_snapshots WHERE id = ?",
            rs -> rs.next() ? Optional.of(read(rs.getString(1), PromotionInstrument.class)) : Optional.empty(),
            id
        );
    }

    @Override
    public Optional<WalletAccount> findWalletByAccountId(String accountId) {
        return jdbc.query(
            "SELECT data::text FROM wallet_account_snapshots WHERE data->>'accountId' = ?",
            rs -> rs.next() ? Optional.of(read(rs.getString(1), WalletAccount.class)) : Optional.empty(),
            accountId
        );
    }

    @Override
    public List<PromotionInstrument> listBenefits(
        String accountId,
        PromotionStatus status,
        BenefitType type,
        int limit,
        int offset
    ) {
        String sql = """
            SELECT data::text
              FROM promotion_instrument_snapshots
             WHERE data->>'accountId' = ?
               AND (? IS NULL OR data->>'status' = ?)
               AND (? IS NULL OR data->>'benefitType' = ?)
             ORDER BY data->>'createdAt'
             LIMIT ? OFFSET ?
            """;
        return jdbc.query(
            sql,
            (rs, rowNumber) -> read(rs.getString(1), PromotionInstrument.class),
            accountId,
            name(status),
            name(status),
            name(type),
            name(type),
            limit,
            offset
        );
    }

    @Override
    public int countBenefits(String accountId, PromotionStatus status, BenefitType type) {
        String sql = """
            SELECT count(*)
              FROM promotion_instrument_snapshots
             WHERE data->>'accountId' = ?
               AND (? IS NULL OR data->>'status' = ?)
               AND (? IS NULL OR data->>'benefitType' = ?)
            """;
        Integer count = jdbc.queryForObject(
            sql,
            Integer.class,
            accountId,
            name(status),
            name(status),
            name(type),
            name(type)
        );
        return count == null ? 0 : count;
    }

    @Override
    public List<PromotionInstrument> findExpirable(Instant now, int limit) {
        String sql = """
            SELECT data::text
              FROM promotion_instrument_snapshots
             WHERE data->>'status' IN ('ISSUED', 'RESERVED', 'RELEASED')
               AND data->>'validUntil' <= ?
             ORDER BY data->>'validUntil'
             LIMIT ?
            """;
        // RFC3339 UTC strings compare chronologically as text, matching the
        // text-expression index (timestamptz casts are not IMMUTABLE there).
        return jdbc.query(
            sql,
            (rs, rowNumber) -> read(rs.getString(1), PromotionInstrument.class),
            java.time.format.DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)),
            limit
        );
    }

    @Override
    public Optional<BenefitRedemption> findRedemptionByReason(String benefitId, BusinessReason reason) {
        return jdbc.query(
            "SELECT data::text FROM benefit_redemptions WHERE benefit_id = ? AND reason_key = ?",
            rs -> rs.next() ? Optional.of(read(rs.getString(1), BenefitRedemption.class)) : Optional.empty(),
            benefitId,
            reason.key()
        );
    }

    @Override
    public Optional<BenefitRedemption> findRedemption(String id) {
        return jdbc.query(
            "SELECT data::text FROM benefit_redemptions WHERE redemption_id = ?",
            rs -> rs.next() ? Optional.of(read(rs.getString(1), BenefitRedemption.class)) : Optional.empty(),
            id
        );
    }

    @Override
    public void saveMutation(
        PromotionInstrument benefit,
        WalletAccount wallet,
        WalletPromotionEvent event,
        BenefitRedemption redemption,
        ReversalRecord reversal
    ) {
        saveSnapshot("promotion_instrument_snapshots", benefit.benefitId(), benefit.version(), benefit);
        saveSnapshot("wallet_account_snapshots", wallet.walletAccountId(), wallet.version(), wallet);
        appendLedgerEntry(benefit, wallet, event);
        if (redemption != null) {
            insertRedemption(redemption);
        }
        if (reversal != null) {
            insertReversal(reversal);
        }
        outbox.append(envelope(event, benefit));
    }

    private void appendLedgerEntry(PromotionInstrument benefit, WalletAccount wallet, WalletPromotionEvent event) {
        jdbc.update(
            """
            INSERT INTO wallet_ledger_entries(ledger_entry_id, wallet_account_id, benefit_id, data)
            VALUES (?, ?, ?, ?::jsonb)
            ON CONFLICT DO NOTHING
            """,
            wallet.lastLedgerEntryId(),
            wallet.walletAccountId(),
            benefit.benefitId(),
            json(event.payload().get("walletBalanceDelta"))
        );
    }

    private void insertRedemption(BenefitRedemption redemption) {
        jdbc.update(
            """
            INSERT INTO benefit_redemptions(redemption_id, benefit_id, reason_key, data)
            VALUES (?, ?, ?, ?::jsonb)
            """,
            redemption.redemptionId(),
            redemption.benefitId(),
            redemption.businessReason().key(),
            json(redemption)
        );
    }

    private void insertReversal(ReversalRecord reversal) {
        jdbc.update(
            "INSERT INTO redemption_reversals(reversal_id, redemption_id, data) VALUES (?, ?, ?::jsonb)",
            reversal.reversalId(),
            reversal.redemptionId(),
            json(reversal)
        );
        jdbc.update(
            """
            UPDATE benefit_redemptions
               SET data = jsonb_set(data, '{reversedMinorUnits}', to_jsonb((data->>'reversedMinorUnits')::bigint + ?))
             WHERE redemption_id = ?
            """,
            reversal.amount().minorUnits(),
            reversal.redemptionId()
        );
    }

    private void saveSnapshot(String table, String id, long newVersion, Object data) {
        long expectedVersion = newVersion - 1;
        if (expectedVersion < 0) {
            throw new OptimisticConcurrencyException("snapshot version conflict for " + id);
        }
        int rows = expectedVersion == 0
            ? insertSnapshot(table, id, newVersion, data)
            : updateSnapshot(table, id, expectedVersion, newVersion, data);
        if (rows == 0) {
            throw new OptimisticConcurrencyException("snapshot version conflict for " + id);
        }
    }

    private int insertSnapshot(String table, String id, long newVersion, Object data) {
        return jdbc.update(
            "INSERT INTO " + table + "(id, version, data) VALUES (?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
            id,
            newVersion,
            json(data)
        );
    }

    private int updateSnapshot(String table, String id, long expectedVersion, long newVersion, Object data) {
        return jdbc.update(
            "UPDATE " + table + " SET version = ?, data = ?::jsonb, updated_at = now() WHERE id = ? AND version = ?",
            newVersion,
            json(data),
            id,
            expectedVersion
        );
    }

    private EventEnvelope envelope(WalletPromotionEvent event, PromotionInstrument benefit) {
        return new EventEnvelope(
            DeterministicEventIds.forTransition(event.eventType(), benefit.benefitId(), benefit.version()),
            event.eventType(),
            event.occurredAt(),
            PrefixedIds.newCorrelationId(),
            null,
            "wallet-promotion",
            1,
            event.payload()
        );
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored JSON could not be decoded", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("value could not be encoded as JSON", exception);
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
