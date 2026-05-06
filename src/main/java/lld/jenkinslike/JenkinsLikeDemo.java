package lld.jenkinslike;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Small executable example for the in-memory Jenkins-like scheduler.
 */
public class JenkinsLikeDemo {
    public static void main(String[] args) throws InterruptedException {
        PipelineDefinition pipeline = PipelineDefinition.builder("payments-ci")
                .requiredLabel("linux")
                .env("BRANCH", "main")
                .addStage(Stage.of("checkout",
                        BuildStep.named("clone repo", context ->
                                context.log("Checking out " + context.getEnv("BRANCH")))))
                .addStage(Stage.of("test",
                        BuildStep.named("unit tests", context ->
                                context.log("mvn test")),
                        BuildStep.named("archive reports", context ->
                                context.log("reports archived"))))
                .build();

        try (InMemoryBuildScheduler scheduler =
                     InMemoryBuildScheduler.withSingleAgent("agent-1", Set.of("linux", "jdk21"))) {
            BuildRun run = scheduler.submit(pipeline, Map.of("BRANCH", "feature/ci"));
            waitFor(run);

            System.out.println("Build #" + run.getBuildNumber() + " -> " + run.getStatus());
            run.getLogs().forEach(entry -> System.out.println(entry.message()));
        }
    }

    private static void waitFor(BuildRun run) throws InterruptedException {
        while (run.getStatus() == BuildStatus.QUEUED || run.getStatus() == BuildStatus.RUNNING) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }
}
