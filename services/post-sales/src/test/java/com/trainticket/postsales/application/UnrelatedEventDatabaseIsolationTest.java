package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.postsales.adapters.http.FarePricingAdjustmentQuoteClient;
import com.trainticket.postsales.infrastructure.persistence.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named="TEST_POSTSALES_UNRELATED_EVENTS",matches=".+")
class UnrelatedEventDatabaseIsolationTest {
    @Test
    void ignoredEventsFinishWithoutAcquiringAnyDatabaseConnection() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var entries=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_POSTSALES_UNRELATED_EVENTS"))));
        EventEnvelope event=null;
        for(var entry:entries){var candidate=mapper.readValue(entry.get(1).get(1).asText(),EventEnvelope.class);if(candidate.eventType().equals("JourneyOrderPendingPayment")){event=candidate;break;}}
        assertNotNull(event);
        var selected=event;
        String schema="postsales_filter_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))){
            admin.createStatement().execute("CREATE SCHEMA "+schema);
            var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(1);config.setConnectionInitSql("SET search_path TO "+schema);
            try(var pool=new HikariDataSource(config);var workers=Executors.newSingleThreadExecutor()){
                var jdbc=new JdbcTemplate(pool);
                try(var migrations=Files.list(Path.of("migrations"))){for(var migration:migrations.sorted().toList())jdbc.execute(Files.readString(migration));}
                var service=new PostSalesApplicationService(new PostgresPostSalesRepository(pool,mapper),new OutboxAppender(pool,mapper)::append,
                    new FarePricingAdjustmentQuoteClient("http://localhost:18001","http://localhost:18001","http://localhost:18001",RestClient.builder()),
                    new PostgresPostSalesPolicyContextStore(pool,mapper),new PostgresPostSalesExternalEventProjectionStore(pool,mapper),Clock.systemUTC());
                var handler=new PostSalesEventHandler(new PostgresConsumedEventLog(pool),service,new PostSalesExternalEventPolicy(service),new DataSourceTransactionManager(pool));
                try(var held=pool.getConnection()){
                    var handled=workers.submit(()->handler.handle(selected));
                    assertEquals(EventSubscriber.HandlerResult.SUCCESS,handled.get(1,TimeUnit.SECONDS));
                }
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM processed_events",Integer.class));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class));
                EventEnvelope created=null;
                for(var entry:entries){var candidate=mapper.readValue(entry.get(1).get(1).asText(),EventEnvelope.class);if(candidate.eventType().equals("JourneyOrderCreated")){created=candidate;break;}}
                assertNotNull(created);
                var context=PostSalesPolicyContextMapper.fromEventPayload(mapper.convertValue(created.payload(),java.util.Map.class)).orElseThrow();
                for(var type:java.util.List.of(com.trainticket.postsales.domain.PostSalesCaseType.REFUND,com.trainticket.postsales.domain.PostSalesCaseType.CHANGE)){
                    var pending=com.trainticket.postsales.domain.PostSalesCase.open(context.journeyOrderId(),type,
                        com.trainticket.postsales.domain.PostSalesScope.ticket("booking","segment","traveler","ticket"),"CUSTOMER_REQUEST","account",UUID.randomUUID().toString(),java.time.Instant.now(),"cmd","corr");
                    assertThrows(PolicyContextUnavailableException.class,()->service.policyContextFor(pending));
                }
                service.recordPolicyContext(context);
                var change=com.trainticket.postsales.domain.PostSalesCase.open(context.journeyOrderId(),com.trainticket.postsales.domain.PostSalesCaseType.CHANGE,
                    com.trainticket.postsales.domain.PostSalesScope.ticket("booking","segment","traveler","ticket"),"CUSTOMER_REQUEST","account",UUID.randomUUID().toString(),java.time.Instant.now(),"cmd","corr");
                assertEquals(context,service.policyContextFor(change));
            }finally{admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
