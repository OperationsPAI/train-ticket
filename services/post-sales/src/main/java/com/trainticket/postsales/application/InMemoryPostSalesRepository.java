package com.trainticket.postsales.application;

import com.trainticket.postsales.domain.PostSalesCase;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryPostSalesRepository implements PostSalesRepository {
    private final ConcurrentMap<String, PostSalesCase> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> caseIdByIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public void save(PostSalesCase postSalesCase) {
        byId.put(postSalesCase.caseId(), postSalesCase);
        caseIdByIdempotencyKey.put(postSalesCase.idempotencyKey(), postSalesCase.caseId());
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
    public java.util.List<PostSalesCase> findAll() {
        return java.util.List.copyOf(byId.values());
    }
}
