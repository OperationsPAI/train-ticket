package com.trainticket.postsales.application;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryPostSalesPolicyContextStore implements PostSalesPolicyContextStore {
    private final ConcurrentMap<String, PostSalesPolicyContext> byOrderId = new ConcurrentHashMap<>();

    @Override
    public Optional<PostSalesPolicyContext> findByOrderId(String journeyOrderId) {
        return Optional.ofNullable(byOrderId.get(journeyOrderId));
    }

    @Override
    public void save(PostSalesPolicyContext context) {
        byOrderId.put(context.journeyOrderId(), context);
    }
}
