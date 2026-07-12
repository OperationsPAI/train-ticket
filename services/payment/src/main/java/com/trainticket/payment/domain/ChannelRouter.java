package com.trainticket.payment.domain;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

public final class ChannelRouter {
    private static final List<PaymentChannel> DEFAULT_CHANNELS = List.of(
        new PaymentChannel("ALIPAY", "ALIPAY", 500_000, 30, true, 40),
        new PaymentChannel("WECHAT_PAY", "WECHAT_PAY", 200_000, 30, true, 35),
        new PaymentChannel("UNIONPAY", "UNIONPAY", 1_000_000, 60, true, 15),
        new PaymentChannel("APPLE_PAY", "APPLE_PAY", 100_000, 30, true, 5),
        new PaymentChannel("BALANCE", "BALANCE", 50_000, 5, true, 5)
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
}
