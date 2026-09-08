package com.trainticket.postsales.application;

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
 * PostSalesEventHandler rejects uninteresting event types before writing to
 * processed_events, because charging a database round-trip to every event on
 * every subscribed stream starved the HTTP thread badly enough to fail the
 * readiness probe and restart the pod.
 *
 * That optimisation is only safe while isInteresting() and the dispatch it
 * guards agree. Listing a type that no branch handles wastes a write; OMITTING
 * one that a branch handles drops the event silently, with no failure anywhere.
 *
 * This test reads the handler's own source and checks that every event type the
 * dispatch compares against is one isInteresting() admits. Scraping the source
 * rather than calling the handler is deliberate: the dispatch is a private
 * if-chain over a live envelope, and the property under test is a statement about
 * that chain, not about any one execution of it.
 */
class PostSalesEventHandlerInterestTest {
    private static final Path SOURCE = Path.of(
        "src/main/java/com/trainticket/postsales/application/PostSalesEventHandler.java");

    @Test
    void everyEventTypeTheDispatchComparesAgainstIsAdmittedByIsInteresting() throws IOException {
        String source = Files.readString(SOURCE);

        String dispatch = between(source, "private EventSubscriber.HandlerResult handleInCurrentThread",
            "private boolean isInteresting");
        String predicate = between(source, "private boolean isInteresting", "private static Optional");

        Set<String> comparedInDispatch = literals(dispatch, Pattern.compile(
            "\"([A-Z][A-Za-z]+)\"\\.equals\\(envelope\\.eventType\\(\\)\\)"));
        assertThat(comparedInDispatch)
            .as("scraped nothing from the dispatch -- this test has stopped testing anything")
            .isNotEmpty();

        Set<String> admitted = literals(predicate, Pattern.compile("\"([A-Z][A-Za-z]+)\""));

        assertThat(admitted)
            .as("isInteresting() must admit every type the dispatch acts on, or those "
                + "events are dropped before reaching it")
            .containsAll(comparedInDispatch);
    }

    @Test
    void theExternalEventPolicyIsConsultedRatherThanDuplicated() {
        // The ancillary and dispatch event names live in PostSalesExternalEventPolicy.
        // isInteresting must delegate to handles() instead of restating them, or the
        // two lists drift and the drift is invisible.
        String source;
        try {
            source = Files.readString(SOURCE);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        String predicate = between(source, "private boolean isInteresting", "private static Optional");
        assertThat(predicate)
            .as("isInteresting must fall through to externalEventPolicy.handles()")
            .contains("externalEventPolicy.handles(");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + 1);
        assertThat(from).as("could not locate %s in the handler source", start).isNotNegative();
        assertThat(to).as("could not locate %s in the handler source", end).isGreaterThan(from);
        return source.substring(from, to);
    }

    private static Set<String> literals(String text, Pattern pattern) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
