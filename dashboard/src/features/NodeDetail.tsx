import { useState } from "react";
import { Database, Loader2, RefreshCw, X } from "lucide-react";
import type { RaftNodeStatus } from "../types/dashboard";
import { fetchNodeSummary, type NodeSummary } from "../services/api";
import { C, tint } from "../theme";
import { Badge } from "../ui/Badge";

export function NodeDetail({ node, onClose }: { node: RaftNodeStatus; onClose: () => void }) {
    const [summary, setSummary] = useState<NodeSummary | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const num = parseInt(node.nodeId.replace("node-", ""), 10);
    const isLeader = node.role === "LEADER";
    const down = node.role === "UNREACHABLE";
    const color = down ? C.red : isLeader ? C.green : C.blue;

    async function fetchBalance() {
        setLoading(true);
        setError(null);
        try {
            setSummary(await fetchNodeSummary(num));
        } catch (e: any) {
            setError(e.message || "Node unreachable");
        } finally {
            setLoading(false);
        }
    }

    const cells: [string, string, string?][] = [
        ["Role", node.role, color],
        ["Leader", node.leaderId || "—"],
        ["Term", String(node.term)],
        ["Commit", String(node.commitIndex)],
        ["Log", String(node.logIndex)],
        ["Status", down ? "DOWN" : "UP", down ? C.red : C.green],
    ];

    return (
        <div className="anim-rise rounded-2xl border p-5" style={{ background: C.surfaceHi, borderColor: tint(color, 0.35) }}>
            <div className="mb-4 flex items-center gap-2.5">
                <Database size={16} style={{ color }} />
                <span className="text-[14px] font-bold" style={{ color: C.text }}>Node-{num}</span>
                <Badge color={color}>{node.role}</Badge>
                <button onClick={onClose} className="ml-auto rounded-md p-1 transition-colors hover:bg-white/5" style={{ color: C.dim }}>
                    <X size={15} />
                </button>
            </div>

            <div className="mb-4 grid grid-cols-3 gap-2.5 md:grid-cols-6">
                {cells.map(([label, val, c]) => (
                    <div key={label} className="rounded-xl px-3 py-2.5 text-center" style={{ background: "rgba(140,165,210,0.06)" }}>
                        <div className="font-mono text-[13px] font-bold tabular-nums" style={{ color: c || C.text }}>{val}</div>
                        <div className="mt-0.5 text-[9px] font-semibold uppercase tracking-wider" style={{ color: C.dim }}>{label}</div>
                    </div>
                ))}
            </div>

            <div className="flex flex-wrap items-center gap-4">
                <button
                    onClick={fetchBalance}
                    disabled={loading || down}
                    className="flex items-center gap-1.5 rounded-lg px-3.5 py-2 text-[12px] font-semibold transition-opacity disabled:opacity-30"
                    style={{ background: tint(C.blue, 0.14), color: C.blue }}
                >
                    {loading ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />}
                    Fetch balance
                </button>

                {summary && (
                    <div className="flex items-center gap-5 font-mono text-[12px]">
                        <span style={{ color: C.dim }}>Accounts <b style={{ color: C.text }}>{summary.totalAccounts}</b></span>
                        <span style={{ color: C.dim }}>Balance <b style={{ color: C.green }}>${Number(summary.totalBalance).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</b></span>
                        <span style={{ color: C.dim }}>Events <b style={{ color: C.text }}>{summary.totalEvents}</b></span>
                    </div>
                )}
                {error && <span className="text-[12px] font-medium" style={{ color: C.red }}>{error}</span>}
            </div>
        </div>
    );
}
