-- Switch revenue dimensions to authoritative business facts after the
-- historical backfill has completed. The source metric event table is retained.
SET LOCAL lock_timeout = '2s';

DO $$
BEGIN
  IF NOT pg_try_advisory_xact_lock(hashtextextended('reporting:projection-refresh', 0)) THEN
    RAISE EXCEPTION 'Reporting projection refresh is active; retry migration 008';
  END IF;
END
$$;

-- Freeze the source rows before checking coverage. Event ingestion waits here,
-- then resumes after commit with the metric row and capture reference written
-- atomically by the new service code.
LOCK TABLE reporting_metric_events IN SHARE MODE;

DO $$
BEGIN
  IF EXISTS (SELECT event_id FROM reporting_metric_events ORDER BY event_id LIMIT 1)
     AND current_setting('reporting.allow_revenue_rebuild', true) IS DISTINCT FROM 'on' THEN
    RAISE EXCEPTION 'Migration 008 requires SET LOCAL reporting.allow_revenue_rebuild = on for populated reporting data';
  END IF;
  IF EXISTS (SELECT event_id FROM reporting_metric_events WHERE event_type='PaymentCaptured' AND amount IS NOT NULL LIMIT 1)
     AND NOT EXISTS (SELECT 1 FROM reporting_dimension_backfills WHERE backfill_name='authoritative-revenue-v1') THEN
    RAISE EXCEPTION 'Migration 008 requires completed authoritative-revenue-v1 backfill';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM reporting_metric_events event
    LEFT JOIN reporting_capture_references capture ON capture.event_id=event.event_id
    WHERE event.event_type='PaymentCaptured' AND event.amount IS NOT NULL AND capture.event_id IS NULL
    LIMIT 1
  ) THEN
    RAISE EXCEPTION 'Migration 008 requires a reference for every captured payment';
  END IF;
END
$$;

CREATE MATERIALIZED VIEW reporting_revenue_breakdowns_008 AS
WITH captures AS MATERIALIZED (
  SELECT
    event.event_id,
    event.occurred_at,
    event.amount,
    COALESCE(event.currency, 'USD') AS currency,
    COALESCE(event.channel, 'unknown') AS channel_value,
    capture.business_ref,
    COALESCE(scope.order_id, CASE WHEN capture.business_ref LIKE 'ord-%' THEN capture.business_ref END) AS order_id,
    COALESCE(scope.segment_refs, ARRAY[]::text[]) AS segment_refs,
    COALESCE(scope.traveler_refs, ARRAY[]::text[]) AS traveler_refs,
    COALESCE((event.flags->>'distanceKm')::numeric, 0) AS distance_km
  FROM reporting_metric_events event
  JOIN reporting_capture_references capture ON capture.event_id=event.event_id
  LEFT JOIN reporting_business_scopes scope ON scope.business_ref=capture.business_ref
  WHERE event.event_type='PaymentCaptured' AND event.amount IS NOT NULL
),
capture_facts AS MATERIALIZED (
  SELECT captures.*,
         EXISTS (
           SELECT 1 FROM reporting_order_attachments attachment
           WHERE attachment.order_id=captures.order_id AND attachment.attachment_type='ANCILLARY'
         ) AS ancillary_attached,
         EXISTS (
           SELECT 1 FROM reporting_order_attachments attachment
           WHERE attachment.order_id=captures.order_id AND attachment.attachment_type='INSURANCE'
         ) AS insurance_attached
  FROM captures
),
route_members AS (
  SELECT capture.*, COALESCE(member.dimension_value, 'unknown') AS dimension_value,
         row_number() OVER (PARTITION BY capture.event_id ORDER BY member.dimension_value NULLS LAST) AS member_rank,
         count(*) OVER (PARTITION BY capture.event_id) AS member_count
  FROM capture_facts capture
  LEFT JOIN LATERAL (
    SELECT DISTINCT route.route_id AS dimension_value
    FROM reporting_order_routes route
    WHERE route.order_id=capture.order_id
      AND (cardinality(capture.segment_refs)=0 OR route.route_id=ANY(capture.segment_refs))
  ) member ON true
),
traveler_members AS (
  SELECT capture.*, COALESCE(member.dimension_value, 'unknown') AS dimension_value,
         row_number() OVER (PARTITION BY capture.event_id ORDER BY member.dimension_value,member.traveler_ref NULLS LAST) AS member_rank,
         count(*) OVER (PARTITION BY capture.event_id) AS member_count
  FROM capture_facts capture
  LEFT JOIN LATERAL (
    SELECT traveler.traveler_type AS dimension_value,traveler.traveler_ref
    FROM reporting_order_travelers traveler
    WHERE traveler.order_id=capture.order_id
      AND (cardinality(capture.traveler_refs)=0 OR traveler.traveler_ref=ANY(capture.traveler_refs))
  ) member ON true
),
seat_members AS (
  SELECT capture.*, COALESCE(member.dimension_value, 'unknown') AS dimension_value,
         row_number() OVER (PARTITION BY capture.event_id ORDER BY member.dimension_value,member.segment_ref NULLS LAST,member.traveler_ref NULLS LAST) AS member_rank,
         count(*) OVER (PARTITION BY capture.event_id) AS member_count
  FROM capture_facts capture
  LEFT JOIN LATERAL (
    SELECT seat.seat_class AS dimension_value,seat.segment_ref,seat.traveler_ref
    FROM reporting_seat_classes seat
    WHERE seat.order_id=capture.order_id
      AND (cardinality(capture.segment_refs)=0 OR seat.segment_ref=ANY(capture.segment_refs))
      AND (cardinality(capture.traveler_refs)=0 OR seat.traveler_ref=ANY(capture.traveler_refs))
  ) member ON true
),
split_dimensions AS (
  SELECT 'route'::text AS dimension,dimension_value,event_id,currency,
         (sign(amount) * ((trunc(abs(amount)*100)::bigint/member_count)
           + CASE WHEN member_rank<=mod(trunc(abs(amount)*100)::bigint,member_count) THEN 1 ELSE 0 END)/100.0)::numeric(18,2) AS amount,
         distance_km/member_count AS distance_km,ancillary_attached,insurance_attached
  FROM route_members
  UNION ALL
  SELECT 'traveler_type',dimension_value,event_id,currency,
         (sign(amount) * ((trunc(abs(amount)*100)::bigint/member_count)
           + CASE WHEN member_rank<=mod(trunc(abs(amount)*100)::bigint,member_count) THEN 1 ELSE 0 END)/100.0)::numeric(18,2),
         distance_km/member_count,ancillary_attached,insurance_attached
  FROM traveler_members
  UNION ALL
  SELECT 'seat_class',dimension_value,event_id,currency,
         (sign(amount) * ((trunc(abs(amount)*100)::bigint/member_count)
           + CASE WHEN member_rank<=mod(trunc(abs(amount)*100)::bigint,member_count) THEN 1 ELSE 0 END)/100.0)::numeric(18,2),
         distance_km/member_count,ancillary_attached,insurance_attached
  FROM seat_members
),
dimension_rows AS (
  SELECT * FROM split_dimensions
  UNION ALL
  SELECT 'channel',channel_value,event_id,currency,amount,distance_km,ancillary_attached,insurance_attached FROM capture_facts
  UNION ALL
  SELECT 'time_period',to_char(date_trunc('hour',occurred_at AT TIME ZONE 'UTC'),'YYYY-MM-DD"T"HH24:00:00"Z"'),
         event_id,currency,amount,distance_km,ancillary_attached,insurance_attached
  FROM capture_facts
)
SELECT dimension,dimension_value,currency,
       SUM(amount) AS revenue,
       COUNT(DISTINCT event_id) AS payment_count,
       SUM(distance_km) AS distance_km,
       COUNT(DISTINCT event_id) FILTER (WHERE ancillary_attached) AS ancillary_count,
       COUNT(DISTINCT event_id) FILTER (WHERE insurance_attached) AS insurance_count
FROM dimension_rows
GROUP BY dimension,dimension_value,currency;
CREATE UNIQUE INDEX idx_reporting_revenue_breakdowns_dimension_value_currency_008
  ON reporting_revenue_breakdowns_008 (dimension,dimension_value,currency);

CREATE MATERIALIZED VIEW reporting_revenue_by_route_008 AS
SELECT NULLIF(dimension_value,'unknown') AS route_id,currency,revenue,payment_count
FROM reporting_revenue_breakdowns_008 WHERE dimension='route';
CREATE UNIQUE INDEX idx_reporting_revenue_by_route_route_currency_008
  ON reporting_revenue_by_route_008 (route_id,currency);

CREATE MATERIALIZED VIEW reporting_revenue_by_seat_class_008 AS
SELECT NULLIF(dimension_value,'unknown') AS seat_class,currency,revenue,payment_count
FROM reporting_revenue_breakdowns_008 WHERE dimension='seat_class';
CREATE UNIQUE INDEX idx_reporting_revenue_by_seat_class_currency_008
  ON reporting_revenue_by_seat_class_008 (seat_class,currency);

-- Preserve relation and column grants before replacing the original relations.
DO $$
DECLARE
  original record;
  privilege record;
  replacement text;
  recipient text;
BEGIN
  FOR original IN
    SELECT c.oid,c.relname,c.relowner,c.relacl
    FROM pg_class c
    WHERE c.oid IN ('reporting_revenue_by_route'::regclass,
                    'reporting_revenue_by_seat_class'::regclass,
                    'reporting_revenue_breakdowns'::regclass)
  LOOP
    replacement := original.relname || '_008';
    FOR privilege IN
      SELECT a.grantee,a.privilege_type,a.is_grantable
      FROM aclexplode(COALESCE(original.relacl,acldefault('r',original.relowner))) a
    LOOP
      recipient := CASE WHEN privilege.grantee=0 THEN 'PUBLIC'
                        ELSE quote_ident(pg_get_userbyid(privilege.grantee)) END;
      EXECUTE format('GRANT %s ON %I TO %s%s',privilege.privilege_type,replacement,
                     recipient,CASE WHEN privilege.is_grantable THEN ' WITH GRANT OPTION' ELSE '' END);
    END LOOP;
    FOR privilege IN
      SELECT att.attname,a.grantee,a.privilege_type,a.is_grantable
      FROM pg_attribute att CROSS JOIN LATERAL aclexplode(att.attacl) a
      WHERE att.attrelid=original.oid AND att.attnum>0 AND NOT att.attisdropped
    LOOP
      recipient := CASE WHEN privilege.grantee=0 THEN 'PUBLIC'
                        ELSE quote_ident(pg_get_userbyid(privilege.grantee)) END;
      EXECUTE format('GRANT %s (%I) ON %I TO %s%s',privilege.privilege_type,
                     privilege.attname,replacement,recipient,
                     CASE WHEN privilege.is_grantable THEN ' WITH GRANT OPTION' ELSE '' END);
    END LOOP;
    EXECUTE format('ALTER MATERIALIZED VIEW %I OWNER TO %I',replacement,pg_get_userbyid(original.relowner));
  END LOOP;
END
$$;

DO $$
BEGIN
  DROP MATERIALIZED VIEW reporting_revenue_by_route RESTRICT;
  DROP MATERIALIZED VIEW reporting_revenue_by_seat_class RESTRICT;
  DROP MATERIALIZED VIEW reporting_revenue_breakdowns RESTRICT;
  ALTER MATERIALIZED VIEW reporting_revenue_breakdowns_008 RENAME TO reporting_revenue_breakdowns;
  ALTER MATERIALIZED VIEW reporting_revenue_by_route_008 RENAME TO reporting_revenue_by_route;
  ALTER MATERIALIZED VIEW reporting_revenue_by_seat_class_008 RENAME TO reporting_revenue_by_seat_class;
  ALTER INDEX idx_reporting_revenue_breakdowns_dimension_value_currency_008 RENAME TO idx_reporting_revenue_breakdowns_dimension_value_currency;
  ALTER INDEX idx_reporting_revenue_by_route_route_currency_008 RENAME TO idx_reporting_revenue_by_route_route_currency;
  ALTER INDEX idx_reporting_revenue_by_seat_class_currency_008 RENAME TO idx_reporting_revenue_by_seat_class_currency;
END
$$;
