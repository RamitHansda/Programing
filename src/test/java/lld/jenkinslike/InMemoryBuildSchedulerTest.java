package lld.jenkinslike;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryBuildSchedulerTest {
    private InMemoryBuildScheduler scheduler;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void executesStagesInOrderAndMergesEnvironment() {
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        scheduler = InMemoryBuildScheduler.withSingleAgent("linux-1", Set.of("linux"));

        PipelineDefinition pipeline = PipelineDefinition.builder("deploy-api")
                .requiredLabel("linux")
                .env("BRANCH", "main")
                .addStage(Stage.of("checkout",
                        BuildStep.named("clone", context -> executed.add(context.getEnv("BRANCH")))))
                .addStage(Stage.of("test",
                        BuildStep.named("unit", context -> executed.add(context.getEnv("SUITE")))))
                .build();

        BuildRun run = scheduler.submit(pipeline, Map.of("SUITE", "unit-fast"));

        awaitStatus(run, BuildStatus.SUCCESS);

        assertEquals(List.of("main", "unit-fast"), executed);
        assertEquals(2, run.getStages().size());
        assertEquals("checkout", run.getStages().get(0).getName());
        assertEquals("test", run.getStages().get(1).getName());
        assertEquals(StepStatus.SUCCESS, run.getStages().get(0).getStatus());
        assertEquals("linux-1", run.getAgentName().orElseThrow());
        assertTrue(run.getLogs().stream().anyMatch(entry -> entry.message().contains("Build completed successfully")));
    }

    @Test
    void failingStepFailsBuildAndSkipsLaterStages() {
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        scheduler = InMemoryBuildScheduler.withSingleAgent("linux-1", Set.of("linux"));

        PipelineDefinition pipeline = PipelineDefinition.builder("broken-api")
                .requiredLabel("linux")
                .addStage(Stage.of("compile",
                        BuildStep.named("javac", context -> executed.add("compile"))))
                .addStage(Stage.of("test",
                        BuildStep.named("unit", context -> {
                            executed.add("test");
                            throw new IllegalStateException("unit tests failed");
                        })))
                .addStage(Stage.of("deploy",
                        BuildStep.named("publish", context -> executed.add("deploy"))))
                .build();

        BuildRun run = scheduler.submit(pipeline);

        awaitStatus(run, BuildStatus.FAILED);

        assertEquals(List.of("compile", "test"), executed);
        assertEquals("unit tests failed", run.getFailureMessage().orElseThrow());
        assertEquals(2, run.getStages().size());
        assertEquals(StepStatus.FAILED, run.getStages().get(1).getStatus());
        assertEquals("unit", run.getStages().get(1).getSteps().get(0).getStepName());
    }

    @Test
    void routesBuildToAgentThatMatchesRequiredLabels() {
        scheduler = new InMemoryBuildScheduler(List.of(
                new Agent("linux-1", Set.of("linux")),
                new Agent("docker-1", Set.of("linux", "docker"))));

        PipelineDefinition pipeline = PipelineDefinition.builder("container-build")
                .requiredLabel("docker")
                .addStage(Stage.of("image",
                        BuildStep.named("docker-build", context -> context.log("building image"))))
                .build();

        BuildRun run = scheduler.submit(pipeline);

        awaitStatus(run, BuildStatus.SUCCESS);

        assertEquals("docker-1", run.getAgentName().orElseThrow());
    }

    @Test
    void rejectsPipelineWhenNoAgentHasRequiredLabels() {
        scheduler = InMemoryBuildScheduler.withSingleAgent("linux-1", Set.of("linux"));

        PipelineDefinition pipeline = PipelineDefinition.builder("mac-build")
                .requiredLabel("macos")
                .addStage(Stage.of("test", BuildStep.named("xcode", context -> {
                })))
                .build();

        BuildException exception = assertThrows(BuildException.class, () -> scheduler.submit(pipeline));

        assertTrue(exception.getMessage().contains("no agent can run labels"));
    }

    @Test
    void canCancelQueuedBuildBeforeWorkerStartsIt() throws InterruptedException {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        scheduler = InMemoryBuildScheduler.withSingleAgent("linux-1", Set.of("linux"));

        PipelineDefinition blocking = PipelineDefinition.builder("long-build")
                .requiredLabel("linux")
                .addStage(Stage.of("hold", BuildStep.named("wait", context -> {
                    blockerStarted.countDown();
                    assertTrue(releaseBlocker.await(1, TimeUnit.SECONDS));
                })))
                .build();
        PipelineDefinition queued = PipelineDefinition.builder("queued-build")
                .requiredLabel("linux")
                .addStage(Stage.of("run", BuildStep.named("echo", context -> context.log("should not run"))))
                .build();

        BuildRun first = scheduler.submit(blocking);
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
        BuildRun second = scheduler.submit(queued);

        assertTrue(scheduler.cancel(second.getBuildNumber()));
        releaseBlocker.countDown();

        awaitStatus(first, BuildStatus.SUCCESS);
        assertEquals(BuildStatus.CANCELLED, second.getStatus());
        assertTrue(second.getStages().isEmpty());
    }

    @Test
    void registeredJobsCanBeTriggeredByName() {
        scheduler = InMemoryBuildScheduler.withSingleAgent("linux-1", Set.of("linux"));
        PipelineDefinition pipeline = PipelineDefinition.builder("nightly")
                .requiredLabel("linux")
                .addStage(Stage.of("smoke", BuildStep.named("curl", context -> context.log("ok"))))
                .build();
        scheduler.registerJob(pipeline);

        BuildRun run = scheduler.triggerJob("nightly");

        awaitStatus(run, BuildStatus.SUCCESS);
        assertEquals("nightly", run.getPipeline().getJobName());
    }

    @Test
    void unknownRegisteredJobNameIsRejected() {
        scheduler = InMemoryBuildScheduler.withSingleAgent("linux-1", Set.of("linux"));

        BuildException exception = assertThrows(BuildException.class, () -> scheduler.triggerJob("missing"));

        assertEquals("job is not registered: missing", exception.getMessage());
    }

    private static void awaitStatus(BuildRun run, BuildStatus expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (run.getStatus() == expected) {
                return;
            }
            if (run.getStatus() == BuildStatus.FAILED && expected != BuildStatus.FAILED) {
                fail("build failed unexpectedly: " + run.getFailureMessage().orElse("<no message>"));
            }
            sleepBriefly();
        }
        fail("timed out waiting for " + expected + ", current status: " + run.getStatus());
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for build");
        }
    }
}
