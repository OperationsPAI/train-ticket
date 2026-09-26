package com.trainticket.travelerprofile.application;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The bound a claimed birth date is held to.
 *
 * The create path used to invent 1970-01-01 for a traveler who claimed none, and
 * the commit that removed the invention left the accept path with no bound at
 * all: every parseable date was stored as a fact, including years that are a
 * typo rather than a claim about a traveler. The earliest claim a profile can
 * carry is a rule about this platform rather than a property of the claim, so it
 * is stated where the service's other rules are.
 */
@Component
public class BirthDateClaimPolicy {
    static final String EARLIEST_CLAIM_SETTING = "traveler-profile.birth-date.earliest-claim";
    static final String EARLIEST_CLAIM_DEFAULT = "1900-01-01";

    private final LocalDate earliestClaim;

    public BirthDateClaimPolicy(
        @Value("${" + EARLIEST_CLAIM_SETTING + ":" + EARLIEST_CLAIM_DEFAULT + "}") String earliestClaim
    ) {
        this.earliestClaim = parse(earliestClaim);
    }

    /**
     * The policy a construction outside the container gets. The create path holds
     * a claim to the same bound the release states; a test that builds the service
     * itself is not asserting what the settings file says.
     */
    public static BirthDateClaimPolicy statedDefault() {
        return new BirthDateClaimPolicy(EARLIEST_CLAIM_DEFAULT);
    }

    /** The first date a claim may carry. A claim before it is refused. */
    public LocalDate earliestClaim() {
        return earliestClaim;
    }

    private static LocalDate parse(String value) {
        try {
            return LocalDate.parse(Objects.requireNonNull(value, EARLIEST_CLAIM_SETTING + " is required").trim());
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalArgumentException(EARLIEST_CLAIM_SETTING + " must be an ISO-8601 date (YYYY-MM-DD)");
        }
    }
}
