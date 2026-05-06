package lld.jenkinslike;

/**
 * Lifecycle for an individual pipeline step or stage.
 */
public enum StepStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}
