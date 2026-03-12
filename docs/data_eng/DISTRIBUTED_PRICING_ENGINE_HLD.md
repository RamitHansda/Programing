# High-Level Design: Distributed Pricing Engine

---

## 1. Problem Restatement & Scale

| Dimension | Number |
|---|---|
| Trades | 20M |
| Positions | 5M |
| Pricing models | 50+ |
| Monte Carlo paths / instrument | 10,000 |
| **Total calculations** | **~50 billion** |
| Daily pricing window (EOD) | 4 hours |
| Required throughput | **~3.5M instruments/min** |

A single machine running Black-Scholes at 10µs/instrument would take **500 hours** for 5M positions. Monte Carlo at 10ms/instrument would take **14 hours**. Distribution is non-negotiable.

---

## 2. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         CLIENTS / UPSTREAM                               │
│   Risk Dashboard    PnL Reports    Trader Workstations    Reg Reporting  │
└────────────┬─────────────────────────────────────────────┬───────────────┘
             │  REST / gRPC                                │ WebSocket (live)
             ▼                                             ▼
┌────────────────────────┐                   ┌────────────────────────────┐
│   PRICING API GATEWAY  │                   │    RESULTS QUERY SERVICE   │
│  (Load balanced x3)    │                   │   (CQRS read side)         │
└────────────┬───────────┘                   └────────────────────────────┘
             │ Publish pricing run request
             ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        ORCHESTRATION LAYER                             │
│                                                                        │
│  ┌─────────────────────┐    ┌──────────────────────────────────────┐  │
│  │  Pricing Run Manager │    │         Work Scheduler               │  │
│  │  - Run lifecycle     │    │  - Partitions 5M positions           │  │
│  │  - Dependency graph  │    │  - Assigns priority (exotic > plain) │  │
│  │  - Progress tracking │    │  - Rebalances failed batches         │  │
│  └──────────┬──────────┘    └──────────────────┬───────────────────┘  │
│             └──────────────────┬────────────────┘                      │
└──────────────────────────────────────────────────────────────────────┘
                                 │  Push work batches
                                 ▼
┌───────────────────────────────────────────────────────────────────────┐
│                     DISTRIBUTED MESSAGE BUS                           │
│                         (Apache Kafka)                                │
│                                                                       │
│  Topic: pricing-requests-exotic     (partitions = 200, high priority) │
│  Topic: pricing-requests-vanilla    (partitions = 500)                │
│  Topic: pricing-requests-bonds      (partitions = 100)                │
│  Topic: pricing-results             (partitions = 300)                │
└───────────────────────────────────────────────────────────────────────┘
          │                   │                    │
          ▼                   ▼                    ▼
┌──────────────┐   ┌──────────────────┐   ┌─────────────────┐
│ EXOTIC POOL  │   │  VANILLA POOL    │   │  BOND/RATES POOL │
│ (Monte Carlo)│   │ (Black-Scholes)  │   │  (DCF / HJM)    │
│              │   │                  │   │                  │
│  50 nodes    │   │   200 nodes      │   │  50 nodes        │
│  8 CPU each  │   │   4 CPU each     │   │  4 CPU each      │
│  GPU optional│   │                  │   │                  │
└──────┬───────┘   └────────┬─────────┘   └────────┬────────┘
       └────────────────────┼─────────────────────┘
                            │ Publish results
                            ▼
┌───────────────────────────────────────────────────────────────────────┐
│                     RESULTS AGGREGATION SERVICE                       │
│                                                                       │
│   ┌──────────────┐  ┌───────────────┐  ┌──────────────────────────┐  │
│   │ PnL Calculator│  │Greeks Netter  │  │  VaR / CVaR Engine       │  │
│   │ (MTM vs book)│  │(portfolio-lvl)│  │  (Historical + Param)    │  │
│   └──────────────┘  └───────────────┘  └──────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────────────┐
│                         STORAGE LAYER                                 │
│                                                                       │
│  ┌──────────────────┐  ┌───────────────┐  ┌────────────────────────┐ │
│  │ TimescaleDB /    │  │  Redis        │  │  HDFS / S3             │ │
│  │ ClickHouse       │  │  (hot results │  │  (Monte Carlo paths,   │ │
│  │ (PnL time series)│  │   Greeks)     │  │   scenario archives)   │ │
│  └──────────────────┘  └───────────────┘  └────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────┘
```

---

## 3. Core Components Deep Dive

### 3.1 Work Scheduler

Responsible for decomposing a pricing run into parallelisable units.

```
Pricing Run (5M positions)
        │
        ├── Dependency Resolution
        │   (e.g. basket options depend on underlying equity prices first)
        │
        ├── Priority Queuing
        │   CRITICAL  → Exotic derivatives (longest compute, price first)
        │   HIGH      → Vanilla options + OTC swaps
        │   NORMAL    → Exchange-traded equities + bonds
        │
        ├── Batch Sizing
        │   Exotic:   50 instruments/batch  (heavy Monte Carlo)
        │   Vanilla:  500 instruments/batch (fast closed-form)
        │   Equity:   5000 instruments/batch (trivial MTM)
        │
        └── Node Affinity
            Re-use cached model state on same node (warm JIT, loaded vol surfaces)
```

### 3.2 Pricing Node

Each node is a stateless JVM process consuming from Kafka. It holds:

```
┌──────────────────────────────────────────┐
│            PRICING NODE                  │
│                                          │
│  ┌────────────┐   ┌─────────────────┐   │
│  │ Kafka      │   │ Market Data      │   │
│  │ Consumer   │──▶│ Cache (Hazelcast)│   │
│  └────────────┘   │ - Vol surfaces   │   │
│                   │ - Yield curves   │   │
│  ┌────────────┐   │ - Spot prices    │   │
│  │ Thread Pool│   └─────────────────┘   │
│  │ (N=cpu*2)  │                          │
│  │            │   ┌─────────────────┐   │
│  │  Worker 1  │   │ Model Registry   │   │
│  │  Worker 2  │──▶│ - BS Engine      │   │
│  │  Worker 3  │   │ - MC Engine      │   │
│  │  Worker N  │   │ - DCF Engine     │   │
│  └────────────┘   └─────────────────┘   │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │ Result Publisher (Kafka Producer)   │ │
│  └─────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

### 3.3 Market Data Distribution

Market data is the single hottest shared dependency — every node needs it simultaneously:

```
Market Data Source (Bloomberg/Reuters)
        │
        ▼
Market Data Normalizer
        │
        ├──▶ Kafka topic: market-data-snapshots  (broadcast)
        │
        └──▶ Distributed Cache (Hazelcast / Redis Cluster)
                 │
                 ├── Vol Surface Grid  (~2GB)
                 ├── Yield Curves      (~500MB)
                 └── Spot Prices       (~100MB)

All pricing nodes subscribe and pre-load on run start.
Stale-read tolerance: < 1 second (configured per asset class).
```

### 3.4 Monte Carlo at Scale

10,000 paths × 5M instruments = **50 billion paths**. Optimisations:

```
Strategy 1: GPU Acceleration (CUDA / OpenCL)
  - 1 A100 GPU = ~500 billion random numbers/sec
  - Batch 1000 instruments to same GPU kernel
  - 10x speedup over CPU for pure MC

Strategy 2: Quasi-Monte Carlo (Sobol sequences)
  - Achieves same accuracy as 10,000 paths with ~1,000 QMC paths
  - 10x path reduction → 10x compute reduction

Strategy 3: Variance Reduction (Control Variates + Antithetic)
  - Use Black-Scholes as control variate for vanilla payoffs
  - Halves effective path count needed

Strategy 4: Common Random Numbers
  - Same Sobol sequence across correlated instruments
  - Critical for portfolio-level VaR (correlation must be preserved)
```

---

## 4. Data Flow: Full Pricing Run

```
T=0:00  EOD trigger fires
          │
T=0:01  Position snapshot extracted from trade store (5M rows)
        Market data snapshot locked (Bloomberg close prices)
          │
T=0:02  Work Scheduler creates ~50,000 batches
        Pushes to Kafka (fan-out across 300 nodes)
          │
T=0:03  ┌── Equity nodes price 3M positions in 8 minutes (trivial MTM)
        ├── Bond nodes price 800K positions in 12 minutes
        └── Option/Exotic nodes begin MC (longest path)
          │
T=0:20  Greeks netted at book/desk level as results stream in
          │
T=0:45  Monte Carlo complete for 99% of exotics
          │
T=0:50  VaR aggregation: reconstruct portfolio P&L distribution
          │
T=1:00  Full risk report available:
          - Position-level PV
          - Greeks (delta, gamma, vega, theta, rho) by desk
          - 1-day 99% VaR
          - 10-day 99% CVaR
          - Stress test results
```

---

## 5. Key Design Decisions & Trade-offs

### 5.1 Pull vs Push for Work Distribution

| Approach | Pros | Cons |
|---|---|---|
| **Push (Kafka partitions)** | Simple, durable, replay-able | Hotspots if exotic batch is slow |
| **Pull (work stealing)** | Self-balancing, no stragglers | Complex coordination, ZooKeeper needed |
| **Chosen: Kafka + Rebalancer** | Durable + adaptive | Slightly more complex orchestrator |

Work stealing is implemented at the pool level: if a node finishes its partition early, it picks from an overflow topic.

### 5.2 Stateless vs Stateful Nodes

Nodes are **stateless** for the pricing computation itself, but maintain a **warm local cache** of:
- JIT-compiled pricing model bytecode
- Pre-loaded volatility surfaces per asset class

This avoids cold-start latency (loading 2GB vol surfaces per run) while keeping nodes replaceable.

### 5.3 Consistency of Market Data

All nodes must use the **exact same market data snapshot**. Achieved by:
1. Assigning a `snapshotId` to each pricing run
2. Pinning nodes to that snapshot version in the cache
3. Nodes reject requests from a different `snapshotId` (prevents stale/mixed runs)

### 5.4 Fault Tolerance

```
Node failure during run:
  1. Kafka offset not committed → batch automatically retried by another node
  2. Orchestrator detects timeout (30s per batch) → reschedules
  3. 3 retries max → batch marked failed, alert raised, partial results published

Broker failure:
  - Kafka replication factor = 3, min.insync.replicas = 2
  - Run pauses, resumes when broker recovers (offset preserved)

Full datacenter failure:
  - Active-passive DR: standby DC pre-loaded with market data
  - RTO: 15 minutes, RPO: 0 (Kafka geo-replication)
```

---

## 6. Scaling Model

| Instruments to price | Nodes needed | Wall-clock time |
|---|---|---|
| 100K (desk-level run) | 5 | 2 min |
| 1M (divisional) | 30 | 8 min |
| 5M (full portfolio EOD) | 300 | 60 min |
| 20M (full trade universe) | 1,000 | ~4 hours (with MC) |

**Auto-scaling rule:** Kubernetes HPA scales pricing node pods based on Kafka consumer lag. If lag on `pricing-requests-exotic` exceeds 10,000 messages, spawn 10 more pods. Scale down 5 min after lag clears.

---

## 7. VaR Architecture

```
Input: 5M positions × 10,000 simulated P&L paths each
       = 50 billion data points

Step 1: Each pricing node produces sorted P&L array per instrument
Step 2: Aggregation service streams results into a columnar store (Apache Arrow)
Step 3: Portfolio P&L = Σ(position.quantity × instrument.pnl[scenario])
        Computed in parallel using vectorised SIMD operations
Step 4: Sort portfolio P&L array
        VaR(99%, 1-day)  = 1st percentile of portfolio P&L
        CVaR(99%, 1-day) = mean of worst 1% of scenarios

Correlation preservation:
  All instruments sharing an underlying (e.g. AAPL) use the same
  random number seed → P&L paths are naturally correlated.
```

---

## 8. Technology Stack

| Layer | Technology | Rationale |
|---|---|---|
| Message bus | Apache Kafka | Durable, ordered, replayable, proven at 1M msg/s |
| Pricing nodes | JVM (Java 21 virtual threads) | Mature quant libraries, low GC latency with ZGC |
| Distributed cache | Hazelcast / Redis Cluster | Sub-ms reads for market data |
| GPU compute | CUDA via JCuda | 10-50x MC speedup for exotic books |
| Orchestration | Kubernetes + Helm | Auto-scaling, rolling deploys |
| Results store | ClickHouse | Columnar, vectorised, handles billions of rows |
| Hot results | Redis | Sub-ms Greeks/PV lookup for risk dashboard |
| Long-term archive | S3 + Parquet | MC path archives for regulatory backtesting |
| Observability | Prometheus + Grafana | Throughput, latency, pricing errors per model |

---

## 9. Observability & SLAs

```
Key metrics (Prometheus):
  pricing_throughput_instruments_per_second   target: >50,000/s
  pricing_latency_p99_ms{model="MONTE_CARLO"} target: <500ms
  pricing_error_rate                          target: <0.01%
  kafka_consumer_lag{topic="pricing-requests"} target: <10,000

Alerts:
  - Run not complete within 90 min → page on-call
  - Error rate > 0.1% → alert risk ops
  - Node count drops below 80% of target → auto-scale + alert
  - Market data snapshot age > 5 min → pause run, re-lock data

SLAs:
  - Desk-level Greeks available within 5 minutes of EOD
  - Full portfolio VaR within 60 minutes of EOD
  - Intraday repricing (delta P&L) within 30 seconds of trade
```

---

## 10. Summary

The design achieves **50B+ calculations/day** through five core principles:

1. **Instrument-type-aware work queues** — exotics get dedicated high-CPU pools; equities use lightweight MTM nodes
2. **Kafka-backed durable fan-out** — no work is lost on node failure; backpressure is natural
3. **Immutable market data snapshots** — all nodes compute consistently; no "which price did this use?" questions
4. **Variance reduction + QMC** — cuts effective Monte Carlo compute by 10x before adding hardware
5. **Streaming aggregation** — Greeks and VaR are computed incrementally as results arrive, not in a final big-bang step
