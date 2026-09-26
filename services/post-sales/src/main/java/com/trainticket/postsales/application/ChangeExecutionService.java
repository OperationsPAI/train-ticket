package com.trainticket.postsales.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.domain.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ChangeExecutionService {
    /// The replacement reservation saga records a fifteen minute deadline for its own step, so a
    /// case that has waited longer than that can only be terminal.
    private static final Duration RESERVATION_WAIT_LIMIT = Duration.ofMinutes(15);
    /// How long a cancelled booking's capacity hold is waited for before the case releases it
    /// itself. The release is an event on the booking stream and normally lands in well under a
    /// second; two minutes is far outside any delivery this stream is allowed to be late by, and
    /// a case left waiting on one that will never come is the failure this bounds.
    private static final Duration RELEASE_WAIT_LIMIT = Duration.ofMinutes(2);

    private final PostSalesApplicationService cases;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String baseTemplate;
    private final RefundFundingService funding;
    private final ChangeExecutionGuard guard;
    private final Clock clock;

    public ChangeExecutionService(PostSalesApplicationService cases,RestClient.Builder builder,ObjectMapper mapper,RefundFundingService funding,ChangeExecutionGuard guard,Clock clock,
        @Value("${CHANGE_SERVICE_URL_TEMPLATE:http://%s:8080}") String baseTemplate) {
        this.cases=cases;this.mapper=mapper;this.baseTemplate=baseTemplate;this.funding=funding;this.guard=guard;this.clock=clock;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(5));
        client=builder.requestFactory(factory).build();
    }

    public PostSalesCase advance(String caseId,String correlation) {
        return guard.execute(caseId, () -> advanceLocked(caseId,correlation), () -> cases.get(caseId));
    }

    private PostSalesCase advanceLocked(String caseId,String correlation) {
        PostSalesCase change=cases.get(caseId);
        if(change.caseType()!=PostSalesCaseType.CHANGE || change.decision()==null)throw new DomainRuleViolation("evaluated CHANGE case is required");
        if(change.status()==PostSalesCaseStatus.APPLIED || change.status()==PostSalesCaseStatus.FAILED)return change;
        if(change.status()!=PostSalesCaseStatus.EXECUTING)throw new DomainRuleViolation("change must be approved before execution");
        String id=PostSalesMapper.prefixed(change.caseId(),"psc-");
        Map<String,Map<String,Object>> ops=change.changeOperations();
        if(ops.containsKey("capacity-rejected"))return compensateCapacityFailure(change,correlation);
        if(ops.containsKey("target-expired"))return completeExpiredTarget(change,correlation);
        if(!ops.containsKey("target")) {
            JsonNode order=get("journey-order","/api/v1/journey-orders/"+change.journeyOrderId(),correlation);
            JsonNode offer=get("offer-management","/api/v1/offers/"+change.targetOfferId(),correlation);
            if(!order.path("accountId").asText().equals(offer.path("accountId").asText()))throw new DomainRuleViolation("target offer is unavailable for the original account");
            if(!Instant.now().isBefore(Instant.parse(text(offer,"expiresAt"))))return cases.recordChangeOperation(caseId,"target-expired",
                Map.of("offerId",text(offer,"offerId"),"expiresAt",text(offer,"expiresAt")));
            JsonNode itinerary=get("trip-planning","/api/v1/itineraries/"+text(offer,"itineraryRef"),correlation);
            List<JsonNode> payments=funding.changePayments(change,text(order,"accountId"),false,correlation);
            if(!RefundFundingService.balance(payments).equals(RefundFundingService.money(order.path("monetarySummary").path("payableTotal"))))throw new DomainRuleViolation("recorded payments do not match the current order balance");
            JsonNode fare=get("fare-pricing","/api/v1/fare-quotes/"+text(offer,"fareQuoteRef"),correlation);
            return cases.recordChangeOperation(caseId,"target",Map.of("offer",map(offer),"itinerary",map(itinerary),"fare",map(fare),"payment",map(payments.getFirst()),"accountId",text(order,"accountId")));
        }
        JsonNode target=mapper.valueToTree(ops.get("target"));
        JsonNode offer=target.path("offer");
        for(JsonNode item:offer.path("items")) for(String traveler:change.scope().travelerRefs()) {
            String name="booking:"+item.path("segmentRef").asText()+":"+traveler;
            if(!ops.containsKey(name))return saveCommand(change,name,"booking-orchestration","/api/v1/internal/change-reservations",
                Map.of("caseId",id,"segmentRef",text(item,"segmentRef"),"travelerRef",traveler),correlation);
        }
        boolean waitingForReservation=false;
        for(JsonNode item:offer.path("items")) for(String traveler:change.scope().travelerRefs()) {
            String name="booking:"+item.path("segmentRef").asText()+":"+traveler;
            String bookingId=String.valueOf(ops.get(name).get("segmentBookingId"));
            if(!ops.containsKey("confirmed:"+bookingId)) {
                JsonNode booking=get("booking-orchestration","/api/v1/internal/segment-bookings/"+bookingId,correlation);
                if("FAILED".equals(text(booking,"status")) && "NO_AVAILABLE_CAPACITY".equals(booking.path("failureReason").asText())) {
                    return cases.recordChangeOperation(caseId,"capacity-rejected",Map.of("segmentBookingId",bookingId,"source","booking-orchestration","code","NO_AVAILABLE_CAPACITY"));
                }
                if("FAILED".equals(text(booking,"status")) || "CANCELLED".equals(text(booking,"status")))throw new DomainRuleViolation("replacement reservation failed: "+bookingId);
                Map<String,Object> waiting=ops.get("waiting:"+name);
                if(!ops.get(name).containsKey("requestedAt") && waiting==null) {
                    // Operations recorded before the wait deadline existed carry no request time;
                    // start their clock now instead of treating them as fresh forever.
                    return cases.recordChangeOperation(caseId,"waiting:"+name,Map.of("since",clock.instant().toString()));
                }
                if(reservationWaitExpired(ops.get(name),waiting))return cases.failReservationTimeout(caseId,correlation);
                if(!"CONFIRMED".equals(text(booking,"status"))) {
                    waitingForReservation=true;continue;
                }
                JsonNode hold=getPending("capacity-availability","/api/v1/capacity-holds/"+text(booking,"capacityHoldId"),correlation);
                if(hold==null || !"CONFIRMED".equals(text(hold,"status"))) { waitingForReservation=true;continue; }
                return cases.recordChangeOperation(caseId,"confirmed:"+bookingId,Map.of("booking",map(booking),"hold",map(hold)));
            }
        }
        if(waitingForReservation)return change;
        Money due=change.decision().amountSnapshot().extraChargeAmount();
        if(!due.isZero()) {
            if(!ops.containsKey("difference-payment"))return saveCommand(change,"difference-payment","payment","/api/v1/payment-intents",
                Map.of("businessRef",id,"purpose","change","payerRef",text(target,"accountId"),"amount",PostSalesMapper.money(due),"preferredChannel","ALIPAY_SIM"),correlation);
            String paymentId=String.valueOf(ops.get("difference-payment").get("paymentIntentId"));
            JsonNode currentPayment=get("payment","/api/v1/payment-intents/"+paymentId,correlation);
            if(Set.of("EXPIRED","CANCELLED","FAILED").contains(text(currentPayment,"status"))
                && currentPayment.path("capturedAmount").path("minorUnits").asLong()==0) {
                return compensateUnpaidChange(change,currentPayment,correlation);
            }
            if(currentPayment.path("capturedAmount").path("minorUnits").asLong()==0
                && !Instant.now().isBefore(Instant.parse(text(currentPayment,"expiresAt")))) {
                if(!id.equals(text(currentPayment,"businessRef")) || !"change".equals(text(currentPayment,"purpose")))throw new DomainRuleViolation("rejected difference payment identity mismatch");
                return saveCommand(change,"cancel-expired-difference","payment","/api/v1/payment-intents/"+paymentId+"/cancel",
                    Map.of("reason","CHANGE_PAYMENT_TERMINATED"),correlation);
            }
            if(!ops.containsKey("difference-captured"))return saveCommand(change,"difference-captured","payment","/api/v1/payment-intents/"+paymentId+"/capture",
                Map.of("channelRef",Map.of("channel","ALIPAY_SIM")),correlation);
            JsonNode paid=get("payment","/api/v1/payment-intents/"+paymentId,correlation);
            if(!"CAPTURED".equals(text(paid,"status")))return change;
            if(!id.equals(text(paid,"businessRef")) || due.toMinorUnits()!=paid.path("capturedAmount").path("minorUnits").asLong())throw new DomainRuleViolation("change payment does not match the quoted amount");
        }
        for(var operation:ops.entrySet())if(operation.getKey().startsWith("confirmed:")) {
            JsonNode booking=mapper.valueToTree(operation.getValue()).path("booking");
            String bookingId=text(booking,"segmentBookingId");
            if(!ops.containsKey("ticket:"+bookingId)) {
                JsonNode leg=null;
                for(JsonNode candidate:target.path("itinerary").path("legs"))if(text(booking,"segmentRef").equals(candidate.path("serviceSegmentRef").asText()))leg=candidate;
                if(leg==null)throw new DomainRuleViolation("replacement segment is outside target itinerary");
                JsonNode hold=mapper.valueToTree(operation.getValue()).path("hold");
                Map<String,Object> body=new LinkedHashMap<>();
                body.put("segmentBookingId",bookingId);body.put("journeyOrderId",change.journeyOrderId());body.put("travelerRef",text(booking,"travelerRef"));
                body.put("segmentRef",text(booking,"segmentRef"));body.put("issuePurpose","REPLACEMENT");
                body.put("scheduledServiceRef",text(leg,"servicePlanRef"));body.put("serviceDate",Instant.parse(text(leg,"departureTime")).atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString());
                body.put("capacityHoldId",text(hold,"holdId"));body.put("capacityUnitRef",text(hold,"capacityUnitRef"));body.put("interval",map(hold.path("interval")));
                body.put("classRef",text(target.path("fare").path("breakdown").path("seatClassMultiplier"),"seatClass"));body.put("expiresAt",text(leg,"departureTime"));
                return saveCommand(change,"ticket:"+bookingId,"entitlement-ticketing","/api/v1/entitlements",body,correlation);
            }
        }
        for(String ticket:change.scope().entitlementRefs()) {
            if(!ops.containsKey("void:"+ticket))return saveCommand(change,"void:"+ticket,"entitlement-ticketing","/api/v1/entitlements/"+ticket+"/void",
                Map.of("reason","CHANGE","policy","NORMAL","businessCaseRef",id),correlation);
        }
        for(String bookingId:change.scope().orderItemRefs()) {
            JsonNode booking=get("booking-orchestration","/api/v1/internal/segment-bookings/"+bookingId,correlation);
            if(!"CANCELLED".equals(text(booking,"status")))return change;
            String holdId=text(booking,"capacityHoldId");
            if(holdId==null || holdId.isBlank())throw new DomainRuleViolation("cancelled booking has no capacity hold: "+bookingId);
            JsonNode hold=get("capacity-availability","/api/v1/capacity-holds/"+holdId,correlation);
            if(!"RELEASED".equals(text(hold,"status"))) {
                String waitKey="waiting-release:"+bookingId;
                Map<String,Object> waiting=ops.get(waitKey);
                if(waiting==null)return cases.recordChangeOperation(caseId,waitKey,Map.of("since",clock.instant().toString()));
                if(!releaseWaitExpired(waiting))return change;
                // Capacity releases this hold when it consumes SegmentBookingCancelled, and that
                // event can be lost: the stream is trimmed by count, so a group that falls further
                // behind than the retention never sees the entry, and one whose handling fails
                // five times is moved to the dead letter, which nothing replays. Either way the
                // hold stays Confirmed for a booking that is CANCELLED, the seat is held out of
                // the pool, and this case waits for ever: measured, 34 such holds and one case
                // EXECUTING for five and a half hours, while every later journey that reused the
                // order failed. Releasing it here is idempotent and puts the seat back where the
                // cancellation said it belonged.
                releaseHold(change,bookingId,holdId,correlation);
                JsonNode released=get("capacity-availability","/api/v1/capacity-holds/"+holdId,correlation);
                if(!"RELEASED".equals(text(released,"status")))throw new DomainRuleViolation("cancelled booking's hold is still "+text(released,"status")+": "+holdId);
            }
            if(!change.releasedOrderItems().contains(bookingId))return cases.recordChangeCapacityReleased(caseId,bookingId);
        }
        Money refund=change.decision().amountSnapshot().refundAmount();
        if(!refund.isZero()) {
            if(!ops.containsKey("refund-funding") && !ops.containsKey("difference-refund")) {
                long remaining=refund.toMinorUnits();
                List<Map<String,Object>> allocations=new ArrayList<>();
                for(JsonNode payment:funding.changePayments(change,text(target,"accountId"),false,correlation)) {
                    Money available=RefundFundingService.balance(List.of(payment));
                    available.compareTo(refund);
                    long assigned=Math.min(remaining,available.toMinorUnits());
                    if(assigned>0)allocations.add(Map.of("paymentIntentId",text(payment,"paymentIntentId"),"amount",PostSalesMapper.money(Money.fromMinorUnits(assigned,refund.currency().getCurrencyCode())),"channelRef",map(payment.path("channelRef"))));
                    remaining-=assigned;
                    if(remaining==0)break;
                }
                if(remaining!=0)throw new DomainRuleViolation("change payments do not cover the approved difference refund");
                return cases.recordChangeOperation(caseId,"refund-funding",Map.of("allocations",allocations));
            }
            JsonNode allocations=mapper.valueToTree(ops.get("refund-funding")).path("allocations");
            for(int index=0;index<allocations.size();index++) {
                JsonNode allocation=allocations.get(index);
                String name=refundOperation(index);
                if(!ops.containsKey(name))return saveCommand(change,name,"payment","/api/v1/refunds",
                    Map.of("paymentIntentId",text(allocation,"paymentIntentId"),"amount",map(allocation.path("amount")),"reason","CHANGE_FARE_DIFFERENCE",
                        "businessCaseRef",id,"channelRef",map(allocation.path("channelRef"))),correlation);
                JsonNode returned=get("payment","/api/v1/refunds/"+ops.get(name).get("refundId"),correlation);
                if(!"SETTLED".equals(text(returned,"status")))return change;
            }
            if(allocations.isEmpty()) {
                JsonNode returned=get("payment","/api/v1/refunds/"+ops.get("difference-refund").get("refundId"),correlation);
                if(!"SETTLED".equals(text(returned,"status")))return change;
            }
        }
        if(!ops.containsKey("order-applied"))return saveCommand(change,"order-applied","journey-order","/api/v1/journey-orders/"+change.journeyOrderId()+"/apply-change",
            Map.of("caseId",id),correlation);
        return cases.completeChange(caseId,correlation);
    }

    private PostSalesCase completeExpiredTarget(PostSalesCase change,String correlation) {
        if(change.changeOperations().keySet().stream().anyMatch(name->!name.equals("target-expired") && !name.equals("expired-target-completed"))) {
            throw new DomainRuleViolation("expired target completion requires no replacement operations");
        }
        for(String ticket:change.scope().entitlementRefs()) {
            JsonNode original=get("entitlement-ticketing","/api/v1/entitlements/"+ticket,correlation);
            if(!change.journeyOrderId().equals(text(original,"journeyOrderId")) || !"ISSUED".equals(text(original,"status")))throw new DomainRuleViolation("expired target original ticket is unavailable");
        }
        if(!change.changeOperations().containsKey("expired-target-completed"))return cases.recordChangeOperation(change.caseId(),"expired-target-completed",Map.of("originalTicketsPreserved",true));
        return cases.failExpiredTarget(change.caseId(),correlation);
    }

    private PostSalesCase compensateCapacityFailure(PostSalesCase change,String correlation) {
        var ops=change.changeOperations();
        String caseId=PostSalesMapper.prefixed(change.caseId(),"psc-");
        if(ops.keySet().stream().anyMatch(name->name.startsWith("void:") || name.equals("order-applied") || name.startsWith("difference-refund"))) {
            throw new DomainRuleViolation("capacity compensation requires original commercial state to remain unchanged");
        }
        for(String ticket:change.scope().entitlementRefs()) {
            JsonNode original=get("entitlement-ticketing","/api/v1/entitlements/"+ticket,correlation);
            if(!change.journeyOrderId().equals(text(original,"journeyOrderId")) || !"ISSUED".equals(text(original,"status"))) {
                throw new DomainRuleViolation("original ticket is unavailable for capacity compensation");
            }
        }
        Set<String> replacements=new TreeSet<>();
        for(var entry:ops.entrySet())if(entry.getKey().startsWith("booking:"))replacements.add(String.valueOf(entry.getValue().get("segmentBookingId")));
        if(replacements.isEmpty() || !replacements.contains(String.valueOf(ops.get("capacity-rejected").get("segmentBookingId")))) {
            throw new DomainRuleViolation("capacity rejection is outside the recorded replacement reservations");
        }
        if(!ops.containsKey("capacity-replacement-tickets")) {
            var tickets=new LinkedHashMap<String,Object>();
            int offset=0;
            while(true) {
                JsonNode page=get("entitlement-ticketing","/api/v1/entitlements?journeyOrderId="+change.journeyOrderId()+"&limit=100&offset="+offset,correlation);
                if(!page.path("items").isArray() || !page.path("total").isIntegralNumber())throw new DomainRuleViolation("replacement ticket listing is incomplete");
                for(JsonNode ticket:page.path("items"))if(replacements.contains(text(ticket,"segmentBookingId"))) {
                    if(!change.journeyOrderId().equals(text(ticket,"journeyOrderId")))throw new DomainRuleViolation("replacement ticket order mismatch");
                    tickets.put(text(ticket,"entitlementId"),map(ticket));
                }
                offset+=page.path("items").size();
                if(offset>=page.path("total").asInt())break;
                if(page.path("items").isEmpty())throw new DomainRuleViolation("replacement ticket listing ended before total");
            }
            return cases.recordChangeOperation(change.caseId(),"capacity-replacement-tickets",tickets);
        }
        for(var entry:ops.get("capacity-replacement-tickets").entrySet()) {
            JsonNode ticket=get("entitlement-ticketing","/api/v1/entitlements/"+entry.getKey(),correlation);
            if(!replacements.contains(text(ticket,"segmentBookingId")) || !change.journeyOrderId().equals(text(ticket,"journeyOrderId")))throw new DomainRuleViolation("replacement ticket compensation scope mismatch");
            if(!"VOIDED".equals(text(ticket,"status")))return saveCommand(change,"void-replacement:"+entry.getKey(),"entitlement-ticketing",
                "/api/v1/entitlements/"+entry.getKey()+"/void",Map.of("reason","CHANGE","policy","NORMAL","businessCaseRef",caseId),correlation);
        }
        for(String bookingId:replacements) {
            JsonNode booking=get("booking-orchestration","/api/v1/internal/segment-bookings/"+bookingId,correlation);
            if(!change.journeyOrderId().equals(text(booking,"journeyOrderId")) || !("change:"+caseId).equals(text(booking,"bookingPurpose"))) {
                throw new DomainRuleViolation("capacity compensation booking identity mismatch");
            }
            if(!Set.of("CANCELLED","FAILED").contains(text(booking,"status")) && !ops.containsKey("cancel-replacement:"+bookingId))return saveCommand(change,"cancel-replacement:"+bookingId,"booking-orchestration",
                "/api/v1/internal/change-compensations/"+bookingId,Map.of("caseId",caseId),correlation);
            if(!Set.of("CANCELLED","FAILED").contains(text(booking,"status")))return change;
            String entitlement=booking.path("entitlementId").asText("");
            var issued=ops.get("ticket:"+bookingId);
            if(issued!=null) {
                String recorded=String.valueOf(issued.get("entitlementId"));
                if(!entitlement.isBlank() && !recorded.equals(entitlement))throw new DomainRuleViolation("replacement entitlement identity mismatch");
                entitlement=recorded;
            }
            if(!entitlement.isBlank()) {
                JsonNode ticket=get("entitlement-ticketing","/api/v1/entitlements/"+entitlement,correlation);
                if(!bookingId.equals(text(ticket,"segmentBookingId")) || !change.journeyOrderId().equals(text(ticket,"journeyOrderId")))throw new DomainRuleViolation("replacement ticket identity mismatch");
                if(!"VOIDED".equals(text(ticket,"status")))return saveCommand(change,"void-replacement:"+entitlement,"entitlement-ticketing",
                    "/api/v1/entitlements/"+entitlement+"/void",Map.of("reason","CHANGE","policy","NORMAL","businessCaseRef",caseId),correlation);
            }
            String holdId=booking.path("capacityHoldId").asText("");
            if(!holdId.isBlank()) {
                JsonNode hold=getPending("capacity-availability","/api/v1/capacity-holds/"+holdId,correlation);
                if(hold==null)return change;
                if(!bookingId.equals(text(hold,"segmentBookingRef")))throw new DomainRuleViolation("replacement Hold identity mismatch");
                if(!Set.of("RELEASED","EXPIRED","FAILED").contains(text(hold,"status")))return change;
            }
        }
        if(ops.containsKey("difference-payment")) {
            String paymentId=String.valueOf(ops.get("difference-payment").get("paymentIntentId"));
            JsonNode payment=get("payment","/api/v1/payment-intents/"+paymentId,correlation);
            JsonNode target=mapper.valueToTree(ops.get("target"));
            Money due=change.decision().amountSnapshot().extraChargeAmount();
            if(!caseId.equals(text(payment,"businessRef")) || !text(target,"accountId").equals(text(payment,"payerRef"))
                || !"change".equals(text(payment,"purpose")) || !due.equals(RefundFundingService.money(payment.path("amount"))))throw new DomainRuleViolation("compensation payment identity mismatch");
            Money captured=RefundFundingService.money(payment.path("capturedAmount")),refunded=RefundFundingService.money(payment.path("refundedAmount"));
            if(!captured.isZero()) {
                if(!captured.equals(due))throw new DomainRuleViolation("captured difference differs from approved compensation amount");
                if(!ops.containsKey("capacity-difference-refund")) {
                    if(!refunded.isZero())throw new DomainRuleViolation("difference was refunded outside recorded compensation");
                    return saveCommand(change,"capacity-difference-refund","payment","/api/v1/refunds",Map.of("paymentIntentId",paymentId,
                        "amount",PostSalesMapper.money(captured),"reason","CHANGE_CAPACITY_UNAVAILABLE","businessCaseRef",caseId,"channelRef",map(payment.path("channelRef"))),correlation);
                }
                JsonNode returned=get("payment","/api/v1/refunds/"+ops.get("capacity-difference-refund").get("refundId"),correlation);
                if(!caseId.equals(text(returned,"businessCaseRef")) || !paymentId.equals(text(returned,"paymentIntentId"))
                    || !captured.equals(RefundFundingService.money(returned.path("amount"))))throw new DomainRuleViolation("capacity compensation refund mismatch");
                if(!"SETTLED".equals(text(returned,"status")) || !refunded.equals(captured))return change;
            } else {
                if(!refunded.isZero())throw new DomainRuleViolation("uncaptured difference has refunded funds");
                if(!Set.of("EXPIRED","CANCELLED","FAILED").contains(text(payment,"status")))return saveCommand(change,"cancel-difference-payment","payment",
                    "/api/v1/payment-intents/"+paymentId+"/cancel",Map.of("reason","CHANGE_CAPACITY_UNAVAILABLE"),correlation);
            }
        }
        if(!ops.containsKey("capacity-compensation-completed"))return cases.recordChangeOperation(change.caseId(),"capacity-compensation-completed",
            Map.of("originalTicketsPreserved",true,"replacementBookings",replacements,"differencePaymentSettled",true));
        return cases.failCapacityChange(change.caseId(),correlation);
    }

    private PostSalesCase compensateUnpaidChange(PostSalesCase change,JsonNode payment,String correlation) {
        var ops=change.changeOperations();
        if(ops.keySet().stream().anyMatch(name->name.startsWith("ticket:") || name.startsWith("void:") || name.equals("order-applied"))) {
            throw new DomainRuleViolation("unpaid change compensation requires original tickets to remain unchanged");
        }
        String caseId=PostSalesMapper.prefixed(change.caseId(),"psc-");
        if(!ops.containsKey("payment-terminated"))return cases.recordChangeOperation(change.caseId(),"payment-terminated",map(payment));
        for(var operation:ops.entrySet())if(operation.getKey().startsWith("booking:")) {
            String bookingId=String.valueOf(operation.getValue().get("segmentBookingId"));
            String name="cancel-replacement:"+bookingId;
            if(!ops.containsKey(name))return saveCommand(change,name,"booking-orchestration","/api/v1/internal/change-reservations/"+bookingId+"/cancel",
                Map.of("caseId",caseId),correlation);
            JsonNode booking=get("booking-orchestration","/api/v1/internal/segment-bookings/"+bookingId,correlation);
            if(!"CANCELLED".equals(text(booking,"status")))return change;
            String holdId=text(booking,"capacityHoldId");
            JsonNode hold=getPending("capacity-availability","/api/v1/capacity-holds/"+holdId,correlation);
            if(hold==null || !"RELEASED".equals(text(hold,"status"))) {
                // A cancelled replacement whose Hold survived a transport loss would otherwise
                // block the compensation forever, so request the release that the cancellation
                // event should have caused.
                String releaseName="release-hold:"+bookingId;
                if(!ops.containsKey(releaseName))return saveCommand(change,releaseName,"capacity-availability",
                    "/api/v1/capacity-holds/"+holdId+"/release",Map.of(),correlation);
                return change;
            }
        }
        for(String ticket:change.scope().entitlementRefs()) {
            if(!"ISSUED".equals(text(get("entitlement-ticketing","/api/v1/entitlements/"+ticket,correlation),"status"))) {
                throw new DomainRuleViolation("original ticket is unavailable after unpaid change");
            }
        }
        if(!ops.containsKey("unpaid-compensation-completed"))return cases.recordChangeOperation(change.caseId(),"unpaid-compensation-completed",Map.of("originalTicketsPreserved",true));
        return cases.failUnpaidChange(change.caseId(),correlation);
    }

    public Map<String,Object> issueAuthorization(String caseId,String bookingId,String correlation) {
        PostSalesCase change=cases.get(caseId);
        if(change.caseType()!=PostSalesCaseType.CHANGE || change.status()!=PostSalesCaseStatus.EXECUTING)throw new DomainRuleViolation("change is not executing");
        var operations=change.changeOperations();
        if(operations.containsKey("capacity-rejected"))throw new DomainRuleViolation("change compensation prevents replacement issuance");
        var recorded=operations.get("confirmed:"+bookingId);
        if(recorded==null)throw new DomainRuleViolation("replacement booking is not authorized by this change");
        JsonNode booking=get("booking-orchestration","/api/v1/internal/segment-bookings/"+bookingId,correlation);
        if(!change.journeyOrderId().equals(text(booking,"journeyOrderId")))throw new DomainRuleViolation("replacement order mismatch");
        JsonNode target=mapper.valueToTree(operations.get("target"));
        List<JsonNode> payments=funding.changePayments(change,text(target,"accountId"),false,correlation);
        JsonNode order=get("journey-order","/api/v1/journey-orders/"+change.journeyOrderId(),correlation);
        if(!RefundFundingService.balance(payments).equals(RefundFundingService.money(order.path("monetarySummary").path("payableTotal"))))throw new DomainRuleViolation("available funds differ from the current order");
        String paymentId=payments.stream().filter(payment->!RefundFundingService.balance(List.of(payment)).isZero()).map(payment->text(payment,"paymentIntentId")).findFirst().orElseThrow(()->new DomainRuleViolation("change has no available payment"));
        Money due=change.decision().amountSnapshot().extraChargeAmount();
        if(!due.isZero()) {
            if(!operations.containsKey("difference-payment"))throw new DomainRuleViolation("difference payment is required");
            paymentId=String.valueOf(operations.get("difference-payment").get("paymentIntentId"));
            JsonNode payment=get("payment","/api/v1/payment-intents/"+paymentId,correlation);
            if(!"CAPTURED".equals(text(payment,"status")) || !PostSalesMapper.prefixed(change.caseId(),"psc-").equals(text(payment,"businessRef"))
                || payment.path("capturedAmount").path("minorUnits").asLong()!=due.toMinorUnits() || payment.path("refundedAmount").path("minorUnits").asLong()!=0)throw new DomainRuleViolation("difference payment is not captured for this change");
        }
        return Map.of("authorized",true,"caseId",PostSalesMapper.prefixed(change.caseId(),"psc-"),"journeyOrderId",change.journeyOrderId(),
            "segmentBookingId",bookingId,"segmentRef",text(booking,"segmentRef"),"travelerRef",text(booking,"travelerRef"),"paymentIntentId",paymentId);
    }

    public Map<String,Object> settlement(String caseId,String correlation) {
        PostSalesCase change=cases.get(caseId);
        if(change.caseType()!=PostSalesCaseType.CHANGE || (change.status()!=PostSalesCaseStatus.EXECUTING && change.status()!=PostSalesCaseStatus.APPLIED))throw new DomainRuleViolation("change settlement requires an approved case");
        JsonNode target=mapper.valueToTree(change.changeOperations().get("target"));
        List<JsonNode> payments=funding.changePayments(change,text(target,"accountId"),true,correlation);
        Set<String> ids=new HashSet<>();payments.forEach(payment->ids.add(text(payment,"paymentIntentId")));
        Money expected=change.decision().amountSnapshot().refundAmount(),returned=Money.zero(expected.currency());
        Set<String> refundIds=new HashSet<>();
        for(var op:change.changeOperations().entrySet())if(op.getKey().equals("difference-refund") || op.getKey().startsWith("difference-refund:")) {
            String refundId=String.valueOf(op.getValue().get("refundId"));
            if(!refundIds.add(refundId))throw new DomainRuleViolation("change refund is recorded more than once");
            JsonNode refund=get("payment","/api/v1/refunds/"+refundId,correlation);
            if(!"SETTLED".equals(text(refund,"status")) || !PostSalesMapper.prefixed(change.caseId(),"psc-").equals(text(refund,"businessCaseRef")) || !ids.contains(text(refund,"paymentIntentId")))throw new DomainRuleViolation("change refund settlement mismatch");
            returned=returned.add(RefundFundingService.money(refund.path("amount")));
        }
        if(!expected.equals(returned))throw new DomainRuleViolation("settled change refunds differ from the approved total");
        return Map.of("journeyOrderId",change.journeyOrderId(),"caseId",PostSalesMapper.prefixed(change.caseId(),"psc-"),"balance",PostSalesMapper.money(RefundFundingService.balance(payments)),"payments",payments);
    }
    private static String refundOperation(int index) {return index==0?"difference-refund":"difference-refund:"+index;}

    private PostSalesCase saveCommand(PostSalesCase change,String name,String service,String path,Map<String,Object> body,String correlation) {
        JsonNode result=client.post().uri(baseTemplate.formatted(service)+path).header("Idempotency-Key",key(change.caseId()+":"+name))
            .header("X-Correlation-Id","corr-"+key(change.caseId()+":correlation")).body(body).exchange((request,response)->{
                if(name.equals("difference-captured") && response.getStatusCode().value()==422) {
                    JsonNode rejection=mapper.readTree(response.getBody());
                    if("DOMAIN_RULE_VIOLATION".equals(rejection.path("code").asText()) && "payment intent is expired for new payment activity".equals(rejection.path("message").asText())) {
                        return mapper.valueToTree(Map.of("paymentExpired",true,"source",service,"code",text(rejection,"code"),"message",text(rejection,"message")));
                    }
                    throw new DomainRuleViolation("difference capture rejected: "+rejection);
                }
                if(name.startsWith("ticket:") && response.getStatusCode().value()==422) {
                    JsonNode rejection=mapper.readTree(response.getBody());
                    if("DOMAIN_RULE_VIOLATION".equals(rejection.path("code").asText()) && Set.of("no compatible seat exists and standing is not accepted","standing quota is exhausted").contains(rejection.path("message").asText())) {
                        return mapper.valueToTree(Map.of("capacityRejected",true,"segmentBookingId",name.substring("ticket:".length()),"source",service,"code",text(rejection,"code"),"message",text(rejection,"message")));
                    }
                    throw new DomainRuleViolation("change ticket rejected: "+rejection);
                }
                if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("change "+name+" returned "+response.getStatusCode()+": "+new String(response.getBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                return mapper.readTree(response.getBody());
            });
        String operation=result.path("capacityRejected").asBoolean()?"capacity-rejected":result.path("paymentExpired").asBoolean()?"difference-rejected":name;
        Map<String,Object> recorded=new java.util.LinkedHashMap<>(map(result));
        if(name.startsWith("booking:"))recorded.put("requestedAt",clock.instant().toString());
        return cases.recordChangeOperation(change.caseId(),operation,recorded);
    }

    private boolean reservationWaitExpired(Map<String,Object> operation,Map<String,Object> waiting) {
        Object requestedAt=operation==null?null:operation.get("requestedAt");
        if(requestedAt==null && waiting!=null)requestedAt=waiting.get("since");
        return reservationWaitExpired(requestedAt==null?null:String.valueOf(requestedAt), clock.instant());
    }

    private boolean releaseWaitExpired(Map<String,Object> waiting) {
        Object since=waiting==null?null:waiting.get("since");
        if(since==null)return false;
        return Instant.parse(String.valueOf(since)).plus(RELEASE_WAIT_LIMIT).isBefore(clock.instant());
    }

    /// Releases a hold whose booking is already cancelled, for the case where the release event
    /// capacity would have acted on was lost.
    private void releaseHold(PostSalesCase change,String bookingId,String holdId,String correlation) {
        client.post().uri(baseTemplate.formatted("capacity-availability")+"/api/v1/capacity-holds/"+holdId+"/release")
            .header("Idempotency-Key",key(change.caseId()+":release-cancelled-hold:"+bookingId))
            .header("X-Correlation-Id",correlation).retrieve().toBodilessEntity();
    }

    static boolean reservationWaitExpired(String requestedAt, Instant now) {
        if(requestedAt==null)return false;
        return Instant.parse(requestedAt).plus(RESERVATION_WAIT_LIMIT).isBefore(now);
    }
    private JsonNode get(String service,String path,String correlation) {
        return client.get().uri(baseTemplate.formatted(service)+path).header("X-Correlation-Id",correlation).exchange((request,response)->{
            if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("change reference lookup returned "+response.getStatusCode());
            return mapper.readTree(response.getBody());
        });
    }
    private JsonNode getPending(String service,String path,String correlation){
        return client.get().uri(baseTemplate.formatted(service)+path).header("X-Correlation-Id",correlation).exchange((request,response)->{
            if(response.getStatusCode().value()==404)return null;
            if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("change pending reference lookup returned "+response.getStatusCode());
            return mapper.readTree(response.getBody());
        });
    }
    private Map<String,Object> map(JsonNode node) {return mapper.convertValue(node,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
    private static String text(JsonNode node,String key) {String value=node.path(key).asText();if(value.isBlank())throw new DomainRuleViolation("change response missing "+key);return value;}
    public static String key(String material) {
        try {byte[] bytes=Arrays.copyOf(java.security.MessageDigest.getInstance("SHA-256").digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8)),16);
            bytes[6]=(byte)((bytes[6]&15)|112);bytes[8]=(byte)((bytes[8]&63)|128);var buffer=java.nio.ByteBuffer.wrap(bytes);return new UUID(buffer.getLong(),buffer.getLong()).toString();
        }catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
}
