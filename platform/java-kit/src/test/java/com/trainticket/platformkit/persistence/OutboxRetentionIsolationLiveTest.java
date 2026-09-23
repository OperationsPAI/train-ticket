package com.trainticket.platformkit.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.LettuceRedisStreamOperations;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.RedisClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="TEST_OUTBOX_EVENTS",matches=".+")
class OutboxRetentionIsolationLiveTest {
    @Test
    void blockedRetentionDoesNotDelayActualEventPublication() throws Exception {
        String schema="relay_isolation_"+UUID.randomUUID().toString().replace("-","");
        String stream="events:"+schema;
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(4);
        config.setConnectionInitSql("SET search_path TO "+schema);
        var redis=RedisClient.create(System.getenv("TEST_REDIS_URL"));
        try(var pool=new HikariDataSource(config);var connection=redis.connect()) {
            var jdbc=new JdbcTemplate(pool);
            jdbc.execute("CREATE SCHEMA "+schema);
            try {
                jdbc.execute(Files.readString(Path.of("../../services/post-sales/migrations/001_post_sales_persistence.sql")));
                var events=new ObjectMapper().readTree(Files.readString(Path.of(System.getenv("TEST_OUTBOX_EVENTS"))));
                var event=new ObjectMapper().readTree(events.get(0).get(1).get(1).asText());
                assertTrue(event.path("eventId").isTextual());
                try(var relay=new OutboxRelay(jdbc,new LettuceRedisStreamOperations(connection),Duration.ofMillis(50));
                    var blocker=pool.getConnection()) {
                    blocker.setAutoCommit(false);
                    blocker.createStatement().execute("LOCK TABLE idempotency_records IN SHARE MODE");
                    for(int poll=0;poll<20;poll++)relay.pollOnceAndSweep();
                    long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
                    int waiting=0;
                    while(waiting==0 && System.nanoTime()<deadline) {
                        waiting=jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE relation=?::regclass AND NOT granted",Integer.class,schema+".idempotency_records");
                        Thread.sleep(10);
                    }
                    assertEquals(1,waiting,"retention must be blocked on the actual database table");
                    jdbc.update("INSERT INTO outbox(event_id,stream,envelope) VALUES (?,?,?::jsonb)",event.path("eventId").asText(),stream,event.toString());
                    assertTimeoutPreemptively(Duration.ofSeconds(1),relay::pollOnceAndSweep);
                    var delivered=connection.sync().xrange(stream,io.lettuce.core.Range.unbounded());
                    assertEquals(1,delivered.size());
                    assertEquals(event,new ObjectMapper().readTree(delivered.getFirst().getBody().get("envelope")));
                    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL",Integer.class));
                    for(int poll=0;poll<40;poll++)relay.pollOnceAndSweep();
                    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE relation=?::regclass AND NOT granted",Integer.class,schema+".idempotency_records"));
                    assertEquals(1,connection.sync().xlen(stream));
                    blocker.rollback();
                }
            } finally {
                connection.sync().del(stream);
                jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
            }
        } finally {
            redis.shutdown();
        }
    }
}
