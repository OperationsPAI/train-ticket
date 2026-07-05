package com.trainticket.journeyorder.api;

import com.trainticket.journeyorder.api.dto.CancelJourneyOrderRequest;
import com.trainticket.journeyorder.api.dto.CancelJourneyOrderResponse;
import com.trainticket.journeyorder.api.dto.CreateJourneyOrderRequest;
import com.trainticket.journeyorder.api.dto.CreateJourneyOrderResponse;
import com.trainticket.journeyorder.api.dto.GetJourneyOrderResponse;
import com.trainticket.journeyorder.api.dto.ListJourneyOrdersResponse;
import com.trainticket.journeyorder.api.dto.MonetarySummaryDto;
import com.trainticket.journeyorder.api.dto.MoneyDto;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderService;
import com.trainticket.journeyorder.application.port.in.OrderListResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/journey-orders")
public class JourneyOrderController {

    private final JourneyOrderService orderService;
    private final HttpServletRequest request;

    public JourneyOrderController(JourneyOrderService orderService, HttpServletRequest request) {
        this.orderService = orderService;
        this.request = request;
    }

    @PostMapping
    public ResponseEntity<?> createOrder(
            @Valid @RequestBody CreateJourneyOrderRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        String correlationId = resolveCorrelationId();

        var appRequest = new com.trainticket.journeyorder.application.port.in.JourneyOrderRequest(
            body.accountId(), body.offerId(), body.offerVersion(),
            body.travelerRefs(), body.segmentRefs()
        );

        JourneyOrderResult result = orderService.createOrder(appRequest, idempotencyKey, correlationId);

        CreateJourneyOrderResponse response = new CreateJourneyOrderResponse(
            result.orderId(), result.accountId(), result.offerId(),
            toApiMonetarySummary(result.monetarySummary()),
            result.status(), result.travelerRefs(), result.segmentRefs(), result.createdAt()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable String orderId) {
        Optional<JourneyOrderResult> result = orderService.getOrder(orderId);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "NOT_FOUND", "message", "Order not found: " + orderId,
                    "correlationId", resolveCorrelationId(), "details", Map.of()));
        }

        JourneyOrderResult r = result.get();
        GetJourneyOrderResponse response = new GetJourneyOrderResponse(
            r.orderId(), r.accountId(), r.offerId(),
            toApiMonetarySummary(r.monetarySummary()),
            r.status(), r.travelerRefs(), r.segmentRefs(), r.createdAt()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<ListJourneyOrdersResponse> listOrders(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        OrderListResult list = orderService.listOrders(accountId, status, limit, offset);

        var items = list.items().stream()
            .map(r -> new CreateJourneyOrderResponse(
                r.orderId(), r.accountId(), r.offerId(),
                toApiMonetarySummary(r.monetarySummary()),
                r.status(), r.travelerRefs(), r.segmentRefs(), r.createdAt()
            ))
            .toList();

        return ResponseEntity.ok(new ListJourneyOrdersResponse(items, list.total(), list.limit(), list.offset()));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<?> cancelOrder(
            @PathVariable String orderId,
            @Valid @RequestBody CancelJourneyOrderRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        String correlationId = resolveCorrelationId();

        var appRequest = new com.trainticket.journeyorder.application.port.in.CancelJourneyOrderRequest(orderId, body.reason());
        CancelJourneyOrderResult result = orderService.cancelOrder(appRequest, idempotencyKey, correlationId);

        return ResponseEntity.ok(new CancelJourneyOrderResponse(result.orderId(), result.status(), result.cancelledAt()));
    }

    private static MonetarySummaryDto toApiMonetarySummary(JourneyOrderResult.MonetarySummaryDto summary) {
        return new MonetarySummaryDto(
            new MoneyDto(summary.currency(), summary.subtotal()),
            new MoneyDto(summary.currency(), summary.taxTotal()),
            new MoneyDto(summary.currency(), summary.feeTotal()),
            new MoneyDto(summary.currency(), summary.discountTotal()),
            new MoneyDto(summary.currency(), summary.cancelledTotal()),
            new MoneyDto(summary.currency(), summary.payableTotal()),
            new MoneyDto(summary.currency(), summary.payableTotal()),
            summary.currency()
        );
    }

    private String resolveCorrelationId() {
        String fromMdc = MDC.get("correlationId");
        if (fromMdc != null && !fromMdc.isBlank()) return fromMdc;
        if (request != null) {
            String fromHeader = request.getHeader("X-Correlation-Id");
            if (fromHeader != null && !fromHeader.isBlank()) return fromHeader;
        }
        return UUID.randomUUID().toString();
    }
}
