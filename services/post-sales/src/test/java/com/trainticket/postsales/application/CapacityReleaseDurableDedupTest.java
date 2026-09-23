package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.postsales.adapters.http.FarePricingAdjustmentQuoteClient;
import com.trainticket.postsales.infrastructure.persistence.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named="TEST_CAPACITY_DEDUP_EVIDENCE",matches=".+")
class CapacityReleaseDurableDedupTest {
    @Test
    void completedAndUnrelatedReleasesDoNotWriteDeduplicationOrSnapshots() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var evidence=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CAPACITY_DEDUP_EVIDENCE"))));
        String schema="capacity_dedup_"+UUID.randomUUID().toString().replace("-","");
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(3);config.setConnectionInitSql("SET search_path TO "+schema);
        try(var pool=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(pool);jdbc.execute("CREATE SCHEMA "+schema);
            try {
                try(var files=Files.list(Path.of("migrations"))){for(var file:files.sorted().toList())jdbc.execute(Files.readString(file));}
                var repository=new PostgresPostSalesRepository(pool,mapper);
                var service=new PostSalesApplicationService(repository,new OutboxAppender(pool,mapper)::append,
                    new FarePricingAdjustmentQuoteClient("http://localhost:18001","http://localhost:18001","http://localhost:18001",RestClient.builder()),
                    new PostgresPostSalesPolicyContextStore(pool,mapper),new PostgresPostSalesExternalEventProjectionStore(pool,mapper),Clock.systemUTC());
                var handler=new PostSalesEventHandler(new PostgresConsumedEventLog(pool),service,new PostSalesExternalEventPolicy(service),new DataSourceTransactionManager(pool));
                try(var blocker=pool.getConnection()) {
                    blocker.setAutoCommit(false);
                    blocker.createStatement().execute("LOCK TABLE processed_events IN SHARE MODE");
                    for(var raw:evidence.path("events")) {
                        var event=envelope(raw,mapper);
                        assertTimeoutPreemptively(Duration.ofSeconds(1),()->assertEquals(EventSubscriber.HandlerResult.SUCCESS,handler.handle(event)));
                    }
                    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM processed_events",Integer.class));
                    var snapshot=evidence.path("snapshot");
                    jdbc.update("INSERT INTO post_sales_case_snapshots(id,version,data) VALUES (?,?,?::jsonb)",snapshot.path("id").asText(),snapshot.path("version").asLong(),snapshot.path("data").toString());
                    for(int attempt=0;attempt<3;attempt++)for(var raw:evidence.path("events")) {
                        var event=envelope(raw,mapper);
                        assertTimeoutPreemptively(Duration.ofSeconds(1),()->assertEquals(EventSubscriber.HandlerResult.SUCCESS,handler.handle(event)));
                    }
                    assertEquals(snapshot.path("version").asLong(),repository.findById(snapshot.path("id").asText()).orElseThrow().version());
                    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class));
                    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM processed_events",Integer.class));
                    blocker.rollback();
                }
            } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    private static EventEnvelope envelope(com.fasterxml.jackson.databind.JsonNode recorded,ObjectMapper mapper) {
        return new EventEnvelope(PrefixedIds.newEventId(),recorded.path("eventType").asText(),java.time.Instant.parse(recorded.path("occurredAt").asText()),
            PrefixedIds.newCorrelationId(),PrefixedIds.newCommandId(),"capacity-availability",1,mapper.convertValue(recorded.path("payload"),java.util.Map.class));
    }
}
