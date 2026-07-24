# ChaosLedger

**A self-attacking, self-healing distributed financial ledger.**

A 3-node Raft-replicated ledger that does double-entry accounting, continuously injects realistic failures into itself, and proves it never loses money. Built with Java 21, Spring Boot, Apache Ratis, Kafka, and PostgreSQL.

<!-- TODO: Replace with actual demo GIF -->
<!-- ![ChaosLedger Trust Dashboard](docs/assets/demo.gif) -->

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Trust Dashboard (React)                 │
└────────────────────────────┬────────────────────────────────┘
                             │ WebSocket
        ┌────────────────────┼────────────────────┐
        │                    │                    │
   ┌────▼─────┐        ┌────▼─────┐        ┌────▼─────┐
   │ Ledger-1 │◄──────►│ Ledger-2 │◄──────►│ Ledger-3 │
   │ (Leader) │  Raft   │(Follower)│  Raft   │(Follower)│
   └────┬─────┘        └────┬─────┘        └────┬─────┘
        │                    │                    │
   ┌────▼─────┐        ┌────▼─────┐        ┌────▼─────┐
   │Postgres-1│        │Postgres-2│        │Postgres-3│
   └──────────┘        └──────────┘        └──────────┘
        │                    │                    │
        └────────────────────┼────────────────────┘
                             │
                        ┌────▼────┐
                        │  Kafka  │
                        └─────────┘
                             ▲
        ┌────────────────────┼────────────────────┐
   ┌────┴──────┐      ┌─────┴─────┐      ┌──────┴─────┐
   │ Toxiproxy │      │Chaos Agent│      │  Invariant  │
   │ (Network) │      │ (Process) │      │  Checker    │
   └───────────┘      └───────────┘      └─────────────┘
```

Writes go through the Raft consensus log before being applied to PostgreSQL, so all nodes agree on event order. Toxiproxy sits between Raft peers for network-level fault injection. Five invariants are checked continuously against live data every 10 seconds.

---

## Invariants

| Invariant | What It Proves |
|-----------|---------------|
| Conservation of money | Total balance always equals deposits minus withdrawals |
| No negative balances | No account ever drops below zero |
| Projection determinism | Replaying the same events always yields the same state |
| Event ordering | Events are strictly ordered by version with no gaps |
| Account integrity | Every account has exactly one open event, no orphaned events |

---

## Chaos Scenarios

| ID | Scenario | Category | Result |
|----|----------|----------|--------|
| 1.1 | Leader crash mid-write | Node failure | ✅ PASS |
| 1.2 | Follower crash during replication | Node failure | ✅ PASS |
| 2.1 | Symmetric partition during write | Network | ✅ PASS |
| 2.2 | Asymmetric partition | Network | ✅ PASS |
| 2.4 | Flapping network (10-byte fragments) | Network | ✅ PASS |
| 2.7 | Split-brain with write acceptance | Network | ✅ PASS |
| 3.1 | Clock drift forward 2s | Clock | ✅ PASS |
| 5.3 | Poison pill message | Kafka | ✅ PASS |
| 7.1 | Concurrent transfer same account | Application | ✅ PASS |
| 7.3 | Idempotency key replay after failover | Application | ✅ PASS |

Full scenario documentation: [src/CHAOS_CATALOG.md](src/CHAOS_CATALOG.md)

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21, Spring Boot 3.5 |
| Consensus | Apache Ratis 3.1 (Raft) |
| Event Store | PostgreSQL 16 (JSONB + optimistic concurrency) |
| Message Bus | Apache Kafka 3.7 |
| Chaos | Toxiproxy 2.9 |
| Testing | jqwik, Testcontainers, Awaitility |
| Dashboard | React + Vite + Recharts |
| Infrastructure | Docker Compose |

---

## Getting Started

### Prerequisites

Java 21+, Docker Desktop (Compose v2), Maven 3.9+

### Run the Cluster

```bash
git clone https://github.com/YOUR_USERNAME/chaosledger.git
cd chaosledger

./mvnw clean package -DskipTests
docker compose up --build -d
```

| Service | URL |
|---------|-----|
| Ledger Node 1 | http://localhost:8080 |
| Ledger Node 2 | http://localhost:8081 |
| Ledger Node 3 | http://localhost:8082 |
| Trust Dashboard | http://localhost:3000 |

### Try It Out

```bash
# Open an account
curl -s -X POST http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerId": "alice", "currency": "INR"}' | jq

# Deposit ₹10,000
curl -s -X POST http://localhost:8080/accounts/{ACCOUNT_ID}/deposit \
  -H 'Content-Type: application/json' \
  -d '{"amount": 10000, "idempotencyKey": "dep-001"}' | jq

# Transfer ₹3,000 to another account
curl -s -X POST http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountId": "{FROM_ID}", "toAccountId": "{TO_ID}", "amount": 3000, "idempotencyKey": "txn-001"}' | jq

# Check invariants
curl -s http://localhost:8080/invariants | jq
```

### Run Chaos Tests

```bash
docker build -t chaosledger-ledger:test .
docker compose -f docker-compose.chaos.yml up -d

./mvnw test -Dtest="CatalogScenarioTest"
./mvnw test -Dtest="Week13ScenarioTest"
```

---

## API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/accounts` | Open a new account |
| `GET` | `/accounts/{id}` | Get account and balance |
| `POST` | `/accounts/{id}/deposit` | Deposit money |
| `POST` | `/accounts/{id}/withdraw` | Withdraw money |
| `POST` | `/transfers` | Transfer between accounts |
| `GET` | `/invariants` | Live invariant results |
| `GET` | `/raft/status` | Raft cluster status |
| `GET` | `/hlc/status` | Hybrid Logical Clock state |
| `GET` | `/health` | Health check |

All mutating endpoints accept an `idempotencyKey` for safe retries across failures and failovers.

---

## Project Structure

```
chaosledger/
├── src/main/java/com/chaosledger/ledger/
│   ├── api/                    # REST controllers + DTOs
│   ├── chaos/                  # ChaosEngine + ToxiproxyClient
│   ├── domain/
│   │   ├── commands/           # Deposit, Withdraw, Transfer
│   │   ├── events/             # Event types + EventStore interface
│   │   ├── hlc/                # Hybrid Logical Clock
│   │   ├── invariants/         # 5 invariant implementations
│   │   ├── Account.java        # Event-sourced aggregate
│   │   └── Money.java          # BigDecimal value object
│   └── infrastructure/
│       ├── eventstore/         # PostgresEventStore (JSONB)
│       ├── kafka/              # Event publisher + consumer
│       ├── raft/               # Ratis StateMachine + RaftEventStore
│       └── websocket/          # Dashboard streaming
├── src/test/                   # 38 test files across 5 layers
├── dashboard/                  # React Trust Dashboard
├── chaos-agent/                # Python sidecar for container control
├── docker-compose.yml          # Standard 3-node cluster
└── docker-compose.chaos.yml    # Chaos cluster with Toxiproxy
```

---



---
