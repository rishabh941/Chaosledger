import { tint } from "../theme";

export function Badge({ color, children }: { color: string; children: React.ReactNode }) {
    return (
        <span
            className="inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[10px] font-bold uppercase tracking-[0.1em]"
            style={{ background: tint(color, 0.16), color }}
        >
            {children}
        </span>
    );
}

export function Dot({ color, pulse }: { color: string; pulse?: boolean }) {
    return (
        <span
            className={`inline-block h-2 w-2 rounded-full shrink-0 ${pulse ? "anim-pulse" : ""}`}
            style={{ background: color }}
        />
    );
}
