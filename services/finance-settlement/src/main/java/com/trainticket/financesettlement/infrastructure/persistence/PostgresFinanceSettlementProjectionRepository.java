package com.trainticket.financesettlement.infrastructure.persistence;

import org.springframework.beans.factory.annotation.Autowired;
import com.trainticket.financesettlement.application.AncillaryFinancialFact;
import com.trainticket.financesettlement.application.BenefitCostEntry;
import com.trainticket.financesettlement.application.ChannelStatementLineProjection;
import com.trainticket.financesettlement.application.ChannelStatementProjection;
import com.trainticket.financesettlement.application.FinanceSettlementEventHandler;
import com.trainticket.financesettlement.application.FinanceSettlementProjectionRepository;
import com.trainticket.financesettlement.domain.Money;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
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
            "SELECT payment_intent_id, currency, amount, source_event_id, occurred_at FROM captures_by_order_id WHERE order_id = ?",
            rs -> rs.next() ? Optional.of(new FinanceSettlementEventHandler.PaymentCaptureFact(
                orderId,
                rs.getString("payment_intent_id"),
                money(rs.getString("currency"), rs.getString("amount")),
                rs.getString("source_event_id"),
                rs.getTimestamp("occurred_at").toInstant()
            )) : Optional.empty(),
            orderId
        );
    }

    @Override
    public void saveCapture(String orderId, FinanceSettlementEventHandler.PaymentCaptureFact capture) {
        jdbc.update(
            """
                INSERT INTO captures_by_order_id(order_id, payment_intent_id, currency, amount, source_event_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_id) DO UPDATE SET
                  payment_intent_id = EXCLUDED.payment_intent_id,
                  currency = EXCLUDED.currency,
                  amount = EXCLUDED.amount,
                  source_event_id = EXCLUDED.source_event_id,
                  occurred_at = EXCLUDED.occurred_at,
                  updated_at = now()
                """,
            orderId,
            capture.paymentIntentId(),
            capture.amount().currency().getCurrencyCode(),
            capture.amount().amount(),
            capture.sourceEventId(),
            Timestamp.from(capture.occurredAt())
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
    public List<FinanceSettlementEventHandler.PaymentCaptureFact> findCapturesForSettlementDate(java.time.LocalDate settlementDate) {
        return jdbc.query(
            """
                SELECT order_id, payment_intent_id, currency, amount, source_event_id, occurred_at
                FROM captures_by_order_id
                WHERE occurred_at >= ? AND occurred_at < ?
                ORDER BY order_id
                """,
            (rs, rowNum) -> new FinanceSettlementEventHandler.PaymentCaptureFact(
                rs.getString("order_id"),
                rs.getString("payment_intent_id"),
                money(rs.getString("currency"), rs.getString("amount")),
                rs.getString("source_event_id"),
                rs.getTimestamp("occurred_at").toInstant()
            ),
            Timestamp.from(settlementDate.atStartOfDay().toInstant(ZoneOffset.UTC)),
            Timestamp.from(settlementDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
        );
    }

    @Override
    public void saveChannelStatementLine(ChannelStatementLineProjection line) {
        jdbc.update(
            """
                INSERT INTO channel_statement_lines(statement_line_id, channel_statement_id, statement_date, order_id, payment_intent_id, channel_order_id, currency, amount, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (statement_line_id) DO UPDATE SET
                  channel_statement_id = EXCLUDED.channel_statement_id,
                  statement_date = EXCLUDED.statement_date,
                  order_id = EXCLUDED.order_id,
                  payment_intent_id = EXCLUDED.payment_intent_id,
                  channel_order_id = EXCLUDED.channel_order_id,
                  currency = EXCLUDED.currency,
                  amount = EXCLUDED.amount,
                  source_event_id = EXCLUDED.source_event_id,
                  updated_at = now()
                """,
            line.statementLineId(),
            line.channelStatementId(),
            line.statementDate(),
            line.orderId(),
            line.paymentIntentId(),
            line.channelOrderId(),
            line.actualAmount().currency().getCurrencyCode(),
            line.actualAmount().amount(),
            line.sourceEventId()
        );
    }

    @Override
    public List<ChannelStatementLineProjection> findChannelStatementLinesForSettlementDate(java.time.LocalDate settlementDate) {
        return jdbc.query(
            """
                SELECT statement_line_id, channel_statement_id, statement_date, order_id, payment_intent_id, channel_order_id, currency, amount, source_event_id
                FROM channel_statement_lines
                WHERE statement_date = ?
                ORDER BY statement_line_id
                """,
            (rs, rowNum) -> new ChannelStatementLineProjection(
                rs.getString("statement_line_id"),
                rs.getString("channel_statement_id"),
                rs.getString("statement_date"),
                rs.getString("order_id"),
                rs.getString("payment_intent_id"),
                rs.getString("channel_order_id"),
                money(rs.getString("currency"), rs.getString("amount")),
                rs.getString("source_event_id")
            ),
            settlementDate.toString()
        );
    }

    @Override
    public List<ChannelStatementProjection> findChannelStatementsForSettlementDate(java.time.LocalDate settlementDate) {
        return jdbc.query(
            "SELECT * FROM channel_statements WHERE statement_date = ? ORDER BY channel_statement_id",
            (rs, rowNum) -> mapStatement(rs),
            settlementDate.toString()
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
    public void saveAncillaryFinancialFact(AncillaryFinancialFact fact) {
        jdbc.update(
            """
                INSERT INTO ancillary_financial_facts(event_id, event_type, fact_kind, ancillary_order_item_id, journey_order_id,
                  service_type, supplier_ref, payable_currency, payable_amount, refundable_currency, refundable_amount,
                  refunded_currency, refunded_amount, retained_currency, retained_amount, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET
                  event_type = EXCLUDED.event_type,
                  fact_kind = EXCLUDED.fact_kind,
                  ancillary_order_item_id = EXCLUDED.ancillary_order_item_id,
                  journey_order_id = EXCLUDED.journey_order_id,
                  service_type = EXCLUDED.service_type,
                  supplier_ref = EXCLUDED.supplier_ref,
                  payable_currency = EXCLUDED.payable_currency,
                  payable_amount = EXCLUDED.payable_amount,
                  refundable_currency = EXCLUDED.refundable_currency,
                  refundable_amount = EXCLUDED.refundable_amount,
                  refunded_currency = EXCLUDED.refunded_currency,
                  refunded_amount = EXCLUDED.refunded_amount,
                  retained_currency = EXCLUDED.retained_currency,
                  retained_amount = EXCLUDED.retained_amount,
                  occurred_at = EXCLUDED.occurred_at,
                  updated_at = now()
                """,
            fact.eventId(),
            fact.eventType(),
            fact.factKind(),
            fact.ancillaryOrderItemId(),
            fact.journeyOrderId(),
            fact.serviceType(),
            fact.supplierRef(),
            currencyCode(fact.payableAmount()),
            amountValue(fact.payableAmount()),
            currencyCode(fact.refundableAmount()),
            amountValue(fact.refundableAmount()),
            currencyCode(fact.refundedAmount()),
            amountValue(fact.refundedAmount()),
            currencyCode(fact.retainedAmount()),
            amountValue(fact.retainedAmount()),
            Timestamp.from(fact.occurredAt())
        );
    }

    @Override
    public Optional<AncillaryFinancialFact> findAncillaryFinancialFact(String eventId) {
        return jdbc.query(
            "SELECT * FROM ancillary_financial_facts WHERE event_id = ?",
            rs -> rs.next() ? Optional.of(mapAncillaryFinancialFact(rs)) : Optional.empty(),
            eventId
        );
    }

    @Override
    public List<AncillaryFinancialFact> findAncillaryFinancialFacts(String journeyOrderId, int limit, int offset) {
        if (journeyOrderId == null || journeyOrderId.isBlank()) {
            return jdbc.query(
                "SELECT * FROM ancillary_financial_facts ORDER BY occurred_at DESC, event_id ASC LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapAncillaryFinancialFact(rs),
                limit,
                offset
            );
        }
        return jdbc.query(
            "SELECT * FROM ancillary_financial_facts WHERE journey_order_id = ? ORDER BY occurred_at DESC, event_id ASC LIMIT ? OFFSET ?",
            (rs, rowNum) -> mapAncillaryFinancialFact(rs),
            journeyOrderId,
            limit,
            offset
        );
    }

    @Override
    public long countAncillaryFinancialFacts(String journeyOrderId) {
        Long count = journeyOrderId == null || journeyOrderId.isBlank()
            ? jdbc.queryForObject("SELECT count(*) FROM ancillary_financial_facts", Long.class)
            : jdbc.queryForObject("SELECT count(*) FROM ancillary_financial_facts WHERE journey_order_id = ?", Long.class, journeyOrderId);
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

    private AncillaryFinancialFact mapAncillaryFinancialFact(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AncillaryFinancialFact(
            rs.getString("event_id"),
            rs.getString("event_type"),
            rs.getString("fact_kind"),
            rs.getString("ancillary_order_item_id"),
            rs.getString("journey_order_id"),
            rs.getString("service_type"),
            rs.getString("supplier_ref"),
            nullableMoney(rs.getString("payable_currency"), rs.getString("payable_amount")),
            nullableMoney(rs.getString("refundable_currency"), rs.getString("refundable_amount")),
            nullableMoney(rs.getString("refunded_currency"), rs.getString("refunded_amount")),
            nullableMoney(rs.getString("retained_currency"), rs.getString("retained_amount")),
            rs.getTimestamp("occurred_at").toInstant()
        );
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

    private static String currencyCode(Money money) {
        return money == null ? null : money.currency().getCurrencyCode();
    }

    private static BigDecimal amountValue(Money money) {
        return money == null ? null : money.amount();
    }

    private static Money nullableMoney(String currency, String amount) {
        return currency == null || amount == null ? null : money(currency, amount);
    }

    private static Money money(String currency, String amount) {
        return new Money(Currency.getInstance(currency), new BigDecimal(amount));
    }
}
