# ChaosLedger Event Schema

All state in ChaosLedger is derived from events. Events are immutable, append-only records stored in PostgreSQL as JSONB. The events table enforces a unique constraint on `(aggregate_id, version)` to prevent concurrent corruption.

## Event Types

### AccountOpened
- **Aggregate:** Account
- **Fields:** `eventId`, `aggregateId`, `ownerId`, `currency`, `occurredAt`
- **Why it exists:** Establishes a new account with an initial zero balance. Without this event, there is no account to deposit into or withdraw from. Every account must begin with exactly one AccountOpened event.
- **Invariants preserved:** An account cannot be used until opened. The currency is fixed at open time and never changes.

### MoneyDeposited
- **Aggregate:** Account
- **Fields:** `eventId`, `aggregateId`, `amount`, `currency`, `idempotencyKey`, `occurredAt`
- **Why it exists:** Records an external deposit into an account. This is the only way money enters the system.
- **Invariants preserved:** Amount must be positive. Conservation of money: total system balance increases by exactly `amount`. Idempotency key prevents duplicate deposits from network retries.

### MoneyWithdrawn
- **Aggregate:** Account
- **Fields:** `eventId`, `aggregateId`, `amount`, `currency`, `idempotencyKey`, `occurredAt`
- **Why it exists:** Records a withdrawal from an account. This is the only way money leaves the system.
- **Invariants preserved:** Amount must be positive. Balance must not go negative (checked before event is produced). Conservation of money: total system balance decreases by exactly `amount`.

### MoneyTransferred
- **Aggregate:** Account (the sender)
- **Fields:** `eventId`, `aggregateId`, `toAccountId`, `amount`, `currency`, `idempotencyKey`, `occurredAt`
- **Why it exists:** Records the debit side of an internal transfer. Always paired with a TransferReceived event on the receiving account. Together they form a double-entry accounting pair.
- **Invariants preserved:** Sender balance must be sufficient. Amount must be positive. Both accounts must share the same currency. Conservation: total system balance is unchanged (money moves, not created or destroyed).

### TransferReceived
- **Aggregate:** Account (the receiver)
- **Fields:** `eventId`, `aggregateId`, `fromAccountId`, `amount`, `currency`, `idempotencyKey`, `occurredAt`
- **Why it exists:** Records the credit side of an internal transfer. This is the counterpart to MoneyTransferred on the sender's account.
- **Invariants preserved:** Double-entry consistency: for every MoneyTransferred event, there is exactly one TransferReceived event with matching amount and idempotency key.

## Design Decisions

1. **Events use BigDecimal, never double.** Floating-point arithmetic causes rounding errors that violate conservation of money. `BigDecimal` with scale 2 ensures exact arithmetic.

2. **Every mutating event carries an idempotencyKey.** Network retries, message replays, and chaos scenarios can all cause duplicate command delivery. The idempotency key is checked before processing and stored in `processed_commands` to guarantee exactly-once semantics.

3. **Events store the currency redundantly.** Even though the account's currency is fixed at open time, storing it on every event makes event replay self-contained. A MoneyDeposited event can be understood without loading the AccountOpened event.

4. **Transfers produce two events on two aggregates.** This is a conscious choice over a single "Transfer" event spanning two aggregates. It keeps each aggregate's event stream self-contained and makes per-account replay simple. The trade-off is that the two events must be appended atomically (within a single database transaction) to prevent partial transfers.

5. **Version numbers start at 1, not 0.** A new aggregate has version 0 (no events). The first event gets version 1. This makes the `expectedVersion` parameter unambiguous: 0 means "this aggregate should not exist yet."
