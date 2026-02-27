# How to Build Protocol Adapters — Design & Reasoning

This doc explains **how** to build protocol adapters and **why** they are designed this way (e.g. in ingest pipelines like the [Telemetry HLD](../telemetry-system/HLD.md)).

---

## 1. What Is a Protocol Adapter?

A **protocol adapter** is a component that:

- **Accepts** input in one specific protocol/schema (e.g. NetFlow v9, IPFIX, sFlow, REST JSON, gRPC).
- **Normalizes** it into a **single canonical format** your system understands.
- **Outputs** only that canonical format downstream (e.g. to a message bus, stream processor, or storage).

So: *many protocols in, one schema out.*

---

## 2. How to Build Them

### 2.1 Define a Canonical Model (Target Contract)

- Introduce **one** internal representation (e.g. `FlowRecord` with 5-tuple, bytes, packets, timestamps, device/interface).
- All downstream components depend **only** on this model. They never see NetFlow/sFlow/JSON specifics.

**Why:** Downstream code stays protocol-agnostic. Adding a new protocol (e.g. IPFIX) doesn’t change stream processors, storage, or APIs.

### 2.2 Define the Adapter Contract (Interface)

- **Interface:** e.g. `ProtocolAdapter` with a method like `FlowRecord parse(byte[] raw)` or `List<FlowRecord> parse(InputStream in)`.
- Each concrete adapter implements this interface for one protocol (NetFlowAdapter, SFlowAdapter, RestJsonAdapter, etc.).

**Why:** Same abstraction for all protocols; easy to add new adapters and test them in isolation.

### 2.3 One Adapter per Protocol

- **NetFlowAdapter:** understands NetFlow v9/v10 (IPFIX) packets → maps to `FlowRecord`.
- **SFlowAdapter:** understands sFlow datagrams → maps to `FlowRecord`.
- **RestJsonAdapter:** understands your REST/JSON payload → maps to `FlowRecord`.

Each adapter is responsible only for parsing and mapping; no business logic beyond “protocol → canonical.”

**Why:** Single responsibility; each adapter can be developed, scaled, and deployed independently (e.g. separate processes or sidecars as in the HLD).

### 2.4 Stateless, No Durable State

- Adapters **parse → normalize → emit**. They don’t store flows; they don’t hold long-lived session state.
- Optional: in-memory buffers for batching before sending to the bus.

**Why:** Horizontal scaling and simple failure model. If an instance dies, you don’t lose durable state; you only lose in-flight data (handled by at-least-once/backpressure as in the HLD).

### 2.5 Output to a Single Sink (e.g. Message Bus)

- All adapters publish the **same** canonical type (e.g. Avro/Protobuf `FlowRecord`) to the same logical channel (e.g. Kafka topic).
- Partitioning key (e.g. `hash(device_id, exporter_id)` or 5-tuple) is applied at publish time.

**Why:** One durable log at ingest; multiple consumers (aggregation, search, lake) without caring which protocol the flow came from.

### 2.6 Failures and Backpressure

- **Parse errors:** Log, metric, optionally dead-letter; do not crash the whole process for one bad packet.
- **Backpressure:** If the bus is slow, apply backpressure (drop, sample, or block) and expose metrics.

**Why:** Protects the pipeline from bad input and overload; aligns with the HLD’s “shed load rather than OOM” approach.

---

## 3. Why Build It This Way? (Reasoning Summary)

| Decision | Reason |
|----------|--------|
| **Single canonical model** | Downstream stays simple and protocol-agnostic; one schema to version and evolve. |
| **Adapter interface per protocol** | Open/closed: open for new protocols (new adapter), closed for changing core pipeline. |
| **One adapter per protocol** | Clear ownership, testability, and independent scaling/deploy (e.g. NetFlow-heavy vs REST-heavy). |
| **Stateless adapters** | Scale horizontally; no shared state; easy to run as sidecars or separate processes. |
| **Single output sink (e.g. Kafka)** | One committed boundary; replay and multiple consumers without protocol-specific code. |
| **Parse errors isolated** | One bad packet doesn’t take down the ingest layer; observability via logs/metrics. |
| **Backpressure at adapter/bus** | Prevents OOM and cascading failure under load (as in the HLD). |

---

## 4. Relation to Your Telemetry HLD

Your [HLD](https://github.com/.../HLD.md) says:

- *“Protocol adapters: Separate processes or sidecars for NetFlow v9, IPFIX, sFlow, or REST/gRPC. Output: canonical flow record (e.g. Avro/Protobuf).”*

This design follows that exactly:

- **Separate processes/sidecars** → each adapter can be a separate deployable unit.
- **Canonical flow record** → the `FlowRecord` (or Avro/Protobuf equivalent) is the only thing that hits the bus.
- **Sharding, backpressure, optional pre-aggregation** → applied after normalization, in the same way the HLD describes.

---

## 5. Minimal Class Diagram

```
                    ┌─────────────────────┐
                    │   FlowRecord        │  (canonical model)
                    │   (canonical)       │
                    └──────────▲──────────┘
                               │
         ┌─────────────────────┼─────────────────────┐
         │                     │                     │
┌────────┴────────┐  ┌─────────┴──────────┐  ┌───────┴────────┐
│ ProtocolAdapter │  │ NetFlowAdapter     │  │ SFlowAdapter   │
│ (interface)     │  │ implements         │  │ implements     │
└────────┬────────┘  └────────────────────┘  └────────────────┘
         │
         │  parse(raw) → FlowRecord(s)
         │
         ▼
   [Message Bus / Kafka]
```

The Java example lives in package `lld.protocoladapters` (canonical `FlowRecord`, `ProtocolAdapter` interface, `NetFlowAdapter`, `SFlowAdapter`, `IngestPipeline`, `ProtocolAdapterDemo`). It is a small in-memory example of this pattern (no real NetFlow/sFlow packet parsing).
