# TaskForge Architecture

TaskForge is implemented in Kotlin under `src/main/kotlin/lld/taskforge`. It is a small in-memory backend built on the JDK rather than a framework so the engine behavior and extension seams remain easy to inspect.

## Components

| Component | Responsibility |
| --- | --- |
| `TaskForgeHttpServer` | HTTP/JSON API using `com.sun.net.httpserver.HttpServer`. |
| `TaskForgeEngine` | Workflow registration, execution orchestration, cancellation, approvals, retries, timeouts, and state snapshots. |
| `WorkflowValidator` | Rejects invalid workflow definitions before storage. |
| `TaskHandlerRegistry` | Runtime registry for task handlers. This is the extensibility seam. |
| `TaskHandler` | Interface implemented by each task type. |
| `ReferenceResolver` | Resolves `${tasks.<taskId>.output.<key>}` references and task conditions. |
| `InMemoryWorkflowStore` | Stores workflow definitions for this assignment. |
| `InMemoryApprovalBroker` | Tracks pending approval tasks and API approval signals. |

## Workflow definition format

The API accepts JSON. Internally that maps to:

```json
{
  "id": "deploy-api",
  "name": "Deploy API",
  "tasks": [
    {
      "id": "build",
      "type": "script",
      "dependsOn": [],
      "condition": true,
      "config": {
        "command": "echo api:42"
      },
      "retryPolicy": {
        "maxAttempts": 2,
        "backoffMillis": 100
      },
      "timeoutMillis": 5000
    }
  ]
}
```

Validation rejects:

- blank workflow IDs
- workflows without tasks
- blank or duplicate task IDs
- unknown task types
- dangling dependency references
- dependency cycles
- invalid retry and timeout values

Validation errors include JSON-like paths such as `$.tasks[1].dependsOn[0]`.

## Execution model

When `startExecution` is called, the engine creates an execution record with one mutable state record per task. The orchestrator loop:

1. Finds pending tasks whose dependencies are terminal and successful.
2. Evaluates the task condition.
3. Submits runnable tasks to a worker pool.
4. Captures completed task outputs under the task's ID.
5. Skips downstream tasks if any dependency failed, timed out, was cancelled, or was already skipped because of upstream failure.
6. Marks the execution `SUCCEEDED`, `FAILED`, or `CANCELLED` when no further progress is possible.

Tasks with no unmet dependencies are submitted concurrently. The shared execution state is only published after a task reaches `SUCCEEDED`, so downstream tasks never observe partial output from running upstream tasks.

## Task and execution states

Task states:

- `PENDING`
- `RUNNING`
- `WAITING_APPROVAL`
- `SUCCEEDED`
- `FAILED`
- `TIMED_OUT`
- `SKIPPED_CONDITION`
- `SKIPPED_UPSTREAM_FAILED`
- `CANCELLED`

Execution states:

- `RUNNING`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`

`SKIPPED_CONDITION` means the task's own condition evaluated false. `SKIPPED_UPSTREAM_FAILED` means the task did not get a chance to evaluate its own condition because a dependency failed, timed out, or was cancelled.

## Shared state and references

Each successful task contributes a map of output values. Outputs are namespaced by task ID:

```json
{
  "build": {
    "image": "api:42"
  }
}
```

Task configs can reference prior output values:

```json
{
  "body": "{\"image\":\"${tasks.build.output.image}\"}"
}
```

If a config string is exactly one reference, the referenced value is passed through with its original type. If a reference is embedded in a larger string, it is converted to text. Missing config references fail the task permanently because silently continuing would make automation unsafe.

Conditions can be boolean values or strings:

- `true` / `false`
- `${tasks.build.output.ready}`
- `exists(tasks.build.output.image)`
- `${tasks.build.output.status} == "green"`
- `${tasks.build.output.status} != "red"`

Missing condition references evaluate as false.

## Retries and timeouts

Each task can define:

```json
{
  "retryPolicy": {
    "maxAttempts": 3,
    "backoffMillis": 250
  },
  "timeoutMillis": 10000
}
```

A retry happens when a handler returns `HandlerResult.Failure(retryable = true)` or throws `TaskFailureException(retryable = true)`. Other failures are permanent. HTTP 5xx responses are retryable by default; non-2xx/declared non-success statuses are failures.

Timeouts are enforced by running the handler call in a `Future` and using `Future.get(timeout)`. On timeout the future is cancelled with interruption. Handlers that block on interruptible APIs or check the cancellation token will stop promptly. A timed-out task is marked `TIMED_OUT`; downstream tasks are marked `SKIPPED_UPSTREAM_FAILED`.

## Cancellation

`cancelExecution` sets an execution cancellation token, cancels pending approval waits, waits a short grace interval, then interrupts running task futures. During the grace interval, cooperative handlers can notice `context.cancellationToken` and clean up. After the grace interval, the engine uses `Future.cancel(true)` to force interruption.

Tasks not started yet are marked `CANCELLED`. Running tasks that complete after cancellation are also reported as `CANCELLED`, even if a handler returns a normal failure while reacting to the cancellation token.

## Approvals

Approvals are represented as a first-class task type: `approval`.

Approval task config:

```json
{
  "approvalTimeoutMillis": 600000,
  "token": "release-token"
}
```

When the approval handler starts, it moves the task to `WAITING_APPROVAL` and waits for an API signal containing:

```json
{
  "actor": "sre@example.com",
  "decision": "approved",
  "token": "release-token",
  "comment": "change window open"
}
```

`approved` succeeds the task. Any other decision fails the task. A missing approval before the window expires fails the task. The token is intentionally simple for this assignment; it makes identity and intent explicit while keeping auth concerns out of the engine.

## Built-in task types

### `http`

Config:

- `url` required
- `method` optional, default `GET`
- `headers` optional object
- `body` optional
- `successStatusCodes` optional list; default is any 2xx status

Output:

- `statusCode`
- `body`
- `headers`

Non-success 5xx responses are retryable. Other non-success responses are permanent failures.

### `script`

Config:

- `command` required, either a shell string or an argv list
- `workingDirectory` optional
- `environment` optional object
- `retryableExitCodes` optional list

Output:

- `exitCode`
- `stdout`
- `stderr`

Exit code 0 is success. Stderr alone is not failure. A nonzero exit code is failure; it is retryable only if listed in `retryableExitCodes`.

### `file`

Config:

- `operation`: `read`, `write`, `append`, or `delete`
- `path`
- `content` for write/append

Paths are constrained to a base directory to avoid accidental host filesystem access. Output includes the path and operation-specific fields such as `content`, `size`, and `deleted`.

### `approval`

Described above. This is useful in DevOps workflows for release gates, incident commander approval, or production migration checkpoints.

## Extensibility

New task types do not require engine changes. Implement `TaskHandler` and register it:

```kotlin
class SlackTaskHandler : TaskHandler {
    override val type = "slack"

    override fun execute(context: TaskContext): HandlerResult {
        val channel = context.config["channel"].toString()
        val text = context.config["text"].toString()
        // Send message here.
        return HandlerResult.Success(mapOf("channel" to channel, "text" to text))
    }
}

val registry = BuiltInTaskHandlers.registry().register(SlackTaskHandler())
val engine = TaskForgeEngine(registry)
```

The engine only depends on the `TaskHandler` interface and `HandlerResult`. It does not branch on handler type except through the registry lookup.

## HTTP API

All bodies and responses are JSON.

### Register a workflow

`POST /workflows`

Request body: workflow definition.

Responses:

- `201` with stored workflow
- `422` with validation details
- `400` for malformed JSON or invalid request shape

### Retrieve a workflow

`GET /workflows/{workflowId}`

Responses:

- `200` with workflow
- `404` if not found

### Start an execution

`POST /workflows/{workflowId}/executions`

Responses:

- `202` with execution snapshot
- `404` if workflow does not exist

### Observe an execution

`GET /executions/{executionId}`

Responses:

- `200` with execution snapshot, per-task state, and task outputs
- `404` if execution does not exist

### Cancel an execution

`POST /executions/{executionId}/cancel`

Responses:

- `202` with current execution snapshot
- `404` if execution does not exist

### Resolve an approval

`POST /executions/{executionId}/approvals/{taskId}`

Request body:

```json
{
  "actor": "sre@example.com",
  "decision": "approved",
  "token": "release-token",
  "comment": "optional"
}
```

Responses:

- `202` if accepted
- `409` if no approval is pending or token does not match

