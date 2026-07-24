import type { LucideIcon } from "lucide-react";
import { C, tint } from "../theme";

interface StatCardProps {
    icon: LucideIcon;
    label: string;
    value: string | number;
    suffix?: string;
    color?: string;
    hint?: string;
    live?: boolean;
}

export function StatCard({ icon: Icon, label, value, suffix, color = C.blue, hint, live }: StatCardProps) {
    return (
        <div
            className="group relative rounded-2xl border p-5 transition-all duration-200 hover:-translate-y-0.5"
            style={{ background: C.surface, borderColor: C.border }}
            onMouseEnter={(e) => {
                e.currentTarget.style.borderColor = tint(color, 0.5);
                e.currentTarget.style.boxShadow = `0 12px 28px -12px ${tint(color, 0.55)}`;
            }}
            onMouseLeave={(e) => {
                e.currentTarget.style.borderColor = C.border;
                e.currentTarget.style.boxShadow = "";
            }}
        >
            <div className="flex items-start justify-between">
                <div
                    className="flex h-11 w-11 items-center justify-center rounded-xl"
                    style={{ background: tint(color, 0.14), color }}
                >
                    <Icon size={20} strokeWidth={2} />
                </div>
                {live && <span className="anim-pulse h-2 w-2 rounded-full" style={{ background: color }} />}
            </div>

            <div className="mt-4 flex items-baseline gap-1">
                <span className="text-[28px] font-bold leading-none tracking-tight tabular-nums" style={{ color: C.text }}>
                    {value}
                </span>
                {suffix && <span className="text-sm font-semibold" style={{ color: C.muted }}>{suffix}</span>}
            </div>

            <div className="mt-1.5 text-[11px] font-semibold uppercase tracking-wider" style={{ color: C.dim }}>
                {label}
            </div>
            {hint && <div className="mt-1 text-[11px]" style={{ color: C.muted }}>{hint}</div>}
        </div>
    );
}
