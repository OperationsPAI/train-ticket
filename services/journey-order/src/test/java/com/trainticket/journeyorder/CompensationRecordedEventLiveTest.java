package com.trainticket.journeyorder;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.journeyorder.infrastructure.identity.HttpIdentityVerificationAdapter;
import com.trainticket.journeyorder.infrastructure.offer.HttpOfferPriceAdapter;
import com.trainticket.journeyorder.infrastructure.persistence.PostgresJourneyOrderStateRepository;
import com.trainticket.journeyorder.infrastructure.persistence.TransactionalOutboxEventPublisher;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.persistence.MigrationRunner;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompensationRecordedEventLiveTest {
    @Test
    void recordedCompensationPreservesOrderItemsTimelineAndVersionAcrossReplay() throws Exception {
        verifyPreserved("/compensation-recorded.json");
    }

    @Test
    void recordedWalletCompensationPreservesConfirmedTravelersAndCommercialItems() throws Exception {
        verifyPreserved("/wallet-compensation-recorded.json");
    }

    private void verifyPreserved(String resource) throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var source = mapper.readTree(getClass().getResourceAsStream(resource));
        var snapshot = source.required("order");
        var approved = mapper.treeToValue(source.required("approved"), EventEnvelope.class);
        var applied = mapper.treeToValue(source.required("applied"), EventEnvelope.class);
        var recordedCase = source.required("case");
        assertEquals("COMPENSATION", recordedCase.required("caseType").asText());
        assertEquals("APPLIED", recordedCase.required("status").asText());
        assertEquals("psc-" + recordedCase.required("caseId").asText(), source.required("applied").at("/payload/caseId").asText());
        assertEquals(recordedCase.required("journeyOrderId"), snapshot.required("id"));
        assertEquals("COMPENSATION", source.required("approved").at("/payload/approvedActions/decisionKind").asText());
        assertEquals("COMPENSATION", source.required("applied").at("/payload/caseType").asText());
        assertEquals(snapshot.required("id"), source.required("applied").at("/payload/orderId"));
        assertEquals(source.required("approved").at("/payload/caseId"), source.required("applied").at("/payload/caseId"));
        if (source.required("approved").at("/payload/approvedActions").has("compensation")) {
            assertEquals("CONFIRMED", snapshot.required("data").required("state").asText());
            assertEquals(2, snapshot.required("data").required("travelers").size());
            for (var item : snapshot.required("data").required("orderItems")) assertFalse(item.required("cancelled").asBoolean());
        }
        String schema = "order_compensation_" + UUID.randomUUID().toString().replace("-", "");
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("SET search_path TO " + schema);
        config.addDataSourceProperty("options", "-c statement_timeout=5000 -c lock_timeout=2000");
        try (var pool = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(pool);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                var manager = new DataSourceTransactionManager(pool);
                new MigrationRunner(pool, manager).run(Path.of("migrations"));
                assertEquals(schema, jdbc.queryForObject("SELECT current_schema()", String.class));
                jdbc.update("INSERT INTO journey_order_snapshots(id,version,data) VALUES (?,?,?::jsonb)",
                    snapshot.required("id").asText(), snapshot.required("version").asLong(), snapshot.required("data").toString());
                var before = jdbc.queryForList("SELECT to_jsonb(o)::text FROM journey_order_snapshots o ORDER BY id", String.class);
                var tx = new TransactionTemplate(manager);
                for (int restart = 0; restart < 2; restart++) {
                    var handler = new OrderManagementService(
                        new TransactionalOutboxEventPublisher(new OutboxAppender(pool, mapper), mapper), Clock.systemUTC(),
                        new PostgresJourneyOrderStateRepository(pool, mapper),
                        new HttpIdentityVerificationAdapter(mapper, RestClient.builder()), new HttpOfferPriceAdapter(mapper, RestClient.builder()));
                    for (var event : new EventEnvelope[]{applied, approved, approved, applied}) {
                        assertEquals(new EventSubscriber.Success(), tx.execute(status -> handler.handle(event)));
                        assertEquals(before, jdbc.queryForList("SELECT to_jsonb(o)::text FROM journey_order_snapshots o ORDER BY id", String.class));
                        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
                    }
                }
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
                System.out.printf("Compensation order: case=%s version=%d snapshot=unchanged outbox=0 replay=stable%n",
                    source.required("applied").at("/payload/caseId").asText(), snapshot.required("version").asLong());
            } finally {
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
