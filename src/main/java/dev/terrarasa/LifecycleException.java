package dev.terrarasa;

/** A lifecycle step failed; callers decide whether recovery is possible from the current phase. */
public class LifecycleException extends Exception {
    public LifecycleException(String message) {
        super(message);
    }

    public LifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
