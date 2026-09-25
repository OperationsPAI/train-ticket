package com.trainticket.walletpromotion.infrastructure.persistence;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.EventPublisher;
import com.trainticket.walletpromotion.application.MutationArtifacts;
import com.trainticket.walletpromotion.application.PromotionRepository;
import com.trainticket.walletpromotion.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnMissingBean(PromotionRepository.class)
public class InMemoryPromotionRepository implements PromotionRepository {
    private final Map<String,com.trainticket.walletpromotion.application.IssueBenefitCommand> issuanceRequests=new ConcurrentHashMap<>();
    private final Map<String,String> issuanceBenefits=new ConcurrentHashMap<>();
    public Optional<PromotionInstrument> findIssue(com.trainticket.walletpromotion.application.IssueBenefitCommand command) {
        var original=issuanceRequests.get(command.issuanceKey());
        if(original==null)return Optional.empty();
        if(!original.equals(command))throw new com.trainticket.platformkit.http.ApiException(com.trainticket.platformkit.http.ApiErrorCode.CONFLICT,"issuance key already has another request");
        return findBenefit(issuanceBenefits.get(command.issuanceKey()));
    }
    public void recordIssue(com.trainticket.walletpromotion.application.IssueBenefitCommand command,String benefitId) {
        issuanceRequests.put(command.issuanceKey(),command);issuanceBenefits.put(command.issuanceKey(),benefitId);
    }
    private final Map<String, PromotionInstrument> benefits = new ConcurrentHashMap<>();
    private final Map<String, WalletAccount> wallets = new ConcurrentHashMap<>();
    private final Map<String, BenefitReservation> reservations = new ConcurrentHashMap<>();
    private final Map<String, BenefitRedemption> redemptions = new ConcurrentHashMap<>();
    private final Map<String, String> reasonIndex = new ConcurrentHashMap<>();
    private final Map<String, ReservationRelease> releases = new ConcurrentHashMap<>();
    private final Map<String, String> releaseReasonIndex = new ConcurrentHashMap<>();
    private final Map<String, ReversalRecord> reversals = new ConcurrentHashMap<>();
    private final Map<String, String> reversalReasonIndex = new ConcurrentHashMap<>();
    private final EventPublisher publisher;
    public InMemoryPromotionRepository(Optional<EventPublisher> publisher) { this.publisher = publisher.orElse(envelope -> {}); }
    public Optional<PromotionInstrument> findBenefit(String benefitId) { return Optional.ofNullable(benefits.get(benefitId)); }
    public Optional<PromotionInstrument> lockBenefit(String benefitId) { return findBenefit(benefitId); }
    public Optional<WalletAccount> findWalletByAccountId(String accountId) { return wallets.values().stream().filter(w -> w.accountId().equals(accountId)).findFirst(); }
    public List<PromotionInstrument> listBenefits(String accountId, PromotionStatus status, BenefitType benefitType, int limit, int offset) { return filtered(accountId,status,benefitType).stream().skip(offset).limit(limit).toList(); }
    public int countBenefits(String accountId, PromotionStatus status, BenefitType benefitType) { return filtered(accountId,status,benefitType).size(); }
    public List<PromotionInstrument> findExpirable(Instant now, int limit) { return benefits.values().stream().filter(b -> !b.status().terminal() && !now.isBefore(b.validUntil())).limit(limit).toList(); }
    public List<BenefitReservation> findExpiredReservations(Instant now, int limit) { return reservations.values().stream().filter(r -> r.status()==BenefitReservationStatus.ACTIVE && !now.isBefore(r.expiresAt())).limit(limit).toList(); }
    public List<BenefitReservation> findActiveReservations(String benefitId) { return reservations.values().stream().filter(r -> r.benefitId().equals(benefitId) && r.status()==BenefitReservationStatus.ACTIVE).toList(); }
    public Optional<BenefitReservation> findReservation(String benefitId,String reservationRef) { return Optional.ofNullable(reservations.get(reservationKey(benefitId,reservationRef))); }
    public Optional<BenefitRedemption> findRedemptionByReference(String benefitId, String redemptionRef) { return Optional.ofNullable(reasonIndex.get(benefitId + "|" + redemptionRef)).map(redemptions::get); }
    public Optional<BenefitRedemption> findRedemption(String redemptionId) { return Optional.ofNullable(redemptions.get(redemptionId)); }
    public Optional<ReservationRelease> findReleaseByReference(String benefitId,String releaseRef) { return Optional.ofNullable(releaseReasonIndex.get(benefitId+"|"+releaseRef)).map(releases::get); }
    public Optional<ReversalRecord> findReversalByReference(String redemptionId,String reversalRef) { return Optional.ofNullable(reversalReasonIndex.get(redemptionId+"|"+reversalRef)).map(reversals::get); }
    public synchronized void saveMutation(PromotionInstrument benefit, WalletAccount wallet, WalletPromotionEvent event, MutationArtifacts artifacts) {
        benefits.put(benefit.benefitId(), benefit); wallets.put(wallet.walletAccountId(), wallet);
        for (BenefitReservation reservation : artifacts.reservations()) {
            reservations.put(reservationKey(reservation.benefitId(), reservation.reservationRef()), reservation);
        }
        BenefitRedemption redemption = artifacts.redemption();
        if (redemption != null) { String k = benefit.benefitId()+"|"+redemption.redemptionRef(); if (reasonIndex.containsKey(k)) throw new org.springframework.dao.DuplicateKeyException("duplicate redemption reference"); redemptions.put(redemption.redemptionId(), redemption); reasonIndex.put(k, redemption.redemptionId()); }
        ReservationRelease release = artifacts.release();
        if (release != null) { String k=benefit.benefitId()+"|"+release.releaseRef(); if(releaseReasonIndex.containsKey(k))throw new org.springframework.dao.DuplicateKeyException("duplicate release reference"); releases.put(release.releaseId(),release);releaseReasonIndex.put(k,release.releaseId()); }
        ReversalRecord reversal = artifacts.reversal();
        if (reversal != null) {
            String k=reversal.redemptionId()+"|"+reversal.reversalRef();
            if(reversalReasonIndex.containsKey(k))throw new org.springframework.dao.DuplicateKeyException("duplicate reversal reference");
            reversals.put(reversal.reversalId(),reversal);reversalReasonIndex.put(k,reversal.reversalId());
            BenefitRedemption r = redemptions.get(reversal.redemptionId());
            if (r != null) redemptions.put(r.redemptionId(), new BenefitRedemption(r.redemptionId(), r.benefitId(), r.accountId(), r.amount(), r.redemptionRef(), r.reservationRef(), r.useReference(), r.businessReason(), r.redeemedAt(), r.reversedMinorUnits()+reversal.amount().minorUnits()));
        }
        publisher.publish(new EventEnvelope(DeterministicEventIds.forTransition(event.eventType(), benefit.benefitId(), benefit.version()), event.eventType(), event.occurredAt(), com.trainticket.platformkit.messaging.PrefixedIds.newCorrelationId(), null, "wallet-promotion", 1, event.payload()));
    }
    private static String reservationKey(String benefitId,String reservationRef){return benefitId+"|"+reservationRef;}
    private List<PromotionInstrument> filtered(String accountId, PromotionStatus status, BenefitType type) { return benefits.values().stream().filter(b -> b.accountId().equals(accountId)).filter(b -> status==null || b.status()==status).filter(b -> type==null || b.benefitType()==type).sorted(Comparator.comparing(PromotionInstrument::createdAt)).toList(); }
}
