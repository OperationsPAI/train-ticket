CREATE TABLE IF NOT EXISTS post_sales_policy_contexts (
    journey_order_id text PRIMARY KEY,
    data jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
