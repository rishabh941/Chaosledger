CREATE TABLE processed_commands (
    idempotency_key UUID PRIMARY KEY,
    command_type VARCHAR(128) NOT NULL,
    aggregate_id UUID NOT NULL,
    result JSONB,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_commands_aggregate
    ON processed_commands (aggregate_id);

COMMENT ON TABLE processed_commands IS
    'Stores processed command idempotency keys to prevent duplicate execution.';
