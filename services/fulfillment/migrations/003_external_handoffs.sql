CREATE TABLE IF NOT EXISTS ancillary_fulfillment_handoffs (
  ancillary_order_item_id text PRIMARY KEY,
  data                    jsonb NOT NULL,
  updated_at              timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ride_execution_views (
  ride_request_id text PRIMARY KEY,
  data            jsonb NOT NULL,
  updated_at      timestamptz NOT NULL DEFAULT now()
);
