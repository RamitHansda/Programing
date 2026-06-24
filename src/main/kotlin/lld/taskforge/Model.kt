package lld.taskforge

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

enum class TaskStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    SKIPPED_CONDITION,
    SKIPPED_UPSTREAM_FAILED,
    CANCELLED
}

enum class ExecutionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

data class RetryPolicy(
    val maxAttempts: Int = 1,
    val backoffMillis: Long = 0
)

data class TaskDefinition(
    val id: String,
    val type: String,
    val dependsOn: List<String> = emptyList(),
    val condition: Any? = null,
    val config: Map<String, Any?> = emptyMap(),
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val timeoutMillis: Long? = null
)

data class WorkflowDefinition(
    val id: String,
    val name: String,
    val tasks: List<TaskDefinition>
)

data class ValidationError(
    val path: String,
    val message: String
)

data class TaskSnapshot(
    val id: String,
    val type: String,
    val status: TaskStatus,
    val attempt: Int,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val output: Map<String, Any?>,
    val error: String?
)

data class ExecutionSnapshot(
    val id: String,
    val workflowId: String,
    val status: ExecutionStatus,
    val startedAt: Instant,
    val completedAt: Instant?,
    val tasks: List<TaskSnapshot>
)

sealed class HandlerResult {
    data class Success(val output: Map<String, Any?> = emptyMap()) : HandlerResult()

    data class Failure(
        val message: String,
        val retryable: Boolean = false,
        val output: Map<String, Any?> = emptyMap()
    ) : HandlerResult()
}

class TaskFailureException(
    message: String,
    val retryable: Boolean = false
) : RuntimeException(message)

class MissingReferenceException(val reference: String) : RuntimeException("Reference '$reference' could not be resolved")

class WorkflowValidationException(val errors: List<ValidationError>) : RuntimeException(
    errors.joinToString("; ") { "${it.path}: ${it.message}" }
)

class CancellationToken internal constructor(private val cancelled: AtomicBoolean) {
    fun isCancellationRequested(): Boolean = cancelled.get()

    fun throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw InterruptedException("Execution was cancelled")
        }
    }
}

data class ApprovalRequest(
    val actor: String,
    val decision: String,
    val token: String? = null,
    val comment: String? = null
)

data class ApprovalOutcome(
    val actor: String,
    val decision: String,
    val comment: String? = null
)

interface ApprovalBroker {
    fun awaitApproval(
        executionId: String,
        taskId: String,
        windowMillis: Long,
        expectedToken: String?,
        cancellationToken: CancellationToken
    ): ApprovalOutcome?
}

data class TaskContext(
    val workflow: WorkflowDefinition,
    val executionId: String,
    val task: TaskDefinition,
    val config: Map<String, Any?>,
    val taskOutputs: Map<String, Map<String, Any?>>,
    val cancellationToken: CancellationToken,
    val approvalBroker: ApprovalBroker,
    val updateStatus: (TaskStatus) -> Unit = {}
) {
    fun outputOf(taskId: String): Map<String, Any?> = taskOutputs[taskId].orEmpty()
}

interface TaskHandler {
    val type: String

    fun execute(context: TaskContext): HandlerResult
}

