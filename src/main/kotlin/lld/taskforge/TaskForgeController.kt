package lld.taskforge

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class TaskForgeController(private val engine: TaskForgeEngine) {
    @PostMapping("/workflows")
    fun createWorkflow(@RequestBody definition: WorkflowDefinition): ResponseEntity<WorkflowDefinition> {
        return ResponseEntity.status(HttpStatus.CREATED).body(engine.registerWorkflow(definition))
    }

    @GetMapping("/workflows/{workflowId}")
    fun getWorkflow(@PathVariable workflowId: String): WorkflowDefinition {
        return engine.getWorkflow(workflowId) ?: throw NoSuchElementException("Workflow '$workflowId' not found")
    }

    @PostMapping("/workflows/{workflowId}/executions")
    fun startExecution(@PathVariable workflowId: String): ResponseEntity<ExecutionSnapshot> {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(engine.startExecution(workflowId))
    }

    @GetMapping("/executions/{executionId}")
    fun getExecution(@PathVariable executionId: String): ExecutionSnapshot {
        return engine.getExecution(executionId) ?: throw NoSuchElementException("Execution '$executionId' not found")
    }

    @PostMapping("/executions/{executionId}/cancel")
    fun cancelExecution(@PathVariable executionId: String): ResponseEntity<ExecutionSnapshot> {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(engine.cancelExecution(executionId))
    }

    @PostMapping("/executions/{executionId}/approvals/{taskId}")
    fun resolveApproval(
        @PathVariable executionId: String,
        @PathVariable taskId: String,
        @RequestBody request: ApprovalRequest
    ): ResponseEntity<Map<String, Any>> {
        val accepted = engine.resolveApproval(executionId, taskId, request)
        return if (accepted) {
            ResponseEntity.status(HttpStatus.ACCEPTED).body(mapOf("accepted" to true))
        } else {
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf(
                    "accepted" to false,
                    "error" to "No matching pending approval, or token did not match"
                )
            )
        }
    }
}

