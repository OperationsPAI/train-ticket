package com.trainticket.postsales.application;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryPostSalesExternalEventProjectionStore implements PostSalesExternalEventProjectionStore {
    private final ConcurrentMap<String, AncillaryPostSalesProjection> ancillaryByItemId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DispatchPostSalesProjection> dispatchByRideRequestId = new ConcurrentHashMap<>();

    @Override
    public void saveAncillary(AncillaryPostSalesProjection projection) {
        ancillaryByItemId.put(projection.ancillaryOrderItemId(), projection);
    }

    @Override
    public Optional<AncillaryPostSalesProjection> findAncillaryByItemId(String ancillaryOrderItemId) {
        return Optional.ofNullable(ancillaryByItemId.get(ancillaryOrderItemId));
    }

    @Override
    public void saveDispatch(DispatchPostSalesProjection projection) {
        dispatchByRideRequestId.put(projection.rideRequestId(), projection);
    }

    @Override
    public Optional<DispatchPostSalesProjection> findDispatchByRideRequestId(String rideRequestId) {
        return Optional.ofNullable(dispatchByRideRequestId.get(rideRequestId));
    }
}
