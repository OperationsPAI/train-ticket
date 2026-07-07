package com.trainticket.travelerprofile.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.persistence.SnapshotRepository;
import com.trainticket.travelerprofile.application.TravelerState;
import com.trainticket.travelerprofile.application.TravelerProfileStore;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresTravelerProfileStore implements TravelerProfileStore {
    private final ObjectMapper objectMapper;
    private final SnapshotRepository<TravelerJson.TravelerSnapshot> snapshots;

    public PostgresTravelerProfileStore(DataSource dataSource, ObjectMapper objectMapper) {
        this(objectMapper, new SnapshotRepository<>(dataSource, objectMapper, "traveler_profile_snapshots", TravelerJson.TravelerSnapshot.class));
    }

    PostgresTravelerProfileStore(ObjectMapper objectMapper, SnapshotRepository<TravelerJson.TravelerSnapshot> snapshots) {
        this.objectMapper = objectMapper;
        this.snapshots = snapshots;
    }

    @Override
    public Optional<TravelerState> findByTravelerId(String travelerId) {
        String id = travelerId != null && travelerId.startsWith("tvl-") ? travelerId.substring(4) : travelerId;
        return snapshots.get(id).map(snapshot -> {
            TravelerState state = TravelerJson.toState(snapshot.data(), objectMapper);
            state.aggregate().withVersion(snapshot.version());
            return state;
        });
    }

    @Override
    public void save(TravelerState state) {
        long version = snapshots.save(state.aggregate().profileId(), state.aggregate().version(), TravelerJson.snapshot(state, objectMapper));
        state.aggregate().withVersion(version);
    }
}
