package com.trainticket.journeyorder.application.service;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public final class PurchaseSourceService {
    private final JourneyOrderStateRepository repository;

    public PurchaseSourceService(JourneyOrderStateRepository repository) {
        this.repository = repository;
    }

    public PurchaseSource get(String orderId) {
        var order = repository.findOrder(orderId)
            .orElseThrow(() -> new OrderManagementService.NotFoundException("Order not found: " + orderId)).order();
        var sources = order.timeline().stream()
            .filter(fact -> "JourneyOrderCreated".equals(fact.factType())).toList();
        if (sources.size() != 1) {
            throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "Order requires one accepted purchase source");
        }
        var source = sources.getFirst();
        String offerId = source.attributes().get("offerId");
        if (offerId == null || offerId.isBlank()) {
            throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "Accepted purchase source has no offer reference");
        }
        return new PurchaseSource(order.orderId(), order.accountId(), offerId, source.factId(), source.occurredAt());
    }

    public record PurchaseSource(String orderId, String accountId, String offerId, String timelineFactId, Instant acceptedAt) {}
}
