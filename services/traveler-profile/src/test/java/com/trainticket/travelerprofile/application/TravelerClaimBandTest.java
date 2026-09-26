package com.trainticket.travelerprofile.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TravelerClaimBandTest {
    private static final LocalDate TODAY = LocalDate.parse("2026-09-26");
    private static final TravelerClaimBand PRICED = new TravelerClaimBand(6, 65);

    @Test
    void holdsAClaimOnEitherEndOfTheBand() {
        assertTrue(PRICED.holds(TODAY.minusYears(6), TODAY));
        assertTrue(PRICED.holds(TODAY.minusYears(65), TODAY));
    }

    @Test
    void refusesAClaimOutsideTheBand() {
        assertFalse(PRICED.holds(TODAY.minusYears(5), TODAY));
        assertFalse(PRICED.holds(TODAY.minusYears(66), TODAY));
    }

    @Test
    void aCallerThatWiresTheServiceItselfCarriesNoEndTheReleaseDidNotPublish() {
        TravelerClaimBand unpriced = TravelerClaimBand.unpriced();

        assertTrue(unpriced.holds(TODAY, TODAY));
        assertTrue(unpriced.holds(TODAY.minusYears(119), TODAY));
    }
}
