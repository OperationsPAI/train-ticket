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
