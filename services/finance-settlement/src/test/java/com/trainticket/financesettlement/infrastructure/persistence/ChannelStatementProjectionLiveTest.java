package com.trainticket.financesettlement.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.trainticket.financesettlement.application.ChannelStatementProjection;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The monotonic half of the channel-statement projection, against a real
 * PostgreSQL.
 *
 * <p>payment-channel writes the generation and the freeze to its outbox in one
 * relay pass, and the consumer group runs one consumer per replica, so the two
 * announcements are applied in whichever order the replicas reach them. The
 * guard cannot live in the handler alone: two replicas can both read a row that
 * does not exist yet and then write, so the last writer decided the status --
 * measured at revision 509, a statement frozen through the API was stored
 * GENERATED with a null frozen_at. The upsert has to make the decision itself.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ChannelStatementProjectionLiveTest {
    @Test
    void aGenerationWrittenAfterItsFreezeDoesNotUnfreezeTheRow() throws Exception {
        String schema = "finance_statement_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("SET search_path TO " + schema);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                new MigrationRunner(pool, new DataSourceTransactionManager(pool)).run(Path.of("migrations"));
                PostgresFinanceSettlementProjectionRepository projections = new PostgresFinanceSettlementProjectionRepository(pool);

                projections.saveChannelStatement(statement("FROZEN", Instant.parse("2026-07-02T00:10:00Z")));
                // The interleaving this guards: the generation lands last.
                projections.saveChannelStatement(statement("GENERATED", null));

                ChannelStatementProjection stored = projections.findChannelStatement("chs-live-1").orElseThrow();
                assertEquals("FROZEN", stored.status());
                assertNotNull(stored.frozenAt());
                assertEquals(Instant.parse("2026-07-02T00:00:04Z"), stored.generatedAt());
                assertEquals(Money.of("CNY", "1234.56"), stored.grossPaymentAmount());
                System.out.println("Channel statement projection: a generation written after its freeze left the row frozen");
            } finally {
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static ChannelStatementProjection statement(String status, Instant frozenAt) {
        return new ChannelStatementProjection(
            "chs-live-1",
            "ALIPAY_SIM",
            "2026-07-02",
            "CNY",
            "v1",
            342648,
            Money.of("CNY", "1234.56"),
            Money.of("CNY", "45.00"),
            Money.zero(java.util.Currency.getInstance("CNY")),
            "sha256:abc",
            status,
            Instant.parse("2026-07-02T00:00:04Z"),
            frozenAt,
            status.equals("FROZEN") ? "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e301" : "evt-0194f2e0-7b3e-7610-8284-5c26e8b0e302"
        );
    }
}
