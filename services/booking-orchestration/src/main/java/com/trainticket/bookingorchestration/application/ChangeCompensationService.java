package com.trainticket.bookingorchestration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.trainticket.bookingorchestration.domain.SegmentBookingStatus;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChangeCompensationService {
    private final SegmentBookingRepository bookings;
    private final BookingSagaRepository sagas;
    private final BookingOrchestrationService service;
    private final EventPublisher publisher;
    private final Clock clock;

    public ChangeCompensationService(SegmentBookingRepository bookings,BookingSagaRepository sagas,
        BookingOrchestrationService service,EventPublisher publisher,Clock clock) {
        this.bookings=bookings;this.sagas=sagas;this.service=service;this.publisher=publisher;this.clock=clock;
    }

    @Transactional
    public BookingOrchestrationService.SegmentBookingDetail cancel(String bookingId,String caseId,JsonNode change,String correlation) {
        if(caseId==null || !caseId.equals(change.path("caseId").asText()) || !"CHANGE".equals(change.path("caseType").asText())
            || !"APPROVED".equals(change.path("status").asText()) || !change.path("changeOperations").has("capacity-rejected"))fail("executing capacity compensation is required");
        boolean recorded=false;
        var operations=change.path("changeOperations").fields();
        while(operations.hasNext()) {
            var entry=operations.next();
            if(entry.getKey().startsWith("void:") || entry.getKey().equals("order-applied"))fail("original ticket modification prevents compensation");
            if(entry.getKey().startsWith("booking:") && bookingId.equals(entry.getValue().path("segmentBookingId").asText()))recorded=true;
        }
        if(!recorded)fail("booking is absent from the compensation case");
        sagas.lockReference(caseId);
        var record=bookings.findById(bookingId).orElseThrow(()->new BookingOrchestrationService.NotFoundException("replacement booking not found"));
        var booking=record.booking();
        if(!("change:"+caseId).equals(booking.bookingPurpose()) || !booking.journeyOrderId().equals(change.path("journeyOrderId").asText()))fail("replacement booking identity mismatch");
        if(booking.status()==SegmentBookingStatus.CANCELLED || booking.status()==SegmentBookingStatus.FAILED)return service.getSegmentBooking(bookingId).orElseThrow();
        if(booking.entitlementId().isPresent())fail("issued replacement requires ticket void confirmation");
        booking.requestCancellation("CHANGE_CAPACITY_UNAVAILABLE");
        booking.markCancelled("CHANGE_CAPACITY_UNAVAILABLE");
        bookings.save(booking,record.sagaId());
        publisher.publish(new EventEnvelope(PrefixedIds.newEventId(),"SegmentBookingCancelled",clock.instant(),correlation,PrefixedIds.newCommandId(),
            "booking-orchestration",1,Map.of("segmentBookingId",bookingId,"journeyOrderId",booking.journeyOrderId(),"segmentRef",booking.segmentRef(),
                "travelerRef",booking.travelerRef(),"reason","CHANGE_CAPACITY_UNAVAILABLE","postSalesCaseId",caseId)));
        return service.getSegmentBooking(bookingId).orElseThrow();
    }

    private static void fail(String message) { throw new BookingOrchestrationService.PreconditionFailedException(message,null); }
}
