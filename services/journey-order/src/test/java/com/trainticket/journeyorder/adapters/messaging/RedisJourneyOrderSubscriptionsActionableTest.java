package com.trainticket.journeyorder.adapters.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * `isActionable` decides whether an event reaches OrderManagementService.handle
 * at all, so it has to agree with that method's switch.
 *
 * Why the filter exists: handle() is @Transactional, so Spring opens a database
 * transaction on entry, and the method reads and writes processed_events around
 * its switch. An event type whose branch is just `new Success()` therefore still
 * costs a full transaction — measured at a p50 of 49 ms with handler timing
 * instrumented, on all 11 subscribed streams, while journey-order's backlog grew
 * continuously.
 *
 * Why this test exists: the two lists are in different files and neither
 * compiler nor runtime relates them. Listing a type in ACTIONABLE that the
 * switch ignores wastes a transaction. OMITTING one the switch handles drops the
 * event silently — no error, no DLQ entry, no log — which is the failure mode
 * this whole investigation kept running into.
 *
 * The switch is read out of the service source rather than exercised, because
 * the property under test is a statement about that switch's cases, not about
 * any one execution of it.
 */
class RedisJourneyOrderSubscriptionsActionableTest {
    private static final Path SERVICE_SOURCE = Path.of(
        "src/main/java/com/trainticket/journeyorder/application/service/OrderManagementService.java");

    @Test
    void everyHandledEventTypeIsActionable() throws IOException {
        String source = Files.readString(SERVICE_SOURCE);
        String switchBlock = handleSwitch(source);

        Set<String> handled = new LinkedHashSet<>();
        // Each `case "A", "B" -> handleSomething(envelope);` arm that dispatches to
        // a real method. Arms whose body is `new EventSubscriber.Success()` are the
        // deliberate no-ops and must NOT be required.
        Matcher arms = Pattern.compile(
            "case\\s+((?:\"[A-Za-z]+\"\\s*,?\\s*)+)->\\s*([^;]+);", Pattern.DOTALL).matcher(switchBlock);
        while (arms.find()) {
            String body = arms.group(2);
            if (body.contains("new EventSubscriber.Success()")) {
                continue;
            }
            Matcher literals = Pattern.compile("\"([A-Za-z]+)\"").matcher(arms.group(1));
            while (literals.find()) {
                handled.add(literals.group(1));
            }
        }

        assertThat(handled)
            .as("scraped no dispatching cases out of the switch -- this test has stopped testing anything")
            .isNotEmpty();

        for (String eventType : handled) {
            assertThat(RedisJourneyOrderSubscriptions.isActionable(eventType))
                .as("OrderManagementService.handle dispatches %s, but isActionable() rejects it, so "
                    + "the event is dropped before ever reaching the handler", eventType)
                .isTrue();
        }
    }

    @Test
    void theDeliberateNoOpTypesAreNotActionable() throws IOException {
        String switchBlock = handleSwitch(Files.readString(SERVICE_SOURCE));

        // The arm that returns Success() directly is the ignore list. Those types
        // must be filtered out, or the filter buys nothing for them.
        Matcher noOpArm = Pattern.compile(
            "case\\s+((?:\"[A-Za-z]+\"\\s*,?\\s*)+)->\\s*new EventSubscriber\\.Success\\(\\)", Pattern.DOTALL)
            .matcher(switchBlock);
        Set<String> ignored = new LinkedHashSet<>();
        while (noOpArm.find()) {
            Matcher literals = Pattern.compile("\"([A-Za-z]+)\"").matcher(noOpArm.group(1));
            while (literals.find()) {
                ignored.add(literals.group(1));
            }
        }

        assertThat(ignored)
            .as("the switch's explicit ignore list could not be located")
            .isNotEmpty();

        for (String eventType : ignored) {
            assertThat(RedisJourneyOrderSubscriptions.isActionable(eventType))
                .as("%s is an explicit no-op in the switch, so it must be filtered out before the "
                    + "@Transactional handler rather than paying for a transaction", eventType)
                .isFalse();
        }
    }

    @Test
    void anUnknownEventTypeIsNotActionable() {
        assertThat(RedisJourneyOrderSubscriptions.isActionable("SomethingNobodyHandles")).isFalse();
    }

    private static String handleSwitch(String source) {
        int from = source.indexOf("HandlerResult result = switch (envelope.eventType())");
        assertThat(from).as("could not locate the dispatch switch in the service source").isNotNegative();
        int to = source.indexOf("stateRepository.recordProcessedEvent", from);
        assertThat(to).as("could not locate the end of the dispatch switch").isGreaterThan(from);
        return source.substring(from, to);
    }
}
