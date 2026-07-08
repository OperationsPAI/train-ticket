package com.trainticket.walletpromotion.infrastructure.persistence;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.EventPublisher;
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
    private final Map<String, PromotionInstrument> benefits = new ConcurrentHashMap<>();
    private final Map<String, WalletAccount> wallets = new ConcurrentHashMap<>();
    private final Map<String, BenefitRedemption> redemptions = new ConcurrentHashMap<>();
    private final Map<String, String> reasonIndex = new ConcurrentHashMap<>();
    private final EventPublisher publisher;
    public InMemoryPromotionRepository(Optional<EventPublisher> publisher) { this.publisher = publisher.orElse(envelope -> {}); }
    public Optional<PromotionInstrument> findBenefit(String benefitId) { return Optional.ofNullable(benefits.get(benefitId)); }
    public Optional<WalletAccount> findWalletByAccountId(String accountId) { return wallets.values().stream().filter(w -> w.accountId().equals(accountId)).findFirst(); }
    public List<PromotionInstrument> listBenefits(String accountId, PromotionStatus status, BenefitType benefitType, int limit, int offset) { return filtered(accountId,status,benefitType).stream().skip(offset).limit(limit).toList(); }
    public int countBenefits(String accountId, PromotionStatus status, BenefitType benefitType) { return filtered(accountId,status,benefitType).size(); }
    public List<PromotionInstrument> findExpirable(Instant now, int limit) { return benefits.values().stream().filter(b -> (b.status()==PromotionStatus.ISSUED||b.status()==PromotionStatus.RESERVED||b.status()==PromotionStatus.RELEASED) && !now.isBefore(b.validUntil())).limit(limit).toList(); }
    public Optional<BenefitRedemption> findRedemptionByReason(String benefitId, BusinessReason reason) { return Optional.ofNullable(reasonIndex.get(benefitId + "|" + reason.key())).map(redemptions::get); }
    public Optional<BenefitRedemption> findRedemption(String redemptionId) { return Optional.ofNullable(redemptions.get(redemptionId)); }
    public synchronized void saveMutation(PromotionInstrument benefit, WalletAccount wallet, WalletPromotionEvent event, BenefitRedemption redemption, ReversalRecord reversal) {
        benefits.put(benefit.benefitId(), benefit); wallets.put(wallet.walletAccountId(), wallet);
        if (redemption != null) { String k = benefit.benefitId()+"|"+redemption.businessReason().key(); if (reasonIndex.containsKey(k)) throw new org.springframework.dao.DuplicateKeyException("duplicate redemption reason"); redemptions.put(redemption.redemptionId(), redemption); reasonIndex.put(k, redemption.redemptionId()); }
        if (reversal != null) { BenefitRedemption r = redemptions.get(reversal.redemptionId()); if (r != null) redemptions.put(r.redemptionId(), new BenefitRedemption(r.redemptionId(), r.benefitId(), r.accountId(), r.amount(), r.redemptionRef(), r.businessReason(), r.redeemedAt(), r.reversedMinorUnits()+reversal.amount().minorUnits())); }
        publisher.publish(new EventEnvelope(DeterministicEventIds.forTransition(event.eventType(), benefit.benefitId(), benefit.version()), event.eventType(), event.occurredAt(), com.trainticket.platformkit.messaging.PrefixedIds.newCorrelationId(), null, "wallet-promotion", 1, event.payload()));
    }
    private List<PromotionInstrument> filtered(String accountId, PromotionStatus status, BenefitType type) { return benefits.values().stream().filter(b -> b.accountId().equals(accountId)).filter(b -> status==null || b.status()==status).filter(b -> type==null || b.benefitType()==type).sorted(Comparator.comparing(PromotionInstrument::createdAt)).toList(); }
}
