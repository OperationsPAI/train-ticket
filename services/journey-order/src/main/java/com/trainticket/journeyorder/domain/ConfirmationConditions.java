package com.trainticket.journeyorder.domain;

public record ConfirmationConditions(
    boolean bookingSummaryAccepted,
    boolean capacitySummaryAccepted,
    boolean paymentConditionSatisfied,
    boolean entitlementSummaryAccepted,
    boolean riskClear
) {
    public static ConfirmationConditions none() {
        return new ConfirmationConditions(false, false, false, false, false);
    }

    public ConfirmationConditions withBookingSummaryAccepted() {
        return new ConfirmationConditions(true, capacitySummaryAccepted, paymentConditionSatisfied, entitlementSummaryAccepted, riskClear);
    }

    public ConfirmationConditions withCapacitySummaryAccepted() {
        return new ConfirmationConditions(bookingSummaryAccepted, true, paymentConditionSatisfied, entitlementSummaryAccepted, riskClear);
    }

    public ConfirmationConditions withPaymentConditionSatisfied() {
        return new ConfirmationConditions(bookingSummaryAccepted, capacitySummaryAccepted, true, entitlementSummaryAccepted, riskClear);
    }

    public ConfirmationConditions withEntitlementSummaryAccepted() {
        return new ConfirmationConditions(bookingSummaryAccepted, capacitySummaryAccepted, paymentConditionSatisfied, true, riskClear);
    }

    public ConfirmationConditions withRiskCleared() {
        return new ConfirmationConditions(bookingSummaryAccepted, capacitySummaryAccepted, paymentConditionSatisfied, entitlementSummaryAccepted, true);
    }

    public ConfirmationConditions withRiskBlocked() {
        return new ConfirmationConditions(bookingSummaryAccepted, capacitySummaryAccepted, paymentConditionSatisfied, entitlementSummaryAccepted, false);
    }

    public boolean canConfirm() {
        return bookingSummaryAccepted && capacitySummaryAccepted && paymentConditionSatisfied && entitlementSummaryAccepted && riskClear;
    }
}
