import { Server } from "lucide-react";
import type { RaftNodeStatus } from "../types/dashboard";
import { C, tint } from "../theme";

const POS = [
    { x: 50, y: 20 },
    { x: 20, y: 78 },
    { x: 80, y: 78 },
];

function roleColor(role: string) {
    if (role === "LEADER") return C.green;
    if (role === "UNREACHABLE") return C.red;
    return C.blue;
}

interface Props {
    nodes: RaftNodeStatus[];
    selected: string | null;
    onSelect: (id: string) => void;
}

export function Topology({ nodes, selected, onSelect }: Props) {
    const leaderIdx = nodes.findIndex((n) => n.role === "LEADER");
    const followers = [0, 1, 2].filter((i) => i !== leaderIdx);

    const links: [number, number][] = [];
    if (leaderIdx >= 0) followers.forEach((f) => links.push([leaderIdx, f]));

    return (
        <div className="relative h-[300px] w-full">
            <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="absolute inset-0 h-full w-full">
                {links.map(([a, b], i) => (
                    <g key={i}>
                        <line
                            x1={POS[a].x} y1={POS[a].y} x2={POS[b].x} y2={POS[b].y}
                            stroke={tint(C.green, 0.4)} strokeWidth="0.4" strokeDasharray="1.4 1.8"
                            vectorEffect="non-scaling-stroke"
                        >
                            <animate attributeName="stroke-dashoffset" from="0" to="-6" dur={`${2.4 + i * 0.4}s`} repeatCount="indefinite" />
                        </line>
                        <circle r="0.9" fill={C.green}>
                            <animateMotion dur={`${2.8 + i * 0.4}s`} repeatCount="indefinite"
                                path={`M ${POS[a].x} ${POS[a].y} L ${POS[b].x} ${POS[b].y}`} />
                        </circle>
                    </g>
                ))}
            </svg>

            {nodes.map((node, i) => {
                const color = roleColor(node.role);
                const isSel = selected === node.nodeId;
                const isLeader = node.role === "LEADER";
                const num = node.nodeId.replace("node-", "");
                return (
                    <button
                        key={node.nodeId}
                        onClick={() => onSelect(node.nodeId)}
                        className="absolute flex flex-col items-center transition-transform duration-200 hover:scale-105"
                        style={{ left: `${POS[i].x}%`, top: `${POS[i].y}%`, transform: "translate(-50%,-50%)", width: 150 }}
                    >
                        <div
                            className={`flex h-[68px] w-[68px] items-center justify-center rounded-full ${isLeader ? "anim-ring" : ""}`}
                            style={{
                                background: `radial-gradient(circle at 50% 34%, ${tint(color, 0.22)}, ${C.surfaceHi})`,
                                border: `2px solid ${isSel ? color : tint(color, 0.55)}`,
                                boxShadow: isSel ? `0 0 0 4px ${tint(color, 0.14)}` : "0 6px 18px rgba(0,0,0,0.35)",
                            }}
                        >
                            <Server size={26} style={{ color }} strokeWidth={1.8} />
                        </div>

                        <div className="mt-2 text-[13px] font-bold" style={{ color: C.text }}>Node-{num}</div>
                        <div
                            className="mt-1 rounded-full px-2 py-0.5 text-[8px] font-bold uppercase tracking-[0.12em]"
                            style={{ background: tint(color, 0.16), color }}
                        >
                            {node.role}
                        </div>
                        <div className="mt-1.5 flex gap-2 font-mono text-[10px]" style={{ color: C.dim }}>
                            <span>T{node.term}</span>
                            <span>C{node.commitIndex}</span>
                            <span>L{node.logIndex}</span>
                        </div>
                    </button>
                );
            })}
        </div>
    );
}
