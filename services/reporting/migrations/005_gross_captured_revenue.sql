-- Run in one transaction; populated installations require an explicit rebuild window.
SET LOCAL lock_timeout = '2s';

DO $$
BEGIN
  IF NOT pg_try_advisory_xact_lock(hashtextextended('reporting:projection-refresh', 0)) THEN
    RAISE EXCEPTION 'Reporting projection refresh is active; retry migration 005 in the coordinated rebuild window';
  END IF;
  IF current_setting('reporting.allow_revenue_rebuild', true) IS DISTINCT FROM 'on'
     AND EXISTS (SELECT event_id FROM reporting_metric_events ORDER BY event_id LIMIT 1) THEN
    RAISE EXCEPTION 'Migration 005 requires SET LOCAL reporting.allow_revenue_rebuild = on for populated reporting data';
  END IF;
END
$$;

CREATE MATERIALIZED VIEW reporting_revenue_by_route_005 AS
SELECT
  route_id,
  COALESCE(currency, 'USD') AS currency,
  SUM(COALESCE(amount, 0)) AS revenue,
  COUNT(*) AS payment_count
FROM reporting_metric_events
WHERE event_type = 'PaymentCaptured'
GROUP BY route_id, COALESCE(currency, 'USD');
CREATE UNIQUE INDEX idx_reporting_revenue_by_route_route_currency_005
  ON reporting_revenue_by_route_005 (route_id, currency);

CREATE MATERIALIZED VIEW reporting_revenue_by_seat_class_005 AS
SELECT
  seat_class,
  COALESCE(currency, 'USD') AS currency,
  SUM(COALESCE(amount, 0)) AS revenue,
  COUNT(*) AS payment_count
FROM reporting_metric_events
WHERE event_type = 'PaymentCaptured'
GROUP BY seat_class, COALESCE(currency, 'USD');
CREATE UNIQUE INDEX idx_reporting_revenue_by_seat_class_currency_005
  ON reporting_revenue_by_seat_class_005 (seat_class, currency);

CREATE MATERIALIZED VIEW reporting_revenue_breakdowns_005 AS
WITH dimensions AS (
  SELECT
    COALESCE(route_id, 'unknown') AS route_value,
    COALESCE(seat_class, 'unknown') AS seat_value,
    COALESCE(channel, 'unknown') AS channel_value,
    COALESCE(passenger_type, 'unknown') AS passenger_value,
    date_trunc('hour', occurred_at AT TIME ZONE 'UTC') AS hour_value,
    COALESCE(currency, 'USD') AS currency,
    amount,
    flags
  FROM reporting_metric_events
  WHERE event_type = 'PaymentCaptured'
)
SELECT
  CASE
    WHEN GROUPING(route_value) = 0 THEN 'route'
    WHEN GROUPING(seat_value) = 0 THEN 'seat_class'
    WHEN GROUPING(channel_value) = 0 THEN 'channel'
    WHEN GROUPING(passenger_value) = 0 THEN 'passenger_type'
    ELSE 'time_period'
  END AS dimension,
  CASE
    WHEN GROUPING(route_value) = 0 THEN route_value
    WHEN GROUPING(seat_value) = 0 THEN seat_value
    WHEN GROUPING(channel_value) = 0 THEN channel_value
    WHEN GROUPING(passenger_value) = 0 THEN passenger_value
    ELSE to_char(hour_value, 'YYYY-MM-DD"T"HH24:00:00"Z"')
  END AS dimension_value,
  currency,
  SUM(COALESCE(amount, 0)) AS revenue,
  COUNT(*) AS payment_count,
  SUM(COALESCE((flags->>'distanceKm')::numeric, 0)) AS distance_km,
  SUM(CASE WHEN COALESCE((flags->>'ancillaryAttached')::boolean, false) THEN 1 ELSE 0 END) AS ancillary_count,
  SUM(CASE WHEN COALESCE((flags->>'insuranceAttached')::boolean, false) THEN 1 ELSE 0 END) AS insurance_count
FROM dimensions
GROUP BY GROUPING SETS (
  (route_value, currency),
  (seat_value, currency),
  (channel_value, currency),
  (passenger_value, currency),
  (hour_value, currency)
);
CREATE UNIQUE INDEX idx_reporting_revenue_breakdowns_dimension_value_currency_005
  ON reporting_revenue_breakdowns_005 (dimension, dimension_value, currency);

-- Preserve relation and column grants before replacing the original relations.
DO $$
DECLARE
  original record;
  privilege record;
  replacement text;
  recipient text;
BEGIN
  FOR original IN
    SELECT c.oid, c.relname, c.relowner, c.relacl
    FROM pg_class c
    WHERE c.oid IN ('reporting_revenue_by_route'::regclass,
                    'reporting_revenue_by_seat_class'::regclass,
                    'reporting_revenue_breakdowns'::regclass)
  LOOP
    replacement := original.relname || '_005';
    FOR privilege IN
      SELECT a.grantee, a.privilege_type, a.is_grantable
      FROM aclexplode(COALESCE(original.relacl, acldefault('r', original.relowner))) a
    LOOP
      recipient := CASE WHEN privilege.grantee = 0 THEN 'PUBLIC'
                        ELSE quote_ident(pg_get_userbyid(privilege.grantee)) END;
      EXECUTE format('GRANT %s ON %I TO %s%s', privilege.privilege_type, replacement,
                     recipient, CASE WHEN privilege.is_grantable THEN ' WITH GRANT OPTION' ELSE '' END);
    END LOOP;
    FOR privilege IN
      SELECT att.attname, a.grantee, a.privilege_type, a.is_grantable
      FROM pg_attribute att CROSS JOIN LATERAL aclexplode(att.attacl) a
      WHERE att.attrelid = original.oid AND att.attnum > 0 AND NOT att.attisdropped
    LOOP
      recipient := CASE WHEN privilege.grantee = 0 THEN 'PUBLIC'
                        ELSE quote_ident(pg_get_userbyid(privilege.grantee)) END;
      EXECUTE format('GRANT %s (%I) ON %I TO %s%s', privilege.privilege_type,
                     privilege.attname, replacement, recipient,
                     CASE WHEN privilege.is_grantable THEN ' WITH GRANT OPTION' ELSE '' END);
    END LOOP;
    EXECUTE format('ALTER MATERIALIZED VIEW %I OWNER TO %I', replacement, pg_get_userbyid(original.relowner));
  END LOOP;
END
$$;

-- RESTRICT preserves external dependencies by aborting the entire transaction.
DO $$
DECLARE
  swap_started timestamptz := clock_timestamp();
BEGIN
  DROP MATERIALIZED VIEW reporting_revenue_by_route RESTRICT;
  DROP MATERIALIZED VIEW reporting_revenue_by_seat_class RESTRICT;
  DROP MATERIALIZED VIEW reporting_revenue_breakdowns RESTRICT;
  ALTER MATERIALIZED VIEW reporting_revenue_by_route_005 RENAME TO reporting_revenue_by_route;
  ALTER MATERIALIZED VIEW reporting_revenue_by_seat_class_005 RENAME TO reporting_revenue_by_seat_class;
  ALTER MATERIALIZED VIEW reporting_revenue_breakdowns_005 RENAME TO reporting_revenue_breakdowns;
  ALTER INDEX idx_reporting_revenue_by_route_route_currency_005 RENAME TO idx_reporting_revenue_by_route_route_currency;
  ALTER INDEX idx_reporting_revenue_by_seat_class_currency_005 RENAME TO idx_reporting_revenue_by_seat_class_currency;
  ALTER INDEX idx_reporting_revenue_breakdowns_dimension_value_currency_005 RENAME TO idx_reporting_revenue_breakdowns_dimension_value_currency;
  RAISE NOTICE 'Reporting revenue swap took % ms; exclusive locks release at transaction commit',
    extract(epoch FROM clock_timestamp() - swap_started) * 1000;
END
$$;
