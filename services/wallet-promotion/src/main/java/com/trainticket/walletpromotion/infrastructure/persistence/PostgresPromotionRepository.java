package com.trainticket.walletpromotion.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.walletpromotion.application.PromotionRepository;
import com.trainticket.walletpromotion.domain.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresPromotionRepository implements PromotionRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper; private final OutboxAppender outbox;
    public PostgresPromotionRepository(DataSource ds, ObjectMapper mapper, OutboxAppender outbox) { this.jdbc = new JdbcTemplate(ds); this.mapper = mapper; this.outbox = outbox; }
    public Optional<PromotionInstrument> findBenefit(String id) { return jdbc.query("SELECT data::text FROM promotion_instrument_snapshots WHERE id=?", rs -> rs.next()?Optional.of(read(rs.getString(1), PromotionInstrument.class)):Optional.empty(), id); }
    public Optional<WalletAccount> findWalletByAccountId(String accountId) { return jdbc.query("SELECT data::text FROM wallet_account_snapshots WHERE data->>'accountId'=?", rs -> rs.next()?Optional.of(read(rs.getString(1), WalletAccount.class)):Optional.empty(), accountId); }
    public List<PromotionInstrument> listBenefits(String accountId, PromotionStatus status, BenefitType type, int limit, int offset) { String sql="SELECT data::text FROM promotion_instrument_snapshots WHERE data->>'accountId'=? AND (? IS NULL OR data->>'status'=?) AND (? IS NULL OR data->>'benefitType'=?) ORDER BY data->>'createdAt' LIMIT ? OFFSET ?"; return jdbc.query(sql, (rs,n)->read(rs.getString(1), PromotionInstrument.class), accountId, name(status), name(status), name(type), name(type), limit, offset); }
    public int countBenefits(String accountId, PromotionStatus status, BenefitType type) { Integer c=jdbc.queryForObject("SELECT count(*) FROM promotion_instrument_snapshots WHERE data->>'accountId'=? AND (? IS NULL OR data->>'status'=?) AND (? IS NULL OR data->>'benefitType'=?)", Integer.class, accountId, name(status), name(status), name(type), name(type)); return c==null?0:c; }
    public List<PromotionInstrument> findExpirable(Instant now, int limit) { return jdbc.query("SELECT data::text FROM promotion_instrument_snapshots WHERE data->>'status' IN ('ISSUED','RESERVED','RELEASED') AND (data->>'validUntil')::timestamptz <= ? ORDER BY data->>'validUntil' LIMIT ?", (rs,n)->read(rs.getString(1), PromotionInstrument.class), java.sql.Timestamp.from(now), limit); }
    public Optional<BenefitRedemption> findRedemptionByReason(String benefitId, BusinessReason reason) { return jdbc.query("SELECT data::text FROM benefit_redemptions WHERE benefit_id=? AND reason_key=?", rs -> rs.next()?Optional.of(read(rs.getString(1), BenefitRedemption.class)):Optional.empty(), benefitId, reason.key()); }
    public Optional<BenefitRedemption> findRedemption(String id) { return jdbc.query("SELECT data::text FROM benefit_redemptions WHERE redemption_id=?", rs -> rs.next()?Optional.of(read(rs.getString(1), BenefitRedemption.class)):Optional.empty(), id); }
    public void saveMutation(PromotionInstrument b, WalletAccount w, WalletPromotionEvent e, BenefitRedemption r, ReversalRecord rev) {
        upsert("promotion_instrument_snapshots", b.benefitId(), b.version(), b); upsert("wallet_account_snapshots", w.walletAccountId(), w.version(), w);
        jdbc.update("INSERT INTO wallet_ledger_entries(ledger_entry_id,wallet_account_id,benefit_id,data) VALUES (?,?,?,?::jsonb) ON CONFLICT DO NOTHING", w.lastLedgerEntryId(), w.walletAccountId(), b.benefitId(), json(e.payload().get("walletBalanceDelta")));
        if (r != null) jdbc.update("INSERT INTO benefit_redemptions(redemption_id,benefit_id,reason_key,data) VALUES (?,?,?,?::jsonb)", r.redemptionId(), r.benefitId(), r.businessReason().key(), json(r));
        if (rev != null) { jdbc.update("INSERT INTO redemption_reversals(reversal_id,redemption_id,data) VALUES (?,?,?::jsonb)", rev.reversalId(), rev.redemptionId(), json(rev)); jdbc.update("UPDATE benefit_redemptions SET data=jsonb_set(data,'{reversedMinorUnits}',to_jsonb((data->>'reversedMinorUnits')::bigint + ?)) WHERE redemption_id=?", rev.amount().minorUnits(), rev.redemptionId()); }
        outbox.append(new EventEnvelope(DeterministicEventIds.forTransition(e.eventType(), b.benefitId(), b.version()), e.eventType(), e.occurredAt(), com.trainticket.platformkit.messaging.PrefixedIds.newCorrelationId(), null, "wallet-promotion", 1, e.payload()));
    }
    private void upsert(String table, String id, long version, Object data) { jdbc.update("INSERT INTO "+table+"(id,version,data) VALUES (?,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET version=EXCLUDED.version,data=EXCLUDED.data,updated_at=now()", id, version, json(data)); }
    private <T> T read(String json, Class<T> type) { try { return mapper.readValue(json, type); } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); } }
    private String json(Object v) { try { return mapper.writeValueAsString(v); } catch (JsonProcessingException ex) { throw new IllegalArgumentException(ex); } }
    private static String name(Enum<?> e) { return e==null?null:e.name(); }
}
