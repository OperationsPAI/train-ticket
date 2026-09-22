CREATE FUNCTION pg_temp.canonical_disruption_types(value jsonb)
RETURNS jsonb
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    result jsonb;
BEGIN
    IF jsonb_typeof(value) = 'object' THEN
        SELECT coalesce(jsonb_object_agg(key,
            CASE WHEN key = 'disruptionType' THEN
                to_jsonb(CASE item #>> '{}'
                    WHEN 'SERVICE_DELAY' THEN 'DELAY'
                    WHEN 'SERVICE_CANCELLED' THEN 'CANCELLATION'
                    WHEN 'SERVICE_SUSPENDED' THEN 'CANCELLATION'
                    WHEN 'SAILING_SUSPENDED' THEN 'CANCELLATION'
                    WHEN 'DRIVER_CANCELLED' THEN 'CANCELLATION'
                    WHEN 'DISPATCH_FAILED' THEN 'CANCELLATION'
                    WHEN 'STOP_CHANGED' THEN 'PARTIAL'
                    WHEN 'PORT_CALL_CHANGED' THEN 'PARTIAL'
                    WHEN 'CONNECTION_MISSED' THEN 'PARTIAL'
                    WHEN 'MISSED_CONNECTION' THEN 'PARTIAL'
                    WHEN 'BATCH_SYSTEM_EVENT' THEN 'PARTIAL'
                    WHEN 'ROAD_CLOSED' THEN 'FORCE_MAJEURE'
                    WHEN 'WEATHER' THEN 'FORCE_MAJEURE'
                    WHEN 'OPERATION_RESTRICTION' THEN 'FORCE_MAJEURE'
                    WHEN 'SUPPLIER_FAILURE' THEN 'FORCE_MAJEURE'
                    ELSE item #>> '{}'
                END)
            ELSE pg_temp.canonical_disruption_types(item)
            END), '{}'::jsonb)
        INTO result
        FROM jsonb_each(value) AS fields(key, item);
        RETURN result;
    ELSIF jsonb_typeof(value) = 'array' THEN
        SELECT coalesce(jsonb_agg(pg_temp.canonical_disruption_types(item) ORDER BY position), '[]'::jsonb)
        INTO result
        FROM jsonb_array_elements(value) WITH ORDINALITY AS items(item, position);
        RETURN result;
    END IF;
    RETURN value;
END;
$$;

UPDATE incident_snapshots
SET data = pg_temp.canonical_disruption_types(data)
WHERE data IS DISTINCT FROM pg_temp.canonical_disruption_types(data);

UPDATE recovery_case_snapshots
SET data = pg_temp.canonical_disruption_types(data)
WHERE data IS DISTINCT FROM pg_temp.canonical_disruption_types(data);

UPDATE service_alert_snapshots
SET data = pg_temp.canonical_disruption_types(data)
WHERE data IS DISTINCT FROM pg_temp.canonical_disruption_types(data);

UPDATE idempotency_records
SET response_body = pg_temp.canonical_disruption_types(response_body)
WHERE response_body IS DISTINCT FROM pg_temp.canonical_disruption_types(response_body);

DROP FUNCTION pg_temp.canonical_disruption_types(jsonb);
