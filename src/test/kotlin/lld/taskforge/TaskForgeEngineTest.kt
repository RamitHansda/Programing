package lld.taskforge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TaskForgeEngineTest {
    private val registry = TaskHandlerRegistry(
        listOf(
            EmitHandler(),
            CaptureHandler(),
            FlakyHandler(),
            BlockingHandler(),
            ApprovalTaskHandler()
        )
    )
    private val engine = TaskForgeEngine(registry, cancellationGraceMillis = 25)

    @AfterEach
    fun tearDown() {
        engine.shutdown()
        FlakyHandler.reset()
    }

    @Test
    fun `validation reports unknown types dangling dependencies and cycles`() {
        val exception = assertThrows<WorkflowValidationException> {
            engine.registerWorkflow(
                WorkflowDefinition(
                    id = "bad",
                    name = "Bad workflow",
                    tasks = listOf(
                        TaskDefinition(id = "a", type = "missing", dependsOn = listOf("b")),
                        TaskDefinition(id = "b", type = "emit", dependsOn = listOf("a", "ghost"))
                    )
                )
            )
        }

        assertTrue(exception.errors.any { it.path.endsWith(".type") && "unknown task type" in it.message })
        assertTrue(exception.errors.any { "ghost" in it.message })
        assertTrue(exception.errors.any { "cycle detected" in it.message })
    }

    @Test
    fun `executes dependencies and resolves upstream output references`() {
        engine.registerWorkflow(
            WorkflowDefinition(
                id = "refs",
                name = "References",
                tasks = listOf(
                    TaskDefinition(id = "build", type = "emit", config = mapOf("key" to "image", "value" to "api:42")),
                    TaskDefinition(
                        id = "deploy",
                        type = "capture",
                        dependsOn = listOf("build"),
                        config = mapOf("message" to "deploying \${tasks.build.output.image}")
                    )
                )
            )
        )

        val execution = engine.startExecution("refs")
        val complete = awaitTerminal(execution.id)
        val deploy = complete.task("deploy")

        assertEquals(ExecutionStatus.SUCCEEDED, complete.status)
        assertEquals(TaskStatus.SUCCEEDED, deploy.status)
        assertEquals("deploying api:42", deploy.output["message"])
    }

    @Test
    fun `retryable failures are retried before downstream tasks run`() {
        engine.registerWorkflow(
            WorkflowDefinition(
                id = "retry",
                name = "Retry",
                tasks = listOf(
                    TaskDefinition(
                        id = "flaky",
                        type = "flaky",
                        retryPolicy = RetryPolicy(maxAttempts = 2),
                        config = mapOf("key" to "ready")
                    ),
                    TaskDefinition(
                        id = "after",
                        type = "capture",
                        dependsOn = listOf("flaky"),
                        config = mapOf("message" to "\${tasks.flaky.output.ready}")
                    )
                )
            )
        )

        val execution = engine.startExecution("retry")
        val complete = awaitTerminal(execution.id)

        assertEquals(ExecutionStatus.SUCCEEDED, complete.status)
        assertEquals(2, complete.task("flaky").attempt)
        assertEquals("true", complete.task("after").output["message"])
    }

    @Test
    fun `permanent failure skips downstream chain`() {
        engine.registerWorkflow(
            WorkflowDefinition(
                id = "fail",
                name = "Failure",
                tasks = listOf(
                    TaskDefinition(id = "bad", type = "capture", config = mapOf("fail" to true)),
                    TaskDefinition(id = "after", type = "emit", dependsOn = listOf("bad"))
                )
            )
        )

        val execution = engine.startExecution("fail")
        val complete = awaitTerminal(execution.id)

        assertEquals(ExecutionStatus.FAILED, complete.status)
        assertEquals(TaskStatus.FAILED, complete.task("bad").status)
        assertEquals(TaskStatus.SKIPPED_UPSTREAM_FAILED, complete.task("after").status)
    }

    @Test
    fun `task timeout marks task timed out and skips downstream work`() {
        engine.registerWorkflow(
            WorkflowDefinition(
                id = "timeout",
                name = "Timeout",
                tasks = listOf(
                    TaskDefinition(id = "block", type = "block", timeoutMillis = 50),
                    TaskDefinition(id = "after", type = "emit", dependsOn = listOf("block"))
                )
            )
        )

        val execution = engine.startExecution("timeout")
        val complete = awaitTerminal(execution.id)

        assertEquals(ExecutionStatus.FAILED, complete.status)
        assertEquals(TaskStatus.TIMED_OUT, complete.task("block").status)
        assertEquals(TaskStatus.SKIPPED_UPSTREAM_FAILED, complete.task("after").status)
    }

    @Test
    fun `cancelling an execution stops running tasks and prevents pending tasks from starting`() {
        engine.registerWorkflow(
            WorkflowDefinition(
                id = "cancel",
                name = "Cancel",
                tasks = listOf(
                    TaskDefinition(id = "block", type = "block"),
                    TaskDefinition(id = "after", type = "emit", dependsOn = listOf("block"))
                )
            )
        )

        val execution = engine.startExecution("cancel")
        awaitTaskStatus(execution.id, "block", TaskStatus.RUNNING)
        engine.cancelExecution(execution.id)
        val complete = awaitTerminal(execution.id)

        assertEquals(ExecutionStatus.CANCELLED, complete.status)
        assertEquals(TaskStatus.CANCELLED, complete.task("block").status)
        assertEquals(TaskStatus.CANCELLED, complete.task("after").status)
    }

    @Test
    fun `approval task waits for explicit identity and decision`() {
        engine.registerWorkflow(
            WorkflowDefinition(
                id = "approval",
                name = "Approval",
                tasks = listOf(
                    TaskDefinition(
                        id = "gate",
                        type = "approval",
                        config = mapOf("approvalTimeoutMillis" to 2_000, "token" to "release-token")
                    ),
                    TaskDefinition(
                        id = "after",
                        type = "capture",
                        dependsOn = listOf("gate"),
                        config = mapOf("message" to "approved by \${tasks.gate.output.actor}")
                    )
                )
            )
        )

        val execution = engine.startExecution("approval")
        awaitTaskStatus(execution.id, "gate", TaskStatus.WAITING_APPROVAL)
        val accepted = engine.resolveApproval(
            execution.id,
            "gate",
            ApprovalRequest(actor = "sre@example.com", decision = "approved", token = "release-token")
        )
        val complete = awaitTerminal(execution.id)

        assertTrue(accepted)
        assertEquals(ExecutionStatus.SUCCEEDED, complete.status)
        assertEquals("approved by sre@example.com", complete.task("after").output["message"])
    }

    private fun awaitTerminal(executionId: String): ExecutionSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val snapshot = engine.getExecution(executionId)
            if (snapshot != null && snapshot.status != ExecutionStatus.RUNNING) return snapshot
            Thread.sleep(10)
        }
        error("Execution $executionId did not finish")
    }

    private fun awaitTaskStatus(executionId: String, taskId: String, status: TaskStatus) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val snapshot = engine.getExecution(executionId)
            if (snapshot?.task(taskId)?.status == status) return
            Thread.sleep(10)
        }
        error("Task $taskId did not reach $status")
    }

    private fun ExecutionSnapshot.task(id: String): TaskSnapshot = tasks.first { it.id == id }
}

private class EmitHandler : TaskHandler {
    override val type: String = "emit"

    override fun execute(context: TaskContext): HandlerResult {
        val key = context.config["key"]?.toString() ?: "value"
        val value = context.config["value"] ?: true
        return HandlerResult.Success(mapOf(key to value.toString()))
    }
}

private class CaptureHandler : TaskHandler {
    override val type: String = "capture"

    override fun execute(context: TaskContext): HandlerResult {
        if (context.config["fail"] == true) return HandlerResult.Failure("requested failure")
        return HandlerResult.Success(mapOf("message" to context.config["message"].toString()))
    }
}

private class FlakyHandler : TaskHandler {
    override val type: String = "flaky"

    override fun execute(context: TaskContext): HandlerResult {
        val attempts = counters.computeIfAbsent(context.task.id) { AtomicInteger() }.incrementAndGet()
        return if (attempts == 1) {
            HandlerResult.Failure("temporary", retryable = true)
        } else {
            HandlerResult.Success(mapOf(context.config["key"].toString() to true.toString()))
        }
    }

    companion object {
        private val counters = ConcurrentHashMap<String, AtomicInteger>()

        fun reset() {
            counters.clear()
        }
    }
}

private class BlockingHandler : TaskHandler {
    override val type: String = "block"

    override fun execute(context: TaskContext): HandlerResult {
        while (!context.cancellationToken.isCancellationRequested()) {
            Thread.sleep(25)
        }
        return HandlerResult.Failure("cancelled")
    }
}

