# The Agentic Loop — Staff Engineer Deep Dive & How to Build/Operate One

A companion to `docs/AI-AGENTIC-INTERVIEW-PREP.md`. That doc covers the broad landscape (memory, RAG, multi-agent, frameworks). This one goes one level deeper on a single mechanism — **the agentic loop itself**: what actually happens on every iteration, where it breaks, and how a staff engineer designs and operates one in production. Same format as `TEMPORAL_ARCHITECTURE_STAFF_ENG.md`: internals first, then textbook-vs-production signal questions.

---

## Part 0: The One-Sentence Mental Model

> **An agentic loop is a `while` loop wrapped around an LLM call, where each iteration re-sends the entire growing conversation to the model, the model responds with either final text or a request to call one or more tools, the harness executes those tools and appends the results as new messages, and the loop repeats until the model stops asking for tools (or a budget/guardrail kicks in).**

There is no hidden magic beyond this. Everything that makes an agentic loop feel intelligent — planning, self-correction, tool selection — is the model doing next-token prediction over a context that keeps growing with each iteration's observations. Everything that makes it *reliable in production* is the harness code around that loop, which is almost entirely a software-engineering problem, not an ML problem.

---

## Part 1: How the Agentic Loop Actually Works

### 1.1 The Core Loop, Stripped to Its Skeleton

```
messages = [system_prompt, user_goal]

loop:
    response = llm.call(messages, tools=tool_schemas)
    messages.append(response)                     # the model's turn

    if response.has_no_tool_calls:
        return response.text                       # done

    for each tool_call in response.tool_calls:
        result = execute(tool_call.name, tool_call.args)
        messages.append(tool_result(tool_call.id, result))  # observation

    if budget_exhausted:
        return "stopped: budget exceeded"
```

Every "agent framework" (LangGraph, the OpenAI Agents SDK, a hand-rolled loop, a coding agent like this one) is a variation on this skeleton with more structure bolted on: typed state, persistence, streaming, sub-agent dispatch, guardrail hooks. None of them change the fundamental shape.

**Staff-level answer:** "It's a while loop, not a state machine with hidden transitions. Each turn, you send the model the full conversation so far plus tool schemas; it either produces a final answer or asks to call tools; you execute those tools and append the results as new messages; repeat. The 'reasoning' is the model conditioning on an ever-growing transcript of its own prior thoughts and tool observations — there's no separate planning engine underneath."

---

### 1.2 Context Is Re-Sent, Not Incrementally Updated

- LLM APIs are stateless per call (ignoring server-side "conversation" APIs, which are a thin wrapper over the same idea). **Every single loop iteration sends the entire message history again** — system prompt, every prior assistant turn, every prior tool call and its result.
- This has two direct consequences:
  1. **Cost and latency grow with the number of turns**, roughly quadratically in naive implementations (each of N turns re-processes O(N) prior tokens), unless mitigated by prompt caching (1.9) or context compaction (1.4).
  2. **Anything you want the model to "remember" mid-loop must be in that message list.** There is no implicit state — if a fact isn't in the messages (or re-injected via a tool result), the model has no access to it on the next turn.

**Staff-level gotcha:** teams new to this often assume the model "remembers" earlier reasoning the way a person does. It doesn't — it re-reads the transcript every time. If you silently truncate or summarize history to save cost, you are literally deleting the agent's memory of that period, and it will re-ask questions or re-do work it already did.

---

### 1.3 Tool Call Parsing and Execution

- Modern LLM APIs support **structured function calling**: the model doesn't emit natural-language "I will call search(...)" — it emits a schema-constrained JSON object (tool name + arguments), and this is usually enforced via constrained decoding on the provider side, not just prompted behavior. This is why tool definitions with tight JSON schemas (types, enums, required fields) meaningfully reduce malformed calls compared to loose natural-language tool descriptions.
- A single turn can request **multiple tool calls in parallel** (e.g., "read file A" and "read file B" in the same turn). The harness decides whether to execute them concurrently or sequentially — concurrency is a real speed win but reintroduces classic race-condition concerns the moment any tool has side effects (two parallel calls both trying to edit the same file, or both charging the same order).
- **Every tool result becomes an observation appended as a message, and errors are results too, not exceptions that crash the loop.** A tool that raises should be caught by the harness and turned into a `{"error": "..."}` observation — the model can then reason about the failure and retry differently. A tool failure that instead crashes the whole process throws away everything the agent has learned so far and is almost always the wrong default.

---

### 1.4 Context Window Pressure and Compaction

- The message list grows unboundedly as the loop runs; the context window does not. Long-running agentic loops (many tool calls, large tool outputs like file contents or search results) eventually threaten to overflow the model's context limit.
- Mitigations, roughly in order of how much information they preserve:
  1. **Truncate large individual tool outputs** before they enter the message list (e.g., cap file reads, paginate search results) — cheapest, no information loss on the parts you keep, but you decide up front what's droppable.
  2. **Sliding window** — keep only the last N turns verbatim. Simple, but silently forgets anything before the window, including decisions the model made for a reason.
  3. **Summarization / compaction** — periodically replace older turns with an LLM-generated summary of "what happened and what's still relevant." Preserves the gist at the cost of an extra LLM call and inevitable lossy compression — critical details can get summarized away.
  4. **Structured external memory** — write key facts to a scratchpad/file/DB as they're discovered, and rely on that artifact instead of the raw transcript for anything long-lived. This is why many coding agents keep a running plan or TODO list as an explicit tool-managed artifact rather than trusting it to survive in conversational context indefinitely.

**Staff-level answer:** "You don't get to keep infinite context, so you have to decide, deliberately, what's allowed to be forgotten. The safest architecture treats the conversation history as working memory that can be compacted, and pushes anything that must survive — a plan, accumulated findings, decisions made — into an explicit artifact (a file, a task list, a memory store) that isn't at the mercy of your truncation strategy."

---

### 1.5 Termination Conditions

An agentic loop needs **more than one** way to stop, because relying on just one is how you get either an incomplete task or a runaway one:

- **Natural completion** — the model's response has no tool calls; it believes it's done. This is the "happy path" and also the least trustworthy signal in isolation — models can prematurely decide they're done, or never converge and keep calling tools forever.
- **Explicit finish/submit tool** — instead of relying on "no tool calls," some harnesses require the model to call a `submit_answer(...)` or `mark_complete(...)` tool with a structured final result. This makes "done" an unambiguous, validatable event instead of an absence of behavior.
- **Step/iteration budget** — a hard cap (`max_iterations`) that forces termination regardless of model behavior. This is the single most important guardrail against cost blowouts and infinite loops, and it should never be optional in production.
- **Token/cost budget** — cumulative cost or token count across the whole run, independent of step count (a loop with 5 steps that each read a huge file can burn more tokens than a loop with 50 cheap steps).
- **Wall-clock timeout** — protects against a slow external tool (a hanging API call) stalling the whole run indefinitely, independent of iteration count.
- **Repeated-action detection** — if the model calls the same tool with the same arguments N times in a row, that's a strong signal of a stuck loop (it got an error, didn't change approach, and is retrying blindly) and should trigger an explicit "you're repeating yourself" nudge or a hard stop, not silent continuation.
- **Human interrupt** — for anything irreversible, the loop should be able to pause and wait for approval rather than run to one of the above limits.

**Staff-level gotcha:** teams that ship with only the "no tool calls → done" condition and no budget cap are one bad prompt change away from a cost incident — a model that gets into a retry loop on a flaky tool with no repeated-action detection will happily burn budget until someone notices the bill.

---

### 1.6 Sub-Agents: Loops Spawning Loops

- A common production pattern is an **orchestrator loop that dispatches sub-agent loops as if they were tools** (e.g., a `run_subagent(task_description)` "tool" that internally runs a full nested agentic loop and returns only its final summary as the observation).
- This is the practical answer to context window pressure at the *task* level, not just the *turn* level: a sub-agent can burn tens of thousands of tokens exploring a codebase or reading documents, and the orchestrator's context only grows by the sub-agent's final summary, not its entire internal transcript.
- It also gives you **fault isolation and independent budgets** — a sub-agent that spins can be killed without corrupting the orchestrator's state, and each sub-agent can have its own `max_iterations`/token budget scoped to its sub-task.
- The cost: **coordination and result-aggregation complexity**. The orchestrator has to decide what to hand off, wait for results (sequentially or in parallel fan-out), and merge sub-agent outputs — and a sub-agent's summary is a lossy compression of everything it actually did, which is sometimes exactly the information the orchestrator needed.

---

### 1.7 Streaming Inside a Loop

- Users expect to see tokens as they're generated, not wait for a full turn to complete — but a turn isn't "done" from the harness's perspective until you know whether it ends in a tool call or final text, and structured tool-call arguments often arrive as partial JSON fragments across multiple stream chunks.
- Practical handling: stream text content to the UI token-by-token as it arrives (that part is safe to show immediately), but **buffer tool-call argument fragments until they form valid, complete JSON** before attempting to parse and execute — executing on a partial/malformed argument object is a reliability bug waiting to happen, not a streaming nicety.
- For multi-tool-call turns, the harness typically surfaces "tool started" / "tool result" events to the UI as each completes, independent of the text stream, so a user watching a coding agent sees "reading file X... done, editing file Y..." rather than a wall of silence between the initial response and the final answer.

---

### 1.8 Non-Determinism, and Why That's a Different Problem Than Temporal's

Worth contrasting directly with `TEMPORAL_ARCHITECTURE_STAFF_ENG.md`, since both are "durable multi-step execution" systems on the surface:

- **Temporal's determinism requirement is about replay correctness**: given the same recorded event history, the same code must take the same path, so a crash-and-resume produces the exact same outcome. Determinism there is a hard engineering constraint you *must* satisfy.
- **An agentic loop is inherently non-deterministic at the model layer** (sampling temperature, model updates, even nominally "same" prompts can produce different tool choices) — and that's *expected*, not a bug to eliminate. You don't get replay-equivalence for an agentic loop the way you do for a Temporal workflow.
- This means the reliability strategy is different: instead of "guarantee the exact same execution on retry" (Temporal's approach), agentic loops rely on **idempotent tools + bounded budgets + evals over many runs** to get statistical reliability, not deterministic reliability. If you need a step in an agent's process to be *exactly* reproducible and crash-recoverable (e.g., "charge the customer exactly once, resume precisely after a crash"), that step belongs in a durable-execution system like Temporal invoked *as a tool*, not reimplemented as agent reasoning — a well-designed system uses the agentic loop for judgment/reasoning steps and a durable-execution backend for the parts that must not be re-derived by an LLM each time.

---

### 1.9 Prompt Caching: The Loop's Biggest Lever on Cost and Latency

- Because the harness re-sends the growing message list on every turn, most of each request is **identical to the previous request's prefix** (system prompt + all prior turns) — only the newest turn's tool result is new.
- Providers expose **prompt/context caching** that lets them skip re-processing that unchanged prefix, cutting both cost and time-to-first-token substantially on later turns of a long loop.
- The practical implication for harness design: **keep the message history strictly append-only and stable**. Editing, reordering, or summarizing earlier messages *invalidates the cached prefix from that point forward*, forcing full reprocessing on every subsequent turn. This directly conflicts with the compaction strategies in 1.4 — summarizing history saves context-window space but destroys cache hits, so a staff-level design treats "when to compact" as a cost/latency trade-off decision, not a free win, and often defers compaction until it's actually needed rather than doing it eagerly on every turn.

---

## Part 2: Common Follow-Ups

**"Isn't this just ReAct?"**
ReAct (Yao et al., 2022) is the *prompting pattern* — the idea of interleaving explicit Thought/Action/Observation text so the model reasons before acting. The agentic loop is the *execution harness* that makes any tool-calling pattern (ReAct-style explicit reasoning, or plain structured function calling with no explicit "Thought:" text) actually run, retry, and terminate. Most production agents today use structured function calling without literal "Thought:" text but are still conceptually doing ReAct's reason-then-act cycle — the harness loop is the same either way.

**"How is an agentic loop different from a workflow orchestrator like Temporal or a DAG like Airflow?"**
An orchestrator executes a graph whose shape is largely known/bounded ahead of time (even if branchy), with steps that are typically deterministic code. An agentic loop's next step is **decided by the model at runtime based on the previous step's result**, with no fixed graph — that's the entire point (see the decision framework in Part 4). They compose well: an agent can call a Temporal workflow as a tool for the part of the process that needs durable, exactly-defined execution.

**"Why does the agent keep repeating the same failed tool call?"**
Usually one of: the tool's error message is uninformative (the model has nothing to act on), the harness isn't detecting repeated-identical-calls as a stop condition, or the model's context window has aged out the earlier attempt so it doesn't "remember" trying it before — all three are harness bugs, not fundamental model limitations.

**"Can you make the loop fully deterministic for testing?"**
You can pin `temperature=0` and a fixed model snapshot to reduce variance, and you can mock/stub tool responses for reproducible unit tests of the harness logic — but you cannot get Temporal-style guaranteed byte-identical replay from the model itself; two calls with identical inputs can still occasionally diverge. Treat agent evals as statistical (pass rate over N runs), not pass/fail on a single run.

---

## Part 3: How to Build/Operate One Like a Staff Engineer — Signal Questions

For each: what a **textbook answer** sounds like vs. what a **real-production answer** sounds like.

### Q1. "Tell me about a production incident caused by an agentic loop."

- **Textbook**: "It got stuck in a loop so we added a max iteration count."
- **Real-production**: Names a specific failure — e.g. a tool returning a *slightly different but still "successful"* error-shaped response that the model kept treating as retriable, burning the full iteration budget every run and multiplying cost by 10x before someone noticed in a billing dashboard; or a summarization step that silently dropped a constraint the user gave three turns earlier, causing the agent to violate it confidently; or two parallel tool calls in the same turn both writing to the same file and corrupting it because the harness executed them concurrently without a lock.

### Q2. "How do you decide your iteration budget and what happens when it's hit?"

- **Textbook**: "We cap it at 25 steps."
- **Real-production**: Distinguishes step budget from token/cost budget (a few steps with huge tool outputs can be more expensive than many cheap steps), describes what actually happens on hit — not just "it stops," but whether it returns partial progress, surfaces "ran out of budget" explicitly to the user/caller rather than silently truncating, and whether the budget is tunable per task type (a "read this one file" agent needs far fewer steps than "refactor this module across 20 files").

### Q3. "How do you keep tool-call arguments from being malformed or hallucinated?"

- **Textbook**: "Tools have JSON schemas."
- **Real-production**: Talks about validating arguments with a schema library (Pydantic, Zod) *before* execution and returning validation errors as observations rather than crashing, tightening schemas with enums/required fields specifically because they've seen the model invent a plausible-but-wrong field name under a loose schema, and — for the sharpest edge case — flagging tools whose arguments look syntactically valid but are semantically wrong (a real file path that exists but isn't the one relevant to the task) as something schema validation alone can't catch.

### Q4. "You need to compact context on a long-running loop. Walk me through the trade-off."

- **Textbook**: "Summarize old messages when the context gets too big."
- **Real-production**: Explicitly names the cache-invalidation cost from 1.9 (compacting kills the prompt cache prefix, so it's not a free win), describes deferring compaction until actually near the limit rather than proactively, and separates "safe to compact/drop" (verbose tool outputs already acted on) from "must survive" (an explicit plan/TODO artifact, user-stated constraints) so compaction targets the former and never touches the latter.

### Q5. "How do you evaluate whether changes to your agent's prompt or tools made it better or worse?"

- **Textbook**: "We run it and check if the answer looks right."
- **Real-production**: Runs a fixed eval set across N repetitions per task (because of non-determinism, per 1.8) and tracks a distribution — pass rate, median steps-to-completion, cost per successful run — not a single pass/fail; has specifically caught a regression where a prompt change improved output quality but tripled average steps-to-completion, which wouldn't show up in a single-run "looks right" check at all.

### Q6. "When do you dispatch a sub-agent instead of just adding another tool call to the main loop?"

- **Textbook**: "For complex subtasks."
- **Real-production**: Gives a concrete threshold from experience — e.g. "any exploration that's likely to touch more than N files/results, because otherwise the raw intermediate output pollutes the orchestrator's context and starts crowding out the actual task" — and separately flags the real cost: the sub-agent's summary is lossy, so tasks where the orchestrator needs a *specific detail* from deep in the sub-agent's work (not just a verdict) are a bad fit for the hand-off, because that detail may not survive summarization.

### Q7. "A tool your agent calls has side effects (sends email, charges a card). How do you make that safe?"

- **Textbook**: "Make it idempotent."
- **Real-production**: Same as Temporal's activity-idempotency answer (Part 1.3 of the Temporal doc) but with the agent-specific wrinkle: the model can call the same tool with the same arguments across *separate, unrelated runs* (not just retries within one run), so the idempotency key needs to be scoped to the actual business intent (e.g., "this specific approved order"), not just "this specific tool call," and irreversible actions get a human-approval gate ahead of execution regardless of how confident the model's reasoning looked in the transcript.

### How to Use This Section

Ask one or two, then push for the actual number or the actual error they saw. Someone who's only read about agent loops gives correct-sounding, generic answers ("we add guardrails"); someone who's operated one gives specific thresholds, specific failure signatures, and a specific story about the first time it went wrong.

---

## Part 4: When an Agentic Loop Is the Right Tool

**Reach for an agentic loop when:**
- The number and order of steps genuinely can't be known ahead of time — the right next action depends on what the previous tool call returned (explore a codebase, research a topic, debug an unfamiliar error).
- The task benefits from judgment/synthesis at each step, not just execution of a fixed procedure.
- Some looseness in step count/cost is acceptable in exchange for handling open-ended tasks a fixed pipeline can't anticipate.

**Don't reach for it (or wrap it) when:**
- The steps and their order are actually fixed/known — that's a pipeline or DAG (Airflow), not a reason to pay LLM-inference cost and latency at every step to "decide" something that isn't actually in question.
- A step must be durably, exactly-once, and deterministically recoverable across crashes (charge a card, move money) — hand that specific step to a durable-execution system (Temporal) invoked as a tool from the loop, rather than trusting the loop's own retry behavior for it.
- Latency SLA is tight and sub-second — every loop iteration is at least one LLM round-trip; a deterministic function will always be faster for a task that doesn't actually need judgment.

---

## Part 5: Staff-Level Trade-off Summary

| Topic | One-line | Deeper point |
|---|---|---|
| Core mechanism | While loop re-sending the full growing transcript each turn. | No hidden state — anything not in the messages (or re-derived via a tool) doesn't exist to the model on the next turn. |
| Tool execution | Structured function calling, errors returned as observations. | A tool that raises instead of returning an error observation throws away everything the agent has learned so far. |
| Context pressure | Grows unbounded; window doesn't. | Compaction/summarization is lossy and (see caching) not free — decide deliberately what's allowed to be forgotten. |
| Termination | Needs multiple independent guardrails, not just "no tool calls." | Step budget, cost budget, wall-clock timeout, and repeated-action detection are all required in production, not optional extras. |
| Sub-agents | Nested loops dispatched as tools. | Solves context pollution and enables fault isolation, at the cost of lossy summary-only results passed back up. |
| Determinism | Inherently non-deterministic at the model layer, by design. | Reliability comes from idempotent tools + bounded budgets + statistical evals, not replay-equivalence like Temporal. |
| Prompt caching | Append-only history keeps the cached prefix valid. | Editing/summarizing earlier turns invalidates the cache from that point forward — a real cost trade-off against compaction. |

---

## Part 6: Minimal Shape of a Production-Grade Loop (Python)

Builds on the bare-bones `run_agent()` in `AI-AGENTIC-INTERVIEW-PREP.md` (HOW-01) by making the guardrails from Part 1.5 explicit rather than implicit:

```python
def run_agent(user_goal: str, max_iterations: int = 25, max_cost_usd: float = 2.00) -> str:
    messages = [system_prompt(), {"role": "user", "content": user_goal}]
    total_cost = 0.0
    last_calls_seen = []  # for repeated-action detection

    for step in range(max_iterations):
        response = llm.call(messages, tools=TOOL_SCHEMAS)
        total_cost += response.cost
        messages.append(response.to_message())

        if not response.tool_calls:
            return response.text  # natural completion

        if total_cost > max_cost_usd:
            return "stopped: cost budget exceeded"

        for call in response.tool_calls:
            signature = (call.name, frozenset(call.args.items()))
            if last_calls_seen.count(signature) >= 2:
                messages.append(tool_result(call.id,
                    {"error": "This exact call was just retried twice with no new information. Try a different approach."}))
                continue
            last_calls_seen.append(signature)

            try:
                result = execute_tool(call.name, call.args)   # validated against Pydantic schema inside
            except Exception as e:
                result = {"error": str(e)}                    # observation, not a crash

            messages.append(tool_result(call.id, result))

    return "stopped: max iterations reached"
```

The point isn't the exact code — it's that **every guardrail in Part 1.5 is a few lines of harness code, not a model capability**. A staff engineer reviewing an agentic system should be checking for exactly these lines, the same way they'd check a Temporal deploy for idempotent activities: the reliability lives in the boring parts.

---

## Summary

- **Internals**: a while loop that re-sends the growing transcript every turn, structured tool calling with errors-as-observations, context-window pressure forcing a deliberate forget/compact strategy, multiple independent termination guardrails, optional nested sub-agent loops, and prompt caching that rewards append-only history.
- **Where it breaks**: missing/soft termination guardrails (runaway cost), tool errors that crash instead of becoming observations, context compaction that silently deletes load-bearing constraints, and treating a single run's output as a pass/fail signal instead of evaluating a distribution over many runs.
- **How to operate it like a staff engineer**: treat budgets (steps, cost, wall-clock) as mandatory, keep the message history append-only as long as caching economics justify it, push anything that must survive long-term into an explicit artifact rather than trusting conversational memory, hand off truly durable/exactly-once steps to a system like Temporal instead of reimplementing them as agent reasoning, and eval with repetition because the loop is non-deterministic by design.
- Use Part 1–2 to explain precisely how an agentic loop executes. Use Part 3 to calibrate whether someone's agent experience is textbook-deep or production-deep.
