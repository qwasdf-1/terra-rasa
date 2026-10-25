package dev.terrarasa;

/** Ownership classes from the dimension policy. */
public enum DimensionClass {
    /** Replaced on every run activation (the vanilla trio plus explicitly configured extras). */
    RESETTABLE_CANONICAL,
    /** Never touched by run activation, e.g. a host mod's lobby. */
    PERSISTENT_SERVICE,
    /** A staging identity for a prospective run. */
    STAGE,
    /** Configured or runtime dimension not named by the policy; left untouched. */
    EXTERNAL
}
