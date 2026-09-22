package com.trainticket.payment.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.domain.Refund;
import com.trainticket.payment.domain.ports.RefundRepository;
import com.trainticket.platformkit.persistence.SnapshotRepository;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresRefundRepository implements RefundRepository {
    private final ObjectMapper objectMapper;
    private final SnapshotRepository<JacksonPaymentJson.RefundSnapshot> snapshots;
    private final JdbcTemplate jdbc;

    @Autowired
    public PostgresRefundRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.snapshots = new SnapshotRepository<>(dataSource, objectMapper, "refund_snapshots", JacksonPaymentJson.RefundSnapshot.class);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<Refund> findById(String refundId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            jdbc.query("SELECT id FROM refund_snapshots WHERE id = ? FOR UPDATE",
                rs -> { return rs.next(); }, refundId);
        }
        return snapshots.get(refundId)
            .map(snapshot -> JacksonPaymentJson.toRefund(snapshot.data(), objectMapper).withVersion(snapshot.version()));
    }

    @Override
    public List<Refund> findByBusinessCaseRef(String businessCaseRef) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { return rs.next(); }, "payment-refund-case:" + businessCaseRef);
        }
        List<String> ids = jdbc.query(
            "SELECT id FROM refund_snapshots WHERE data->>'sourceCaseRef'=? ORDER BY id",
            (rs, row) -> rs.getString("id"), businessCaseRef);
        return ids.stream().map(id -> findById(id).orElseThrow()).toList();
    }

    @Override
    public void save(Refund refund) {
        long newVersion = snapshots.save(refund.refundId(), refund.version(), JacksonPaymentJson.refundSnapshot(refund, objectMapper));
        refund.withVersion(newVersion);
    }
}
