# TaskForge Test Design

The automated tests live in `src/test/kotlin/lld/taskforge`.

## Goals

The tests focus on the highest-risk behavior in the assignment:

- workflow validation
- DAG execution order and output references
- retry behavior
- downstream skip semantics
- timeout handling
- cancellation handling
- approval resolution
- handler extensibility
- HTTP API smoke coverage

## Test layers

### Engine tests

`TaskForgeEngineTest` exercises the engine directly with a registry containing test-only handlers:

- `EmitHandler`
- `CaptureHandler`
- `FlakyHandler`
- `BlockingHandler`
- built-in `ApprovalTaskHandler`

Using test-only handlers proves reviewers can add a new task type without modifying engine source files.

Covered cases:

1. Invalid workflow definitions report unknown task types, dangling dependencies, and cycles.
2. A downstream task consumes `${tasks.build.output.image}` from an upstream task.
3. Retryable failures are retried and downstream work runs only after success.
4. Permanent failures mark downstream tasks `SKIPPED_UPSTREAM_FAILED`.
5. Per-task timeout marks the task `TIMED_OUT` and skips downstream work.
6. Cancellation marks running and pending tasks `CANCELLED`.
7. Approval waits in `WAITING_APPROVAL`, accepts an actor/decision/token signal, and exposes approval output downstream.

### HTTP API tests

`TaskForgeSpringApiTest` starts a Spring Boot application context and exercises the Spring MVC layer through `MockMvc`. It verifies:

1. `POST /workflows` stores a JSON workflow.
2. `POST /workflows/{id}/executions` starts an execution.
3. `GET /executions/{id}` returns a terminal execution snapshot with task output.

This intentionally stays as a smoke test because engine behavior is covered more deeply at the engine layer.

## Manual verification

Run:

```bash
mvn test
```

To run the backend:

```bash
mvn spring-boot:run
```

Example workflow registration:

```bash
curl -X POST http://localhost:8080/workflows \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "example",
    "name": "Example",
    "tasks": [
      {
        "id": "produce",
        "type": "script",
        "config": {"command": "printf api:42"},
        "timeoutMillis": 5000
      },
      {
        "id": "write",
        "type": "file",
        "dependsOn": ["produce"],
        "config": {
          "operation": "write",
          "path": "release.txt",
          "content": "${tasks.produce.output.stdout}"
        }
      }
    ]
  }'
```

Then start it:

```bash
curl -X POST http://localhost:8080/workflows/example/executions
```

## Gaps and residual risk

- The built-in HTTP handler is not integration-tested against a real HTTP server in this suite.
- The built-in script handler is not heavily stress-tested for large stdout/stderr streams.
- Spring/Jackson request binding is covered through API smoke testing, not an exhaustive API contract suite.
- Persistence and multi-process recovery are outside the current in-memory scope.

