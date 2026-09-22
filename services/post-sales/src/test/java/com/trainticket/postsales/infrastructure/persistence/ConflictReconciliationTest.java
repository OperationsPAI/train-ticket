package com.trainticket.postsales.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.postsales.application.PostSalesConflictReconciler;
import com.trainticket.postsales.domain.*;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

class ConflictReconciliationTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_CONFLICT_SNAPSHOTS", matches=".+")
    void reconcilesOnlyTheVerifiedConflictAndPublishesOneFailure() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var samples = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CONFLICT_SNAPSHOTS"))));
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        String schema = "conflict_reconcile_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = new HikariDataSource(config)) {
            var adminJdbc = new JdbcTemplate(admin);
            adminJdbc.execute("CREATE SCHEMA " + schema);
            config.setMaximumPoolSize(4);
            config.setConnectionInitSql("SET search_path TO " + schema);
            try (var dataSource = new HikariDataSource(config); var workers = Executors.newFixedThreadPool(4)) {
                var jdbc = new JdbcTemplate(dataSource);
                jdbc.execute(Files.readString(Path.of("migrations/001_post_sales_persistence.sql")));
                for (var sample : samples) jdbc.update("INSERT INTO post_sales_case_snapshots(id,version,data) VALUES (?,?,?::jsonb)", sample.path("id").asText(),sample.path("version").asLong(),sample.path("data").toString());
                var repository = new PostgresPostSalesRepository(dataSource,mapper);
                var original = java.util.stream.StreamSupport.stream(samples.spliterator(),false).filter(s -> s.path("data").path("status").asText().equals("APPLIED")).findFirst().orElseThrow();
                var conflict = java.util.stream.StreamSupport.stream(samples.spliterator(),false).filter(s -> s.path("data").path("status").asText().equals("EXECUTING")).findFirst().orElseThrow();
                String id = conflict.path("id").asText();
                String orderId = conflict.path("data").path("journeyOrderId").asText();
                jdbc.update("INSERT INTO post_sales_active_refunds(journey_order_id,case_id) VALUES (?,?)",orderId,id);
                var reconciler = new PostSalesConflictReconciler(repository,new OutboxAppender(dataSource,mapper)::append,Clock.systemUTC(),mapper,System.getenv("TEST_PAYMENT_URL"),System.getenv("TEST_TICKETING_URL"),RestClient.builder());
                var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                assertThrows(DomainRuleViolation.class,() -> transaction.execute(status -> reconciler.reconcile(original.path("id").asText(),PrefixedIds.newCorrelationId())));
                String correlation = PrefixedIds.newCorrelationId();
                var results = new java.util.ArrayList<java.util.concurrent.Future<PostSalesCase>>();
                for (int index=0; index<4;index++) results.add(workers.submit(() -> transaction.execute(status -> reconciler.reconcile(id,correlation))));
                for (var result:results) {
                    var changed = result.get(20,TimeUnit.SECONDS);
                    assertEquals(PostSalesCaseStatus.FAILED,changed.status());
                    assertEquals("SCOPE_ALREADY_REFUNDED:psc-"+original.path("id").asText(),changed.terminalReason());
                }
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM post_sales_active_refunds",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='PostSalesFailed'",Integer.class));
                assertEquals(original.path("data"),mapper.readTree(jdbc.queryForObject("SELECT data::text FROM post_sales_case_snapshots WHERE id=?",String.class,original.path("id").asText())));
                assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM post_sales_case_snapshots",Integer.class));
                assertEquals(conflict.path("version").asLong()+1,repository.findById(id).orElseThrow().version());
            } finally { adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
