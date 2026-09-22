CREATE INDEX IF NOT EXISTS idx_ancillary_items_offer
ON ancillary_order_items((data->>'ancillaryOfferId'));
