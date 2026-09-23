CREATE TABLE compensation_issuances (
    case_id text PRIMARY KEY,
    benefit_id text NOT NULL UNIQUE,
    request jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_compensation_benefit_case
    ON promotion_instrument_snapshots ((data->>'caseId'))
    WHERE data->>'issuanceSource' = 'POST_SALES_COMP';
