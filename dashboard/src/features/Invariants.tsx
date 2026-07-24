import { CheckCircle2, XCircle } from "lucide-react";
import type { InvariantResult } from "../types/dashboard";
import { C, tint } from "../theme";
import { Card } from "../ui/Card";

export function Invariants({ invariants }: { invariants: InvariantResult[] }) {
    const passed = invariants.filter((i) => i.status === "PASSED").length;
    const pct = invariants.length ? Math.round((passed / invariants.length) * 100) : 100;

    return (
        <Card title="Invariant Monitor" accent={C.green}>
            <div className="mb-5 flex items-center gap-4 pb-5" style={{ borderBottom: `1px solid ${C.border}` }}>
                <div className="text-[40px] font-extrabold leading-none tabular-nums" style={{ color: pct === 100 ? C.green : C.amber }}>{pct}%</div>
                <div>
                    <div className="text-[13px] font-semibold" style={{ color: C.text }}>{passed}/{invariants.length} invariants passing</div>
                    <div className="text-[11px]" style={{ color: C.dim }}>Continuously verified against the event log</div>
                </div>
            </div>

            <div className="grid grid-cols-1 gap-x-8 lg:grid-cols-2">
                {invariants.map((inv) => {
                    const ok = inv.status === "PASSED";
                    return (
                        <div key={inv.id} className="flex items-center gap-3 py-2.5" style={{ borderBottom: `1px solid ${tint("#8ca5d2", 0.06)}` }}>
                            {ok ? <CheckCircle2 size={16} style={{ color: C.green }} className="shrink-0" /> : <XCircle size={16} style={{ color: C.red }} className="shrink-0" />}
                            <div className="min-w-0 flex-1">
                                <div className="truncate text-[12px] font-semibold" style={{ color: C.text }}>{inv.name}</div>
                                <div className="truncate text-[10px]" style={{ color: C.dim }}>{inv.description}</div>
                            </div>
                            <span className="rounded-full px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider" style={{ background: tint(ok ? C.green : C.red, 0.16), color: ok ? C.green : C.red }}>
                                {ok ? "Pass" : "Fail"}
                            </span>
                        </div>
                    );
                })}
            </div>
        </Card>
    );
}
