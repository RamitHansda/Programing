package lld.taskforge

fun main() {
    val port = System.getenv("TASKFORGE_PORT")?.toIntOrNull() ?: 8080
    val engine = TaskForgeEngine(BuiltInTaskHandlers.registry())
    val server = TaskForgeHttpServer(engine, port)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
            engine.shutdown()
        }
    )
    server.start()
    println("TaskForge listening on http://localhost:${server.port}")
}

