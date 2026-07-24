export type RaftRole = "LEADER" | "FOLLOWER" | "CANDIDATE" | "NO_GROUP";

export interface RaftNodeStatus {
    nodeId: string;
    role: RaftRole;
    leaderId: string;
    term: number;
    commitIndex: number;
    logIndex: number;
}

export type InvariantStatus = "PASSED" | "FAILED" | "ERROR";

export interface InvariantResult {
    id: string;
    name: string;
    description: string;
    status: InvariantStatus;
    message: string;
    checkedAt: string;
    durationMs: number;
}

export type TxOperation = "DEPOSIT" | "WITHDRAW" | "TRANSFER";
export type TxStatus = "COMMITTED" | "PENDING" | "FAILED";

export interface Transaction {
    id: string;
    account: string;
    operation: TxOperation;
    amount: number;
    status: TxStatus;
    leader: string;
    latency: number;
    timestamp: string;
}

export type ChaosSeverity = "critical" | "warning" | "info" | "success";

export interface ChaosEvent {
    id: string;
    type: string;
    severity: ChaosSeverity;
    description: string;
    timestamp: string;
}

export interface MetricPoint {
    time: string;
    tps: number;
    latency: number;
    replLag: number;
    cpu: number;
    memory: number;
}

export type LogLevel = "INFO" | "WARN" | "ERROR" | "DEBUG";

export interface LogEntry {
    time: string;
    level: LogLevel;
    message: string;
}

export interface TimelineEvent {
    label: string;
    detail: string;
    color: string;
    time: string;
}

export type PageId =
    | "dashboard"
    | "transactions"
    | "cluster"
    | "chaos"
    | "metrics"
    | "logs";
