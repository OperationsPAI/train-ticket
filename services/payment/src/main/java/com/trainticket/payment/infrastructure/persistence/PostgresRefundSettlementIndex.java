package com.trainticket.payment.infrastructure.persistence;

import com.trainticket.payment.domain.ports.RefundSettlementIndex;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresRefundSettlementIndex implements RefundSettlementIndex {
    private final JdbcTemplate jdbc;

    PostgresRefundSettlementIndex(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void record(RefundSettlement settlement) {
        jdbc.update(
            """
                INSERT INTO refund_settlements(refund_id, payment_intent_id, business_reference, source_case_ref, currency, amount_minor_units, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (refund_id) DO NOTHING
                """,
            settlement.refundId(),
            settlement.paymentIntentId(),
            settlement.businessReference(),
            settlement.sourceCaseRef(),
            settlement.currency(),
            settlement.amountMinorUnits(),
            Timestamp.from(settlement.settledAt())
        );
    }

    @Override
    public List<RefundSettlement> list(Instant settledFrom, Instant settledTo, Cursor after, int limit) {
        // (settled_at, refund_id) is the index order, so the cursor is the same
        // pair and a page is a range scan rather than a sort.
        Instant from = after == null ? settledFrom : after.settledAt();
        String fromRefundId = after == null ? "" : after.refundId();
        return jdbc.query(
            """
                SELECT refund_id, payment_intent_id, business_reference, source_case_ref, currency, amount_minor_units, settled_at
                FROM refund_settlements
                WHERE settled_at >= ? AND settled_at < ?
                  AND (settled_at, refund_id) > (?, ?)
                ORDER BY settled_at, refund_id
                LIMIT ?
                """,
            (rs, rowNum) -> new RefundSettlement(
                rs.getString("refund_id"),
                rs.getString("payment_intent_id"),
                rs.getString("business_reference"),
                rs.getString("source_case_ref"),
                rs.getString("currency"),
                rs.getLong("amount_minor_units"),
                rs.getTimestamp("settled_at").toInstant()
            ),
            Timestamp.from(from),
            Timestamp.from(settledTo),
            Timestamp.from(from),
            fromRefundId,
            limit
        );
    }
}
