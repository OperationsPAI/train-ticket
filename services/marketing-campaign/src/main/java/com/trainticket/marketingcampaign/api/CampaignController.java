package com.trainticket.marketingcampaign.api;

import com.trainticket.marketingcampaign.application.MarketingCampaignService;
import com.trainticket.marketingcampaign.application.MarketingCampaignService.CampaignDetail;
import com.trainticket.marketingcampaign.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController extends CampaignApiSupport {
    private final MarketingCampaignService service;

    public CampaignController(MarketingCampaignService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CampaignDetail> draft(@RequestBody DraftCampaignRequest request, HttpServletRequest httpRequest) {
        request.validate();
        CampaignDetail detail = service.draftCampaign(
            new MarketingCampaignService.DraftCampaignCommand(request.externalKey(), request.name(), request.window().toWindow()),
            correlationId(httpRequest)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @GetMapping("/{campaignId}")
    public CampaignDetail get(@PathVariable String campaignId) {
        return service.get(campaignId);
    }

    @PostMapping("/{campaignId}/submit")
    public CampaignDetail submit(@PathVariable String campaignId, HttpServletRequest httpRequest) {
        return service.submitCampaign(campaignId, correlationId(httpRequest));
    }

    @PostMapping("/{campaignId}/approve")
    public CampaignDetail approve(@PathVariable String campaignId, @RequestBody ApprovalRequest request, HttpServletRequest httpRequest) {
        request.validate();
        return service.approveCampaign(campaignId, new MarketingCampaignService.ApproveCampaignCommand(request.approvalRef()), correlationId(httpRequest));
    }

    @PostMapping("/{campaignId}/schedule")
    public CampaignDetail schedule(@PathVariable String campaignId, HttpServletRequest httpRequest) {
        return service.scheduleCampaign(campaignId, correlationId(httpRequest));
    }

    @PostMapping("/{campaignId}/start")
    public CampaignDetail start(@PathVariable String campaignId, HttpServletRequest httpRequest) {
        return service.startCampaign(campaignId, correlationId(httpRequest));
    }

    @PostMapping("/{campaignId}/pause")
    public CampaignDetail pause(@PathVariable String campaignId, @RequestBody OperatorReasonRequest request, HttpServletRequest httpRequest) {
        request.validate();
        return service.pauseCampaign(campaignId, new MarketingCampaignService.OperatorReasonCommand(request.reason(), request.operatorRef()), correlationId(httpRequest));
    }

    @PostMapping("/{campaignId}/complete")
    public CampaignDetail complete(@PathVariable String campaignId, @RequestBody ReasonRequest request, HttpServletRequest httpRequest) {
        request.validate();
        return service.completeCampaign(campaignId, new MarketingCampaignService.ReasonCommand(request.reason()), correlationId(httpRequest));
    }

    @PostMapping("/{campaignId}/cancel")
    public CampaignDetail cancel(@PathVariable String campaignId, @RequestBody OperatorReasonRequest request, HttpServletRequest httpRequest) {
        request.validate();
        return service.cancelCampaign(campaignId, new MarketingCampaignService.OperatorReasonCommand(request.reason(), request.operatorRef()), correlationId(httpRequest));
    }

    @PostMapping("/{campaignId}/budget")
    public CampaignDetail setBudget(@PathVariable String campaignId, @RequestBody SetBudgetRequest request, HttpServletRequest httpRequest) {
        request.validate();
        return service.setBudget(campaignId, new MarketingCampaignService.SetBudgetCommand(request.totalBudget().toMoney(), request.targetRuleSetId()), correlationId(httpRequest));
    }

    public record DraftCampaignRequest(String externalKey, String name, WindowRequest window) {
        void validate() {
            requireText(externalKey, "externalKey");
            requireText(name, "name");
            if (window == null) throw new DomainException("window is required");
        }
    }

    public record ApprovalRequest(String approvalRef) {
        void validate() { requireText(approvalRef, "approvalRef"); }
    }

    public record ReasonRequest(String reason) {
        void validate() { requireText(reason, "reason"); }
    }

    public record OperatorReasonRequest(String reason, String operatorRef) {
        void validate() {
            requireText(reason, "reason");
            requireText(operatorRef, "operatorRef");
        }
    }

    public record SetBudgetRequest(MoneyRequest totalBudget, String targetRuleSetId) {
        void validate() {
            if (totalBudget == null) throw new DomainException("totalBudget is required");
            requireText(targetRuleSetId, "targetRuleSetId");
        }
    }
}
