# Telemetry System — 10M Network Flows/sec

High-level design for a telemetry pipeline that ingests **10 million network flows per second**.

## Contents

- **[HLD.md](./HLD.md)** — Full high-level design: scale, architecture, components, trade-offs, failure modes, security, capacity sketch, and phased rollout.

## Key ideas

- **Durable log (Kafka/Pulsar)** as the spine: one write path, multiple consumers.
- **Stateless ingest** → **stream processing** (aggregate/sample) → **hot storage** + **cold lake**.
- No single DB for 10M writes/sec; aggregation and sampling are first-class.
- Designed for backpressure, observability, and cost from day one.

## Scale at a glance

| Metric        | Value        |
|---------------|--------------|
| Flows/sec     | 10M          |
| Ingest BW     | ~1 GB/s      |
| Raw data/day  | ~8.6 TB      |
| Compressed/day| ~2+ TB       |
