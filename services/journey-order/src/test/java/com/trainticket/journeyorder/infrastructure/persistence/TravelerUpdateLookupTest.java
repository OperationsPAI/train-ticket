package com.trainticket.journeyorder.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.journeyorder.application.service.InMemoryJourneyOrderStateRepository;
import com.trainticket.journeyorder.domain.OrderLifecycleState;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

class TravelerUpdateLookupTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_ORDER_SNAPSHOTS_FILE", matches=".+")
    void returnsOnlyOrdersAcceptingTravelerUpdatesWithoutChangingHistory() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var samples = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_ORDER_SNAPSHOTS_FILE"))));
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setMaximumPoolSize(1);
        String schema = "order_lookup_" + UUID.randomUUID().toString().replace("-", "");
        try (var dataSource = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                jdbc.execute("SET search_path TO " + schema);
                jdbc.execute(Files.readString(Path.of("migrations/001_journey_order_persistence.sql")));
                var repository = new PostgresJourneyOrderStateRepository(dataSource, mapper);
                var memory = new InMemoryJourneyOrderStateRepository();
                var travelers = new HashSet<String>();
                int excluded = 0;
                int included = 0;
                for (var sample : samples) {
                    jdbc.update("INSERT INTO journey_order_snapshots(id,version,data) VALUES (?,?,?::jsonb)", sample.path("id").asText(), sample.path("version").asLong(), sample.path("data").toString());
                    var stored = repository.findOrder(sample.path("id").asText()).orElseThrow();
                    memory.saveOrder(stored.order().withVersion(0), stored.idempotencyKey());
                    stored.order().travelers().forEach(traveler -> travelers.add(traveler.travelerId()));
                    if (Set.of(OrderLifecycleState.CANCELLED, OrderLifecycleState.COMPLETED, OrderLifecycleState.FAILED).contains(stored.order().state())) excluded++;
                    else included++;
                }
                assertTrue(included > 0 && excluded > 0);
                for (String traveler : travelers) {
                    var expected = new HashSet<String>();
                    for (var sample : samples) {
                        var stored = repository.findOrder(sample.path("id").asText()).orElseThrow();
                        if (stored.order().travelers().stream().anyMatch(item -> item.travelerId().equals(traveler))
                            && !Set.of(OrderLifecycleState.CANCELLED, OrderLifecycleState.COMPLETED, OrderLifecycleState.FAILED).contains(stored.order().state())) expected.add(stored.order().orderId());
                    }
                    assertEquals(expected, new HashSet<>(repository.findOrdersForTravelerUpdates(traveler).stream().map(item -> item.order().orderId()).toList()));
                    assertEquals(expected, new HashSet<>(memory.findOrdersForTravelerUpdates(traveler).stream().map(item -> item.order().orderId()).toList()));
                }
                assertEquals(samples.size(), jdbc.queryForObject("SELECT count(*) FROM journey_order_snapshots", Integer.class));
                for (var sample : samples) {
                    assertEquals(sample.path("data"), mapper.readTree(jdbc.queryForObject("SELECT data::text FROM journey_order_snapshots WHERE id=?", String.class, sample.path("id").asText())));
                }
            } finally {
                jdbc.execute("SET search_path TO public");
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
