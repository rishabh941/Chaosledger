/**
 * REST API client for ChaosLedger backend.
 *
 * In development, Vite's proxy forwards /api/* to localhost:8080.
 * In Docker production, nginx does the same thing.
 * So all URLs are relative — no hardcoded hosts.
 */

const BASE = "";  // empty = same origin (via proxy)

// When node-1 is paused/partitioned, automatically try node-2 then node-3.
// Vite proxies /api-n2 → localhost:8081, /api-n3 → localhost:8082.
// nginx uses upstream failover natively.
const FAILOVER_SUFFIXES = ['', '-n2', '-n3'];

async function fetchWithFailover(url: string, init?: RequestInit): Promise<Response> {
    if (!url.startsWith('/api/')) return fetch(url, init);
    let lastError: Error | undefined;
    for (const suffix of FAILOVER_SUFFIXES) {
        const target = suffix ? url.replace('/api/', `/api${suffix}/`) : url;
        try {
            const timeout = suffix ? 5000 : 10000;
            const res = await fetch(target, { ...init, signal: AbortSignal.timeout(timeout) });
            if (res.status < 500) return res;
        } catch (e) {
            lastError = e instanceof Error ? e : new Error(String(e));
        }
    }
    throw lastError || new Error(`All nodes unreachable: ${url}`);
}

async function fetchJson<T>(url: string): Promise<T> {
    const res = await fetchWithFailover(url);
    if (!res.ok) {
        throw new Error(`API ${url} returned ${res.status}: ${res.statusText}`);
    }
    return res.json();
}

export interface RaftStatusResponse {
    nodeId: string;
    role: string;
    leaderId: string;
    term: number;
    peers: string[];
    groupId?: string;
    error?: string;
}

export async function fetchRaftStatus(): Promise<RaftStatusResponse> {
    return fetchJson(`${BASE}/api/raft/status`);
}

// All 3 Nodes (via DashboardController)
export async function fetchAllNodes(): Promise<RaftStatusResponse[]> {
    return fetchJson(`${BASE}/api/dashboard/nodes`);
}

export interface DashboardSnapshot {
    nodes: RaftStatusResponse[];
    invariantStatus: {
        invariantCount: number;
        lastRunAt: string | null;
        eventsScanned: number;
        passed: number;
        failed: number;
        errors: number;
        allPassing: boolean;
    };
    invariants: InvariantResponse[];
    eventCount: number;
    timestamp: string;
}

export async function fetchDashboardSnapshot(): Promise<DashboardSnapshot> {
    return fetchJson(`${BASE}/api/dashboard/snapshot`);
}

export interface InvariantResponse {
    name: string;
    status: "PASSED" | "FAILED" | "ERROR";
    message: string;
    checkedAt: string | null;
    duration: { seconds: number; nano: number } | null;
    lastViolation: string | null;
}

export async function fetchInvariants(): Promise<{
    status: Record<string, unknown>;
    invariants: InvariantResponse[];
}> {
    return fetchJson(`${BASE}/api/invariants`);
}

export async function triggerInvariantCheck(): Promise<{
    status: Record<string, unknown>;
    invariants: InvariantResponse[];
}> {
    const res = await fetchWithFailover(`${BASE}/api/invariants/run`, { method: "POST" });
    return res.json();
}

export interface EventResponse {
    eventId: string;
    aggregateId: string;
    eventType: string;
    version: number;
    hlcPhysicalTime: number;
    hlcLogicalCounter: number;
    hlcNodeId: string;
    createdAt: string;
}

export async function fetchRecentEvents(limit = 20): Promise<EventResponse[]> {
    return fetchJson(`${BASE}/api/debug/events?limit=${limit}`);
}

export async function fetchEventCount(): Promise<{ count: number; at: string }> {
    return fetchJson(`${BASE}/api/debug/events/count`);
}

export interface HlcStatusResponse {
    nodeId: string;
    physicalTime: number;
    physicalTimeHuman: string;
    logicalCounter: number;
    wallClock: number;
    wallClockHuman: string;
    drift: number;
    formatted: string;
}

export async function fetchHlcStatus(): Promise<HlcStatusResponse> {
    return fetchJson(`${BASE}/api/hlc/status`);
}

export interface ChaosStatusResponse {
    ready: boolean;
    eventLog: Array<{
        timestamp: string;
        action: string;
        description: string;
    }>;
}

export async function fetchChaosStatus(): Promise<ChaosStatusResponse> {
    return fetchJson(`${BASE}/api/chaos/status`);
}

export async function triggerPartition(nodeId: number): Promise<void> {
    const res = await fetchWithFailover(`${BASE}/api/chaos/partition/${nodeId}`, { method: "POST" });
    if (!res.ok) throw new Error(`Partition failed: ${res.status} (chaos might not be enabled)`);
}

export async function triggerHardPartition(nodeId: number): Promise<void> {
    const res = await fetch(`${BASE}/api/chaos/partition-hard/${nodeId}`, { method: "POST" });
    if (!res.ok) throw new Error(`Hard partition failed: ${res.status} (chaos might not be enabled)`);
}

export async function triggerHeal(): Promise<void> {
    const res = await fetchWithFailover(`${BASE}/api/chaos/heal`, { method: "POST" });
    if (!res.ok) throw new Error(`Heal failed: ${res.status} (chaos might not be enabled)`);
}

export async function transferLeadership(): Promise<{ action: string; from?: string; to?: string; reason?: string }> {
    const res = await fetchWithFailover(`${BASE}/api/raft/transfer-leadership`, { method: "POST" });
    if (!res.ok) {
        const text = await res.text();
        throw new Error(`Transfer leadership failed: ${res.status}: ${text}`);
    }
    return res.json();
}

// Chaos Agent (docker pause/unpause — bypasses Spring Boot)
export async function pauseNode(nodeId: number): Promise<{ action: string; node: number }> {
    const res = await fetch(`/chaos-agent/pause/${nodeId}`, { method: "POST" });
    if (!res.ok) {
        const text = await res.text();
        throw new Error(`Pause failed: ${res.status}: ${text}`);
    }
    return res.json();
}

export async function unpauseNode(nodeId: number): Promise<{ action: string; node: number }> {
    const res = await fetch(`/chaos-agent/unpause/${nodeId}`, { method: "POST" });
    if (!res.ok) {
        const text = await res.text();
        throw new Error(`Unpause failed: ${res.status}: ${text}`);
    }
    return res.json();
}

export async function chaosHealAll(): Promise<{ action: string; containers: Array<{ node: number; result: string }>; toxiproxy_reset: boolean }> {
    const res = await fetch(`/chaos-agent/heal-all`, { method: "POST" });
    if (!res.ok) {
        const text = await res.text();
        throw new Error(`Heal-all failed: ${res.status}: ${text}`);
    }
    return res.json();
}

export async function fetchHealth(): Promise<{ status: string; service: string; timestamp: string }> {
    return fetchJson(`${BASE}/api/health`);
}

async function postJson<T>(url: string, body: unknown): Promise<T> {
    const res = await fetchWithFailover(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
    if (!res.ok) {
        const text = await res.text();
        throw new Error(`API ${url} returned ${res.status}: ${text}`);
    }
    return res.json();
}

export async function createAccount(ownerId: string, currency: string): Promise<{ accountId: string }> {
    return postJson(`${BASE}/api/accounts`, { ownerId, currency });
}

export async function deposit(accountId: string, amount: number): Promise<{ status: string }> {
    return postJson(`${BASE}/api/accounts/${accountId}/deposit`, {
        amount,
        idempotencyKey: crypto.randomUUID(),
    });
}

export async function withdraw(accountId: string, amount: number): Promise<{ status: string }> {
    return postJson(`${BASE}/api/accounts/${accountId}/withdraw`, {
        amount,
        idempotencyKey: crypto.randomUUID(),
    });
}

export async function transfer(fromAccountId: string, toAccountId: string, amount: number): Promise<{ transferId: string }> {
    return postJson(`${BASE}/api/transfers`, {
        fromAccountId,
        toAccountId,
        amount,
        idempotencyKey: crypto.randomUUID(),
    });
}

export async function getAccount(accountId: string): Promise<Record<string, unknown>> {
    return fetchJson(`${BASE}/api/accounts/${accountId}`);
}

const NODE_PREFIXES = ['/api', '/api-n2', '/api-n3'];

async function fetchFromNode<T>(nodeId: number, path: string): Promise<T> {
    const prefix = NODE_PREFIXES[nodeId - 1];
    const url = path.startsWith('/api/') ? path.replace('/api/', `${prefix}/`) : `${prefix}${path}`;
    const res = await fetch(url, { signal: AbortSignal.timeout(5000) });
    if (!res.ok) throw new Error(`Node-${nodeId} returned ${res.status}`);
    return res.json();
}

export interface NodeSummary {
    nodeId: string;
    totalAccounts: number;
    totalBalance: number;
    totalEvents: number;
    timestamp: string;
}

export async function fetchNodeSummary(nodeId: number): Promise<NodeSummary> {
    return fetchFromNode(nodeId, '/api/accounts/summary');
}

// Per-Account Verification (same account, read from each node)
export interface AccountView {
    accountId: string;
    ownerId: string;
    balance: number;
    currency: string;
    status: string;
    version: number;
}

/**
 * Reads one account directly from a specific node (no failover), so the three
 * replicas can be compared. Comparing per-account catches divergence that a
 * ledger-wide total hides: a half-replicated transfer leaves the total equal
 * on both nodes while the individual balances disagree.
 */
export async function fetchAccountFromNode(nodeId: number, accountId: string): Promise<AccountView> {
    return fetchFromNode(nodeId, `/api/accounts/${accountId}`);
}
