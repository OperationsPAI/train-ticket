package com.trainticket.marketingcampaign.api;

import com.trainticket.marketingcampaign.application.MarketingCampaignService;
import com.trainticket.marketingcampaign.application.MarketingCampaignService.TemplateDetail;
import com.trainticket.marketingcampaign.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CouponTemplateController extends CampaignApiSupport {
    private final MarketingCampaignService service;

    public CouponTemplateController(MarketingCampaignService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/campaigns/{campaignId}/templates")
    public ResponseEntity<TemplateDetail> draft(@PathVariable String campaignId, @RequestBody DraftTemplateRequest request, HttpServletRequest httpRequest) {
        request.validate();
        TemplateDetail detail = service.draftTemplate(
            campaignId,
            new MarketingCampaignService.DraftTemplateCommand(
                request.templateCode(),
                request.templateVersion(),
                request.faceValue().toMoney(),
                request.minimumSpend().toMoney(),
                request.applicableScope(),
                request.redemptionRule(),
                request.validityWindow().toWindow()
            ),
            correlationId(httpRequest)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @PostMapping("/api/v1/templates/{templateId}/validate")
    public TemplateDetail validate(@PathVariable String templateId, HttpServletRequest httpRequest) {
        return service.validateTemplate(templateId, correlationId(httpRequest));
    }

    @PostMapping("/api/v1/templates/{templateId}/publish")
    public TemplateDetail publish(@PathVariable String templateId, @RequestBody ApprovalRequest request, HttpServletRequest httpRequest) {
        request.validate();
        return service.publishTemplate(templateId, new MarketingCampaignService.ApproveCampaignCommand(request.approvalRef()), correlationId(httpRequest));
    }

    @PostMapping("/api/v1/templates/{templateId}/retire")
    public TemplateDetail retire(@PathVariable String templateId, @RequestBody ReasonRequest request, HttpServletRequest httpRequest) {
        request.validate();
        return service.retireTemplate(templateId, new MarketingCampaignService.ReasonCommand(request.reason()), correlationId(httpRequest));
    }

    public record DraftTemplateRequest(String templateCode, int templateVersion, MoneyRequest faceValue, MoneyRequest minimumSpend, String applicableScope, String redemptionRule, WindowRequest validityWindow) {
        void validate() {
            requireText(templateCode, "templateCode");
            if (templateVersion <= 0) throw new DomainException("templateVersion must be positive");
            if (faceValue == null) throw new DomainException("faceValue is required");
            if (minimumSpend == null) throw new DomainException("minimumSpend is required");
            requireText(applicableScope, "applicableScope");
            requireText(redemptionRule, "redemptionRule");
            if (validityWindow == null) throw new DomainException("validityWindow is required");
        }
    }

    public record ApprovalRequest(String approvalRef) {
        void validate() { requireText(approvalRef, "approvalRef"); }
    }

    public record ReasonRequest(String reason) {
        void validate() { requireText(reason, "reason"); }
    }
}
