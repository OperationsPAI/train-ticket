package com.trainticket.walletpromotion.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.idempotency.UuidV7;
import com.trainticket.platformkit.persistence.DbIdempotencyStore;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.walletpromotion.adapters.http.WalletPromotionController;
import com.trainticket.walletpromotion.domain.ApplicableScope;
import com.trainticket.walletpromotion.domain.BalanceType;
import com.trainticket.walletpromotion.domain.BenefitScopeType;
import com.trainticket.walletpromotion.domain.BenefitType;
import com.trainticket.walletpromotion.domain.BusinessReason;
import com.trainticket.walletpromotion.domain.IssuanceSource;
import com.trainticket.walletpromotion.domain.Money;
import com.trainticket.walletpromotion.domain.ReasonType;
import com.trainticket.walletpromotion.domain.RedemptionRule;
import com.trainticket.walletpromotion.domain.RevocationRule;
import com.trainticket.walletpromotion.infrastructure.persistence.PostgresPromotionRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnabledIfEnvironmentVariable(named = "TEST_WALLET_COMPENSATION_CASE", matches = ".+")
class CompensationIssuanceLiveTest {
    private static final int CONCURRENCY = 8;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void existingCompensationWithoutIssuanceRecordRejectsAnotherCredit() throws Exception {
        String schema = "wallet_compensation_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                 .connectTimeout(Duration.ofSeconds(5)).build();
             var admin = DriverManager.getConnection(requiredEnvironment("TEST_DATABASE_URL"))) {
            IssueBenefitCommand command = recordedCommand(client);
            String body = mapper.writeValueAsString(command);
            try (var statement = admin.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            try {
                PersistedState beforeReplay;
                try (var context = start(schema)) {
                    var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    JsonNode issued = successfulIssue(client, endpoint(context), body);
                    var original = assertSingleIssuance(jdbc, command, issued);
                    // Preserve actual service output and remove only dedup metadata in this isolated schema.
                    assertEquals(1, jdbc.update("DELETE FROM benefit_issuances WHERE issuance_key = ?", command.issuanceKey()));
                    beforeReplay = new PersistedState(original.benefits(), original.wallets(), original.ledgers(),
                        List.of(), original.events());
                }
                try (var restarted = start(schema)) {
                    var jdbc = new JdbcTemplate(restarted.getBean(DataSource.class));
                    var response = post(client, endpoint(restarted), body);
                    System.out.printf("Missing issuance record evidence: schema=%s caseId=%s HTTP=%d benefits=%d ledgers=%d BenefitIssued=%d%n",
                        schema, command.caseId(), response.statusCode(), rows(jdbc, "promotion_instrument_snapshots").size(),
                        rows(jdbc, "wallet_ledger_entries").size(), jdbc.queryForObject(
                            "SELECT count(*) FROM outbox WHERE envelope->>'eventType' = 'BenefitIssued'", Integer.class));
                    assertEquals(409, response.statusCode(), response.body());
                    assertEquals("CONFLICT", mapper.readTree(response.body()).required("code").asText());
                    assertEquals(beforeReplay, new PersistedState(rows(jdbc, "promotion_instrument_snapshots"),
                        rows(jdbc, "wallet_account_snapshots"), rows(jdbc, "wallet_ledger_entries"),
                        rows(jdbc, "benefit_issuances"), rows(jdbc, "outbox")));
                }
            } finally {
                dropSchema(admin, schema);
            }
        }
    }

    @Test
    void recordedCompensationIssuesOnceAcrossConcurrentRequestsAndServiceRestart() throws Exception {
        String schema = "wallet_compensation_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                 .connectTimeout(Duration.ofSeconds(5)).build();
             var admin = DriverManager.getConnection(requiredEnvironment("TEST_DATABASE_URL"))) {
            IssueBenefitCommand command = recordedCommand(client);
            String body = mapper.writeValueAsString(command);
            try (var statement = admin.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            try {
                JsonNode issued;
                PersistedState afterIssuance;
                PromotionRepository previousRepository;
                WalletPromotionService previousService;
                try (var context = start(schema)) {
                    var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    assertEquals(schema, jdbc.queryForObject("SELECT current_schema()", String.class));
                    assertEquals(List.of("001", "002", "003", "004", "005", "006", "007"),
                        jdbc.queryForList("SELECT version FROM schema_migrations ORDER BY version", String.class));
                    issued = concurrentIssue(client, endpoint(context), body, command.caseId(), schema, admin);
                    afterIssuance = assertSingleIssuance(jdbc, command, issued);
                    assertEquals(CONCURRENCY, jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class));
                    previousRepository = context.getBean(PromotionRepository.class);
                    previousService = context.getBean(WalletPromotionService.class);
                    evidence("concurrent", schema, command, issued);
                }
                try (var restarted = start(schema)) {
                    assertNotSame(previousRepository, restarted.getBean(PromotionRepository.class));
                    assertNotSame(previousService, restarted.getBean(WalletPromotionService.class));
                    var jdbc = new JdbcTemplate(restarted.getBean(DataSource.class));
                    URI endpoint = endpoint(restarted);
                    JsonNode replay = successfulIssue(client, endpoint, body);
                    assertEquals(issued, replay);
                    assertEquals(afterIssuance, assertSingleIssuance(jdbc, command, replay));
                    assertEquals(CONCURRENCY + 1, jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class));
                    evidence("restart replay", schema, command, replay);

                    // Change only the approved amount for the requested conflict probe.
                    var changed = mapper.valueToTree(command).deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.required("amount"))
                        .put("minorUnits", Math.addExact(command.amount().minorUnits(), 1));
                    var conflict = post(client, endpoint, changed.toString());
                    assertEquals(409, conflict.statusCode(), conflict.body());
                    assertEquals("CONFLICT", mapper.readTree(conflict.body()).required("code").asText());
                    assertEquals(afterIssuance, assertSingleIssuance(jdbc, command, issued));
                    assertEquals(CONCURRENCY + 1, jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class));
                    assertEquals(issued, successfulIssue(client, endpoint, body));
                    assertEquals(afterIssuance, assertSingleIssuance(jdbc, command, issued));
                    evidence("changed amount HTTP 409", schema, command, issued);
                }
            } finally {
                dropSchema(admin, schema);
            }
        }
    }

    private static void dropSchema(Connection admin, String schema) throws Exception {
        try (var statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
            try (var remaining = statement.executeQuery(
                "SELECT count(*) FROM pg_namespace WHERE nspname = '" + schema + "'")) {
                assertTrue(remaining.next());
                assertEquals(0, remaining.getInt(1));
            }
        }
        System.out.println("Compensation test schema removed: " + schema);
    }

    private IssueBenefitCommand recordedCommand(HttpClient client) throws Exception {
        var recorded = mapper.readTree(Files.readString(Path.of(requiredEnvironment("TEST_WALLET_COMPENSATION_CASE"))));
        assertEquals("COMPENSATION", recorded.required("caseType").asText());
        var decision = recorded.required("decision");
        assertEquals("COMPENSATION", decision.required("kind").asText());
        assertTrue(decision.required("eligible").asBoolean());
        String caseId = text(recorded, "caseId");
        String orderId = text(recorded, "journeyOrderId");
        var orderResponse = client.send(HttpRequest.newBuilder(URI.create(
                requiredEnvironment("TEST_JOURNEY_ORDER_URL") + "/api/v1/journey-orders/" + orderId))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, orderResponse.statusCode(), orderResponse.body());
        var order = mapper.readTree(orderResponse.body());
        assertEquals(orderId, text(order, "orderId"));
        String accountId = text(order, "accountId");
        assertEquals(text(recorded, "actorRef"), accountId);
        var amount = decision.required("refundableAmount");
        assertTrue(amount.required("minorUnits").isIntegralNumber());
        Money money = new Money(text(amount, "currency"), amount.required("minorUnits").longValue());
        assertTrue(money.minorUnits() > 0);
        // Anchor validity to the recorded quote and the executor's 30 day policy.
        Instant validFrom = Instant.parse(text(decision, "quotedAt"));
        return new IssueBenefitCommand(accountId, BenefitType.COMPENSATION_CREDIT, BalanceType.PROMOTION_CREDIT,
            money, IssuanceSource.POST_SALES_COMP, caseId, new ApplicableScope(BenefitScopeType.ANY_TRIP, null, money.currency()),
            new RedemptionRule(false, null, false), new RevocationRule(null), validFrom, validFrom.plus(Duration.ofDays(30)),
            new BusinessReason(ReasonType.POST_SALES_COMP, text(recorded, "reasonCode"), "POST_SALES_CASE", caseId, null));
    }

    private JsonNode concurrentIssue(HttpClient client, URI endpoint, String body, String caseId,
                                     String schema, Connection admin) throws Exception {
        var ready = new CountDownLatch(CONCURRENCY);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(CONCURRENCY)) {
            var calls = new ArrayList<Future<JsonNode>>();
            admin.setAutoCommit(false);
            try {
                // Hold the same database lock until all eight service transactions contend.
                try (var lock = admin.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    lock.setString(1, "wallet-compensation:" + caseId);
                    lock.execute();
                }
                for (int index = 0; index < CONCURRENCY; index++) {
                    calls.add(workers.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return successfulIssue(client, endpoint, body);
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                awaitBlockedTransactions(admin, schema);
                admin.commit();
            } finally {
                start.countDown();
                admin.rollback();
                admin.setAutoCommit(true);
            }
            JsonNode first = calls.getFirst().get(30, TimeUnit.SECONDS);
            for (var call : calls) {
                assertEquals(first, call.get(30, TimeUnit.SECONDS));
            }
            return first;
        }
    }

    private static void awaitBlockedTransactions(Connection admin, String schema) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        int blocked = 0;
        try (var query = admin.prepareStatement("""
            SELECT count(*) FROM pg_locks locks
            JOIN pg_stat_activity activity ON activity.pid = locks.pid
            WHERE locks.locktype = 'advisory' AND NOT locks.granted AND activity.application_name = ?
            """)) {
            query.setString(1, schema);
            while (System.nanoTime() < deadline) {
                // Refresh activity statistics while the lock holding transaction remains open.
                try (var refresh = admin.createStatement()) {
                    refresh.execute("SELECT pg_stat_clear_snapshot()");
                }
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    blocked = rows.getInt(1);
                }
                if (blocked == CONCURRENCY) {
                    System.out.println("Concurrent PostgreSQL transactions waiting for case lock: " + blocked);
                    return;
                }
                Thread.sleep(25);
            }
        }
        assertEquals(CONCURRENCY, blocked, "All requests must reach the database concurrently");
    }

    private JsonNode successfulIssue(HttpClient client, URI endpoint, String body) throws Exception {
        var response = post(client, endpoint, body);
        assertEquals(201, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private static HttpResponse<String> post(HttpClient client, URI endpoint, String body) throws Exception {
        // A new transport key ensures every request exercises durable case deduplication.
        return client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json").header("Idempotency-Key", UuidV7.generate())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private PersistedState assertSingleIssuance(JdbcTemplate jdbc, IssueBenefitCommand command, JsonNode issued) throws Exception {
        var benefits = rows(jdbc, "promotion_instrument_snapshots");
        var wallets = rows(jdbc, "wallet_account_snapshots");
        var ledgers = rows(jdbc, "wallet_ledger_entries");
        var issuances = rows(jdbc, "benefit_issuances");
        var events = rows(jdbc, "outbox");
        assertEquals(1, benefits.size());
        assertEquals(1, wallets.size());
        assertEquals(1, ledgers.size());
        assertEquals(1, issuances.size());
        assertEquals(1, events.size());
        var benefit = mapper.readTree(benefits.getFirst()).required("data");
        assertEquals(issued, benefit);
        assertEquals(command.caseId(), text(benefit, "caseId"));
        assertEquals(command.accountId(), text(benefit, "accountId"));
        assertEquals("POST_SALES_COMP", text(benefit, "issuanceSource"));
        assertEquals("COMPENSATION_CREDIT", text(benefit, "benefitType"));
        assertEquals("PROMOTION_CREDIT", text(benefit, "balanceType"));
        assertEquals(command.amount(), mapper.treeToValue(benefit.required("issuedAmount"), Money.class));
        var issuance = mapper.readTree(issuances.getFirst());
        assertEquals(command.issuanceKey(), text(issuance, "issuance_key"));
        assertEquals(text(issued, "benefitId"), text(issuance, "benefit_id"));
        assertEquals(command, mapper.treeToValue(issuance.required("request"), IssueBenefitCommand.class));
        var wallet = mapper.readTree(wallets.getFirst()).required("data");
        assertEquals(command.accountId(), text(wallet, "accountId"));
        assertEquals(text(issued, "walletAccountId"), text(wallet, "walletAccountId"));
        assertEquals(1, wallet.required("version").asLong());
        assertEquals(1, wallet.required("balances").size());
        assertEquals(command.amount(), mapper.treeToValue(wallet.required("balances").get(0).required("availableBalance"), Money.class));
        var ledger = mapper.readTree(ledgers.getFirst());
        assertEquals(text(issued, "benefitId"), text(ledger, "benefit_id"));
        assertEquals(text(wallet, "walletAccountId"), text(ledger, "wallet_account_id"));
        assertEquals(text(wallet, "lastLedgerEntryId"), text(ledger, "ledger_entry_id"));
        assertEquals(command.amount(), mapper.treeToValue(ledger.required("data").required("availableDelta"), Money.class));
        var event = mapper.readTree(events.getFirst()).required("envelope");
        assertEquals("BenefitIssued", text(event, "eventType"));
        var payload = event.required("payload");
        assertEquals(command.caseId(), text(payload, "caseId"));
        assertEquals(command.accountId(), text(payload, "accountId"));
        assertEquals(text(issued, "benefitId"), text(payload, "benefitId"));
        assertEquals(command.amount(), mapper.treeToValue(payload.required("issuedAmount"), Money.class));
        assertEquals(ledger.required("data"), payload.required("walletBalanceDelta"));
        return new PersistedState(benefits, wallets, ledgers, issuances, events);
    }

    private static List<String> rows(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT to_jsonb(record)::text FROM " + table + " record", String.class);
    }

    private static String text(JsonNode node, String field) {
        var value = node.required(field);
        assertTrue(value.isTextual(), field);
        assertFalse(value.textValue().isBlank(), field);
        return value.textValue();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required for the live compensation test");
        }
        return value;
    }

    private static ConfigurableApplicationContext start(String schema) {
        return SpringApplication.run(LiveConfiguration.class, "--server.port=0", "--server.address=127.0.0.1",
            "--wallet-test.schema=" + schema, "--spring.main.banner-mode=off",
            "--server.tomcat.basedir=" + Path.of("target", schema).toAbsolutePath());
    }

    private static URI endpoint(ConfigurableApplicationContext context) {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port + "/api/v1/benefits");
    }

    private static void evidence(String phase, String schema, IssueBenefitCommand command, JsonNode issued) {
        System.out.printf("Compensation evidence: phase=%s schema=%s caseId=%s accountId=%s amount=%s %d benefitId=%s benefits=1 ledgers=1 BenefitIssued=1%n",
            phase, schema, command.caseId(), command.accountId(), command.amount().currency(), command.amount().minorUnits(),
            text(issued, "benefitId"));
    }

    private record PersistedState(List<String> benefits, List<String> wallets, List<String> ledgers,
                                  List<String> issuances, List<String> events) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @Import(WalletPromotionController.class)
    static class LiveConfiguration {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource(@Value("${wallet-test.schema}") String schema) {
            var config = new HikariConfig();
            config.setJdbcUrl(requiredEnvironment("TEST_DATABASE_URL"));
            config.setSchema(schema);
            config.setMaximumPoolSize(CONCURRENCY);
            config.setConnectionTimeout(10000);
            config.setConnectionInitSql("SET statement_timeout = '25s'");
            config.addDataSourceProperty("ApplicationName", schema);
            return new HikariDataSource(config);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        MigrationRunner migrationRunner(DataSource dataSource, PlatformTransactionManager transactionManager) {
            var runner = new MigrationRunner(dataSource, transactionManager);
            runner.run(Path.of("migrations"));
            return runner;
        }

        @Bean
        @DependsOn("migrationRunner")
        PromotionRepository promotionRepository(DataSource dataSource, ObjectMapper mapper) {
            return new PostgresPromotionRepository(dataSource, mapper, new OutboxAppender(dataSource, mapper));
        }

        @Bean
        WalletPromotionService walletPromotionService(PromotionRepository repository) {
            return new WalletPromotionService(repository, (benefit, useReference, businessReason) -> { });
        }

        @Bean
        IdempotencyStore idempotencyStore(DataSource dataSource, ObjectMapper mapper) {
            return new DbIdempotencyStore(dataSource, mapper);
        }
    }
}
