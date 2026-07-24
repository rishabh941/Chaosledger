import {
    LineChart, Line, AreaChart, Area, BarChart, Bar,
    XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from "recharts";
import type { MetricPoint } from "../types/dashboard";
import { C, tint } from "../theme";
import { Card } from "../ui/Card";

const tick = { fill: C.dim, fontSize: 10 };
const axis = { stroke: C.border };

function TT({ active, payload, label }: any) {
    if (!active || !payload?.length) return null;
    return (
        <div className="rounded-lg border px-3 py-2 text-[12px]" style={{ background: C.surfaceHi, borderColor: C.borderHi }}>
            <div className="mb-1" style={{ color: C.muted }}>{label}</div>
            {payload.map((p: any, i: number) => (
                <div key={i} className="font-semibold" style={{ color: p.color }}>{p.name}: {p.value}</div>
            ))}
        </div>
    );
}

export function Charts({ data }: { data: MetricPoint[] }) {
    return (
        <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
            <Card title="Transactions / sec">
                <ResponsiveContainer width="100%" height={190}>
                    <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -18 }}>
                        <CartesianGrid strokeDasharray="3 3" stroke={C.border} />
                        <XAxis dataKey="time" tick={tick} axisLine={axis} tickLine={false} minTickGap={40} />
                        <YAxis tick={tick} axisLine={axis} tickLine={false} />
                        <Tooltip content={<TT />} />
                        <Line type="monotone" dataKey="tps" name="TPS" stroke={C.blue} strokeWidth={2.5} dot={false} animationDuration={600} />
                    </LineChart>
                </ResponsiveContainer>
            </Card>

            <Card title="Commit latency (ms)">
                <ResponsiveContainer width="100%" height={190}>
                    <AreaChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -18 }}>
                        <CartesianGrid strokeDasharray="3 3" stroke={C.border} />
                        <XAxis dataKey="time" tick={tick} axisLine={axis} tickLine={false} minTickGap={40} />
                        <YAxis tick={tick} axisLine={axis} tickLine={false} />
                        <Tooltip content={<TT />} />
                        <Area type="monotone" dataKey="latency" name="Latency" stroke={C.amber} fill={tint(C.amber, 0.16)} strokeWidth={2.5} animationDuration={600} />
                    </AreaChart>
                </ResponsiveContainer>
            </Card>

            <Card title="Replication lag">
                <ResponsiveContainer width="100%" height={190}>
                    <BarChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -18 }}>
                        <CartesianGrid strokeDasharray="3 3" stroke={C.border} />
                        <XAxis dataKey="time" tick={tick} axisLine={axis} tickLine={false} minTickGap={40} />
                        <YAxis tick={tick} axisLine={axis} tickLine={false} />
                        <Tooltip content={<TT />} />
                        <Bar dataKey="replLag" name="Lag" fill={C.violet} radius={[3, 3, 0, 0]} animationDuration={600} />
                    </BarChart>
                </ResponsiveContainer>
            </Card>

            <Card title="CPU & memory">
                <ResponsiveContainer width="100%" height={190}>
                    <AreaChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -18 }}>
                        <CartesianGrid strokeDasharray="3 3" stroke={C.border} />
                        <XAxis dataKey="time" tick={tick} axisLine={axis} tickLine={false} minTickGap={40} />
                        <YAxis tick={tick} axisLine={axis} tickLine={false} domain={[0, 100]} />
                        <Tooltip content={<TT />} />
                        <Legend wrapperStyle={{ fontSize: 11 }} />
                        <Area type="monotone" dataKey="cpu" name="CPU %" stroke={C.blue} fill={tint(C.blue, 0.14)} strokeWidth={2} animationDuration={600} />
                        <Area type="monotone" dataKey="memory" name="Memory %" stroke={C.green} fill={tint(C.green, 0.14)} strokeWidth={2} animationDuration={600} />
                    </AreaChart>
                </ResponsiveContainer>
            </Card>
        </div>
    );
}
