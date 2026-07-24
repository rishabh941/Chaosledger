import { useState } from "react";
import { Activity, Server, ShieldCheck, Database, Timer, Gauge as GaugeIcon } from "lucide-react";

import { Sidebar } from "./layout/Sidebar";
import { Topbar } from "./layout/Topbar";
import { Card } from "./ui/Card";
import { StatCard } from "./ui/StatCard";
import { Topology } from "./features/Topology";
import { Gauge } from "./features/Gauge";
import { NodeDetail } from "./features/NodeDetail";
import { NodeBalances } from "./features/NodeBalances";
import { AccountVerify } from "./features/AccountVerify";
import { Charts } from "./features/Charts";
import { Invariants } from "./features/Invariants";
import { TransactionTable } from "./features/TransactionTable";
import { ChaosFeed } from "./features/ChaosFeed";
import { LogTerminal } from "./features/LogTerminal";
import { ActionBar } from "./features/ActionBar";
import { ClusterStats } from "./features/ClusterStats";

import { useDashboardData } from "./hooks/useDashboardData";
import { useAnimatedCounter } from "./hooks/useAnimatedCounter";
import type { PageId, RaftNodeStatus } from "./types/dashboard";
import { C } from "./theme";

const TITLES: Record<PageId, string> = {
    dashboard: "Trust Dashboard",
    transactions: "Transactions",
    cluster: "Cluster",
    chaos: "Chaos Engineering",
    metrics: "Metrics",
    logs: "System Logs",
};

export default function App() {
    const [page, setPage] = useState<PageId>("dashboard");
    const [selected, setSelected] = useState<string | null>(null);

    const {
        nodes, invariants, transactions, metrics, chaosEvents, logs,
        leaderNode, eventCount, mode, tps, avgLatency, clusterStats,
    } = useDashboardData();

    const txCount = useAnimatedCounter(eventCount || 0);
    const animTps = useAnimatedCounter(tps);
    const passed = invariants.filter((i) => i.status === "PASSED").length;
    const consensus = invariants.length ? Math.round((passed / invariants.length) * 100) : 100;

    // pad to 3 nodes for topology
    const allNodes: RaftNodeStatus[] = nodes.length === 3 ? nodes : [
        ...nodes,
        ...Array.from({ length: Math.max(0, 3 - nodes.length) }, (_, i) => ({
            nodeId: `node-${nodes.length + i + 1}`, role: "FOLLOWER" as const,
            leaderId: `node-${leaderNode}`, term: nodes[0]?.term || 0,
            commitIndex: nodes[0]?.commitIndex || 0, logIndex: nodes[0]?.logIndex || 0,
        })),
    ];
    const term = allNodes[0]?.term || 0;
    const reachable = allNodes.filter((n) => n.role !== "UNREACHABLE").length;
    const selectedNode = selected ? allNodes.find((n) => n.nodeId === selected) : null;
    const toggle = (id: string) => setSelected((s) => (s === id ? null : id));

    const ClusterView = (
        <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
            <div className="flex flex-col gap-5 lg:col-span-2">
                <Card title="Cluster Topology">
                    <Topology nodes={allNodes} selected={selected} onSelect={toggle} />
                </Card>
                {selectedNode && <NodeDetail node={selectedNode} onClose={() => setSelected(null)} />}
            </div>
            <div className="flex flex-col gap-5">
                <Card title="Consensus Health"><Gauge value={consensus} label="Consensus" /></Card>
                <Card title="Invariant Score">
                    <div className="py-2 text-center">
                        <div className="text-[46px] font-extrabold leading-none tabular-nums" style={{ color: consensus === 100 ? C.green : C.amber }}>{consensus}%</div>
                        <div className="mt-2 text-[12px]" style={{ color: C.dim }}>{passed}/{invariants.length} invariants passing</div>
                    </div>
                </Card>
            </div>
        </div>
    );

    return (
        <div className="flex min-h-screen" style={{ background: C.bg }}>
            <Sidebar active={page} onNavigate={(p) => { setPage(p); setSelected(null); }} healthyNodes={reachable} />

            <div className="flex min-w-0 flex-1 flex-col">
                <Topbar title={TITLES[page]} leaderNode={leaderNode} term={term} mode={mode} />

                <main className="flex flex-1 flex-col gap-6 overflow-y-auto p-8">
                    {page === "dashboard" && (
                        <>
                            <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
                                <StatCard icon={Activity} label="Cluster Status" value="Healthy" color={C.green} live />
                                <StatCard icon={Server} label="Current Leader" value={`Node-${leaderNode}`} color={C.blue} />
                                <StatCard icon={ShieldCheck} label="Consensus" value={consensus} suffix="%" color={consensus >= 95 ? C.green : C.amber} />
                                <StatCard icon={Database} label="Transactions" value={txCount.toLocaleString()} color={C.violet} />
                            </div>
                            <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
                                <StatCard icon={Timer} label="Avg Latency" value={avgLatency > 0 ? avgLatency : "—"} suffix={avgLatency > 0 ? "ms" : ""} color={C.amber} />
                                <StatCard icon={GaugeIcon} label="Events / sec" value={animTps} color={C.green} live />
                                <StatCard icon={Server} label="Reachable Nodes" value={`${reachable}/3`} color={C.blue} />
                                <StatCard icon={ShieldCheck} label="Invariants" value={`${passed}/${invariants.length}`} color={C.green} />
                            </div>

                            {ClusterView}

                            <Card title="Per-Node Balance" accent={C.violet}><NodeBalances /></Card>
                            <Card title="Per-Account Verification" accent={C.blue}><AccountVerify /></Card>
                            <ActionBar leaderNode={leaderNode} />
                            <Charts data={metrics} />
                            <Invariants invariants={invariants} />
                            <ClusterStats stats={clusterStats} />
                        </>
                    )}

                    {page === "transactions" && (
                        <>
                            <ActionBar leaderNode={leaderNode} />
                            <TransactionTable transactions={transactions} />
                        </>
                    )}

                    {page === "cluster" && (
                        <>
                            {ClusterView}
                            <Card title="Per-Node Balance" accent={C.violet}><NodeBalances /></Card>
                            <Card title="Per-Account Verification" accent={C.blue}><AccountVerify /></Card>
                            <ClusterStats stats={clusterStats} />
                        </>
                    )}

                    {page === "chaos" && (
                        <>
                            <ActionBar leaderNode={leaderNode} />
                            <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
                                <ChaosFeed events={chaosEvents} />
                                <LogTerminal logs={logs} />
                            </div>
                            <Invariants invariants={invariants} />
                        </>
                    )}

                    {page === "metrics" && (
                        <>
                            <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
                                <StatCard icon={Database} label="Total Events" value={txCount.toLocaleString()} color={C.blue} />
                                <StatCard icon={GaugeIcon} label="Events / sec" value={animTps} color={C.green} live />
                                <StatCard icon={Timer} label="Avg Latency" value={avgLatency > 0 ? avgLatency : "—"} suffix={avgLatency > 0 ? "ms" : ""} color={C.amber} />
                                <StatCard icon={ShieldCheck} label="Invariants" value={`${passed}/${invariants.length}`} color={C.green} />
                            </div>
                            <Charts data={metrics} />
                            <ClusterStats stats={clusterStats} />
                        </>
                    )}

                    {page === "logs" && <LogTerminal logs={logs} />}

                    <div className="py-4 text-center text-[10px] font-medium uppercase tracking-[0.18em]" style={{ color: C.dim }}>
                        ChaosLedger · Distributed Ledger · {mode === "live" ? "Live cluster" : "Demo mode"}
                    </div>
                </main>
            </div>
        </div>
    );
}
