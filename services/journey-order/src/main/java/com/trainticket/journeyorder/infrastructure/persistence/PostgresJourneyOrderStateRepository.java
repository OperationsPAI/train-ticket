package com.trainticket.journeyorder.infrastructure.persistence;

import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.application.service.JourneyOrderStateRepository;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.journeyorder.domain.AccountOrderState;
import com.trainticket.journeyorder.domain.JourneyOrder;
import com.trainticket.platformkit.persistence.ProcessedEventStore;
import com.trainticket.platformkit.persistence.SnapshotRepository;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresJourneyOrderStateRepository implements JourneyOrderStateRepository {
    private static final String CREATE_KIND = "CREATE";
    private static final String CANCEL_KIND = "CANCEL";

    private final ObjectMapper mapper;
    private final SnapshotRepository<JacksonJourneyOrderJson.JourneyOrderSnapshot> orders;
    private final SnapshotRepository<JacksonJourneyOrderJson.AccountOrderStateSnapshot> accounts;
    private final JdbcOperations jdbc;
    private final ProcessedEventStore events;

    @Autowired
    public PostgresJourneyOrderStateRepository(DataSource dataSource, ObjectMapper mapper) {
        this(
            mapper,
            new SnapshotRepository<>(
                dataSource,
                mapper,
                "journey_order_snapshots",
                JacksonJourneyOrderJson.JourneyOrderSnapshot.class
            ),
            new SnapshotRepository<>(
                dataSource,
                mapper,
                "account_order_state_snapshots",
                JacksonJourneyOrderJson.AccountOrderStateSnapshot.class
            ),
            new JdbcTemplate(dataSource),
            new ProcessedEventStore(dataSource)
        );
    }

    PostgresJourneyOrderStateRepository(
        ObjectMapper mapper,
        SnapshotRepository<JacksonJourneyOrderJson.JourneyOrderSnapshot> orders,
        SnapshotRepository<JacksonJourneyOrderJson.AccountOrderStateSnapshot> accounts,
        JdbcOperations jdbc,
        ProcessedEventStore events
    ) {
        this.mapper = mapper;
        this.orders = orders;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    public Optional<OrderManagementService.StoredOrder> findOrder(String orderId) {
        return orders.get(orderId).map(snapshot -> new OrderManagementService.StoredOrder(
            JacksonJourneyOrderJson.toOrder(snapshot.data(), mapper).withVersion(snapshot.version()),
            snapshot.data().data().path("idempotencyKey").asText()
        ));
    }

    @Override
    public List<OrderManagementService.StoredOrder> listOrders(String accountId, String status, int limit, int offset) {
        boolean byAccount = accountId != null && !accountId.isBlank();
        boolean byStatus = status != null && !status.isBlank();
        return jdbc.query(
            """
                SELECT id
                FROM journey_order_snapshots
                WHERE (? = false OR data->>'accountId' = ?)
                  AND (? = false OR data->>'status' = ?)
                ORDER BY (data->>'createdAt')::timestamptz DESC
                LIMIT ? OFFSET ?
                """,
            (rs, rowNum) -> findOrder(rs.getString("id")).orElseThrow(),
            byAccount,
            accountId,
            byStatus,
            status,
            limit,
            offset
        );
    }

    @Override
    public long countOrders(String accountId, String status) {
        boolean byAccount = accountId != null && !accountId.isBlank();
        boolean byStatus = status != null && !status.isBlank();
        Long count = jdbc.queryForObject(
            """
                SELECT count(*)
                FROM journey_order_snapshots
                WHERE (? = false OR data->>'accountId' = ?)
                  AND (? = false OR data->>'status' = ?)
                """,
            Long.class,
            byAccount,
            accountId,
            byStatus,
            status
        );
        return count == null ? 0 : count;
    }

    @Override
    public void saveOrder(JourneyOrder order, String idempotencyKey) {
        long newVersion = orders.save(
            order.orderId(),
            order.version(),
            JacksonJourneyOrderJson.orderSnapshot(order, idempotencyKey, mapper)
        );
        order.withVersion(newVersion);
    }

    @Override
    public Optional<AccountOrderState> findAccountState(String accountId) {
        return accounts.get(accountId)
            .map(snapshot -> AccountOrderState.valueOf(snapshot.data().data().path("state").asText()));
    }

    @Override
    public void saveAccountState(String accountId, AccountOrderState state) {
        long expectedVersion = accounts.get(accountId).map(snapshot -> snapshot.version()).orElse(0L);
        accounts.save(accountId, expectedVersion, JacksonJourneyOrderJson.accountStateSnapshot(accountId, state, mapper));
    }

    @Override
    public Optional<OrderManagementService.IdempotencyEntry<JourneyOrderResult>> findCreateIdempotency(String key) {
        return findIdempotency(idempotencyRecordKey(CREATE_KIND, key), CREATE_KIND, JourneyOrderResult.class);
    }

    @Override
    public void saveCreateIdempotency(String key, OrderManagementService.IdempotencyEntry<JourneyOrderResult> entry) {
        saveIdempotency(idempotencyRecordKey(CREATE_KIND, key), CREATE_KIND, entry);
    }

    @Override
    public Optional<OrderManagementService.IdempotencyEntry<CancelJourneyOrderResult>> findCancelIdempotency(String key) {
        return findIdempotency(idempotencyRecordKey(CANCEL_KIND, key), CANCEL_KIND, CancelJourneyOrderResult.class);
    }

    @Override
    public void saveCancelIdempotency(String key, OrderManagementService.IdempotencyEntry<CancelJourneyOrderResult> entry) {
        saveIdempotency(idempotencyRecordKey(CANCEL_KIND, key), CANCEL_KIND, entry);
    }

    @Override
    public boolean recordProcessedEvent(String eventId, String stream) {
        return events.recordIfNew(eventId, stream);
    }

    private static String idempotencyRecordKey(String kind, String key) {
        return "journey-order:" + kind.toLowerCase() + ":" + key;
    }

    private <T> Optional<OrderManagementService.IdempotencyEntry<T>> findIdempotency(String key, String kind, Class<T> resultType) {
        return jdbc.query(
            "SELECT request_hash, response_body::text AS response_body FROM idempotency_records WHERE key = ? AND kind = ?",
            rs -> {
                if (!rs.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(new OrderManagementService.IdempotencyEntry<>(
                        rs.getString("request_hash"),
                        mapper.readValue(rs.getString("response_body"), resultType)
                    ));
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("idempotency record could not be decoded", exception);
                }
            },
            key,
            kind
        );
    }

    private void saveIdempotency(String key, String kind, OrderManagementService.IdempotencyEntry<?> entry) {
        try {
            jdbc.update(
                """
                    INSERT INTO idempotency_records(key, kind, request_hash, response_body)
                    VALUES (?, ?, ?, ?::jsonb)
                    ON CONFLICT (key) DO NOTHING
                    """,
                key,
                kind,
                entry.requestFingerprint(),
                mapper.writeValueAsString(entry.result())
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("idempotency record could not be encoded", exception);
        }
    }
}
