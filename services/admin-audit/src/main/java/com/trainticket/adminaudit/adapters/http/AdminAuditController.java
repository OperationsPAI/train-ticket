package com.trainticket.adminaudit.adapters.http;

import org.springframework.beans.factory.annotation.Autowired;
import com.trainticket.adminaudit.RequestContextFilter;
import com.trainticket.adminaudit.application.AdminAuditService;
import com.trainticket.adminaudit.domain.AuditEntry;
import com.trainticket.adminaudit.domain.ManualAction;
import com.trainticket.adminaudit.domain.OperatorIdentity;
import com.trainticket.adminaudit.domain.OperatorRole;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
@RequestMapping("/api/v1/admin")
public class AdminAuditController {
    private final AdminAuditService service;
    private final IdempotencyService idempotency;

    @Autowired
    public AdminAuditController(AdminAuditService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping("/operators")
    public ResponseEntity<Object> registerOperator(
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody RegisterOperatorRequest request,
        HttpServletRequest httpRequest
    ) {
        request.validate();
        return idempotency.execute(idempotencyKey, request, 201, () -> OperatorResponse.from(service.registerOperator(
            request.email(),
            OperatorRole.valueOf(request.role()),
            AdminAuditService.parseScopes(request.scopes()),
            correlationId(httpRequest)
        )));
    }

    @PostMapping("/manual-actions")
    public ResponseEntity<Object> requestManualAction(
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody ManualActionRequest request,
        HttpServletRequest httpRequest
    ) {
        request.validate();
        return idempotency.execute(idempotencyKey, request, 201, () -> ManualActionResponse.from(service.requestManualAction(
            request.targetDomain(),
            request.targetCommand(),
            request.businessRef(),
            request.reasonCode(),
            request.description(),
            request.requestedByOperatorId(),
            request.requiresApproval(),
            correlationId(httpRequest)
        )));
    }

    @PostMapping("/manual-actions/{manualActionId}/approve")
    public ResponseEntity<Object> approveManualAction(
        @PathVariable String manualActionId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody ApproveManualActionRequest request,
        HttpServletRequest httpRequest
    ) {
        request.validate();
        ApproveFingerprint fingerprint = new ApproveFingerprint(manualActionId, request.approvedByOperatorId());
        return idempotency.execute(idempotencyKey, fingerprint, 200, () -> ApprovalResponse.from(service.approveManualAction(
            manualActionId,
            request.approvedByOperatorId(),
            correlationId(httpRequest)
        )));
    }


    @PostMapping("/manual-actions/{manualActionId}/reject")
    public ResponseEntity<Object> rejectManualAction(
        @PathVariable String manualActionId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody RejectManualActionRequest request,
        HttpServletRequest httpRequest
    ) {
        request.validate();
        RejectFingerprint fingerprint = new RejectFingerprint(manualActionId, request.rejectedByOperatorId(), request.reason());
        return idempotency.execute(idempotencyKey, fingerprint, 200, () -> ManualActionResponse.from(service.rejectManualAction(
            manualActionId,
            request.rejectedByOperatorId(),
            request.reason(),
            correlationId(httpRequest)
        )));
    }

    @PostMapping("/manual-actions/{manualActionId}/execute")
    public ResponseEntity<Object> executeManualAction(
        @PathVariable String manualActionId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody ExecuteManualActionRequest request,
        HttpServletRequest httpRequest
    ) {
        request.validate();
        ExecuteFingerprint fingerprint = new ExecuteFingerprint(manualActionId, request.resultSummary());
        return idempotency.execute(idempotencyKey, fingerprint, 200, () -> ManualActionResponse.from(service.executeManualAction(
            manualActionId,
            request.resultSummary(),
            correlationId(httpRequest)
        )));
    }

    @GetMapping("/audit-trail")
    public AuditTrailPage listAuditTrail(
        @RequestParam(value = "businessRef", required = false) String businessRef,
        @RequestParam(value = "limit", defaultValue = "20") int limit,
        @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        AdminAuditService.Page<AuditEntry> page = service.listAuditTrail(businessRef, limit, offset);
        return new AuditTrailPage(
            page.items().stream().map(AuditEntryResponse::from).toList(),
            page.total(),
            page.limit(),
            page.offset()
        );
    }

    @GetMapping("/operators/{operatorId}")
    public OperatorDetailsResponse getOperator(@PathVariable String operatorId) {
        return OperatorDetailsResponse.from(service.getOperator(operatorId));
    }

    private static String correlationId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestContextFilter.CORRELATION_ID_HEADER);
    }

    public record RegisterOperatorRequest(String email, String role, List<String> scopes, String displayName) {
        void validate() {
            requireText(email, "email");
            requireText(role, "role");
            requireText(displayName, "displayName");
            try {
                OperatorRole.valueOf(role);
            } catch (IllegalArgumentException exception) {
                throw new com.trainticket.adminaudit.application.ValidationException("invalid role");
            }
            if (scopes == null || scopes.isEmpty()) {
                throw new com.trainticket.adminaudit.application.ValidationException("scopes are required");
            }
        }
    }

    public record ManualActionRequest(
        String targetDomain,
        String targetCommand,
        String businessRef,
        String reasonCode,
        String description,
        String requestedByOperatorId,
        Boolean requiresApproval
    ) {
        void validate() {
            requireText(targetDomain, "targetDomain");
            requireText(targetCommand, "targetCommand");
            requireText(businessRef, "businessRef");
            requireText(reasonCode, "reasonCode");
            requireText(description, "description");
            requireText(requestedByOperatorId, "requestedByOperatorId");
            if (requiresApproval == null) {
                throw new com.trainticket.adminaudit.application.ValidationException("requiresApproval is required");
            }
        }
    }

    public record ApproveManualActionRequest(String approvedByOperatorId) {
        void validate() {
            requireText(approvedByOperatorId, "approvedByOperatorId");
        }
    }


    public record RejectManualActionRequest(String rejectedByOperatorId, String reason) {
        void validate() {
            requireText(rejectedByOperatorId, "rejectedByOperatorId");
            requireText(reason, "reason");
        }
    }

    public record ExecuteManualActionRequest(String resultSummary) {
        void validate() {
            requireText(resultSummary, "resultSummary");
        }
    }

    private record ApproveFingerprint(String manualActionId, String approvedByOperatorId) {}
    private record RejectFingerprint(String manualActionId, String rejectedByOperatorId, String reason) {}
    private record ExecuteFingerprint(String manualActionId, String resultSummary) {}

    public record OperatorResponse(String operatorId, String email, String role, List<String> scopes, String status) {
        static OperatorResponse from(OperatorIdentity operator) {
            return new OperatorResponse(
                operator.operatorId(),
                operator.email(),
                operator.role().name(),
                operator.scopes().stream().map(Enum::name).sorted().toList(),
                operator.active() ? "ACTIVE" : "SUSPENDED"
            );
        }
    }

    public record OperatorDetailsResponse(
        String operatorId,
        String email,
        String role,
        List<String> scopes,
        String status,
        String registeredAt
    ) {
        static OperatorDetailsResponse from(OperatorIdentity operator) {
            return new OperatorDetailsResponse(
                operator.operatorId(),
                operator.email(),
                operator.role().name(),
                operator.scopes().stream().map(Enum::name).sorted().toList(),
                operator.active() ? "ACTIVE" : "SUSPENDED",
                operator.registeredAt().toString()
            );
        }
    }

    public record ManualActionResponse(String manualActionId, String targetDomain, String status) {
        static ManualActionResponse from(ManualAction action) {
            return new ManualActionResponse(action.manualActionId(), action.targetDomain(), action.state().name());
        }
    }

    public record ApprovalResponse(String manualActionId, String status) {
        static ApprovalResponse from(ManualAction action) {
            return new ApprovalResponse(action.manualActionId(), "APPROVED");
        }
    }

    public record AuditTrailPage(List<AuditEntryResponse> items, int total, int limit, int offset) {}

    public record AuditEntryResponse(
        String entryId,
        String actorId,
        String actorDisplayName,
        String actionType,
        String resourceRef,
        String resourceDomain,
        String reasonCode,
        String correlationId,
        String resultSummary,
        String correctedEntryId,
        String recordedAt
    ) {
        static AuditEntryResponse from(AuditEntry entry) {
            return new AuditEntryResponse(
                entry.entryId(),
                entry.actorId(),
                entry.actorDisplayName(),
                entry.actionType(),
                entry.resourceRef(),
                entry.resourceDomain(),
                entry.reasonCode(),
                entry.correlationId(),
                entry.resultSummary(),
                entry.correctedEntryId(),
                entry.recordedAt().toString()
            );
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new com.trainticket.adminaudit.application.ValidationException(field + " is required");
        }
    }
}
