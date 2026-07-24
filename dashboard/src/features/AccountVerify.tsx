import { useState } from "react";
import { Search, Loader2, CheckCircle2, AlertTriangle, XCircle } from "lucide-react";
import { fetchAccountFromNode, type AccountView } from "../services/api";
import { C, tint } from "../theme";

interface NodeRes { acct?: AccountView; error?: string; loading: boolean }

const NODE_COLOR = [C.blue, C.green, C.violet];

type Verdict = { color: string; icon: typeof CheckCircle2; title: string; detail: string } | null;

/**
 * Reads the SAME account from all three nodes and compares balance + version.
 * Version is shown alongside balance so normal replication lag (a follower a
 * few entries behind) reads differently from true divergence (same version,
 * different money).
 */
export function AccountVerify() {
    const [id, setId] = useState("");
    const [res, setRes] = useState<Record<number, NodeRes>>({});
    const [ran, setRan] = useState(false);

    async function verify() {
        const acct = id.trim();
        if (!acct) return;
        setRan(true);
        setRes({ 1: { loading: true }, 2: { loading: true }, 3: { loading: true } });
        await Promise.all([1, 2, 3].map(async (n) => {
            try {
                const a = await fetchAccountFromNode(n, acct);
                setRes((p) => ({ ...p, [n]: { acct: a, loading: false } }));
            } catch (e: any) {
                setRes((p) => ({ ...p, [n]: { error: e.message || "Unreachable", loading: false } }));
            }
        }));
    }

    const found = [1, 2, 3].map((n) => res[n]?.acct).filter((a): a is AccountView => !!a);
    const busy = [1, 2, 3].some((n) => res[n]?.loading);
    const maxVersion = found.length ? Math.max(...found.map((a) => a.version)) : 0;

    const verdict: Verdict = (() => {
        if (busy || found.length < 2) return null;
        const balances = found.map((a) => Number(a.balance));
        const versions = found.map((a) => a.version);
        const sameMoney = balances.every((b) => Math.abs(b - balances[0]) < 0.005);
        const sameVersion = versions.every((v) => v === versions[0]);

        if (sameMoney && sameVersion) {
            return { color: C.green, icon: CheckCircle2, title: "All nodes agree", detail: `Identical balance at version ${versions[0]} on ${found.length} node(s).` };
        }
        if (sameMoney) {
            return { color: C.green, icon: CheckCircle2, title: "Balances agree", detail: "Same money on every node; versions differ only because a replica is still applying entries." };
        }
        if (!sameVersion) {
            return { color: C.amber, icon: AlertTriangle, title: "Replication lag", detail: "Balances differ but so do versions — a replica is behind and should converge. Re-check in a moment." };
        }
        return { color: C.red, icon: XCircle, title: "Divergence detected", detail: "Nodes report different balances at the SAME version. This is a real consistency violation, not lag." };
    })();

    return (
        <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2.5">
                <input
                    value={id}
                    onChange={(e) => setId(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter") verify(); }}
                    placeholder="Paste an account ID to compare across all 3 nodes"
                    className="min-w-[280px] flex-1 rounded-lg px-3 py-2.5 font-mono text-[12px] focus:outline-none"
                    style={{ background: "#101d34", border: `1px solid ${C.border}`, color: C.text }}
                />
                <button
                    onClick={verify}
                    disabled={busy || !id.trim()}
                    className="flex cursor-pointer items-center gap-1.5 rounded-lg px-4 py-2.5 text-[12px] font-semibold transition-opacity disabled:cursor-not-allowed disabled:opacity-30"
                    style={{ background: tint(C.blue, 0.14), color: C.blue }}
                >
                    {busy ? <Loader2 size={14} className="animate-spin" /> : <Search size={14} />}
                    Verify across nodes
                </button>
            </div>

            {verdict && (
                <div className="flex items-start gap-2.5 rounded-xl p-3.5" style={{ background: tint(verdict.color, 0.10), border: `1px solid ${tint(verdict.color, 0.30)}` }}>
                    <verdict.icon size={16} style={{ color: verdict.color }} className="mt-0.5 shrink-0" />
                    <div>
                        <div className="text-[13px] font-bold" style={{ color: verdict.color }}>{verdict.title}</div>
                        <div className="mt-0.5 text-[11px]" style={{ color: C.textSoft }}>{verdict.detail}</div>
                    </div>
                </div>
            )}

            {ran && (
                <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                    {[1, 2, 3].map((n) => {
                        const r = res[n];
                        const c = NODE_COLOR[n - 1];
                        const behind = r?.acct ? maxVersion - r.acct.version : 0;
                        return (
                            <div key={n} className="rounded-xl p-4" style={{ background: "rgba(140,165,210,0.05)", border: `1px solid ${tint(r?.error ? C.red : c, 0.22)}` }}>
                                <div className="mb-3 flex items-center gap-2">
                                    <span className="h-2 w-2 rounded-full" style={{ background: r?.error ? C.red : c }} />
                                    <span className="text-[10px] font-semibold uppercase tracking-[0.12em]" style={{ color: C.dim }}>Node-{n}</span>
                                    {behind > 0 && (
                                        <span className="ml-auto rounded-full px-2 py-0.5 text-[9px] font-bold" style={{ background: tint(C.amber, 0.16), color: C.amber }}>
                                            {behind} behind
                                        </span>
                                    )}
                                </div>

                                {r?.loading && <div className="flex items-center gap-2 text-[12px]" style={{ color: C.dim }}><Loader2 size={12} className="animate-spin" /> Reading…</div>}
                                {r?.error && <div className="text-[12px] font-medium" style={{ color: C.red }}>{r.error}</div>}
                                {r?.acct && (
                                    <div className="space-y-2">
                                        <Row label="Balance" value={`$${Number(r.acct.balance).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`} color={C.green} />
                                        <Row label="Version" value={`v${r.acct.version}`} />
                                        <Row label="Status" value={r.acct.status} />
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
