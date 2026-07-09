package com.trainticket.walletpromotion.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletPromotionEvent(String eventType, Map<String, Object> payload, Instant occurredAt, long aggregateVersion) {}
