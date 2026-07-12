package com.trainticket.financesettlement.domain;

import java.math.BigDecimal;

public record TaxCalculation(
    Money vatOnServiceFees,
    Money stampDutyOnTickets,
    Money withholdingOnSupplierPayment,
    Money taxReversal
) {
    private static final BigDecimal VAT_RATE = new BigDecimal("0.06");
    private static final BigDecimal STAMP_DUTY_RATE = new BigDecimal("0.0005");

    public static TaxCalculation calculate(Money serviceFees, Money ticketAmount, Money supplierPayment, BigDecimal withholdingPct, Money refundedTicketAmount) {
        Money vat = RevenueAllocation.multiply(serviceFees, VAT_RATE);
        Money stampDuty = RevenueAllocation.multiply(ticketAmount, STAMP_DUTY_RATE);
        Money withholding = RevenueAllocation.multiply(supplierPayment, withholdingPct);
        Money reversal = refundedTicketAmount.isZero()
            ? Money.zero(refundedTicketAmount.currency())
            : RevenueAllocation.multiply(refundedTicketAmount, STAMP_DUTY_RATE).negate();
        return new TaxCalculation(vat, stampDuty, withholding, reversal);
    }
}
