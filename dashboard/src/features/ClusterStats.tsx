import type { ClusterStat } from "../hooks/useDashboardData";
import { C } from "../theme";

export function ClusterStats({ stats }: { stats: ClusterStat[] }) {
    if (!stats.length) return null;
    return (
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
            {stats.map((s) => (
                <div key={s.label} className="rounded-2xl border p-5 text-center" style={{ background: C.surface, borderColor: C.border }}>
                    <div className="text-[30px] font-extrabold leading-none tabular-nums" style={{ color: s.color }}>{s.value.toLocaleString()}</div>
                    <div className="mt-2 text-[10px] font-semibold uppercase tracking-wider" style={{ color: C.dim }}>{s.label}</div>
                </div>
            ))}
        </div>
    );
}
