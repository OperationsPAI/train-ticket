package com.trainticket.postsales.application;

import java.util.Optional;

public interface PostSalesExternalEventProjectionStore {
    void saveAncillary(AncillaryPostSalesProjection projection);

    Optional<AncillaryPostSalesProjection> findAncillaryByItemId(String ancillaryOrderItemId);

    void saveDispatch(DispatchPostSalesProjection projection);

    Optional<DispatchPostSalesProjection> findDispatchByRideRequestId(String rideRequestId);
}
