package com.trainticket.walletpromotion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.walletpromotion.application.WalletPromotionServiceTest;
import com.trainticket.walletpromotion.domain.Money;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.ReasonType;
import com.trainticket.walletpromotion.domain.WalletAccount;
import com.trainticket.walletpromotion.domain.WalletPromotionEvent;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementSetter;

class PostgresPromotionRepositoryOptimisticConcurrencyTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void newSnapshotUsesInsertConflictGuard() {
        JdbcOperations jdbc = org.mockito.Mockito.mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);
        CapturingRepository repository = new CapturingRepository(jdbc, objectMapper);
        PromotionInstrument benefit = baseBenefit();

        repository.saveMutation(benefit, wallet(benefit), event(benefit), null, null);

        List<String> sql = repository.updateSql();

        assertThat(sql).noneMatch(statement -> statement.contains("ON CONFLICT(id) DO UPDATE"));
        assertThat(sql).anyMatch(statement -> statement.startsWith("INSERT INTO promotion_instrument_snapshots") && statement.contains("ON CONFLICT DO NOTHING"));
    }

    @Test
    void insertSnapshotBindsIdVersionThenJsonSnapshot() throws Exception {
        JdbcOperations jdbc = org.mockito.Mockito.mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);
        PostgresPromotionRepository repository = new PostgresPromotionRepository(
            jdbc,
            objectMapper,
            new OutboxAppender(jdbc, objectMapper)
        );
        PromotionInstrument benefit = baseBenefit();
        WalletAccount wallet = wallet(benefit);

        repository.saveMutation(benefit, wallet, event(benefit), null, null);

        ArgumentCaptor<PreparedStatementSetter> setters = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc, atLeastOnce()).update(startsWith("INSERT INTO promotion_instrument_snapshots"), setters.capture());
        BoundStatement statement = BoundStatement.capture(setters.getAllValues().getFirst());

        assertThat(statement.values()).hasSize(3);
        assertThat(statement.values()).containsExactly(benefit.benefitId(), benefit.version(), objectMapper.writeValueAsString(benefit));
        assertThat(statement.boundTypes()).containsExactly(String.class, Long.class, String.class);
        JsonNode roundTripped = objectMapper.readTree((String) statement.values().get(2));
        assertThat(roundTripped.get("benefitId").asText()).isEqualTo(benefit.benefitId());
        assertThat(roundTripped.get("version").asLong()).isEqualTo(benefit.version());
    }

    @Test
    void updateSnapshotBindsNewVersionJsonIdThenExpectedVersion() throws Exception {
        JdbcOperations jdbc = org.mockito.Mockito.mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);
        PostgresPromotionRepository repository = new PostgresPromotionRepository(
            jdbc,
            objectMapper,
            new OutboxAppender(jdbc, objectMapper)
        );
        PromotionInstrument updated = baseBenefit().reserve(
            new Money("USD", 10),
            WalletPromotionServiceTest.reason(ReasonType.ORDER_PURCHASE, "RESERVE", "ORDER", "ord-1"),
            Instant.parse("2026-02-01T00:00:00Z")
        );
        WalletAccount wallet = wallet(updated);

        repository.saveMutation(updated, wallet, event(updated), null, null);

        ArgumentCaptor<PreparedStatementSetter> setters = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc, atLeastOnce()).update(startsWith("UPDATE promotion_instrument_snapshots"), setters.capture());
        BoundStatement statement = BoundStatement.capture(setters.getAllValues().getFirst());

        assertThat(statement.values()).hasSize(4);
        assertThat(statement.values()).containsExactly(updated.version(), objectMapper.writeValueAsString(updated), updated.benefitId(), updated.version() - 1);
        assertThat(statement.boundTypes()).containsExactly(Long.class, String.class, String.class, Long.class);
        JsonNode roundTripped = objectMapper.readTree((String) statement.values().get(1));
        assertThat(roundTripped.get("benefitId").asText()).isEqualTo(updated.benefitId());
        assertThat(roundTripped.get("updatedAt")).isNotNull();
        assertThat(roundTripped.get("version").asLong()).isEqualTo(updated.version());
    }

    @Test
    void staleSnapshotUpdateRaisesOptimisticConcurrencyException() {
        JdbcOperations jdbc = org.mockito.Mockito.mock(JdbcOperations.class);
        when(jdbc.update(startsWith("UPDATE promotion_instrument_snapshots"), any(PreparedStatementSetter.class)))
            .thenReturn(0);
        CapturingRepository repository = new CapturingRepository(jdbc, objectMapper);
        PromotionInstrument stale = baseBenefit().reserve(
            new Money("USD", 10),
            WalletPromotionServiceTest.reason(ReasonType.ORDER_PURCHASE, "RESERVE", "ORDER", "ord-1"),
            Instant.now()
        );

        assertThatThrownBy(() -> repository.saveMutation(stale, wallet(stale), event(stale), null, null))
            .isInstanceOf(OptimisticConcurrencyException.class);
    }

    private static PromotionInstrument baseBenefit() {
        return PromotionInstrument.issue(
            "ben-1",
            "acc-1",
            "wac-1",
            com.trainticket.walletpromotion.domain.BenefitType.BALANCE,
            com.trainticket.walletpromotion.domain.BalanceType.PROMOTION_CREDIT,
            new Money("USD", 100),
            com.trainticket.walletpromotion.domain.IssuanceSource.MANUAL_OPS,
            null,
            new com.trainticket.walletpromotion.domain.ApplicableScope("ANY_TRIP", null, "USD"),
            new com.trainticket.walletpromotion.domain.RedemptionRule(false, null, false),
            new com.trainticket.walletpromotion.domain.RevocationRule(null),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-08-01T00:00:00Z"),
            WalletPromotionServiceTest.reason(ReasonType.MANUAL_OPS, "TEST", "MANUAL_ACTION", "act-1"),
            Instant.now()
        );
    }

    private static WalletAccount wallet(PromotionInstrument benefit) {
        return WalletAccount.create(benefit.walletAccountId(), benefit.accountId(), Instant.now())
            .applyLedger(
                benefit.balanceType(),
                "USD",
                new Money("USD", 100),
                new Money("USD", 0),
                new Money("USD", 0),
                "wle-1",
                Instant.now()
            );
    }

    private static WalletPromotionEvent event(PromotionInstrument benefit) {
        return new WalletPromotionEvent(
            "BenefitIssued",
            Map.of("walletBalanceDelta", Map.of("ledgerEntryId", "wle-1")),
            Instant.now(),
            benefit.version()
        );
    }

    private static final class CapturingRepository extends PostgresPromotionRepository {
        private final JdbcOperations jdbc;

        private CapturingRepository(JdbcOperations jdbc, ObjectMapper objectMapper) {
            super(jdbc, objectMapper, new OutboxAppender(jdbc, objectMapper));
            this.jdbc = jdbc;
        }

        private List<String> updateSql() {
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeast(0)).update(captor.capture(), any(PreparedStatementSetter.class));
            return new ArrayList<>(captor.getAllValues());
        }
    }

    private record BoundStatement(List<Object> values, List<Class<?>> boundTypes) {
        private static BoundStatement capture(PreparedStatementSetter setter) throws SQLException {
            PreparedStatement statement = org.mockito.Mockito.mock(PreparedStatement.class);
            Map<Integer, Object> values = new TreeMap<>();
            Map<Integer, Class<?>> types = new TreeMap<>();
            org.mockito.Mockito.doAnswer(invocation -> {
                int index = invocation.getArgument(0);
                values.put(index, invocation.getArgument(1));
                types.put(index, String.class);
                return null;
            }).when(statement).setString(org.mockito.Mockito.anyInt(), org.mockito.Mockito.anyString());
            org.mockito.Mockito.doAnswer(invocation -> {
                int index = invocation.getArgument(0);
                values.put(index, invocation.getArgument(1));
                types.put(index, Long.class);
                return null;
            }).when(statement).setLong(org.mockito.Mockito.anyInt(), org.mockito.Mockito.anyLong());

            setter.setValues(statement);

            return new BoundStatement(List.copyOf(values.values()), List.copyOf(types.values()));
        }
    }
}
