package com.trainticket.postsales.application;

import com.trainticket.postsales.domain.PostSalesCase;
import java.util.Optional;

public interface PostSalesRepository {
    void save(PostSalesCase postSalesCase);
    Optional<PostSalesCase> findById(String caseId);
    Optional<PostSalesCase> findByIdempotencyKey(String idempotencyKey);
    java.util.List<PostSalesCase> findAll();
}
