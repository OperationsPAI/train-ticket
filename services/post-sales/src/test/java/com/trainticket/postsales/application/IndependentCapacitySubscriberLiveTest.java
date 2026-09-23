package com.trainticket.postsales.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.LazyRedisEventSubscriber;
import com.trainticket.platformkit.messaging.LettuceRedisStreamOperations;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.postsales.adapters.http.FarePricingAdjustmentQuoteClient;
import com.trainticket.postsales.adapters.messaging.RedisMessagingConfiguration;
import com.trainticket.postsales.adapters.messaging.RedisMessagingProperties;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.infrastructure.persistence.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.RedisClient;
import io.lettuce.core.Range;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named = "TEST_CAPACITY_ISOLATION", matches = "true")
class IndependentCapacitySubscriberLiveTest {
    @Test
    void recordedReleaseCompletesWhileOrderProjectionWorkersAndQueueAreBlocked() throws Exception {
        assertEquals("4", System.getenv("CONSUMER_THREADS"));
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var evidence = mapper.readTree(Files.readString(Path.of(required("TEST_CAPACITY_DEDUP_EVIDENCE"))));
        var caseEvents = mapper.readTree(Files.readString(Path.of(required("TEST_CAPACITY_CASE_EVENTS"))));
        var orderRows = mapper.readTree(Files.readString(Path.of(required("TEST_POSTSALES_UNRELATED_EVENTS"))));
        var orders = new ArrayList<EventEnvelope>();
        for (var row : orderRows) {
            var event = mapper.readValue(row.get(1).get(1).asText(), EventEnvelope.class);
            if (event.eventType().equals("JourneyOrderCreated") && orders.size() < 40) orders.add(event);
        }
        assertEquals(40, orders.size());
        assertEquals(40, orders.stream().map(EventEnvelope::eventId).distinct().count());
        String schema = "capacity_isolation_" + UUID.randomUUID().toString().replace("-", "");
        String databaseUrl = required("TEST_DATABASE_URL");
        String redisUrl = required("TEST_REDIS_URL");
        String capacityStream = "events:capacity-availability";
        String orderStream = "events:journey-order";
        var streams = List.of(capacityStream, orderStream, "events:disruption-recovery", "events:ancillary-service", "events:dispatch");
        String group = "post-sales";
        var report = mapper.createObjectNode().put("schema", schema).put("redisUrl", redisUrl);
        var redis = RedisClient.create(redisUrl);
        try (var admin = DriverManager.getConnection(databaseUrl); var connection = redis.connect()) {
            // The test requires a dedicated Redis database because production stream names are exercised.
            assertEquals(0L, connection.sync().dbsize(), "TEST_REDIS_URL must select an empty dedicated database");
            admin.createStatement().execute("CREATE SCHEMA " + schema);
            var config = new HikariConfig();
            config.setJdbcUrl(databaseUrl);
            config.setMaximumPoolSize(10);
            config.setConnectionInitSql("SET search_path TO " + schema);
            config.addDataSourceProperty("ApplicationName", schema);
            try (var pool = new HikariDataSource(config)) {
                var jdbc = new JdbcTemplate(pool);
                try (var migrations = Files.list(Path.of("migrations"))) {
                    for (var migration : migrations.sorted().toList()) jdbc.execute(Files.readString(migration));
                }
                var repository = new PostgresPostSalesRepository(pool, mapper);
                var contexts = new PostgresPostSalesPolicyContextStore(pool, mapper);
                var transactionManager = new DataSourceTransactionManager(pool);
                var transaction = new TransactionTemplate(transactionManager);
                var service = new PostSalesApplicationService(repository, new OutboxAppender(pool, mapper)::append,
                    new FarePricingAdjustmentQuoteClient("http://localhost:18001", "http://localhost:18001", "http://localhost:18001", RestClient.builder()),
                    contexts, new PostgresPostSalesExternalEventProjectionStore(pool, mapper), Clock.systemUTC());
                var handler = new PostSalesEventHandler(new PostgresConsumedEventLog(pool), service,
                    new PostSalesExternalEventPolicy(service), Optional.of(transactionManager));
                var snapshot = evidence.required("snapshot");
                jdbc.update("INSERT INTO post_sales_case_snapshots(id,version,data) VALUES(?,?,?::jsonb)",
                    snapshot.required("id").asText(), snapshot.required("version").asLong(), snapshot.required("data").toString());
                var recorded = repository.findById(snapshot.required("id").asText()).orElseThrow();
                var quoted = recordedCaseEvent(caseEvents, "PostSalesDecisionQuoted", recorded.caseId());
                var approved = recordedCaseEvent(caseEvents, "PostSalesApproved", recorded.caseId());
                var started = recordedCaseEvent(caseEvents, "PostSalesExecutionStarted", recorded.caseId());
                assertEquals(recorded.decision().quotedAt(), Instant.parse(quoted.required("occurredAt").asText()));
                // Replay the recorded decision and approval through the domain before replaying releases.
                var replay = PostSalesCase.rehydrate(recorded.caseId(), recorded.journeyOrderId(), recorded.caseType(),
                    recorded.scope(), recorded.reasonCode(), recorded.actorRef(), recorded.idempotencyKey(),
                    PostSalesCaseStatus.ELIGIBILITY_CHECKING, null, List.of(), null, null, List.of(), recorded.createdAt())
                    .withVersion(recorded.version()).withRefundPayments(recorded.refundPayments());
                replay.recordDecision(recorded.decision(), false, false, Instant.parse(quoted.required("occurredAt").asText()),
                    PrefixedIds.newCommandId(), PrefixedIds.newEventId(), PrefixedIds.newCorrelationId());
                replay.approve(approved.required("payload").required("approvedActions").required("approvalRef").asText(),
                    Instant.parse(approved.required("occurredAt").asText()), PrefixedIds.newCommandId(), PrefixedIds.newEventId(), PrefixedIds.newCorrelationId());
                replay.startExecution(Instant.parse(started.required("occurredAt").asText()),
                    PrefixedIds.newCommandId(), PrefixedIds.newEventId(), PrefixedIds.newCorrelationId());
                transaction.executeWithoutResult(status -> repository.save(replay));
                assertEquals(PostSalesCaseStatus.EXECUTING, repository.findById(recorded.caseId()).orElseThrow().status());
                for (var event : orders) {
                    contexts.save(PostSalesPolicyContextMapper.fromEventPayload(mapper.convertValue(event.payload(), Map.class)).orElseThrow());
                }
                var operations = new LettuceRedisStreamOperations(connection);
                for (String stream : streams) operations.createGroup(stream, group);
                try (var blocker = pool.getConnection(); var beans = new AnnotationConfigApplicationContext()) {
                    blocker.setAutoCommit(false);
                    blocker.createStatement().executeQuery("SELECT journey_order_id FROM post_sales_policy_contexts FOR UPDATE").close();
                    for (var event : orders) operations.publish(orderStream, mapper.writeValueAsString(event));
                    beans.registerBean(ObjectMapper.class, () -> mapper);
                    beans.registerBean(RedisMessagingProperties.class, () -> new RedisMessagingProperties(redisUrl, schema));
                    beans.registerBean(PostSalesEventHandler.class, () -> handler);
                    beans.register(RedisMessagingConfiguration.class);
                    try {
                        beans.refresh();
                        var main = beans.getBean(LazyRedisEventSubscriber.class);
                        var capacity = beans.getBean(RedisEventSubscriber.class);
                        await(Duration.ofSeconds(10), () -> main.isReady() && executor(delegate(main)).getActiveCount() == 4
                            && executor(delegate(main)).getQueue().size() == 16 && blockedQueries(jdbc, schema) == 5);
                        var mainDelegate = delegate(main);
                        assertEquals(4, executor(capacity).getCorePoolSize());
                        assertEquals(16, executor(capacity).getQueue().remainingCapacity());
                        report.put("blockedOrderHandlers", blockedQueries(jdbc, schema));
                        report.put("queuedOrderEvents", executor(delegate(main)).getQueue().size());
                        report.put("capacityThreads", executor(capacity).getCorePoolSize());
                        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
                        var published = report.putArray("capacityMessages");
                        long releaseStart = System.nanoTime();
                        for (var raw : evidence.required("events")) {
                            assertEquals("CapacityReleased", raw.required("eventType").asText());
                            assertTrue(recorded.scope().orderItemRefs().contains(raw.required("payload").required("references").required("segmentBookingRef").asText()));
                            var event = new EventEnvelope(PrefixedIds.newEventId(), raw.required("eventType").asText(),
                                Instant.parse(raw.required("occurredAt").asText()), PrefixedIds.newCorrelationId(), PrefixedIds.newCommandId(),
                                "capacity-availability", 1, mapper.convertValue(raw.required("payload"), Map.class));
                            String messageId = operations.publish(capacityStream, mapper.writeValueAsString(event));
                            published.addObject().put("sourceMessageId", raw.required("messageId").asText())
                                .put("replayMessageId", messageId).put("eventId", event.eventId());
                        }
                        await(Duration.ofSeconds(2), () -> repository.findById(recorded.caseId()).orElseThrow().status() == PostSalesCaseStatus.APPLIED
                            && connection.sync().xpending(capacityStream, group).getCount() == 0
                            && executor(capacity).getCompletedTaskCount() == evidence.required("events").size());
                        report.put("capacityCompletionMillis", Duration.ofNanos(System.nanoTime() - releaseStart).toMillis());
                        assertEquals(5, blockedQueries(jdbc, schema));
                        assertEquals(16, executor(delegate(main)).getQueue().size());
                        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class));
                        assertEquals(recorded.releasedOrderItems(), repository.findById(recorded.caseId()).orElseThrow().releasedOrderItems());
                        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='PostSalesApplied'", Integer.class));
                        report.set("completedCase", mapper.readTree(jdbc.queryForObject("SELECT data::text FROM post_sales_case_snapshots WHERE id=?", String.class, recorded.caseId())));
                        report.set("outbox", mapper.valueToTree(jdbc.queryForList("SELECT envelope::text FROM outbox")));
                        long version = repository.findById(recorded.caseId()).orElseThrow().version();
                        blocker.rollback();
                        await(Duration.ofSeconds(10), () -> jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class) == orders.size()
                            && connection.sync().xpending(orderStream, group).getCount() == 0);
                        for (var event : orders) {
                            var expected = PostSalesPolicyContextMapper.fromEventPayload(mapper.convertValue(event.payload(), Map.class)).orElseThrow();
                            assertEquals(expected, contexts.findByOrderId(expected.journeyOrderId()).orElseThrow());
                        }
                        // A fresh subscriber also exercises durable duplicate handling after process state is discarded.
                        beans.close();
                        assertTrue(executor(capacity).isTerminated());
                        assertTrue(executor(mainDelegate).isTerminated());
                        try (var restarted = new AnnotationConfigApplicationContext()) {
                            restarted.registerBean(ObjectMapper.class, () -> mapper);
                            restarted.registerBean(RedisMessagingProperties.class, () -> new RedisMessagingProperties(redisUrl, schema));
                            restarted.registerBean(PostSalesEventHandler.class, () -> handler);
                            restarted.register(RedisMessagingConfiguration.class);
                            restarted.refresh();
                            var restartedCapacity = restarted.getBean(RedisEventSubscriber.class);
                            for (var message : connection.sync().xrange(capacityStream, Range.unbounded())) {
                                operations.publish(capacityStream, message.getBody().get("envelope"));
                            }
                            await(Duration.ofSeconds(10), () -> executor(restartedCapacity).getCompletedTaskCount() == evidence.required("events").size()
                                && connection.sync().xpending(capacityStream, group).getCount() == 0
                                && jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class) == orders.size());
                            assertEquals(version, repository.findById(recorded.caseId()).orElseThrow().version());
                            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE envelope->>'eventType'='PostSalesApplied'", Integer.class));
                            report.put("orderEventsCompleted", orders.size());
                            report.put("replayVersion", version);
                        }
                    } finally {
                        blocker.rollback();
                    }
                }
                for (String stream : streams) assertEquals(0L, connection.sync().xlen(stream + ":dlq"));
                Files.createDirectories(Path.of("target/recorded-tests"));
                mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/recorded-tests/capacity-isolation.json").toFile(), report);
                System.out.println("Capacity isolation completed in " + report.path("capacityCompletionMillis").asLong()
                    + " ms while 5 order handlers and 16 queued events remained blocked; evidence: target/recorded-tests/capacity-isolation.json");
            } finally {
                admin.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
                for (String stream : streams) connection.sync().del(stream, stream + ":dlq");
            }
        } finally {
            redis.shutdown();
        }
    }

    private static JsonNode recordedCaseEvent(JsonNode events, String type, String caseId) {
        for (var event : events) {
            if (type.equals(event.path("eventType").asText()) && ("psc-" + caseId).equals(event.path("payload").path("caseId").asText())) return event;
        }
        throw new IllegalArgumentException("Missing recorded " + type + " for " + caseId);
    }

    private static int blockedQueries(JdbcTemplate jdbc, String applicationName) {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock' AND query LIKE 'INSERT INTO post_sales_policy_contexts%'", Integer.class, applicationName);
    }

    private static RedisEventSubscriber delegate(LazyRedisEventSubscriber subscriber) {
        return (RedisEventSubscriber) org.springframework.test.util.ReflectionTestUtils.getField(subscriber, "delegate");
    }

    private static ThreadPoolExecutor executor(RedisEventSubscriber subscriber) {
        return (ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(subscriber, "handlerExecutor");
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for recorded event processing");
            Thread.sleep(10);
        }
    }

    private static String required(String name) {
        return java.util.Objects.requireNonNull(System.getenv(name), name + " is required");
    }
}
