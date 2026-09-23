package com.trainticket.journeyorder.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.journeyorder.domain.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

class ChangeOrderSnapshotLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_CHANGE_ORDER",matches=".+")
    void replacementOrderKeepsAccountingAndReplaysAfterReload() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var source=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CHANGE_ORDER"))));
        var target=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CHANGE_OFFER"))));
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(1);
        String schema="change_order_"+UUID.randomUUID().toString().replace("-","");
        try(var pool=new HikariDataSource(config)){
            var jdbc=new JdbcTemplate(pool);jdbc.execute("CREATE SCHEMA "+schema);jdbc.execute("SET search_path TO "+schema);
            try{
                jdbc.execute(Files.readString(Path.of("migrations/001_journey_order_persistence.sql")));
                String id=source.path("orderId").asText();
                jdbc.update("INSERT INTO journey_order_snapshots(id,version,data) VALUES (?,1,?::jsonb)",id,source.toString());
                var repo=new PostgresJourneyOrderStateRepository(pool,mapper);var stored=repo.findOrder(id).orElseThrow();var order=stored.order();
                var items=new ArrayList<OrderItem>();
                String traveler=order.travelers().getFirst().travelerId();
                for(var item:target.path("items")){
                    String itemId=item.path("offerItemId").asText()+":replacement:"+traveler;
                    items.add(new OrderItem(itemId,OrderItemType.SEGMENT_FARE,"replacement",Money.fromMinorUnits(item.path("itemPrice").path("minorUnits").asLong(),"CNY"),item.path("offerItemId").asText(),List.of(new OrderLineBinding(itemId,traveler,item.path("segmentRef").asText(),""))));
                }
                var before=order.monetarySummary().payableTotal();var offer=order.offerSnapshot();var segments=order.segments();String caseId="psc-"+UUID.randomUUID();
                order.applyChange(caseId,offer,segments,items,Money.of("CNY","15.00"),Instant.now());
                repo.saveOrder(order,stored.idempotencyKey());
                var restored=repo.findOrder(id).orElseThrow().order();
                assertEquals(OrderLifecycleState.CONFIRMED,restored.state());
                assertEquals(before.plus(Money.of("CNY","15.00")),restored.monetarySummary().payableTotal());
                int count=restored.orderItems().size();
                restored.applyChange(caseId,offer,segments,items,Money.of("CNY","15.00"),Instant.now());
                assertEquals(count,restored.orderItems().size());
                assertEquals(1,restored.timeline().stream().filter(f->"ChangeApplied".equals(f.factType())).count());
            }finally{jdbc.execute("SET search_path TO public");jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
