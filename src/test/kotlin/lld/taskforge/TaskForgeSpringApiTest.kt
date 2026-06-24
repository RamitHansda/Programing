package lld.taskforge

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
class TaskForgeSpringApiTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `spring boot api registers workflow starts execution and returns task output`() {
        val workflowJson = """
            {
              "id": "api",
              "name": "API smoke",
              "tasks": [
                {
                  "id": "produce",
                  "type": "script",
                  "config": {"command": "printf hello"},
                  "timeoutMillis": 5000
                }
              ]
            }
        """.trimIndent()

        mockMvc.post("/workflows") {
            contentType = MediaType.APPLICATION_JSON
            content = workflowJson
        }.andExpect {
            status { isCreated() }
        }

        val start = mockMvc.post("/workflows/api/executions") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isAccepted() }
        }.andReturn()
        val executionId = JsonPath.read<String>(start.response.contentAsString, "$.id")
        val complete = awaitExecution(executionId)

        assertEquals("SUCCEEDED", complete["status"])
        assertTrue(complete.toString().contains("hello"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun awaitExecution(executionId: String): Map<String, Any?> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val response = mockMvc.get("/executions/$executionId").andExpect {
                status { isOk() }
            }.andReturn()
            val body = JsonPath.read<Map<String, Any?>>(response.response.contentAsString, "$")
            if (body["status"] != "RUNNING") return body
            Thread.sleep(10)
        }
        error("Execution did not finish")
    }
}

