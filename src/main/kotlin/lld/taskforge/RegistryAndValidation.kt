package lld.taskforge

import java.util.concurrent.ConcurrentHashMap

class TaskHandlerRegistry(handlers: Iterable<TaskHandler> = emptyList()) {
    private val handlersByType = ConcurrentHashMap<String, TaskHandler>()

    init {
        handlers.forEach(::register)
    }

    fun register(handler: TaskHandler): TaskHandlerRegistry {
        require(handler.type.isNotBlank()) { "Task handler type must not be blank" }
        handlersByType[handler.type] = handler
        return this
    }

    fun get(type: String): TaskHandler? = handlersByType[type]

    fun require(type: String): TaskHandler = get(type)
        ?: throw IllegalArgumentException("No task handler registered for type '$type'")

    fun knownTypes(): Set<String> = handlersByType.entries.map { it.key }.toSet()
}

class WorkflowValidator(private val registry: TaskHandlerRegistry) {
    fun validate(definition: WorkflowDefinition): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        if (definition.id.isBlank()) {
            errors += ValidationError("$.id", "workflow id is required")
        }
        if (definition.tasks.isEmpty()) {
            errors += ValidationError("$.tasks", "workflow must contain at least one task")
            return errors
        }

        val ids = mutableSetOf<String>()
        definition.tasks.forEachIndexed { index, task ->
            val path = "$.tasks[$index]"
            if (task.id.isBlank()) {
                errors += ValidationError("$path.id", "task id is required")
            } else if (!ids.add(task.id)) {
                errors += ValidationError("$path.id", "duplicate task id '${task.id}'")
            }
            if (task.type.isBlank()) {
                errors += ValidationError("$path.type", "task type is required")
            } else if (registry.get(task.type) == null) {
                errors += ValidationError("$path.type", "unknown task type '${task.type}'")
            }
            if (task.retryPolicy.maxAttempts < 1) {
                errors += ValidationError("$path.retryPolicy.maxAttempts", "must be at least 1")
            }
            if (task.retryPolicy.backoffMillis < 0) {
                errors += ValidationError("$path.retryPolicy.backoffMillis", "must not be negative")
            }
            if (task.timeoutMillis != null && task.timeoutMillis <= 0) {
                errors += ValidationError("$path.timeoutMillis", "must be positive when provided")
            }
        }

        val tasksById = definition.tasks.associateBy { it.id }
        definition.tasks.forEachIndexed { index, task ->
            task.dependsOn.forEachIndexed { depIndex, dependency ->
                if (dependency !in tasksById) {
                    errors += ValidationError(
                        "$.tasks[$index].dependsOn[$depIndex]",
                        "dependency '$dependency' does not refer to a task in this workflow"
                    )
                }
            }
        }

        val cycle = findCycle(definition.tasks)
        if (cycle.isNotEmpty()) {
            errors += ValidationError("$.tasks", "cycle detected: ${cycle.joinToString(" -> ")}")
        }

        return errors
    }

    private fun findCycle(tasks: List<TaskDefinition>): List<String> {
        val byId = tasks.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val stack = mutableListOf<String>()

        fun dfs(taskId: String): List<String> {
            if (taskId !in byId) return emptyList()
            if (taskId in visiting) {
                val start = stack.indexOf(taskId)
                return stack.subList(start, stack.size) + taskId
            }
            if (taskId in visited) return emptyList()

            visiting += taskId
            stack += taskId
            byId.getValue(taskId).dependsOn.forEach { dependency ->
                val cycle = dfs(dependency)
                if (cycle.isNotEmpty()) return cycle
            }
            stack.removeAt(stack.lastIndex)
            visiting -= taskId
            visited += taskId
            return emptyList()
        }

        tasks.forEach { task ->
            val cycle = dfs(task.id)
            if (cycle.isNotEmpty()) return cycle
        }
        return emptyList()
    }
}

object ReferenceResolver {
    private val referencePattern = Regex("\\$\\{([^}]+)}")

    fun resolveConfig(value: Any?, outputs: Map<String, Map<String, Any?>>): Any? {
        return when (value) {
            is Map<*, *> -> value.entries.associate { (key, nestedValue) ->
                key.toString() to resolveConfig(nestedValue, outputs)
            }
            is List<*> -> value.map { resolveConfig(it, outputs) }
            is String -> resolveString(value, outputs)
            else -> value
        }
    }

    fun evaluateCondition(condition: Any?, outputs: Map<String, Map<String, Any?>>): Boolean {
        return when (condition) {
            null -> true
            is Boolean -> condition
            is String -> evaluateStringCondition(condition, outputs)
            else -> truthy(condition)
        }
    }

    private fun resolveString(value: String, outputs: Map<String, Map<String, Any?>>): Any? {
        val exactReference = referencePattern.matchEntire(value)
        if (exactReference != null) {
            return resolvePath(exactReference.groupValues[1].trim(), outputs)
        }
        return referencePattern.replace(value) { match ->
            val resolved = resolvePath(match.groupValues[1].trim(), outputs)
            resolved?.toString() ?: ""
        }
    }

    private fun evaluateStringCondition(raw: String, outputs: Map<String, Map<String, Any?>>): Boolean {
        val condition = raw.trim()
        if (condition.isEmpty()) return true
        if (condition.equals("true", ignoreCase = true)) return true
        if (condition.equals("false", ignoreCase = true)) return false
        if (condition.startsWith("exists(") && condition.endsWith(")")) {
            val path = condition.removePrefix("exists(").removeSuffix(")").trim().removeReferenceDelimiters()
            return try {
                resolvePath(path, outputs) != null
            } catch (_: MissingReferenceException) {
                false
            }
        }

        val operator = when {
            "!=" in condition -> "!="
            "==" in condition -> "=="
            else -> null
        }
        if (operator != null) {
            val parts = condition.split(operator, limit = 2)
            val left = resolveOperand(parts[0].trim(), outputs, missingAsNull = true)
            val right = parseLiteral(parts[1].trim())
            return if (operator == "==") left == right else left != right
        }

        return truthy(resolveOperand(condition, outputs, missingAsNull = true))
    }

    private fun resolveOperand(raw: String, outputs: Map<String, Map<String, Any?>>, missingAsNull: Boolean): Any? {
        val operand = raw.removeReferenceDelimiters()
        return try {
            if (looksLikeReference(operand)) resolvePath(operand, outputs) else parseLiteral(raw)
        } catch (e: MissingReferenceException) {
            if (missingAsNull) null else throw e
        }
    }

    private fun resolvePath(path: String, outputs: Map<String, Map<String, Any?>>): Any? {
        val parts = path.split(".").filter { it.isNotBlank() }
        if (parts.size < 3 || parts[0] != "tasks" || parts[2] != "output") {
            throw MissingReferenceException(path)
        }
        var current: Any? = outputs[parts[1]] ?: throw MissingReferenceException(path)
        parts.drop(3).forEach { part ->
            current = when (val map = current) {
                is Map<*, *> -> if (map.containsKey(part)) map[part] else throw MissingReferenceException(path)
                else -> throw MissingReferenceException(path)
            }
        }
        return current
    }

    private fun parseLiteral(raw: String): Any? {
        val value = raw.trim()
        return when {
            value.equals("null", ignoreCase = true) -> null
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            value.startsWith("\"") && value.endsWith("\"") && value.length >= 2 -> value.substring(1, value.length - 1)
            value.startsWith("'") && value.endsWith("'") && value.length >= 2 -> value.substring(1, value.length - 1)
            value.toLongOrNull() != null -> value.toLong()
            value.toDoubleOrNull() != null -> value.toDouble()
            else -> value
        }
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotBlank() && !value.equals("false", ignoreCase = true)
        else -> true
    }

    private fun looksLikeReference(value: String): Boolean = value.startsWith("tasks.") && ".output" in value

    private fun String.removeReferenceDelimiters(): String {
        val trimmed = trim()
        return if (trimmed.startsWith("\${") && trimmed.endsWith("}")) {
            trimmed.substring(2, trimmed.length - 1).trim()
        } else {
            trimmed
        }
    }
}

