package com.trainticket.postsales;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.application.PostSalesApplicationService;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesScope;
import com.trainticket.platformkit.idempotency.UuidV7;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="TEST_CAPACITY_COMPENSATION_SOURCE",matches=".+")
class AcceptedPolicyContextLiveTest {
    @Test
    void actualOrderPricesRefundWithoutWaitingForOrderEventProjection() throws Exception {
        String schema="accepted_policy_"+UUID.randomUUID().toString().replace("-","");
        String base=System.getenv("TEST_LIVE_BASE_URL_TEMPLATE");
        try(var admin=DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA "+schema);
            try(var app=new SpringApplication(Application.class,TransactionObservation.class).run("--server.port=0","--post-sales.messaging.redis.enabled=false",
                "--DATABASE_URL=postgresql://postgres@localhost:15432/postgres?currentSchema="+schema,"--REDIS_URL=redis://localhost:16379",
                "--post-sales.fare-pricing.base-url="+base.formatted("fare-pricing"),"--JOURNEY_ORDER_BASE_URL="+base.formatted("journey-order"),
                "--OFFER_MANAGEMENT_BASE_URL="+base.formatted("offer-management"))) {
                var mapper=app.getBean(ObjectMapper.class);
                var sample=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CAPACITY_COMPENSATION_SOURCE")))).path("post_sales").path("data");
                var service=app.getBean(PostSalesApplicationService.class);var jdbc=new JdbcTemplate(app.getBean(DataSource.class));
                var scope=mapper.treeToValue(sample.path("scope"),PostSalesScope.class);
                var opened=service.open(new PostSalesApplicationService.OpenCaseCommand(sample.path("journeyOrderId").asText(),PostSalesCaseType.REFUND,
                    scope,"VOLUNTARY",sample.path("actorRef").asText(),UuidV7.generate(),"cmd-"+UuidV7.generate(),"corr-"+UuidV7.generate()));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM post_sales_policy_contexts",Integer.class));
                com.trainticket.postsales.domain.PostSalesCase evaluated;
                try(var workers=java.util.concurrent.Executors.newFixedThreadPool(8)) {
                    var calls=new java.util.ArrayList<java.util.concurrent.Future<com.trainticket.postsales.domain.PostSalesCase>>();
                    for(int index=0;index<8;index++)calls.add(workers.submit(()->service.evaluate(opened.caseId(),"cmd-"+UuidV7.generate(),"corr-"+UuidV7.generate())));
                    evaluated=calls.getFirst().get(20,java.util.concurrent.TimeUnit.SECONDS);
                    for(var call:calls)assertEquals(evaluated.decision(),call.get(20,java.util.concurrent.TimeUnit.SECONDS).decision());
                }
                assertNotNull(evaluated.decision());assertTrue(evaluated.decision().eligible());
                assertNotNull(evaluated.decision().refundAssessment());
                assertEquals("LT_48H_NON_REFUNDABLE",evaluated.decision().refundAssessment().tierApplied());
                assertEquals(0,evaluated.decision().amountSnapshot().refundAmount().toMinorUnits());
                assertEquals(30990,evaluated.decision().refundAssessment().penaltyAmount().toMinorUnits());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM post_sales_policy_contexts",Integer.class));
                var replay=service.evaluate(opened.caseId(),"cmd-"+UuidV7.generate(),"corr-"+UuidV7.generate());
                assertEquals(evaluated.decision(),replay.decision());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='PostSalesDecisionQuoted'",Integer.class));
            } finally {admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TransactionObservation {
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanPostProcessor verifyOutboundTransaction() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if(bean instanceof org.springframework.web.client.RestClient.Builder builder)builder.requestInterceptor((request,body,execution)->{
                        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"external quote reads must not hold a database transaction");
                        return execution.execute(request,body);
                    });
                    return bean;
                }
            };
        }
    }
}
