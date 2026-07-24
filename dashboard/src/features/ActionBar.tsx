import { useState } from "react";
import {
    Plus, ArrowDownToLine, ArrowUpFromLine, ArrowLeftRight, Loader2, Zap, ShieldCheck,
    Copy, Check, Shuffle, Unplug, HeartPulse,
} from "lucide-react";
import { C, tint } from "../theme";
import { Card } from "../ui/Card";
import {
    createAccount, deposit, withdraw, transfer, getAccount, triggerInvariantCheck,
    triggerPartition, triggerHeal, transferLeadership, pauseNode, chaosHealAll,
} from "../services/api";

interface LogLine { time: string; ok: boolean; msg: string }

const FIELD = "w-full rounded-lg px-3 py-2.5 text-[12px] transition-colors focus:outline-none";
const FIELD_STYLE = { background: "#101d34", border: `1px solid ${C.border}`, color: C.text };
/** native dropdown items need explicit colors or they render dark-on-dark */
const OPTION_STYLE = { background: "#101d34", color: C.text };

function Label({ children }: { children: React.ReactNode }) {
    return (
        <label className="mb-1.5 block text-[10px] font-semibold uppercase tracking-wider" style={{ color: C.dim }}>
            {children}
        </label>
    );
}

function Field({ label, value, onChange, placeholder, mono, type, step, min }: {
    label: string; value: string; onChange: (v: string) => void;
    placeholder?: string; mono?: boolean; type?: string; step?: string; min?: string;
}) {
    return (
        <div>
            <Label>{label}</Label>
            <input
                type={type || "text"} value={value} placeholder={placeholder} step={step} min={min}
                onChange={(e) => onChange(e.target.value)}
                className={`${FIELD} ${mono ? "font-mono" : ""}`}
                style={FIELD_STYLE}
            />
        </div>
    );
}

function Group({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <div>
            <div className="mb-2.5 text-[10px] font-semibold uppercase tracking-[0.12em]" style={{ color: C.dim }}>{title}</div>
            <div className="flex flex-wrap gap-2">{children}</div>
        </div>
    );
}

export function ActionBar({ leaderNode = 1 }: { leaderNode?: number }) {
    const [amount, setAmount] = useState("100.00");
    const [currency, setCurrency] = useState("USD");
    const [accountId, setAccountId] = useState("");
    const [toAccountId, setToAccountId] = useState("");
    const [busy, setBusy] = useState<string | null>(null);
    const [logs, setLogs] = useState<LogLine[]>([]);
    const [accts, setAccts] = useState<string[]>([]);
    const [copied, setCopied] = useState<string | null>(null);

    const log = (ok: boolean, msg: string) =>
        setLogs((p) => [{ time: new Date().toLocaleTimeString("en-US", { hour12: false }), ok, msg }, ...p.slice(0, 29)]);

    const copy = (id: string) => { navigator.clipboard.writeText(id); setCopied(id); setTimeout(() => setCopied(null), 1500); };

    async function run(name: string, fn: () => Promise<void>) {
        setBusy(name);
        try { await fn(); } catch (e: any) { log(false, e.message || "Failed"); } finally { setBusy(null); }
    }

    const createAcct = () => run("create", async () => {
        const res = await createAccount(crypto.randomUUID(), currency);
        setAccountId(res.accountId); setAccts((p) => [res.accountId, ...p]);
        log(true, `Account created: ${res.accountId}`);
    });
    const doDeposit = () => run("deposit", async () => {
        if (!accountId) return log(false, "Select an account first");
        await deposit(accountId, parseFloat(amount)); log(true, `Deposited $${amount}`);
    });
    const doWithdraw = () => run("withdraw", async () => {
        if (!accountId) return log(false, "Select an account first");
        await withdraw(accountId, parseFloat(amount)); log(true, `Withdrew $${amount}`);
    });
    const doTransfer = () => run("transfer", async () => {
        if (!accountId || !toAccountId) return log(false, "Need source + destination account");
        await transfer(accountId, toAccountId, parseFloat(amount)); log(true, `Transferred $${amount}`);
    });
    const checkBal = () => run("balance", async () => {
        if (!accountId) return log(false, "Select an account first");
        const a = await getAccount(accountId); log(true, `Balance: $${a.balance} (${a.currency}) — ${a.status}`);
    });
    const runInv = () => run("inv", async () => {
        const res = await triggerInvariantCheck();
        const p = res.invariants.filter((i) => i.status === "PASSED").length;
        log(true, `Invariants: ${p}/${res.invariants.length} passed`);
    });
    const genLoad = () => run("load", async () => {
        log(true, "Generating load…");
        const a = await createAccount(crypto.randomUUID(), "USD");
        const b = await createAccount(crypto.randomUUID(), "USD");
        setAccts((p) => [a.accountId, b.accountId, ...p]); setAccountId(a.accountId); setToAccountId(b.accountId);
        await new Promise((r) => setTimeout(r, 1500));
        for (let i = 0; i < 5; i++) { const amt = Math.round((50 + Math.random() * 200) * 100) / 100; await deposit(a.accountId, amt); log(true, `Deposit #${i + 1}: $${amt}`); await new Promise((r) => setTimeout(r, 500)); }
        const t = Math.round((10 + Math.random() * 50) * 100) / 100; await transfer(a.accountId, b.accountId, t); log(true, `Transfer $${t} A→B`);
    });
    const changeLeader = () => run("leader", async () => {
        const res = await transferLeadership();
        log(res.action === "transfer_initiated", res.action === "transfer_initiated" ? `Leadership: ${res.from} → ${res.to}` : `Skipped: ${res.reason}`);
    });
    const partitionFollower = () => run("chaos", async () => {
        const f = [1, 2, 3].filter((n) => n !== leaderNode); const t = f[Math.floor(Math.random() * f.length)];
        await triggerPartition(t); log(true, `Node-${t} partitioned`);
    });
    const partitionLeader = () => run("chaos", async () => {
        const res = await pauseNode(leaderNode);
        log(res.action !== "already_paused", res.action === "already_paused" ? `Node-${leaderNode} already paused` : `Node-${leaderNode} paused — election will follow`);
    });
    const heal = () => run("heal", async () => {
        try { const r = await chaosHealAll(); log(true, `Healed — Toxiproxy ${r.toxiproxy_reset ? "reset" : "?"}`); }
        catch { await triggerHeal(); log(true, "Partitions healed (Toxiproxy)"); }
    });

    const btn = "flex items-center gap-1.5 rounded-lg px-4 py-2 text-[12px] font-semibold transition-opacity disabled:opacity-30 disabled:cursor-not-allowed cursor-pointer";
    const spin = (n: string) => busy === n;

    return (
        <Card title="Account Actions" accent={C.blue}>
            <div className="space-y-5">
                {accts.length > 0 && (
                    <div>
                        <label className="mb-2 block text-[10px] font-semibold uppercase tracking-wider" style={{ color: C.dim }}>Accounts (click to select)</label>
                        <div className="flex flex-wrap gap-2">
                            {accts.map((id) => (
                                <div key={id} className="flex items-center gap-1">
                                    <button onClick={() => setAccountId(id)} className="rounded-lg border px-2.5 py-1.5 font-mono text-[11px] transition-colors"
                                        style={accountId === id ? { borderColor: C.blue, background: tint(C.blue, 0.14), color: C.blue } : { borderColor: C.border, color: C.dim }}>
                                        {id.substring(0, 8)}…
                                    </button>
                                    <button onClick={() => copy(id)} className="p-1" style={{ color: C.dim }} title="Copy">
                                        {copied === id ? <Check size={12} style={{ color: C.green }} /> : <Copy size={12} />}
                                    </button>
                                    <button onClick={() => setToAccountId(id)} className="px-1 text-[9px] font-bold" style={{ color: C.dim }} title="Set as destination">TO</button>
                                </div>
                            ))}
                        </div>
                    </div>
                )}

                <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-4">
                    <Field label="Account (from)" value={accountId} onChange={setAccountId} placeholder="Create or paste ID" mono />
                    <Field label="To account" value={toAccountId} onChange={setToAccountId} placeholder="Click TO above" mono />
                    <Field label="Amount" value={amount} onChange={setAmount} type="number" min="0.01" step="0.01" />
                    <div>
                        <Label>Currency</Label>
                        <select value={currency} onChange={(e) => setCurrency(e.target.value)} className={FIELD} style={FIELD_STYLE}>
                            <option value="USD" style={OPTION_STYLE}>USD</option>
                            <option value="EUR" style={OPTION_STYLE}>EUR</option>
                            <option value="GBP" style={OPTION_STYLE}>GBP</option>
                        </select>
                    </div>
                </div>

                <Group title="Banking">
                    <button onClick={createAcct} disabled={!!busy} className={btn} style={{ background: tint(C.blue, 0.14), color: C.blue }}>{spin("create") ? <Loader2 size={13} className="animate-spin" /> : <Plus size={13} />}Create account</button>
                    <button onClick={doDeposit} disabled={!!busy || !accountId} className={btn} style={{ background: tint(C.green, 0.14), color: C.green }}>{spin("deposit") ? <Loader2 size={13} className="animate-spin" /> : <ArrowDownToLine size={13} />}Deposit</button>
                    <button onClick={doWithdraw} disabled={!!busy || !accountId} className={btn} style={{ background: tint(C.amber, 0.14), color: C.amber }}>{spin("withdraw") ? <Loader2 size={13} className="animate-spin" /> : <ArrowUpFromLine size={13} />}Withdraw</button>
                    <button onClick={doTransfer} disabled={!!busy || !accountId || !toAccountId} className={btn} style={{ background: tint(C.violet, 0.14), color: C.violet }}>{spin("transfer") ? <Loader2 size={13} className="animate-spin" /> : <ArrowLeftRight size={13} />}Transfer</button>
                    <button onClick={checkBal} disabled={!!busy || !accountId} className={btn} style={{ background: "rgba(140,165,210,0.08)", color: C.muted }}>Check balance</button>
                </Group>

                <Group title="Chaos & System">
                    <button onClick={genLoad} disabled={!!busy} className={btn} style={{ background: tint(C.amber, 0.14), color: C.amber }}>{spin("load") ? <Loader2 size={13} className="animate-spin" /> : <Zap size={13} />}Generate load</button>
                    <button onClick={runInv} disabled={!!busy} className={btn} style={{ background: tint(C.green, 0.14), color: C.green }}>{spin("inv") ? <Loader2 size={13} className="animate-spin" /> : <ShieldCheck size={13} />}Run invariants</button>
                    <button onClick={changeLeader} disabled={!!busy} className={btn} style={{ background: tint(C.red, 0.14), color: C.red }}>{spin("leader") ? <Loader2 size={13} className="animate-spin" /> : <Shuffle size={13} />}Change leader</button>
                    <button onClick={partitionFollower} disabled={!!busy} className={btn} style={{ background: tint(C.red, 0.14), color: C.red }}>{spin("chaos") ? <Loader2 size={13} className="animate-spin" /> : <Unplug size={13} />}Partition follower</button>
                    <button onClick={partitionLeader} disabled={!!busy} className={btn} style={{ background: tint(C.red, 0.14), color: C.red }}><Unplug size={13} />Partition leader</button>
                    <button onClick={heal} disabled={!!busy} className={btn} style={{ background: tint(C.green, 0.14), color: C.green }}>{spin("heal") ? <Loader2 size={13} className="animate-spin" /> : <HeartPulse size={13} />}Heal cluster</button>
                </Group>

                {logs.length > 0 && (
                    <div className="max-h-[160px] overflow-y-auto rounded-xl p-4 font-mono text-[11px]" style={{ background: "#0b1526", border: `1px solid ${C.border}` }}>
                        {logs.map((l, i) => (
                            <div key={i} className="flex gap-2.5 py-0.5">
                                <span className="shrink-0" style={{ color: C.dim }}>{l.time}</span>
                                <span className="shrink-0 font-bold" style={{ color: l.ok ? C.green : C.red }}>{l.ok ? "OK " : "ERR"}</span>
                                <span className="break-all" style={{ color: C.textSoft }}>{l.msg}</span>
                            </div>
                        ))}
                    </div>
                )}
            </div>
        </Card>
    );
}
