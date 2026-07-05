package com.trainticket.postsales.api;

import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.postsales.application.PostSalesApplicationService;
import com.trainticket.postsales.application.PostSalesMapper;
import com.trainticket.postsales.domain.PostSalesCase;
import com.trainticket.postsales.domain.PostSalesCaseType;
import com.trainticket.postsales.domain.PostSalesScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/post-sales-cases")
@Validated
public class PostSalesController {
    private final PostSalesApplicationService service;

    public PostSalesController(PostSalesApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> open(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        HttpServletRequest httpRequest,
        @Valid @RequestBody OpenCaseRequest request
    ) {
        PostSalesCase postSalesCase = service.open(new PostSalesApplicationService.OpenCaseCommand(
            request.journeyOrderId(),
            request.caseType(),
            request.scope().toDomain(),
            request.reasonCode(),
            request.actorRef(),
            idempotencyKey,
            commandId(),
            requestCorrelationId(httpRequest)
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(PostSalesMapper.openResponse(postSalesCase));
    }

    @PostMapping("/{caseId}/evaluate")
    public Map<String, Object> evaluate(
        @PathVariable String caseId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        HttpServletRequest httpRequest
    ) {
        return PostSalesMapper.evaluateResponse(service.evaluate(caseId, commandId(), requestCorrelationId(httpRequest)));
    }

    @PostMapping("/{caseId}/approve")
    public Map<String, Object> approve(
        @PathVariable String caseId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        HttpServletRequest httpRequest
    ) {
        return PostSalesMapper.approveResponse(service.approve(caseId, commandId(), requestCorrelationId(httpRequest)));
    }

    @GetMapping("/{caseId}")
    public Map<String, Object> get(@PathVariable String caseId) {
        return PostSalesMapper.caseDetails(service.get(caseId));
    }


    private static String requestCorrelationId(HttpServletRequest request) {
        Object correlationId = request.getAttribute("correlationId");
        if (correlationId instanceof String value && !value.isBlank()) {
            return value;
        }
        String header = request.getHeader("X-Correlation-Id");
        return header == null || header.isBlank() ? null : header;
    }

    private static String commandId() {
        return PrefixedIds.newCommandId();
    }

    public record OpenCaseRequest(
        @NotBlank String journeyOrderId,
        @NotNull PostSalesCaseType caseType,
        @NotNull ScopeRequest scope,
        @NotBlank String reasonCode,
        @NotBlank String actorRef
    ) { }

    public record ScopeRequest(
        @NotNull List<String> orderItemRefs,
        @NotNull List<String> segmentRefs,
        @NotNull List<String> travelerRefs,
        @NotNull List<String> entitlementRefs
    ) {
        PostSalesScope toDomain() {
            return new PostSalesScope(orderItemRefs, segmentRefs, travelerRefs, entitlementRefs);
        }
    }
}
