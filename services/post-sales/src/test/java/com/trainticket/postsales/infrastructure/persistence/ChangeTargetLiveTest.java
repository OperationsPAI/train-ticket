package com.trainticket.postsales.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.postsales.domain.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

class ChangeTargetLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_CHANGE_CASE", matches=".+")
    void selectedOfferSurvivesRestartAndCannotBeReplaced() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var recorded = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CHANGE_CASE"))));
        var offer = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_CHANGE_OFFER"))));
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        String schema = "change_target_" + UUID.randomUUID().toString().replace("-", "");
        try (var pool = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(pool);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                jdbc.execute("SET search_path TO " + schema);
                for (var file : Files.list(Path.of("migrations")).sorted().toList()) jdbc.execute(Files.readString(file));
                var repository = new PostgresPostSalesRepository(pool,mapper);
                var source = PostSalesCase.open(recorded.path("journeyOrderId").asText(),PostSalesCaseType.CHANGE,
                    mapper.treeToValue(recorded.path("scope"),PostSalesScope.class),recorded.path("reasonCode").asText(),recorded.path("actorRef").asText(),
                    UUID.randomUUID().toString(),Instant.now(),"cmd-select","corr-select");
                repository.save(source);
                var loaded = repository.findById(source.caseId()).orElseThrow();
                loaded.selectChangeOffer(offer.path("offerId").asText());
                repository.save(loaded);
                var restored = new PostgresPostSalesRepository(pool,mapper).findById(source.caseId()).orElseThrow();
                assertEquals(offer.path("offerId").asText(),restored.targetOfferId());
                String replacement="off-"+UUID.randomUUID();
                assertThrows(DomainRuleViolation.class,()->restored.reviseChangeOffer("off-unmatched",replacement));
                restored.reviseChangeOffer(restored.targetOfferId(),replacement);
                repository.save(restored);
                assertEquals(replacement,repository.findById(source.caseId()).orElseThrow().targetOfferId());
                java.util.Map<String,Object> operation=new java.util.LinkedHashMap<>();
                operation.put("segmentBookingId",recorded.path("scope").path("orderItemRefs").get(0).asText());
                operation.put("failureReason",null);
                restored.recordChangeOperation("booking",operation);
                assertThrows(DomainRuleViolation.class,()->restored.reviseChangeOffer(replacement,offer.path("offerId").asText()));
                repository.save(restored);
                var replayed=repository.findById(source.caseId()).orElseThrow();
                assertEquals(operation,replayed.changeOperations().get("booking"));
                replayed.recordChangeOperation("booking",operation);
                assertThrows(DomainRuleViolation.class,()->replayed.recordChangeOperation("booking",java.util.Map.of("segmentBookingId","sb-other")));
                restored.selectChangeOffer(restored.targetOfferId());
                assertThrows(DomainRuleViolation.class,()->restored.selectChangeOffer("off-"+UUID.randomUUID()));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class));
            } finally {
                jdbc.execute("SET search_path TO public");
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
