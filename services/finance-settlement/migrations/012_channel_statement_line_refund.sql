-- A channel statement's REFUND lines name the platform refund they settle, and
-- the refund side of a settlement date cannot be reconciled without it: the
-- comparison pairs the channel's line with payment's own settlement of the same
-- refund, and both sides carry the refund id.
ALTER TABLE channel_statement_lines ADD COLUMN IF NOT EXISTS refund_id TEXT NOT NULL DEFAULT '';
