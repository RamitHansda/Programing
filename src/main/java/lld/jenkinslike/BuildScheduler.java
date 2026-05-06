package lld.jenkinslike;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Public facade for submitting and inspecting Jenkins-like pipeline builds.
 */
public interface BuildScheduler extends AutoCloseable {
    void register(PipelineDefinition pipeline);

    BuildRun trigger(String jobName);

    BuildRun trigger(String jobName, Map<String, String> parameters);

    BuildRun submit(PipelineDefinition pipeline);

    BuildRun submit(PipelineDefinition pipeline, Map<String, String> parameters);

    Optional<BuildRun> getRun(long buildNumber);

    List<BuildRun> getRuns();

    boolean cancel(long buildNumber);

    int queuedBuildCount();

    void shutdown();

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

    @Override
    default void close() {
        shutdown();
    }
}
