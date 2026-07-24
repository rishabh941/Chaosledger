import { C } from "../theme";

export function Gauge({ value, label }: { value: number; label: string }) {
    const color = value >= 95 ? C.green : value >= 80 ? C.amber : C.red;
    const arc = 251.33;
    const filled = (Math.min(100, Math.max(0, value)) / 100) * arc;

    return (
        <div className="flex flex-col items-center py-2">
            <svg width="184" height="112" viewBox="0 0 200 120">
                <path d="M20 100 A80 80 0 0 1 180 100" fill="none" stroke="rgba(140,165,210,0.10)" strokeWidth="9" strokeLinecap="round" />
                <path
                    d="M20 100 A80 80 0 0 1 180 100"
                    fill="none" stroke={color} strokeWidth="9" strokeLinecap="round"
                    strokeDasharray={`${filled} ${arc}`}
                    className="transition-all duration-700"
                />
                <text x="100" y="84" textAnchor="middle" fill={C.text} fontSize="30" fontWeight="800" fontFamily="Inter, sans-serif">
                    {value}%
                </text>
            </svg>
            <div className="mt-1 text-[11px] font-semibold uppercase tracking-[0.14em]" style={{ color: C.dim }}>
                {label}
            </div>
        </div>
    );
}
