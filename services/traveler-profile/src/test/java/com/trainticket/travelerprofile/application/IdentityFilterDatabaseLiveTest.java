package com.trainticket.travelerprofile.application;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.persistence.*;
import com.trainticket.travelerprofile.infrastructure.persistence.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@EnabledIfEnvironmentVariable(named="TEST_IDENTITY_FILTER_EVENTS",matches=".+")
class IdentityFilterDatabaseLiveTest {
    @Test
    void unrelatedIdentityEventsDoNotAcquireDatabaseConnections() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var events=new ArrayList<EventEnvelope>();
        for(var raw:mapper.readTree(Files.readString(Path.of(System.getenv("TEST_IDENTITY_FILTER_EVENTS")))))events.add(mapper.readValue(raw.get(1).get(1).asText(),EventEnvelope.class));
        String schema="traveler_filter_"+UUID.randomUUID().toString().replace("-","");
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(1);config.setConnectionInitSql("SET search_path TO "+schema);
        try(var pool=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(pool);jdbc.execute("CREATE SCHEMA "+schema);
            try {
                try(var files=Files.list(Path.of("migrations"))){for(var file:files.sorted().toList())jdbc.execute(Files.readString(file));}
                var service=new TravelerProfileService(new PostgresTravelerProfileStore(pool,mapper),new DbIdempotencyStore(pool,mapper),new OutboxAppender(pool,mapper)::append,mapper);
                var identity=new IdentityVerificationEventHandler(service,mapper);
                var handler=new DeduplicatingEventHandler(new PostgresConsumedEventLog(pool),identity,new DataSourceTransactionManager(pool));
                var ignored=events.stream().filter(event->!identity.handles(event)).toList();
                assertTrue(ignored.size()>10);
                try(var held=pool.getConnection()) {
                    assertTimeoutPreemptively(Duration.ofSeconds(1),()->{for(var event:ignored)assertEquals(EventSubscriber.HandlerResult.SUCCESS,handler.handle(event));});
                }
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM processed_events",Integer.class));
                var actual=events.stream().filter(identity::handles).findFirst().orElseThrow();
                for(int attempt=0;attempt<2;attempt++)assertEquals(EventSubscriber.HandlerResult.SUCCESS,handler.handle(actual));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processed_events",Integer.class));
            } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
