import { useEffect, useState } from "react";
import { Clock } from "lucide-react";
import { C, tint } from "../theme";

interface Props {
    title: string;
    leaderNode: number;
    term: number;
    mode: "live" | "demo";
}

export function Topbar({ title, leaderNode, term, mode }: Props) {
    const [now, setNow] = useState(() => new Date().toLocaleTimeString("en-US", { hour12: false }));
    useEffect(() => {
        const t = setInterval(() => setNow(new Date().toLocaleTimeString("en-US", { hour12: false })), 1000);
        return () => clearInterval(t);
    }, []);

    const live = mode === "live";

    return (
        <header
            className="sticky top-0 z-30 flex h-14 shrink-0 items-center gap-4 px-8"
            style={{ background: "rgba(14,26,48,0.82)", backdropFilter: "blur(14px)", borderBottom: `1px solid ${C.border}` }}
        >
            <h1 className="text-[15px] font-bold tracking-tight" style={{ color: C.text }}>{title}</h1>

            <span
                className="flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[10px] font-bold uppercase tracking-wider"
                style={{ background: tint(live ? C.green : C.amber, 0.16), color: live ? C.green : C.amber }}
            >
                <span className="anim-pulse h-1.5 w-1.5 rounded-full" style={{ background: live ? C.green : C.amber }} />
                {live ? "Live" : "Demo"}
            </span>

            <div className="ml-auto flex items-center gap-5 text-[12px]">
                <Meta label="Leader" value={`Node-${leaderNode}`} color={C.green} dot />
                <Divider />
                <Meta label="Term" value={String(term)} color={C.blue} />
                <Divider />
                <div className="flex items-center gap-1.5" style={{ color: C.dim }}>
                    <Clock size={13} />
                    <span className="font-mono tabular-nums">{now}</span>
                </div>
            </div>
        </header>
    );
}

function Meta({ label, value, color, dot }: { label: string; value: string; color: string; dot?: boolean }) {
    return (
        <div className="flex items-center gap-1.5">
            {dot && <span className="h-1.5 w-1.5 rounded-full" style={{ background: color }} />}
            <span style={{ color: C.dim }}>{label}</span>
            <span className="font-semibold" style={{ color }}>{value}</span>
        </div>
    );
}

function Divider() {
    return <span className="h-4 w-px" style={{ background: C.border }} />;
}
