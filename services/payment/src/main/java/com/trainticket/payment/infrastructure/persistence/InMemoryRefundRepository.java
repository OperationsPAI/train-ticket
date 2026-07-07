package com.trainticket.payment.infrastructure.persistence;

import com.trainticket.payment.domain.Refund;
import com.trainticket.payment.domain.ports.RefundRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnMissingBean(RefundRepository.class)
public class InMemoryRefundRepository implements RefundRepository {
    private final Map<String, Refund> refunds = new ConcurrentHashMap<>();

    @Override
    public Optional<Refund> findById(String refundId) {
        return Optional.ofNullable(refunds.get(refundId));
    }

    @Override
    public void save(Refund refund) {
        refunds.put(refund.refundId(), refund);
    }
}
