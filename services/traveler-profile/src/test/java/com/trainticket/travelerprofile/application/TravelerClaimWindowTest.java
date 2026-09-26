package com.trainticket.travelerprofile.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TravelerClaimWindowTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);

    @Test
    void carriesAClaimAtEitherEndOfTheStatedWindow() {
        TravelerClaimWindow window = new TravelerClaimWindow(10, 20);
        assertTrue(window.holds(TODAY.minusYears(10), TODAY));
        assertTrue(window.holds(TODAY.minusYears(20), TODAY));
        assertFalse(window.holds(TODAY.minusYears(9), TODAY));
        assertFalse(window.holds(TODAY.minusYears(21), TODAY));
    }

    @Test
    void aWindowNoReleaseStatedCarriesEveryAge() {
        TravelerClaimWindow window = TravelerClaimWindow.unstated();
        assertTrue(window.holds(TODAY, TODAY));
        assertTrue(window.holds(TODAY.minusYears(120), TODAY));
    }

    @Test
    void refusesEndsThatDoNotBoundAnAgeRange() {
        assertThrows(IllegalArgumentException.class, () -> new TravelerClaimWindow(-1, 20));
        assertThrows(IllegalArgumentException.class, () -> new TravelerClaimWindow(20, 10));
    }
}
