package com.trainticket.financesettlement.infrastructure.persistence;

import org.springframework.beans.factory.annotation.Autowired;
import com.trainticket.financesettlement.application.FinanceSettlementEventHandler;
import com.trainticket.financesettlement.application.FinanceSettlementProjectionRepository;
import com.trainticket.financesettlement.domain.Money;
import java.math.BigDecimal;
import java.util.Currency;
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

    private static Money money(String currency, String amount) {
        return new Money(Currency.getInstance(currency), new BigDecimal(amount));
    }
}
