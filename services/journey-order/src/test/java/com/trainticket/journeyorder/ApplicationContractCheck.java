package com.trainticket.journeyorder;

public final class ApplicationContractCheck {
    private ApplicationContractCheck() {
    }

    public static void main(String[] args) {
        ServiceProfile profile = Application.profile();
        if (!"journey-order".equals(profile.serviceId())) {
            throw new IllegalStateException("unexpected service id: " + profile.serviceId());
        }
        if (!"ok".equals(profile.health())) {
            throw new IllegalStateException("unexpected health value");
        }
    }
}
