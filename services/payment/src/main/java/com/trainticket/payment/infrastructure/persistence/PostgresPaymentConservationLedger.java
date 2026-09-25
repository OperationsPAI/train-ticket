package com.trainticket.payment.infrastructure.persistence;

import com.trainticket.payment.observability.CurrencyConservation;
import com.trainticket.payment.observability.PaymentConservationLedger;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reconstructs the conservation totals from the durable payment snapshots.
 *
 * <p>Captured amounts live on the payment-intent snapshot; settled refunds live
 * on the refund snapshot. A refund that resolves a late collection is a
 * <em>reversal</em> rather than an ordinary refund, so it is counted in
 * {@code reversed} and excluded from {@code refunded}. That keeps the two
 * returned-money terms disjoint and makes
 * {@code captured - refunded - reversed = unsettled} exact.
 *
 * <p>The three aggregates are computed independently and merged on the handful
 * of currencies: the refund totals never join the multi-million row intent
 * table, and the reversal lookup is driven from the small resolved-late-case
 * set. Even so this reads two large snapshot tables, so it runs on its own
 * daemon thread at a slow cadence (see {@code PaymentConservationService})
 * rather than competing with the request path. Building covering indexes on
 * the two amount expressions out of band would make it an index-only scan;
 * that is deliberately not a migration, because {@code MigrationRunner} wraps
 * migrations in one transaction and a non-concurrent CREATE INDEX would block
 * payment writes for minutes on the current multi-gigabyte table.
 *
 * <p>Every aggregate is grouped by the record's own currency, so no query ever
 * sums two currencies together.
 */
public final class PostgresPaymentConservationLedger implements PaymentConservationLedger {
    private static final String TOTALS_SQL = """
        WITH captured AS (
            SELECT data->'capturedAmount'->>'currency' AS currency,
                   COALESCE(SUM((data->'capturedAmount'->>'minorUnits')::numeric), 0)::bigint AS minor_units
            FROM payment_intent_snapshots
            WHERE data->'capturedAmount'->>'currency' IS NOT NULL
            GROUP BY 1
        ),
        settled AS (
            SELECT data->'amount'->>'currency' AS currency,
                   COALESCE(SUM((data->'amount'->>'minorUnits')::numeric), 0)::bigint AS minor_units
            FROM refund_snapshots
            WHERE data->>'status' = 'SETTLED'
            GROUP BY 1
        ),
        reversed AS (
            SELECT refund.data->'amount'->>'currency' AS currency,
                   COALESCE(SUM((refund.data->'amount'->>'minorUnits')::numeric), 0)::bigint AS minor_units
            FROM late_payment_case_snapshots late_case
            JOIN refund_snapshots refund ON refund.id = late_case.data->>'refundId'
            WHERE late_case.data->>'status' = 'RESOLVED'
              AND refund.data->>'status' = 'SETTLED'
            GROUP BY 1
        )
        SELECT currency,
               SUM(captured_minor_units) AS captured_minor_units,
               SUM(settled_minor_units) - SUM(reversed_minor_units) AS refunded_minor_units,
               SUM(reversed_minor_units) AS reversed_minor_units
        FROM (
            SELECT currency, minor_units AS captured_minor_units, 0::bigint AS settled_minor_units, 0::bigint AS reversed_minor_units FROM captured
            UNION ALL
            SELECT currency, 0::bigint, minor_units, 0::bigint FROM settled
            UNION ALL
            SELECT currency, 0::bigint, 0::bigint, minor_units FROM reversed
        ) totals
        GROUP BY currency
        ORDER BY currency
        """;

    private final JdbcOperations jdbc;

    public PostgresPaymentConservationLedger(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    PostgresPaymentConservationLedger(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CurrencyConservation> totalsByCurrency() {
        return jdbc.query(TOTALS_SQL, (rs, rowNum) -> new CurrencyConservation(
            rs.getString("currency"),
            rs.getLong("captured_minor_units"),
            rs.getLong("refunded_minor_units"),
            rs.getLong("reversed_minor_units")
        ));
    }
}
