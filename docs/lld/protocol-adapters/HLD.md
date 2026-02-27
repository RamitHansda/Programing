# High-Level Design: Protocol Adapter Layer

**Audience:** Engineers and architects implementing or operating the ingest front door of a flow-telemetry pipeline. Complements the parent [Telemetry System HLD](../telemetry-system/HLD.md), which covers the full pipeline.

---

## 1. Problem & Scope

**Goal:** Accept network flow data from multiple, incompatible protocols (NetFlow v9/v10, sFlow, REST/gRPC/JSON), normalize it to a **single canonical schema**, and publish to a durable message bus. Downstream systems must not depend on the source protocol.

**Scope of this HLD:** The **protocol adapter layer** only — receipt of raw input, parsing, normalization, optional ingest-time filter/sample, and publish. It does **not** cover the message bus, stream processing, or storage.

**Context:** In the parent telemetry HLD, this layer is the “Ingest Layer” (stateless collectors / protocol adapters). Its output feeds the durable log (e.g. Kafka); all other pipeline stages consume from that log.

---

## 2. Non-Goals

- **Not** implementing the message bus, stream processors, or storage (see parent HLD).
- **Not** full packet capture or DPI; input is already flow/sample records or API payloads.
- **Not** long-term retention or query API; adapters emit and forget (except optional in-memory batching).
- **Not** protocol discovery or auto-negotiation; each listener/port is bound to a known protocol.

---

## 3. Requirements

### 3.1 Functional

| ID | Requirement |
|----|-------------|
| F1 | Ingest **NetFlow v9** and **IPFIX** (v10) over UDP; parse template + data sets; output canonical flow records. |
| F2 | Ingest **sFlow** over UDP; parse datagram and sample records; map to canonical flow records (or flow-like records from samples). |
| F3 | Ingest **REST/JSON** or **gRPC** flow payloads; normalize schema to canonical; output same canonical records. |
| F4 | Emit **one canonical schema** (e.g. Avro/Protobuf FlowRecord) to the configured sink (e.g. Kafka topic). |
| F5 | Support optional **ingest-time filter** (e.g. by subnet, device, port) and **sampling** (e.g. 1:N) before publish. |
| F6 | Partition key for publish is configurable (e.g. hash(device_id, exporter_id) or hash(5-tuple)). |

### 3.2 Non-Functional

| ID | Requirement |
|----|-------------|
| NF1 | **Stateless:** no durable state; restart loses only in-flight data. |
| NF2 | **Horizontal scaling:** add instances behind LB or multiple UDP ports; no shared state. |
| NF3 | **Fault isolation:** parse errors must not crash the process; log + metric + optional dead-letter. |
| NF4 | **Backpressure:** when sink is slow, apply configurable policy (block, drop, or sample) and expose metrics. |
| NF5 | **Observability:** metrics (received vs parsed vs published, errors, latency) and structured logs. |

---

## 4. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           PROTOCOL ADAPTER LAYER (this HLD)                               │
│                                                                                          │
│   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐                  │
│   │   NetFlow   │   │   sFlow     │   │  REST/JSON  │   │   gRPC      │   ← Protocols    │
│   │   (UDP)     │   │   (UDP)     │   │  (HTTP)     │   │   (HTTP/2)  │                  │
│   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘                  │
│          │                 │                 │                 │                          │
│          ▼                 ▼                 ▼                 ▼                          │
│   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐                  │
│   │  NetFlow    │   │   sFlow     │   │   REST      │   │   gRPC      │   ← Adapters      │
│   │  Adapter    │   │   Adapter   │   │   Adapter   │   │   Adapter   │   (parse + map)   │
│   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘                  │
│          │                 │                 │                 │                          │
│          └─────────────────┴────────┬────────┴─────────────────┘                          │
│                                     │                                                     │
│                          ┌──────────▼──────────┐                                          │
│                          │  Canonical schema   │   FlowRecord (5-tuple, bytes, packets,   │
│                          │  (single contract)  │   timestamps, device_id, exporter_id…)   │
│                          └──────────┬──────────┘                                          │
│                                     │                                                     │
│                          ┌──────────▼──────────┐                                          │
│                          │  Optional: filter,  │   Ingest-time filter / sample            │
│                          │  sample, batch      │   Batch by size or time                  │
│                          └──────────┬──────────┘                                          │
│                                     │                                                     │
└─────────────────────────────────────┼─────────────────────────────────────────────────────┘
                                       │
                          ┌────────────▼────────────┐
                          │   Message bus (Kafka)   │   Partition key = e.g. hash(device_id)
                          │   Topic: flow-events   │   Serialization: Avro / Protobuf
                          └───────────────────────┘
```

**Design principle:** Many protocols in, one schema out. All adapters produce the same canonical type; the bus and downstream are protocol-agnostic.

---

## 5. Component Design

### 5.1 Canonical Flow Record (Schema)

Single internal and wire format for a flow. Downstream never sees “NetFlow” or “sFlow” fields.

**Representative fields:**

| Field | Type | Description |
|-------|------|-------------|
| src_ip | IP/varchar | Source IP |
| dst_ip | IP/varchar | Destination IP |
| src_port | uint16 | Source port |
| dst_port | uint16 | Destination port |
| protocol | enum/string | e.g. TCP, UDP, ICMP |
| bytes | uint64 | Octet count |
| packets | uint64 | Packet count |
| start_time | timestamp | Flow start |
| end_time | timestamp | Flow end |
| device_id | string | Exporter/device identifier |
| exporter_id | string | Exporter instance (e.g. for multi-tenant) |
| ingress_interface | optional | If available from protocol |

**Serialization:** Avro or Protobuf for the bus; schema registry recommended for evolution.

### 5.2 Adapter Contract (Interface)

All adapters implement the same contract:

- **Input:** protocol-specific raw payload (e.g. UDP packet bytes, HTTP body, gRPC message).
- **Output:** zero or more canonical FlowRecords.
- **Errors:** return empty list (or skip bad record); log and increment error metric; do not throw to caller where it would kill the process.

Optional: `getProtocolName()` for logging and metrics.

### 5.3 Per-Protocol Adapters

| Adapter | Transport | Input | Responsibilities |
|---------|-----------|--------|------------------|
| **NetFlow** | UDP | NetFlow v9 / IPFIX packet | Decode header, template set, data set; maintain template cache per exporter; map fields → FlowRecord. |
| **sFlow** | UDP | sFlow datagram | Decode header and sample records (flow/counter); map or aggregate to FlowRecord(s). |
| **REST/JSON** | TCP (HTTP) | JSON body | Parse JSON; map vendor-specific field names to canonical; validate required fields. |
| **gRPC** | TCP (HTTP/2) | Protobuf message | Decode request; map to FlowRecord; same contract as others. |

Each adapter is a separate deployable unit (process or sidecar) so that NetFlow-heavy and REST-heavy workloads can scale independently.

### 5.4 Receiver / Dispatcher

- **UDP:** One or more sockets (e.g. port 2055 NetFlow, 6343 sFlow); each socket type dispatches to the correct adapter.
- **HTTP/gRPC:** One service with routes; path or content-type selects adapter (or dedicated ports per protocol).

No business logic in the receiver; it only forwards raw bytes (or parsed HTTP/gRPC envelope) to the chosen adapter.

### 5.5 Optional Ingest-Time Processing

After normalization, before publish:

- **Filter:** Drop records that don’t match configured rules (e.g. ignore certain subnets or devices).
- **Sample:** Keep 1 out of N records to reduce volume (e.g. 1:10 for a given device).
- **Batch:** Buffer by count or time (e.g. 500 records or 100 ms) then publish in bulk for throughput.

These are applied to the canonical record only; no protocol-specific logic here.

### 5.6 Publisher / Sink

- **Target:** Single logical sink (e.g. one Kafka topic). Same topic for all protocols.
- **Partition key:** From canonical record (e.g. `hash(device_id, exporter_id)` or `hash(5-tuple)`) to preserve ordering per key and spread load.
- **Serialization:** Canonical schema (Avro/Protobuf). Schema registry for compatibility.
- **Backpressure:** If send fails or is slow, apply policy (block with timeout, drop, or sample) and expose metrics; do not unbounded queue (OOM risk).

---

## 6. Data Flow (Sequence)

```
  Exporter          Receiver         Adapter           Filter/Sample      Publisher        Kafka
     │                  │                 │                    │                │             │
     │  UDP/HTTP raw    │                 │                    │                │             │
     │─────────────────>│                 │                    │                │             │
     │                  │  dispatch       │                    │                │             │
     │                  │────────────────>│                    │                │             │
     │                  │                 │  parse → FlowRecord(s)              │             │
     │                  │                 │───────────────────>│                │             │
     │                  │                 │                    │  batch / drop  │             │
     │                  │                 │                    │──────────────>│             │
     │                  │                 │                    │                │  produce    │
     │                  │                 │                    │                │───────────>│
     │                  │                 │                    │                │    ack      │
     │                  │                 │                    │                │<───────────│
```

---

## 7. Deployment & Scaling

- **Units:** One process (or pod) can run one or more adapter types (e.g. NetFlow + sFlow in one binary, or separate sidecars per protocol).
- **Scaling:** Horizontal: add instances; put LB (or DNS) in front for HTTP; for UDP, either multiple IPs/ports or single port with many workers (each worker = one adapter instance).
- **No shared state:** No DB or distributed cache in this layer; optional in-process template cache per NetFlow exporter is best-effort and can be rebuilt on restart.

---

## 8. Failure Handling & Observability

| Failure | Handling |
|---------|----------|
| Malformed packet / body | Log, increment `parse_errors` (by protocol); return empty list; do not crash. |
| Unknown template (NetFlow) | Cache miss; skip or buffer until template arrives; metric. |
| Sink unavailable / slow | Backpressure: block (with timeout), drop, or sample; expose `publish_failures` / `backpressure_dropped`. |
| Process crash | Acceptable; in-flight data lost; at-least-once semantics from exporters (e.g. NetFlow retransmit) or API retry. |

**Metrics (representative):** `flows_received`, `flows_parsed`, `flows_published`, `parse_errors`, `publish_errors`, `backpressure_dropped`, latency percentiles. **Logs:** structured; include protocol, exporter_id, and error type; avoid logging full payload at scale.

---

## 9. Interfaces Summary

| Interface | Direction | Description |
|-----------|-----------|-------------|
| **Northbound (in)** | Network → Adapter | NetFlow/sFlow UDP; HTTP/gRPC with JSON or Protobuf body. |
| **Southbound (out)** | Adapter → Bus | Kafka produce (or equivalent); key = partition key from FlowRecord; value = serialized canonical schema. |
| **Internal** | Receiver → Adapter | Raw bytes (UDP) or parsed request body; adapter returns `List<FlowRecord>`. |

---

## 10. Relation to Parent Telemetry HLD

This HLD details the **Ingest Layer** described in the [Telemetry System HLD](../telemetry-system/HLD.md):

- **Protocol adapters** → this document’s adapters (NetFlow, sFlow, REST, gRPC).
- **Canonical flow record** → Section 5.1; emitted to the bus as in parent HLD.
- **Sharding / partition key** → Section 5.6; same as parent (e.g. hash(device_id, exporter_id)).
- **Backpressure** → Section 5.6 and 8; “shed load rather than OOM” from parent HLD.
- **Stateless, horizontally scalable** → Section 7; matches parent ingest design.

Stream processing, storage, and query API remain in the parent HLD; this HLD stops at the message bus produce.
