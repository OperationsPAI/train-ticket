package com.trainticket.postsales;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.application.PostSalesApplicationService;
import com.trainticket.postsales.infrastructure.persistence.PostgresPostSalesRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="TEST_CAPACITY_RELEASE_HISTORY",matches=".+")
class CapacityReleaseHistoryLiveTest {
    @Test
    void recordedCompletedReleaseDoesNotWaitForUnrelatedOrderWriter() throws Exception {
        String schema="release_history_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
            admin.createStatement().execute("CREATE SCHEMA "+schema);
            try(var app=SpringApplication.run(Application.class,"--server.port=0","--post-sales.messaging.redis.enabled=false",
                "--DATABASE_URL=postgresql://postgres@localhost:15432/postgres?currentSchema="+schema,"--REDIS_URL=redis://localhost:16379")) {
                var mapper=app.getBean(ObjectMapper.class);
                var row=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CAPACITY_RELEASE_HISTORY"))));
                String booking=row.path("data").path("releasedOrderItems").get(0).asText();
                var jdbc=new JdbcTemplate(app.getBean(DataSource.class));
                jdbc.update("INSERT INTO post_sales_case_snapshots(id,version,data) VALUES(?,?,?::jsonb)",row.path("id").asText(),row.path("version").asLong(),row.path("data").toString());
                var service=app.getBean(PostSalesApplicationService.class);
                var repository=app.getBean(PostgresPostSalesRepository.class);
                var transaction=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
                try(var workers=Executors.newSingleThreadExecutor()) {
                    transaction.executeWithoutResult(status->{
                        repository.lockOrder(row.path("data").path("journeyOrderId").asText());
                        var reader=workers.submit(()->service.applyForSegmentBooking(booking,"evt-"+UUID.randomUUID(),"corr-"+UUID.randomUUID()));
                        try { reader.get(2,TimeUnit.SECONDS); }
                        catch(Exception error){throw new IllegalStateException(error);}
                    });
                }
                assertEquals(row.path("version").asLong(),jdbc.queryForObject("SELECT version FROM post_sales_case_snapshots WHERE id=?",Long.class,row.path("id").asText()));
                assertEquals(row.path("data"),mapper.readTree(jdbc.queryForObject("SELECT data::text FROM post_sales_case_snapshots WHERE id=?",String.class,row.path("id").asText())));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class));
            } finally {admin.createStatement().execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
