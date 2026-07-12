CREATE TABLE IF NOT EXISTS schema_migrations (
    version varchar(128) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS campaigns (
    campaign_id varchar(96) PRIMARY KEY,
    version bigint NOT NULL,
    data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS campaigns_external_key_idx ON campaigns ((data->>'externalKey'));
CREATE INDEX IF NOT EXISTS campaigns_status_idx ON campaigns ((data->>'status'));

CREATE TABLE IF NOT EXISTS campaign_budgets (
    budget_id varchar(96) PRIMARY KEY,
    campaign_id varchar(96) NOT NULL,
    version bigint NOT NULL,
    data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT campaign_budgets_campaign_fk FOREIGN KEY (campaign_id) REFERENCES campaigns(campaign_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS campaign_budgets_campaign_id_idx ON campaign_budgets (campaign_id);

CREATE TABLE IF NOT EXISTS targeting_rule_sets (
    rule_set_id varchar(96) PRIMARY KEY,
    campaign_id varchar(96) NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT targeting_rule_sets_campaign_fk FOREIGN KEY (campaign_id) REFERENCES campaigns(campaign_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS targeting_rule_sets_campaign_id_idx ON targeting_rule_sets (campaign_id);

CREATE TABLE IF NOT EXISTS coupon_templates (
    template_id varchar(96) PRIMARY KEY,
    campaign_id varchar(96) NOT NULL,
    version bigint NOT NULL,
    data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT coupon_templates_campaign_fk FOREIGN KEY (campaign_id) REFERENCES campaigns(campaign_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS coupon_templates_campaign_id_idx ON coupon_templates (campaign_id);
CREATE UNIQUE INDEX IF NOT EXISTS coupon_templates_code_version_idx ON coupon_templates ((data->>'templateCode'), ((data->>'templateVersion')::integer));

CREATE TABLE IF NOT EXISTS issuance_batches (
    batch_id varchar(96) PRIMARY KEY,
    campaign_id varchar(96) NOT NULL,
    template_id varchar(96) NOT NULL,
    version bigint NOT NULL,
    data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT issuance_batches_campaign_fk FOREIGN KEY (campaign_id) REFERENCES campaigns(campaign_id) ON DELETE CASCADE,
    CONSTRAINT issuance_batches_template_fk FOREIGN KEY (template_id) REFERENCES coupon_templates(template_id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS issuance_batches_campaign_id_idx ON issuance_batches (campaign_id);
CREATE INDEX IF NOT EXISTS issuance_batches_template_id_idx ON issuance_batches (template_id);
CREATE INDEX IF NOT EXISTS issuance_batches_status_idx ON issuance_batches ((data->>'status'));

CREATE TABLE IF NOT EXISTS issuance_items (
    item_id varchar(96) PRIMARY KEY,
    batch_id varchar(96) NOT NULL,
    campaign_id varchar(96) NOT NULL,
    template_id varchar(96) NOT NULL,
    account_id varchar(128) NOT NULL,
    idempotency_key varchar(160) NOT NULL,
    status varchar(48) NOT NULL,
    data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT issuance_items_batch_fk FOREIGN KEY (batch_id) REFERENCES issuance_batches(batch_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS issuance_items_idempotency_idx ON issuance_items (batch_id, idempotency_key);
CREATE INDEX IF NOT EXISTS issuance_items_campaign_idx ON issuance_items (campaign_id, template_id, account_id);

CREATE TABLE IF NOT EXISTS redemption_validations (
    validation_id varchar(96) PRIMARY KEY,
    campaign_id varchar(96) NOT NULL,
    template_id varchar(96) NOT NULL,
    account_id varchar(128) NOT NULL,
    benefit_id varchar(128) NOT NULL,
    status varchar(48) NOT NULL,
    data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS redemption_validations_benefit_idx ON redemption_validations (benefit_id);
CREATE INDEX IF NOT EXISTS redemption_validations_campaign_idx ON redemption_validations (campaign_id, template_id);

CREATE TABLE IF NOT EXISTS outbox (
    seq bigserial PRIMARY KEY,
    event_id text NOT NULL UNIQUE,
    stream text NOT NULL,
    envelope jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX IF NOT EXISTS outbox_unpublished_idx ON outbox (seq) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS processed_events (
    event_id text PRIMARY KEY,
    stream text,
    processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS idempotency_records (
    key text PRIMARY KEY,
    request_hash text NOT NULL,
    status_code int NOT NULL,
    response_body jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
