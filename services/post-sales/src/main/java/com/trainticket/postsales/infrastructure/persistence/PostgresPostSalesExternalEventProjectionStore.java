package com.trainticket.postsales.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.postsales.application.AncillaryPostSalesProjection;
import com.trainticket.postsales.application.DispatchPostSalesProjection;
import com.trainticket.postsales.application.PostSalesExternalEventProjectionStore;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresPostSalesExternalEventProjectionStore implements PostSalesExternalEventProjectionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PostgresPostSalesExternalEventProjectionStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveAncillary(AncillaryPostSalesProjection projection) {
        jdbc.update("""
            INSERT INTO post_sales_ancillary_projections (ancillary_order_item_id, journey_order_id, status, data, updated_at)
            VALUES (?, ?, ?, ?::jsonb, now())
            ON CONFLICT (ancillary_order_item_id) DO UPDATE
            SET journey_order_id = EXCLUDED.journey_order_id,
                status = EXCLUDED.status,
                data = EXCLUDED.data,
                updated_at = now()
            """, projection.ancillaryOrderItemId(), projection.journeyOrderId(), projection.status(), write(projection));
    }

    @Override
    public Optional<AncillaryPostSalesProjection> findAncillaryByItemId(String ancillaryOrderItemId) {
        return jdbc.query(
            "SELECT data FROM post_sales_ancillary_projections WHERE ancillary_order_item_id = ?",
            rs -> rs.next() ? Optional.of(read(rs.getString("data"), AncillaryPostSalesProjection.class)) : Optional.empty(),
            ancillaryOrderItemId
        );
    }

    @Override
    public void saveDispatch(DispatchPostSalesProjection projection) {
        jdbc.update("""
            INSERT INTO post_sales_dispatch_projections (ride_request_id, rider_account_id, status, data, updated_at)
            VALUES (?, ?, ?, ?::jsonb, now())
            ON CONFLICT (ride_request_id) DO UPDATE
            SET rider_account_id = EXCLUDED.rider_account_id,
                status = EXCLUDED.status,
                data = EXCLUDED.data,
                updated_at = now()
            """, projection.rideRequestId(), projection.riderAccountId(), projection.status(), write(projection));
    }

    @Override
    public Optional<DispatchPostSalesProjection> findDispatchByRideRequestId(String rideRequestId) {
        return jdbc.query(
            "SELECT data FROM post_sales_dispatch_projections WHERE ride_request_id = ?",
            rs -> rs.next() ? Optional.of(read(rs.getString("data"), DispatchPostSalesProjection.class)) : Optional.empty(),
            rideRequestId
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("post-sales external projection could not be encoded", exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("post-sales external projection could not be decoded", exception);
        }
    }
}
