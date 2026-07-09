package com.trainticket.walletpromotion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import com.trainticket.platformkit.persistence.OutboxAppender;
import com.trainticket.walletpromotion.application.WalletPromotionServiceTest;
import com.trainticket.walletpromotion.domain.Money;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.ReasonType;
import com.trainticket.walletpromotion.domain.WalletAccount;
import com.trainticket.walletpromotion.domain.WalletPromotionEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;

class PostgresPromotionRepositoryOptimisticConcurrencyTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void newSnapshotUsesInsertConflictGuard() {
        JdbcOperations jdbc = org.mockito.Mockito.mock(JdbcOperations.class);
        org.mockito.Mockito.when(jdbc.update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class)))
            .thenReturn(1);
        CapturingRepository repository = new CapturingRepository(jdbc, objectMapper);
        PromotionInstrument benefit = baseBenefit();

        repository.saveMutation(benefit, wallet(benefit), event(benefit), null, null);

        List<String> sql = repository.updateSql();

        assertThat(sql).noneMatch(statement -> statement.contains("ON CONFLICT(id) DO UPDATE"));
        assertThat(sql).anyMatch(statement -> statement.startsWith("INSERT INTO promotion_instrument_snapshots") && statement.contains("ON CONFLICT DO NOTHING"));
    }

    @Test
    void staleSnapshotUpdateRaisesOptimisticConcurrencyException() {
        JdbcOperations jdbc = org.mockito.Mockito.mock(JdbcOperations.class);
        org.mockito.Mockito.when(jdbc.update(org.mockito.ArgumentMatchers.startsWith("UPDATE promotion_instrument_snapshots"), org.mockito.ArgumentMatchers.any(Object[].class)))
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
            org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeast(0)).update(captor.capture(), org.mockito.ArgumentMatchers.any(Object[].class));
            return new ArrayList<>(captor.getAllValues());
        }
    }
}
