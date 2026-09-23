package com.trainticket.postsales.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.postsales.adapters.http.FarePricingAdjustmentQuoteClient;
import com.trainticket.postsales.application.OrderNotRefundableException;
import com.trainticket.postsales.application.PostSalesApplicationService;
import com.trainticket.postsales.domain.PostSalesCaseType;
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

class AppliedRefundScopeTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_APPLIED_REFUND_FILE", matches=".+")
    void completedRefundRejectsNewChangeAndRefundForItsTicket() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var sample = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_APPLIED_REFUND_FILE"))));
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        String schema = "applied_scope_" + UUID.randomUUID().toString().replace("-", "");
        try (var dataSource = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                jdbc.execute("SET search_path TO " + schema);
                jdbc.execute(Files.readString(Path.of("migrations/001_post_sales_persistence.sql")));
                jdbc.execute(Files.readString(Path.of("migrations/004_applied_order_scope_lookup.sql")));
                jdbc.execute(Files.readString(Path.of("migrations/009_policy_contexts.sql")));
                jdbc.update("INSERT INTO post_sales_case_snapshots(id,version,data) VALUES (?,?,?::jsonb)",sample.path("id").asText(),sample.path("version").asLong(),sample.path("data").toString());
                var repository = new PostgresPostSalesRepository(dataSource,mapper);
                var original = repository.findById(sample.path("id").asText()).orElseThrow();
                var appender = new OutboxAppender(dataSource, mapper);
                var service = new PostSalesApplicationService(repository,appender::append,
                    new FarePricingAdjustmentQuoteClient("http://fare-pricing:8080","http://journey-order:8080","http://offer-management:8080", RestClient.builder()),
                    new PostgresPostSalesPolicyContextStore(dataSource,mapper),new PostgresPostSalesExternalEventProjectionStore(dataSource,mapper),Clock.systemUTC());
                for (var type : new PostSalesCaseType[]{PostSalesCaseType.CHANGE,PostSalesCaseType.REFUND,PostSalesCaseType.CANCELLATION}) {
                    var command = new PostSalesApplicationService.OpenCaseCommand(original.journeyOrderId(),type,original.scope(),"CUSTOMER_REQUEST",original.actorRef(),UUID.randomUUID().toString(),"cmd-check","corr-check");
                    assertThrows(OrderNotRefundableException.class,() -> service.open(command));
                }
                var replay = service.open(new PostSalesApplicationService.OpenCaseCommand(original.journeyOrderId(),original.caseType(),original.scope(),original.reasonCode(),original.actorRef(),original.idempotencyKey(),"cmd-check","corr-check"));
                assertEquals(original.caseId(),replay.caseId());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM post_sales_case_snapshots",Integer.class));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM post_sales_active_refunds",Integer.class));
                var compensation = service.open(new PostSalesApplicationService.OpenCaseCommand(original.journeyOrderId(),PostSalesCaseType.COMPENSATION,original.scope(),"SERVICE_QUALITY_COMPLAINT",original.actorRef(),UUID.randomUUID().toString(),"cmd-check","corr-check"));
                assertEquals(PostSalesCaseType.COMPENSATION,compensation.caseType());
            } finally {
                jdbc.execute("SET search_path TO public");
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
