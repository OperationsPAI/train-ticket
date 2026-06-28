package com.trainticket.adminaudit;

public final class ApplicationContractCheck {
    private ApplicationContractCheck() {
    }

    public static void main(String[] args) {
        ServiceProfile profile = Application.profile();
        if (!"admin-audit".equals(profile.serviceId())) {
            throw new IllegalStateException("unexpected service id: " + profile.serviceId());
        }
        if (!"ok".equals(profile.health())) {
            throw new IllegalStateException("unexpected health value");
        }
    }
}
