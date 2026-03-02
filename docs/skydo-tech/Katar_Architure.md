# Katar — Architecture Overview (Staff Engineer)

## 1. Executive Summary

**Katar** is a **Spring Boot library** that provides a **queue abstraction with full task visibility and DB-driven configuration**. It sits between application code and **AWS SQS**, adding:

- **Observability**: Every message is tracked in a `run_log` table (CREATED → PENDING → IN_PROGRESS → SUCCESS / ERROR_*).
- **Configuration as data**: Job types, queues, subscribers, and concurrency are defined in DB (`sub_config`), not in code.
- **Executor registration by convention**: Handlers are discovered via `@KatarExecutor(identifier = "JOB_TYPE")` and invoked by reflection.

Consuming applications enable it with `@EnableKatar`, point to a dedicated Katar DB and SQS, and get both producing and consuming capabilities based on that DB config.

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         APPLICATION (e.g. alpha, beta)                            │
│  @EnableKatar  │  @KatarExecutor("WEB_ENGAGE_*")  │  KatarProducer.sendMessage()  │
└─────────────────────────────────────────────────────────────────────────────────┘
                    │                                    │
                    │ produce                            │ consume
                    ▼                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                    KATAR LIBRARY                                  │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐       │
│  │ KatarProducer│   │ KatarConsumer │   │ RunLogService│   │ ExecutorStore│       │
│  │ SubConfigSvc │   │ (MessageList.)│   │ JobDataSvc   │   │ (jobType →   │       │
│  │ KatarSerializer   │ KatarDeserializer  │ IpService    │   │  bean, method)│       │
│  └──────┬───────┘   └──────┬───────┘   └──────┬───────┘   └──────────────┘       │
│         │                  │                  │                                    │
│  ┌──────┴──────────────────┴──────────────────┴──────┐                            │
│  │  InitializeService (CommandLineRunner)             │                            │
│  │  → RegisterExecutorService → QueueListenerService  │                            │
│  │  → QueueProducerService                            │                            │
│  └───────────────────────────────────────────────────┘                            │
└─────────────────────────────────────────────────────────────────────────────────┘
         │                         │                           │
         ▼                         ▼                           ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────────────────┐
│   AWS SQS       │    │  Katar DB        │    │  Redis (optional, external   │
│   (JMS/SQS lib) │    │  (sub_config,    │    │   payload / JobData refs)    │
│                 │    │   run_log,       │    │                              │
│                 │    │   job_data;      │    │                              │
│                 │    │   Hibernate      │    │                              │
│                 │    │   Envers on      │    │                              │
│                 │    │   run_log)       │    │                              │
└─────────────────┘    └─────────────────┘    └─────────────────────────────┘
```

- **One logical “queue system”** shared across services: routing and who listens to what come from **sub_config** in the **Katar DB**.
- **Same DB** holds subscription config and run logs; **SQS** is the transport; **Redis** is used for large/external payload references when needed.

---

## 3. Component Map

| Layer / Concern        | Components | Responsibility |
|------------------------|------------|----------------|
| **Entry / Config**     | `@EnableKatar`, `KatarConfiguration`, `KatarProperties` | Wire Katar’s package, JPA repos (Katar EM/TM), and `katar.datasource.*` (DB + SQS + optional Redis). |
| **Bootstrap**          | `InitializeService` (CommandLineRunner) | After context is up: register executors, start listeners, create producers. |
| **Discovery**          | `RegisterExecutorService`, `ExecutorStore`, `@KatarExecutor` | Scan beans for `@KatarExecutor`; map `identifier` → (bean, Method). |
| **Queue I/O**          | `SqsConfig`, `QueueListenerService`, `QueueProducerService`, `MessageProducerStore` | SQS connection/session; create consumers per (queue × concurrency); create one producer per distinct queue name. |
| **Produce path**       | `KatarProducer`, `SubConfigService`, `RunLogService`, `KatarSerializer` | Resolve queue from job type → create run_log → serialize → send; on failure mark ERROR_PRODUCER. |
| **Consume path**       | `KatarConsumer` (JMS MessageListener), `KatarDeserializer`, `ExecutorStore`, `RunLogService`, `JobDataService` | Deserialize → create run_log if missing → discard if DISCARDED → invoke executor → ack; on failure ERROR_CONSUMER + retry count. |
| **Data / Persistence** | `SubConfig`, `RunLog`, `JobData`; `SubConfigRepository`, `RunLogRepository`, `JobDataRepository` | Config and audit trail; optional large payload refs (e.g. Redis key in JobData). |
| **External data**      | `RedisService`, `JobDataService` | Store/retrieve/delete by key; cleanup after job completion. |

---

## 4. Data Flow

### 4.1 Produce (send message)

1. App calls `KatarProducer.sendMessage(KatarRequest)` with `jobType`, `data`, optional `externalIdentifier`.
2. **SubConfigService** loads active `SubConfig` for `jobType` (expects exactly one) → `queueName`, `subService`.
3. **RunLogService** creates a **RunLog** (state CREATED, new UUID `identifier`), then producer sends to SQS.
4. Producer gets **MessageProducer** from `MessageProducerStore` by `queueName`, sends serialized **KatarData** (identifier, jobType, data, pubService, etc.).
5. RunLog state set to **PENDING** (and sub_ip not set yet).
6. On any throw: RunLog set to **ERROR_PRODUCER**.

### 4.2 Consume (process message)

1. **QueueListenerService** has started N consumers per queue (from `sub_config.concurrency`), all using **KatarConsumer** as `MessageListener`.
2. **KatarConsumer.onMessage**:
   - Deserialize message → **KatarData**.
   - **RunLogService.createRunLogIfRequired**: if no RunLog for `identifier`, create one (e.g. cross-service visibility).
   - **RunLogService.doDiscardJob**: if state is DISCARDED, skip execution and ack.
   - Otherwise **executeJob**:
     - Set state **IN_PROGRESS**, set `sub_ip`.
     - Look up **ExecutorStore** for `jobType` → (bean, method); invoke with 0 or 1 arg (payload string).
     - **RunLogService.finishRunLog** → state **SUCCESS**.
   - **JobDataService.removeExternalDataIfPresent**: e.g. delete Redis key if JobData exists and is not DIRECT.
   - **message.acknowledge()**.
3. On any throw: **RunLogService.updateRunLogStateAndIncrementRetryCount(identifier, ERROR_CONSUMER)** (no ack → SQS redelivery).

---

## 5. Key Design Decisions and Trade-offs

| Decision | Rationale | Trade-off |
|----------|------------|-----------|
| **DB-driven routing** | Add/change job types and subscribers without redeploy; one source of truth. | Requires DB access and discipline: e.g. exactly one active sub_config per job type; no two services same queue. |
| **Reflection-based executors** | Simple API: one annotation + one method. | Single-arg limit; no first-class type-safe contract per job type; validation only at startup. |
| **Single shared Session for producers** | One JMS Session used to create producers at startup. | Session/connection failure affects all producers; no per-queue isolation. |
| **Static maps (ExecutorStore, MessageProducerStore)** | Simple, no need for dynamic bean wiring per job type. | Not multi-tenant or multi-context friendly; lifecycle tied to process. |
| **Run log on both sides** | Full visibility: who produced, who consumed, when, and state. | Two writes per produced message (create + update); consumer may create RunLog if not present. |
| **Envers on RunLog** | Full history of run_log changes for debugging/audit. | Extra table and rev storage; consider retention. |
| **Separate Katar DataSource** | Katar’s config and run_log don’t pollute app’s DB. | Two DBs to operate; connection pool and URL in `katar.datasource`. |

---

## 6. Configuration and Operational Model

- **Per-service identity**: `katar.datasource.serviceName` — which queues this process **consumes** (sub_config where `sub_service = serviceName`) and how it appears in run_log (pub_service/sub_service).
- **Producers**: Any service that has `@EnableKatar` and sends via `KatarProducer` can produce to **any** queue that appears in **any** active `sub_config` (QueueProducerService creates producers for all active configs).
- **Invariants** (from ReadMe):
  - One **sub_config** per new job type (or clear ownership).
  - **No two services** use the **same queue** (prevents multiple consumers competing on same queue by design).
  - **job_type** is unique across the system (so executor registration and sub_config stay 1:1).

---

## 7. Data Model (concise)

- **sub_config**: `job_type` (unique), `sub_service`, `queue_name`, `concurrency`, `is_active`. Drives listening and sending.
- **run_log**: `identifier` (UUID, UK), `pub_service`, `pub_ip`, `sub_service`, `sub_ip`, `job_type`, `state` (CREATED | PENDING | IN_PROGRESS | SUCCESS | ERROR_PRODUCER | ERROR_CONSUMER | DISCARDED), `retry_count`, `external_identifier`, timestamps. Audited via **aud_run_log** (Envers).
- **job_data**: Optional; links `identifier` to payload or external ref (e.g. Redis key) and `DataSource` (e.g. DIRECT vs external).

---

## 8. Integration Points

- **AWS SQS**: Via `amazon-sqs-java-messaging-lib`, JMS API; `KatarProperties.sqsUrl` (+ region). One connection; one session for producers; multiple sessions for consumers (per concurrency).
- **PostgreSQL**: Katar’s own DataSource (Hikari), JPA, Envers; dialect and credentials under `katar.datasource`.
- **Redis**: Optional; `RedisService` (Jedis pool) for external/large payload; `JobDataService` cleans up after successful consume.

---

## 9. Risks and Recommendations

- **Single SQS connection/session**: Failure or throttling can affect all producers; consider health checks and reconnection/backoff.
- **No explicit dead-letter or max retries in library**: Retries are SQS-driven; ERROR_CONSUMER only increments retry_count. Consider documenting DLQ and max receives.
- **ExecutorStore/MessageProducerStore are static**: Not suitable for multiple Katar contexts or dynamic job types without restart.
- **Validation**: ReadMe mentions “Validations of guidelines” as future work (e.g. enforce unique queue per service, single active sub_config per job type at startup or via admin API).

---

## 10. Summary Diagram (Logical)

```
[App] --KatarRequest--> KatarProducer --> sub_config lookup --> run_log (CREATED)
       --> serialize --> MessageProducerStore(queue) --> SQS

SQS --> KatarConsumer --> deserialize --> run_log (create if needed / DISCARD check)
       --> ExecutorStore(jobType) --> @KatarExecutor method
       --> run_log (SUCCESS) --> JobDataService cleanup --> ack
```

This gives you a Staff-level view of how Katar is structured, how data and control flow, and where the main levers and risks are for evolution and operations.
