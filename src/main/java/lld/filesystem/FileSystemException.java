package lld.filesystem;

/**
 * Checked-style runtime error for invalid filesystem operations.
 */
public final class FileSystemException extends RuntimeException {
    public FileSystemException(String message) {
        super(message);
    }
}
