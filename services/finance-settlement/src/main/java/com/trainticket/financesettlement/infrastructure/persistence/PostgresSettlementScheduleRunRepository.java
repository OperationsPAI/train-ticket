package com.trainticket.financesettlement.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.financesettlement.application.SettlementScheduleRun;
import com.trainticket.financesettlement.application.SettlementScheduleRunRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresSettlementScheduleRunRepository implements SettlementScheduleRunRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresSettlementScheduleRunRepository(DataSource dataSource, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mapper = mapper;
    }

    @Override
    public Optional<SettlementScheduleRun> acquire(String jobKey, String jobType, LocalDate startDate, LocalDate endDate,
                                                   String owner, Instant now, Duration leaseDuration) {
        return jdbc.query(
            """
            INSERT INTO settlement_schedule_runs(
              job_key,job_type,start_date,end_date,status,lease_owner,lease_until,attempts,result_refs,failure,started_at,completed_at,updated_at)
            VALUES(?,?,?,?,'RUNNING',?,?,1,'[]'::jsonb,NULL,?,NULL,?)
            ON CONFLICT(job_key) DO UPDATE SET
              status='RUNNING', lease_owner=EXCLUDED.lease_owner, lease_until=EXCLUDED.lease_until,
              attempts=settlement_schedule_runs.attempts+1, result_refs='[]'::jsonb,
              failure=NULL, completed_at=NULL, updated_at=EXCLUDED.updated_at
            WHERE settlement_schedule_runs.status <> 'SUCCEEDED'
              AND settlement_schedule_runs.lease_until <= EXCLUDED.updated_at
            RETURNING job_key,job_type,start_date,end_date,status,lease_owner,lease_until,attempts,
                      result_refs::text,failure,started_at,completed_at,updated_at
            """,
            rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(),
            jobKey, jobType, Date.valueOf(startDate), Date.valueOf(endDate), owner,
            Timestamp.from(now.plus(leaseDuration)), Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Override
    public void complete(String jobKey, String owner, List<String> resultRefs, Instant completedAt) {
        int changed = jdbc.update(
            """
            UPDATE settlement_schedule_runs
            SET status='SUCCEEDED', result_refs=?::jsonb, failure=NULL, completed_at=?, lease_until=?, updated_at=?
            WHERE job_key=? AND status='RUNNING' AND lease_owner=?
            """,
            json(resultRefs), Timestamp.from(completedAt), Timestamp.from(completedAt), Timestamp.from(completedAt), jobKey, owner
        );
        requireOwned(changed);
    }

    @Override
    public void fail(String jobKey, String owner, String failure, Instant failedAt) {
        int changed = jdbc.update(
            """
            UPDATE settlement_schedule_runs
            SET status='FAILED', failure=?, completed_at=?, lease_until=?, updated_at=?
            WHERE job_key=? AND status='RUNNING' AND lease_owner=?
            """,
            failure, Timestamp.from(failedAt), Timestamp.from(failedAt), Timestamp.from(failedAt), jobKey, owner
        );
        requireOwned(changed);
    }

    @Override
    public Optional<SettlementScheduleRun> findByKey(String jobKey) {
        return jdbc.query(
            "SELECT job_key,job_type,start_date,end_date,status,lease_owner,lease_until,attempts,result_refs::text,failure,started_at,completed_at,updated_at FROM settlement_schedule_runs WHERE job_key=?",
            rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), jobKey
        );
    }

    @Override
    public List<SettlementScheduleRun> find(int limit, int offset) {
        return jdbc.query(
            "SELECT job_key,job_type,start_date,end_date,status,lease_owner,lease_until,attempts,result_refs::text,failure,started_at,completed_at,updated_at FROM settlement_schedule_runs ORDER BY updated_at DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> map(rs), limit, offset
        );
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM settlement_schedule_runs", Long.class);
        return count == null ? 0 : count;
    }

    private SettlementScheduleRun map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            Timestamp completed = rs.getTimestamp("completed_at");
            return new SettlementScheduleRun(
                rs.getString("job_key"), rs.getString("job_type"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getString("status"), rs.getString("lease_owner"),
                rs.getTimestamp("lease_until").toInstant(), rs.getInt("attempts"),
                mapper.readValue(rs.getString("result_refs"), STRING_LIST), rs.getString("failure"),
                rs.getTimestamp("started_at").toInstant(), completed == null ? null : completed.toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            );
        } catch (JsonProcessingException error) {
            throw new java.sql.SQLException("invalid settlement schedule result refs", error);
        }
    }

    private String json(List<String> values) {
        try { return mapper.writeValueAsString(values); }
        catch (JsonProcessingException error) { throw new IllegalStateException("cannot encode settlement result refs", error); }
    }

    private static void requireOwned(int changed) {
        if (changed != 1) throw new IllegalStateException("settlement schedule lease is not owned");
    }
}
