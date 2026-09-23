package com.trainticket.postsales.application;

import com.trainticket.postsales.domain.PostSalesCase;
import java.util.Optional;

public interface PostSalesRepository {
    default void lockOrder(String journeyOrderId) {}
    void save(PostSalesCase postSalesCase);
    Optional<PostSalesCase> findById(String caseId);
    Optional<PostSalesCase> findByIdempotencyKey(String idempotencyKey);
    Optional<PostSalesCase> findActiveRefundCaseForOrder(String journeyOrderId);
    java.util.List<PostSalesCase> findAppliedCasesForOrder(String journeyOrderId);

    /** Cases whose scope covers the given order item, which is how a capacity release finds its case. */
    java.util.List<PostSalesCase> findByOrderItemRef(String orderItemRef);

    default java.util.List<PostSalesCase> findPendingCapacityRelease(String orderItemRef) {
        return findByOrderItemRef(orderItemRef).stream().filter(current->!current.releasedOrderItems().contains(orderItemRef)
            && (current.status()==com.trainticket.postsales.domain.PostSalesCaseStatus.APPROVED
                || current.status()==com.trainticket.postsales.domain.PostSalesCaseStatus.EXECUTING
                || (current.caseType()==com.trainticket.postsales.domain.PostSalesCaseType.CHANGE
                    && current.status()==com.trainticket.postsales.domain.PostSalesCaseStatus.APPLIED))).toList();
    }
}
