# Jenkins-like execution system

A small in-process model of a Jenkins-style CI execution system. It supports
registered jobs, queued build runs, labeled agents, ordered stages, named steps,
environment/parameter injection, build logs, cancellation, and failure
short-circuiting.

For the distributed-system view, see [HLD.md](./HLD.md).

## Core concepts

| Concept | Description |
|---------|-------------|
| `PipelineDefinition` | Immutable job definition: name, environment, required agent labels, ordered stages. |
| `Stage` | Ordered group of build steps. A failed step fails the stage and stops later stages. |
| `BuildStep` | Executable unit that receives a `BuildContext`. |
| `Agent` | Worker capability descriptor with labels such as `linux` or `docker`. |
| `BuildRun` | Runtime record for one build number: status, stage results, logs, timestamps, agent. |
| `BuildScheduler` | Facade for registering, triggering, cancelling, and inspecting builds. |

## Usage

```java
InMemoryBuildScheduler scheduler =
        InMemoryBuildScheduler.withSingleAgent("linux-1", Set.of("linux", "docker"));

PipelineDefinition pipeline = PipelineDefinition.builder("deploy-api")
        .requiredLabel("docker")
        .env("BRANCH", "main")
        .addStage(Stage.of("checkout",
                BuildStep.named("clone", ctx -> ctx.log("checkout " + ctx.getEnv("BRANCH")))))
        .addStage(Stage.of("test",
                BuildStep.named("unit", ctx -> ctx.log("mvn test"))))
        .build();

BuildRun run = scheduler.submit(pipeline, Map.of("SUITE", "fast"));
```

## Design notes

- **Queueing:** submitted builds enter a FIFO `LinkedBlockingQueue`.
- **Agent routing:** each worker thread owns one `Agent`; a build can run only
  when the agent contains all required labels.
- **Execution:** stages and steps execute sequentially within a build run. The
  first step exception marks the step, stage, and build as failed.
- **Observability:** `BuildRun` stores timestamped logs plus structured
  `StageResult` and `StepResult` objects.
- **Scope:** this is intentionally in-memory. Persistence, distributed agents,
  SCM webhooks, artifact storage, credentials, and retry policies can be added
  behind the same scheduler/domain boundaries.

## Run tests

```bash
mvn test -Dtest=lld.jenkinslike.InMemoryBuildSchedulerTest
```
