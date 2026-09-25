package com.trainticket.walletpromotion.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.walletpromotion.application.MutationArtifacts;
import com.trainticket.walletpromotion.application.PromotionRepository;
import com.trainticket.walletpromotion.domain.BenefitRedemption;
import com.trainticket.walletpromotion.domain.BenefitReservation;
import com.trainticket.walletpromotion.domain.BenefitType;
import com.trainticket.walletpromotion.domain.BusinessReason;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.PromotionStatus;
import com.trainticket.walletpromotion.domain.ReservationRelease;
import com.trainticket.walletpromotion.domain.ReversalRecord;
import com.trainticket.walletpromotion.domain.WalletAccount;
import com.trainticket.walletpromotion.domain.WalletPromotionEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

public class PostgresPromotionRepository implements PromotionRepository {
    @Override public Optional<PromotionInstrument> findCompensationIssue(com.trainticket.walletpromotion.application.IssueBenefitCommand command) {
        if(command.caseId()==null || command.caseId().isBlank())throw new IllegalArgumentException("compensation caseId is required");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},"wallet-compensation:"+command.caseId());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},"wallet-account:"+command.accountId());
        var rows=jdbc.query("SELECT request::text,benefit_id FROM compensation_issuances WHERE case_id=?",(rs,index)->new String[]{rs.getString(1),rs.getString(2)},command.caseId());
        if(rows.isEmpty()){
            var existing=jdbc.queryForList("SELECT id FROM promotion_instrument_snapshots WHERE data->>'issuanceSource'='POST_SALES_COMP' AND data->>'caseId'=?",String.class,command.caseId());
            if(!existing.isEmpty())throw new com.trainticket.platformkit.http.ApiException(com.trainticket.platformkit.http.ApiErrorCode.CONFLICT,"compensation benefit exists without its issuance request");
            return Optional.empty();
        }
        var original=read(rows.getFirst()[0],com.trainticket.walletpromotion.application.IssueBenefitCommand.class);
        if(!original.equals(command))throw new com.trainticket.platformkit.http.ApiException(com.trainticket.platformkit.http.ApiErrorCode.CONFLICT,"compensation case already has another issuance request");
        return Optional.of(findBenefit(rows.getFirst()[1]).orElseThrow(()->new IllegalStateException("compensation issuance has no benefit")));
    }

    @Override public void recordCompensationIssue(com.trainticket.walletpromotion.application.IssueBenefitCommand command,String benefitId) {
        jdbc.update("INSERT INTO compensation_issuances(case_id,benefit_id,request) VALUES(?,?,?::jsonb)",command.caseId(),benefitId,json(command));
    }
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
               AND (?::text IS NULL OR data->>'status' = ?::text)
               AND (?::text IS NULL OR data->>'benefitType' = ?::text)
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
               AND (?::text IS NULL OR data->>'status' = ?::text)
               AND (?::text IS NULL OR data->>'benefitType' = ?::text)
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
             WHERE data->>'status' IN ('ISSUED', 'RESERVED', 'REDEEMED', 'RELEASED', 'REVERSED')
               AND data->>'validUntil' <= ?
             ORDER BY data->>'validUntil'
             LIMIT ?
            """;
        // validUntil is serialized as an RFC3339 UTC (Z) Instant string, which sorts
        // chronologically as text (matching idx_promotion_expiry). The previous
        // ::numeric cast assumed epoch decimals and silently matched nothing, so the
        // scheduled expiry sweep never expired any benefit.
        return jdbc.query(
            sql,
            (rs, rowNumber) -> read(rs.getString(1), PromotionInstrument.class),
            now.toString(),
            limit
        );
    }

    @Override
    public List<BenefitReservation> findExpiredReservations(Instant now, int limit) {
        return jdbc.query(
            "SELECT data::text FROM benefit_reservations WHERE status='ACTIVE' AND expires_at<=? ORDER BY expires_at LIMIT ?",
            (rs, rowNumber) -> read(rs.getString(1), BenefitReservation.class),
            java.sql.Timestamp.from(now),
            limit
        );
    }

    @Override
    public List<BenefitReservation> findActiveReservations(String benefitId) {
        return jdbc.query(
            "SELECT data::text FROM benefit_reservations WHERE benefit_id=? AND status='ACTIVE' ORDER BY reservation_ref",
            (rs, rowNumber) -> read(rs.getString(1), BenefitReservation.class),
            benefitId
        );
    }

    @Override
    public Optional<BenefitReservation> findReservation(String benefitId, String reservationRef) {
        return jdbc.query(
            "SELECT data::text FROM benefit_reservations WHERE benefit_id=? AND reservation_ref=?",
            rs -> rs.next() ? Optional.of(read(rs.getString(1), BenefitReservation.class)) : Optional.empty(),
            benefitId,
            reservationRef
        );
    }

    @Override
    public Optional<BenefitRedemption> findRedemptionByReference(String benefitId, String redemptionRef) {
        return jdbc.query(
            "SELECT data::text FROM benefit_redemptions WHERE benefit_id = ? AND redemption_ref = ?",
            rs -> rs.next() ? Optional.of(read(rs.getString(1), BenefitRedemption.class)) : Optional.empty(),
            benefitId,
            redemptionRef
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
    public Optional<ReservationRelease> findReleaseByReference(String benefitId, String releaseRef) {
        return jdbc.query(
            "SELECT data::text FROM reservation_releases WHERE benefit_id=? AND release_ref=?",
            rs -> rs.next() ? Optional.of(read(rs.getString(1), ReservationRelease.class)) : Optional.empty(),
            benefitId,
            releaseRef
        );
    }

    @Override
    public Optional<ReversalRecord> findReversalByReference(String redemptionId, String reversalRef) {
        return jdbc.query(
            "SELECT data::text FROM redemption_reversals WHERE redemption_id=? AND reversal_ref=?",
            rs -> rs.next() ? Optional.of(read(rs.getString(1), ReversalRecord.class)) : Optional.empty(),
            redemptionId,
            reversalRef
        );
    }

    @Override
    public void saveMutation(
        PromotionInstrument benefit,
        WalletAccount wallet,
        WalletPromotionEvent event,
        MutationArtifacts artifacts
    ) {
        saveSnapshot("promotion_instrument_snapshots", benefit.benefitId(), benefit.version(), benefit);
        saveSnapshot("wallet_account_snapshots", wallet.walletAccountId(), wallet.version(), wallet);
        appendLedgerEntry(benefit, wallet, event);
        for (BenefitReservation reservation : artifacts.reservations()) {
            upsertReservation(reservation);
        }
        BenefitRedemption redemption = artifacts.redemption();
        if (redemption != null) {
            insertRedemption(redemption);
        }
        if (artifacts.release() != null) {
            insertRelease(artifacts.release());
        }
        ReversalRecord reversal = artifacts.reversal();
        if (reversal != null) {
            insertReversal(reversal);
        }
        outbox.append(envelope(event, benefit));
    }

    private void upsertReservation(BenefitReservation reservation) {
        jdbc.update(
            """
            INSERT INTO benefit_reservations(benefit_id,reservation_ref,status,expires_at,data)
            VALUES(?,?,?,?,?::jsonb)
            ON CONFLICT(benefit_id,reservation_ref) DO UPDATE
              SET status=EXCLUDED.status,expires_at=EXCLUDED.expires_at,data=EXCLUDED.data,updated_at=now()
            """,
            reservation.benefitId(),
            reservation.reservationRef(),
            reservation.status().name(),
            java.sql.Timestamp.from(reservation.expiresAt()),
            json(reservation)
        );
    }

    private void insertRelease(ReservationRelease release) {
        jdbc.update(
            "INSERT INTO reservation_releases(release_id,benefit_id,reservation_ref,release_ref,data) VALUES(?,?,?,?,?::jsonb)",
            release.releaseId(),
            release.benefitId(),
            release.reservationRef(),
            release.releaseRef(),
            json(release)
        );
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
            INSERT INTO benefit_redemptions(redemption_id, benefit_id, reason_key, redemption_ref, data)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """,
            redemption.redemptionId(),
            redemption.benefitId(),
            redemption.businessReason().key(),
            redemption.redemptionRef(),
            json(redemption)
        );
    }

    private void insertReversal(ReversalRecord reversal) {
        jdbc.update(
            "INSERT INTO redemption_reversals(reversal_id, redemption_id, reason_key, reversal_ref, data) VALUES (?, ?, ?, ?, ?::jsonb)",
            reversal.reversalId(),
            reversal.redemptionId(),
            reversal.businessReason().key(),
            reversal.reversalRef(),
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
            snapshotInsertParameters(id, newVersion, data)
        );
    }

    private PreparedStatementSetter snapshotInsertParameters(String id, long newVersion, Object data) {
        String snapshotJson = json(data);
        return statement -> {
            statement.setString(1, id);
            statement.setLong(2, newVersion);
            statement.setString(3, snapshotJson);
        };
    }

    private int updateSnapshot(String table, String id, long expectedVersion, long newVersion, Object data) {
        return jdbc.update(
            "UPDATE " + table + " SET version = ?, data = ?::jsonb, updated_at = now() WHERE id = ? AND version = ?",
            snapshotUpdateParameters(id, expectedVersion, newVersion, data)
        );
    }

    private PreparedStatementSetter snapshotUpdateParameters(
        String id,
        long expectedVersion,
        long newVersion,
        Object data
    ) {
        String snapshotJson = json(data);
        return statement -> {
            statement.setLong(1, newVersion);
            statement.setString(2, snapshotJson);
            statement.setString(3, id);
            statement.setLong(4, expectedVersion);
        };
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
