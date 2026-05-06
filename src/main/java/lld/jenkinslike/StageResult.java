package lld.jenkinslike;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Execution outcome for one pipeline stage.
 */
public class StageResult {
    private final String name;
    private final List<StepResult> steps = new ArrayList<>();
    private Instant startedAt;
    private Instant finishedAt;
    private StepStatus status = StepStatus.QUEUED;

    StageResult(String name) {
        this.name = name;
    }

    void markRunning(Instant startedAt) {
        this.startedAt = startedAt;
        this.status = StepStatus.RUNNING;
    }

    void addStep(StepResult stepResult) {
        steps.add(stepResult);
    }

    void markFinished(StepStatus status, Instant finishedAt) {
        this.status = status;
        this.finishedAt = finishedAt;
    }

    public String getName() {
        return name;
    }

    public List<StepResult> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public StepStatus getStatus() {
        return status;
    }
}
