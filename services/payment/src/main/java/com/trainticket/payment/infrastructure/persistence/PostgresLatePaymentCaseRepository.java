package com.trainticket.payment.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.domain.LatePaymentCase;
import com.trainticket.payment.domain.ports.LatePaymentCaseRepository;
import com.trainticket.platformkit.persistence.SnapshotRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresLatePaymentCaseRepository implements LatePaymentCaseRepository {
    private final ObjectMapper objectMapper;
    private final SnapshotRepository<JacksonPaymentJson.LatePaymentCaseSnapshot> snapshots;

    @Autowired
    public PostgresLatePaymentCaseRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this(objectMapper, new SnapshotRepository<>(dataSource, objectMapper, "late_payment_case_snapshots", JacksonPaymentJson.LatePaymentCaseSnapshot.class));
    }

    PostgresLatePaymentCaseRepository(ObjectMapper objectMapper, SnapshotRepository<JacksonPaymentJson.LatePaymentCaseSnapshot> snapshots) {
        this.objectMapper = objectMapper;
        this.snapshots = snapshots;
    }

    @Override
    public Optional<LatePaymentCase> findById(String latePaymentCaseId) {
        return snapshots.get(latePaymentCaseId)
            .map(snapshot -> JacksonPaymentJson.toLatePaymentCase(snapshot.data()).withVersion(snapshot.version()));
    }

    @Override
    public void save(LatePaymentCase latePaymentCase) {
        long newVersion = snapshots.save(
            latePaymentCase.latePaymentCaseId(),
            latePaymentCase.version(),
            JacksonPaymentJson.latePaymentCaseSnapshot(latePaymentCase, objectMapper));
        latePaymentCase.withVersion(newVersion);
    }
}
