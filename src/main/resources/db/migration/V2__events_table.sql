CREATE TABLE events(
    id UUID PRIMARY KEY ,
    aggregate_id UUID NOT NULL ,
    aggregate_type VARCHAR(64) NOT NULL ,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_events_aggregate_version UNIQUE (aggregate_id, version),
    CONSTRAINT chk_events_version_positive CHECK (version > 0)
);

CREATE INDEX idx_events_aggregate_id_version
    ON events (aggregate_id, version);

CREATE INDEX idx_events_created_at
    ON events (created_at);

COMMENT ON TABLE events IS
    'Append-only event log. Never UPDATE or DELETE rows from this table.';