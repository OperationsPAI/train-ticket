package com.trainticket.payment.adapters.http;
import com.trainticket.payment.application.OrphanRefundRecoveryService;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrphanRefundRecoveryController {
    private final OrphanRefundRecoveryService recovery;
    public OrphanRefundRecoveryController(OrphanRefundRecoveryService recovery){this.recovery=recovery;}
    @PostMapping("/api/v1/internal/refund-recoveries/{caseId}")
    public RefundDetailsResponse recover(@PathVariable String caseId,@RequestBody Request request) {
        return PaymentHttpMapper.refundDetails(recovery.recover(caseId,request.channelRefundId()));
    }
    public record Request(String channelRefundId){}
}
