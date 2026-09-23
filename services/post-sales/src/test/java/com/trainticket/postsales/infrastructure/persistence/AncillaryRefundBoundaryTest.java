package com.trainticket.postsales.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.postsales.application.*;
import com.trainticket.postsales.adapters.http.FarePricingAdjustmentQuoteClient;
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
import org.springframework.web.client.RestClient;

class AncillaryRefundBoundaryTest{
 @Test @EnabledIfEnvironmentVariable(named="TEST_ANCILLARY_REFUND_EVIDENCE",matches=".+")
 void independentRefundDoesNotIncreasePrimaryTicketRefund()throws Exception{
  var mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  var source=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_ANCILLARY_REFUND_EVIDENCE"))));
  var event=mapper.treeToValue(source.path("refundEvent"),EventEnvelope.class);
  var context=mapper.readValue(Files.readString(Path.of(System.getenv("TEST_PRIMARY_POLICY_CONTEXT"))),PostSalesPolicyContext.class);
  var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(1);
  try(var pool=new HikariDataSource(config)){
   var jdbc=new JdbcTemplate(pool);String schema="ancillary_refund_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE SCHEMA "+schema);jdbc.execute("SET search_path TO "+schema);
   try{
    jdbc.execute(Files.readString(Path.of("migrations/001_post_sales_persistence.sql")));
    jdbc.execute(Files.readString(Path.of("migrations/009_policy_contexts.sql")));
    var contexts=new PostgresPostSalesPolicyContextStore(pool,mapper);contexts.save(context);
    var projections=new PostgresPostSalesExternalEventProjectionStore(pool,mapper);
    var quote=new FarePricingAdjustmentQuoteClient("http://fare-pricing:8080","http://journey-order:8080","http://offer-management:8080",RestClient.builder());
    var service=new PostSalesApplicationService(new PostgresPostSalesRepository(pool,mapper),new OutboxAppender(pool,mapper)::append,quote,contexts,projections,Clock.systemUTC());
    var projection=AncillaryPostSalesProjection.fromEvent(event.eventId(),event.eventType(),event.occurredAt(),mapper.convertValue(event.payload(),java.util.Map.class));
    service.recordAncillaryProjection(projection);
    assertEquals(context,contexts.findByOrderId(context.journeyOrderId()).orElseThrow());
    assertEquals(source.path("payment").path("paymentIntentId").asText(),projections.findAncillaryByItemId(projection.ancillaryOrderItemId()).orElseThrow().paymentIntentId());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM post_sales_case_snapshots",Integer.class));
   }finally{jdbc.execute("SET search_path TO public");jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
  }
 }
}
