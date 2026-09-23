package com.trainticket.journeyorder.api;

import com.trainticket.journeyorder.application.service.PurchaseSourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PurchaseSourceController {
    private final PurchaseSourceService service;

    public PurchaseSourceController(PurchaseSourceService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/internal/journey-orders/{orderId}/purchase-source")
    public PurchaseSourceService.PurchaseSource get(@PathVariable String orderId) {
        return service.get(orderId);
    }
}
