package com.trainticket.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the boundary between the channels payment ROUTES to and the channels
 * payment-channel can SETTLE.
 *
 * The incident: DEFAULT_CHANNELS carried APPLE_PAY and BALANCE at a combined
 * weight of 10 out of 100, neither of which has a SIM counterpart.
 * channelFacingId had no case for them and fell through to `default -> value`, so
 * payment posted the literal "APPLE_PAY" to payment-channel, which answered 400
 * VALIDATION_FAILED "unsupported channel" -- a response body the client then
 * discarded, keeping only the status. Capture surfaced 500, the booking saga died
 * with terminalReason "payment failed", and roughly one payment in ten failed for
 * a reason that took three hops and a code change to see.
 *
 * These tests make that shape impossible to reintroduce silently.
 */
class ChannelRouterTest {
    /**
     * The exact set from docs/08-contracts/api/payment-channel.md. If
     * payment-channel's contract grows a channel, this list changes with it --
     * deliberately, in one place, rather than being discovered in production.
     */
    private static final List<String> SETTLEABLE = List.of("ALIPAY_SIM", "WECHAT_SIM", "UNIONPAY_SIM");

    @Test
    void everyDefaultChannelMapsToAChannelPaymentChannelCanSettle() {
        ChannelRouter router = ChannelRouter.defaults();
        // Route enough times to hit every weighted channel rather than trusting a
        // single draw. Any channel the router can pick must be settleable.
        for (int i = 0; i < 500; i++) {
            PaymentChannel picked = router.route(Money.fromMinorUnits(10_000, "CNY"), null);
            String facing = ChannelRouter.channelFacingId(picked.channelId());
            assertThat(facing)
                .as("router picked %s, which maps to %s -- payment-channel accepts only %s",
                    picked.channelId(), facing, SETTLEABLE)
                .isIn(SETTLEABLE);
        }
    }

    @Test
    void mapsThePlatformChannelIdsToTheirSimCounterparts() {
        assertThat(ChannelRouter.channelFacingId("ALIPAY")).isEqualTo("ALIPAY_SIM");
        assertThat(ChannelRouter.channelFacingId("WECHAT_PAY")).isEqualTo("WECHAT_SIM");
        assertThat(ChannelRouter.channelFacingId("UNIONPAY")).isEqualTo("UNIONPAY_SIM");
    }

    @Test
    void passesAnAlreadyChannelFacingIdThrough() {
        // The refund path reads the channel back off a stored ChannelRef. Today that
        // holds the platform id, but accepting the SIM id keeps this idempotent
        // rather than making the mapping order-dependent.
        for (String sim : SETTLEABLE) {
            assertThat(ChannelRouter.channelFacingId(sim)).isEqualTo(sim);
        }
    }

    @Test
    void rejectsAChannelWithNoSimCounterpart() {
        // The regression itself: these two used to pass through unchanged.
        assertThatThrownBy(() -> ChannelRouter.channelFacingId("APPLE_PAY"))
            .isInstanceOf(DomainRuleViolation.class)
            .hasMessageContaining("APPLE_PAY")
            .hasMessageContaining("ALIPAY_SIM");
        assertThatThrownBy(() -> ChannelRouter.channelFacingId("BALANCE"))
            .isInstanceOf(DomainRuleViolation.class)
            .hasMessageContaining("BALANCE");
    }

    @Test
    void aCustomRouterCannotSilentlyRouteToAnUnsettleableChannel() {
        // A ChannelRouter built with an arbitrary channel list is the other way in.
        // It is allowed to construct one -- the limit belongs to the handoff, not to
        // the router -- but the handoff must refuse it rather than pass it on.
        ChannelRouter custom = new ChannelRouter(List.of(
            new PaymentChannel("CRYPTO", "CRYPTO", 100_000, 30, true, 100)));
        PaymentChannel picked = custom.route(Money.fromMinorUnits(10_000, "CNY"), null);
        assertThat(picked.channelId()).isEqualTo("CRYPTO");
        assertThatThrownBy(() -> ChannelRouter.channelFacingId(picked.channelId()))
            .isInstanceOf(DomainRuleViolation.class)
            .hasMessageContaining("CRYPTO");
    }

    @Test
    void defaultWeightsStillSumToOneHundred() {
        // Removing two channels meant redistributing their weight. A total other
        // than 100 does not break routing but makes the intended mix unreadable.
        ChannelRouter router = ChannelRouter.defaults();
        assertThat(router.channels().stream().mapToInt(PaymentChannel::weight).sum()).isEqualTo(100);
    }
}
