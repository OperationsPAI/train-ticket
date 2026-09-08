package com.trainticket.payment.domain;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

public final class ChannelRouter {
    // Only channels that payment-channel can actually settle belong here.
    //
    // This list used to carry APPLE_PAY (weight 5) and BALANCE (weight 5) as
    // well. Neither has a SIM counterpart: payment-channel's contract
    // (docs/08-contracts/api/payment-channel.md, docs/02-domains/payment-channel.md)
    // defines exactly three channels -- ALIPAY_SIM, WECHAT_SIM, UNIONPAY_SIM --
    // and rejects anything else with 400 VALIDATION_FAILED "unsupported channel".
    // channelFacingId had no mapping for those two and fell through to
    // `default -> value`, so payment sent the literal "APPLE_PAY", the channel
    // handoff failed, capture returned 500, and the booking saga died with
    // "payment failed". At a combined weight of 10 out of 100 that was roughly
    // one payment in ten, which is why it read as flaky rather than broken.
    //
    // Adding the two to payment-channel instead would be the wrong fix: it would
    // claim support for channels the domain contract does not define. Routing to a
    // channel this platform cannot settle is the actual defect.
    private static final List<PaymentChannel> DEFAULT_CHANNELS = List.of(
        new PaymentChannel("ALIPAY", "ALIPAY", 500_000, 30, true, 45),
        new PaymentChannel("WECHAT_PAY", "WECHAT_PAY", 200_000, 30, true, 40),
        new PaymentChannel("UNIONPAY", "UNIONPAY", 1_000_000, 60, true, 15)
    );

    private final List<PaymentChannel> channels;

    public ChannelRouter(List<PaymentChannel> channels) {
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels are required"));
        if (this.channels.isEmpty()) {
            throw new DomainRuleViolation("at least one payment channel is required");
        }
    }

    public static ChannelRouter defaults() {
        return new ChannelRouter(DEFAULT_CHANNELS);
    }

    public PaymentChannel route(Money amount, String preferredChannel) {
        Objects.requireNonNull(amount, "amount is required");
        if (preferredChannel != null && !preferredChannel.isBlank()) {
            PaymentChannel preferred = find(preferredChannel);
            if (!preferred.enabled()) {
                return fallback(amount);
            }
            if (amount.toMinorUnits() > preferred.maxAmountMinor()) {
                throw new DomainRuleViolation("AMOUNT_EXCEEDS_CHANNEL_LIMIT");
            }
            return preferred;
        }
        return fallback(amount);
    }

    private PaymentChannel fallback(Money amount) {
        return fallback(amount, ThreadLocalRandom.current());
    }

    PaymentChannel fallback(Money amount, RandomGenerator random) {
        List<PaymentChannel> eligible = channels.stream()
            .filter(PaymentChannel::enabled)
            .filter(channel -> amount.toMinorUnits() <= channel.maxAmountMinor())
            .toList();
        if (eligible.isEmpty()) {
            throw new DomainRuleViolation("AMOUNT_EXCEEDS_CHANNEL_LIMIT");
        }

        int totalWeight = eligible.stream().mapToInt(PaymentChannel::weight).sum();
        if (totalWeight <= 0) {
            return eligible.get(random.nextInt(eligible.size()));
        }

        int cursor = random.nextInt(totalWeight);
        for (PaymentChannel channel : eligible) {
            cursor -= channel.weight();
            if (cursor < 0) {
                return channel;
            }
        }
        return eligible.getLast();
    }

    public PaymentChannel requireAvailable(String channelId, Money amount) {
        PaymentChannel channel = find(channelId);
        if (!channel.enabled()) {
            throw new DomainRuleViolation("payment channel is unavailable");
        }
        if (amount.toMinorUnits() > channel.maxAmountMinor()) {
            throw new DomainRuleViolation("AMOUNT_EXCEEDS_CHANNEL_LIMIT");
        }
        return channel;
    }

    public PaymentChannel requireEnabled(String channelId) {
        PaymentChannel channel = find(channelId);
        if (!channel.enabled()) {
            throw new DomainRuleViolation("payment channel is unavailable");
        }
        return channel;
    }

    public List<PaymentChannel> channels() {
        return channels;
    }

    public boolean isSupportedChannel(String channelId) {
        try {
            find(channelId);
            return true;
        } catch (DomainRuleViolation violation) {
            return false;
        }
    }

    public boolean isEnabled(String channelId) {
        return find(channelId).enabled();
    }

    private PaymentChannel find(String channelId) {
        String normalized = normalize(channelId);
        return channels.stream()
            .filter(channel -> channel.channelId().equals(normalized) || channel.channelType().equals(normalized))
            .findFirst()
            .orElseThrow(() -> new DomainRuleViolation("unsupported payment channel"));
    }

    public static String normalize(String channelId) {
        String value = Objects.requireNonNull(channelId, "channelId is required").trim();
        if (value.isBlank()) {
            throw new DomainRuleViolation("channelId must not be blank");
        }
        return switch (value) {
            case "ALIPAY_SIM" -> "ALIPAY";
            case "WECHAT_SIM" -> "WECHAT_PAY";
            case "UNIONPAY_SIM" -> "UNIONPAY";
            default -> value;
        };
    }

    /**
     * Inverse of {@link #normalize}: maps an internal channel id to the
     * payment-channel-facing (simulated provider) channel id used on the
     * payment-channel handoff contract. Channels without a provider mapping
     * pass through unchanged.
     */
    /**
     * Maps a platform channel id to the channel-facing id payment-channel expects.
     *
     * Fails loudly on an unmapped channel rather than passing the value through.
     * The previous `default -> value` turned a configuration mistake into a 400
     * from payment-channel, surfaced as a 500 from capture and a dead booking saga
     * with terminalReason "payment failed" -- three hops from the cause, and the
     * response body naming the real reason ("unsupported channel") was discarded
     * by the client. A ChannelRouter constructed with a channel that has no SIM
     * counterpart is a deployment error; it should be impossible to route to it,
     * not merely expensive to diagnose afterwards.
     */
    public static String channelFacingId(String channelId) {
        String value = Objects.requireNonNull(channelId, "channelId is required").trim();
        return switch (value) {
            case "ALIPAY" -> "ALIPAY_SIM";
            case "WECHAT_PAY" -> "WECHAT_SIM";
            case "UNIONPAY" -> "UNIONPAY_SIM";
            // Already channel-facing: the refund path reads the channel back off a
            // stored ChannelRef, which holds the SIM id.
            case "ALIPAY_SIM", "WECHAT_SIM", "UNIONPAY_SIM" -> value;
            default -> throw new DomainRuleViolation(
                "channel '" + value + "' has no payment-channel counterpart; payment-channel "
                    + "accepts only ALIPAY_SIM, WECHAT_SIM and UNIONPAY_SIM. Routing to it "
                    + "would fail the channel handoff and kill the booking saga.");
        };
    }
}
