# Agentic Support Copilot — Build Guide

> **Context:** This document covers the architecture, component-by-component implementation, and operational setup for the Agentic Support Copilot built at Skydo. The system uses a **hybrid RAG + read-only tool-calling** architecture with human-in-the-loop (HITL) approvals, PII masking, policy checks, and audit logs. Outcomes: ~65% auto-triage, ~35–40% assisted/auto resolution, first-response time reduced from ~90 min → ~10–12 min, SLA breaches down ~50%.

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

### Design Principles

| Principle | Decision |
|---|---|
| **Read-only tools** | Agent can never write/mutate; all mutations require human confirmation |
| **Hybrid RAG** | BM25 for exact policy/keyword matches + vector for semantic similarity |
| **Confidence-gated** | Only auto-send when classifier confidence ≥ threshold |
| **PII-first** | Mask PII before any LLM call; unmask only in final CRM write |
| **Audit everything** | Every LLM call, tool call, decision, and human action is logged with trace ID |

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
| **Audit Log** | DynamoDB + CloudWatch | Immutable append-only log; CloudWatch for alerting |
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

---

## 13. Infrastructure & Deployment

### AWS Architecture

```
API Gateway
    │
    ▼
Lambda (Ticket Ingestor)
    │
    ▼
SQS Queue (ticket-processing)
    │
    ▼
ECS Fargate (support-copilot-worker)
    ├── LangGraph orchestrator
    ├── Qdrant client
    ├── Elasticsearch client
    └── DynamoDB client (audit log + checkpoints)

Qdrant  ← ECS Fargate or EC2 (self-hosted, inside VPC)
Elasticsearch ← AWS OpenSearch Service
DynamoDB ← audit log + LangGraph checkpoints
S3 ← policy document store
Secrets Manager ← OpenAI API key, internal API key
CloudWatch ← metrics, alerts (SLA breach rate, auto-resolve rate)
Retool ← HITL dashboard (connects to FastAPI /hitl/* endpoints)
```

### Docker for the worker

```dockerfile
FROM python:3.11-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ ./src/
ENV PYTHONPATH=/app

CMD ["python", "-m", "src.worker"]
```

### Key environment variables

```bash
OPENAI_API_KEY=...
INTERNAL_API_KEY=...
QDRANT_HOST=qdrant.internal
QDRANT_PORT=6333
ES_HOST=https://opensearch.internal:443
DYNAMODB_TABLE=support-copilot-audit
LANGFUSE_SECRET_KEY=...
LANGFUSE_PUBLIC_KEY=...
AUTO_RESOLVE_CONFIDENCE=0.85
MAX_TOOL_CALLS=6
```

---

## 14. Evaluation & Metrics

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

---

## 15. Rollout Strategy

### Phase 1 — Shadow Mode (Week 1–2)

- Agent runs on all tickets but outputs are **not sent** — only logged.
- Compare agent drafts against what human agents actually sent.
- Measure: draft quality, policy check pass rate, false positive rate on HITL escalation.

### Phase 2 — Assisted Mode (Week 3–4)

- HITL mode for **all** tickets.
- Agent drafts are shown to human agents as suggestions.
- Humans approve/edit/reject — build training signal.
- Target: ≥ 70% approval rate before moving to Phase 3.

### Phase 3 — Auto-Resolve (Week 5+)

- Enable auto-send for `confidence ≥ 0.85` AND `policy_check_passed`.
- Start with low-risk intents only: `fx_rate_query`, `general_faq`.
- Expand intent coverage as trust is established.
- Keep SLA breach rate and CSAT as circuit-breaker signals.

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
