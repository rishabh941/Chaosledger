import { Zap } from "lucide-react";
import type { ChaosEvent } from "../types/dashboard";
import { C, tint, SEVERITY } from "../theme";
import { Card } from "../ui/Card";

export function ChaosFeed({ events }: { events: ChaosEvent[] }) {
    return (
        <Card title="Chaos Events" accent={C.amber}>
            <div className="space-y-1">
                {events.length === 0 && <div className="py-8 text-center text-[12px]" style={{ color: C.dim }}>No chaos events recorded</div>}
                {events.slice(0, 12).map((e) => {
                    const color = SEVERITY[e.severity] || C.blue;
                    return (
                        <div key={e.id} className="flex items-start gap-3 rounded-xl px-3 py-3 transition-colors hover:bg-white/[0.02]">
                            <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg" style={{ background: tint(color, 0.14), color }}>
                                <Zap size={15} />
                            </div>
                            <div className="min-w-0 flex-1">
                                <div className="flex items-center gap-2">
                                    <span className="text-[13px] font-semibold" style={{ color: C.text }}>{e.type}</span>
                                    <span className="rounded-full px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider" style={{ background: tint(color, 0.16), color }}>{e.severity}</span>
                                </div>
                                <div className="mt-0.5 text-[12px]" style={{ color: C.muted }}>{e.description}</div>
                                <div className="mt-0.5 font-mono text-[10px]" style={{ color: C.dim }}>{new Date(e.timestamp).toLocaleTimeString("en-US", { hour12: false })}</div>
                            </div>
                        </div>
                    );
                })}
            </div>
        </Card>
    );
}
