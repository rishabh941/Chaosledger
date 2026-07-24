import { useState } from "react";
import { Database, Loader2, CheckCircle2, AlertTriangle } from "lucide-react";
import { fetchNodeSummary, type NodeSummary } from "../services/api";
import { C, tint } from "../theme";

interface Res { summary?: NodeSummary; error?: string; loading: boolean }
const NODE_COLOR = [C.blue, C.green, C.violet];

export function NodeBalances() {
    const [res, setRes] = useState<Record<number, Res>>({});

    async function check(n: number) {
        setRes((p) => ({ ...p, [n]: { loading: true } }));
        try {
            const summary = await fetchNodeSummary(n);
            setRes((p) => ({ ...p, [n]: { summary, loading: false } }));
        } catch (e: any) {
            setRes((p) => ({ ...p, [n]: { error: e.message || "Unreachable", loading: false } }));
        }
    }
    const checkAll = () => Promise.allSettled([1, 2, 3].map(check));

    const balances = [1, 2, 3].map((n) => res[n]?.summary?.totalBalance).filter((b): b is number => b !== undefined);
    const allLoaded = [1, 2, 3].every((n) => res[n]?.summary);
    const inSync = balances.length >= 2 && balances.every((b) => Math.abs(b - balances[0]) < 0.01);
    const hasAny = Object.keys(res).length > 0;

    return (
        <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2.5">
                {[1, 2, 3].map((n) => {
                    const c = NODE_COLOR[n - 1];
                    return (
                        <button
                            key={n}
                            onClick={() => check(n)}
                            disabled={res[n]?.loading}
                            className="flex items-center gap-1.5 rounded-lg px-4 py-2 text-[12px] font-semibold transition-opacity disabled:opacity-40"
                            style={{ background: tint(c, 0.14), color: c }}
                        >
                            {res[n]?.loading ? <Loader2 size={13} className="animate-spin" /> : <Database size={13} />}
                            Node-{n}
                        </button>
                    );
                })}
                <button
                    onClick={checkAll}
                    className="rounded-lg px-4 py-2 text-[12px] font-semibold transition-opacity"
                    style={{ background: tint(C.green, 0.14), color: C.green }}
                >
                    Check all
                </button>
                {allLoaded && (
                    <span className="ml-1 flex items-center gap-1.5 text-[12px] font-semibold" style={{ color: inSync ? C.green : C.amber }}>
                        {inSync ? <CheckCircle2 size={14} /> : <AlertTriangle size={14} />}
                        {inSync ? "All nodes in sync" : "Balance mismatch"}
                    </span>
                )}
            </div>

            {hasAny && (
                <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                    {[1, 2, 3].map((n) => {
                        const r = res[n];
                        const c = NODE_COLOR[n - 1];
                        if (!r) return <div key={n} className="rounded-xl border border-dashed p-4 text-center text-[11px]" style={{ borderColor: C.border, color: C.dim }}>Node-{n} not checked</div>;
                        return (
                            <div key={n} className="rounded-xl p-4" style={{ background: "rgba(140,165,210,0.05)", border: `1px solid ${tint(r.error ? C.red : c, 0.2)}` }}>
                                <div className="mb-3 flex items-center gap-2">
                                    <span className="h-2 w-2 rounded-full" style={{ background: r.error ? C.red : c }} />
                                    <span className="text-[10px] font-semibold uppercase tracking-[0.12em]" style={{ color: C.dim }}>Node-{n}</span>
                                </div>
                                {r.loading && <div className="flex items-center gap-2 text-[12px]" style={{ color: C.dim }}><Loader2 size={12} className="animate-spin" /> Loading…</div>}
                                {r.error && <div className="text-[12px] font-medium" style={{ color: C.red }}>{r.error}</div>}
                                {r.summary && (
                                    <div className="space-y-2">
                                        <Row label="Accounts" value={String(r.summary.totalAccounts)} />
                                        <Row label="Balance" value={`$${Number(r.summary.totalBalance).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`} color={C.green} />
                                        <Row label="Events" value={String(r.summary.totalEvents)} />
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>
            )}
        </div>
    );
}

function Row({ label, value, color }: { label: string; value: string; color?: string }) {
    return (
        <div className="flex justify-between text-[12px]">
            <span style={{ color: C.dim }}>{label}</span>
            <span className="font-mono font-semibold" style={{ color: color || C.text }}>{value}</span>
        </div>
    );
}
