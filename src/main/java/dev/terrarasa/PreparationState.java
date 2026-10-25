package dev.terrarasa;

/** Preparation state machine. */
public enum PreparationState {
    CREATING,
    SPAWN_FINDING,
    PRELOADING,
    /** The run could start; its wider area is being generated until done or until a start is requested. */
    PREGENERATING,
    FLUSHING,
    /** Closed; the CLOSED_READY manifest is being written in the background. */
    PUBLISHING,
    CLOSED_READY,
    FAILED,
    ABANDONED;

    public boolean isTerminal() {
        return this == CLOSED_READY || this == FAILED || this == ABANDONED;
    }
}
