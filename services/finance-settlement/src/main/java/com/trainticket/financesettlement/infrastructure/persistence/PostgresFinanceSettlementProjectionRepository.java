package com.trainticket.financesettlement.infrastructure.persistence;

import org.springframework.beans.factory.annotation.Autowired;
import com.trainticket.financesettlement.application.BenefitCostEntry;
import com.trainticket.financesettlement.application.ChannelStatementProjection;
import com.trainticket.financesettlement.application.FinanceSettlementEventHandler;
import com.trainticket.financesettlement.application.FinanceSettlementProjectionRepository;
import com.trainticket.financesettlement.domain.Money;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresFinanceSettlementProjectionRepository implements FinanceSettlementProjectionRepository {
    private final JdbcOperations jdbc;

    @Autowired
    public PostgresFinanceSettlementProjectionRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    PostgresFinanceSettlementProjectionRepository(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FinanceSettlementEventHandler.PaymentCaptureFact> findCapture(String orderId) {
        return jdbc.query(
            "SELECT payment_intent_id, currency, amount, source_event_id FROM captures_by_order_id WHERE order_id = ?",
            rs -> rs.next() ? Optional.of(new FinanceSettlementEventHandler.PaymentCaptureFact(
                rs.getString("payment_intent_id"),
                money(rs.getString("currency"), rs.getString("amount")),
                rs.getString("source_event_id")
            )) : Optional.empty(),
            orderId
        );
    }

    @Override
    public void saveCapture(String orderId, FinanceSettlementEventHandler.PaymentCaptureFact capture) {
        jdbc.update(
            """
                INSERT INTO captures_by_order_id(order_id, payment_intent_id, currency, amount, source_event_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (order_id) DO UPDATE SET
                  payment_intent_id = EXCLUDED.payment_intent_id,
                  currency = EXCLUDED.currency,
                  amount = EXCLUDED.amount,
                  source_event_id = EXCLUDED.source_event_id,
                  updated_at = now()
                """,
            orderId,
            capture.paymentIntentId(),
            capture.amount().currency().getCurrencyCode(),
            capture.amount().amount(),
            capture.sourceEventId()
        );
    }

    @Override
    public Optional<Money> findApprovedRefund(String caseId) {
        return jdbc.query(
            "SELECT currency, amount FROM approved_refunds_by_case_id WHERE case_id = ?",
            rs -> rs.next() ? Optional.of(money(rs.getString("currency"), rs.getString("amount"))) : Optional.empty(),
            caseId
        );
    }

    @Override
    public void saveApprovedRefund(String caseId, Money amount) {
        jdbc.update(
            """
                INSERT INTO approved_refunds_by_case_id(case_id, currency, amount)
                VALUES (?, ?, ?)
                ON CONFLICT (case_id) DO UPDATE SET
                  currency = EXCLUDED.currency,
                  amount = EXCLUDED.amount,
                  updated_at = now()
                """,
            caseId,
            amount.currency().getCurrencyCode(),
            amount.amount()
        );
    }

    @Override
    public void saveBenefitCostEntry(BenefitCostEntry entry) {
        jdbc.update(
            """
                INSERT INTO benefit_cost_entries(event_id, benefit_id, account_id, issuance_source, case_id, currency, amount, event_type, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET
                  benefit_id = EXCLUDED.benefit_id,
                  account_id = EXCLUDED.account_id,
                  issuance_source = EXCLUDED.issuance_source,
                  case_id = EXCLUDED.case_id,
                  currency = EXCLUDED.currency,
                  amount = EXCLUDED.amount,
                  event_type = EXCLUDED.event_type,
                  occurred_at = EXCLUDED.occurred_at
                """,
            entry.eventId(),
            entry.benefitId(),
            entry.accountId(),
            entry.issuanceSource(),
            entry.caseId(),
            entry.amount().currency().getCurrencyCode(),
            entry.amount().amount(),
            entry.eventType(),
            Timestamp.from(entry.occurredAt())
        );
    }

    @Override
    public Optional<BenefitCostEntry> findLatestBenefitCostEntryForBenefit(String benefitId) {
        return jdbc.query(
            """
                SELECT event_id, benefit_id, account_id, issuance_source, case_id, currency, amount, event_type, occurred_at
                FROM benefit_cost_entries
                WHERE benefit_id = ?
                ORDER BY occurred_at DESC, event_id ASC
                LIMIT 1
                """,
            rs -> rs.next() ? Optional.of(new BenefitCostEntry(
                rs.getString("event_id"),
                rs.getString("benefit_id"),
                rs.getString("account_id"),
                rs.getString("issuance_source"),
                rs.getString("case_id"),
                money(rs.getString("currency"), rs.getString("amount")),
                rs.getString("event_type"),
                rs.getTimestamp("occurred_at").toInstant()
            )) : Optional.empty(),
            benefitId
        );
    }

    @Override
    public List<BenefitCostEntry> findBenefitCostEntries(String accountId, int limit, int offset) {
        if (accountId == null || accountId.isBlank()) {
            return jdbc.query(
                """
                    SELECT event_id, benefit_id, account_id, issuance_source, case_id, currency, amount, event_type, occurred_at
                    FROM benefit_cost_entries
                    ORDER BY occurred_at DESC, event_id ASC
                    LIMIT ? OFFSET ?
                    """,
                (rs, rowNum) -> new BenefitCostEntry(
                    rs.getString("event_id"),
                    rs.getString("benefit_id"),
                    rs.getString("account_id"),
                    rs.getString("issuance_source"),
                    rs.getString("case_id"),
                    money(rs.getString("currency"), rs.getString("amount")),
                    rs.getString("event_type"),
                    rs.getTimestamp("occurred_at").toInstant()
                ),
                limit,
                offset
            );
        }
        return jdbc.query(
            """
                SELECT event_id, benefit_id, account_id, issuance_source, case_id, currency, amount, event_type, occurred_at
                FROM benefit_cost_entries
                WHERE account_id = ?
                ORDER BY occurred_at DESC, event_id ASC
                LIMIT ? OFFSET ?
                """,
            (rs, rowNum) -> new BenefitCostEntry(
                rs.getString("event_id"),
                rs.getString("benefit_id"),
                rs.getString("account_id"),
                rs.getString("issuance_source"),
                rs.getString("case_id"),
                money(rs.getString("currency"), rs.getString("amount")),
                rs.getString("event_type"),
                rs.getTimestamp("occurred_at").toInstant()
            ),
            accountId,
            limit,
            offset
        );
    }

    @Override
    public long countBenefitCostEntries(String accountId) {
        Long count = accountId == null || accountId.isBlank()
            ? jdbc.queryForObject("SELECT count(*) FROM benefit_cost_entries", Long.class)
            : jdbc.queryForObject("SELECT count(*) FROM benefit_cost_entries WHERE account_id = ?", Long.class, accountId);
        return count == null ? 0L : count;
    }

    @Override
    public void saveChannelStatement(ChannelStatementProjection s) {
        jdbc.update(
            """
                INSERT INTO channel_statements(channel_statement_id, channel, statement_date, currency, seed_version, line_count,
                  gross_payment_currency, gross_payment_amount, gross_refund_currency, gross_refund_amount,
                  fee_currency, fee_amount, statement_hash, status, generated_at, frozen_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (channel_statement_id) DO UPDATE SET
                  line_count = EXCLUDED.line_count,
                  gross_payment_currency = EXCLUDED.gross_payment_currency, gross_payment_amount = EXCLUDED.gross_payment_amount,
                  gross_refund_currency = EXCLUDED.gross_refund_currency, gross_refund_amount = EXCLUDED.gross_refund_amount,
                  fee_currency = EXCLUDED.fee_currency, fee_amount = EXCLUDED.fee_amount,
                  statement_hash = EXCLUDED.statement_hash, status = EXCLUDED.status,
                  frozen_at = EXCLUDED.frozen_at, source_event_id = EXCLUDED.source_event_id,
                  updated_at = now()
                """,
            s.channelStatementId(), s.channel(), s.statementDate(), s.currency(), s.seedVersion(), s.lineCount(),
            s.grossPaymentAmount().currency().getCurrencyCode(), s.grossPaymentAmount().amount(),
            s.grossRefundAmount().currency().getCurrencyCode(), s.grossRefundAmount().amount(),
            s.feeAmount().currency().getCurrencyCode(), s.feeAmount().amount(),
            s.statementHash(), s.status(),
            Timestamp.from(s.generatedAt()),
            s.frozenAt() == null ? null : Timestamp.from(s.frozenAt()),
            s.sourceEventId()
        );
    }

    @Override
    public Optional<ChannelStatementProjection> findChannelStatement(String channelStatementId) {
        return jdbc.query(
            "SELECT * FROM channel_statements WHERE channel_statement_id = ?",
            rs -> rs.next() ? Optional.of(mapStatement(rs)) : Optional.empty(),
            channelStatementId
        );
    }

    @Override
    public List<ChannelStatementProjection> findChannelStatements(String channel, String statementDate, int limit, int offset) {
        return jdbc.query(
            "SELECT * FROM channel_statements WHERE channel = ? AND statement_date = ? ORDER BY generated_at DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> mapStatement(rs),
            channel, statementDate, limit, offset
        );
    }

    @Override
    public long countChannelStatements(String channel, String statementDate) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM channel_statements WHERE channel = ? AND statement_date = ?",
            Long.class, channel, statementDate
        );
        return count == null ? 0L : count;
    }

    private ChannelStatementProjection mapStatement(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ChannelStatementProjection(
            rs.getString("channel_statement_id"),
            rs.getString("channel"),
            rs.getString("statement_date"),
            rs.getString("currency"),
            rs.getString("seed_version"),
            rs.getInt("line_count"),
            money(rs.getString("gross_payment_currency"), rs.getString("gross_payment_amount")),
            money(rs.getString("gross_refund_currency"), rs.getString("gross_refund_amount")),
            money(rs.getString("fee_currency"), rs.getString("fee_amount")),
            rs.getString("statement_hash"),
            rs.getString("status"),
            rs.getTimestamp("generated_at").toInstant(),
            rs.getTimestamp("frozen_at") == null ? null : rs.getTimestamp("frozen_at").toInstant(),
            rs.getString("source_event_id")
        );
    }

    private static Money money(String currency, String amount) {
        return new Money(Currency.getInstance(currency), new BigDecimal(amount));
    }
}
