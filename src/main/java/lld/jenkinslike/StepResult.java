package lld.jenkinslike;

import java.time.Duration;

/**
 * Captures the result of a single pipeline step.
 */
public class StepResult {
    private final String stageName;
    private final String stepName;
    private final StepStatus status;
    private final Duration duration;
    private final String errorMessage;

    public StepResult(String stageName,
                      String stepName,
                      StepStatus status,
                      Duration duration,
                      String errorMessage) {
        this.stageName = stageName;
        this.stepName = stepName;
        this.status = status;
        this.duration = duration;
        this.errorMessage = errorMessage;
    }

    public String getStageName() {
        return stageName;
    }

    public String getStepName() {
        return stepName;
    }

    public StepStatus getStatus() {
        return status;
    }

    public Duration getDuration() {
        return duration;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
