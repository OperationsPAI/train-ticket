package com.trainticket.postsales.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.application.CompensationMismatchCheckpoint;
import com.trainticket.postsales.application.CompensationMismatchStore;
import com.trainticket.postsales.application.PostSalesMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PostgresCompensationMismatchStore implements CompensationMismatchStore {
    private final JdbcOperations jdbc;
    private final ObjectMapper mapper;

    public PostgresCompensationMismatchStore(DataSource dataSource, ObjectMapper mapper) {
        this(new JdbcTemplate(dataSource), mapper);
    }

    public PostgresCompensationMismatchStore(JdbcOperations jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override public Optional<CompensationMismatchCheckpoint> find(String caseId) {
        var rows = jdbc.query("SELECT * FROM compensation_mismatch_checkpoints WHERE case_id=?",
            (rs, row) -> checkpoint(rs), PostSalesMapper.stripCasePrefix(caseId));
        return rows.stream().findFirst();
    }

    @Override public CompensationMismatchCheckpoint record(CompensationMismatchCheckpoint checkpoint) {
        jdbc.update("""
            INSERT INTO compensation_mismatch_checkpoints(
              case_id,journey_order_id,case_status,mismatch_code,reason,
              approved_currency,approved_minor_units,refund_evidence,wallet_evidence,
              rights_evidence,operation_evidence,correlation_id,detected_at)
            VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?)
            ON CONFLICT(case_id) DO NOTHING
            """, checkpoint.caseId(), checkpoint.journeyOrderId(), checkpoint.caseStatus(),
            checkpoint.mismatchCode(), checkpoint.reason(), checkpoint.approvedCurrency(),
            checkpoint.approvedMinorUnits(), json(checkpoint.refundEvidence()), json(checkpoint.walletEvidence()),
            json(checkpoint.rightsEvidence()), json(checkpoint.operationEvidence()), checkpoint.correlationId(),
            java.sql.Timestamp.from(checkpoint.detectedAt()));
        return find(checkpoint.caseId()).orElseThrow(() -> new IllegalStateException("compensation mismatch checkpoint was not persisted"));
    }

    private CompensationMismatchCheckpoint checkpoint(ResultSet rs) throws SQLException {
        return new CompensationMismatchCheckpoint(
            rs.getString("case_id"), rs.getString("journey_order_id"), rs.getString("case_status"),
            rs.getString("mismatch_code"), rs.getString("reason"), rs.getString("approved_currency"),
            rs.getLong("approved_minor_units"), json(rs.getString("refund_evidence")),
            json(rs.getString("wallet_evidence")), json(rs.getString("rights_evidence")),
            json(rs.getString("operation_evidence")), rs.getString("correlation_id"),
            rs.getTimestamp("detected_at").toInstant());
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("checkpoint evidence is not JSON", error); }
    }

    private JsonNode json(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("checkpoint evidence is invalid JSON", error); }
    }
}
