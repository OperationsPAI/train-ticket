package com.trainticket.journeyorder.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.journeyorder.domain.Money;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

class AncillaryAmountTest {
 @Test @EnabledIfEnvironmentVariable(named="TEST_PAID_ANCILLARY",matches=".+")
 void preservesActualAncillaryAmountWithoutChangingPrimaryCollection()throws Exception{
  var mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  var source=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_PAID_ORDER"))));
  var item=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_PAID_ANCILLARY")))).path("item");
  var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(1);
  try(var pool=new HikariDataSource(config)){
   var jdbc=new JdbcTemplate(pool);String schema="ancillary_amount_"+UUID.randomUUID().toString().replace("-","");
   jdbc.execute("CREATE SCHEMA "+schema);jdbc.execute("SET search_path TO "+schema);
   try{
    jdbc.execute(Files.readString(Path.of("migrations/001_journey_order_persistence.sql")));
    jdbc.update("INSERT INTO journey_order_snapshots(id,version,data) VALUES (?,?,?::jsonb)",source.path("id").asText(),source.path("version").asLong(),source.path("data").toString());
    var repo=new PostgresJourneyOrderStateRepository(pool,mapper);var stored=repo.findOrder(source.path("id").asText()).orElseThrow();var order=stored.order();
    var original=order.monetarySummary();String id=item.path("ancillaryOrderItemId").asText();
    order.addOrUpdateAncillaryOrderItem(id,item.path("travelerRef").asText(),item.path("segmentRef").asText(),item.path("catalogItemId").asText(),item.path("serviceType").asText(),Money.fromMinorUnits(item.path("payableAmount").path("minorUnits").asLong(),item.path("payableAmount").path("currency").asText()),Instant.now(),"CONFIRMED");
    repo.saveOrder(order,stored.idempotencyKey());
    var restored=repo.findOrder(order.orderId()).orElseThrow().order();var added=restored.orderItems().stream().filter(value->value.orderItemId().equals(id)).findFirst().orElseThrow();
    assertEquals(1200,added.amount().toMinorUnits());assertTrue(added.independentlySettled());assertEquals(original,restored.monetarySummary());
    restored.cancelAncillaryOrderItem(id,item.path("serviceType").asText(),"refund-settled",Instant.now());repo.saveOrder(restored,stored.idempotencyKey());
    assertEquals(original,repo.findOrder(order.orderId()).orElseThrow().order().monetarySummary());
   }finally{jdbc.execute("SET search_path TO public");jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
  }
 }
}
