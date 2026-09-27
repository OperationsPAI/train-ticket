package com.trainticket.groupbooking.application;

/**
 * Whether service-plan knows a segment.
 *
 * A group booking's `segmentRefs` are the segments the group is for, and they
 * are stored and priced as given. Measured at revision 493: the references the
 * workload sent, `seg-group-<uuid8>`, answered 404 from service-plan while the
 * booking was stored and quoted, and no member, confirmation or cancellation
 * path had ever run (`group_members` held 0 rows across 84,720 bookings).
 */
public interface SegmentRefResolver {
    boolean exists(String segmentRef);
}
