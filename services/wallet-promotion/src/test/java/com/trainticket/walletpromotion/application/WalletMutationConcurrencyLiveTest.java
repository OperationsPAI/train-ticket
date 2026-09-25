package com.trainticket.walletpromotion.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.walletpromotion.domain.ApplicableScope;
import com.trainticket.walletpromotion.domain.BalanceType;
import com.trainticket.walletpromotion.domain.BenefitReservationStatus;
import com.trainticket.walletpromotion.domain.BenefitScopeType;
import com.trainticket.walletpromotion.domain.BenefitType;
import com.trainticket.walletpromotion.domain.BusinessReason;
import com.trainticket.walletpromotion.domain.IssuanceSource;
import com.trainticket.walletpromotion.domain.Money;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.PromotionStatus;
import com.trainticket.walletpromotion.domain.ReasonType;
import com.trainticket.walletpromotion.domain.RedemptionRule;
import com.trainticket.walletpromotion.domain.RevocationRule;
import com.trainticket.walletpromotion.infrastructure.persistence.PostgresPromotionRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class WalletMutationConcurrencyLiveTest {
    private static final int CONCURRENCY = 16;
    private static final Instant START = Instant.parse("2026-09-25T04:00:00Z");

    @Test
    void concurrentIssuanceReplaysAndDistinctCreditsSerializePerWallet() throws Exception {
        withSchema((context, jdbc) -> {
            WalletPromotionService service = context.getBean(WalletPromotionService.class);
            IssueBenefitCommand compensation = issue(
                "acc-concurrent", IssuanceSource.POST_SALES_COMP, "psc-concurrent", null, 100,
                reason(ReasonType.POST_SALES_COMP, "APPROVED_COMPENSATION", "POST_SALES_CASE", "psc-concurrent")
            );

            List<PromotionInstrument> replayed = concurrent(CONCURRENCY, () -> service.issue(compensation));
            assertEquals(1, replayed.stream().map(PromotionInstrument::benefitId).distinct().count());
            assertCounts(jdbc, 1, 1, 1, 1);
            assertWallet(jdbc, "acc-concurrent", 100, 0, 0, 1);

            List<Callable<PromotionInstrument>> distinct = new ArrayList<>();
            for (int index = 0; index < CONCURRENCY; index++) {
                int number = index;
                distinct.add(() -> service.issue(issue(
                    "acc-concurrent", IssuanceSource.MANUAL_OPS, null, "issue-" + number, 10,
                    reason(ReasonType.MANUAL_OPS, "CREDIT-" + number, "MANUAL_ACTION", "act-" + number)
                )));
            }
            List<PromotionInstrument> issued = concurrent(distinct);
            assertEquals(CONCURRENCY, issued.stream().map(PromotionInstrument::benefitId).distinct().count());
            assertCounts(jdbc, CONCURRENCY + 1, CONCURRENCY + 1, CONCURRENCY + 1, CONCURRENCY + 1);
            assertWallet(jdbc, "acc-concurrent", 260, 0, 0, CONCURRENCY + 1);
        });
    }

    @Test
    void concurrentLifecycleReplaysKeepAvailableReservedAndRedeemedConsistent() throws Exception {
        withSchema((context, jdbc) -> {
            WalletPromotionService service = context.getBean(WalletPromotionService.class);
            MutableClock clock = context.getBean(MutableClock.class);
            PromotionInstrument benefit = service.issue(issue(
                "acc-lifecycle", IssuanceSource.MANUAL_OPS, null, "lifecycle-issue", 100,
                reason(ReasonType.MANUAL_OPS, "LIFECYCLE", "MANUAL_ACTION", "act-lifecycle")
            ));

            ReserveBenefitCommand reserve = new ReserveBenefitCommand(
                money(60), "res-lifecycle", START.plus(Duration.ofMinutes(5)),
                WalletPromotionServiceTest.use("ord-lifecycle"),
                reason(ReasonType.ORDER_PURCHASE, "RESERVE", "ORDER", "ord-lifecycle")
            );
            List<PromotionInstrument> reservations = concurrent(CONCURRENCY, () -> service.reserve(benefit.benefitId(), reserve));
            assertTrue(reservations.stream().allMatch(result -> result.version() == 2));
            assertLifecycle(jdbc, benefit.benefitId(), "acc-lifecycle", PromotionStatus.RESERVED, 40, 60, 0, 2, 2, 2);
            assertEquals(1, count(jdbc, "benefit_reservations"));

            RedeemBenefitCommand redeem = new RedeemBenefitCommand(
                money(20), "red-lifecycle", "res-lifecycle", WalletPromotionServiceTest.use("ord-lifecycle"),
                reason(ReasonType.ORDER_PURCHASE, "REDEEM", "ORDER", "ord-lifecycle")
            );
            ReleaseBenefitCommand release = new ReleaseBenefitCommand(
                money(10), "res-lifecycle", "rel-lifecycle",
                reason(ReasonType.RESERVATION_TIMEOUT, "RELEASE", "ORDER", "ord-lifecycle")
            );
            List<Callable<Object>> mixed = new ArrayList<>();
            for (int index = 0; index < CONCURRENCY / 2; index++) {
                mixed.add(() -> service.redeem(benefit.benefitId(), redeem));
                mixed.add(() -> service.release(benefit.benefitId(), release));
            }
            concurrent(mixed);
            assertLifecycle(jdbc, benefit.benefitId(), "acc-lifecycle", PromotionStatus.RESERVED, 50, 30, 20, 4, 4, 4);
            assertEquals(1, count(jdbc, "benefit_redemptions"));
            assertEquals(1, count(jdbc, "reservation_releases"));

            clock.advance(Duration.ofMinutes(6));
            concurrent(CONCURRENCY, () -> { service.expireDueBenefits(); return true; });
            assertLifecycle(jdbc, benefit.benefitId(), "acc-lifecycle", PromotionStatus.RELEASED, 80, 0, 20, 5, 5, 5);
            JsonNode reservation = json(jdbc, "SELECT data::text FROM benefit_reservations WHERE benefit_id=?", benefit.benefitId());
            assertEquals(BenefitReservationStatus.EXPIRED.name(), reservation.required("status").asText());
            assertEquals(2, count(jdbc, "reservation_releases"));

            String redemptionId = json(jdbc, "SELECT data::text FROM benefit_redemptions WHERE benefit_id=?", benefit.benefitId())
                .required("redemptionId").asText();
            ReverseRedemptionCommand reverse = new ReverseRedemptionCommand(
                redemptionId, "rev-lifecycle", money(5),
                reason(ReasonType.REVERSAL, "REVERSE", "ORDER", "ord-lifecycle")
            );
            concurrent(CONCURRENCY, () -> service.reverse(benefit.benefitId(), reverse));
            assertLifecycle(jdbc, benefit.benefitId(), "acc-lifecycle", PromotionStatus.REVERSED, 85, 0, 15, 6, 6, 6);
            assertEquals(1, count(jdbc, "redemption_reversals"));

            clock.advance(Duration.ofDays(2));
            concurrent(CONCURRENCY, () -> { service.expireDueBenefits(); return true; });
            assertLifecycle(jdbc, benefit.benefitId(), "acc-lifecycle", PromotionStatus.EXPIRED, 0, 0, 15, 7, 7, 7);

            ReverseRedemptionCommand afterExpiry = new ReverseRedemptionCommand(
                redemptionId, "rev-after-expiry", money(5),
                reason(ReasonType.REVERSAL, "REVERSE_AFTER_EXPIRY", "ORDER", "ord-lifecycle")
            );
            concurrent(CONCURRENCY, () -> service.reverse(benefit.benefitId(), afterExpiry));
            assertLifecycle(jdbc, benefit.benefitId(), "acc-lifecycle", PromotionStatus.EXPIRED, 0, 0, 10, 8, 8, 8);
            assertEquals(2, count(jdbc, "redemption_reversals"));
        });
    }

    private static IssueBenefitCommand issue(
        String accountId,
        IssuanceSource source,
        String caseId,
        String issuanceRef,
        long amount,
        BusinessReason reason
    ) {
        return new IssueBenefitCommand(
            accountId, BenefitType.BALANCE, BalanceType.PROMOTION_CREDIT, money(amount), source, caseId,
            issuanceRef, new ApplicableScope(BenefitScopeType.ANY_TRIP, null, "USD"),
            new RedemptionRule(false, null, false), new RevocationRule(null), START,
            START.plus(Duration.ofDays(1)), reason
        );
    }

    private static BusinessReason reason(ReasonType type, String code, String referenceType, String referenceId) {
        return new BusinessReason(type, code, referenceType, referenceId, null);
    }

    private static Money money(long amount) { return new Money("USD", amount); }

    private static void assertCounts(JdbcTemplate jdbc, int benefits, int issuances, int ledgers, int events) {
        assertEquals(benefits, count(jdbc, "promotion_instrument_snapshots"));
        assertEquals(issuances, count(jdbc, "benefit_issuances"));
        assertEquals(ledgers, count(jdbc, "wallet_ledger_entries"));
        assertEquals(events, count(jdbc, "outbox"));
    }

    private static void assertLifecycle(
        JdbcTemplate jdbc,
        String benefitId,
        String accountId,
        PromotionStatus status,
        long available,
        long reserved,
        long redeemed,
        long version,
        int ledgers,
        int events
    ) throws Exception {
        JsonNode benefit = json(jdbc, "SELECT data::text FROM promotion_instrument_snapshots WHERE id=?", benefitId);
        assertEquals(status.name(), benefit.required("status").asText());
        assertEquals(available, benefit.required("availableAmount").required("minorUnits").asLong());
        assertEquals(reserved, benefit.required("reservedAmount").required("minorUnits").asLong());
        assertEquals(redeemed, benefit.required("redeemedAmount").required("minorUnits").asLong());
        assertEquals(version, benefit.required("version").asLong());
        assertWallet(jdbc, accountId, available, reserved, redeemed, version);
        assertEquals(ledgers, count(jdbc, "wallet_ledger_entries"));
        assertEquals(events, count(jdbc, "outbox"));
    }

    private static void assertWallet(
        JdbcTemplate jdbc,
        String accountId,
        long available,
        long reserved,
        long redeemed,
        long version
    ) throws Exception {
        JsonNode wallet = json(jdbc, "SELECT data::text FROM wallet_account_snapshots WHERE data->>'accountId'=?", accountId);
        JsonNode balance = wallet.required("balances").get(0);
        assertEquals(available, balance.required("availableBalance").required("minorUnits").asLong());
        assertEquals(reserved, balance.required("reservedBalance").required("minorUnits").asLong());
        assertEquals(redeemed, balance.required("redeemedBalance").required("minorUnits").asLong());
        assertEquals(version, wallet.required("version").asLong());
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static JsonNode json(JdbcTemplate jdbc, String sql, Object... args) throws Exception {
        return new ObjectMapper().findAndRegisterModules().readTree(jdbc.queryForObject(sql, String.class, args));
    }

    private static <T> List<T> concurrent(int count, Callable<T> action) throws Exception {
        List<Callable<T>> calls = new ArrayList<>();
        for (int index = 0; index < count; index++) calls.add(action);
        return concurrent(calls);
    }

    private static <T> List<T> concurrent(List<? extends Callable<T>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(calls.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return call.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

    private static void withSchema(SchemaTest test) throws Exception {
        String schema = "wallet_concurrency_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(requiredEnvironment("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(LiveConfiguration.class)
                .web(WebApplicationType.NONE)
                .run("--wallet-test.schema=" + schema, "--spring.main.banner-mode=off")) {
                test.run(context, new JdbcTemplate(context.getBean(DataSource.class)));
            } finally {
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    @FunctionalInterface
    interface SchemaTest { void run(ConfigurableApplicationContext context, JdbcTemplate jdbc) throws Exception; }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(START);
        void advance(Duration duration) { current.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableTransactionManagement
    static class LiveConfiguration {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource(@Value("${wallet-test.schema}") String schema) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(requiredEnvironment("TEST_DATABASE_URL"));
            config.setSchema(schema);
            config.setMaximumPoolSize(CONCURRENCY + 4);
            config.setConnectionTimeout(10000);
            config.setConnectionInitSql("SET statement_timeout = '25s'");
            config.addDataSourceProperty("ApplicationName", schema);
            return new HikariDataSource(config);
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean MigrationRunner migrationRunner(DataSource dataSource, PlatformTransactionManager manager) {
            MigrationRunner runner = new MigrationRunner(dataSource, manager);
            runner.run(Path.of("migrations"));
            return runner;
        }

        @Bean @DependsOn("migrationRunner")
        PromotionRepository promotionRepository(DataSource dataSource, ObjectMapper mapper) {
            return new PostgresPromotionRepository(dataSource, mapper, new OutboxAppender(dataSource, mapper));
        }

        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean BenefitScopeVerifier scopeVerifier() { return (benefit, useReference, reason) -> { }; }
        @Bean WalletPromotionService walletPromotionService(PromotionRepository repository, BenefitScopeVerifier verifier, MutableClock clock) {
            return new WalletPromotionService(repository, verifier, clock);
        }

        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
    }
}
