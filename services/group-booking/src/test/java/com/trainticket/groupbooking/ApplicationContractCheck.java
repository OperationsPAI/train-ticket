package com.trainticket.groupbooking;

public final class ApplicationContractCheck {
    private ApplicationContractCheck() {
    }

    public static void main(String[] args) {
        ServiceProfile profile = Application.profile();
        if (!"group-booking".equals(profile.serviceId())) {
            throw new IllegalStateException("unexpected service id: " + profile.serviceId());
        }
        if (!"java".equals(profile.language())) {
            throw new IllegalStateException("unexpected language: " + profile.language());
        }
    }
}
