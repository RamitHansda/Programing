package lld.jenkinslike;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Public facade for submitting and inspecting Jenkins-like pipeline builds.
 */
public interface BuildScheduler extends AutoCloseable {
    void registerJob(PipelineDefinition pipeline);

    BuildRun triggerJob(String jobName);

    BuildRun triggerJob(String jobName, Map<String, String> parameters);

    BuildRun submit(PipelineDefinition pipeline);

    BuildRun submit(PipelineDefinition pipeline, Map<String, String> parameters);

    Optional<BuildRun> findBuild(long buildNumber);

    List<BuildRun> listBuilds();

    boolean cancel(long buildNumber);

    int queuedBuildCount();

    void shutdown();

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

    @Override
    default void close() {
        shutdown();
    }
}
