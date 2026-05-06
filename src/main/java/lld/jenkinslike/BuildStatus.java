package lld.jenkinslike;

/**
 * Top-level lifecycle for a build run.
 */
public enum BuildStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}
