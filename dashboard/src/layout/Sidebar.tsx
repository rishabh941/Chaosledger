import type { LucideIcon } from "lucide-react";
import {
    ShieldCheck, LayoutDashboard, ArrowLeftRight, Network, Zap, Activity, Terminal,
} from "lucide-react";
import type { PageId } from "../types/dashboard";
import { C, tint } from "../theme";

const NAV: { id: PageId; label: string; icon: LucideIcon }[] = [
    { id: "dashboard", label: "Dashboard", icon: LayoutDashboard },
    { id: "transactions", label: "Transactions", icon: ArrowLeftRight },
    { id: "cluster", label: "Cluster", icon: Network },
    { id: "chaos", label: "Chaos", icon: Zap },
    { id: "metrics", label: "Metrics", icon: Activity },
    { id: "logs", label: "Logs", icon: Terminal },
];

interface Props {
    active: PageId;
    onNavigate: (p: PageId) => void;
    healthyNodes: number;
}

export function Sidebar({ active, onNavigate, healthyNodes }: Props) {
    return (
        <aside
            className="flex w-[248px] shrink-0 flex-col"
            style={{ background: "#101d34", borderRight: `1px solid ${C.border}` }}
        >
            {/* Brand */}
            <div className="flex items-center gap-3 px-6 py-6">
                <div
                    className="flex h-11 w-11 items-center justify-center rounded-xl"
                    style={{ background: "linear-gradient(135deg,#33d6a0,#12b981)", boxShadow: "0 6px 18px rgba(51,214,160,0.30)" }}
                >
                    <ShieldCheck size={22} className="text-white" strokeWidth={2.2} />
                </div>
                <div>
                    <div className="text-[16px] font-bold leading-tight tracking-tight" style={{ color: C.text }}>
                        ChaosLedger
                    </div>
                    <div className="text-[10px] font-semibold uppercase tracking-[0.18em]" style={{ color: C.dim }}>
                        Trust Dashboard
                    </div>
                </div>
            </div>

            <div className="mx-6 h-px" style={{ background: C.border }} />

            {/* Nav */}
            <nav className="flex-1 space-y-1 px-4 py-5">
                {NAV.map(({ id, label, icon: Icon }) => {
                    const on = active === id;
                    return (
                        <button
                            key={id}
                            onClick={() => onNavigate(id)}
                            className="relative flex w-full items-center gap-3 rounded-xl px-4 py-2.5 text-left text-[14px] transition-colors duration-150"
                            style={
                                on
                                    ? { background: tint(C.blue, 0.16), color: "#dbeafe", fontWeight: 600, boxShadow: `inset 3px 0 0 ${C.blue}` }
                                    : { color: C.muted, fontWeight: 500 }
                            }
                            onMouseEnter={(e) => { if (!on) { e.currentTarget.style.background = "rgba(140,165,210,0.08)"; e.currentTarget.style.color = C.text; } }}
                            onMouseLeave={(e) => { if (!on) { e.currentTarget.style.background = ""; e.currentTarget.style.color = C.muted; } }}
                        >
                            <Icon size={19} strokeWidth={on ? 2.4 : 1.9} style={{ color: on ? C.blue : "currentColor" }} />
                            {label}
                        </button>
                    );
                })}
            </nav>

            {/* Health footer */}
            <div className="px-6 py-5" style={{ borderTop: `1px solid ${C.border}` }}>
                <div className="mb-2.5 text-[10px] font-semibold uppercase tracking-[0.18em]" style={{ color: C.dim }}>
                    Cluster Health
                </div>
                <div className="flex gap-1.5">
                    {[1, 2, 3].map((n) => (
                        <div
                            key={n}
                            className="h-1.5 flex-1 rounded-full"
                            style={{ background: n <= healthyNodes ? C.green : "rgba(140,165,210,0.16)" }}
                        />
                    ))}
                </div>
                <div className="mt-2.5 text-[12px] font-medium" style={{ color: C.textSoft }}>
                    {healthyNodes}/3 nodes online
                </div>
            </div>
        </aside>
    );
}
