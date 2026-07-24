/**
 * ChaosLedger design tokens — professional navy observability theme.
 * Single source of truth for colors used in inline styles.
 * Tailwind utility equivalents live in index.css (@theme).
 */
export const C = {
    bg: "#0e1a30",
    surface: "#16233d",
    surfaceHi: "#1d2c49",
    surfaceHover: "#233458",

    border: "rgba(140,165,210,0.14)",
    borderHi: "rgba(140,165,210,0.26)",

    text: "#f3f6fc",
    textSoft: "#c4d0e4",
    muted: "#93a2c0",
    dim: "#65758f",

    blue: "#4f9cf9",
    green: "#33d6a0",
    amber: "#f6b93b",
    red: "#f4726f",
    violet: "#a78bfa",
    cyan: "#38bdf8",
} as const;

/** low-opacity tint of an accent for chip/badge backgrounds */
export const tint = (hex: string, alpha = 0.14) => {
    const n = parseInt(hex.slice(1), 16);
    const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    return `rgba(${r},${g},${b},${alpha})`;
};

export const SEVERITY: Record<string, string> = {
    critical: C.red,
    warning: C.amber,
    info: C.blue,
    success: C.green,
};

export const TX_STATUS: Record<string, string> = {
    COMMITTED: C.green,
    PENDING: C.amber,
    FAILED: C.red,
};

export const LOG_LEVEL: Record<string, string> = {
    INFO: C.blue,
    WARN: C.amber,
    ERROR: C.red,
    DEBUG: C.dim,
};
