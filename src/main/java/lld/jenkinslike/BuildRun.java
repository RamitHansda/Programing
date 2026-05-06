package lld.jenkinslike;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime record for a single build number.
 */
public class BuildRun {
    private final long buildNumber;
    private final PipelineDefinition pipeline;
    private final AtomicReference<BuildStatus> status;
    private final List<StageResult> stages;
    private final List<BuildLogEntry> logs;
    private final Instant queuedAt;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile String agentName;
    private volatile String failureMessage;

    public BuildRun(long buildNumber, PipelineDefinition pipeline) {
        this.buildNumber = buildNumber;
        this.pipeline = pipeline;
        this.status = new AtomicReference<>(BuildStatus.QUEUED);
        this.stages = new CopyOnWriteArrayList<>();
        this.logs = new CopyOnWriteArrayList<>();
        this.queuedAt = Instant.now();
    }

    public long getBuildNumber() {
        return buildNumber;
    }

    public PipelineDefinition getPipeline() {
        return pipeline;
    }

    public BuildStatus getStatus() {
        return status.get();
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Optional<Instant> getStartedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> getCompletedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Optional<String> getAgentName() {
        return Optional.ofNullable(agentName);
    }

    public Optional<String> getFailureMessage() {
        return Optional.ofNullable(failureMessage);
    }

    public List<StageResult> getStages() {
        return Collections.unmodifiableList(new ArrayList<>(stages));
    }

    public List<BuildLogEntry> getLogs() {
        return Collections.unmodifiableList(new ArrayList<>(logs));
    }

    boolean markRunning(String agentName) {
        boolean changed = status.compareAndSet(BuildStatus.QUEUED, BuildStatus.RUNNING);
        if (changed) {
            this.agentName = agentName;
            this.startedAt = Instant.now();
            log("Build started on agent " + agentName);
        }
        return changed;
    }

    void markSuccess() {
        status.set(BuildStatus.SUCCESS);
        completedAt = Instant.now();
        log("Build completed successfully");
    }

    void markFailed(String message) {
        status.set(BuildStatus.FAILED);
        failureMessage = message;
        completedAt = Instant.now();
        log("Build failed: " + message);
    }

    boolean cancel() {
        boolean changed = status.compareAndSet(BuildStatus.QUEUED, BuildStatus.CANCELLED);
        if (changed) {
            completedAt = Instant.now();
            log("Build cancelled before execution");
        }
        return changed;
    }

    void addStageResult(StageResult result) {
        stages.add(result);
    }

    void log(String message) {
        logs.add(new BuildLogEntry(Instant.now(), message));
    }
}
