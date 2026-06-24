package lld.taskforge

import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

interface WorkflowStore {
    fun save(definition: WorkflowDefinition)
    fun get(id: String): WorkflowDefinition?
}

class InMemoryWorkflowStore : WorkflowStore {
    private val workflows = ConcurrentHashMap<String, WorkflowDefinition>()

    override fun save(definition: WorkflowDefinition) {
        workflows[definition.id] = definition
    }

    override fun get(id: String): WorkflowDefinition? = workflows[id]
}

class TaskForgeEngine(
    private val registry: TaskHandlerRegistry,
    private val store: WorkflowStore = InMemoryWorkflowStore(),
    workerThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(4),
    private val cancellationGraceMillis: Long = 250
) {
    private val validator = WorkflowValidator(registry)
    private val orchestratorExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val taskExecutor: ExecutorService = Executors.newFixedThreadPool(workerThreads)
    private val handlerExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val executions = ConcurrentHashMap<String, ExecutionRuntime>()
    private val approvals = InMemoryApprovalBroker()

    fun registerWorkflow(definition: WorkflowDefinition): WorkflowDefinition {
        val errors = validator.validate(definition)
        if (errors.isNotEmpty()) throw WorkflowValidationException(errors)
        store.save(definition)
        return definition
    }

    fun getWorkflow(id: String): WorkflowDefinition? = store.get(id)

    fun startExecution(workflowId: String): ExecutionSnapshot {
        val definition = store.get(workflowId) ?: throw NoSuchElementException("Workflow '$workflowId' not found")
        val execution = ExecutionRuntime(definition)
        executions[execution.id] = execution
        orchestratorExecutor.submit { runExecution(execution) }
        return execution.snapshot()
    }

    fun getExecution(executionId: String): ExecutionSnapshot? = executions[executionId]?.snapshot()

    fun cancelExecution(executionId: String): ExecutionSnapshot {
        val execution = executions[executionId] ?: throw NoSuchElementException("Execution '$executionId' not found")
        execution.cancelRequested.set(true)
        approvals.cancelExecution(executionId)
        Thread.sleep(cancellationGraceMillis)
        execution.runningFutures.values.forEach { it.cancel(true) }
        return execution.snapshot()
    }

    fun resolveApproval(executionId: String, taskId: String, request: ApprovalRequest): Boolean {
        return approvals.resolve(executionId, taskId, request)
    }

    fun shutdown() {
        orchestratorExecutor.shutdownNow()
        taskExecutor.shutdownNow()
        handlerExecutor.shutdownNow()
    }

    private fun runExecution(execution: ExecutionRuntime) {
        val completion = ExecutorCompletionService<TaskRunOutcome>(taskExecutor)
        val pending = execution.workflow.tasks.map { it.id }.toMutableSet()
        val running = mutableSetOf<String>()
        val tasksById = execution.workflow.tasks.associateBy { it.id }

        try {
            while (true) {
                if (execution.cancelRequested.get()) {
                    pending.toList().forEach { taskId ->
                        execution.taskStates.getValue(taskId).finish(TaskStatus.CANCELLED, "Execution was cancelled")
                        pending -= taskId
                    }
                    if (running.isEmpty()) {
                        execution.finish(ExecutionStatus.CANCELLED)
                        return
                    }
                }

                markSkippedByFailedDependencies(execution, pending, tasksById)

                val ready = pending.filter { taskId ->
                    tasksById.getValue(taskId).dependsOn.all { dependency ->
                        execution.taskStates.getValue(dependency).status in setOf(
                            TaskStatus.SUCCEEDED,
                            TaskStatus.SKIPPED_CONDITION
                        )
                    }
                }

                ready.forEach { taskId ->
                    val task = tasksById.getValue(taskId)
                    val shouldRun = ReferenceResolver.evaluateCondition(task.condition, execution.outputsSnapshot())
                    if (!shouldRun) {
                        execution.taskStates.getValue(task.id).finish(TaskStatus.SKIPPED_CONDITION, null)
                        pending -= task.id
                    } else {
                        val state = execution.taskStates.getValue(task.id)
                        state.start()
                        val future = completion.submit(Callable { runTaskWithRetry(execution, task) })
                        execution.runningFutures[task.id] = future
                        running += task.id
                        pending -= task.id
                    }
                }

                if (pending.isEmpty() && running.isEmpty()) {
                    val failed = execution.taskStates.values.any {
                        it.status in setOf(TaskStatus.FAILED, TaskStatus.TIMED_OUT, TaskStatus.SKIPPED_UPSTREAM_FAILED)
                    }
                    execution.finish(if (failed) ExecutionStatus.FAILED else ExecutionStatus.SUCCEEDED)
                    return
                }

                val completed = completion.poll(100, TimeUnit.MILLISECONDS)
                if (completed != null) {
                    val completedTaskId = execution.runningFutures.entries.firstOrNull { it.value == completed }?.key
                    if (completedTaskId == null) {
                        continue
                    }
                    val outcome = try {
                        completed.get()
                    } catch (_: CancellationException) {
                        TaskRunOutcome(completedTaskId, TaskStatus.CANCELLED, emptyMap(), "Execution was cancelled")
                    }
                    running -= outcome.taskId
                    execution.runningFutures.remove(outcome.taskId)
                    val state = execution.taskStates.getValue(outcome.taskId)
                    state.finish(outcome.status, outcome.error, outcome.output)
                    if (outcome.status == TaskStatus.SUCCEEDED) {
                        execution.taskOutputs[outcome.taskId] = outcome.output
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            execution.cancelRequested.set(true)
            execution.finish(ExecutionStatus.CANCELLED)
        } catch (e: Exception) {
            execution.finish(ExecutionStatus.FAILED)
        } finally {
            execution.runningFutures.values.forEach { it.cancel(true) }
            approvals.cancelExecution(execution.id)
        }
    }

    private fun markSkippedByFailedDependencies(
        execution: ExecutionRuntime,
        pending: MutableSet<String>,
        tasksById: Map<String, TaskDefinition>
    ) {
        val blockedStatuses = setOf(
            TaskStatus.FAILED,
            TaskStatus.TIMED_OUT,
            TaskStatus.CANCELLED,
            TaskStatus.SKIPPED_UPSTREAM_FAILED
        )
        pending.toList().forEach { taskId ->
            val task = tasksById.getValue(taskId)
            if (task.dependsOn.any { dependency -> execution.taskStates.getValue(dependency).status in blockedStatuses }) {
                execution.taskStates.getValue(taskId).finish(
                    TaskStatus.SKIPPED_UPSTREAM_FAILED,
                    "At least one dependency did not complete successfully"
                )
                pending -= taskId
            }
        }
    }

    private fun runTaskWithRetry(execution: ExecutionRuntime, task: TaskDefinition): TaskRunOutcome {
        val maxAttempts = max(1, task.retryPolicy.maxAttempts)
        var lastFailure: TaskRunOutcome? = null

        for (attempt in 1..maxAttempts) {
            if (execution.cancelRequested.get()) {
                return TaskRunOutcome(task.id, TaskStatus.CANCELLED, emptyMap(), "Execution was cancelled")
            }
            execution.taskStates.getValue(task.id).attempt = attempt
            try {
                val resolvedConfig = ReferenceResolver.resolveConfig(task.config, execution.outputsSnapshot()) as Map<String, Any?>
                val context = TaskContext(
                    workflow = execution.workflow,
                    executionId = execution.id,
                    task = task,
                    config = resolvedConfig,
                    taskOutputs = execution.outputsSnapshot(),
                    cancellationToken = CancellationToken(execution.cancelRequested),
                    approvalBroker = approvals,
                    updateStatus = { status -> execution.taskStates.getValue(task.id).status = status }
                )
                val result = executeHandlerWithTimeout(task, context)
                if (execution.cancelRequested.get()) {
                    return TaskRunOutcome(task.id, TaskStatus.CANCELLED, emptyMap(), "Execution was cancelled")
                }
                when (result) {
                    is HandlerResult.Success -> return TaskRunOutcome(task.id, TaskStatus.SUCCEEDED, result.output, null)
                    is HandlerResult.Failure -> {
                        lastFailure = TaskRunOutcome(task.id, TaskStatus.FAILED, result.output, result.message)
                        if (!result.retryable || attempt == maxAttempts) return lastFailure
                    }
                }
            } catch (e: MissingReferenceException) {
                return TaskRunOutcome(task.id, TaskStatus.FAILED, emptyMap(), e.message)
            } catch (e: TimeoutException) {
                lastFailure = TaskRunOutcome(task.id, TaskStatus.TIMED_OUT, emptyMap(), "Timed out after ${task.timeoutMillis}ms")
                if (attempt == maxAttempts) return lastFailure
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return TaskRunOutcome(task.id, TaskStatus.CANCELLED, emptyMap(), "Execution was cancelled")
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                val retryable = cause is TaskFailureException && cause.retryable
                lastFailure = TaskRunOutcome(task.id, TaskStatus.FAILED, emptyMap(), cause.message ?: cause.javaClass.simpleName)
                if (!retryable || attempt == maxAttempts) return lastFailure
            } catch (e: Exception) {
                lastFailure = TaskRunOutcome(task.id, TaskStatus.FAILED, emptyMap(), e.message ?: e.javaClass.simpleName)
                return lastFailure
            }

            sleepBeforeRetry(task.retryPolicy.backoffMillis, execution.cancelRequested)
            execution.taskStates.getValue(task.id).status = TaskStatus.RUNNING
        }
        return lastFailure ?: TaskRunOutcome(task.id, TaskStatus.FAILED, emptyMap(), "Task failed")
    }

    private fun executeHandlerWithTimeout(task: TaskDefinition, context: TaskContext): HandlerResult {
        val handler = registry.require(task.type)
        val future = handlerExecutor.submit<HandlerResult> { handler.execute(context) }
        return try {
            if (task.timeoutMillis == null) {
                future.get()
            } else {
                future.get(task.timeoutMillis, TimeUnit.MILLISECONDS)
            }
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw e
        }
    }

    private fun sleepBeforeRetry(backoffMillis: Long, cancelled: AtomicBoolean) {
        if (backoffMillis <= 0) return
        val deadline = System.currentTimeMillis() + backoffMillis
        while (System.currentTimeMillis() < deadline) {
            if (cancelled.get()) throw InterruptedException("Execution was cancelled")
            Thread.sleep((deadline - System.currentTimeMillis()).coerceAtMost(50))
        }
    }
}

private data class TaskRunOutcome(
    val taskId: String,
    val status: TaskStatus,
    val output: Map<String, Any?>,
    val error: String?
)

private class ExecutionRuntime(val workflow: WorkflowDefinition) {
    val id: String = UUID.randomUUID().toString()
    val startedAt: Instant = Instant.now()
    @Volatile var completedAt: Instant? = null
    @Volatile var status: ExecutionStatus = ExecutionStatus.RUNNING
    val cancelRequested = AtomicBoolean(false)
    val taskStates: Map<String, MutableTaskState> = workflow.tasks.associate { task ->
        task.id to MutableTaskState(task.id, task.type)
    }
    val taskOutputs = ConcurrentHashMap<String, Map<String, Any?>>()
    val runningFutures = ConcurrentHashMap<String, Future<*>>()

    fun outputsSnapshot(): Map<String, Map<String, Any?>> = taskOutputs.mapValues { it.value.toMap() }

    fun finish(finalStatus: ExecutionStatus) {
        if (completedAt == null) {
            status = finalStatus
            completedAt = Instant.now()
        }
    }

    fun snapshot(): ExecutionSnapshot = ExecutionSnapshot(
        id = id,
        workflowId = workflow.id,
        status = status,
        startedAt = startedAt,
        completedAt = completedAt,
        tasks = workflow.tasks.map { task -> taskStates.getValue(task.id).snapshot() }
    )
}

private class MutableTaskState(private val id: String, private val type: String) {
    @Volatile var status: TaskStatus = TaskStatus.PENDING
    @Volatile var attempt: Int = 0
    @Volatile var startedAt: Instant? = null
    @Volatile var completedAt: Instant? = null
    @Volatile var output: Map<String, Any?> = emptyMap()
    @Volatile var error: String? = null

    fun start() {
        status = TaskStatus.RUNNING
        startedAt = Instant.now()
    }

    fun finish(status: TaskStatus, error: String?, output: Map<String, Any?> = emptyMap()) {
        this.status = status
        this.error = error
        this.output = output
        completedAt = Instant.now()
    }

    fun snapshot(): TaskSnapshot = TaskSnapshot(
        id = id,
        type = type,
        status = status,
        attempt = attempt,
        startedAt = startedAt,
        completedAt = completedAt,
        output = output,
        error = error
    )
}

private class InMemoryApprovalBroker : ApprovalBroker {
    private data class Key(val executionId: String, val taskId: String)

    private data class PendingApproval(
        val expectedToken: String?,
        val future: CompletableFuture<ApprovalOutcome>
    )

    private val pending = ConcurrentHashMap<Key, PendingApproval>()

    override fun awaitApproval(
        executionId: String,
        taskId: String,
        windowMillis: Long,
        expectedToken: String?,
        cancellationToken: CancellationToken
    ): ApprovalOutcome? {
        val key = Key(executionId, taskId)
        val approval = PendingApproval(expectedToken, CompletableFuture())
        val existing = pending.putIfAbsent(key, approval)
        require(existing == null) { "Approval already pending for task '$taskId'" }

        val deadline = System.currentTimeMillis() + windowMillis
        try {
            while (System.currentTimeMillis() < deadline) {
                cancellationToken.throwIfCancellationRequested()
                val remaining = deadline - System.currentTimeMillis()
                try {
                    return approval.future.get(remaining.coerceAtMost(100), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Poll so cancellation can interrupt an approval wait promptly.
                }
            }
            return null
        } finally {
            pending.remove(key)
        }
    }

    fun resolve(executionId: String, taskId: String, request: ApprovalRequest): Boolean {
        val pendingApproval = pending[Key(executionId, taskId)] ?: return false
        if (pendingApproval.expectedToken != null && pendingApproval.expectedToken != request.token) {
            return false
        }
        return pendingApproval.future.complete(
            ApprovalOutcome(
                actor = request.actor,
                decision = request.decision,
                comment = request.comment
            )
        )
    }

    fun cancelExecution(executionId: String) {
        pending.entries
            .filter { it.key.executionId == executionId }
            .forEach { it.value.future.cancel(true) }
    }
}

