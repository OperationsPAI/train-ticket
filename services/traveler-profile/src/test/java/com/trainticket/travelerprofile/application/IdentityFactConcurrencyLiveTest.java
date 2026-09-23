package com.trainticket.travelerprofile.application;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.platformkit.persistence.*;
import com.trainticket.travelerprofile.infrastructure.persistence.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="TEST_TRAVELER_SNAPSHOT",matches=".+")
class IdentityFactConcurrencyLiveTest {
    @Test
    void concurrentActualIdentityFactsPreserveBothUpdates() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var source=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_TRAVELER_SNAPSHOT"))));
        String schema="traveler_concurrency_"+UUID.randomUUID().toString().replace("-","");
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(4);config.setConnectionInitSql("SET search_path TO "+schema);
        try(var pool=new HikariDataSource(config);var workers=Executors.newFixedThreadPool(2)) {
            var jdbc=new JdbcTemplate(pool);jdbc.execute("CREATE SCHEMA "+schema);
            try {
                try(var files=Files.list(Path.of("migrations"))){for(var file:files.sorted().toList())jdbc.execute(Files.readString(file));}
                jdbc.update("INSERT INTO traveler_profile_snapshots(id,version,data) VALUES(?,?,?::jsonb)",source.path("id").asText(),source.path("version").asLong(),source.path("data").toString());
                var store=new PostgresTravelerProfileStore(pool,mapper);
                var service=new TravelerProfileService(store,new DbIdempotencyStore(pool,mapper),new OutboxAppender(pool,mapper)::append,mapper);
                String id=source.path("data").path("travelerId").asText();
                var state=store.findByTravelerId(id).orElseThrow();
                var facts=state.aggregate().verificationFacts();
                assertTrue(facts.size()>=2);
                var tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
                var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
                var futures=new java.util.ArrayList<Future<Boolean>>();
                for(int index=0;index<2;index++) {
                    var fact=facts.get(index);
                    futures.add(workers.submit(()->{ready.countDown();start.await();return tx.execute(status->service.recordIdentityVerificationFact(id,fact));}));
                }
                assertTrue(ready.await(2,TimeUnit.SECONDS));start.countDown();
                for(var future:futures)assertTrue(future.get(5,TimeUnit.SECONDS));
                var restored=store.findByTravelerId(id).orElseThrow();
                assertEquals(state.aggregate().version()+2,restored.aggregate().version());
                assertTrue(restored.aggregate().verificationFacts().containsAll(facts));
            } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
