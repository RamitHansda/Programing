package lld.taskforge

object JsonSupport {
    fun parse(raw: String): Any? = Parser(raw).parse()

    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, nestedValue) ->
            "${quote(key.toString())}:${stringify(nestedValue)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        is WorkflowDefinition -> stringify(value.toJson())
        is TaskDefinition -> stringify(value.toJson())
        is RetryPolicy -> stringify(mapOf("maxAttempts" to value.maxAttempts, "backoffMillis" to value.backoffMillis))
        is ExecutionSnapshot -> stringify(value.toJson())
        is TaskSnapshot -> stringify(value.toJson())
        is ValidationError -> stringify(mapOf("path" to value.path, "message" to value.message))
        else -> quote(value.toString())
    }

    fun workflowFromJson(value: Any?): WorkflowDefinition {
        val map = value.asMap("workflow")
        val tasks = map.list("tasks").mapIndexed { index, taskValue ->
            val task = taskValue.asMap("tasks[$index]")
            TaskDefinition(
                id = task.requiredString("id", "tasks[$index].id"),
                type = task.requiredString("type", "tasks[$index].type"),
                dependsOn = task.stringList("dependsOn"),
                condition = task["condition"],
                config = task.map("config").orEmpty(),
                retryPolicy = task.map("retryPolicy")?.let {
                    RetryPolicy(
                        maxAttempts = it.long("maxAttempts")?.toInt() ?: 1,
                        backoffMillis = it.long("backoffMillis") ?: 0
                    )
                } ?: RetryPolicy(),
                timeoutMillis = task.long("timeoutMillis")
            )
        }
        return WorkflowDefinition(
            id = map.requiredString("id", "id"),
            name = map.string("name") ?: map.requiredString("id", "id"),
            tasks = tasks
        )
    }

    fun approvalRequestFromJson(value: Any?): ApprovalRequest {
        val map = value.asMap("approval")
        return ApprovalRequest(
            actor = map.requiredString("actor", "actor"),
            decision = map.requiredString("decision", "decision"),
            token = map.string("token"),
            comment = map.string("comment")
        )
    }

    private fun quote(value: String): String {
        val builder = StringBuilder("\"")
        value.forEach { char ->
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (char.code < 0x20) {
                    builder.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(char)
                }
            }
        }
        return builder.append('"').toString()
    }

    private class Parser(private val raw: String) {
        private var index = 0

        fun parse(): Any? {
            val value = parseValue()
            skipWhitespace()
            require(index == raw.length) { "Unexpected trailing JSON at character $index" }
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            require(index < raw.length) { "Unexpected end of JSON" }
            return when (raw[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseKeyword("true", true)
                'f' -> parseKeyword("false", false)
                'n' -> parseKeyword("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek('}')) {
                expect('}')
                return result
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                if (peek('}')) {
                    expect('}')
                    return result
                }
                expect(',')
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek(']')) {
                expect(']')
                return result
            }
            while (true) {
                result += parseValue()
                skipWhitespace()
                if (peek(']')) {
                    expect(']')
                    return result
                }
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (index < raw.length) {
                val char = raw[index++]
                when (char) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        require(index < raw.length) { "Unterminated escape sequence" }
                        val escaped = raw[index++]
                        builder.append(
                            when (escaped) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    val hex = raw.substring(index, index + 4)
                                    index += 4
                                    hex.toInt(16).toChar()
                                }
                                else -> error("Unsupported escape sequence \\$escaped")
                            }
                        )
                    }
                    else -> builder.append(char)
                }
            }
            error("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = index
            if (raw[index] == '-') index++
            while (index < raw.length && raw[index].isDigit()) index++
            if (index < raw.length && raw[index] == '.') {
                index++
                while (index < raw.length && raw[index].isDigit()) index++
            }
            if (index < raw.length && (raw[index] == 'e' || raw[index] == 'E')) {
                index++
                if (index < raw.length && (raw[index] == '+' || raw[index] == '-')) index++
                while (index < raw.length && raw[index].isDigit()) index++
            }
            val token = raw.substring(start, index)
            require(token.isNotEmpty() && token != "-") { "Expected JSON value at character $start" }
            return if (token.contains('.') || token.contains('e', ignoreCase = true)) token.toDouble() else token.toLong()
        }

        private fun parseKeyword(keyword: String, value: Any?): Any? {
            require(raw.startsWith(keyword, index)) { "Expected '$keyword' at character $index" }
            index += keyword.length
            return value
        }

        private fun skipWhitespace() {
            while (index < raw.length && raw[index].isWhitespace()) index++
        }

        private fun expect(expected: Char) {
            skipWhitespace()
            require(index < raw.length && raw[index] == expected) { "Expected '$expected' at character $index" }
            index++
        }

        private fun peek(expected: Char): Boolean {
            skipWhitespace()
            return index < raw.length && raw[index] == expected
        }
    }
}

private fun WorkflowDefinition.toJson(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "tasks" to tasks.map { it.toJson() }
)

private fun TaskDefinition.toJson(): Map<String, Any?> = mapOf(
    "id" to id,
    "type" to type,
    "dependsOn" to dependsOn,
    "condition" to condition,
    "config" to config,
    "retryPolicy" to retryPolicy,
    "timeoutMillis" to timeoutMillis
)

private fun ExecutionSnapshot.toJson(): Map<String, Any?> = mapOf(
    "id" to id,
    "workflowId" to workflowId,
    "status" to status.name,
    "startedAt" to startedAt.toString(),
    "completedAt" to completedAt?.toString(),
    "tasks" to tasks.map { it.toJson() }
)

private fun TaskSnapshot.toJson(): Map<String, Any?> = mapOf(
    "id" to id,
    "type" to type,
    "status" to status.name,
    "attempt" to attempt,
    "startedAt" to startedAt?.toString(),
    "completedAt" to completedAt?.toString(),
    "output" to output,
    "error" to error
)

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(name: String): Map<String, Any?> = this as? Map<String, Any?>
    ?: throw IllegalArgumentException("$name must be a JSON object")

private fun Map<String, Any?>.requiredString(key: String, path: String): String = string(key)
    ?: throw IllegalArgumentException("$path is required")

private fun Map<String, Any?>.string(key: String): String? = this[key]?.toString()

private fun Map<String, Any?>.long(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.map(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

private fun Map<String, Any?>.list(key: String): List<Any?> = when (val value = this[key]) {
    is List<*> -> value
    null -> emptyList()
    else -> throw IllegalArgumentException("$key must be an array")
}

private fun Map<String, Any?>.stringList(key: String): List<String> = list(key).map { it.toString() }

