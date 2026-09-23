package com.trainticket.financesettlement.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.financesettlement.application.*;
import com.trainticket.financesettlement.adapters.messaging.RedisMessagingConfiguration;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@EnabledIfEnvironmentVariable(named="TEST_FINANCE_EVENTS",matches=".+")
class FinanceEventFilterLiveTest {
    @Test
    void unrelatedEventsAvoidDatabaseTransactionsAndPaymentStillRecognizesOnce() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        List<EventEnvelope> events=new ArrayList<>();
        for(var raw:mapper.readTree(Files.readString(Path.of(System.getenv("TEST_FINANCE_EVENTS")))))events.add(mapper.treeToValue(raw,EventEnvelope.class));
        String schema="finance_filter_"+UUID.randomUUID().toString().replace("-","");
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(1);config.setConnectionInitSql("SET search_path TO "+schema);
        try(var pool=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(pool);jdbc.execute("CREATE SCHEMA "+schema);
            try {
                try(var files=Files.list(Path.of("migrations"))){for(var file:files.sorted().toList())jdbc.execute(Files.readString(file));}
                var projections=new PostgresFinanceSettlementProjectionRepository(pool);
                var service=new FinanceSettlementApplicationService(new PostgresFinanceRepositories.RevenueRecognitions(pool,mapper),new PostgresFinanceRepositories.Cases(pool,mapper),
                    new PostgresFinanceRepositories.Invoices(pool,mapper),projections,new PostgresFinanceRepositories.Batches(pool,mapper),new PostgresFinanceRepositories.Suppliers(pool,mapper),
                    new PostgresFinanceRepositories.FeeAccruals(pool,mapper),new TransactionalOutboxEventPublisher(new OutboxAppender(pool,mapper),mapper),new DomainEventEnvelopeMapper(),Clock.systemUTC());
                var target=new FinanceSettlementEventHandler(new PostgresConsumedEventLogRepository(pool),new PostgresReferenceRepositories.PaymentIntentRefs(pool),
                    new PostgresReferenceRepositories.SegmentBookingRefs(pool),projections,Clock.systemUTC(),service);
                var proxy=new ProxyFactory(target);
                var advice=new TransactionInterceptor();advice.setTransactionManager(new DataSourceTransactionManager(pool));advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
                proxy.addAdvice(advice);
                var handler=(EventSubscriber.EventHandler)proxy.getProxy();
                var ignored=events.stream().filter(event->!FinanceSettlementEventHandler.handlesEvent(event.eventType())).toList();
                assertFalse(ignored.isEmpty());
                try(var held=pool.getConnection()) {
                    assertTimeoutPreemptively(Duration.ofSeconds(1),()->{
                        for(var event:ignored)assertEquals(com.trainticket.platformkit.messaging.HandlerResult.SUCCESS,RedisMessagingConfiguration.dispatch(handler,event));
                    });
                }
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM processed_events",Integer.class));
                var captured=events.stream().filter(event->event.eventType().equals("PaymentCaptured") && "purchase".equals(((Map<?,?>)event.payload()).get("purpose"))).findFirst().orElseThrow();
                for(int attempt=0;attempt<2;attempt++)assertEquals(com.trainticket.platformkit.messaging.HandlerResult.SUCCESS,RedisMessagingConfiguration.dispatch(handler,captured));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM revenue_recognition_snapshots",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='RevenueRecognized'",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM processed_events",Integer.class));
            } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
