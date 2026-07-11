package com.trainticket.postsales.infrastructure.persistence;

import com.trainticket.postsales.application.PostSalesPolicyContext;
import com.trainticket.postsales.application.PostSalesPolicyContextStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresPostSalesPolicyContextStore implements PostSalesPolicyContextStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PostgresPostSalesPolicyContextStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<PostSalesPolicyContext> findByOrderId(String journeyOrderId) {
        ensureTable();
        return jdbc.query("SELECT data FROM post_sales_policy_contexts WHERE journey_order_id = ?", rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(read(rs.getString("data")));
        }, journeyOrderId);
    }

    @Override
    public void save(PostSalesPolicyContext context) {
        ensureTable();
        jdbc.update("""
            INSERT INTO post_sales_policy_contexts (journey_order_id, data, updated_at)
            VALUES (?, ?::jsonb, now())
            ON CONFLICT (journey_order_id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()
            """, context.journeyOrderId(), write(context));
    }

    private void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS post_sales_policy_contexts (
                journey_order_id text PRIMARY KEY,
                data jsonb NOT NULL,
                updated_at timestamptz NOT NULL DEFAULT now()
            )
            """);
    }

    private String write(PostSalesPolicyContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("post-sales policy context could not be encoded", exception);
        }
    }

    private PostSalesPolicyContext read(String json) {
        try {
            return objectMapper.readValue(json, PostSalesPolicyContext.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("post-sales policy context could not be decoded", exception);
        }
    }
}
