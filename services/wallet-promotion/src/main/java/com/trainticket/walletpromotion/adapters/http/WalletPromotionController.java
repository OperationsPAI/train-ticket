package com.trainticket.walletpromotion.adapters.http;

import com.trainticket.walletpromotion.application.*;
import com.trainticket.walletpromotion.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WalletPromotionController {
    private final WalletPromotionService service;
    public WalletPromotionController(WalletPromotionService service) { this.service = service; }
    @PostMapping("/benefits") @ResponseStatus(HttpStatus.CREATED) public PromotionInstrument issue(@RequestBody IssueBenefitCommand request) { return service.issue(request); }
    @GetMapping("/benefits/{benefitId}") public PromotionInstrument get(@PathVariable String benefitId) { return service.getBenefit(benefitId); }
    @GetMapping("/benefits") public WalletPromotionService.PagedBenefits list(@RequestParam String byAccountId, @RequestParam(required=false) PromotionStatus status, @RequestParam(required=false) BenefitType benefitType, @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset) { return service.list(byAccountId, status, benefitType, limit, offset); }
    @PostMapping("/benefits/{benefitId}/reserve") public PromotionInstrument reserve(@PathVariable String benefitId, @RequestBody ReserveBenefitCommand request) { return service.reserve(benefitId, request); }
    @PostMapping("/benefits/{benefitId}/redeem") public WalletPromotionService.RedeemResult redeem(@PathVariable String benefitId, @RequestBody RedeemBenefitCommand request) { return service.redeem(benefitId, request); }
    @PostMapping("/benefits/{benefitId}/release") public PromotionInstrument release(@PathVariable String benefitId, @RequestBody ReleaseBenefitCommand request) { return service.release(benefitId, request); }
    @PostMapping("/benefits/{benefitId}/revoke") public PromotionInstrument revoke(@PathVariable String benefitId, @RequestBody RevokeBenefitCommand request) { return service.revoke(benefitId, request); }
    @PostMapping("/benefits/{benefitId}/reverse-redemption") public WalletPromotionService.ReverseResult reverse(@PathVariable String benefitId, @RequestBody ReverseRedemptionCommand request) { return service.reverse(benefitId, request); }
    @GetMapping("/wallet-accounts/{accountId}") public WalletAccount wallet(@PathVariable String accountId) { return service.getWallet(accountId); }
}
