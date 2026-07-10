package com.trainticket.marketingcampaign.api;

import com.trainticket.marketingcampaign.application.MarketingCampaignService;
import com.trainticket.marketingcampaign.application.MarketingCampaignService.BatchDetail;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IssuanceBatchController extends CampaignApiSupport {
    private final MarketingCampaignService service;

    public IssuanceBatchController(MarketingCampaignService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/campaigns/{campaignId}/batches")
    public ResponseEntity<BatchDetail> plan(@PathVariable String campaignId, @RequestBody PlanBatchRequest request, HttpServletRequest httpRequest) {
        request.validate();
        BatchDetail detail = service.planBatch(campaignId, new MarketingCampaignService.PlanBatchCommand(request.templateId(), request.audienceSnapshotId()), correlationId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @PostMapping("/api/v1/batches/{batchId}/start")
    public BatchDetail start(@PathVariable String batchId, HttpServletRequest httpRequest) {
        return service.startBatch(batchId, correlationId(httpRequest));
    }

    @PostMapping("/api/v1/batches/{batchId}/close")
    public BatchDetail close(@PathVariable String batchId, @RequestBody ReasonRequest request, HttpServletRequest httpRequest) {
        request.validate();
        return service.closeBatch(batchId, new MarketingCampaignService.ReasonCommand(request.reason()), correlationId(httpRequest));
    }

    public record PlanBatchRequest(String templateId, String audienceSnapshotId) {
        void validate() {
            requireText(templateId, "templateId");
            requireText(audienceSnapshotId, "audienceSnapshotId");
        }
    }

    public record ReasonRequest(String reason) {
        void validate() { requireText(reason, "reason"); }
    }
}
