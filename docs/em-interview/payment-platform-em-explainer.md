# Cross-Border Payment Platform — EM Explainer

## What This System Is

This is Skydo's **cross-border payment processing platform** — a system that takes international payments from businesses and routes them through compliance, currency conversion, SWIFT, and final settlement into INR, processing 10K+ transactions per day.

---

## How to Walk Through It as an EM

### 1. Start with the business problem (30 seconds)

> "When a foreign company pays an Indian business, you have to solve four hard problems simultaneously: handle multiple vendor formats, pass compliance/AML checks, convert foreign currency at the best rate, and settle into Indian bank accounts — all with zero double-processing."

---

### 2. Explain the architecture choice — Modular Monolith

> "We deliberately chose a **modular monolith over microservices**. Each domain — funding, invoicing, compliance, FX, settlement — is a separate module with clear boundaries, but they all deploy together. Services communicate through **Kafka topics** rather than direct calls. This gave us the operational simplicity of a monolith with the decoupling of microservices, which was the right trade-off for our team size and velocity."

---

### 3. Walk the happy path

```
API Gateway → ALB → Funding Service
                        ↓ (funding topic)
              Invoice Service → Invoice Mapper → transaction topic
                        ↓
              Compliance Service (automated + manual dashboard)
                        ↓ (approved)
              Swift Service (batches by currency + VA provider)
                        ↓
              FX Service (converts at AD bank → pricing event)
                        ↓
              Pricing Engine (calculates fees → settlement event)
                        ↓
              Settlement Service → HDFC API
                        ↓
              Ledger Service (records) + Notification Service (alerts)
```

> "A payment enters through our API Gateway, hits the Funding Service which normalizes it into a canonical form regardless of which vendor it came from. From there, it flows through Kafka topics — compliance checks run in parallel, then it hits SWIFT batching, FX conversion at an Authorized Dealer bank, pricing calculation, and finally settlement at HDFC."

---

### 4. Highlight the 3 hardest engineering problems you solved

**Problem 1 — Idempotency at every layer**

> "Money cannot be processed twice. We implemented **distributed locks in Redis** at the Funding layer to deduplicate incoming requests even under retries. Every downstream service — Settlement, Ledger, Refund — has its own idempotency layer. Even the HDFC settlement API call uses an idempotency key. The system is designed so any operation can be retried safely."

**Problem 2 — Vendor normalization**

> "We receive payments from multiple vendors, each with different data formats and webhook schemas. The Funding Service has vendor-specific adapters that each produce the same canonical event structure. Downstream services never know which vendor originated the payment — this isolation made adding a new vendor a matter of writing one new adapter, not changing core logic."

**Problem 3 — Compliance without blocking throughput**

> "Compliance is a mix of automated rules (sanction checks, TM — transaction monitoring) and manual review. We built a **Compliance Dashboard** where the ops team reviews flagged transactions. The architecture is async — transactions wait in the pipeline, not blocking new ones. Rejected transactions trigger the **Refund Service** which returns funds to source with its own idempotency guarantee."

---

### 5. Explain the resilience design

> "Every critical Kafka topic has a **retry topic and a DLQ**. If the Notification Service fails, it retries with backoff. After 3 retries, the message goes to the DLQ and triggers an alert. The same pattern applies to invoice, pricing, and settlement topics. This means no silent failures — every dropped message is observable and recoverable."

| Topic | Retry | DLQ |
|---|---|---|
| Funding topic | `funding_compliance_retry_topic` | DLQ for funding topic |
| Invoice topic | retry topic | DLQ for invoice topic |
| Pricing topic | retry topic | DLQ for pricing topic |
| Settlement topic | retry after 3 attempts | DLQ for settlement topic |
| Notification | notification retry topic | DLQ on failure |

---

### 6. Close with the trade-offs you'd make differently

> "If I were redesigning this today, I'd extract Settlement and FX as true independent services — they have the most divergent scaling needs and the tightest regulatory boundaries. The monolith served us well early, but as compliance requirements grew, those two modules became the right candidates for extraction."

---

## One-Line Summary for Any Audience

> "We built a cross-border payment platform that handles vendor normalization, async compliance screening, SWIFT batching, FX conversion, and bank settlement — with idempotency guarantees at every stage, so no transaction is ever lost, duplicated, or silently dropped."

---

## Key Components Reference

| Component | Responsibility |
|---|---|
| **ALB** | Entry point, load balancing |
| **Funding Service** | Vendor adapters, canonical normalization, Redis distributed lock |
| **Invoice Service** | Invoice generation |
| **Invoice Mapper Service** | Maps vendor invoice formats to internal structure |
| **Compliance Service** | Automated KYC/AML, sanction checks, TM consumer |
| **Compliance Dashboard** | Manual review by ops team; approve → SWIFT, reject → Refund |
| **Swift Service** | Batches transactions by currency + VA provider, initiates SWIFT |
| **FX Service** | Converts USD/GBP to INR via AD bank, emits pricing event |
| **Pricing Engine** | Calculates per-transaction fees, triggers settlement event |
| **Settlement Service** | Idempotent settlement via HDFC API |
| **Ledger Service** | Records all transactions with idempotency layer |
| **Notification Service** | Sends alerts at each stage with retry/DLQ |
| **Refund Service** | Returns funds to source when compliance rejects |
