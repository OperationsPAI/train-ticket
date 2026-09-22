package com.trainticket.journeyorder.application;

import static org.junit.jupiter.api.Assertions.*;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.service.InMemoryJourneyOrderStateRepository;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderManagementServiceOfferPricingTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"),ZoneOffset.UTC);

    @Test
    void orderPreservesResolvedPricesAndSchedule() {
        var repository=new InMemoryJourneyOrderStateRepository();
        var service=new OrderManagementService(event->{},CLOCK,repository);
        var request=new JourneyOrderRequest("acc-1","off-1",1,List.of("tvl-1","tvl-2","tvl-3"),List.of("seg-1"));
        var resolved=OrderCreationFixture.snapshot(request,10001);
        var result=service.createOrderFromOffer(request,"idem-priced","corr-priced",resolved);
        var order=repository.findOrder(result.orderId()).orElseThrow().order();
        assertEquals(10001,result.monetarySummary().payableTotal());
        assertEquals(resolved.segments(),order.segments());
        assertEquals(resolved.travelers(),order.travelers());
        assertEquals(resolved.offer(),order.offerSnapshot());
    }

    @Test
    void creationWithoutAnAuthoritativeOfferFails() {
        var service=new OrderManagementService(event->{},CLOCK);
        var request=new JourneyOrderRequest("acc-1","off-missing",1,List.of("tvl-1"),List.of("seg-1"));
        assertThrows(IllegalStateException.class,()->service.createOrder(request,"idem-missing","corr-missing"));
        assertEquals(0,service.listOrders(null,null,20,0).total());
    }
}
