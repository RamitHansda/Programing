package lld.taskforge

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object BuiltInTaskHandlers {
    fun registry(baseDirectory: Path = Paths.get(System.getProperty("java.io.tmpdir"), "taskforge-files")): TaskHandlerRegistry {
        return TaskHandlerRegistry(
            listOf(
                HttpTaskHandler(),
                ScriptTaskHandler(),
                FileTaskHandler(baseDirectory),
                ApprovalTaskHandler()
            )
        )
    }
}

class HttpTaskHandler(
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
) : TaskHandler {
    override val type: String = "http"

    override fun execute(context: TaskContext): HandlerResult {
        val url = context.config.string("url") ?: return HandlerResult.Failure("config.url is required")
        val method = context.config.string("method")?.uppercase() ?: "GET"
        val headers = context.config.map("headers").orEmpty()
        val body = context.config["body"]?.toString()
        val successStatusCodes = context.config.longList("successStatusCodes")?.map { it.toInt() }?.toSet()

        val builder = HttpRequest.newBuilder(URI.create(url))
        context.task.timeoutMillis?.let { builder.timeout(Duration.ofMillis(it)) }
        headers.forEach { (name, value) -> builder.header(name, value.toString()) }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val ok = if (successStatusCodes == null) response.statusCode() in 200..299 else response.statusCode() in successStatusCodes
        val output = mapOf(
            "statusCode" to response.statusCode(),
            "body" to response.body(),
            "headers" to response.headers().map()
        )
        return if (ok) {
            HandlerResult.Success(output)
        } else {
            HandlerResult.Failure("HTTP status ${response.statusCode()} was outside the success range", retryable = response.statusCode() >= 500, output = output)
        }
    }
}

class ScriptTaskHandler : TaskHandler {
    override val type: String = "script"

    override fun execute(context: TaskContext): HandlerResult {
        val command = context.config.command() ?: return HandlerResult.Failure("config.command is required")
        val processBuilder = ProcessBuilder(command)
        context.config.string("workingDirectory")?.let { processBuilder.directory(Paths.get(it).toFile()) }
        context.config.map("environment")?.forEach { (name, value) -> processBuilder.environment()[name] = value.toString() }

        val process = processBuilder.start()
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readBytes().toString(StandardCharsets.UTF_8) }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readBytes().toString(StandardCharsets.UTF_8) }

        try {
            while (process.isAlive) {
                context.cancellationToken.throwIfCancellationRequested()
                process.waitFor(100, TimeUnit.MILLISECONDS)
            }
            val exitCode = process.exitValue()
            val output = mapOf(
                "exitCode" to exitCode,
                "stdout" to stdout.get(1, TimeUnit.SECONDS),
                "stderr" to stderr.get(1, TimeUnit.SECONDS)
            )
            return if (exitCode == 0) {
                HandlerResult.Success(output)
            } else {
                HandlerResult.Failure("Script exited with code $exitCode", retryable = exitCode in retryableExitCodes(context.config), output = output)
            }
        } catch (e: InterruptedException) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        } finally {
            if (context.cancellationToken.isCancellationRequested() && process.isAlive) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private fun retryableExitCodes(config: Map<String, Any?>): Set<Int> {
        return config.longList("retryableExitCodes").orEmpty().map { it.toInt() }.toSet()
    }
}

class FileTaskHandler(private val baseDirectory: Path) : TaskHandler {
    override val type: String = "file"

    override fun execute(context: TaskContext): HandlerResult {
        val operation = context.config.string("operation") ?: return HandlerResult.Failure("config.operation is required")
        val path = resolvePath(context.config.string("path") ?: return HandlerResult.Failure("config.path is required"))
        return when (operation.lowercase()) {
            "read" -> {
                if (!Files.exists(path)) return HandlerResult.Failure("File '$path' does not exist")
                HandlerResult.Success(
                    mapOf(
                        "path" to path.toString(),
                        "content" to Files.readString(path),
                        "size" to Files.size(path)
                    )
                )
            }
            "write", "append" -> {
                val content = context.config["content"]?.toString() ?: ""
                Files.createDirectories(path.parent ?: baseDirectory)
                if (operation.equals("append", ignoreCase = true)) {
                    Files.writeString(path, content, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
                } else {
                    Files.writeString(path, content)
                }
                HandlerResult.Success(mapOf("path" to path.toString(), "size" to Files.size(path)))
            }
            "delete" -> HandlerResult.Success(mapOf("path" to path.toString(), "deleted" to Files.deleteIfExists(path)))
            else -> HandlerResult.Failure("Unsupported file operation '$operation'")
        }
    }

    private fun resolvePath(rawPath: String): Path {
        val requested = Paths.get(rawPath)
        val normalized = if (requested.isAbsolute) requested.normalize() else baseDirectory.resolve(requested).normalize()
        val normalizedBase = baseDirectory.toAbsolutePath().normalize()
        require(normalized.toAbsolutePath().normalize().startsWith(normalizedBase)) {
            "File task path '$rawPath' escapes base directory '$normalizedBase'"
        }
        return normalized
    }
}

class ApprovalTaskHandler : TaskHandler {
    override val type: String = "approval"

    override fun execute(context: TaskContext): HandlerResult {
        val windowMillis = context.config.long("approvalTimeoutMillis")
            ?: context.task.timeoutMillis
            ?: return HandlerResult.Failure("approvalTimeoutMillis or task.timeoutMillis is required")
        val token = context.config.string("token")

        context.updateStatus(TaskStatus.WAITING_APPROVAL)
        val approval = context.approvalBroker.awaitApproval(
            executionId = context.executionId,
            taskId = context.task.id,
            windowMillis = windowMillis,
            expectedToken = token,
            cancellationToken = context.cancellationToken
        ) ?: return HandlerResult.Failure("Approval timed out after ${windowMillis}ms")

        val output = mapOf(
            "actor" to approval.actor,
            "decision" to approval.decision,
            "comment" to approval.comment
        )
        return if (approval.decision.equals("approved", ignoreCase = true)) {
            HandlerResult.Success(output)
        } else {
            HandlerResult.Failure("Approval decision was '${approval.decision}'", output = output)
        }
    }
}

private fun Map<String, Any?>.string(key: String): String? = this[key]?.toString()

private fun Map<String, Any?>.long(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

private fun Map<String, Any?>.longList(key: String): List<Long>? {
    return when (val value = this[key]) {
        is List<*> -> value.mapNotNull {
            when (it) {
                is Number -> it.toLong()
                is String -> it.toLongOrNull()
                else -> null
            }
        }
        else -> null
    }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.map(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

private fun Map<String, Any?>.command(): List<String>? {
    return when (val value = this["command"]) {
        is String -> listOf("bash", "-lc", value)
        is List<*> -> value.map { it.toString() }
        else -> null
    }
}

