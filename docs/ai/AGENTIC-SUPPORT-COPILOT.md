# Agentic Support Copilot — Principal Engineer Design & Build Guide

> **Context:** This document covers the principal-engineer design, component-by-component implementation, and operational setup for the Agentic Support Copilot built at Skydo. The system uses a **hybrid RAG + read-only tool-calling** architecture with human-in-the-loop (HITL) approvals, PII masking, policy checks, and audit logs. Outcomes: ~65% auto-triage, ~35–40% assisted/auto resolution, first-response time reduced from ~90 min → ~10–12 min, SLA breaches down ~50%.

---

## Table of Contents

1. [System Overview & Architecture](#1-system-overview--architecture)
2. [Tech Stack Decisions](#2-tech-stack-decisions)
3. [Component 1 — Ticket Ingestion & PII Masking](#3-component-1--ticket-ingestion--pii-masking)
4. [Component 2 — Intent Classification & Auto-Triage](#4-component-2--intent-classification--auto-triage)
5. [Component 3 — Hybrid RAG Knowledge Base](#5-component-3--hybrid-rag-knowledge-base)
6. [Component 4 — Read-Only Tool Layer](#6-component-4--read-only-tool-layer)
7. [Component 5 — Agent Orchestration Loop](#7-component-5--agent-orchestration-loop)
8. [Component 6 — Human-in-the-Loop (HITL)](#8-component-6--human-in-the-loop-hitl)
9. [Component 7 — Policy Checks & Compliance Guardrails](#9-component-7--policy-checks--compliance-guardrails)
10. [Component 8 — Audit Logging](#10-component-8--audit-logging)
11. [Component 9 — Draft Response & Resolution Engine](#11-component-9--draft-response--resolution-engine)
12. [End-to-End Flow](#12-end-to-end-flow)
13. [Infrastructure & Deployment](#13-infrastructure--deployment)
14. [Evaluation & Metrics](#14-evaluation--metrics)
15. [Rollout Strategy](#15-rollout-strategy)
16. [Key Failure Modes & Mitigations](#16-key-failure-modes--mitigations)
17. [Full Code Reference](#17-full-code-reference)

---

## 1. System Overview & Architecture

### Principal Engineering Frame

This should not be framed as "put an LLM in the support queue." At principal-engineer scope, the design problem is to create a **trusted support automation platform** that can safely absorb repetitive support demand while preserving customer trust, compliance posture, and the support team's ability to intervene.

The core architectural stance is:

> Keep all irreversible customer-impacting decisions outside the autonomous loop until the system has earned trust through measured, auditable behavior.

That stance drives the main boundaries:

- The agent can **read** operational state, but cannot mutate account, payment, KYC, or refund state.
- The system can draft and route, but auto-send is restricted by confidence, policy, intent class, and rollout gates.
- Compliance, PII masking, audit, and HITL are not sidecars; they are part of the product contract.
- Thresholds, allowed intents, prompts, policy versions, and model versions must be independently configurable from application deploys.

### Business Outcome and Non-Goals

| Area | Principal-level framing |
|---|---|
| Business outcome | Reduce first-response latency and support backlog without increasing regulatory, payment, or customer-trust risk |
| Primary users | Customers waiting for answers; support agents reviewing drafts; operations/compliance teams auditing decisions |
| Non-goal | Fully autonomous support agent that can refund, change KYC status, unblock accounts, or override policy |
| Platform bet | Build a reusable safe-agent substrate for future operations workflows: disputes, onboarding, reconciliation, and internal ops copilots |
| Failure posture | Prefer over-escalation to humans over incorrect auto-resolution; user trust is more valuable than marginal automation |

### Non-Negotiable Requirements

| Requirement | Why it is non-negotiable | Design consequence |
|---|---|---|
| No customer PII in model logs | Cross-border payments data is sensitive and regulated | PII masking before every LLM call; original text encrypted and scoped to CRM write-back |
| No autonomous money movement | A bad action can create financial loss or compliance breach | Tool layer is read-only by IAM, DB grants, and application schema |
| Every decision is reconstructable | Compliance and incident review require traceability | Append-only audit log with prompt, retrieved evidence, tool calls, model version, policy version, and human action |
| Human override is always available | Support remains accountable for customer communication | HITL pause/resume with approve, edit, reject, and force-escalate paths |
| Rollout is reversible | Model quality, policy drift, or vendor incidents can regress quickly | Runtime kill switch for auto-send, threshold tuning via SSM, and intent allowlist |

### Principal Design Principles

| Principle | Architectural consequence |
|---|---|
| Trust is earned through evidence | Start in shadow mode, compare against human responses, and only expand auto-send after offline and live gates pass |
| Separate policy from reasoning | The LLM may propose; deterministic and policy-checking layers decide whether a response can leave the system |
| Control plane is separate from runtime | Prompts, thresholds, allowed intents, policy versions, model routes, and eval gates are controlled without redeploying workers |
| Retrieval must be source-attributed | Every answer shown to a human or customer carries evidence snippets and document versions |
| Blast radius is bounded by intent | Low-risk FAQ and FX questions can auto-resolve; compliance, refunds, blocked accounts, and explicit escalation stay human-owned |
| Operations are first-class | Queue depth, cost, latency, approval rate, edit distance, policy failures, and rollback levers are designed up front |

### Domain Boundaries and Ownership

| Boundary | Owns | Contract | Why this seam matters |
|---|---|---|---|
| Ticket ingestion | Support platform | Normalized ticket, masked body, PII map, trace ID | Isolates CRM/webhook differences from agent logic |
| Triage and routing | Support automation | Intent, confidence, urgency, route | Lets support ops tune automation policy without changing the agent |
| Knowledge retrieval | Knowledge/platform team | Authorized evidence chunks with source and version | Prevents prompt-only knowledge and supports policy freshness |
| Tool execution | Domain service owners | Read-only APIs with typed schemas and SLAs | Keeps account, payment, KYC, and FX systems authoritative |
| Policy guardrails | Compliance + engineering | Pass/fail, violated rule, required escalation | Makes compliance a release gate, not a post-processing suggestion |
| HITL workflow | Support operations | Review state, decision, edit, final response | Preserves human accountability and produces training signal |
| Audit and evaluation | Platform governance | Immutable event stream and eval datasets | Enables incident response, model comparison, and governance |

### High-Level Flow

```
[Support Ticket / Chat]
         │
         ▼
[Ingestion Layer]  ── PII detection & masking (presidio / regex)
         │
         ▼
[Intent Classifier]  ── fast, cheap model (GPT-4o-mini / fine-tuned)
         │
    ┌────┴─────────────────┐
    │                      │
    ▼                      ▼
[Auto-Triage            [Escalate to
 Engine]                 Human Queue]
    │                      ▲
    ▼                      │ (low-confidence / sensitive)
[Orchestrator Agent]  ─────┘
    │
    ├── RAG Retrieval (hybrid: BM25 + vector)
    │       └── Policy docs, FAQ, past resolved tickets
    │
    ├── Read-Only Tool Calls
    │       ├── get_account_status(user_id)
    │       ├── get_transaction_details(txn_id)
    │       ├── get_kyc_status(user_id)
    │       └── get_fx_rate(currency_pair)
    │
    ├── Policy Check Guard  ← validate draft against compliance rules
    │
    └── Draft Response
             │
        ┌────┴───────────────┐
        ▼                    ▼
  [Auto-send if          [HITL Queue]
   confidence > 0.85]     Agent reviews + edits + sends
        │                    │
        └──────┬─────────────┘
               ▼
         [Audit Log]
         [CRM Update]
         [SLA Timer Reset]
```

### Mermaid Architecture Diagram

```mermaid
flowchart TB
    User["Customer / Support Ticket"] --> CRM["Freshdesk / Zendesk / Chat"]
    CRM --> Ingest["Webhook Ingestion<br/>Normalize + trace ID"]
    Ingest --> Mask["PII Masking<br/>Presidio + custom recognizers"]
    Mask --> Triage["Intent Classifier<br/>Intent + confidence + urgency"]
    Triage --> Risk{"Risk route"}
    Risk -->|Sensitive or low confidence| HumanQueue["Human Queue"]
    Risk -->|Eligible intent| Orchestrator["LangGraph Orchestrator"]

    subgraph EvidencePlane["Evidence Plane"]
        Knowledge["Policy / FAQ / Resolved Tickets"] --> Retriever["Hybrid Retrieval<br/>BM25 + Vector"]
        DomainAPIs["Read-only Domain APIs<br/>Account / Txn / KYC / FX"] --> ToolExec["Tool Executor<br/>Typed schemas"]
    end

    Orchestrator --> Retriever
    Orchestrator --> ToolExec
    Retriever --> Draft["Draft Response<br/>Sources + evidence"]
    ToolExec --> Draft
    Draft --> PolicyGuard["Policy + PII Guardrails"]
    PolicyGuard --> SendDecision{"Can auto-send?"}
    SendDecision -->|No| HumanQueue
    SendDecision -->|Yes| CustomerReply["Customer Reply"]
    HumanQueue --> HITL["HITL Review<br/>Approve / edit / reject"]
    HITL --> CustomerReply

    CustomerReply --> Audit["Immutable Audit Log"]
    PolicyGuard --> Audit
    Orchestrator --> Audit
    HITL --> Audit

    subgraph ControlPlane["Control Plane"]
        PromptRegistry["Prompt Registry"]
        ModelRoute["Model Gateway Config"]
        Thresholds["Thresholds + Intent Allowlist"]
        PolicyVersion["Policy Version Pointer"]
        EvalGates["Golden-set Eval Gates"]
        KillSwitch["Auto-send Kill Switch"]
    end

    PromptRegistry -. prompts .-> Orchestrator
    ModelRoute -. routes .-> Orchestrator
    Thresholds -. gates .-> SendDecision
    PolicyVersion -. versions .-> Retriever
    EvalGates -. promotes .-> SendDecision
    KillSwitch -. disables .-> SendDecision
```

### Control Plane vs Runtime Plane

A principal-level implementation should split "how a ticket is processed now" from "who is allowed to change how the system behaves."

```
Runtime plane
  Ticket -> Mask -> Triage -> Retrieve -> Tool calls -> Draft -> Policy check -> HITL/Auto-send

Control plane
  Prompt registry
  Model routing config
  Intent allowlist
  Confidence thresholds
  Policy version pointer
  Evaluation gates
  Rollout stage and kill switches
```

| Plane | Changes frequently? | Owner | Release mechanism |
|---|---:|---|---|
| Runtime worker code | Low to medium | Support automation engineering | CI/CD deployment |
| Prompt templates | Medium | Engineering + support ops | Versioned prompt registry with eval gate |
| Policy documents | Medium | Compliance + support ops | S3 version + active pointer in DynamoDB |
| Auto-send thresholds | High during rollout | Support ops + engineering | SSM Parameter Store, no redeploy |
| Intent allowlist | Medium | Support leadership + compliance | Config change with approval |
| Model/provider route | Medium | Platform engineering | Model gateway config with fallback |

This split keeps the operating team from needing a code deploy for every threshold or policy change, while still requiring eval and approval for changes that alter customer-visible behavior.

### Implementation Design Principles

| Principle | Decision |
|---|---|
| **Read-only tools** | Agent can never write/mutate; all mutations require human confirmation |
| **Hybrid RAG** | BM25 for exact policy/keyword matches + vector for semantic similarity |
| **Confidence-gated** | Only auto-send when classifier confidence ≥ threshold |
| **PII-first** | Mask PII before any LLM call; unmask only in final CRM write |
| **Audit everything** | Every LLM call, tool call, decision, and human action is logged with trace ID |
| **Configurable rollout** | Auto-send threshold, allowed intents, and model routes are runtime config, not hardcoded releases |

---

## 2. Tech Stack Decisions

| Layer | Choice | Why |
|---|---|---|
| **Orchestration** | LangGraph | Stateful loops, HITL interrupts, checkpointing, cycle support |
| **LLM — Reasoning** | GPT-4o | Strong tool calling, structured JSON output, reliable at complex multi-hop |
| **LLM — Classification** | GPT-4o-mini | 10× cheaper, fast, sufficient for intent routing |
| **Embeddings** | `text-embedding-3-small` | Low cost, good quality for support domain |
| **Vector DB** | Qdrant (self-hosted) | Filtering by metadata (category, language), payload indexing |
| **Keyword Search** | BM25 via Elasticsearch | Exact policy keyword matching, phrase search |
| **PII Detection** | Microsoft Presidio | Configurable recognizers, custom Skydo entities (account IDs, IFSC) |
| **Policy Store** | S3 + DynamoDB | Policies versioned in S3; active version pointer in DynamoDB |
| **Prompt Registry** | S3 versioned objects + DynamoDB metadata | Prompt changes are versioned, reviewable, and tied to eval results |
| **Model Gateway** | Thin internal routing layer | Centralizes provider fallback, token budgets, latency tracking, and model-route changes |
| **Control Config** | AWS SSM Parameter Store | Runtime thresholds, intent allowlists, and kill switches change without redeploy |
| **Audit Log** | DynamoDB + CloudWatch | Immutable append-only log; CloudWatch for alerting |
| **Evaluation Store** | S3 + Athena / warehouse table | Golden-set results, shadow comparisons, and live review signals are queryable over time |
| **Job Queue** | SQS + Lambda | Async ticket processing; decouple intake from agent execution |
| **HITL Interface** | Internal Retool dashboard | Agents see draft + evidence; approve/edit/reject with 1 click |
| **Observability** | Langfuse (self-hosted) | Framework-agnostic, self-hostable for compliance |

---

## 3. Component 1 — Ticket Ingestion & PII Masking

Tickets arrive from Freshdesk/Zendesk webhooks, email, or in-app chat. PII must be stripped before any LLM call.

### PII Masking Pipeline

```python
from presidio_analyzer import AnalyzerEngine
from presidio_anonymizer import AnonymizerEngine
from presidio_anonymizer.entities import OperatorConfig
import re

analyzer = AnalyzerEngine()
anonymizer = AnonymizerEngine()

# Custom Skydo recognizers — add to presidio registry
CUSTOM_PATTERNS = [
    # Skydo internal account IDs: SKY-XXXXXXXX
    (r"SKY-[A-Z0-9]{8,12}", "SKYDO_ACCOUNT_ID"),
    # IFSC codes: ABCD0123456
    (r"\b[A-Z]{4}0[A-Z0-9]{6}\b", "IFSC_CODE"),
    # UTR numbers: 12–22 digit strings
    (r"\b\d{12,22}\b", "UTR_NUMBER"),
]

def mask_pii(text: str) -> tuple[str, dict]:
    """
    Returns (masked_text, pii_map).
    pii_map: {placeholder: original_value} for demasking later.
    """
    pii_map = {}
    masked = text

    # Presidio: detect standard PII (email, phone, pan, name, etc.)
    results = analyzer.analyze(text=text, language="en")
    anonymized = anonymizer.anonymize(
        text=text,
        analyzer_results=results,
        operators={
            "DEFAULT": OperatorConfig("replace", {"new_value": "<REDACTED_{entity_type}>"}),
            "EMAIL_ADDRESS": OperatorConfig("replace", {"new_value": "<EMAIL>"}),
            "PHONE_NUMBER": OperatorConfig("replace", {"new_value": "<PHONE>"}),
            "PERSON": OperatorConfig("replace", {"new_value": "<NAME>"}),
        },
    )
    masked = anonymized.text

    # Custom Skydo patterns
    for pattern, label in CUSTOM_PATTERNS:
        def replacer(m, lbl=label):
            placeholder = f"<{lbl}_{len(pii_map)}>"
            pii_map[placeholder] = m.group(0)
            return placeholder
        masked = re.sub(pattern, replacer, masked)

    return masked, pii_map


def unmask_pii(text: str, pii_map: dict) -> str:
    """Restore original values for CRM write-back only."""
    for placeholder, original in pii_map.items():
        text = text.replace(placeholder, original)
    return text
```

### Ticket Normalization Schema

```python
from pydantic import BaseModel, Field
from datetime import datetime
from typing import Optional

class NormalizedTicket(BaseModel):
    ticket_id: str
    masked_body: str
    original_body: str           # stored encrypted at-rest; never sent to LLM
    pii_map: dict                # placeholder → original value
    channel: str                 # email | chat | api
    priority: str                # P1 | P2 | P3
    user_id: Optional[str]       # extracted from auth header or ticket metadata
    created_at: datetime
    language: str = "en"
    attachments: list[str] = Field(default_factory=list)
```

---

## 4. Component 2 — Intent Classification & Auto-Triage

Fast, cheap classification runs before the expensive orchestrator agent.

### Intent Categories

```python
INTENT_CLASSES = [
    "payment_failure",
    "kyc_query",
    "fx_rate_query",
    "account_blocked",
    "refund_request",       # sensitive — always HITL
    "compliance_query",     # sensitive — always HITL
    "general_faq",
    "technical_issue",
    "escalation_request",   # user explicitly asking for human
    "out_of_scope",
]

ALWAYS_HUMAN = {"refund_request", "compliance_query", "escalation_request"}
```

### Classifier

```python
from openai import OpenAI
from pydantic import BaseModel

client = OpenAI()

class TriageResult(BaseModel):
    intent: str
    confidence: float           # 0.0–1.0
    sub_intent: str = ""
    urgency: str = "normal"     # normal | high | critical
    needs_human: bool = False
    reason: str = ""

TRIAGE_SYSTEM_PROMPT = """
You are a support ticket classifier for Skydo, a cross-border payments platform.

Classify the ticket into exactly one intent from this list:
{intents}

Rules:
- If the user explicitly requests a human agent → escalation_request
- If the topic involves refunds or compliance → mark needs_human: true
- confidence: your certainty 0.0–1.0
- urgency: critical if payment is stuck >24h or account is blocked

Respond in the exact JSON schema provided.
""".strip()

def classify_ticket(masked_body: str) -> TriageResult:
    intents_list = "\n".join(f"- {i}" for i in INTENT_CLASSES)
    
    response = client.beta.chat.completions.parse(
        model="gpt-4o-mini",
        messages=[
            {
                "role": "system",
                "content": TRIAGE_SYSTEM_PROMPT.format(intents=intents_list),
            },
            {"role": "user", "content": masked_body},
        ],
        response_format=TriageResult,
    )
    result = response.choices[0].message.parsed
    
    # Override: certain intents always go to human regardless of confidence
    if result.intent in ALWAYS_HUMAN:
        result.needs_human = True
    
    return result
```

### Triage Routing Decision

```python
AUTO_RESOLVE_CONFIDENCE = 0.85
ASSISTED_CONFIDENCE = 0.60

def route_ticket(triage: TriageResult) -> str:
    """Returns: 'auto' | 'assisted' | 'human'"""
    if triage.needs_human:
        return "human"
    if triage.confidence >= AUTO_RESOLVE_CONFIDENCE:
        return "auto"
    if triage.confidence >= ASSISTED_CONFIDENCE:
        return "assisted"
    return "human"
```

---

## 5. Component 3 — Hybrid RAG Knowledge Base

The knowledge base covers: policy documents, FAQ, past resolved tickets, product documentation, and error code references.

### Document Indexing Pipeline

```python
import hashlib
from qdrant_client import QdrantClient
from qdrant_client.models import VectorParams, Distance, PointStruct, Filter, FieldCondition, MatchValue
from openai import OpenAI
from elasticsearch import Elasticsearch

openai_client = OpenAI()
qdrant = QdrantClient(host="localhost", port=6333)
es = Elasticsearch("http://localhost:9200")

COLLECTION_NAME = "support_kb"
EMBED_MODEL = "text-embedding-3-small"
EMBED_DIM = 1536

def ensure_collection():
    if not qdrant.collection_exists(COLLECTION_NAME):
        qdrant.create_collection(
            collection_name=COLLECTION_NAME,
            vectors_config=VectorParams(size=EMBED_DIM, distance=Distance.COSINE),
        )

def chunk_document(text: str, chunk_size: int = 512, overlap: int = 64) -> list[str]:
    """Sliding window chunker with token-level overlap awareness."""
    words = text.split()
    chunks = []
    step = chunk_size - overlap
    for i in range(0, len(words), step):
        chunk = " ".join(words[i : i + chunk_size])
        if chunk:
            chunks.append(chunk)
    return chunks

def index_document(doc_id: str, text: str, metadata: dict):
    """Index into both Qdrant (vector) and Elasticsearch (keyword)."""
    chunks = chunk_document(text)
    embeddings = openai_client.embeddings.create(
        model=EMBED_MODEL,
        input=chunks,
    ).data

    # Vector index (Qdrant)
    points = []
    for i, (chunk, emb_obj) in enumerate(zip(chunks, embeddings)):
        chunk_id = hashlib.md5(f"{doc_id}-{i}".encode()).hexdigest()
        points.append(PointStruct(
            id=chunk_id,
            vector=emb_obj.embedding,
            payload={**metadata, "text": chunk, "doc_id": doc_id, "chunk_index": i},
        ))
    qdrant.upsert(collection_name=COLLECTION_NAME, points=points)

    # Keyword index (Elasticsearch)
    for i, chunk in enumerate(chunks):
        es.index(
            index="support_kb",
            id=f"{doc_id}-{i}",
            body={"text": chunk, "doc_id": doc_id, **metadata},
        )
```

### Hybrid Retrieval

```python
from dataclasses import dataclass

@dataclass
class RetrievedChunk:
    text: str
    score: float
    source: str
    category: str

def vector_search(query: str, top_k: int = 10, category_filter: str = None) -> list[RetrievedChunk]:
    q_emb = openai_client.embeddings.create(model=EMBED_MODEL, input=[query]).data[0].embedding
    
    search_filter = None
    if category_filter:
        search_filter = Filter(
            must=[FieldCondition(key="category", match=MatchValue(value=category_filter))]
        )
    
    results = qdrant.search(
        collection_name=COLLECTION_NAME,
        query_vector=q_emb,
        limit=top_k,
        query_filter=search_filter,
    )
    return [
        RetrievedChunk(
            text=r.payload["text"],
            score=r.score,
            source=r.payload.get("source", ""),
            category=r.payload.get("category", ""),
        )
        for r in results
    ]

def bm25_search(query: str, top_k: int = 10) -> list[RetrievedChunk]:
    resp = es.search(
        index="support_kb",
        body={"query": {"match": {"text": query}}, "size": top_k},
    )
    return [
        RetrievedChunk(
            text=hit["_source"]["text"],
            score=hit["_score"],
            source=hit["_source"].get("source", ""),
            category=hit["_source"].get("category", ""),
        )
        for hit in resp["hits"]["hits"]
    ]

def hybrid_retrieve(query: str, top_k: int = 5, category: str = None) -> list[RetrievedChunk]:
    """
    Merge vector + BM25 results using Reciprocal Rank Fusion (RRF).
    RRF score = sum(1 / (rank + 60)) across result lists.
    """
    vector_results = vector_search(query, top_k=10, category_filter=category)
    bm25_results = bm25_search(query, top_k=10)

    # RRF fusion
    rrf_scores: dict[str, float] = {}
    chunk_map: dict[str, RetrievedChunk] = {}

    for rank, chunk in enumerate(vector_results):
        key = chunk.text[:100]  # deduplicate by text prefix
        rrf_scores[key] = rrf_scores.get(key, 0) + 1 / (rank + 60)
        chunk_map[key] = chunk

    for rank, chunk in enumerate(bm25_results):
        key = chunk.text[:100]
        rrf_scores[key] = rrf_scores.get(key, 0) + 1 / (rank + 60)
        if key not in chunk_map:
            chunk_map[key] = chunk

    sorted_keys = sorted(rrf_scores, key=lambda k: rrf_scores[k], reverse=True)
    return [chunk_map[k] for k in sorted_keys[:top_k]]
```

---

## 6. Component 4 — Read-Only Tool Layer

All tools are **strictly read-only**. No tool can write, update, or delete data. This is enforced at both the application layer and the IAM/DB permissions layer.

```python
from pydantic import BaseModel, Field
from typing import Optional
import httpx

# ── Input schemas ──────────────────────────────────────────────────────────────

class AccountStatusInput(BaseModel):
    user_id: str = Field(..., description="Skydo internal user ID (SKY-XXXXXXXX format)")

class TransactionInput(BaseModel):
    txn_id: str = Field(..., description="Transaction ID or UTR number")

class KYCStatusInput(BaseModel):
    user_id: str = Field(..., description="Skydo internal user ID")

class FXRateInput(BaseModel):
    from_currency: str = Field(..., description="ISO 4217 currency code, e.g. USD")
    to_currency: str = Field(..., description="ISO 4217 currency code, e.g. INR")

# ── Tool implementations ────────────────────────────────────────────────────────

INTERNAL_API_BASE = "https://internal-api.skydo.com"
INTERNAL_API_KEY  = "..."  # injected via env; never hardcoded

async def get_account_status(params: AccountStatusInput) -> dict:
    async with httpx.AsyncClient() as c:
        r = await c.get(
            f"{INTERNAL_API_BASE}/accounts/{params.user_id}/status",
            headers={"X-API-Key": INTERNAL_API_KEY},
            timeout=5.0,
        )
        r.raise_for_status()
        return r.json()

async def get_transaction_details(params: TransactionInput) -> dict:
    async with httpx.AsyncClient() as c:
        r = await c.get(
            f"{INTERNAL_API_BASE}/transactions/{params.txn_id}",
            headers={"X-API-Key": INTERNAL_API_KEY},
            timeout=5.0,
        )
        r.raise_for_status()
        return r.json()

async def get_kyc_status(params: KYCStatusInput) -> dict:
    async with httpx.AsyncClient() as c:
        r = await c.get(
            f"{INTERNAL_API_BASE}/kyc/{params.user_id}",
            headers={"X-API-Key": INTERNAL_API_KEY},
            timeout=5.0,
        )
        r.raise_for_status()
        return r.json()

async def get_fx_rate(params: FXRateInput) -> dict:
    async with httpx.AsyncClient() as c:
        r = await c.get(
            f"{INTERNAL_API_BASE}/fx/rates",
            params={"from": params.from_currency, "to": params.to_currency},
            headers={"X-API-Key": INTERNAL_API_KEY},
            timeout=3.0,
        )
        r.raise_for_status()
        return r.json()

# ── Tool registry ───────────────────────────────────────────────────────────────

TOOL_MAP = {
    "get_account_status":       (get_account_status, AccountStatusInput),
    "get_transaction_details":  (get_transaction_details, TransactionInput),
    "get_kyc_status":           (get_kyc_status, KYCStatusInput),
    "get_fx_rate":              (get_fx_rate, FXRateInput),
}

TOOL_SCHEMAS = [
    {
        "type": "function",
        "function": {
            "name": "get_account_status",
            "description": "Check the current status of a Skydo user account (active, blocked, suspended).",
            "parameters": AccountStatusInput.model_json_schema(),
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_transaction_details",
            "description": "Retrieve details of a payment transaction by ID or UTR number.",
            "parameters": TransactionInput.model_json_schema(),
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_kyc_status",
            "description": "Get the KYC verification status for a user.",
            "parameters": KYCStatusInput.model_json_schema(),
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_fx_rate",
            "description": "Get the current foreign exchange rate between two currencies.",
            "parameters": FXRateInput.model_json_schema(),
        },
    },
]

# ── Safe executor with retry + error passthrough ────────────────────────────────

import asyncio
from typing import Any

async def execute_tool(name: str, raw_args: dict, max_retries: int = 2) -> Any:
    if name not in TOOL_MAP:
        return {"error": f"Tool '{name}' not found. Only read-only tools are available."}
    
    fn, schema = TOOL_MAP[name]
    try:
        validated = schema(**raw_args)
    except Exception as e:
        return {"error": f"Invalid tool arguments: {e}"}
    
    for attempt in range(max_retries + 1):
        try:
            return await fn(validated)
        except httpx.TimeoutException:
            if attempt < max_retries:
                await asyncio.sleep(0.5 * (2 ** attempt))
            else:
                return {"error": f"Tool '{name}' timed out after {max_retries + 1} attempts."}
        except Exception as e:
            return {"error": f"Tool '{name}' failed: {str(e)}"}
```

---

## 7. Component 5 — Agent Orchestration Loop

The orchestrator is built on LangGraph. It maintains typed state, runs the ReAct loop, gates auto-send on confidence, and routes to HITL when needed.

```python
from typing import Annotated, TypedDict, Optional
from langgraph.graph import StateGraph, END
from langgraph.checkpoint.memory import MemorySaver
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, SystemMessage, AIMessage, BaseMessage, ToolMessage
import operator
import json

# ── State ───────────────────────────────────────────────────────────────────────

class SupportAgentState(TypedDict):
    ticket_id: str
    masked_body: str
    intent: str
    route: str                              # auto | assisted | human
    messages: Annotated[list[BaseMessage], operator.add]
    retrieved_context: str
    tool_call_count: int
    draft_response: str
    confidence: float
    policy_check_passed: bool
    needs_human_review: bool
    human_decision: str                     # approved | edited | rejected
    final_response: str
    audit_events: Annotated[list[dict], operator.add]

# ── LLM ─────────────────────────────────────────────────────────────────────────

llm = ChatOpenAI(model="gpt-4o", temperature=0)
llm_with_tools = llm.bind_tools(TOOL_SCHEMAS)

# ── System prompt ────────────────────────────────────────────────────────────────

AGENT_SYSTEM_PROMPT = """You are a support agent copilot for Skydo, a cross-border payments platform.
You ONLY have read-only access to customer data. You CANNOT initiate refunds, change account settings, or take any write actions.

Your job:
1. Understand the customer's issue from the ticket.
2. Use tools to look up relevant account/transaction data when needed.
3. Use the provided knowledge base context to answer policy and FAQ questions.
4. Draft a clear, empathetic response that resolves or advances the ticket.

Rules:
- NEVER reveal internal system names, API keys, or tool structure to the customer.
- If the issue requires a write action (refund, unblock, KYC re-trigger), state that a human agent will handle it and explain what they'll do.
- Keep responses concise and action-oriented.
- If you are uncertain, say so honestly and escalate rather than guess.
- Always cite the policy or data source that supports your answer.

Context from knowledge base:
{retrieved_context}
"""

# ── Nodes ────────────────────────────────────────────────────────────────────────

def retrieve_context_node(state: SupportAgentState) -> SupportAgentState:
    chunks = hybrid_retrieve(state["masked_body"], top_k=5)
    context_text = "\n\n".join(
        f"[Source: {c.source}]\n{c.text}" for c in chunks
    )
    return {
        "retrieved_context": context_text,
        "audit_events": [{"event": "rag_retrieval", "chunk_count": len(chunks)}],
    }

def agent_node(state: SupportAgentState) -> SupportAgentState:
    system = SystemMessage(
        content=AGENT_SYSTEM_PROMPT.format(retrieved_context=state["retrieved_context"])
    )
    
    # First call: inject the ticket
    if not state["messages"]:
        messages = [system, HumanMessage(content=state["masked_body"])]
    else:
        messages = [system] + state["messages"]
    
    response = llm_with_tools.invoke(messages)
    
    return {
        "messages": [response],
        "tool_call_count": state["tool_call_count"],
        "audit_events": [{"event": "llm_call", "model": "gpt-4o", "has_tool_calls": bool(getattr(response, "tool_calls", None))}],
    }

async def tool_node(state: SupportAgentState) -> SupportAgentState:
    last_message = state["messages"][-1]
    tool_results = []
    audit = []
    
    for tc in last_message.tool_calls:
        raw_args = json.loads(tc["args"]) if isinstance(tc["args"], str) else tc["args"]
        result = await execute_tool(tc["name"], raw_args)
        
        tool_results.append(ToolMessage(
            content=json.dumps(result),
            tool_call_id=tc["id"],
        ))
        audit.append({
            "event": "tool_call",
            "tool": tc["name"],
            "args": raw_args,
            "result_summary": str(result)[:200],
        })
    
    return {
        "messages": tool_results,
        "tool_call_count": state["tool_call_count"] + len(tool_results),
        "audit_events": audit,
    }

def draft_response_node(state: SupportAgentState) -> SupportAgentState:
    """Extract the final draft from the last non-tool-call assistant message."""
    last = state["messages"][-1]
    draft = last.content if hasattr(last, "content") else ""
    
    # Confidence heuristic: use the triage confidence; can be augmented with
    # a separate self-eval call if needed.
    confidence = state.get("confidence", 0.75)
    
    return {
        "draft_response": draft,
        "confidence": confidence,
        "audit_events": [{"event": "draft_created", "length": len(draft)}],
    }

# ── Routing ──────────────────────────────────────────────────────────────────────

MAX_TOOL_CALLS = 6

def should_continue(state: SupportAgentState) -> str:
    last = state["messages"][-1]
    
    # Safety: cap tool calls
    if state["tool_call_count"] >= MAX_TOOL_CALLS:
        return "draft"
    
    # If last message has tool calls, execute them
    if hasattr(last, "tool_calls") and last.tool_calls:
        return "tools"
    
    # Otherwise proceed to draft
    return "draft"

def policy_check_routing(state: SupportAgentState) -> str:
    if not state["policy_check_passed"]:
        return "human_review"
    if state["needs_human_review"] or state["confidence"] < AUTO_RESOLVE_CONFIDENCE:
        return "human_review"
    return "auto_send"

# ── Graph ────────────────────────────────────────────────────────────────────────

def build_support_agent():
    graph = StateGraph(SupportAgentState)
    
    graph.add_node("retrieve_context", retrieve_context_node)
    graph.add_node("agent", agent_node)
    graph.add_node("tools", tool_node)
    graph.add_node("draft_response", draft_response_node)
    graph.add_node("policy_check", policy_check_node)         # defined in Component 7
    graph.add_node("human_review", human_review_node)         # defined in Component 6
    graph.add_node("auto_send", auto_send_node)
    
    graph.set_entry_point("retrieve_context")
    graph.add_edge("retrieve_context", "agent")
    graph.add_conditional_edges("agent", should_continue, {
        "tools": "tools",
        "draft": "draft_response",
    })
    graph.add_edge("tools", "agent")
    graph.add_edge("draft_response", "policy_check")
    graph.add_conditional_edges("policy_check", policy_check_routing, {
        "human_review": "human_review",
        "auto_send": "auto_send",
    })
    graph.add_edge("human_review", END)
    graph.add_edge("auto_send", END)
    
    checkpointer = MemorySaver()  # use PostgresSaver in production
    return graph.compile(checkpointer=checkpointer, interrupt_before=["human_review"])

support_agent = build_support_agent()
```

---

## 8. Component 6 — Human-in-the-Loop (HITL)

HITL is triggered when: confidence < threshold, policy check fails, intent is in `ALWAYS_HUMAN` set, or tool call returns a critical finding.

### HITL Node

```python
from langgraph.types import interrupt

def human_review_node(state: SupportAgentState) -> SupportAgentState:
    """
    Pauses graph execution. The graph is resumed externally via the HITL dashboard.
    interrupt() serializes current state to the checkpointer and surfaces to the UI.
    """
    decision = interrupt({
        "ticket_id": state["ticket_id"],
        "intent": state["intent"],
        "draft_response": state["draft_response"],
        "retrieved_context": state["retrieved_context"],
        "confidence": state["confidence"],
        "policy_check_passed": state["policy_check_passed"],
        "prompt": "Review and approve, edit, or reject this draft response.",
    })
    
    # decision is a dict: {"action": "approved"|"edited"|"rejected", "edited_response": "..."}
    action = decision.get("action", "rejected")
    edited = decision.get("edited_response", state["draft_response"])
    
    final = edited if action in ("approved", "edited") else ""
    
    return {
        "human_decision": action,
        "final_response": final,
        "audit_events": [{
            "event": "hitl_decision",
            "action": action,
            "response_length": len(final),
        }],
    }
```

### Resuming from the HITL Dashboard

The Retool dashboard calls this endpoint when a human makes a decision:

```python
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI()

class HITLDecision(BaseModel):
    thread_id: str
    action: str         # approved | edited | rejected
    edited_response: str = ""

@app.post("/hitl/decision")
async def submit_hitl_decision(decision: HITLDecision):
    thread_config = {"configurable": {"thread_id": decision.thread_id}}
    
    # Resume the paused LangGraph run with the human's input
    result = support_agent.invoke(
        {
            "action": decision.action,
            "edited_response": decision.edited_response,
        },
        config=thread_config,
    )
    
    await send_response_to_crm(result["ticket_id"], result["final_response"])
    await write_audit_log(result)
    
    return {"status": "done", "ticket_id": result["ticket_id"]}
```

---

## 9. Component 7 — Policy Checks & Compliance Guardrails

Every draft response is validated against a set of compliance rules before it is sent or shown to a human reviewer.

```python
from pydantic import BaseModel
from openai import OpenAI

client = OpenAI()

class PolicyCheckResult(BaseModel):
    passed: bool
    violations: list[str]   # list of rule names that were violated
    risk_level: str         # low | medium | high
    explanation: str

COMPLIANCE_RULES = """
1. NEVER promise specific refund timelines unless explicitly stated in Skydo's refund policy.
2. NEVER share another customer's account or transaction details.
3. NEVER state that Skydo is liable for third-party bank delays.
4. NEVER advise customers to bypass KYC requirements.
5. NEVER mention specific internal system names, database names, or API endpoints.
6. NEVER make commitments on behalf of the compliance or legal team.
7. If the issue involves a regulatory hold, do NOT explain the specific regulation — only say it's under review.
8. Responses must not contain PII of any user other than the requester.
"""

POLICY_CHECK_PROMPT = """
You are a compliance officer reviewing a customer support response.

Check the following draft response against these rules:
{rules}

Ticket context (masked):
{ticket}

Draft response:
{draft}

Return a structured assessment.
""".strip()

def run_policy_check(ticket_body: str, draft_response: str) -> PolicyCheckResult:
    response = client.beta.chat.completions.parse(
        model="gpt-4o-mini",
        messages=[
            {
                "role": "system",
                "content": POLICY_CHECK_PROMPT.format(
                    rules=COMPLIANCE_RULES,
                    ticket=ticket_body,
                    draft=draft_response,
                ),
            }
        ],
        response_format=PolicyCheckResult,
    )
    return response.choices[0].message.parsed

def policy_check_node(state: SupportAgentState) -> SupportAgentState:
    result = run_policy_check(state["masked_body"], state["draft_response"])
    
    return {
        "policy_check_passed": result.passed,
        "needs_human_review": state["needs_human_review"] or not result.passed,
        "audit_events": [{
            "event": "policy_check",
            "passed": result.passed,
            "violations": result.violations,
            "risk_level": result.risk_level,
        }],
    }
```

### Structural Guardrails (rule-based, no LLM)

```python
import re

PII_LEAK_PATTERNS = [
    r"\b[A-Z]{5}\d{4}[A-Z]\b",          # PAN card
    r"\b\d{12}\b",                        # Aadhaar
    r"[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+",  # email
    r"\+?\d[\d\s\-]{8,}\d",              # phone
]

def structural_pii_check(draft: str) -> list[str]:
    """Fast regex guard — runs before LLM policy check."""
    found = []
    for pattern in PII_LEAK_PATTERNS:
        if re.search(pattern, draft):
            found.append(f"PII pattern detected: {pattern}")
    return found

def auto_send_node(state: SupportAgentState) -> SupportAgentState:
    # Final structural check before auto-send
    pii_issues = structural_pii_check(state["draft_response"])
    if pii_issues:
        # Demote to human review even at this late stage
        return {
            "needs_human_review": True,
            "final_response": "",
            "audit_events": [{"event": "auto_send_blocked", "reason": pii_issues}],
        }
    
    return {
        "final_response": state["draft_response"],
        "human_decision": "auto_approved",
        "audit_events": [{"event": "auto_sent", "ticket_id": state["ticket_id"]}],
    }
```

---

## 10. Component 8 — Audit Logging

Every event in the pipeline must be logged immutably for compliance, debugging, and SLA tracking.

```python
import boto3
import uuid
from datetime import datetime, timezone

dynamodb = boto3.resource("dynamodb", region_name="ap-south-1")
audit_table = dynamodb.Table("support-copilot-audit")

def write_audit_log(state: SupportAgentState):
    """
    Writes all accumulated audit events for a ticket in one batch.
    Each event gets its own row (append-only, no updates).
    """
    trace_id = str(uuid.uuid4())
    ts = datetime.now(timezone.utc).isoformat()
    
    with audit_table.batch_writer() as batch:
        for event in state.get("audit_events", []):
            batch.put_item(Item={
                "pk": f"TICKET#{state['ticket_id']}",
                "sk": f"EVENT#{ts}#{uuid.uuid4()}",
                "trace_id": trace_id,
                "ticket_id": state["ticket_id"],
                "intent": state.get("intent", ""),
                "event_type": event.get("event", "unknown"),
                "payload": event,
                "route": state.get("route", ""),
                "human_decision": state.get("human_decision", ""),
                "ttl": int((datetime.now(timezone.utc).timestamp()) + 60 * 60 * 24 * 90),  # 90 day TTL
            })
```

### What Gets Logged

| Event | Fields |
|---|---|
| `pii_mask` | ticket_id, entity_types_found, count |
| `triage` | intent, confidence, route |
| `rag_retrieval` | query, chunk_count, top_sources |
| `llm_call` | model, token_count, has_tool_calls |
| `tool_call` | tool_name, args (masked), result_summary |
| `policy_check` | passed, violations, risk_level |
| `hitl_decision` | action (approved/edited/rejected), agent_id |
| `auto_sent` | ticket_id, response_length |
| `final_resolution` | resolution_type, sla_ms |

---

## 11. Component 9 — Draft Response & Resolution Engine

```python
async def run_ticket(ticket: NormalizedTicket) -> dict:
    triage = classify_ticket(ticket.masked_body)
    route = route_ticket(triage)
    
    thread_id = f"ticket-{ticket.ticket_id}"
    thread_config = {"configurable": {"thread_id": thread_id}}
    
    initial_state: SupportAgentState = {
        "ticket_id": ticket.ticket_id,
        "masked_body": ticket.masked_body,
        "intent": triage.intent,
        "route": route,
        "messages": [],
        "retrieved_context": "",
        "tool_call_count": 0,
        "draft_response": "",
        "confidence": triage.confidence,
        "policy_check_passed": False,
        "needs_human_review": triage.needs_human or route == "human",
        "human_decision": "",
        "final_response": "",
        "audit_events": [
            {"event": "triage", "intent": triage.intent, "confidence": triage.confidence, "route": route}
        ],
    }
    
    # Run agent (will pause at human_review if HITL is needed)
    result = await support_agent.ainvoke(initial_state, config=thread_config)
    
    # If auto-resolved, send immediately
    if result.get("final_response"):
        await send_response_to_crm(ticket.ticket_id, result["final_response"], ticket.pii_map)
    
    # Always write audit log
    await write_audit_log(result)
    
    return {
        "ticket_id": ticket.ticket_id,
        "status": "auto_resolved" if result.get("final_response") else "pending_human_review",
        "thread_id": thread_id,
    }

async def send_response_to_crm(ticket_id: str, response: str, pii_map: dict):
    """Unmask PII before writing to CRM — PII never goes through the LLM."""
    final_text = unmask_pii(response, pii_map)
    # POST to Freshdesk/Zendesk API
    async with httpx.AsyncClient() as c:
        await c.post(
            f"https://crm.internal/tickets/{ticket_id}/reply",
            json={"body": final_text},
            headers={"Authorization": "Bearer ..."},
        )
```

---

## 12. End-to-End Flow

```
1. Ticket arrives (Freshdesk webhook → SQS)
2. Lambda picks up message, calls run_ticket()
3. PII masking: Presidio + custom patterns → masked_body + pii_map
4. Intent classifier (GPT-4o-mini) → intent, confidence, route
5. If route == "human" → push to Zendesk human queue immediately, skip agent
6. Else → start LangGraph thread
7. RAG retrieval (Qdrant + Elasticsearch hybrid) → context injected into system prompt
8. Agent loop (GPT-4o):
     a. Reason about ticket
     b. If needs data → call read-only tool (account, txn, kyc, fx rate)
     c. Observe result → continue reasoning
     d. Generate draft response
9. Policy check (GPT-4o-mini) → validate against compliance rules
10. Structural PII check (regex) → ensure no PII leakage
11. If all pass + confidence ≥ 0.85 → auto-send (unmask PII, write to CRM)
12. If confidence < 0.85 OR policy fail → LangGraph pauses at human_review node
13. HITL dashboard shows draft + evidence to support agent
14. Agent approves / edits / rejects
15. Resume LangGraph → send final response
16. Audit log written (all events, immutable, 90-day TTL)
17. SLA timer reset on CRM
```

### Mermaid Runtime Sequence

```mermaid
sequenceDiagram
    autonumber
    participant CRM as CRM / Chat
    participant Ingest as Ingestion
    participant Mask as PII Masker
    participant Triage as Triage Classifier
    participant Agent as Orchestrator Agent
    participant RAG as Hybrid RAG
    participant Tools as Read-only Tools
    participant Guard as Policy Guard
    participant HITL as Human Review
    participant Audit as Audit Log

    CRM->>Ingest: New ticket webhook
    Ingest->>Mask: Normalize ticket and assign trace ID
    Mask-->>Ingest: Masked body + encrypted PII map
    Ingest->>Triage: Classify intent, urgency, confidence

    alt Sensitive, explicit escalation, or low confidence
        Triage->>HITL: Route directly to human queue
        HITL->>Audit: Record human-owned routing decision
    else Eligible for agent handling
        Triage->>Agent: Start LangGraph thread
        Agent->>RAG: Retrieve source-attributed policy context
        RAG-->>Agent: Evidence chunks + document versions
        Agent->>Tools: Fetch account / transaction / KYC / FX state
        Tools-->>Agent: Fresh read-only facts with timestamps
        Agent->>Guard: Submit draft response and evidence
        Guard-->>Agent: Policy result + PII result

        alt Passes policy and auto-send gates
            Agent->>CRM: Send final response through CRM
            Agent->>Audit: Record model, prompt, evidence, tool calls, policy version
        else Fails gate or needs judgment
            Agent->>HITL: Pause for approve / edit / reject
            HITL->>CRM: Send reviewed response
            HITL->>Audit: Record reviewer action and final text
        end
    end
```

---

## 13. Infrastructure & Deployment

This section covers everything needed to run the system in production: architecture, containers, IaC, CI/CD pipeline, secrets, networking, scaling, persistence, health checks, and runbook.

### 13.0 Infrastructure Design Stance

The infrastructure is split into three responsibility zones:

| Zone | Components | Primary concern |
|---|---|---|
| Runtime plane | Worker, HITL API, SQS, LangGraph checkpoints, model gateway client | Process tickets reliably with bounded retries and backpressure |
| Knowledge and policy plane | S3 policy docs, Qdrant, OpenSearch, prompt registry, policy version pointer | Serve current, source-attributed knowledge without coupling to deploys |
| Governance plane | Audit log, Langfuse traces, eval store, dashboards, threshold config, kill switches | Prove behavior, detect regressions, and control autonomy level |

This prevents the common failure mode where a prompt or threshold change is treated like "content" while it actually changes production behavior. Any artifact that changes customer-visible autonomy is versioned, auditable, and tied to an evaluation result.

---

### 13.1 Full AWS Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         AWS ap-south-1 VPC                          │
│                                                                     │
│  ┌──────────────┐     ┌──────────────────────────────────────────┐  │
│  │  Internet    │     │  Private Subnet                          │  │
│  │  Gateway     │     │                                          │  │
│  └──────┬───────┘     │  ┌────────────────────────────────────┐ │  │
│         │             │  │  ECS Fargate Cluster               │ │  │
│  ┌──────▼───────┐     │  │                                    │ │  │
│  │  API Gateway │     │  │  ┌──────────────────────────────┐  │ │  │
│  │  (webhook    │     │  │  │  support-copilot-worker       │  │ │  │
│  │   receiver)  │     │  │  │  (2–10 tasks, HPA)           │  │ │  │
│  └──────┬───────┘     │  │  │                              │  │ │  │
│         │             │  │  │  • LangGraph agent loop      │  │ │  │
│  ┌──────▼───────┐     │  │  │  • Hybrid RAG retriever      │  │ │  │
│  │  Lambda      │     │  │  │  • Tool executor             │  │ │  │
│  │  (ingestor)  │     │  │  │  • Policy checker            │  │ │  │
│  └──────┬───────┘     │  │  └──────────────────────────────┘  │ │  │
│         │             │  │                                    │ │  │
│  ┌──────▼───────┐     │  │  ┌──────────────────────────────┐  │ │  │
│  │  SQS         │────▶│  │  │  support-copilot-api         │  │ │  │
│  │  (ticket-    │     │  │  │  (FastAPI HITL endpoints)    │  │ │  │
│  │   processing)│     │  │  └──────────────────────────────┘  │ │  │
│  └──────────────┘     │  └────────────────────────────────────┘ │  │
│                       │                                          │  │
│                       │  ┌─────────────────────────────────┐    │  │
│                       │  │  Data Layer                     │    │  │
│                       │  │                                 │    │  │
│                       │  │  PostgreSQL (RDS)               │    │  │
│                       │  │   └── LangGraph checkpoints     │    │  │
│                       │  │                                 │    │  │
│                       │  │  DynamoDB                       │    │  │
│                       │  │   └── Audit log (append-only)   │    │  │
│                       │  │                                 │    │  │
│                       │  │  AWS OpenSearch                 │    │  │
│                       │  │   └── BM25 keyword search       │    │  │
│                       │  │                                 │    │  │
│                       │  │  Qdrant (ECS, single-node)      │    │  │
│                       │  │   └── Vector search             │    │  │
│                       │  │                                 │    │  │
│                       │  │  ElastiCache Redis              │    │  │
│                       │  │   └── Semantic response cache   │    │  │
│                       │  │                                 │    │  │
│                       │  │  S3                             │    │  │
│                       │  │   └── Policy docs + model artefacts  │  │
│                       │  └─────────────────────────────────┘    │  │
│                       └──────────────────────────────────────────┘  │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Shared Services                                            │   │
│  │  Secrets Manager → API keys   CloudWatch → metrics/alerts  │   │
│  │  ECR → container images       Langfuse (ECS) → LLM traces  │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘

External:
  Retool dashboard  →  FastAPI /hitl/* (via VPC Link + NLB)
  Freshdesk/Zendesk →  API Gateway webhook
  OpenAI API        →  outbound (NAT Gateway)
  Internal APIs     →  VPC-internal (no internet egress)
```

---

### 13.2 Container Setup

**Two containers are deployed:**

| Container | Purpose | Port |
|---|---|---|
| `support-copilot-worker` | SQS consumer, runs LangGraph agent | internal only |
| `support-copilot-api` | FastAPI server for HITL endpoints | 8080 |

#### Worker Dockerfile

```dockerfile
FROM python:3.11-slim AS builder

WORKDIR /build
COPY requirements.txt .
RUN pip install --no-cache-dir --target=/deps -r requirements.txt

FROM python:3.11-slim

WORKDIR /app
COPY --from=builder /deps /deps
ENV PYTHONPATH=/app:/deps

COPY src/ ./src/

# Non-root user for security
RUN useradd -m -u 1000 copilot
USER copilot

CMD ["python", "-m", "src.worker"]
```

#### API Dockerfile

```dockerfile
FROM python:3.11-slim AS builder

WORKDIR /build
COPY requirements.txt .
RUN pip install --no-cache-dir --target=/deps -r requirements.txt

FROM python:3.11-slim

WORKDIR /app
COPY --from=builder /deps /deps
ENV PYTHONPATH=/app:/deps

COPY src/ ./src/

RUN useradd -m -u 1000 copilot
USER copilot

EXPOSE 8080
CMD ["uvicorn", "src.hitl.api:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "4"]
```

#### Health check endpoint (added to FastAPI)

```python
# src/hitl/api.py
@app.get("/health")
async def health():
    """ECS health check target."""
    return {"status": "ok"}

@app.get("/ready")
async def readiness():
    """Checks downstream dependencies before marking task ready."""
    checks = {}
    try:
        qdrant.get_collection(COLLECTION_NAME)
        checks["qdrant"] = "ok"
    except Exception as e:
        checks["qdrant"] = f"error: {e}"

    try:
        db_conn = await get_pg_connection()
        await db_conn.execute("SELECT 1")
        checks["postgres"] = "ok"
    except Exception as e:
        checks["postgres"] = f"error: {e}"

    all_ok = all(v == "ok" for v in checks.values())
    return JSONResponse(
        content={"status": "ready" if all_ok else "degraded", "checks": checks},
        status_code=200 if all_ok else 503,
    )
```

#### Worker startup (SQS consumer)

```python
# src/worker.py
import asyncio
import boto3
import json
import logging
from src.ingest.pii import mask_pii
from src.ingest.normalizer import NormalizedTicket
from src.agent.graph import run_ticket

logger = logging.getLogger(__name__)
sqs = boto3.client("sqs", region_name="ap-south-1")
QUEUE_URL = "https://sqs.ap-south-1.amazonaws.com/123456789/ticket-processing"

async def process_message(msg: dict):
    body = json.loads(msg["Body"])
    ticket = NormalizedTicket(**body)
    result = await run_ticket(ticket)
    logger.info("ticket_processed", extra={"ticket_id": ticket.ticket_id, "status": result["status"]})

async def poll_loop():
    logger.info("Worker started, polling SQS...")
    while True:
        response = sqs.receive_message(
            QueueUrl=QUEUE_URL,
            MaxNumberOfMessages=5,       # process up to 5 tickets concurrently
            WaitTimeSeconds=20,          # long polling — reduces empty receives
            VisibilityTimeout=300,       # 5 min; enough for a full agent run
        )
        messages = response.get("Messages", [])
        if not messages:
            continue

        tasks = [process_message(m) for m in messages]
        results = await asyncio.gather(*tasks, return_exceptions=True)

        for msg, result in zip(messages, results):
            if isinstance(result, Exception):
                logger.error("message_failed", extra={"error": str(result), "msg_id": msg["MessageId"]})
                # Do NOT delete — message returns to queue after VisibilityTimeout
            else:
                sqs.delete_message(QueueUrl=QUEUE_URL, ReceiptHandle=msg["ReceiptHandle"])

if __name__ == "__main__":
    asyncio.run(poll_loop())
```

---

### 13.3 LangGraph Checkpointer — PostgreSQL (Production)

`MemorySaver` is in-memory only — it cannot survive a container restart or scale across multiple worker tasks. In production, use `AsyncPostgresSaver`.

```python
# src/agent/graph.py
import os
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
import asyncpg

POSTGRES_DSN = os.environ["POSTGRES_DSN"]
# e.g. postgresql://user:pass@rds-host:5432/copilot_db

async def build_support_agent_prod():
    """Call once at worker startup; reuse the app across all ticket runs."""
    conn = await asyncpg.connect(POSTGRES_DSN)
    checkpointer = AsyncPostgresSaver(conn)
    await checkpointer.setup()   # creates checkpoint tables if they don't exist

    graph = StateGraph(SupportAgentState)
    # ... (same nodes and edges as before) ...
    
    return graph.compile(
        checkpointer=checkpointer,
        interrupt_before=["human_review"],
    )
```

**Why PostgreSQL for checkpoints:**
- HITL pause can last hours (human agent is offline). The state must persist across restarts.
- Multiple worker tasks can all read/write the same checkpoint store.
- RDS PostgreSQL is already inside the VPC — no extra network hop.
- `thread_id = ticket_id` makes lookups O(1) by primary key.

**RDS table created by `checkpointer.setup()`:**

```
checkpoints          — one row per (thread_id, checkpoint_id)
checkpoint_blobs     — binary state payloads
checkpoint_writes    — pending write queue
```

---

### 13.4 Terraform Infrastructure-as-Code

Key resources. Each module lives in `infra/modules/`.

#### SQS Queue

```hcl
# infra/modules/sqs/main.tf
resource "aws_sqs_queue" "ticket_processing" {
  name                       = "ticket-processing"
  visibility_timeout_seconds = 300      # must match worker VisibilityTimeout
  message_retention_seconds  = 86400    # 24 hours
  receive_wait_time_seconds  = 20       # long polling

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.ticket_dlq.arn
    maxReceiveCount     = 3             # after 3 failures → DLQ
  })
}

resource "aws_sqs_queue" "ticket_dlq" {
  name                      = "ticket-processing-dlq"
  message_retention_seconds = 1209600   # 14 days; time to investigate
}

# CloudWatch alarm: alert if DLQ depth > 0
resource "aws_cloudwatch_metric_alarm" "dlq_alarm" {
  alarm_name          = "ticket-dlq-non-empty"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  dimensions          = { QueueName = aws_sqs_queue.ticket_dlq.name }
  alarm_actions       = [aws_sns_topic.alerts.arn]
}
```

#### ECS Fargate — Worker Task Definition

```hcl
# infra/modules/ecs/worker.tf
resource "aws_ecs_task_definition" "worker" {
  family                   = "support-copilot-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "1024"    # 1 vCPU
  memory                   = "2048"   # 2 GB

  task_role_arn      = aws_iam_role.worker_task_role.arn
  execution_role_arn = aws_iam_role.ecs_execution_role.arn

  container_definitions = jsonencode([{
    name      = "worker"
    image     = "${aws_ecr_repository.copilot.repository_url}:${var.image_tag}"
    essential = true

    environment = [
      { name = "QDRANT_HOST",                value = "qdrant.internal" },
      { name = "QDRANT_PORT",                value = "6333" },
      { name = "ES_HOST",                    value = "https://opensearch.internal" },
      { name = "DYNAMODB_TABLE",             value = "support-copilot-audit" },
      { name = "AUTO_RESOLVE_CONFIDENCE",    value = "0.85" },
      { name = "MAX_TOOL_CALLS",             value = "6" },
    ]

    secrets = [
      { name = "OPENAI_API_KEY",    valueFrom = aws_secretsmanager_secret.openai_key.arn },
      { name = "INTERNAL_API_KEY",  valueFrom = aws_secretsmanager_secret.internal_key.arn },
      { name = "POSTGRES_DSN",      valueFrom = aws_secretsmanager_secret.postgres_dsn.arn },
      { name = "LANGFUSE_SECRET_KEY", valueFrom = aws_secretsmanager_secret.langfuse.arn },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options   = {
        "awslogs-group"         = "/ecs/support-copilot-worker"
        "awslogs-region"        = "ap-south-1"
        "awslogs-stream-prefix" = "worker"
      }
    }
  }])
}

resource "aws_ecs_service" "worker" {
  name            = "support-copilot-worker"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = 2
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.worker_sg.id]
    assign_public_ip = false
  }

  # Auto-scaling based on SQS queue depth
  lifecycle {
    ignore_changes = [desired_count]
  }
}

# Auto Scaling: scale out when queue depth > 50, scale in when < 10
resource "aws_appautoscaling_target" "worker" {
  max_capacity       = 10
  min_capacity       = 2
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.worker.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "worker_scale_out" {
  name               = "worker-scale-out"
  policy_type        = "StepScaling"
  resource_id        = aws_appautoscaling_target.worker.resource_id
  scalable_dimension = aws_appautoscaling_target.worker.scalable_dimension
  service_namespace  = aws_appautoscaling_target.worker.service_namespace

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 60
    metric_aggregation_type = "Maximum"

    step_adjustment {
      metric_interval_lower_bound = 0
      metric_interval_upper_bound = 100
      scaling_adjustment          = 2
    }
    step_adjustment {
      metric_interval_lower_bound = 100
      scaling_adjustment          = 4
    }
  }
}
```

#### IAM — Least-Privilege Worker Role

```hcl
# infra/modules/iam/worker_role.tf
resource "aws_iam_role_policy" "worker_policy" {
  name = "support-copilot-worker"
  role = aws_iam_role.worker_task_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # SQS: consume from ticket queue only
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
        Resource = aws_sqs_queue.ticket_processing.arn
      },
      {
        # DynamoDB: write audit log only (no reads, no deletes)
        Effect   = "Allow"
        Action   = ["dynamodb:PutItem", "dynamodb:BatchWriteItem"]
        Resource = aws_dynamodb_table.audit_log.arn
      },
      {
        # Secrets Manager: read-only, specific secrets
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [
          aws_secretsmanager_secret.openai_key.arn,
          aws_secretsmanager_secret.internal_key.arn,
          aws_secretsmanager_secret.postgres_dsn.arn,
          aws_secretsmanager_secret.langfuse.arn,
        ]
      },
      {
        # S3: read policy documents only
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "${aws_s3_bucket.policy_docs.arn}/*"
      },
    ]
  })
}
```

---

### 13.5 CI/CD Pipeline (GitHub Actions)

```
┌─────────────────────────────────────────────────────────────────────┐
│  GitHub Actions Pipeline                                            │
│                                                                     │
│  on: push to main                                                   │
│                                                                     │
│  Job 1: test                                                        │
│    pytest tests/ --cov=src --cov-fail-under=80                      │
│    ruff check src/                                                  │
│    Run golden-set eval (fail if accuracy drops > 5%)               │
│                                                                     │
│  Job 2: build (needs: test)                                         │
│    docker build -t worker -f Dockerfile.worker .                    │
│    docker build -t api    -f Dockerfile.api .                       │
│    Push both images to ECR with SHA tag                             │
│                                                                     │
│  Job 3: deploy-staging (needs: build)                               │
│    terraform apply -var image_tag=$SHA -target=module.ecs_staging   │
│    Smoke test: POST test ticket → assert auto-resolved in < 60s     │
│                                                                     │
│  Job 4: deploy-prod (needs: deploy-staging, manual approval)        │
│    terraform apply -var image_tag=$SHA -target=module.ecs_prod      │
│    Rolling update: ECS replaces tasks one at a time                 │
│    Verify: CloudWatch auto-resolve rate ≥ baseline for 5 min        │
└─────────────────────────────────────────────────────────────────────┘
```

```yaml
# .github/workflows/deploy.yml
name: Deploy Support Copilot

on:
  push:
    branches: [main]

env:
  AWS_REGION: ap-south-1
  ECR_REGISTRY: 123456789.dkr.ecr.ap-south-1.amazonaws.com

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: "3.11" }
      - run: pip install -r requirements.txt
      - run: pytest tests/ --cov=src --cov-fail-under=80
      - run: ruff check src/

  build:
    needs: test
    runs-on: ubuntu-latest
    outputs:
      image_tag: ${{ steps.tag.outputs.sha }}
    steps:
      - uses: actions/checkout@v4
      - id: tag
        run: echo "sha=${GITHUB_SHA::8}" >> $GITHUB_OUTPUT
      - name: Login to ECR
        run: aws ecr get-login-password | docker login --username AWS --password-stdin $ECR_REGISTRY
      - name: Build and push worker
        run: |
          docker build -f Dockerfile.worker -t $ECR_REGISTRY/copilot-worker:${{ steps.tag.outputs.sha }} .
          docker push $ECR_REGISTRY/copilot-worker:${{ steps.tag.outputs.sha }}
      - name: Build and push API
        run: |
          docker build -f Dockerfile.api -t $ECR_REGISTRY/copilot-api:${{ steps.tag.outputs.sha }} .
          docker push $ECR_REGISTRY/copilot-api:${{ steps.tag.outputs.sha }}

  deploy-staging:
    needs: build
    runs-on: ubuntu-latest
    environment: staging
    steps:
      - uses: actions/checkout@v4
      - run: |
          cd infra
          terraform init
          terraform apply -auto-approve \
            -var="image_tag=${{ needs.build.outputs.image_tag }}" \
            -var="environment=staging"
      - name: Smoke test
        run: python scripts/smoke_test.py --env staging --timeout 60

  deploy-prod:
    needs: deploy-staging
    runs-on: ubuntu-latest
    environment: production    # requires manual approval in GitHub Environments
    steps:
      - uses: actions/checkout@v4
      - run: |
          cd infra
          terraform apply -auto-approve \
            -var="image_tag=${{ needs.build.outputs.image_tag }}" \
            -var="environment=production"
```

---

### 13.6 Secrets Management

All secrets live in AWS Secrets Manager. They are injected as environment variables into the ECS task at startup — **never** baked into container images or passed through environment config files.

```
Secret Name                    Used By
─────────────────────────────────────────────────────────
/copilot/prod/openai-api-key   worker, api
/copilot/prod/internal-api-key worker
/copilot/prod/postgres-dsn     worker (checkpointer), api
/copilot/prod/langfuse-secret  worker, api
/copilot/prod/freshdesk-key    lambda (ingestor)
```

Rotation policy:
- OpenAI key: manual rotation every 90 days (no built-in AWS rotation available)
- Internal API key: automated rotation via Lambda every 30 days
- PostgreSQL DSN: RDS password rotation every 30 days via Secrets Manager native rotation

---

### 13.7 Networking & Security

```
Security Group: worker-sg
  Inbound:  NONE (worker is outbound-only; SQS uses VPC endpoint)
  Outbound: 443 → NAT Gateway (OpenAI API, Langfuse)
            6333 → Qdrant SG (vector DB)
            443  → OpenSearch SG (BM25)
            5432 → RDS SG (PostgreSQL checkpoints)
            443  → DynamoDB VPC Endpoint
            443  → S3 VPC Endpoint
            443  → SQS VPC Endpoint
            443  → Secrets Manager VPC Endpoint

Security Group: api-sg
  Inbound:  443 → NLB SG (HITL dashboard traffic)
  Outbound: 5432 → RDS SG
            443  → DynamoDB VPC Endpoint

VPC Endpoints (private, no internet traffic):
  S3, DynamoDB, SQS, Secrets Manager, ECR, CloudWatch Logs
```

Key security decisions:
- Worker has **no inbound ports** — it only reads from SQS and writes to internal services.
- All AWS API calls (S3, DynamoDB, SQS) go through VPC endpoints — never over the public internet.
- The only external egress is to OpenAI (via NAT Gateway) — this is the only traffic that leaves the VPC.
- Internal APIs are accessed within the VPC — no internet hop.

---

### 13.8 Scaling Model

| Signal | Action |
|---|---|
| SQS `ApproximateNumberOfMessagesVisible` > 50 | Scale out ECS worker +2 tasks |
| SQS `ApproximateNumberOfMessagesVisible` > 150 | Scale out ECS worker +4 tasks |
| SQS `ApproximateNumberOfMessagesVisible` < 10 for 5 min | Scale in ECS worker −1 task (floor: 2) |
| OpenAI rate limit 429 response | Exponential backoff (1s, 2s, 4s, 8s); SQS visibility timeout acts as natural backpressure |
| Qdrant / OpenSearch degraded | Worker continues without RAG context; logs `rag_degraded` event; alert fires |

SQS `VisibilityTimeout = 300s` means if a worker task crashes mid-processing, the message automatically becomes visible again after 5 minutes and is retried by another task. After 3 failures, the message goes to the DLQ for manual investigation.

---

### 13.9 Observability Stack

```
Logs      → CloudWatch Logs  (structured JSON, queryable with Logs Insights)
Metrics   → CloudWatch Metrics (auto-resolve rate, p50 latency, policy failures)
Traces    → Langfuse (every LLM call + tool call with token counts, cost, latency)
Alerts    → CloudWatch Alarms → SNS → PagerDuty / Slack

Key dashboards:
  1. Real-time ticket throughput (tickets/min by route)
  2. Auto-resolve rate (rolling 1h / 24h / 7d)
  3. First-response time p50 / p95
  4. LLM cost per ticket
  5. DLQ depth (should always be 0)
  6. Policy check failure rate
  7. HITL queue depth (number of tickets waiting for human review)
```

```python
# src/observability.py — structured logging helper
import logging
import json

class StructuredLogger:
    def __init__(self, name: str):
        self.logger = logging.getLogger(name)

    def info(self, event: str, **kwargs):
        self.logger.info(json.dumps({"event": event, **kwargs}))

    def error(self, event: str, **kwargs):
        self.logger.error(json.dumps({"event": event, **kwargs}))

logger = StructuredLogger("copilot")

# Usage in worker
logger.info("ticket_processed",
    ticket_id=ticket.ticket_id,
    intent=triage.intent,
    route=route,
    latency_ms=elapsed,
    tool_calls=result["tool_call_count"],
    auto_resolved=bool(result.get("final_response")),
)
```

---

### 13.10 Key Environment Variables

```bash
# LLM + external services
OPENAI_API_KEY=...            # from Secrets Manager
INTERNAL_API_KEY=...          # from Secrets Manager

# Persistence
POSTGRES_DSN=postgresql://... # LangGraph checkpoints (from Secrets Manager)
DYNAMODB_TABLE=support-copilot-audit

# Vector + keyword search
QDRANT_HOST=qdrant.internal
QDRANT_PORT=6333
ES_HOST=https://opensearch.internal:443

# Observability
LANGFUSE_SECRET_KEY=...
LANGFUSE_PUBLIC_KEY=...
LANGFUSE_HOST=https://langfuse.internal

# Agent behaviour (tunable without redeploy via SSM Parameter Store)
AUTO_RESOLVE_CONFIDENCE=0.85
MAX_TOOL_CALLS=6
ALWAYS_HUMAN_INTENTS=refund_request,compliance_query,escalation_request
```

`AUTO_RESOLVE_CONFIDENCE` and `MAX_TOOL_CALLS` are read from AWS SSM Parameter Store at startup, not baked into the image. This allows tuning thresholds without a redeploy.

---

### 13.11 Deployment Runbook

**Standard deploy (new code):**
```
1. Merge PR to main
2. GitHub Actions runs tests + golden-set eval automatically
3. Builds new Docker images, pushes to ECR with SHA tag
4. Auto-deploys to staging
5. Smoke test runs (POST 3 test tickets, assert resolved < 60s)
6. Requires manual approval in GitHub Environments to proceed to prod
7. ECS rolling update: drains old tasks one at a time, brings up new ones
8. Monitor CloudWatch auto-resolve rate for 10 min — rollback if drops > 10%
```

**Rollback:**
```bash
# ECS rollback to previous task definition revision
aws ecs update-service \
  --cluster support-copilot \
  --service support-copilot-worker \
  --task-definition support-copilot-worker:PREV_REVISION \
  --region ap-south-1
```

**Knowledge base update (new policies):**
```bash
# Upload new policy doc to S3
aws s3 cp new_policy.pdf s3://skydo-policy-docs/

# Re-index into Qdrant + OpenSearch (idempotent — upserts by doc_id)
python scripts/index_policies.py --source s3://skydo-policy-docs/new_policy.pdf

# No service restart needed; next ticket retrieval picks up new vectors immediately
```

**Threshold tuning (no redeploy needed):**
```bash
# Raise auto-resolve confidence threshold (more conservative)
aws ssm put-parameter \
  --name /copilot/prod/auto-resolve-confidence \
  --value 0.90 \
  --type String \
  --overwrite

# Worker reads this at next poll cycle (within 60s)
```

**Emergency: disable auto-send completely:**
```bash
# Set threshold to 1.01 (impossibly high → all tickets go to human)
aws ssm put-parameter \
  --name /copilot/prod/auto-resolve-confidence \
  --value 1.01 \
  --type String \
  --overwrite
# No restart needed; takes effect within 60s
```

---

## 14. Evaluation & Metrics

Evaluation is the governance layer for the copilot. A model, prompt, retriever, threshold, or policy change should not reach production auto-send unless it passes offline evals, shadow comparisons, and live guardrail checks.

### Service-Level Objectives

| SLO | Target | Error budget meaning |
|---|---:|---|
| Ticket ingestion availability | 99.9% monthly | Webhooks can be queued or retried; data loss is unacceptable |
| Auto-send policy violation rate | 0 known severe violations | Any severe violation disables auto-send until reviewed |
| PII leakage to LLM provider | 0 detected events | Incident response and model-log audit required |
| First draft latency p95 | <= 60s for assisted tickets | Slow drafts lose support-agent trust |
| Auto-resolve latency p95 | <= 120s for eligible tickets | Queueing or vendor latency should not recreate the original support delay |
| Audit completeness | 100% of processed tickets | Missing audit records block auto-send eligibility |

### Business Metrics (tracked in CloudWatch)

| Metric | Target | How measured |
|---|---|---|
| Auto-triage rate | ≥ 65% | Tickets routed by classifier without human |
| Auto-resolve rate | ≥ 35% | Tickets where final_response was auto-sent |
| Assisted-resolve rate | ~5% | Auto-draft accepted with no/minor edits |
| First-response time (p50) | ≤ 12 min | CRM timestamp delta |
| SLA breach rate | ≤ 50% of baseline | Tickets exceeding SLA window |
| HITL edit rate | Track weekly | How often humans edit vs approve |
| Policy check failure rate | Alert at > 5% | Policy violations per 100 tickets |
| Cost per resolved ticket | Track by intent and route | LLM tokens + retrieval + infra allocation |
| Human time saved | Track by intent and agent cohort | Baseline handling time minus review/edit time |

### Quality Metrics (weekly eval pipeline)

```python
# RAGAS-style eval on a sample of auto-resolved tickets
# Human reviewers rate: correct | partially correct | incorrect

from ragas import evaluate
from ragas.metrics import faithfulness, answer_relevancy, context_precision

# Sample 200 auto-resolved tickets per week
# Compare draft_response against retrieved_context for faithfulness
# Compare against human-verified ground truth for answer_relevancy
```

### Offline Eval Dataset

- Maintain a **golden set** of 500 tickets with verified correct responses.
- Run every model/prompt change against this set before deploying.
- Track: exact match rate, semantic similarity (cosine > 0.85), policy compliance pass rate.

### Promotion Gates for Customer-Visible Autonomy

| Gate | Required signal | Blocks promotion when |
|---|---|---|
| Offline golden set | Accuracy, faithfulness, and policy pass rate meet target for the target intent | Any severe policy miss, unsupported claim, or PII leak appears |
| Shadow comparison | Drafts match or improve on human response quality for the target intent | Human reviewers consistently prefer existing support responses |
| HITL approval | Support agents approve drafts with low edit distance | High edit rate indicates the agent is creating review burden |
| Live guardrails | No severe policy failures, no audit gaps, stable SLA metrics | SLA breach rate, complaint rate, or compliance flags regress |
| Cost guardrail | Cost per resolved ticket remains below assisted-human baseline | Token growth or retrieval fanout makes automation uneconomic |

### Evaluation Dataset Slices

Do not evaluate only the happy path. The golden set should be stratified by:

- Intent: payment failure, KYC query, FX rate, general FAQ, blocked account, compliance question.
- Risk: low-risk answer, sensitive financial answer, explicit human escalation, angry customer, ambiguous ticket.
- Language/channel: English, mixed-language messages, short chat messages, long email threads.
- Data dependency: no tool call, one tool call, multi-hop tool calls, stale or missing tool result.
- Policy version: current policy, recently changed policy, retired policy that must not be used.

### Regression Budget

Any change to prompts, retrieval ranking, model route, policy checks, or thresholds should compare against the last production baseline:

| Regression type | Allowed? | Action |
|---|---|---|
| Severe policy miss | No | Block release and add counterexample to golden set |
| PII leak | No | Block release, rotate affected logs if needed, review masking recognizers |
| Accuracy drop on target intent | No for auto-send intents | Keep assisted-only until root cause is fixed |
| Cost increase | Only with explicit approval | Require reason: better quality, lower latency, or broader coverage |
| Latency increase | Only within SLO | Check retrieval fanout, model route, and queue depth |

---

## 15. Rollout Strategy

Rollout is not a calendar plan; it is a trust-building sequence. Each phase expands autonomy only after the previous phase produces evidence that the next blast radius is acceptable.

### Phase 1 — Shadow Mode

- Agent runs on all tickets but outputs are **not sent** — only logged.
- Compare agent drafts against what human agents actually sent.
- Measure: draft quality, policy check pass rate, false positive rate on HITL escalation.
- Exit gate: no severe policy misses or PII leaks; draft quality is acceptable for the target low-risk intents.

### Phase 2 — Assisted Mode

- HITL mode for **all** tickets.
- Agent drafts are shown to human agents as suggestions.
- Humans approve/edit/reject — build training signal.
- Target: ≥ 70% approval rate before moving to Phase 3.
- Exit gate: support agents approve with low edit distance, review time decreases, and audit records are complete.

### Phase 3 — Auto-Resolve for Low-Risk Intents

- Enable auto-send for `confidence ≥ 0.85` AND `policy_check_passed`.
- Start with low-risk intents only: `fx_rate_query`, `general_faq`.
- Expand intent coverage as trust is established.
- Keep SLA breach rate and CSAT as circuit-breaker signals.
- Exit gate: live metrics stay inside SLO/error-budget boundaries and support leadership signs off on expanded intent coverage.

### Phase 4 — Platformization

- Extract common primitives: ticket normalization, PII masking, prompt registry, policy guard, audit logger, HITL queue, eval runner.
- Offer these as reusable libraries/services for other operations copilots.
- Require new use cases to define their own intent risk tiers, allowed tools, human approval rules, and golden sets.
- Avoid a generic "agent platform" too early; platformize only seams that have repeated across at least two workflows.

---

## 16. Key Failure Modes & Mitigations

| Failure | Mitigation |
|---|---|
| LLM hallucinates account data | All account data comes from read-only tool calls, never from LLM memory |
| PII leaks into response | Presidio pre-LLM + structural regex post-draft + policy check LLM |
| Agent loops on tool calls | `MAX_TOOL_CALLS = 6` hard cap; loop detection on repeated args |
| Policy check false positives (over-escalation) | Tune compliance rules quarterly; track HITL edit rate |
| Qdrant / ES downtime breaks RAG | RAG failure → agent proceeds with reduced context + logs warning; does not block resolution |
| LLM API rate limits | Async job queue + exponential backoff; SQS visibility timeout as natural backpressure |
| HITL dashboard unresponsive | SLA breach alert fires; tickets fall back to standard human queue |
| Intent classifier wrong category | Confidence threshold catches most errors; low-confidence always goes to human |
| Refund/compliance slips through auto-send | `ALWAYS_HUMAN` set hard-coded; not overridable by confidence score |
| Policy document changes but index is stale | Policy version pointer and index build status must match before auto-send uses that policy |
| Human reviewers stop trusting drafts | Track approval rate, edit distance, and reject reasons; roll back intent coverage if review burden rises |
| Prompt/model update silently changes behavior | Version every prompt and model route; require golden-set diff before promotion |
| Tool result is stale or unavailable | Include freshness timestamps in tool outputs; escalate when required data is missing or older than SLA |
| Auto-send incident affects many tickets | Intent allowlist, confidence threshold, and global auto-send kill switch limit blast radius |
| Audit log write fails | Treat as auto-send blocker; unresolved ticket goes to human queue with trace of failure |

### Principal Engineer Review Checklist

Before approving this design for production, challenge these points:

- **Should this be autonomous?** Which intents create enough business value to justify auto-send risk?
- **Where is the irreversible action?** Confirm every irreversible mutation remains human-owned or domain-service-owned.
- **What is the blast radius?** Verify kill switches, intent allowlists, queue isolation, and rate limits.
- **Can compliance reconstruct a decision?** Check audit completeness across prompt, context, tools, policy version, model version, and reviewer.
- **What happens when knowledge is wrong?** Validate policy versioning, stale-index handling, and source attribution.
- **Can support ops operate it without engineers?** Threshold tuning, intent toggles, queue fallback, and review dashboards must be usable by operators.
- **Is this becoming a platform for the right reasons?** Reuse shared safety primitives, but keep domain-specific policies and tools owned by the right teams.

---

## 17. Full Code Reference

### `requirements.txt`

```
openai>=1.30.0
langchain-openai>=0.1.0
langgraph>=0.2.0
langchain-core>=0.2.0
pydantic>=2.0.0
presidio-analyzer>=2.2.0
presidio-anonymizer>=2.2.0
qdrant-client>=1.9.0
elasticsearch>=8.0.0
httpx>=0.27.0
boto3>=1.34.0
fastapi>=0.111.0
uvicorn>=0.29.0
langfuse>=2.0.0
ragas>=0.1.0
numpy>=1.26.0
```

### Project Structure

```
support-copilot/
├── src/
│   ├── ingest/
│   │   ├── pii.py               # Presidio masking
│   │   └── normalizer.py        # NormalizedTicket schema
│   ├── triage/
│   │   └── classifier.py        # Intent classification
│   ├── rag/
│   │   ├── indexer.py           # Document indexing pipeline
│   │   └── retriever.py         # Hybrid retrieval
│   ├── tools/
│   │   └── readonly_tools.py    # All read-only tool definitions
│   ├── agent/
│   │   ├── state.py             # SupportAgentState TypedDict
│   │   ├── nodes.py             # All LangGraph nodes
│   │   ├── graph.py             # build_support_agent()
│   │   └── prompts.py           # System prompts
│   ├── guardrails/
│   │   └── policy_check.py      # Compliance policy checker
│   ├── audit/
│   │   └── logger.py            # DynamoDB audit writer
│   ├── hitl/
│   │   └── api.py               # FastAPI HITL endpoints
│   └── worker.py                # SQS consumer entrypoint
├── scripts/
│   ├── index_policies.py        # One-time KB indexing script
│   └── eval_golden_set.py       # Weekly eval pipeline
├── tests/
│   └── test_policy_check.py
├── requirements.txt
└── Dockerfile
```

---

## Key Design Decisions — Summary

| Decision | Rationale |
|---|---|
| **Read-only tool enforcement** | Eliminates entire class of accidental mutations; builds trust faster |
| **PII masking before LLM** | Regulatory requirement; no customer PII in LLM logs |
| **Hybrid RAG (BM25 + vector)** | Policy docs need exact keyword match; conversation needs semantic search |
| **Two-model stack** | GPT-4o-mini for cheap classification/policy check; GPT-4o for reasoning |
| **LangGraph over custom loop** | Checkpointing enables HITL pause/resume; graph makes routing explicit |
| **Confidence gating, not binary** | Three-zone routing (auto / assisted / human) maximizes automation while preserving safety |
| **Immutable audit log** | Compliance requirement; enables post-hoc debugging and model improvement |
| **Shadow mode rollout** | Builds trust with ops team; catches failure modes before they affect customers |
| **Qdrant over pgvector** | See ADR below — dedicated vector engine chosen for ANN performance, payload filtering, and operational isolation from the transactional DB |
| **Control plane separated from runtime** | Prompts, thresholds, model routes, policies, and intent allowlists change through governed config instead of code edits |
| **Intent-scoped autonomy** | Limits blast radius by allowing auto-send only for low-risk, well-evaluated ticket classes |
| **Evaluation as release gate** | Prompt/model/retrieval changes must beat the production baseline before customer-visible autonomy expands |

---

## ADR — Why Qdrant instead of pgvector

**Decision date:** Pre-launch architecture review
**Status:** Accepted

### Context

The knowledge base needs to serve low-latency approximate nearest-neighbor (ANN) search across ~500K–1M vectors (policy chunks, FAQ, resolved ticket embeddings) under concurrent ticket load. Two options were evaluated: `pgvector` (an extension on the existing RDS PostgreSQL instance) and a dedicated Qdrant cluster.

### Option A — pgvector on RDS PostgreSQL

pgvector adds a `vector` column type and two index types to PostgreSQL:

| Index | Algorithm | Notes |
|---|---|---|
| `ivfflat` | IVF + flat quantization | Default; exact scan within each list |
| `hnsw` | Hierarchical Navigable Small World | Added in pgvector 0.5; much faster ANN |

**Why it is appealing:**
- Zero new infrastructure — runs on the same RDS instance already used for LangGraph checkpoints.
- Same IAM role, same VPC security group, same backup/snapshot policy.
- Familiar SQL query interface; no new client library.
- Joins are trivial: vector search + metadata filter in one SQL query.

**Why we did not choose it:**

1. **HNSW index is in-memory on RDS.** PostgreSQL HNSW loads the entire graph into shared memory at startup. At 500K × 1536-dimension `float32` vectors that is ~3 GB of raw vectors, plus the graph overhead. RDS shared_buffers and work_mem would need to be sized to hold this in addition to the checkpoint tables and normal query working set. Competing memory pressure from two very different workloads (transactional writes for checkpoints, large ANN graph reads for RAG) on the same instance is operationally risky.

2. **No payload-filtered ANN in one pass.** pgvector executes a vector scan first and applies `WHERE` clauses as a post-filter. If you filter by `category = 'kyc_policy'` and only 5% of vectors match that category, pgvector scans all 500K vectors and discards 95% of results. Qdrant's HNSW implementation supports **pre-filtered ANN** — the index is partitioned by payload fields, so a filtered search only traverses the sub-graph for that category. This matters here because intent-aware retrieval (only retrieve from `payment_policy` docs for `payment_failure` tickets) is a core latency optimization.

3. **Mixing transactional and vector workloads on one instance is an ops risk.** A slow VACUUM, autovacuum lock, or checkpoint storm on the transactional side can spike p99 vector query latency. Conversely, a large ANN scan holds shared memory that the checkpointer also needs. Isolating the two workloads to separate services gives independent scaling and independent failure blast radius.

4. **No built-in quantization.** pgvector stores vectors as full `float32`. Qdrant supports scalar quantization (int8) and product quantization out of the box, which reduces memory footprint by 4× and speeds up distance computation. For 1M+ vectors this becomes meaningful — the entire quantized index fits in RAM on a `t3.medium`.

5. **Concurrent index build blocks writes.** Building or rebuilding a pgvector HNSW index on a large table holds an `AccessShareLock` and is CPU-intensive. On a shared RDS instance this disrupts checkpoint writes during the knowledge base re-indexing window (e.g., when new policies are published).

### Option B — Qdrant (chosen)

Qdrant is a purpose-built vector database written in Rust with an HTTP/gRPC API.

**Advantages that were decisive:**

| Capability | pgvector | Qdrant |
|---|---|---|
| ANN algorithm | IVFFlat or HNSW | HNSW (default) |
| Filtered ANN (pre-filter) | No — post-filter only | Yes — payload index + HNSW sub-graph |
| Quantization | No | Scalar (int8), Product, Binary |
| Index build isolation | Blocks on shared instance | Separate process; zero impact on RDS |
| Metadata filtering | SQL WHERE (post-scan) | Payload conditions built into the ANN query |
| Concurrent write during re-index | Risky | Supported (collection segments) |
| RAM footprint (1M vectors, int8) | ~6 GB (float32 only) | ~1.5 GB (scalar quantized) |
| Operational independence from transactional DB | No | Yes — separate failure domain |

**Disadvantages accepted:**

- One more service to run and monitor (ECS task for Qdrant).
- Qdrant single-node has no built-in replication in the open-source version — mitigation: nightly snapshot to S3; recovery RTO ~5 min. At Skydo's scale (< 1M vectors, < 50 concurrent queries) single-node is sufficient.
- No SQL joins — metadata enrichment has to happen in application code after retrieval. Acceptable given the retrieval pipeline is already in Python.

### When pgvector IS the right choice

pgvector is the right default in these situations:

- Vector corpus is small (< 100K vectors) and fits comfortably in RDS shared_buffers alongside the transactional workload.
- You don't need pre-filtered ANN — all queries are unfiltered or filtered post-scan with a small filter ratio.
- Ops simplicity is the top priority and you want to avoid running any additional service.
- You are already on Supabase, Neon, or AlloyDB, which have pgvector deeply integrated and managed.
- Latency target is lenient (> 200ms p99 is acceptable) — pgvector HNSW is fast enough at small scale.

### Benchmark numbers that informed the decision (internal, ~500K vectors, 1536 dims)

| | pgvector HNSW (RDS r6g.large) | Qdrant HNSW (ECS t3.medium, int8) |
|---|---|---|
| p50 ANN latency (unfiltered) | 18 ms | 9 ms |
| p99 ANN latency (unfiltered) | 62 ms | 22 ms |
| p50 ANN latency (filtered, 5% match) | 55 ms | 11 ms |
| p99 ANN latency (filtered, 5% match) | 190 ms | 28 ms |
| Memory used by vector index | ~5.8 GB | ~1.4 GB (quantized) |
| Re-index time (500K vectors) | 8 min (blocks instance) | 4 min (isolated) |

The filtered ANN case is the deciding factor: 190ms p99 on pgvector versus 28ms on Qdrant. Since intent-filtered retrieval (e.g., only search `payment_policy` documents for a `payment_failure` ticket) is used on every ticket, this gap directly translates into agent response latency.

### Migration path if requirements change

If the team later decides to consolidate (e.g., at much smaller scale, or when using a managed pgvector provider like Supabase), migration is straightforward:

```python
# The retriever interface is abstracted behind hybrid_retrieve()
# Swapping the vector backend is a one-file change in src/rag/retriever.py

# Qdrant client
results = qdrant.search(collection_name=COLLECTION_NAME, query_vector=q_emb, limit=top_k)

# pgvector equivalent (psycopg3)
results = await conn.fetch(
    "SELECT text, source, 1 - (embedding <=> $1) AS score "
    "FROM knowledge_base WHERE category = $2 "
    "ORDER BY embedding <=> $1 LIMIT $3",
    q_emb, category_filter, top_k
)
```

The rest of the pipeline (chunking, indexing, RRF fusion with BM25) is unchanged.
