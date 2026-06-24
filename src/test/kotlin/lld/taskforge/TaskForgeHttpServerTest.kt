package lld.taskforge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

class TaskForgeHttpServerTest {
    private val engine = TaskForgeEngine(TaskHandlerRegistry(listOf(ApiEchoHandler())))
    private val server = TaskForgeHttpServer(engine)
    private val client = HttpClient.newHttpClient()

    @AfterEach
    fun tearDown() {
        server.stop()
        engine.shutdown()
    }

    @Test
    fun `http api registers workflow starts execution and returns task output`() {
        server.start()
        val workflowJson = """
            {
              "id": "api",
              "name": "API smoke",
              "tasks": [
                {
                  "id": "echo",
                  "type": "api-echo",
                  "config": {"message": "hello"}
                }
              ]
            }
        """.trimIndent()

        val create = post("/workflows", workflowJson)
        assertEquals(201, create.statusCode())

        val start = post("/workflows/api/executions", "{}")
        assertEquals(202, start.statusCode())
        val executionId = (JsonSupport.parse(start.body()) as Map<*, *>)["id"].toString()
        val complete = awaitExecution(executionId)

        assertEquals("SUCCEEDED", complete["status"])
        assertTrue(JsonSupport.stringify(complete).contains("hello"))
    }

    private fun post(path: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:${server.port}$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:${server.port}$path")).GET().build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun awaitExecution(executionId: String): Map<String, Any?> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val response = get("/executions/$executionId")
            val body = JsonSupport.parse(response.body()) as Map<String, Any?>
            if (body["status"] != "RUNNING") return body
            Thread.sleep(10)
        }
        error("Execution did not finish")
    }
}

private class ApiEchoHandler : TaskHandler {
    override val type: String = "api-echo"

    override fun execute(context: TaskContext): HandlerResult {
        return HandlerResult.Success(mapOf("message" to context.config["message"]))
    }
}

