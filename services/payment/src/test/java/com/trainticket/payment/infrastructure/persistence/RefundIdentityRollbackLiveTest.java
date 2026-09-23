package com.trainticket.payment.infrastructure.persistence;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.payment.domain.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="TEST_REFUND_ROLLBACK_PAYMENT",matches=".+")
class RefundIdentityRollbackLiveTest {
    @Test void retriesUseSameIdentityAfterActualDatabaseRollback() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var source=mapper.readTree(Files.readString(Path.of(System.getenv("TEST_REFUND_ROLLBACK_PAYMENT"))));
        String schema="refund_identity_"+UUID.randomUUID().toString().replace("-","");
        var config=new HikariConfig();config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));config.setMaximumPoolSize(2);config.setConnectionInitSql("SET search_path TO "+schema);
        try(var pool=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(pool);jdbc.execute("CREATE SCHEMA "+schema);
            try {
                try(var files=Files.list(Path.of("migrations"))){for(var file:files.sorted().toList())jdbc.execute(Files.readString(file));}
                String id=source.path("paymentIntentId").asText();
                jdbc.update("INSERT INTO payment_intent_snapshots(id,version,data) VALUES(?,1,?::jsonb)",id,source.toString());
                var payments=new PostgresPaymentIntentRepository(pool,mapper);var refunds=new PostgresRefundRepository(pool,mapper);
                var tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
                String first=tx.execute(status->{var payment=payments.findById(id).orElseThrow();var refund=Refund.request(payment,payment.capturedAmount(),"psc-01a0cd2f-10e6-7031-81e8-13a190c87ef8","post-sales-approved","psc-01a0cd2f-10e6-7031-81e8-13a190c87ef8",Instant.now(),"cmd-refund","corr-refund");refunds.save(refund);status.setRollbackOnly();return refund.refundId();});
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM refund_snapshots",Integer.class));
                String second=tx.execute(status->{var payment=payments.findById(id).orElseThrow();var refund=Refund.request(payment,payment.capturedAmount(),"psc-01a0cd2f-10e6-7031-81e8-13a190c87ef8","post-sales-approved","psc-01a0cd2f-10e6-7031-81e8-13a190c87ef8",Instant.now(),"cmd-refund","corr-refund");refunds.save(refund);return refund.refundId();});
                assertEquals(first,second);assertTrue(refunds.findById(first).isPresent());
            } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
