import type { Transaction } from "../types/dashboard";
import { C, tint, TX_STATUS } from "../theme";
import { Card } from "../ui/Card";

const OP_COLOR: Record<string, string> = { DEPOSIT: C.green, WITHDRAW: C.amber, TRANSFER: C.violet };

export function TransactionTable({ transactions }: { transactions: Transaction[] }) {
    return (
        <Card title="Live Transaction Stream" pad={false}>
            <div className="overflow-x-auto">
                <table className="w-full text-left text-[12px]">
                    <thead>
                        <tr style={{ color: C.dim }}>
                            {["Transaction", "Account", "Operation", "Amount", "Status", "Leader", "Latency", "Time"].map((h) => (
                                <th key={h} className="px-6 py-3 text-[10px] font-semibold uppercase tracking-wider" style={{ borderBottom: `1px solid ${C.border}` }}>{h}</th>
                            ))}
                        </tr>
                    </thead>
                    <tbody>
                        {transactions.slice(0, 18).map((tx) => {
                            const sc = TX_STATUS[tx.status] || C.dim;
                            const oc = OP_COLOR[tx.operation] || C.blue;
                            return (
                                <tr key={tx.id} className="transition-colors hover:bg-white/[0.02]" style={{ borderBottom: `1px solid ${tint("#8ca5d2", 0.05)}` }}>
                                    <td className="px-6 py-2.5 font-mono" style={{ color: C.blue }}>{tx.id}</td>
                                    <td className="px-6 py-2.5 font-mono" style={{ color: C.muted }}>{tx.account}</td>
                                    <td className="px-6 py-2.5 font-semibold" style={{ color: oc }}>{tx.operation}</td>
                                    <td className="px-6 py-2.5 font-mono font-semibold tabular-nums" style={{ color: C.text }}>${tx.amount.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</td>
                                    <td className="px-6 py-2.5">
                                        <span className="rounded-full px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider" style={{ background: tint(sc, 0.16), color: sc }}>{tx.status}</span>
                                    </td>
                                    <td className="px-6 py-2.5 font-mono" style={{ color: C.muted }}>{tx.leader}</td>
                                    <td className="px-6 py-2.5 font-mono tabular-nums" style={{ color: tx.latency > 35 ? C.amber : C.muted }}>{tx.latency}ms</td>
                                    <td className="px-6 py-2.5 font-mono" style={{ color: C.dim }}>{new Date(tx.timestamp).toLocaleTimeString("en-US", { hour12: false })}</td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
                {transactions.length === 0 && <div className="py-10 text-center text-[12px]" style={{ color: C.dim }}>No transactions yet</div>}
            </div>
        </Card>
    );
}
