package com.trainticket.postsales.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void fromMinorUnitsCreatesCorrectAmount() {
        Money money = Money.fromMinorUnits(1000, "CNY");
        assertEquals("10.00", money.amount().toPlainString());
        assertEquals("CNY", money.currency().getCurrencyCode());
    }

    @Test
    void toMinorUnitsReturnsCorrectValue() {
        Money money = Money.of("10.00", "CNY");
        assertEquals(1000L, money.toMinorUnits());
    }

    @Test
    void fromMinorUnitsAndToMinorUnitsRoundTrip() {
        long original = 9999L;
        Money money = Money.fromMinorUnits(original, "USD");
        assertEquals(original, money.toMinorUnits());
    }

    @Test
    void fromMinorUnitsWithZeroWorks() {
        Money money = Money.fromMinorUnits(0, "EUR");
        assertEquals("0.00", money.amount().toPlainString());
        assertEquals(0L, money.toMinorUnits());
    }

    @Test
    void fromMinorUnitsRejectsBlankCurrency() {
        assertThrows(DomainRuleViolation.class, () ->
            Money.fromMinorUnits(100, ""));
    }

    @Test
    void fromMinorUnitsRejectsNullCurrency() {
        assertThrows(NullPointerException.class, () ->
            Money.fromMinorUnits(100, null));
    }
}
