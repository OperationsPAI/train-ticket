package com.trainticket.payment.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.domain.Refund;
import com.trainticket.payment.domain.ports.RefundRepository;
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
public class PostgresRefundRepository implements RefundRepository {
    private final ObjectMapper objectMapper;
    private final SnapshotRepository<JacksonPaymentJson.RefundSnapshot> snapshots;

    @Autowired
    public PostgresRefundRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this(objectMapper, new SnapshotRepository<>(dataSource, objectMapper, "refund_snapshots", JacksonPaymentJson.RefundSnapshot.class));
    }

    PostgresRefundRepository(ObjectMapper objectMapper, SnapshotRepository<JacksonPaymentJson.RefundSnapshot> snapshots) {
        this.objectMapper = objectMapper;
        this.snapshots = snapshots;
    }

    @Override
    public Optional<Refund> findById(String refundId) {
        return snapshots.get(refundId)
            .map(snapshot -> JacksonPaymentJson.toRefund(snapshot.data(), objectMapper).withVersion(snapshot.version()));
    }

    @Override
    public void save(Refund refund) {
        long newVersion = snapshots.save(refund.refundId(), refund.version(), JacksonPaymentJson.refundSnapshot(refund, objectMapper));
        refund.withVersion(newVersion);
    }
}
