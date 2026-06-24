package lld.taskforge

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class TaskForgeSpringConfiguration {
    @Bean
    fun taskHandlerRegistry(): TaskHandlerRegistry = BuiltInTaskHandlers.registry()

    @Bean(destroyMethod = "shutdown")
    fun taskForgeEngine(registry: TaskHandlerRegistry): TaskForgeEngine = TaskForgeEngine(registry)
}

