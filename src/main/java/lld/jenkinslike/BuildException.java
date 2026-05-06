package lld.jenkinslike;

/**
 * Domain exception raised when a build cannot be accepted or controlled.
 */
public class BuildException extends RuntimeException {
    public BuildException(String message) {
        super(message);
    }
}
