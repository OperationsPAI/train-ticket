package com.trainticket.postsales.api;

import com.trainticket.postsales.application.IdempotencyStore;
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
    private final IdempotencyStore idempotencyStore;

    public PostSalesController(PostSalesApplicationService service, IdempotencyStore idempotencyStore) {
        this.service = service;
        this.idempotencyStore = idempotencyStore;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> open(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @Valid @RequestBody OpenCaseRequest request
    ) {
        var result = idempotencyStore.execute("POST /api/v1/post-sales-cases " + idempotencyKey, () -> {
            PostSalesCase postSalesCase = service.open(new PostSalesApplicationService.OpenCaseCommand(
                request.journeyOrderId(),
                request.caseType(),
                request.scope().toDomain(),
                request.reasonCode(),
                request.actorRef(),
                idempotencyKey,
                idempotencyKey,
                correlationIdOrRequest(idempotencyKey, correlationId)
            ));
            return PostSalesMapper.openResponse(postSalesCase);
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(result.value());
    }

    @PostMapping("/{caseId}/evaluate")
    public Map<String, Object> evaluate(
        @PathVariable String caseId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        return idempotencyStore.execute("POST /api/v1/post-sales-cases/" + caseId + "/evaluate " + idempotencyKey, () ->
            PostSalesMapper.evaluateResponse(service.evaluate(caseId, idempotencyKey, correlationIdOrRequest(idempotencyKey, correlationId)))
        ).value();
    }

    @PostMapping("/{caseId}/approve")
    public Map<String, Object> approve(
        @PathVariable String caseId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        return idempotencyStore.execute("POST /api/v1/post-sales-cases/" + caseId + "/approve " + idempotencyKey, () ->
            PostSalesMapper.approveResponse(service.approve(caseId, idempotencyKey, correlationIdOrRequest(idempotencyKey, correlationId)))
        ).value();
    }

    @GetMapping("/{caseId}")
    public Map<String, Object> get(@PathVariable String caseId) {
        return PostSalesMapper.caseDetails(service.get(caseId));
    }

    private static String correlationIdOrRequest(String idempotencyKey, String correlationId) {
        return correlationId == null || correlationId.isBlank() ? idempotencyKey : correlationId;
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
