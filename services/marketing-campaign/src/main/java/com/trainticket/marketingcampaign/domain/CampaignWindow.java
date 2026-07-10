package com.trainticket.marketingcampaign.domain;

import java.time.Instant;
import java.util.Objects;

public record CampaignWindow(Instant validFrom, Instant validUntil) {
    public CampaignWindow {
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        if (!validUntil.isAfter(validFrom)) throw new DomainException("validUntil must be after validFrom");
    }

    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(validFrom) && instant.isBefore(validUntil);
    }

    public boolean contains(CampaignWindow other) {
        Objects.requireNonNull(other, "other");
        return !other.validFrom.isBefore(validFrom) && !other.validUntil.isAfter(validUntil);
    }
}
