package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.BenefitRedemption;
import com.trainticket.walletpromotion.domain.BenefitReservation;
import com.trainticket.walletpromotion.domain.ReservationRelease;
import com.trainticket.walletpromotion.domain.ReversalRecord;
import java.util.List;

public record MutationArtifacts(
    List<BenefitReservation> reservations,
    BenefitRedemption redemption,
    ReservationRelease release,
    ReversalRecord reversal
) {
    public MutationArtifacts {
        reservations = reservations == null ? List.of() : List.copyOf(reservations);
    }

    public static MutationArtifacts none() {
        return new MutationArtifacts(List.of(), null, null, null);
    }

    public static MutationArtifacts reservation(BenefitReservation reservation) {
        return new MutationArtifacts(List.of(reservation), null, null, null);
    }

    public static MutationArtifacts redemption(BenefitReservation reservation, BenefitRedemption redemption) {
        return new MutationArtifacts(reservation == null ? List.of() : List.of(reservation), redemption, null, null);
    }

    public static MutationArtifacts release(BenefitReservation reservation, ReservationRelease release) {
        return new MutationArtifacts(List.of(reservation), null, release, null);
    }

    public static MutationArtifacts reversal(ReversalRecord reversal) {
        return new MutationArtifacts(List.of(), null, null, reversal);
    }

    public static MutationArtifacts reservations(List<BenefitReservation> reservations) {
        return new MutationArtifacts(reservations, null, null, null);
    }
}
