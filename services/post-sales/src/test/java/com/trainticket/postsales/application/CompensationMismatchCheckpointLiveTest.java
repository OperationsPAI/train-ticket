package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.infrastructure.persistence.PostgresCompensationMismatchStore;
import com.trainticket.postsales.infrastructure.persistence.PostgresPostSalesRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches=".+")
class CompensationMismatchCheckpointLiveTest {
    @Test
    void mismatchSurvivesRestartAndPreventsAllFurtherPaymentOrWalletCalls() throws Exception {
        String schema="compensation_mismatch_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA "+schema);
            var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setConnectionInitSql("SET search_path TO "+schema);config.setMaximumPoolSize(8);
            try(var pool=new HikariDataSource(config)) {
                var jdbc=new JdbcTemplate(pool);
                jdbc.execute(Files.readString(Path.of("migrations/001_post_sales_persistence.sql")));
                jdbc.execute(Files.readString(Path.of("migrations/012_compensation_mismatch_checkpoints.sql")));
                var fixture=CompensationExecutionRecoveryTest.fixture();
                var repository=new PostgresPostSalesRepository(pool,fixture.mapper());
                fixture.caseAggregate().withVersion(0);
                repository.save(fixture.caseAggregate());
                AtomicInteger posts=new AtomicInteger();
                try(var server=new CompensationExecutionRecoveryTest.StubServer(fixture,true,posts)) {
                    var first=execution(repository,new PostgresCompensationMismatchStore(pool,fixture.mapper()),fixture.mapper(),server.base());
                    var failure=assertThrows(CompensationMismatchException.class,()->first.advance(fixture.caseId(),"corr-first"));
                    assertEquals("REFUND_AND_WALLET_CONFLICT",failure.checkpoint().mismatchCode());
                    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM compensation_mismatch_checkpoints",Integer.class));
                    assertEquals("ref-settled",jdbc.queryForObject("SELECT refund_evidence->'items'->0->>'refundId' FROM compensation_mismatch_checkpoints",String.class));
                    assertEquals(0,posts.get(),"mismatch detection must never issue another refund or wallet credit");

                    // New repository/store/service objects simulate a process restart. The durable checkpoint
                    // must short-circuit before any downstream request is attempted.
                    int requestsBefore=server.requestCount();
                    var restarted=execution(new PostgresPostSalesRepository(pool,fixture.mapper()),new PostgresCompensationMismatchStore(pool,fixture.mapper()),fixture.mapper(),server.base());
                    var replay=assertThrows(CompensationMismatchException.class,()->restarted.advance(fixture.caseId(),"corr-replay"));
                    assertEquals(failure.checkpoint().detectedAt(),replay.checkpoint().detectedAt());
                    assertEquals(requestsBefore,server.requestCount());

                    var original=new PostgresCompensationMismatchStore(pool,fixture.mapper()).find(fixture.caseId()).orElseThrow();
                    try(var workers=Executors.newFixedThreadPool(8)) {
                        var tasks=java.util.stream.IntStream.range(0,16).mapToObj(index->workers.submit(()->
                            new PostgresCompensationMismatchStore(pool,fixture.mapper()).record(new CompensationMismatchCheckpoint(
                                fixture.caseId(),"ord-comp-1","APPLIED","OTHER_"+index,"changed", "CNY",999,
                                fixture.mapper().createObjectNode(),fixture.mapper().createObjectNode(),fixture.mapper().createObjectNode(),fixture.mapper().createObjectNode(),
                                "corr-other",Instant.parse("2026-09-25T05:00:00Z"))))).toList();
                        for(var task:tasks)assertEquals(original,task.get());
                    }
                    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM compensation_mismatch_checkpoints",Integer.class));
                    assertEquals("REFUND_AND_WALLET_CONFLICT",jdbc.queryForObject("SELECT mismatch_code FROM compensation_mismatch_checkpoints",String.class));
                }
            } finally {admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }

    private static CompensationExecutionService execution(PostSalesRepository repository,CompensationMismatchStore mismatches,ObjectMapper mapper,String base){
        var cases=new PostSalesApplicationService(repository,ignored->{},ignored->Optional.empty(),new InMemoryPostSalesPolicyContextStore(),Clock.fixed(Instant.parse("2026-09-25T04:00:00Z"),ZoneOffset.UTC));
        return new CompensationExecutionService(cases,new ChangeExecutionGuard(),RestClient.builder(),mapper,mismatches,Clock.fixed(Instant.parse("2026-09-25T04:00:00Z"),ZoneOffset.UTC),base);
    }
}
