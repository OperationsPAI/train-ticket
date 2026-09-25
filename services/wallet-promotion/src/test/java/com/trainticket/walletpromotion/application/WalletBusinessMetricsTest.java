package com.trainticket.walletpromotion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainticket.platformkit.http.ApiException;
import com.trainticket.walletpromotion.domain.ApplicableScope;
import com.trainticket.walletpromotion.domain.BalanceType;
import com.trainticket.walletpromotion.domain.BenefitScopeType;
import com.trainticket.walletpromotion.domain.BenefitType;
import com.trainticket.walletpromotion.domain.IssuanceSource;
import com.trainticket.walletpromotion.domain.Money;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.ReasonType;
import com.trainticket.walletpromotion.domain.RedemptionRule;
import com.trainticket.walletpromotion.domain.RevocationRule;
import com.trainticket.walletpromotion.infrastructure.persistence.InMemoryPromotionRepository;
import com.trainticket.walletpromotion.metrics.CapturingMetricExporter;
import com.trainticket.walletpromotion.metrics.OpenTelemetryWalletPromotionMetrics;
import com.trainticket.walletpromotion.metrics.WalletBalanceState;
import com.trainticket.walletpromotion.metrics.WalletPromotionMetrics;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WalletBusinessMetricsTest {
    private static final AttributeKey<String> STATE = AttributeKey.stringKey("state");
    private static final AttributeKey<String> CURRENCY = AttributeKey.stringKey("currency");
    private static final Instant VALID_FROM = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-25T04:00:00Z");

    @Test
    void balanceGaugeIsReconstructedFromDurableStateAfterARestart() {
        InMemoryPromotionRepository repository = new InMemoryPromotionRepository(Optional.empty());
        WalletPromotionService service = service(repository, WalletPromotionMetrics.noop());

        PromotionInstrument spendable = service.issue(issue("acc-1", 100, Instant.parse("2030-01-01T00:00:00Z")));
        service.reserve(spendable.benefitId(), new ReserveBenefitCommand(
            new Money("USD", 30), "res-1", NOW.plus(Duration.ofMinutes(30)), WalletPromotionServiceTest.use("ord-1"),
            WalletPromotionServiceTest.reason(ReasonType.ORDER_PURCHASE, "RESERVE-1", "ORDER", "ord-1")
        ));
        WalletPromotionService.RedeemResult redeemed = service.redeem(spendable.benefitId(), new RedeemBenefitCommand(
            new Money("USD", 30), "red-1", "res-1", WalletPromotionServiceTest.use("ord-1"),
            WalletPromotionServiceTest.reason(ReasonType.ORDER_PURCHASE, "USE-1", "ORDER", "ord-1")
        ));
        service.reverse(spendable.benefitId(), new ReverseRedemptionCommand(
            redeemed.redemption().redemptionId(), "rev-1", new Money("USD", 10),
            WalletPromotionServiceTest.reason(ReasonType.REVERSAL, "REVERSAL-1", "ORDER", "ord-1")
        ));
        // A second, different wallet whose whole amount expires unused.
        service.issue(issue("acc-2", 40, Instant.parse("2021-01-01T00:00:00Z")));
        service.expireDueBenefits();

        // "Restart": a brand-new recorder and meter provider whose gauge reads the
        // repository rather than any state the first service instance held.
        CapturingMetricExporter exporter = new CapturingMetricExporter();
        try (SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(Duration.ofDays(1)).build())
            .build()) {
            new OpenTelemetryWalletPromotionMetrics(provider.get("wallet-promotion-test"), repository::walletBalanceSnapshot);
            provider.forceFlush().join(10, TimeUnit.SECONDS);

            var points = exporter.metric("train_ticket.wallet.balance").getLongGaugeData().getPoints();
            // Two different wallets (different accounts and benefits) produce one
            // series per (state, currency); no identifier reaches a label.
            assertThat(points)
                .extracting(point -> point.getAttributes().get(STATE) + "=" + point.getValue())
                .containsExactlyInAnyOrder(
                    "available=80",   // 100 issued - 30 redeemed + 10 reversed back
                    "reserved=0",
                    "redeemed=20",    // 30 redeemed - 10 reversed
                    "expired=40",     // acc-2's whole benefit
                    "reversed=10"
                );
            for (var point : points) {
                assertThat(point.getAttributes().get(CURRENCY)).isEqualTo("USD");
                assertThat(point.getAttributes().size()).isEqualTo(2);
            }
            // Conservation: issued equals the money still held plus what left.
            long available = value(points, WalletBalanceState.AVAILABLE);
            long reserved = value(points, WalletBalanceState.RESERVED);
            long redeemedValue = value(points, WalletBalanceState.REDEEMED);
            long expired = value(points, WalletBalanceState.EXPIRED);
            assertThat(available + reserved + redeemedValue + expired).isEqualTo(140);
        }
    }

    @Test
    void operationsAreRecordedWithBoundedOutcomesAndNoIdentifiers() {
        InMemoryPromotionRepository repository = new InMemoryPromotionRepository(Optional.empty());
        RecordingMetrics recorded = new RecordingMetrics();
        WalletPromotionService service = service(repository, recorded);

        IssueBenefitCommand command = issue("acc-1", 100, Instant.parse("2030-01-01T00:00:00Z"));
        PromotionInstrument benefit = service.issue(command);
        service.issue(command);
        assertThatThrownBy(() -> service.reserve(benefit.benefitId(), new ReserveBenefitCommand(
            new Money("USD", 500), "res-too-big", NOW.plus(Duration.ofMinutes(30)), WalletPromotionServiceTest.use("ord-1"),
            WalletPromotionServiceTest.reason(ReasonType.ORDER_PURCHASE, "RESERVE-BIG", "ORDER", "ord-1")
        ))).isInstanceOf(ApiException.class);

        assertThat(recorded.calls).containsExactly(
            new RecordingMetrics.Call(WalletPromotionMetrics.Operation.ISSUE, WalletPromotionMetrics.Outcome.SUCCESS, WalletPromotionMetrics.ReasonCode.MANUAL_OPS, "USD"),
            new RecordingMetrics.Call(WalletPromotionMetrics.Operation.ISSUE, WalletPromotionMetrics.Outcome.REPLAYED, WalletPromotionMetrics.ReasonCode.MANUAL_OPS, "USD"),
            new RecordingMetrics.Call(WalletPromotionMetrics.Operation.RESERVE, WalletPromotionMetrics.Outcome.REJECTED, WalletPromotionMetrics.ReasonCode.ORDER_PURCHASE, "USD")
        );
        for (RecordingMetrics.Call call : recorded.calls) {
            assertThat(call.currency()).isEqualTo("USD");
            assertThat(call.toString()).doesNotContain(benefit.benefitId());
            assertThat(call.toString()).doesNotContain("acc-1");
        }
    }

    private static long value(Collection<io.opentelemetry.sdk.metrics.data.LongPointData> points, WalletBalanceState state) {
        return points.stream()
            .filter(point -> state.value().equals(point.getAttributes().get(STATE)))
            .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing state " + state.value()));
    }

    private static WalletPromotionService service(InMemoryPromotionRepository repository, WalletPromotionMetrics metrics) {
        return new WalletPromotionService(
            repository,
            (benefit, useReference, businessReason) -> { },
            Clock.fixed(NOW, ZoneOffset.UTC),
            metrics
        );
    }

    private static IssueBenefitCommand issue(String accountId, long amount, Instant validUntil) {
        return new IssueBenefitCommand(
            accountId,
            BenefitType.BALANCE,
            BalanceType.PROMOTION_CREDIT,
            new Money("USD", amount),
            IssuanceSource.MANUAL_OPS,
            null,
            new ApplicableScope(BenefitScopeType.ANY_TRIP, null, "USD"),
            new RedemptionRule(false, null, true),
            new RevocationRule(null),
            VALID_FROM,
            validUntil,
            WalletPromotionServiceTest.reason(ReasonType.MANUAL_OPS, "TEST", "MANUAL_ACTION", accountId)
        );
    }

    private static final class RecordingMetrics implements WalletPromotionMetrics {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public void operation(Operation operation, Outcome outcome, ReasonCode reasonCode, String currency) {
            calls.add(new Call(operation, outcome, reasonCode, currency));
        }

        private record Call(Operation operation, Outcome outcome, ReasonCode reasonCode, String currency) {
            @Override
            public String toString() {
                return operation.value() + "/" + outcome.value() + "/" + reasonCode.value() + "/" + currency;
            }
        }
    }
}
