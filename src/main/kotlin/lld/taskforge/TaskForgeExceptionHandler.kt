package lld.taskforge

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ApiError(
    val error: String,
    val details: Any? = null
)

@RestControllerAdvice
class TaskForgeExceptionHandler {
    @ExceptionHandler(WorkflowValidationException::class)
    fun workflowValidation(exception: WorkflowValidationException): ResponseEntity<ApiError> {
        return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ApiError("workflow validation failed", exception.errors))
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(exception: NoSuchElementException): ResponseEntity<ApiError> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError(exception.message ?: "resource not found"))
    }

    @ExceptionHandler(IllegalArgumentException::class, HttpMessageNotReadableException::class)
    fun badRequest(exception: Exception): ResponseEntity<ApiError> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError(exception.message ?: "bad request"))
    }
}

