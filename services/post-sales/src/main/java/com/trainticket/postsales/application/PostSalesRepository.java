package com.trainticket.postsales.application;

import com.trainticket.postsales.domain.PostSalesCase;
import java.util.Optional;

public interface PostSalesRepository {
    void save(PostSalesCase postSalesCase);
    Optional<PostSalesCase> findById(String caseId);
    Optional<PostSalesCase> findByIdempotencyKey(String idempotencyKey);
    Optional<PostSalesCase> findActiveRefundCaseForOrder(String journeyOrderId);

    /** Cases whose scope covers the given order item, which is how a capacity release finds its case. */
    java.util.List<PostSalesCase> findByOrderItemRef(String orderItemRef);
}
