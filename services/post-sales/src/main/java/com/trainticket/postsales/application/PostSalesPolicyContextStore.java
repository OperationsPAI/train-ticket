package com.trainticket.postsales.application;

import java.util.Optional;

public interface PostSalesPolicyContextStore {
    Optional<PostSalesPolicyContext> findByOrderId(String journeyOrderId);

    void save(PostSalesPolicyContext context);
}
