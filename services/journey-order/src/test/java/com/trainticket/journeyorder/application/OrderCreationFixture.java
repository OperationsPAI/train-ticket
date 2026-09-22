package com.trainticket.journeyorder.application;

import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.application.port.out.ResolvedOrderOffer;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.journeyorder.domain.*;
import java.time.Instant;
import java.util.List;

final class OrderCreationFixture {
    private OrderCreationFixture() {}

    static JourneyOrderResult createOrder(OrderManagementService service, JourneyOrderRequest request, String key, String correlation) {
        return service.createOrderFromOffer(request,key,correlation,snapshot(request,10_000L*request.travelerRefs().size()));
    }

    static ResolvedOrderOffer snapshot(JourneyOrderRequest request,long total) {
        Instant quoted=Instant.parse("2026-07-01T00:00:00Z");
        Instant departure=Instant.parse("2026-10-20T08:00:00Z");
        var travelers=request.travelerRefs().stream().map(id->new TravelerRef(id,"ADULT")).toList();
        var segments=request.segmentRefs().stream().map(id->new SegmentOrderSnapshot(id,"station-a","station-b","TRAIN",departure,departure.plusSeconds(3600),"")).toList();
        var items=new java.util.ArrayList<OrderItem>();
        for(int index=0;index<travelers.size();index++) {
            String id="fare-"+request.offerId()+"-"+index;
            long amount=total/travelers.size()+(index==0?total%travelers.size():0);
            items.add(new OrderItem(id,OrderItemType.SEGMENT_FARE,"fare",Money.fromMinorUnits(amount,"CNY"),"quote-item",
                List.of(new OrderLineBinding(id,travelers.get(index).travelerId(),segments.getFirst().segmentRef(),""))));
        }
        return new ResolvedOrderOffer(request.accountId(),"WEB",new OfferSnapshotRef(request.offerId(),request.offerVersion(),quoted,departure,"price-ref","rule-ref"),travelers,segments,items,"TRAIN",List.of());
    }
}
