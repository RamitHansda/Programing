# LLD: Worker pool manager (custom thread pool)

## Interview-ready snapshot

**Say first (≈30s):** Fixed set of worker `Thread`s pulling from a shared `BlockingQueue<Runnable>`; **Strategy** for rejection policy (abort/caller-runs/discard); config is an immutable value object; graceful vs immediate **shutdown** via poison pills + interrupt.

**Default assumptions:** In-process (not distributed), fixed pool size (no dynamic resize unless asked), tasks are `Runnable`/`Callable` with no built-in priority unless probed.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Fixed vs elastic pool; bounded vs unbounded queue; what "shutdown" must guarantee. |
| Model | 8 min | `WorkerPoolConfig`, `WorkerPoolManager`, `RejectionPolicy`, worker loop. |
| API + flow | 7 min | `execute/submit`, one walkthrough: submit → queue → worker picks up → run → count. |
| Hard | 12 min | Poison-pill vs interrupt shutdown; task failure isolation; queue-full policies; `Future`/`Callable` support. |
| Close | 3 min | Dynamic resizing, work-stealing, metrics as extensions. |

**Whiteboard order:** (1) `WorkerPoolConfig` (2) queue + worker threads diagram (3) `execute()` sequence (4) shutdown sequence (graceful vs now) (5) failure isolation in worker loop.

**Likely probes:** How is this different from `ThreadPoolExecutor`? What happens if a task throws? How do you know when everything drained on shutdown?

**30s closer:** Config and rejection policy are pluggable value/strategy objects; the manager only orchestrates queue ownership and worker lifecycle; failures are isolated per-task so one bad `Runnable` never kills a worker thread.

---

## Interview prompt (typical)

Design a **reusable worker pool** (like a mini `ThreadPoolExecutor`) that maintains a fixed number of worker threads pulling tasks from a shared queue. Support `execute(Runnable)`, graceful `shutdown()` (finish queued work, reject new submissions), and immediate `shutdownNow()` (stop ASAP, return unstarted tasks). Bonus: `submit(Callable<T>)` returning a `Future<T>`.

## Clarifying questions (ask first)

- **Pool sizing**: fixed only, or must it grow/shrink (elastic) under load?
- **Queue**: bounded (backpressure) or unbounded? What happens when full — block, reject, or run on caller's thread?
- **Task model**: fire-and-forget `Runnable` only, or also `Callable<T>` with a result/exception via `Future`?
- **Failure isolation**: should an uncaught exception in one task kill its worker thread, or must the worker keep running?
- **Shutdown semantics**: must `shutdown()` drain the queue before returning, or is it async with `awaitTermination`?

## Functional requirements

- `execute(Runnable task)` — submit fire-and-forget work; throws if pool is shut down.
- `submit(Callable<T> task)` — optional; returns a `Future<T>`.
- `shutdown()` — stop accepting new tasks, let queued tasks finish.
- `shutdownNow()` — stop ASAP; return the list of tasks that never ran.
- `awaitTermination(timeout)` — block until all workers exit or timeout elapses.

## Non-functional requirements

- **Thread-safe** submission from many caller threads.
- **No lost tasks**: every accepted task either runs or is returned by `shutdownNow()`.
- **Failure isolation**: one task's exception must not terminate its worker or corrupt pool state.
- **Observability**: completed-task count, current queue depth.

## Domain model

| Kind | Types | Notes |
|------|--------|------|
| **Value object** | `WorkerPoolConfig` (pool size, queue factory, `ThreadFactory`, `RejectionPolicy`) | Immutable; built once, shared read-only by the manager. |
| **Aggregate root** | `WorkerPoolManager` | Owns the queue, the worker `Thread` list, and lifecycle flags (`shutdown`, `shutdownNow`). |
| **Strategy** | `RejectionPolicy` (ABORT / CALLER_RUNS / DISCARD) | What to do when a task can't be queued. |
| **Internal worker loop** | `runWorker()` per thread | Pulls from the shared `BlockingQueue<Runnable>`; not a public type. |

**Relationships:** one `WorkerPoolManager` owns 1..N worker `Thread`s and exactly one `BlockingQueue<Runnable>`; `WorkerPoolConfig` is read-only input, never mutated after construction.

**Not modeled:** distributed task queues (that's a message broker LLD), priority scheduling (mention as follow-up).

## Core invariants

- Once `shutdown` is true, `execute()` must reject new tasks (per rejection policy) — no silent drops of *new* work after shutdown is requested.
- A task pulled from the queue always eventually runs or is drained back out via `shutdownNow()` — never left "in limbo".
- Worker threads never exit silently on an unchecked exception from a task; the loop catches `Throwable` around `task.run()`.

## Design patterns (where they matter)

| Pattern | Role |
|--------|------|
| **Strategy** | `RejectionPolicy` — swap ABORT/CALLER_RUNS/DISCARD without touching `execute()`'s core logic. |
| **Producer-Consumer** (structural, not GoF) | Callers produce into the `BlockingQueue`; workers consume — the core concurrency shape of this problem. |
| **Poison pill** | A sentinel `Runnable` signals "no more work" to a worker during graceful shutdown without needing a separate flag check per iteration. |
| **Builder** (optional) | `WorkerPoolConfig.builder()...build()` if config has many optional fields. |

## Java shape (interfaces)

```java
public final class WorkerPoolConfig {
    // immutable: poolSize, queueFactory, threadFactory, rejectionPolicy
    public BlockingQueue<Runnable> createWorkQueue() { ... }
}

public enum RejectionPolicy { ABORT, CALLER_RUNS, DISCARD }

public class WorkerPoolManager {
    public void execute(Runnable task);
    public <T> Future<T> submit(Callable<T> task); // bonus
    public void shutdown();
    public List<Runnable> shutdownNow();
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;
}
```

## Concurrency model (staff answer)

- **Shared state** is exactly one `BlockingQueue<Runnable>` — no other cross-thread mutable state besides an `AtomicInteger` completed-count and `volatile` shutdown flags.
- **Graceful shutdown**: offer one poison-pill sentinel per worker so each worker's blocking `take()` unblocks even with an empty queue; a worker exits when it sees the pill (after draining real work ahead of it).
- **Immediate shutdown**: flip `shutdownNow` volatile, `interrupt()` every worker (unblocks `take()`/`poll()`), then `drainTo()` the queue to collect never-run tasks.
- **Failure isolation**: wrap `task.run()` in `try { } catch (Throwable t)` inside the worker loop and route to `Thread.UncaughtExceptionHandler` — the worker's `while` loop continues.

## Failure modes

- **Task throws unchecked exception**: must not kill the worker thread (catch `Throwable`, not just `Exception`, since `Error` subclasses like `AssertionError` are common in tests).
- **Queue full with bounded queue + ABORT policy**: caller must get a clear `RejectedExecutionException`, not a silent drop.
- **Shutdown called twice / `execute()` after `shutdownNow()`**: must be idempotent and reject cleanly, not throw `NullPointerException` deep in internals.
- **`awaitTermination` timeout mid-drain**: must return `false` rather than hang forever.

## Testing strategy

- Submit N tasks, assert all N run exactly once (use a `CountDownLatch` or `AtomicInteger` counter, not `Thread.sleep`).
- Throw inside a task; assert the worker keeps processing subsequent tasks.
- Call `shutdown()` with tasks queued; assert queued tasks still complete and new `execute()` calls are rejected.
- Call `shutdownNow()`; assert returned list matches never-started tasks and workers exit promptly.
- Stress test: many producer threads submitting concurrently — no `ConcurrentModificationException`, no missed tasks.

## Follow-ups

- **Elastic pool** (core/max size + idle timeout, like `ThreadPoolExecutor`'s `corePoolSize`/`maximumPoolSize`).
- **Priority queue** for tasks with priority levels — swap `BlockingQueue` implementation, note starvation risk.
- **Work-stealing** (per-worker deques) for better load balancing under uneven task costs — mention `ForkJoinPool`.
- **Metrics/backpressure signals**: expose queue depth so callers can shed load upstream.

## Repo tie-in

A working reference implementation lives at `src/main/java/threads/workerpool/WorkerPoolManager.java` (+ `WorkerPoolConfig.java`, `WorkerPoolDemo.java`) in this workspace — it already implements the poison-pill graceful shutdown and per-task failure isolation described above; use it to sanity-check your whiteboard version.
