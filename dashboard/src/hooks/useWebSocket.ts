import { useState, useEffect, useRef, useCallback } from "react";

/**
 * WebSocket message shape — matches DashboardDto.java on the backend.
 * This is what arrives on /topic/dashboard every 2 seconds.
 */
export interface DashboardMessage {
    raftNodes: Array<{
        nodeId: string;
        role: string;
        leaderId: string;
        term: number;
        commitIndex: number;
        logIndex: number;
    }>;
    invariantStatus: Record<string, unknown>;
    invariants: Array<{
        name: string;
        status: string;
        message: string;
        checkedAt: string | null;
        durationMs: number;
    }>;
    eventCount: number;
    recentEvents: Array<Record<string, unknown>>;
    chaosEvents: Array<Record<string, unknown>>;
    hlcStatus: Record<string, unknown>;
    metrics: {
        tps: number;
        totalEvents: number;
        memoryUsedMb: number;
        memoryTotalMb: number;
        memoryPercent: number;
        availableProcessors: number;
    } | null;
    timestamp: string;
}

/**
 * Hand-rolled STOMP-over-WebSocket client, used instead of @stomp/stompjs to
 * avoid 40KB of bundle for what amounts to newline-delimited text frames.
 * Accepts multiple URLs and rotates through them to fail over between nodes.
 */
export function useWebSocket(urls: string[]) {
    const [data, setData] = useState<DashboardMessage | null>(null);
    const [connected, setConnected] = useState(false);
    const wsRef = useRef<WebSocket | null>(null);
    const reconnectTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
    const urlIndex = useRef(0);

    const connect = useCallback(() => {
        if (wsRef.current?.readyState === WebSocket.OPEN) return;

        const url = urls[urlIndex.current % urls.length];

        try {
            const ws = new WebSocket(url);
            wsRef.current = ws;

            ws.onopen = () => {
                ws.send("CONNECT\naccept-version:1.2\nheart-beat:10000,10000\n\n\0");
            };

            ws.onmessage = (event) => {
                const raw = event.data as string;

                if (raw.startsWith("CONNECTED")) {
                    setConnected(true);
                    ws.send(
                        "SUBSCRIBE\nid:sub-0\ndestination:/topic/dashboard\n\n\0"
                    );
                    return;
                }

                if (raw.startsWith("MESSAGE")) {
                    const bodyStart = raw.indexOf("\n\n");
                    if (bodyStart === -1) return;
                    const body = raw.substring(bodyStart + 2).replace(/\0$/, "");
                    try {
                        const parsed: DashboardMessage = JSON.parse(body);
                        setData(parsed);
                    } catch {
                        // Not valid JSON — heartbeat or other frame
                    }
                    return;
                }
            };

            ws.onclose = () => {
                setConnected(false);
                urlIndex.current = (urlIndex.current + 1) % urls.length;
                reconnectTimer.current = setTimeout(connect, 2000);
            };

            ws.onerror = () => {
                ws.close();
            };
        } catch {
            urlIndex.current = (urlIndex.current + 1) % urls.length;
            reconnectTimer.current = setTimeout(connect, 2000);
        }
    }, [urls]);

    useEffect(() => {
        connect();
        return () => {
            clearTimeout(reconnectTimer.current);
            wsRef.current?.close();
        };
    }, [connect]);

    return { data, connected };
}
