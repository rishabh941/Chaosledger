# ChaosLedger — Chaos Scenario Catalogue

## Catalogue Structure

Each scenario has:
- **ID**: `category.number` (e.g., 1.1, 2.4, 7.3)
- **Hypothesis**: What we expect the system to do under this failure
- **Setup**: Preconditions and steady state
- **Fault injection**: What breaks and how
- **Expected behaviour**: Concrete, verifiable outcomes
- **Invariants checked**: Which of the 5 invariants must hold
- **Result**: PASS/FAIL + notes

## Categories

| Category | Description |
|----------|-------------|
| 1.x | Node failures (process crash, OOM, restart) |
| 2.x | Network failures (partition, latency, flapping) |
| 3.x | Clock failures (drift, skew, NTP jumps) |
| 5.x | Message bus failures (Kafka poison pills, lag) |
| 7.x | Application-level failures (idempotency, concurrency) |

---

## Week 12 Scenarios (1–5)

### 1.1 — Leader Crash Mid-Write

| Field | Value |
|-------|-------|
| **ID** | 1.1 |
| **Name** | leader-crash-mid-write |
| **Category** | Node failure |
| **Uses** | ChaosEngine.partitionNode + ChaosTestBase.stopContainer |

**Hypothesis**: If the leader crashes while a write is in flight, the cluster
elects a new leader and the write either commits (if replicated) or fails safely
and can be retried. No money is lost or duplicated.

**Setup**: Two accounts with known balances (10000 + 5000 = 15000 total).
All 3 nodes healthy and replicated.

**Fault injection**: Partition leader's Raft proxy, then stop the Docker container
(process crash). This is a compound failure: network partition + process death.

**Expected behaviour**:
1. New leader elected within 15 seconds
2. Transfer succeeds on new leader
3. Conservation of money holds on both survivors (total = 15000)
4. After restarting crashed node: it catches up, balances match, invariants pass

**Invariants checked**: Conservation, no-negative-balance, event ordering,
projection determinism, account integrity.

**Result**: PASS

---

### 1.2 — Follower Crash During Replication

| Field | Value |
|-------|-------|
| **ID** | 1.2 |
| **Name** | follower-crash-during-replication |
| **Category** | Node failure |
| **Uses** | ChaosEngine.partitionNode + ChaosTestBase.stopContainer |

**Hypothesis**: If a follower crashes while events are being replicated, the
surviving quorum continues accepting writes. On restart, the follower catches up
with all missed events.

**Setup**: One account with 5000 balance. All 3 nodes healthy.

**Fault injection**: Partition and stop one follower's container.

**Expected behaviour**:
1. Writes continue on quorum (leader + remaining follower)
2. 5 deposits succeed while follower is down
3. After restart: follower's event count matches leader's
4. All invariants pass on all nodes

**Invariants checked**: All 5.

**Result**: PASS

---

### 2.1 — Symmetric Partition During Write

| Field | Value |
|-------|-------|
| **ID** | 2.1 |
| **Name** | symmetric-partition-during-write |
| **Category** | Network failure |
| **Uses** | ChaosEngine.partitionNode |

**Hypothesis**: When the leader is symmetrically partitioned, the two
non-partitioned nodes elect a new leader and accept writes. After healing,
all three nodes converge.

**Setup**: Two accounts with known balances (20000 + 10000 = 30000 total).

**Fault injection**: Disable leader's Raft proxy (Toxiproxy).

**Expected behaviour**:
1. New leader elected from non-partitioned nodes
2. Transfer succeeds on surviving quorum
3. Conservation holds during partition (total = 30000)
4. After healing: all 3 nodes show same balances, total = 30000

**Invariants checked**: All 5.

**Result**: PASS

---

### 2.4 — Flapping Network

| Field | Value |
|-------|-------|
| **ID** | 2.4 |
| **Name** | flapping-network |
| **Category** | Network failure |
| **Uses** | ChaosEngine.flappingNetwork (slicer toxic) |

**Hypothesis**: Under a flapping network (data arrives in tiny fragments with
delays), the cluster is slow but functional. At least some writes succeed.
After healing, all nodes converge.

**Setup**: One account with 8000 balance.

**Fault injection**: Slicer toxic on one follower's Raft proxy (10-byte
fragments, 100ms delay between fragments).

**Expected behaviour**:
1. At least 1 of 3 writes succeeds (cluster degrades gracefully)
2. Non-flapping nodes have correct balances
3. After healing: flapping node catches up, all nodes converge

**Invariants checked**: All 5.

**Result**: PASS

---

### 7.3 — Idempotency Key Replay After Failure

| Field | Value |
|-------|-------|
| **ID** | 7.3 |
| **Name** | idempotency-key-replay-after-failure |
| **Category** | Application-level |
| **Uses** | ChaosEngine.partitionNode |

**Hypothesis**: If a client retries a deposit with the same idempotency key
after a leader failover, the system processes it exactly once. The balance
reflects one deposit, not two.

**Setup**: One account with 10000 balance.

**Fault injection**: Deposit 3000 with key K → partition leader → new leader
elected → retry deposit 3000 with same key K.

**Expected behaviour**:
1. First deposit succeeds: balance = 13000
2. Leader partition triggers new election
3. Retry with same key is rejected (409) or is a no-op
4. Balance is 13000 on all nodes — NOT 16000
5. After healing: all nodes converge at 13000

**Invariants checked**: All 5, especially conservation.

**Result**: PASS

---

## Bugs Found

_Document bugs found during Week 12 in BUGS_FOUND.md. Each bug discovered
through chaos testing is interview gold._