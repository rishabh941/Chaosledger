import { useState, useEffect, useRef } from "react";
import { useWebSocket } from "./useWebSocket";
import {
    fetchDashboardSnapshot,
    fetchChaosStatus,
} from "../services/api";
import {
    generateNodes,
    generateInvariants,
    generateTransactions,
    generateMetrics,
    generateChaosEvents,
    generateLogs,
} from "../services/demoData";
import type {
    RaftNodeStatus,
    InvariantResult,
    Transaction,
    MetricPoint,
    ChaosEvent,
    LogEntry,
} from "../types/dashboard";

export interface ClusterStat {
    label: string;
    value: number;
    color: string;
}

interface DashboardData {
    nodes: RaftNodeStatus[];
    invariants: InvariantResult[];
    transactions: Transaction[];
    metrics: MetricPoint[];
    chaosEvents: ChaosEvent[];
    logs: LogEntry[];
    leaderNode: number;
    eventCount: number;
    connected: boolean;
    mode: "live" | "demo";
    tps: number;
    avgLatency: number;
    clusterStats: ClusterStat[];
}

export function useDashboardData(): DashboardData {
    const [mode, setMode] = useState<"live" | "demo">("demo");
    const [nodes, setNodes] = useState<RaftNodeStatus[]>(() => generateNodes(2));
    const [invariants, setInvariants] = useState<InvariantResult[]>(() => generateInvariants());
    const [transactions, setTransactions] = useState<Transaction[]>(() => generateTransactions(25));
    const [metrics, setMetrics] = useState<MetricPoint[]>(() => generateMetrics(30));
    const [chaosEvents, setChaosEvents] = useState<ChaosEvent[]>(() => generateChaosEvents());
    const [logs, setLogs] = useState<LogEntry[]>(() => generateLogs());
    const [leaderNode, setLeaderNode] = useState(2);
    const [eventCount, setEventCount] = useState(0);
    const [tps, setTps] = useState(0);
    const [avgLatency, setAvgLatency] = useState(0);
    const [clusterStats, setClusterStats] = useState<ClusterStat[]>([]);

    // For computing smoothed TPS on the frontend
    const prevEventCount = useRef(0);
    const prevTimestamp = useRef(Date.now());
    const tpsHistory = useRef<number[]>([]);
    const wsTickTimestamps = useRef<number[]>([]);

    // WebSocket connection (with failover to node-2/node-3)
    const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const host = window.location.host;
    const wsUrls = [
        `${wsProtocol}//${host}/ws/websocket`,
        `${wsProtocol}//${host}/ws-n2/websocket`,
        `${wsProtocol}//${host}/ws-n3/websocket`,
    ];
    const { data: wsData, connected: wsConnected } = useWebSocket(wsUrls);

    useEffect(() => {
        async function loadInitialData() {
            try {
                const snapshot = await fetchDashboardSnapshot();

                if (snapshot.nodes && snapshot.nodes.length > 0) {
                    const parsedNodes: RaftNodeStatus[] = snapshot.nodes.map((n) => ({
                        nodeId: n.nodeId || "unknown",
                        role: (n.role as RaftNodeStatus["role"]) || "FOLLOWER",
                        leaderId: n.leaderId || "unknown",
                        term: n.term || 0,
                        commitIndex: 0,
                        logIndex: 0,
                    }));
                    setNodes(parsedNodes);

                    const leader = parsedNodes.find((n) => n.role === "LEADER");
                    if (leader) {
                        const num = parseInt(leader.nodeId.replace("node-", ""), 10);
                        if (!isNaN(num)) setLeaderNode(num);
                    }
                }

                if (snapshot.invariants && snapshot.invariants.length > 0) {
                    const parsedInvariants: InvariantResult[] = snapshot.invariants.map(
                        (inv: any) => ({
                            id: inv.name.toLowerCase().replace(/\s+/g, "-"),
                            name: inv.name,
                            description: inv.message || "",
                            status: inv.status || "PASSED",
                            message: inv.message || "",
                            checkedAt: inv.checkedAt || new Date().toISOString(),
                            durationMs: inv.duration?.seconds
                                ? inv.duration.seconds * 1000
                                : 0,
                        })
                    );
                    setInvariants(parsedInvariants);
                }

                setEventCount(snapshot.eventCount || 0);
                prevEventCount.current = snapshot.eventCount || 0;
                setMode("live");
                console.log(
                    "%c✓ Connected to ChaosLedger backend",
                    "color: #10B981; font-weight: bold"
                );
            } catch (err) {
                console.log(
                    "%c⚠ Backend not reachable — using demo data",
                    "color: #F59E0B; font-weight: bold"
                );
                setMode("demo");
            }
        }

        loadInitialData();

        fetchChaosStatus()
            .then((chaos) => {
                if (chaos.eventLog && chaos.eventLog.length > 0) {
                    const parsed: ChaosEvent[] = chaos.eventLog.map((e, i) => ({
                        id: `chaos-${i}`,
                        type: e.action,
                        severity: severityFromAction(e.action),
                        description: e.description,
                        timestamp: e.timestamp,
                    }));
                    setChaosEvents(parsed);
                }
            })
            .catch(() => {});
    }, []);

    const liveInitialized = useRef(false);
    useEffect(() => {
        if (!wsData) return;

        if (!liveInitialized.current) {
            liveInitialized.current = true;
            setMetrics([]);
            setTransactions([]);
            setChaosEvents([]);
            setLogs([]);
        }
        setMode("live");

        const currentCount = wsData.eventCount || 0;
        setEventCount(currentCount);

        // Compute smoothed TPS (rolling 5-sample average)
        const now = Date.now();
        const elapsed = (now - prevTimestamp.current) / 1000;
        const delta = currentCount - prevEventCount.current;
        const instantTps = elapsed > 0 ? delta / elapsed : 0;
        prevEventCount.current = currentCount;
        prevTimestamp.current = now;

        tpsHistory.current.push(instantTps);
        if (tpsHistory.current.length > 8) tpsHistory.current.shift();
        const peakTps = Math.max(...tpsHistory.current);
        setTps(Math.round(peakTps));
        const computedTps = Math.round(peakTps);

        wsTickTimestamps.current.push(now);
        if (wsTickTimestamps.current.length > 10) wsTickTimestamps.current.shift();
        if (wsData.invariants && wsData.invariants.length > 0) {
            const durations = wsData.invariants
                .map((inv) => inv.durationMs || 0)
                .filter((d) => d > 0);
            if (durations.length > 0) {
                setAvgLatency(Math.round(durations.reduce((a, b) => a + b, 0) / durations.length));
            } else if (wsTickTimestamps.current.length >= 2) {
                const lastTick = wsTickTimestamps.current[wsTickTimestamps.current.length - 1]
                    - wsTickTimestamps.current[wsTickTimestamps.current.length - 2];
                setAvgLatency(Math.round(lastTick / 100));
            }
        } else if (wsTickTimestamps.current.length >= 2) {
            const lastTick = wsTickTimestamps.current[wsTickTimestamps.current.length - 1]
                - wsTickTimestamps.current[wsTickTimestamps.current.length - 2];
            setAvgLatency(Math.round(lastTick / 100));
        }

        let currentLeader = "unknown";

        if (wsData.raftNodes && wsData.raftNodes.length > 0) {
            const parsedNodes: RaftNodeStatus[] = wsData.raftNodes.map((n) => ({
                nodeId: n.nodeId,
                role: n.role as RaftNodeStatus["role"],
                leaderId: n.leaderId,
                term: n.term,
                commitIndex: n.commitIndex,
                logIndex: n.logIndex,
            }));
            setNodes(parsedNodes);

            const leader = parsedNodes.find((n) => n.role === "LEADER");
            if (leader) {
                currentLeader = leader.nodeId;
                const num = parseInt(leader.nodeId.replace("node-", ""), 10);
                if (!isNaN(num)) setLeaderNode(num);
            }

            const leaderCount = parsedNodes.filter(n => n.role === "LEADER").length;
            const reachable = parsedNodes.filter(n => (n.role as string) !== "UNREACHABLE").length;
            const maxCommit = Math.max(...parsedNodes.map(n => n.commitIndex));
            const maxTerm = Math.max(...parsedNodes.map(n => n.term));

            setClusterStats([
                { label: "Current Term", value: maxTerm, color: "#3B82F6" },
                { label: "Active Leaders", value: leaderCount, color: "#10B981" },
                { label: "Reachable Nodes", value: reachable, color: "#3B82F6" },
                { label: "Commit Index", value: maxCommit, color: "#10B981" },
                { label: "Total Events", value: currentCount, color: "#3B82F6" },
                { label: "Invariants", value: wsData.invariants?.length || 0, color: "#10B981" },
            ]);

            const replLag = wsData.raftNodes.length > 1
                ? Math.max(...wsData.raftNodes.map(n => n.commitIndex)) - Math.min(...wsData.raftNodes.map(n => n.commitIndex))
                : 0;

            const memPercent = wsData.metrics?.memoryPercent || 0;

            setMetrics((prev) => {
                const t = new Date();
                const point: MetricPoint = {
                    time: t.toLocaleTimeString("en-US", {
                        hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit",
                    }),
                    tps: computedTps,
                    latency: wsTickTimestamps.current.length >= 2
                        ? Math.round((wsTickTimestamps.current[wsTickTimestamps.current.length - 1]
                            - wsTickTimestamps.current[wsTickTimestamps.current.length - 2]) / 100)
                        : 0,
                    replLag,
                    cpu: 0,
                    memory: memPercent,
                };
                const next = [...prev, point];
                if (next.length > 60) return next.slice(next.length - 60);
                return next;
            });
        }

        if (wsData.invariants && wsData.invariants.length > 0) {
            setInvariants(
                wsData.invariants.map((inv) => ({
                    id: inv.name.toLowerCase().replace(/\s+/g, "-"),
                    name: inv.name,
                    description: inv.message || "",
                    status: inv.status as InvariantResult["status"],
                    message: inv.message || "",
                    checkedAt: inv.checkedAt || new Date().toISOString(),
                    durationMs: inv.durationMs || 0,
                }))
            );
        }

        if (wsData.recentEvents && wsData.recentEvents.length > 0) {
            const txs: Transaction[] = wsData.recentEvents.map((e) => ({
                id: String(e.eventId || ""),
                account: String(e.aggregateId || "").substring(0, 12),
                operation: mapEventType(String(e.eventType || "")),
                amount: Number(e.amount) || 0,
                status: "COMMITTED" as const,
                leader: currentLeader,
                latency: 0,
                timestamp: String(e.createdAt || new Date().toISOString()),
            }));
            setTransactions(txs);
        }

        if (wsData.recentEvents && wsData.recentEvents.length > 0) {
            const logEntries: LogEntry[] = wsData.recentEvents.slice(0, 15).map((e) => ({
                time: new Date(String(e.createdAt)).toLocaleTimeString("en-US", { hour12: false }),
                level: "INFO" as const,
                message: `${e.eventType} on ${String(e.aggregateId).substring(0, 8)}… (v${e.version})`,
            }));
            setLogs(logEntries);
        }

        if (wsData.chaosEvents && wsData.chaosEvents.length > 0) {
            const parsed: ChaosEvent[] = wsData.chaosEvents.map((e, i) => ({
                id: String(e.id || `chaos-${i}`),
                type: String(e.type || e.action || "Unknown"),
                severity: severityFromAction(String(e.type || e.action || "")),
                description: String(e.description || ""),
                timestamp: String(e.timestamp || new Date().toISOString()),
            }));
            setChaosEvents(parsed);
        }
    }, [wsData]);

    // Demo mode: simulate live updates
    useEffect(() => {
        if (mode !== "demo") return;

        const interval = setInterval(() => {
            setMetrics((prev) => {
                const next = [...prev.slice(1)];
                const t = new Date();
                next.push({
                    time: t.toLocaleTimeString("en-US", {
                        hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit",
                    }),
                    tps: Math.floor(Math.random() * 200 + 400),
                    latency: Math.floor(Math.random() * 20 + 8),
                    replLag: Math.floor(Math.random() * 5),
                    cpu: Math.floor(Math.random() * 30 + 25),
                    memory: Math.floor(Math.random() * 15 + 55),
                });
                return next;
            });

            if (Math.random() > 0.4) {
                const ops: Transaction["operation"][] = ["DEPOSIT", "WITHDRAW", "TRANSFER"];
                const newTx: Transaction = {
                    id: `TXN-${Math.random().toString(36).substring(2, 10).toUpperCase()}`,
                    account: `ACC-${Math.random().toString(36).substring(2, 8).toUpperCase()}`,
                    operation: ops[Math.floor(Math.random() * ops.length)],
                    amount: Math.round((Math.random() * 500 + 1) * 100) / 100,
                    status: Math.random() > 0.15 ? "COMMITTED" : Math.random() > 0.5 ? "PENDING" : "FAILED",
                    leader: `node-${leaderNode}`,
                    latency: Math.floor(Math.random() * 45) + 5,
                    timestamp: new Date().toISOString(),
                };
                setTransactions((prev) => [newTx, ...prev.slice(0, 24)]);
            }
        }, 3000);

        return () => clearInterval(interval);
    }, [mode, leaderNode]);

    return {
        nodes,
        invariants,
        transactions,
        metrics,
        chaosEvents,
        logs,
        leaderNode,
        eventCount,
        connected: wsConnected,
        mode,
        tps,
        avgLatency,
        clusterStats,
    };
}

function mapEventType(eventType: string): Transaction["operation"] {
    if (eventType.includes("Deposit") || eventType.includes("Created")) return "DEPOSIT";
    if (eventType.includes("Withdraw")) return "WITHDRAW";
    if (eventType.includes("Transfer")) return "TRANSFER";
    return "DEPOSIT";
}

function severityFromAction(action: string): ChaosEvent["severity"] {
    if (action.includes("PARTITION") || action.includes("DROP")) return "critical";
    if (action.includes("SLOW") || action.includes("FLAP") || action.includes("THROTTLE"))
        return "warning";
    if (action.includes("HEAL") || action.includes("SETUP")) return "success";
    return "info";
}
