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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.trainticket.postsales.application.RefundFundingService refundFunding;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.trainticket.postsales.application.PostSalesExecutionReconciliationService reconciliation;

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

    @PostMapping("/{caseId}/target-offer")
    public Map<String, Object> selectTargetOffer(@PathVariable String caseId,
        @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody TargetOfferRequest request) {
        return PostSalesMapper.caseDetails(service.selectChangeOffer(caseId, request.targetOfferId()));
    }

    public record TargetOfferRequest(@NotBlank String targetOfferId) {}
    @PostMapping("/{caseId}/target-offer/revise")
    public Map<String,Object> reviseTarget(@PathVariable String caseId,@Valid @RequestBody ReviseTargetRequest request) {
        return PostSalesMapper.caseDetails(service.reviseChangeOffer(caseId,request.expectedOfferId(),request.targetOfferId()));
    }
    public record ReviseTargetRequest(@NotBlank String expectedOfferId,@NotBlank String targetOfferId){}

    @PostMapping("/{caseId}/approve")
    public Map<String, Object> approve(
        @PathVariable String caseId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        HttpServletRequest httpRequest
    ) {
        String correlation=requestCorrelationId(httpRequest);
        if(refundFunding!=null)refundFunding.prepare(caseId,correlation);
        com.trainticket.postsales.domain.PostSalesCase approved=service.approve(caseId, commandId(), correlation);
        // The steps a case waits for are events, and an event can be published before the
        // case that needs it exists: measured, a refund whose capacity hold was released at
        // 01:24:11 while its case opened at 01:24:21, which then waited for ever because the
        // release had already been consumed and nothing would mention it again. Reading the
        // owning services' state once, here, applies whatever is already true; a case whose
        // work is done completes at once, and one whose work is not waits as before.
        if(reconciliation!=null && approved.status()==com.trainticket.postsales.domain.PostSalesCaseStatus.EXECUTING
            && refundLike(approved.caseType())) {
            try {
                approved=reconciliation.reconcile(caseId, commandId(), correlation);
            } catch(RuntimeException ignored) {
                // The read is an addition, never a condition: a case whose state cannot be read
                // waits for its events exactly as it did before this call existed. Letting it
                // fail the approval would turn a read the caller did not ask for into a refusal
                // -- measured, 422 "execution reconciliation supports refund-like cases only"
                // on every change approval until this guard was added.
            }
        }
        return PostSalesMapper.approveResponse(approved);
    }

    /// The case types whose execution is reconciled by reading owner state. Matches
    /// PostSalesExecutionEvidenceReader's supported set.
    private static boolean refundLike(com.trainticket.postsales.domain.PostSalesCaseType caseType) {
        return caseType==com.trainticket.postsales.domain.PostSalesCaseType.REFUND
            || caseType==com.trainticket.postsales.domain.PostSalesCaseType.CANCELLATION
            || caseType==com.trainticket.postsales.domain.PostSalesCaseType.REBOOK;
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
            return new PostSalesScope(
                orderItemRefs == null ? List.of() : orderItemRefs,
                segmentRefs == null ? List.of() : segmentRefs,
                travelerRefs == null ? List.of() : travelerRefs,
                entitlementRefs == null ? List.of() : entitlementRefs);
        }
    }
}
