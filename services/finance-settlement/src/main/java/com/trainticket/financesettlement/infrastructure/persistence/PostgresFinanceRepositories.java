package com.trainticket.financesettlement.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.financesettlement.application.FeeAccrualRepository;
import com.trainticket.financesettlement.application.InvoiceRepository;
import com.trainticket.financesettlement.application.ReconciliationBatchRepository;
import com.trainticket.financesettlement.application.SupplierSettlementRepository;
import com.trainticket.financesettlement.application.ReconciliationCaseRepository;
import com.trainticket.financesettlement.application.RevenueRecognitionRepository;
import com.trainticket.financesettlement.domain.FeeAccrual;
import com.trainticket.financesettlement.domain.Invoice;
import com.trainticket.financesettlement.domain.ReconciliationBatch;
import com.trainticket.financesettlement.domain.ReconciliationCase;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import com.trainticket.financesettlement.domain.SupplierSettlement;
import com.trainticket.platformkit.persistence.SnapshotRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

final class PostgresFinanceRepositories {
    private PostgresFinanceRepositories() {}

    @Repository
    @Primary
    @ConditionalOnBean(DataSource.class)
    static class RevenueRecognitions implements RevenueRecognitionRepository {
        private final ObjectMapper mapper;
        private final SnapshotRepository<JacksonFinanceSettlementJson.RevenueRecognitionSnapshot> snapshots;
        private final JdbcTemplate jdbc;

        RevenueRecognitions(DataSource ds, ObjectMapper mapper) {
            this.mapper = mapper;
            this.snapshots = new SnapshotRepository<>(
                ds,
                mapper,
                "revenue_recognition_snapshots",
                JacksonFinanceSettlementJson.RevenueRecognitionSnapshot.class
            );
            this.jdbc = new JdbcTemplate(ds);
        }

        @Override
        public Optional<RevenueRecognition> findById(String id) {
            return snapshots.get(id)
                .map(snapshot -> JacksonFinanceSettlementJson.toRevenue(snapshot.data(), mapper).withVersion(snapshot.version()));
        }

        @Override
        public List<RevenueRecognition> findByOrderId(String orderId) {
            return jdbc.query(
                "SELECT id FROM revenue_recognition_snapshots WHERE data->>'orderId' = ? ORDER BY (data->>'recognizedAt')::timestamptz",
                (rs, rowNum) -> findById(rs.getString("id")).orElseThrow(),
                orderId
            );
        }

        @Override
        public List<RevenueRecognition> findBySupplierAndPeriod(String supplierId, LocalDate startDate, LocalDate endDate) {
            return jdbc.query(
                """
                    SELECT id FROM revenue_recognition_snapshots
                    WHERE data->>'orderItemId' = ?
                      AND (data->>'recognizedAt')::timestamptz >= ?::timestamptz
                      AND (data->>'recognizedAt')::timestamptz < ?::timestamptz
                    ORDER BY (data->>'recognizedAt')::timestamptz, id
                    """,
                (rs, rowNum) -> findById(rs.getString("id")).orElseThrow(),
                supplierId,
                startDate.atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
                endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString()
            );
        }

        @Override
        public void save(RevenueRecognition recognition) {
            long newVersion = snapshots.save(
                recognition.revenueRecognitionId(),
                recognition.version(),
                JacksonFinanceSettlementJson.revenueSnapshot(recognition, mapper)
            );
            recognition.withVersion(newVersion);
        }
    }

    @Repository
    @Primary
    @ConditionalOnBean(DataSource.class)
    static class Cases implements ReconciliationCaseRepository {
        private final ObjectMapper mapper;
        private final SnapshotRepository<JacksonFinanceSettlementJson.ReconciliationCaseSnapshot> snapshots;
        private final JdbcTemplate jdbc;

        Cases(DataSource ds, ObjectMapper mapper) {
            this.mapper = mapper;
            this.snapshots = new SnapshotRepository<>(
                ds,
                mapper,
                "reconciliation_case_snapshots",
                JacksonFinanceSettlementJson.ReconciliationCaseSnapshot.class
            );
            this.jdbc = new JdbcTemplate(ds);
        }

        @Override
        public Optional<ReconciliationCase> findById(String id) {
            return snapshots.get(id)
                .map(snapshot -> JacksonFinanceSettlementJson.toCase(snapshot.data(), mapper).withVersion(snapshot.version()));
        }

        @Override
        public List<ReconciliationCase> find(String orderId, int limit, int offset) {
            String sql;
            Object[] args;
            if (orderId == null || orderId.isBlank()) {
                sql = "SELECT id FROM reconciliation_case_snapshots ORDER BY (data->>'openedAt')::timestamptz DESC LIMIT ? OFFSET ?";
                args = new Object[] {limit, offset};
            } else {
                sql = "SELECT id FROM reconciliation_case_snapshots WHERE data->>'orderId' = ? ORDER BY (data->>'openedAt')::timestamptz DESC LIMIT ? OFFSET ?";
                args = new Object[] {orderId, limit, offset};
            }
            return jdbc.query(sql, (rs, rowNum) -> findById(rs.getString("id")).orElseThrow(), args);
        }

        @Override
        public long count(String orderId) {
            Long value = orderId == null || orderId.isBlank()
                ? jdbc.queryForObject("SELECT count(*) FROM reconciliation_case_snapshots", Long.class)
                : jdbc.queryForObject(
                    "SELECT count(*) FROM reconciliation_case_snapshots WHERE data->>'orderId' = ?",
                    Long.class,
                    orderId
                );
            return value == null ? 0 : value;
        }

        @Override
        public void save(ReconciliationCase c) {
            long newVersion = snapshots.save(
                c.reconciliationCaseId(),
                c.version(),
                JacksonFinanceSettlementJson.caseSnapshot(c, mapper)
            );
            c.withVersion(newVersion);
        }
    }

    @Repository
    @Primary
    @ConditionalOnBean(DataSource.class)
    static class Batches implements ReconciliationBatchRepository {
        private final ObjectMapper mapper;
        private final SnapshotRepository<JacksonFinanceSettlementJson.ReconciliationBatchSnapshot> snapshots;
        private final JdbcTemplate jdbc;

        Batches(DataSource ds, ObjectMapper mapper) {
            this.mapper = mapper;
            this.snapshots = new SnapshotRepository<>(ds, mapper, "reconciliation_batch_snapshots", JacksonFinanceSettlementJson.ReconciliationBatchSnapshot.class);
            this.jdbc = new JdbcTemplate(ds);
        }

        @Override
        public Optional<ReconciliationBatch> findById(String id) {
            return snapshots.get(id).map(snapshot -> JacksonFinanceSettlementJson.toBatch(snapshot.data(), mapper).withVersion(snapshot.version()));
        }

        @Override
        public Optional<ReconciliationBatch> findBySettlementDate(LocalDate settlementDate) {
            return jdbc.query(
                "SELECT id FROM reconciliation_batch_snapshots WHERE data->>'settlementDate' = ? LIMIT 1",
                rs -> rs.next() ? findById(rs.getString("id")) : Optional.empty(),
                settlementDate.toString()
            );
        }

        @Override
        public void save(ReconciliationBatch batch) {
            long newVersion = snapshots.save(batch.batchId(), batch.version(), JacksonFinanceSettlementJson.batchSnapshot(batch, mapper));
            batch.withVersion(newVersion);
        }
    }

    @Repository
    @Primary
    @ConditionalOnBean(DataSource.class)
    static class Suppliers implements SupplierSettlementRepository {
        private final ObjectMapper mapper;
        private final SnapshotRepository<JacksonFinanceSettlementJson.SupplierSettlementSnapshot> snapshots;
        private final JdbcTemplate jdbc;

        Suppliers(DataSource ds, ObjectMapper mapper) {
            this.mapper = mapper;
            this.snapshots = new SnapshotRepository<>(ds, mapper, "supplier_settlement_snapshots", JacksonFinanceSettlementJson.SupplierSettlementSnapshot.class);
            this.jdbc = new JdbcTemplate(ds);
        }

        @Override
        public Optional<SupplierSettlement> findById(String id) {
            return snapshots.get(id).map(snapshot -> JacksonFinanceSettlementJson.toSupplier(snapshot.data(), mapper).withVersion(snapshot.version()));
        }

        @Override
        public Optional<SupplierSettlement> findBySupplierAndPeriod(String supplierId, LocalDate startDate, LocalDate endDate) {
            return jdbc.query(
                "SELECT id FROM supplier_settlement_snapshots WHERE data->>'supplierId' = ? AND data->>'startDate' = ? AND data->>'endDate' = ? LIMIT 1",
                rs -> rs.next() ? findById(rs.getString("id")) : Optional.empty(),
                supplierId, startDate.toString(), endDate.toString()
            );
        }

        @Override
        public void save(SupplierSettlement settlement) {
            long newVersion = snapshots.save(settlement.supplierSettlementId(), settlement.version(), JacksonFinanceSettlementJson.supplierSnapshot(settlement, mapper));
            settlement.withVersion(newVersion);
        }
    }

    @Repository
    @Primary
    @ConditionalOnBean(DataSource.class)
    static class FeeAccruals implements FeeAccrualRepository {
        private final ObjectMapper mapper;
        private final SnapshotRepository<JacksonFinanceSettlementJson.FeeAccrualSnapshot> snapshots;
        private final JdbcTemplate jdbc;

        FeeAccruals(DataSource ds, ObjectMapper mapper) {
            this.mapper = mapper;
            this.snapshots = new SnapshotRepository<>(ds, mapper, "fee_accrual_snapshots", JacksonFinanceSettlementJson.FeeAccrualSnapshot.class);
            this.jdbc = new JdbcTemplate(ds);
        }

        @Override
        public Optional<FeeAccrual> findById(String id) {
            return snapshots.get(id).map(snapshot -> JacksonFinanceSettlementJson.toFee(snapshot.data(), mapper).withVersion(snapshot.version()));
        }

        @Override
        public List<FeeAccrual> findByOrderId(String orderId) {
            return jdbc.query(
                "SELECT id FROM fee_accrual_snapshots WHERE data->>'orderId' = ? ORDER BY id",
                (rs, rowNum) -> findById(rs.getString("id")).orElseThrow(),
                orderId
            );
        }

        @Override
        public void save(FeeAccrual accrual) {
            long newVersion = snapshots.save(accrual.feeAccrualId(), accrual.version(), JacksonFinanceSettlementJson.feeSnapshot(accrual, mapper));
            accrual.withVersion(newVersion);
        }
    }

    @Repository
    @Primary
    @ConditionalOnBean(DataSource.class)
    static class Invoices implements InvoiceRepository {
        private final ObjectMapper mapper;
        private final SnapshotRepository<JacksonFinanceSettlementJson.InvoiceSnapshot> snapshots;
        private final JdbcTemplate jdbc;

        Invoices(DataSource ds, ObjectMapper mapper) {
            this.mapper = mapper;
            this.snapshots = new SnapshotRepository<>(
                ds,
                mapper,
                "invoice_snapshots",
                JacksonFinanceSettlementJson.InvoiceSnapshot.class
            );
            this.jdbc = new JdbcTemplate(ds);
        }

        @Override
        public Optional<Invoice> findById(String id) {
            return snapshots.get(id)
                .map(snapshot -> JacksonFinanceSettlementJson.toInvoice(snapshot.data(), mapper).withVersion(snapshot.version()));
        }

        @Override
        public Optional<Invoice> findByOrderId(String orderId) {
            return jdbc.query(
                "SELECT id FROM invoice_snapshots WHERE data->>'orderId' = ? LIMIT 1",
                rs -> rs.next() ? findById(rs.getString("id")) : Optional.empty(),
                orderId
            );
        }

        @Override
        public void save(Invoice invoice) {
            long newVersion = snapshots.save(
                invoice.invoiceId(),
                invoice.version(),
                JacksonFinanceSettlementJson.invoiceSnapshot(invoice, mapper)
            );
            invoice.withVersion(newVersion);
        }
    }
}
