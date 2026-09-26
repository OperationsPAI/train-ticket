package com.trainticket.travelerprofile.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The window of claimed ages the register carries.
 *
 * A profile is the fact every other service prices, seats and tickets against,
 * so a claim the catalogue has no fare band for is refused where it is handed
 * in rather than stored and discovered at the first quote. The ends are release
 * data: they are the youngest and the oldest band the fare catalogue prices,
 * stated with the service's other settings so a release can move them without a
 * new image.
 */
@Component
public class TravelerClaimWindow {
    static final String MIN_AGE_SETTING = "traveler-profile.claim.min-age-years";
    static final String MAX_AGE_SETTING = "traveler-profile.claim.max-age-years";
    static final int MIN_AGE_DEFAULT = 0;
    static final int MAX_AGE_DEFAULT = 120;

    private final int minAgeYears;
    private final int maxAgeYears;

    public TravelerClaimWindow(
        @Value("${" + MIN_AGE_SETTING + ":" + MIN_AGE_DEFAULT + "}") int minAgeYears,
        @Value("${" + MAX_AGE_SETTING + ":" + MAX_AGE_DEFAULT + "}") int maxAgeYears
    ) {
        if (minAgeYears < 0 || maxAgeYears < minAgeYears) {
            throw new IllegalArgumentException(MIN_AGE_SETTING + " and " + MAX_AGE_SETTING + " must bound an age range");
        }
        this.minAgeYears = minAgeYears;
        this.maxAgeYears = maxAgeYears;
    }

    /**
     * The window a caller that builds the service itself gets. Such a caller is
     * not stating what the release states, so it holds no end the settings did
     * not hand it.
     */
    public static TravelerClaimWindow unstated() {
        return new TravelerClaimWindow(MIN_AGE_DEFAULT, MAX_AGE_DEFAULT);
    }

    /** Whether a claimed birth date falls inside the window this release states. */
    public boolean holds(LocalDate claimed, LocalDate today) {
        long ageYears = ChronoUnit.YEARS.between(claimed, today);
        return ageYears >= minAgeYears && ageYears <= maxAgeYears;
    }
}
