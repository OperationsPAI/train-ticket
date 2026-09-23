package com.trainticket.bookingorchestration.infrastructure.persistence;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.bookingorchestration.application.*;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="TEST_CAPACITY_REPLAY_SOURCE",matches=".+")
class CapacityReplayWriteLiveTest {
    @Test void repeatedCapacityFactDoesNotRewriteBookingSnapshot() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var source=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CAPACITY_REPLAY_SOURCE"))));
        String schema="capacity_replay_"+UUID.randomUUID().toString().replace("-","");
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(2);config.setConnectionInitSql("SET search_path TO "+schema);
        try(var pool=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(pool);jdbc.execute("CREATE SCHEMA "+schema);
            try {
                jdbc.execute(Files.readString(Path.of("migrations/001_booking_orchestration_persistence.sql")));
                for(String type:java.util.List.of("booking","saga")) {
                    var row=source.path(type);String table=type.equals("booking")?"segment_booking_snapshots":"booking_saga_snapshots";
                    jdbc.update("INSERT INTO "+table+"(id,version,data) VALUES(?,?,?::jsonb)",row.path("id").asText(),row.path("version").asLong(),row.path("data").toString());
                }
                var service=new BookingOrchestrationService(Clock.systemUTC(),new TransactionalOutboxEventPublisher(new OutboxAppender(pool,mapper),mapper),new PostgresBookingSagaRepository(pool,mapper),new PostgresSegmentBookingRepository(pool,mapper),new PostgresPaymentIntentSagaReferenceRepository(pool),new PostgresConsumedEventRepository(pool));
                var tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
                var event=mapper.treeToValue(source.path("event"),EventEnvelope.class);
                for(int attempt=0;attempt<3;attempt++) {
                    var delivery=new EventEnvelope(com.trainticket.platformkit.messaging.PrefixedIds.newEventId(),event.eventType(),event.occurredAt(),event.correlationId(),event.causationId(),event.producer(),event.schemaVersion(),event.payload());
                    assertInstanceOf(HandlerResult.Success.class,tx.execute(status->service.handleUpstreamEvent(delivery)));
                }
                assertEquals(source.path("booking").path("version").asLong(),jdbc.queryForObject("SELECT version FROM segment_booking_snapshots WHERE id=?",Long.class,source.path("booking").path("id").asText()));
                assertEquals(source.path("booking").path("data"),mapper.readTree(jdbc.queryForObject("SELECT data::text FROM segment_booking_snapshots WHERE id=?",String.class,source.path("booking").path("id").asText())));
            } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
