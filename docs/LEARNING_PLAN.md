# ChaosLedger Learning Plan

A structured learning path aligned with the build plan. Each section lists exactly what to learn before or during that phase, with time estimates and specific resources.

---

## Phase 1 — Single-Node Ledger (Weeks 1-3) [CURRENT]

### Concepts to master

**Event Sourcing** (4-6 hours total)
- Read: "What is Event Sourcing?" by Martin Fowler — https://martinfowler.com/eaaDev/EventSourcing.html (1 hour)
- Watch: "CQRS and Event Sourcing" by Greg Young on YouTube (1 hour)
- Skim: "Versioning in an Event Sourced System" by Greg Young (30 min)
- Key insight: State is derived from events, never stored directly. Events are the source of truth. You can always reconstruct any account's state at any point in time by replaying events up to that moment.

**Double-Entry Bookkeeping** (2 hours)
- Every financial transaction has two sides: a debit and a credit
- For transfers: sender gets a MoneyTransferred (debit), receiver gets a TransferReceived (credit)
- The invariant: sum of all debits must equal sum of all credits, always

**Optimistic Concurrency Control** (1-2 hours)
- Two writers cannot both claim version N of the same aggregate
- The database unique constraint on (aggregate_id, version) is the enforcer
- Your code catches the constraint violation and throws ConcurrencyException
- Callers should reload events and retry

**Idempotency** (1-2 hours)
- Network retries can deliver the same command twice
- Every mutating command carries a unique idempotencyKey (UUID)
- Before processing, check if that key exists in processed_commands table
- If it does, reject with DuplicateCommandException (409 Conflict)

### Practice exercises
- [ ] Open Postman, create two accounts, deposit money, transfer between them, verify balances
- [ ] Try submitting the same deposit twice with the same idempotencyKey — observe the 409
- [ ] Try withdrawing more than the balance — observe the 422
- [ ] Read the events for an account via GET /accounts/{id}/events

---

## Phase 2 — Property-Based Testing & Invariants (Weeks 4-6)

### Concepts to learn

**Property-Based Testing** (4-6 hours)
- Read: jqwik User Guide — https://jqwik.net/docs/current/user-guide.html (3 hours)
- Key difference from unit tests: instead of testing specific examples, you define properties that must hold for ALL inputs
- jqwik generates thousands of random scenarios and shrinks failing cases to the minimal reproduction

**The 5 Core Invariants to implement:**
1. Conservation of money — total balance is unchanged under any valid transfer sequence
2. Idempotency — same key twice = exactly one effect
3. No negative balance — no command sequence produces negative balance
4. Projection determinism — replaying events always yields same balance
5. Withdraw-deposit symmetry — withdraw X then deposit X returns to start

**Testcontainers** (2 hours)
- Real Postgres in tests, not H2 (which has subtle SQL differences)
- Each test class gets its own container
- Slow to start but catches real bugs

### Practice exercises
- [ ] Write your first @Property test: generate random deposit amounts, verify balance equals sum
- [ ] Trigger a jqwik shrinking: deliberately break an invariant, watch it find the minimal failing case
- [ ] Document the bug in BUGS_FOUND.md

---

## Phase 3 — Distribution & Replication (Weeks 7-10)

### Concepts to learn (THE HARDEST PHASE)

**Raft Consensus Algorithm** (8-12 hours — spend a full week reading before coding)
- Watch: Raft Visualization — http://thesecretlivesofdata.com/raft/ (30 min, interactive)
- Watch: "Designing for Understandability" by Diego Ongaro on YouTube (1 hour)
- Read: The Raft Paper, sections 1-5 — https://raft.github.io/raft.pdf (4 hours)
- Read: Apache Ratis getting-started — https://github.com/apache/ratis (2 hours)

**Self-test (no code) — can you explain these to a friend?**
- [ ] What is a leader and how is it elected?
- [ ] What does "majority quorum" mean and why is it necessary?
- [ ] What is the Raft log?
- [ ] What does "committed" mean?
- [ ] What happens when a leader fails?
- [ ] Why is split-brain impossible with quorum-based consensus?

**Hybrid Logical Clocks** (3-4 hours)
- Read: "Logical Physical Clocks" by Kulkarni et al. (2014) — https://cse.buffalo.edu/tech-reports/2014-04.pdf (90 min)
- Key insight: Wall clocks lie (NTP drift, leap seconds). HLCs combine physical time with logical counters to give causally consistent ordering
- Implementation is only ~60 lines of Java

**Docker Compose Networking** (2-3 hours)
- How containers communicate on a shared Docker network
- Port forwarding between containers
- Environment variable configuration for multi-node setups

---

## Phase 4 — Chaos Engine (Weeks 11-13)

### Concepts to learn

**Chaos Engineering Principles** (2-3 hours)
- Read: Principles of Chaos Engineering — https://principlesofchaos.org/
- Read: Jepsen Reports by Kyle Kingsbury — https://jepsen.io/analyses (skim 2-3 reports)

**Toxiproxy** (2 hours)
- Network-level failure injection: latency, packet loss, connection drops
- HTTP API for controlling proxies programmatically
- Why Toxiproxy before Chaos Mesh: simpler, Docker-based, no Kubernetes needed

**The 10 failure categories:**
1. Node crashes (leader, follower, cascading)
2. Network partitions (symmetric, asymmetric, partial, flapping)
3. Clock anomalies (drift forward, backward, disagreement)
4. Storage failures (slow disk, disk full)
5. Message bus failures (Kafka down, poison pill)
6. Application-level (concurrent transfers, idempotency under failure)

---

## Phase 5 — Dashboard & Polish (Weeks 14-16)

### Concepts to learn

**WebSockets / Server-Sent Events** (2 hours)
- Real-time streaming of invariant status, transactions, chaos events
- Spring Boot WebSocket support

**React + Vite basics** (4-6 hours if new to React)
- Vite project setup (NOT create-react-app)
- Functional components, hooks (useState, useEffect)
- WebSocket client in React

**D3.js basics** (optional, 3-4 hours)
- Force-directed graph for node visualization
- Animated particles for transaction flow
- Alternative: use a charting library like Recharts for simpler visuals

---

## Phase 6 — Stretch Goals (Weeks 17-20)

### Optional concepts

**TLA+ Formal Specification** (8-12 hours)
- Read: Practical TLA+ by Hillel Wayne, chapters 1-4
- Watch: TLA+ Video Course by Leslie Lamport (first 4 videos) — https://lamport.azurewebsites.net/video/videos.html
- Write a 100-200 line spec of ledger consistency model
- Run TLC model checker to verify properties

**Kubernetes** (4-6 hours)
- Migrate from Docker Compose to k8s manifests
- Use `kind` for local development
- Deploy to Oracle Cloud Free Tier (4 free ARM VMs)

**Deterministic Replay** (6-8 hours)
- Capture non-deterministic inputs: message order, RNG seeds, timer firings
- Build in-process simulator for replay
- Verify bit-for-bit identical state transitions

---

## Recommended Reading Order (Interview Gold)

These real-world failure stories are excellent for understanding why distributed correctness matters:

1. GitHub October 2018 incident — https://github.blog/2018-10-30-oct21-post-incident-analysis/
2. Cloudflare leap-second incident — https://blog.cloudflare.com/how-and-why-the-leap-second-affected-cloudflare-dns/
3. Jepsen analyses index — https://jepsen.io/analyses

Read one failure story per week. Each one teaches you something about why systems break and why projects like ChaosLedger matter.
