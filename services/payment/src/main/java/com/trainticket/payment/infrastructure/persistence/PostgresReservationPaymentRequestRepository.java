package com.trainticket.payment.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.application.ReservationPaymentRequest;
import com.trainticket.payment.domain.ports.ReservationPaymentRequestRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresReservationPaymentRequestRepository implements ReservationPaymentRequestRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Autowired
    public PostgresReservationPaymentRequestRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ReservationPaymentRequest> findBySegmentBookingId(String segmentBookingId) {
        return jdbc.query(
            "SELECT data::text AS data FROM reservation_payment_requests WHERE segment_booking_id = ?",
            rs -> rs.next() ? Optional.of(JacksonPaymentJson.toReservation(rs.getString("data"), objectMapper)) : Optional.empty(),
            segmentBookingId
        );
    }

    @Override
    public ReservationPaymentRequest saveIfAbsent(ReservationPaymentRequest request) {
        try {
            jdbc.update(
                "INSERT INTO reservation_payment_requests(segment_booking_id, data) VALUES (?, ?::jsonb)",
                request.segmentBookingId(),
                JacksonPaymentJson.reservationJson(request, objectMapper)
            );
            return request;
        } catch (DuplicateKeyException exception) {
            return findBySegmentBookingId(request.segmentBookingId()).orElseThrow(() -> exception);
        }
    }
}
