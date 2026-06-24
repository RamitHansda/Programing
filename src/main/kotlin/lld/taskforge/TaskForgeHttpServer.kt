package lld.taskforge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class TaskForgeHttpServer(
    private val engine: TaskForgeEngine,
    port: Int = 0
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0)

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange -> handle(exchange) }
    }

    val port: Int
        get() = server.address.port

    fun start() {
        server.start()
    }

    fun stop(delaySeconds: Int = 0) {
        server.stop(delaySeconds)
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.trim('/').split('/').filter { it.isNotBlank() }
            val method = exchange.requestMethod.uppercase()
            when {
                method == "POST" && path == listOf("workflows") -> createWorkflow(exchange)
                method == "GET" && path.size == 2 && path[0] == "workflows" -> getWorkflow(exchange, path[1])
                method == "POST" && path.size == 3 && path[0] == "workflows" && path[2] == "executions" -> startExecution(exchange, path[1])
                method == "GET" && path.size == 2 && path[0] == "executions" -> getExecution(exchange, path[1])
                method == "POST" && path.size == 3 && path[0] == "executions" && path[2] == "cancel" -> cancelExecution(exchange, path[1])
                method == "POST" && path.size == 4 && path[0] == "executions" && path[2] == "approvals" -> resolveApproval(exchange, path[1], path[3])
                else -> respond(exchange, 404, mapOf("error" to "No route for $method ${exchange.requestURI.path}"))
            }
        } catch (e: WorkflowValidationException) {
            respond(exchange, 422, mapOf("error" to "workflow validation failed", "details" to e.errors))
        } catch (e: NoSuchElementException) {
            respond(exchange, 404, mapOf("error" to e.message))
        } catch (e: IllegalArgumentException) {
            respond(exchange, 400, mapOf("error" to e.message))
        } catch (e: Exception) {
            respond(exchange, 500, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        } finally {
            exchange.close()
        }
    }

    private fun createWorkflow(exchange: HttpExchange) {
        val workflow = JsonSupport.workflowFromJson(readJson(exchange))
        respond(exchange, 201, engine.registerWorkflow(workflow))
    }

    private fun getWorkflow(exchange: HttpExchange, workflowId: String) {
        respond(exchange, 200, engine.getWorkflow(workflowId) ?: throw NoSuchElementException("Workflow '$workflowId' not found"))
    }

    private fun startExecution(exchange: HttpExchange, workflowId: String) {
        respond(exchange, 202, engine.startExecution(workflowId))
    }

    private fun getExecution(exchange: HttpExchange, executionId: String) {
        respond(exchange, 200, engine.getExecution(executionId) ?: throw NoSuchElementException("Execution '$executionId' not found"))
    }

    private fun cancelExecution(exchange: HttpExchange, executionId: String) {
        respond(exchange, 202, engine.cancelExecution(executionId))
    }

    private fun resolveApproval(exchange: HttpExchange, executionId: String, taskId: String) {
        val accepted = engine.resolveApproval(executionId, taskId, JsonSupport.approvalRequestFromJson(readJson(exchange)))
        if (accepted) {
            respond(exchange, 202, mapOf("accepted" to true))
        } else {
            respond(exchange, 409, mapOf("accepted" to false, "error" to "No matching pending approval, or token did not match"))
        }
    }

    private fun readJson(exchange: HttpExchange): Any? {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        return JsonSupport.parse(body.ifBlank { "{}" })
    }

    private fun respond(exchange: HttpExchange, status: Int, body: Any?) {
        val bytes = JsonSupport.stringify(body).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}

