package com.trainticket.financesettlement.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void createsMoneyWithCorrectScale() {
        Money m = Money.of("CNY", "123.45");
        assertEquals("CNY", m.currency().getCurrencyCode());
        assertEquals("123.45", m.amount().toPlainString());
    }

    @Test
    void zeroReturnsZeroAmount() {
        Money m = Money.zero(Currency.getInstance("CNY"));
        assertTrue(m.isZero());
        assertFalse(m.isNegative());
    }

    @Test
    void additionRequiresSameCurrency() {
        Money a = Money.of("CNY", "100.00");
        Money b = Money.of("CNY", "50.00");
        assertEquals(Money.of("CNY", "150.00"), a.plus(b));
    }

    @Test
    void additionRejectsCrossCurrency() {
        Money a = Money.of("CNY", "100.00");
        Money b = Money.of("USD", "50.00");
        assertThrows(DomainRuleViolation.class, () -> a.plus(b));
    }

    @Test
    void subtractionRequiresSameCurrency() {
        Money a = Money.of("CNY", "100.00");
        Money b = Money.of("CNY", "30.00");
        assertEquals(Money.of("CNY", "70.00"), a.minus(b));
    }

    @Test
    void negateReturnsNegativeAmount() {
        Money a = Money.of("CNY", "100.00");
        assertEquals(Money.of("CNY", "-100.00"), a.negate());
    }

    @Test
    void compareToOrdersByAmount() {
        assertTrue(Money.of("CNY", "50.00").compareTo(Money.of("CNY", "100.00")) < 0);
        assertTrue(Money.of("CNY", "100.00").compareTo(Money.of("CNY", "50.00")) > 0);
        assertEquals(0, Money.of("CNY", "100.00").compareTo(Money.of("CNY", "100.00")));
    }

    @Test
    void refusesNullCurrencyOrAmount() {
        assertThrows(NullPointerException.class, () -> new Money(null, BigDecimal.TEN));
        assertThrows(NullPointerException.class, () -> new Money(Currency.getInstance("CNY"), null));
    }

    @Test
    void refusesDifferentScaleThanCurrencyFractionDigits() {
        assertThrows(java.lang.ArithmeticException.class, () -> Money.of("CNY", "100.123"));
    }
}
