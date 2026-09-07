package com.trainticket.platformkit.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.lettuce.core.protocol.CommandArgs;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class LettuceRedisStreamOperationsTest {
    @Test
    void publishIssuesXaddWithApproximateMaxlenTrimming() {
        RedisCommands<String, String> commands = syncCommands();
        StatefulRedisConnection<String, String> connection = connectionFor(commands);

        new LettuceRedisStreamOperations(connection).publish("events:payment", "{\"eventId\":\"evt-1\"}");

        ArgumentCaptor<XAddArgs> args = ArgumentCaptor.forClass(XAddArgs.class);
        verify(commands).xadd(eq("events:payment"), args.capture(), ArgumentMatchers.<String, String>anyMap());
        assertThat(commandString(args.getValue()))
            .contains("MAXLEN")
            .contains("~")
            .contains(String.valueOf(LettuceRedisStreamOperations.configuredStreamMaxLen()));
    }

    @Test
    void dlqWritesAreCappedWithApproximateMaxlenTrimming() {
        RedisCommands<String, String> commands = syncCommands();
        StatefulRedisConnection<String, String> connection = connectionFor(commands);

        new LettuceRedisStreamOperations(connection).moveToDlq(
            "events:payment",
            "{\"eventId\":\"evt-1\"}",
            new DlqMetadata("journey-order", "consumer-1", "MaxDeliveryAttempts", 5, Instant.now().toString())
        );

        ArgumentCaptor<XAddArgs> args = ArgumentCaptor.forClass(XAddArgs.class);
        verify(commands).xadd(eq("events:payment:dlq"), args.capture(), ArgumentMatchers.<String, String>anyMap());
        assertThat(commandString(args.getValue()))
            .contains("MAXLEN")
            .contains("~")
            .contains(String.valueOf(LettuceRedisStreamOperations.configuredStreamMaxLen()));
    }

    @Test
    void streamMaxLenDefaultsWhenUnsetOrInvalid() {
        assertThat(LettuceRedisStreamOperations.streamMaxLen(null))
            .isEqualTo(LettuceRedisStreamOperations.DEFAULT_STREAM_MAXLEN);
        assertThat(LettuceRedisStreamOperations.streamMaxLen("  "))
            .isEqualTo(LettuceRedisStreamOperations.DEFAULT_STREAM_MAXLEN);
        assertThat(LettuceRedisStreamOperations.streamMaxLen("not-a-number"))
            .isEqualTo(LettuceRedisStreamOperations.DEFAULT_STREAM_MAXLEN);
        assertThat(LettuceRedisStreamOperations.streamMaxLen("0"))
            .isEqualTo(LettuceRedisStreamOperations.DEFAULT_STREAM_MAXLEN);
        assertThat(LettuceRedisStreamOperations.streamMaxLen("-5"))
            .isEqualTo(LettuceRedisStreamOperations.DEFAULT_STREAM_MAXLEN);
        assertThat(LettuceRedisStreamOperations.DEFAULT_STREAM_MAXLEN).isEqualTo(10_000L);
    }

    @Test
    void streamMaxLenHonoursConfiguredOverride() {
        assertThat(LettuceRedisStreamOperations.streamMaxLen("2500")).isEqualTo(2_500L);
        assertThat(LettuceRedisStreamOperations.streamMaxLen(" 750 ")).isEqualTo(750L);
    }

    @Test
    void autoClaimMinIdleIsLongEnoughThatBacklogCannotLookLikeRepeatedFailure() {
        // The redelivery counter ticks once per reclaim, so this window is that counter's unit.
        assertThat(LettuceRedisStreamOperations.configuredAutoClaimMinIdleMillis())
            .isGreaterThanOrEqualTo(LettuceRedisStreamOperations.DEFAULT_AUTOCLAIM_MIN_IDLE_MS);
        assertThat(LettuceRedisStreamOperations.DEFAULT_AUTOCLAIM_MIN_IDLE_MS).isEqualTo(300_000L);
        assertThat(LettuceRedisStreamOperations.autoClaimMinIdleMillis(null))
            .isEqualTo(LettuceRedisStreamOperations.DEFAULT_AUTOCLAIM_MIN_IDLE_MS);
        assertThat(LettuceRedisStreamOperations.autoClaimMinIdleMillis("not-a-number"))
            .isEqualTo(LettuceRedisStreamOperations.DEFAULT_AUTOCLAIM_MIN_IDLE_MS);
        assertThat(LettuceRedisStreamOperations.autoClaimMinIdleMillis("0"))
            .isEqualTo(LettuceRedisStreamOperations.DEFAULT_AUTOCLAIM_MIN_IDLE_MS);
        assertThat(LettuceRedisStreamOperations.autoClaimMinIdleMillis("90000")).isEqualTo(90_000L);
    }

    @Test
    void autoClaimIssuesXautoclaimWithTheConfiguredMinIdleWindow() {
        RedisCommands<String, String> commands = syncCommands();
        StatefulRedisConnection<String, String> connection = connectionFor(commands);
        when(commands.xautoclaim(anyString(), any(XAutoClaimArgs.class)))
            .thenReturn(new ClaimedMessages<>("0-0", List.of()));

        new LettuceRedisStreamOperations(connection).autoClaim("events:payment", "journey-order", "consumer-1");

        ArgumentCaptor<XAutoClaimArgs<String>> args = ArgumentCaptor.captor();
        verify(commands).xautoclaim(eq("events:payment"), args.capture());
        CommandArgs<String, String> commandArgs = new CommandArgs<>(StringCodec.UTF8);
        args.getValue().build(commandArgs);
        assertThat(commandArgs.toCommandString())
            .contains(String.valueOf(LettuceRedisStreamOperations.configuredAutoClaimMinIdleMillis()));
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> syncCommands() {
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.xadd(anyString(), any(XAddArgs.class), ArgumentMatchers.<String, String>anyMap())).thenReturn("1-0");
        return commands;
    }

    @SuppressWarnings("unchecked")
    private static StatefulRedisConnection<String, String> connectionFor(RedisCommands<String, String> commands) {
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        when(connection.sync()).thenReturn(commands);
        return connection;
    }

    private static String commandString(XAddArgs args) {
        CommandArgs<String, String> commandArgs = new CommandArgs<>(StringCodec.UTF8);
        args.build(commandArgs);
        return commandArgs.toCommandString();
    }
}
