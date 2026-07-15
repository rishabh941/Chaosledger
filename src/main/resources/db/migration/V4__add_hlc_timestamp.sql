-- Add Hybrid Logical Clock timestamp columns to the events table.
-- These columns store the HLC timestamp that was assigned when the
-- event was committed through Raft (or created in single-node mode).
--
-- physicalTime + logicalCounter + nodeId together form a total order
-- across all nodes in the cluster, even when wall clocks disagree.

ALTER TABLE events
    ADD COLUMN hlc_physical_time  BIGINT,
    ADD COLUMN hlc_logical_counter INTEGER,
    ADD COLUMN hlc_node_id        VARCHAR(64);

-- Index for querying events in HLC order (cross-aggregate ordering).
-- This is how you answer "what happened across the entire system
-- between time A and time B?" — something version numbers alone can't do.
CREATE INDEX idx_events_hlc_order
    ON events (hlc_physical_time, hlc_logical_counter, hlc_node_id);

COMMENT ON COLUMN events.hlc_physical_time IS
    'HLC physical component — milliseconds since epoch, always >= wall clock';
COMMENT ON COLUMN events.hlc_logical_counter IS
    'HLC logical counter — increments when physical time does not advance';
COMMENT ON COLUMN events.hlc_node_id IS
    'Node ID that generated this HLC timestamp';