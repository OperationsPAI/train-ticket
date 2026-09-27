-- Pair the yield's numerator with the distance it divides by.
--
-- `yieldPerKm` is defined as captured money over recorded distance, and it was
-- 0.00 on every row because the view reads the distance from the capture's own
-- flags and no capture carries one: fare-pricing is where a distance exists, and
-- it reaches reporting on `JourneyOrderCreated` -- which the projection already
-- stores, via the same `flags->>'distanceKm'` path, once the order event carries
-- it. The order's own fact table is the join that reaches it.
SET LOCAL lock_timeout = '2s';

DO $$
BEGIN
  IF NOT pg_try_advisory_xact_lock(hashtextextended('reporting:projection-refresh', 0)) THEN
    RAISE EXCEPTION 'Reporting projection refresh is active; retry migration 011';
  END IF;
END
$$;

ALTER TABLE reporting_order_routes
  ADD COLUMN IF NOT EXISTS distance_km numeric;

CREATE MATERIALIZED VIEW reporting_revenue_breakdowns_011 AS
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
    COALESCE(
      (event.flags->>'distanceKm')::numeric,
      (SELECT distance.distance_km
         FROM reporting_order_routes distance
        WHERE distance.order_id = COALESCE(scope.order_id, CASE WHEN capture.business_ref LIKE 'ord-%' THEN capture.business_ref END)
          AND distance.distance_km IS NOT NULL
        LIMIT 1),
      0) AS distance_km
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
  SELECT capture.*, COALESCE(member.dimension_value,'unknown') AS dimension_value,
         row_number() OVER (PARTITION BY capture.event_id ORDER BY member.dimension_value) AS member_rank,
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
  SELECT capture.*, COALESCE(member.dimension_value,'unknown') AS dimension_value,
         row_number() OVER (PARTITION BY capture.event_id ORDER BY member.dimension_value,member.traveler_ref) AS member_rank,
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
  SELECT capture.*, COALESCE(member.dimension_value,'unknown') AS dimension_value,
         row_number() OVER (PARTITION BY capture.event_id ORDER BY member.dimension_value,member.segment_ref,member.traveler_ref) AS member_rank,
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
       -- The numerator that belongs with the divisor. A row can mix captures
       -- whose order carried a distance with captures whose order did not, and
       -- dividing the whole row's revenue by the part of its distance that is
       -- known reports a yield that describes neither group. Summing the amount
       -- of the same captures that contributed distance keeps the two sides of
       -- the ratio over one population; they are equal whenever every capture in
       -- the row has a recorded distance.
       SUM(amount) FILTER (WHERE distance_km > 0) AS revenue_with_distance,
       COUNT(DISTINCT event_id) FILTER (WHERE ancillary_attached) AS ancillary_count,
       COUNT(DISTINCT event_id) FILTER (WHERE insurance_attached) AS insurance_count
FROM dimension_rows
GROUP BY dimension,dimension_value,currency;
CREATE UNIQUE INDEX idx_reporting_revenue_breakdowns_dimension_value_currency_011
  ON reporting_revenue_breakdowns_011 (dimension,dimension_value,currency);

CREATE MATERIALIZED VIEW reporting_revenue_by_route_011 AS
SELECT NULLIF(dimension_value,'unknown') AS route_id,currency,revenue,payment_count
FROM reporting_revenue_breakdowns_011 WHERE dimension='route';
CREATE UNIQUE INDEX idx_reporting_revenue_by_route_route_currency_011
  ON reporting_revenue_by_route_011 (route_id,currency);

CREATE MATERIALIZED VIEW reporting_revenue_by_seat_class_011 AS
SELECT NULLIF(dimension_value,'unknown') AS seat_class,currency,revenue,payment_count
FROM reporting_revenue_breakdowns_011 WHERE dimension='seat_class';
CREATE UNIQUE INDEX idx_reporting_revenue_by_seat_class_currency_011
  ON reporting_revenue_by_seat_class_011 (seat_class,currency);

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
    replacement := original.relname || '_011';
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
  ALTER MATERIALIZED VIEW reporting_revenue_breakdowns_011 RENAME TO reporting_revenue_breakdowns;
  ALTER MATERIALIZED VIEW reporting_revenue_by_route_011 RENAME TO reporting_revenue_by_route;
  ALTER MATERIALIZED VIEW reporting_revenue_by_seat_class_011 RENAME TO reporting_revenue_by_seat_class;
  ALTER INDEX idx_reporting_revenue_breakdowns_dimension_value_currency_011 RENAME TO idx_reporting_revenue_breakdowns_dimension_value_currency;
  ALTER INDEX idx_reporting_revenue_by_route_route_currency_011 RENAME TO idx_reporting_revenue_by_route_route_currency;
  ALTER INDEX idx_reporting_revenue_by_seat_class_currency_011 RENAME TO idx_reporting_revenue_by_seat_class_currency;
END
$$;
