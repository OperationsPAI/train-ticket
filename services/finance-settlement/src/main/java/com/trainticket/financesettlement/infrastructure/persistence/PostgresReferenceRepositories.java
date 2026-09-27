package com.trainticket.financesettlement.infrastructure.persistence;

import com.trainticket.financesettlement.application.PaymentIntentOrderReferenceRepository;
import com.trainticket.financesettlement.application.SegmentBookingOrderReferenceRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

final class PostgresReferenceRepositories {
    private PostgresReferenceRepositories() {
    }

    @Repository
    @Primary
    @ConditionalOnBean(DataSource.class)
    static class PaymentIntentRefs implements PaymentIntentOrderReferenceRepository {
        private final JdbcTemplate jdbc;

        PaymentIntentRefs(DataSource dataSource) {
            this.jdbc = new JdbcTemplate(dataSource);
        }

        @Override
        public void save(String paymentIntentId, String orderReference) {
            jdbc.update(
                """
                    INSERT INTO payment_intent_order_refs(payment_intent_id, order_reference)
                    VALUES (?, ?)
                    ON CONFLICT (payment_intent_id) DO UPDATE SET order_reference = EXCLUDED.order_reference
                    """,
                paymentIntentId,
                orderReference
            );
        }

        @Override
        public java.util.Map<String, String> findOrderReferences(java.util.Collection<String> paymentIntentIds) {
            if (paymentIntentIds == null || paymentIntentIds.isEmpty()) return java.util.Map.of();
            java.util.Map<String, String> found = new java.util.LinkedHashMap<>();
            try (var connection = jdbc.getDataSource().getConnection()) {
                var array = connection.createArrayOf("text", paymentIntentIds.toArray());
                jdbc.query(
                    "SELECT payment_intent_id, order_reference FROM payment_intent_order_refs WHERE payment_intent_id = ANY(?)",
                    rs -> {
                        found.put(rs.getString("payment_intent_id"), rs.getString("order_reference"));
                    },
                    array
                );
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("resolve payment intent order references: " + failure.getMessage(), failure);
            }
            return found;
        }

        @Override
        public Optional<String> findOrderReference(String paymentIntentId) {
            return jdbc.query(
                "SELECT order_reference FROM payment_intent_order_refs WHERE payment_intent_id = ?",
                rs -> rs.next() ? Optional.of(rs.getString("order_reference")) : Optional.empty(),
                paymentIntentId
            );
        }
    }

    @Repository
    @Primary
    @ConditionalOnBean(DataSource.class)
    static class SegmentBookingRefs implements SegmentBookingOrderReferenceRepository {
        private final JdbcTemplate jdbc;

        SegmentBookingRefs(DataSource dataSource) {
            this.jdbc = new JdbcTemplate(dataSource);
        }

        @Override
        public void save(String segmentBookingId, String orderReference) {
            jdbc.update(
                """
                    INSERT INTO segment_booking_order_refs(segment_booking_id, order_reference)
                    VALUES (?, ?)
                    ON CONFLICT (segment_booking_id) DO UPDATE SET order_reference = EXCLUDED.order_reference
                    """,
                segmentBookingId,
                orderReference
            );
        }

        @Override
        public Optional<String> findOrderReference(String segmentBookingId) {
            return jdbc.query(
                "SELECT order_reference FROM segment_booking_order_refs WHERE segment_booking_id = ?",
                rs -> rs.next() ? Optional.of(rs.getString("order_reference")) : Optional.empty(),
                segmentBookingId
            );
        }
    }
}
