# TaskForge Decision Log

## Scope

This implementation is a working in-memory backend for the assignment. It favors clear engine behavior and testability over infrastructure breadth. Persistence, authentication, distributed execution, durable queues, and multi-process recovery are intentionally left out, but the seams where they would attach are explicit.

Time spent was not measured by this autonomous agent, so there is no human time-spent calibration to report.

## Kotlin and plain JDK APIs

The assignment allowed Kotlin, Go, or Rust. I chose Kotlin because the existing repository is already JVM/Maven-based and Java 21 is available. The backend uses JDK HTTP server, `HttpClient`, and executor services rather than Spring/Ktor. This keeps the review focused on orchestration behavior rather than framework configuration.

Trade-off: the JSON parser and HTTP router are intentionally small and not a replacement for production libraries. In production I would use a maintained JSON library and web framework.

## In-memory storage

Workflow definitions and executions are stored in memory.

Why:

- enough for assignment behavior and tests
- avoids mixing persistence concerns with the orchestration engine
- exposes a `WorkflowStore` interface so durable storage can replace `InMemoryWorkflowStore`

Consequence: executions are lost on process restart and cannot be coordinated across multiple backend instances.

## Handler registry as the extension seam

The most important design property is that adding a task type must not require engine edits. The engine accepts a `TaskHandlerRegistry`, validates workflow task types against it, and runs handlers through the `TaskHandler` interface.

I intentionally avoided enums or `when(type)` dispatch in the engine. Built-ins are assembled by `BuiltInTaskHandlers.registry()`, and tests register fake handlers without changing engine code.

## Task outputs are namespaced by task ID

Shared state is modeled as `taskId -> output map`. I did not merge every output into one global namespace because concurrent sibling tasks could produce the same key. Namespacing makes data flow deterministic and naturally matches the DAG.

Reference syntax:

```text
${tasks.<taskId>.output.<key>}
```

Missing config references fail the task permanently. Missing condition references evaluate false. The distinction is deliberate: config references usually drive side effects and should not be guessed; conditions are predicates and a missing predicate input should safely skip.

## Condition semantics

Conditions support booleans and a small expression language:

- truthy reference
- `exists(...)`
- equality and inequality against literals

This is enough to demonstrate conditional execution without embedding a scripting language in workflow definitions. A richer production version would either use a policy expression language or compile conditions into safe typed predicates.

## Downstream behavior after skips and failures

If a task is skipped because its own condition is false, downstream tasks may still run once all dependencies are terminal. The skipped task contributes no output.

If a task fails, times out, is cancelled, or is skipped due to upstream failure, downstream tasks become `SKIPPED_UPSTREAM_FAILED`.

This makes state observable:

- condition false is not an error
- handler failure is an error
- inherited skip is clearly different from local condition skip

## Retry model

The retry contract is handler-driven:

- `HandlerResult.Failure(retryable = true)` retries if attempts remain
- `TaskFailureException(retryable = true)` retries if attempts remain
- all other failures are permanent

HTTP 5xx is retryable by default. Script exit codes are permanent unless the workflow lists them in `retryableExitCodes`.

I chose handler-driven retry classification because task types understand their own failure domains better than the generic engine.

## Timeout enforcement

The engine invokes each handler through a `Future` and applies `Future.get(timeout)`. On timeout, the handler future is cancelled with interruption and the task is marked `TIMED_OUT`.

This is more reliable than only checking elapsed time before or after handler execution because the engine is the component enforcing the deadline. Handlers still need to use interruptible APIs or observe the cancellation token for quick cleanup. The built-in script handler destroys its process when interrupted or cancelled.

## Cancellation model

Cancellation has two phases:

1. set a shared cancellation token and cancel pending approval waits
2. after a short grace interval, interrupt running task futures

The grace interval is configurable through `TaskForgeEngine(cancellationGraceMillis = ...)`. A task that finishes after cancellation is marked `CANCELLED` even if its handler reports a normal failure while reacting to the token.

## Approvals as a task type

Human approvals are implemented as an `approval` handler instead of special-case engine behavior. The handler moves its own state to `WAITING_APPROVAL` through `TaskContext.updateStatus` and waits on `ApprovalBroker`.

This preserves the handler extension model while still making approval state visible. The approval API sends actor, decision, optional comment, and optional token.

## Built-in task type choices

Required:

- `http`
- `script`

Added:

- `file`: common in deployment and migration workflows for writing generated configs, reading manifests, or cleaning artifacts. It is constrained to a base directory.
- `approval`: first-class release gates and incident runbook checkpoints are central to DevOps orchestration.

I avoided sleep/no-op tasks because they do not add much production value and can be represented in tests with custom handlers.

## API design

The HTTP API uses resource-oriented routes:

- `POST /workflows`
- `GET /workflows/{id}`
- `POST /workflows/{id}/executions`
- `GET /executions/{id}`
- `POST /executions/{id}/cancel`
- `POST /executions/{id}/approvals/{taskId}`

Validation errors use `422` because the JSON is syntactically valid but semantically invalid. Approval resolution returns `409` when no pending approval matches or the token is wrong.

## What would change for production

- Durable workflow/execution storage and recovery after process restart.
- Idempotency keys for workflow registration, execution start, cancellation, and approval resolution.
- Authentication and authorization for workflow APIs and approval identities.
- Structured logging, metrics, tracing, and audit records.
- Backpressure and configurable per-workflow concurrency limits.
- A maintained JSON/web stack.
- A sandboxed script runner. The current script handler is suitable only for trusted workflows.
- A richer typed workflow schema with versioning and migration.

