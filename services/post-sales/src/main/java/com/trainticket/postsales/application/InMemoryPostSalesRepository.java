package com.trainticket.postsales.application;

import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseStatus;
import com.trainticket.postsales.domain.PostSalesCaseType;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryPostSalesRepository implements PostSalesRepository {
    private final ConcurrentMap<String, PostSalesCase> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> caseIdByIdempotencyKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeRefundCaseIdByOrderId = new ConcurrentHashMap<>();

    @Override
    public void save(PostSalesCase postSalesCase) {
        if (isRefundConflictCase(postSalesCase) && isActive(postSalesCase)) {
            String existingCaseId = activeRefundCaseIdByOrderId.putIfAbsent(postSalesCase.journeyOrderId(), postSalesCase.caseId());
            if (existingCaseId != null && !existingCaseId.equals(postSalesCase.caseId())) {
                throw new RefundAlreadyInProgressException(existingCaseId);
            }
        }
        if (postSalesCase.version() == 0 && byId.putIfAbsent(postSalesCase.caseId(), postSalesCase) != null) {
            throw new OptimisticConcurrencyException("snapshot version conflict for " + postSalesCase.caseId());
        }
        if (postSalesCase.version() > 0 && byId.replace(postSalesCase.caseId(), postSalesCase) == null) {
            throw new OptimisticConcurrencyException("snapshot version conflict for " + postSalesCase.caseId());
        }
        postSalesCase.withVersion(postSalesCase.version() + 1);
        caseIdByIdempotencyKey.put(postSalesCase.idempotencyKey(), postSalesCase.caseId());
        if (isRefundConflictCase(postSalesCase) && !isActive(postSalesCase)) {
            activeRefundCaseIdByOrderId.remove(postSalesCase.journeyOrderId(), postSalesCase.caseId());
        }
    }

    @Override
    public Optional<PostSalesCase> findById(String caseId) {
        return Optional.ofNullable(byId.get(caseId));
    }

    @Override
    public Optional<PostSalesCase> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(caseIdByIdempotencyKey.get(idempotencyKey)).flatMap(this::findById);
    }

    @Override
    public Optional<PostSalesCase> findActiveRefundCaseForOrder(String journeyOrderId) {
        return Optional.ofNullable(activeRefundCaseIdByOrderId.get(journeyOrderId)).flatMap(this::findById);
    }

    @Override
    public java.util.List<PostSalesCase> findAll() {
        return java.util.List.copyOf(byId.values());
    }

    private static boolean isRefundConflictCase(PostSalesCase postSalesCase) {
        return postSalesCase.caseType() == PostSalesCaseType.REFUND
            || postSalesCase.caseType() == PostSalesCaseType.CANCELLATION
            || postSalesCase.caseType() == PostSalesCaseType.REBOOK
            || postSalesCase.caseType() == PostSalesCaseType.CHANGE;
    }

    private static boolean isActive(PostSalesCase postSalesCase) {
        return postSalesCase.status() != PostSalesCaseStatus.REJECTED
            && postSalesCase.status() != PostSalesCaseStatus.CANCELLED
            && postSalesCase.status() != PostSalesCaseStatus.FAILED
            && postSalesCase.status() != PostSalesCaseStatus.APPLIED;
    }
}
