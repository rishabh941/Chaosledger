import type { LogEntry } from "../types/dashboard";
import { C, LOG_LEVEL } from "../theme";
import { Card } from "../ui/Card";

export function LogTerminal({ logs }: { logs: LogEntry[] }) {
    return (
        <Card title="System Logs" pad={false}>
            <div className="max-h-[420px] overflow-y-auto p-5 font-mono text-[12px] leading-relaxed" style={{ background: "#0b1526" }}>
                {logs.length === 0 && <div className="py-8 text-center" style={{ color: C.dim }}>Waiting for log output…</div>}
                {logs.map((l, i) => {
                    const color = LOG_LEVEL[l.level] || C.dim;
                    return (
                        <div key={i} className="flex gap-3 py-0.5">
                            <span className="shrink-0 tabular-nums" style={{ color: C.dim }}>{l.time}</span>
                            <span className="w-12 shrink-0 font-bold" style={{ color }}>{l.level}</span>
                            <span style={{ color: C.textSoft }}>{l.message}</span>
                        </div>
                    );
                })}
            </div>
        </Card>
    );
}
