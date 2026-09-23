package com.trainticket.postsales;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.application.ChangeExecutionGuard;
import com.trainticket.postsales.application.ChangeExecutionService;
import com.trainticket.postsales.application.PostSalesApplicationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="TEST_CAPACITY_COMPENSATION_SOURCE",matches=".+")
class CapacityCompensationLiveTest {
    @Test
    void actualSeatRejectionIsPersistedAndConcurrentExecutionIsSerialized() throws Exception {
        String schema="capacity_compensation_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA "+schema);
            try(var app=SpringApplication.run(Application.class,"--server.port=0","--post-sales.messaging.redis.enabled=false",
                "--DATABASE_URL=postgresql://postgres@localhost:15432/postgres?currentSchema="+schema,
                "--REDIS_URL=redis://localhost:16379","--CHANGE_SERVICE_URL_TEMPLATE="+System.getenv("TEST_LIVE_BASE_URL_TEMPLATE"))) {
                var mapper=app.getBean(ObjectMapper.class);
                var source=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CAPACITY_COMPENSATION_SOURCE"))));
                var row=source.path("post_sales");String id=row.path("id").asText();
                var jdbc=new JdbcTemplate(app.getBean(DataSource.class));
                jdbc.update("INSERT INTO post_sales_case_snapshots(id,version,data) VALUES(?,?,?::jsonb)",id,row.path("version").asLong(),row.path("data").toString());
                var execution=app.getBean(ChangeExecutionService.class);
                var result=execution.advance(id,"corr-"+com.trainticket.platformkit.idempotency.UuidV7.generate());
                var rejected=result.changeOperations().get("capacity-rejected");
                assertNotNull(rejected);
                assertEquals(source.path("booking_orchestration").path("id").asText(),rejected.get("segmentBookingId"));
                assertEquals("no compatible seat exists and standing is not accepted",rejected.get("message"));
                assertFalse(result.changeOperations().keySet().stream().anyMatch(name->name.startsWith("ticket:") || name.startsWith("void:")));
                assertEquals(rejected,app.getBean(PostSalesApplicationService.class).get(id).changeOperations().get("capacity-rejected"));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class));
                var guard=app.getBean(ChangeExecutionGuard.class);
                var acquired=new CountDownLatch(1);var finish=new CountDownLatch(1);
                try(var workers=Executors.newFixedThreadPool(2)) {
                    var first=workers.submit(()->guard.execute(id,()->{
                        acquired.countDown();
                        try { if(!finish.await(5,TimeUnit.SECONDS))throw new IllegalStateException("guard test timed out"); }
                        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                        return true;
                    },()->false));
                    assertTrue(acquired.await(5,TimeUnit.SECONDS));
                    try { assertFalse(guard.execute("psc-"+id,()->true,()->false)); }
                    finally { finish.countDown(); }
                    assertTrue(first.get(5,TimeUnit.SECONDS));
                }
                assertThrows(IllegalStateException.class,()->guard.execute(id,()->{throw new IllegalStateException("abort guarded work");},()->false));
                assertTrue(guard.execute(id,()->true,()->false));
            } finally { admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }
}
