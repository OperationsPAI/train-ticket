package com.trainticket.travelerprofile.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The band of claimed ages the register carries, which is the band the fare catalogue prices.
 *
 * A profile is the fact every other service quotes, seats and tickets against, so a claimed
 * date the catalogue has no band for should be answered where it is handed in rather than
 * stored here and met at the first quote, one service downstream. The ends belong to the
 * catalogue and not to this service: they are published once with the release, so a catalogue
 * that moves its band moves the register with it and no service has to be rebuilt for it.
 */
@Component
public class TravelerClaimBand {
    static final String YOUNGEST_SETTING = "catalogue.band.youngest-age-years";
    static final String OLDEST_SETTING = "catalogue.band.oldest-age-years";
    static final int YOUNGEST_DEFAULT = 0;
    static final int OLDEST_DEFAULT = 120;

    private final int youngestAgeYears;
    private final int oldestAgeYears;

    public TravelerClaimBand(
        @Value("${" + YOUNGEST_SETTING + ":" + YOUNGEST_DEFAULT + "}") int youngestAgeYears,
        @Value("${" + OLDEST_SETTING + ":" + OLDEST_DEFAULT + "}") int oldestAgeYears
    ) {
        if (youngestAgeYears < 0 || oldestAgeYears < youngestAgeYears) {
            throw new IllegalArgumentException(YOUNGEST_SETTING + " and " + OLDEST_SETTING + " must bound an age band");
        }
        this.youngestAgeYears = youngestAgeYears;
        this.oldestAgeYears = oldestAgeYears;
    }

    /**
     * The band a caller that wires the service itself gets. Such a caller is not the catalogue
     * and holds no end the release did not publish.
     */
    public static TravelerClaimBand unpriced() {
        return new TravelerClaimBand(YOUNGEST_DEFAULT, OLDEST_DEFAULT);
    }

    /** Whether a claimed birth date falls inside the band the catalogue publishes. */
    public boolean holds(LocalDate claimed, LocalDate today) {
        long ageYears = ChronoUnit.YEARS.between(claimed, today);
        return ageYears >= youngestAgeYears && ageYears <= oldestAgeYears;
    }
}
