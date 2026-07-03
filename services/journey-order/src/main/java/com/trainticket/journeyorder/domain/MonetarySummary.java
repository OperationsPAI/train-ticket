package com.trainticket.journeyorder.domain;

import java.util.Currency;
import java.util.List;
import java.util.Objects;

public record MonetarySummary(
    Currency currency,
    Money itemSubtotal,
    Money taxTotal,
    Money feeTotal,
    Money discountTotal,
    Money cancelledTotal,
    Money payableTotal
) {
    public MonetarySummary {
        Objects.requireNonNull(currency, "currency is required");
        itemSubtotal = requireCurrency(itemSubtotal, currency, "itemSubtotal");
        taxTotal = requireCurrency(taxTotal, currency, "taxTotal");
        feeTotal = requireCurrency(feeTotal, currency, "feeTotal");
        discountTotal = requireCurrency(discountTotal, currency, "discountTotal");
        cancelledTotal = requireCurrency(cancelledTotal, currency, "cancelledTotal");
        payableTotal = requireCurrency(payableTotal, currency, "payableTotal");
        if (itemSubtotal.isNegative() || taxTotal.isNegative() || feeTotal.isNegative() || cancelledTotal.isNegative() || payableTotal.isNegative()) {
            throw new DomainRuleViolation("monetary summary non-discount totals must not be negative");
        }
        if (discountTotal.amount().signum() > 0) {
            throw new DomainRuleViolation("discountTotal must be zero or negative");
        }
        Money expected = itemSubtotal.plus(taxTotal).plus(feeTotal).plus(discountTotal).minus(cancelledTotal);
        if (!expected.equals(payableTotal)) {
            throw new DomainRuleViolation("payableTotal must equal items + taxes + fees + discounts - cancelledTotal");
        }
    }

    public static MonetarySummary fromItems(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new DomainRuleViolation("JourneyOrder requires at least one order item");
        }
        Currency currency = items.getFirst().amount().currency();
        Money itemSubtotal = Money.zero(currency);
        Money taxTotal = Money.zero(currency);
        Money feeTotal = Money.zero(currency);
        Money discountTotal = Money.zero(currency);
        Money cancelledTotal = Money.zero(currency);
        for (OrderItem item : items) {
            Money amount = requireCurrency(item.amount(), currency, "order item " + item.orderItemId());
            if (item.cancelled()) {
                cancelledTotal = cancelledTotal.plus(amount.amount().signum() < 0 ? amount.negate() : amount);
            }
            switch (item.type()) {
                case TAX -> taxTotal = taxTotal.plus(amount);
                case SERVICE_FEE -> feeTotal = feeTotal.plus(amount);
                case DISCOUNT -> discountTotal = discountTotal.plus(amount);
                case SEGMENT_FARE, ANCILLARY_SERVICE -> itemSubtotal = itemSubtotal.plus(amount);
            }
        }
        Money payable = itemSubtotal.plus(taxTotal).plus(feeTotal).plus(discountTotal).minus(cancelledTotal);
        if (payable.isNegative()) {
            throw new DomainRuleViolation("payableTotal must not become negative");
        }
        return new MonetarySummary(currency, itemSubtotal, taxTotal, feeTotal, discountTotal, cancelledTotal, payable);
    }

    private static Money requireCurrency(Money money, Currency currency, String name) {
        Objects.requireNonNull(money, name + " is required");
        if (!money.currency().equals(currency)) {
            throw new DomainRuleViolation(name + " currency must be " + currency);
        }
        return money;
    }
}
