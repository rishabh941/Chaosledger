import { C as COLORS } from "../theme";
import type {
    Transaction,
    MetricPoint,
    ChaosEvent,
    LogEntry,
    TimelineEvent,
    InvariantResult,
    RaftNodeStatus,
    TxOperation,
    TxStatus,
} from "../types/dashboard";

const rid = () => Math.random().toString(36).substring(2, 10).toUpperCase();
const accId = () => `ACC-${Math.random().toString(36).substring(2, 8).toUpperCase()}`;

// Invariant definitions
export const INVARIANT_DEFS: Omit<InvariantResult, "status" | "message" | "checkedAt" | "durationMs">[] = [
    { id: "conservation",          name: "Conservation of Money",         description: "Total money in system never changes" },
    { id: "no-negative",           name: "No Negative Balance",           description: "No account balance goes below zero" },
    { id: "event-ordering",        name: "Event Ordering",                description: "Events are monotonically ordered per aggregate" },
    { id: "projection-determinism",name: "Projection Determinism",        description: "Same events produce same state" },
    { id: "account-integrity",     name: "Account Integrity",             description: "Every account transitions through valid states" },
    { id: "single-leader",         name: "Exactly One Leader",            description: "At most one leader at any time" },
    { id: "committed-logs",        name: "Committed Logs Never Decrease", description: "Commit index only moves forward" },
    { id: "quorum",                name: "Majority Quorum Available",     description: "At least 2 of 3 nodes reachable" },
    { id: "no-stale-leader",       name: "No Stale Leader",               description: "Old leader steps down after partition" },
    { id: "tx-ordering",           name: "Transaction Ordering Preserved",description: "Causal ordering enforced via HLC" },
    { id: "no-duplicate",          name: "No Duplicate Commits",          description: "Idempotency keys prevent double-apply" },
];

export function generateNodes(leaderId: number): RaftNodeStatus[] {
    return [1, 2, 3].map((n) => ({
        nodeId: `node-${n}`,
        role: n === leaderId ? "LEADER" : "FOLLOWER",
        leaderId: `node-${leaderId}`,
        term: 7,
        commitIndex: n === 3 ? 2538 : 2541,
        logIndex: 2541,
    }));
}

export function generateInvariants(): InvariantResult[] {
    return INVARIANT_DEFS.map((def) => ({
        ...def,
        status: "PASSED" as const,
        message: "All checks passed",
        checkedAt: new Date().toISOString(),
        durationMs: Math.floor(Math.random() * 20 + 3),
    }));
}

export function generateTransactions(count: number): Transaction[] {
    const ops: TxOperation[] = ["DEPOSIT", "WITHDRAW", "TRANSFER"];
    const statuses: TxStatus[] = ["COMMITTED", "COMMITTED", "COMMITTED", "COMMITTED", "PENDING", "FAILED"];
    const leaders = ["node-1", "node-2", "node-3"];
    const now = Date.now();

    return Array.from({ length: count }, (_, i) => ({
        id: `TXN-${rid()}`,
        account: accId(),
        operation: ops[Math.floor(Math.random() * ops.length)],
        amount: Math.round((Math.random() * 500 + 1) * 100) / 100,
        status: statuses[Math.floor(Math.random() * statuses.length)],
        leader: leaders[Math.floor(Math.random() * leaders.length)],
        latency: Math.floor(Math.random() * 45) + 5,
        timestamp: new Date(now - i * (Math.random() * 3000 + 500)).toISOString(),
    }));
}

export function generateMetrics(count: number): MetricPoint[] {
    const now = Date.now();
    return Array.from({ length: count }, (_, i) => {
        const t = new Date(now - (count - 1 - i) * 5000);
        return {
            time: t.toLocaleTimeString("en-US", { hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" }),
            tps: Math.floor(Math.random() * 200 + 400),
            latency: Math.floor(Math.random() * 20 + 8),
            replLag: Math.floor(Math.random() * 5),
            cpu: Math.floor(Math.random() * 30 + 25),
            memory: Math.floor(Math.random() * 15 + 55),
        };
    });
}

export function generateChaosEvents(): ChaosEvent[] {
    const raw = [
        { type: "Node Failure",         severity: "critical" as const, desc: "Node-3 process killed — simulating OOM crash" },
        { type: "Leader Election",      severity: "warning"  as const, desc: "Node-2 elected leader (term 7) after Node-1 partition" },
        { type: "High Latency",         severity: "warning"  as const, desc: "500ms ± 100ms injected on Node-1 Raft traffic" },
        { type: "Packet Loss",          severity: "info"     as const, desc: "Slicer toxic active — 10-byte fragments, 100ms delays" },
        { type: "Recovery Complete",    severity: "success"  as const, desc: "Node-3 rejoined cluster — all invariants verified" },
        { type: "Asymmetric Partition", severity: "critical" as const, desc: "Node-2 upstream throttled to 0 KB/s — cannot receive" },
        { type: "Clock Drift",          severity: "warning"  as const, desc: "+2000ms offset applied to Node-1 AdjustableClock" },
        { type: "Split-Brain Check",    severity: "success"  as const, desc: "0 simultaneous leaders observed across 40 samples" },
        { type: "OCC Race",             severity: "info"     as const, desc: "Concurrent transfer test: exactly 1 of 2 succeeded" },
        { type: "Heal All",             severity: "success"  as const, desc: "All toxics removed — cluster fully restored" },
    ];
    return raw.map((e, i) => ({
        ...e,
        id: rid(),
        description: e.desc,
        timestamp: new Date(Date.now() - i * (Math.random() * 15000 + 5000)).toISOString(),
    }));
}

export function generateLogs(): LogEntry[] {
    const messages = [
        { lvl: "INFO",  msg: "Leader elected: node-2 (term 7)" },
        { lvl: "INFO",  msg: "Transaction TXN-8A3F committed in 12ms" },
        { lvl: "INFO",  msg: "Heartbeat received from node-1 (lag: 2ms)" },
        { lvl: "WARN",  msg: "Packet delay detected on raft-node3 upstream" },
        { lvl: "INFO",  msg: "Snapshot completed: 2541 events compacted" },
        { lvl: "INFO",  msg: "Invariant check cycle: 5/5 passed (23ms)" },
        { lvl: "DEBUG", msg: "Replication lag on node-3: 3 entries behind" },
        { lvl: "INFO",  msg: "HLC tick: 1721834567000:0@node-2" },
        { lvl: "INFO",  msg: "Conservation check: ✓ total=127500.00" },
        { lvl: "INFO",  msg: "Proxy raft-node1 re-enabled after partition heal" },
        { lvl: "INFO",  msg: "Event ordering verified: 847 events across 23 aggregates" },
        { lvl: "WARN",  msg: "OCC conflict on ACC-7B2F — retry succeeded (v4→v5)" },
        { lvl: "INFO",  msg: "Clock offset reset to 0ms on all nodes" },
        { lvl: "INFO",  msg: "Bandwidth toxic asym-cannot_receive-2 removed" },
        { lvl: "ERROR", msg: "Connection timeout on node-1 gRPC channel — reconnecting" },
    ];
    return messages.map((m, i) => ({
        time: new Date(Date.now() - (messages.length - i) * 2000).toLocaleTimeString("en-US", { hour12: false }),
        level: m.lvl as LogEntry["level"],
        message: m.msg,
    }));
}

export const TIMELINE_EVENTS: TimelineEvent[] = [
    { label: "Cluster Boot",    detail: "3 nodes started with Toxiproxy",    color: COLORS.blue,  time: "14:30:00" },
    { label: "Leader Elected",  detail: "node-2 elected (term 1)",           color: COLORS.green, time: "14:30:12" },
    { label: "Steady State",    detail: "2,541 transactions committed",      color: COLORS.blue,  time: "14:31:00" },
    { label: "Chaos: Partition", detail: "Node-1 Raft proxy disabled",       color: COLORS.red,   time: "14:32:15" },
    { label: "Re-election",     detail: "node-3 elected (term 2)",           color: COLORS.amber, time: "14:32:22" },
    { label: "Node Recovered",  detail: "Node-1 rejoined, caught up",        color: COLORS.green, time: "14:33:00" },
    { label: "Invariants ✓",    detail: "All 5 passed on all nodes",         color: COLORS.green, time: "14:33:05" },
    { label: "Snapshot",         detail: "Log compacted at index 2541",      color: COLORS.blue,  time: "14:34:00" },
];

export const CLUSTER_STATS = [
    { label: "Leader Elections",   value: 7,    color: COLORS.blue },
    { label: "Failed Elections",   value: 0,    color: COLORS.red },
    { label: "Chaos Tests",        value: 10,   color: COLORS.amber },
    { label: "Recovered Nodes",    value: 9,    color: COLORS.green },
    { label: "Snapshot Count",     value: 12,   color: COLORS.blue },
    { label: "Replicated Entries", value: 2541, color: COLORS.green },
];
