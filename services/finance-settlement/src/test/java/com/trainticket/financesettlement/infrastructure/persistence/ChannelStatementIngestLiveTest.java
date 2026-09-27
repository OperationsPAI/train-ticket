package com.trainticket.financesettlement.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.financesettlement.adapters.http.ChannelStatementFactsClient;
import com.trainticket.financesettlement.application.ChannelStatementLineSource;
import com.trainticket.financesettlement.application.DomainEventEnvelopeMapper;
import com.trainticket.financesettlement.application.FinanceSettlementApplicationService;
import com.trainticket.financesettlement.application.InMemoryFeeAccrualRepository;
import com.trainticket.financesettlement.application.InMemoryInvoiceRepository;
import com.trainticket.financesettlement.application.InMemoryReconciliationBatchRepository;
import com.trainticket.financesettlement.application.InMemoryReconciliationCaseRepository;
import com.trainticket.financesettlement.application.InMemoryRevenueRecognitionRepository;
import com.trainticket.financesettlement.application.InMemorySupplierSettlementRepository;
import com.trainticket.financesettlement.application.PaymentIntentOrderReferenceRepository;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationBatch;
import com.trainticket.financesettlement.domain.ReconciliationEntry;
import com.trainticket.financesettlement.domain.ReconciliationStatus;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.client.RestClient;

/**
 * The channel side of a real statement, read from the deployed payment-channel
 * and reconciled against the platform's own captures.
 *
 * This is the evidence T22 asks for at line level: every line of the statement
 * is read exactly once, the statement's own arithmetic agrees with what was
 * stored, and captures recorded for those lines join to them by the reference
 * both sides carry. It runs in a schema of its own inside the deployed database,
 * so the release's rows are read and never written.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TEST_CHANNEL_STATEMENT_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TEST_CHANNEL_STATEMENT_URL", matches = ".+")
class ChannelStatementIngestLiveTest {
    /** How many of the statement's payment lines the capture comparison samples. */
    private static final int SAMPLED_CAPTURES = 200;

    private final String schema = "finance_ingest_" + UUID.randomUUID().toString().replace("-", "");
    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;

    ChannelStatementIngestLiveTest() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(4);
        config.setConnectionInitSql("SET search_path TO " + schema);
        config.addDataSourceProperty("options", "-c statement_timeout=120000");
        this.pool = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(pool);
        jdbc.execute("CREATE SCHEMA " + schema);
        new MigrationRunner(pool, new DataSourceTransactionManager(pool)).run(Path.of("migrations"));
    }

    @AfterEach
    void tearDown() {
        try {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        } finally {
            pool.close();
        }
    }

    @Test
    void readsTheWholeStatementAndJoinsItsPaymentsToRealCaptures() throws Exception {
        String statementId = System.getenv("TEST_CHANNEL_STATEMENT_ID");
        Map<String, Object> statement = jdbc.queryForMap(
            "SELECT statement_date, line_count, gross_payment_amount, gross_refund_amount FROM public.channel_statements WHERE channel_statement_id = ?",
            statementId);
        LocalDate settlementDate = LocalDate.parse(String.valueOf(statement.get("statement_date")));
        int lineCount = ((Number) statement.get("line_count")).intValue();
        Money grossPayment = money(statement.get("gross_payment_amount"));
        Money grossRefund = money(statement.get("gross_refund_amount"));
        assertTrue(lineCount > 0, "the statement this runs against must carry lines");

        ChannelStatementLineSource source = new ChannelStatementFactsClient(
            RestClient.builder(), System.getenv("TEST_CHANNEL_STATEMENT_URL"));
        PaymentIntentOrderReferenceRepository references = new PostgresReferenceRepositories.PaymentIntentRefs(pool);

        // The captures this runs against are the release's own, and the sample is
        // the head of the statement's payment lines. The order a line settles is
        // resolved from the payment intent, which is the reference both sides
        // carry, so that index has to be present before the lines are read.
        List<ChannelStatementLineSource.Line> sampled = source.page(statementId, 0, SAMPLED_CAPTURES).lines().stream()
            .filter(line -> "PAYMENT".equalsIgnoreCase(line.lineType()) && !line.paymentIntentId().isBlank())
            .toList();
        assertTrue(!sampled.isEmpty(), "the statement carries payment lines to sample");
        List<String> intents = sampled.stream().map(ChannelStatementLineSource.Line::paymentIntentId).toList();
        jdbc.update(
            """
                INSERT INTO payment_intent_order_refs(payment_intent_id, order_reference)
                SELECT payment_intent_id, order_reference FROM public.payment_intent_order_refs
                WHERE payment_intent_id = ANY(?)
                ON CONFLICT DO NOTHING
                """,
            arrayOf(intents));
        jdbc.update("INSERT INTO channel_statements SELECT * FROM public.channel_statements WHERE channel_statement_id = ?", statementId);
        int copied = jdbc.update(
            "INSERT INTO captures_by_order_id SELECT * FROM public.captures_by_order_id WHERE payment_intent_id = ANY(?) ON CONFLICT DO NOTHING",
            arrayOf(intents));
        assertTrue(copied > 0, "the release holds captures for the lines this statement carries");

        FinanceSettlementApplicationService service = service(source, references);
        int recorded = service.ingestChannelStatementLines(settlementDate, "corr-live-ingest");

        assertEquals(lineCount, recorded, "every line of the statement was read");
        assertEquals(grossPayment, money(jdbc.queryForObject(
            "SELECT sum(amount) FROM channel_statement_lines WHERE channel_statement_id = ? AND line_type = 'PAYMENT'", Object.class, statementId)),
            "the payment lines fold back to the statement's own gross payment");
        assertEquals(grossRefund, money(jdbc.queryForObject(
            "SELECT sum(amount) FROM channel_statement_lines WHERE channel_statement_id = ? AND line_type = 'REFUND'", Object.class, statementId)),
            "the refund lines fold back to the statement's own gross refund");
        assertEquals(0, service.ingestChannelStatementLines(settlementDate, "corr-live-ingest-again"),
            "the channel side is read once");

        ReconciliationBatch batch = service.runDailyReconciliation(settlementDate, "corr-live-reconcile");
        Map<String, ReconciliationEntry> byOrder = new LinkedHashMap<>();
        batch.entries().forEach(entry -> byOrder.put(entry.orderId(), entry));

        int compared = 0;
        int matched = 0;
        for (ChannelStatementLineSource.Line line : sampled) {
            Money capture = captureFor(line.paymentIntentId());
            if (capture == null) continue;
            compared++;
            ReconciliationEntry entry = byOrder.get(orderFor(line.paymentIntentId()));
            assertNotNull(entry, "the capture for " + line.paymentIntentId() + " has a channel side");
            assertEquals(line.actualAmount(), entry.channelAmount());
            assertEquals(capture, entry.platformAmount());
            ReconciliationStatus expected = capture.compareTo(line.actualAmount()) == 0
                ? ReconciliationStatus.MATCHED : ReconciliationStatus.AMOUNT_MISMATCH;
            assertEquals(expected, entry.status());
            if (expected == ReconciliationStatus.MATCHED) matched++;
        }
        assertTrue(compared > 0, "at least one sampled line had a capture to compare with");
        System.out.printf(
            "Channel statement ingest: %d lines (%d payment / %d refund), %d sampled captures compared, %d matched%n",
            recorded, count("PAYMENT", statementId), count("REFUND", statementId), compared, matched);
    }

    private long count(String lineType, String statementId) {
        Long value = jdbc.queryForObject(
            "SELECT count(*) FROM channel_statement_lines WHERE channel_statement_id = ? AND line_type = ?",
            Long.class, statementId, lineType);
        return value == null ? 0 : value;
    }

    private Money captureFor(String paymentIntentId) {
        List<Object> amounts = jdbc.queryForList(
            "SELECT amount FROM captures_by_order_id WHERE payment_intent_id = ? AND currency = 'CNY'", Object.class, paymentIntentId);
        return amounts.isEmpty() ? null : money(amounts.getFirst());
    }

    /** The key the reconciliation joins on: the order, or the intent without one. */
    private String orderFor(String paymentIntentId) {
        List<String> orders = jdbc.queryForList(
            "SELECT order_reference FROM payment_intent_order_refs WHERE payment_intent_id = ?", String.class, paymentIntentId);
        return orders.isEmpty() ? paymentIntentId : orders.getFirst();
    }

    private java.sql.Array arrayOf(List<String> values) throws Exception {
        try (var connection = pool.getConnection()) {
            return connection.createArrayOf("text", values.toArray());
        }
    }

    private static Money money(Object value) {
        if (value == null) return Money.of("CNY", "0.00");
        return Money.of("CNY", new BigDecimal(String.valueOf(value)).toPlainString());
    }

    private FinanceSettlementApplicationService service(ChannelStatementLineSource source, PaymentIntentOrderReferenceRepository references) {
        return new FinanceSettlementApplicationService(
            new InMemoryRevenueRecognitionRepository(),
            new InMemoryReconciliationCaseRepository(),
            new InMemoryInvoiceRepository(),
            new PostgresFinanceSettlementProjectionRepository(pool),
            new InMemoryReconciliationBatchRepository(),
            new InMemorySupplierSettlementRepository(),
            new InMemoryFeeAccrualRepository(),
            envelope -> {
            },
            new DomainEventEnvelopeMapper(),
            Clock.systemUTC()
        )
            .withChannelStatementLines(source)
            .withPaymentIntentOrderReferences(references);
    }
}
