import type { ReactNode } from "react";
import { C } from "../theme";

interface CardProps {
    title?: string;
    right?: ReactNode;
    accent?: string;
    children: ReactNode;
    className?: string;
    pad?: boolean;
}

export function Card({ title, right, accent, children, className = "", pad = true }: CardProps) {
    return (
        <section
            className={`rounded-2xl border overflow-hidden ${className}`}
            style={{ background: C.surface, borderColor: C.border }}
        >
            {(title || right) && (
                <header
                    className="flex items-center gap-3 px-6 py-4"
                    style={{ borderBottom: `1px solid ${C.border}` }}
                >
                    {accent && <span className="h-4 w-1 rounded-full shrink-0" style={{ background: accent }} />}
                    {title && (
                        <h2 className="text-[11px] font-semibold uppercase tracking-[0.14em]" style={{ color: C.muted }}>
                            {title}
                        </h2>
                    )}
                    {right && <div className="ml-auto flex items-center">{right}</div>}
                </header>
            )}
            <div className={pad ? "p-6" : ""}>{children}</div>
        </section>
    );
}
