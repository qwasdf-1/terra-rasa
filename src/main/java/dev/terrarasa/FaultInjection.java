package dev.terrarasa;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Test-only failure points for the activation protocol. A checkpoint armed with
 * {@link #arm} fires once. {@code halt-*} points terminate the JVM immediately to simulate a crash,
 * leaving the journal for startup recovery.
 *
 * <p>Arming requires the JVM to be started with {@code -Dterrarasa.faultInjection=true}, so no
 * command or operator can halt a production server through this class.
 */
public final class FaultInjection {
    public static final String ENABLE_PROPERTY = "terrarasa.faultInjection";
    public static final String RETIRE = "retire";
    public static final String PROMOTE = "promote";
    public static final String RECONSTRUCT = "reconstruct";
    public static final String VALIDATE = "validate";
    public static final String HALT_AFTER_FIRST_MOVE = "halt-after-first-move";
    public static final String HALT_AFTER_PROMOTE = "halt-after-promote";
    public static final String HALT_AFTER_COMMIT = "halt-after-commit";
    public static final List<String> POINTS = List.of(
            RETIRE, PROMOTE, RECONSTRUCT, VALIDATE, HALT_AFTER_FIRST_MOVE, HALT_AFTER_PROMOTE, HALT_AFTER_COMMIT);
    /** Exit status of a simulated crash, distinct from vanilla's own exit codes. */
    public static final int HALT_STATUS = 86;

    private static final boolean ENABLED = Boolean.getBoolean(ENABLE_PROPERTY);
    private static volatile @Nullable String armed;

    private FaultInjection() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Arms {@code point}, or disarms with {@code null}. */
    public static void arm(@Nullable String point) throws LifecycleException {
        if (point != null && !ENABLED) {
            throw new LifecycleException("fault injection is disabled; start the JVM with -D" + ENABLE_PROPERTY + "=true");
        }
        if (point != null && !POINTS.contains(point)) {
            throw new LifecycleException("unknown fault point " + point);
        }
        armed = point;
    }

    public static @Nullable String armed() {
        return armed;
    }

    public static void check(String point) throws LifecycleException {
        if (point.equals(armed)) {
            armed = null;
            if (point.startsWith("halt-")) {
                TerraRasa.LOGGER.error("Fault injection: halting JVM at {}", point);
                // auditor-ignore[exit_in_production]: simulated crash for the recovery tests, armable only with the JVM flag; it must skip the shutdown hooks and saves System.exit would run
                Runtime.getRuntime().halt(HALT_STATUS);
            }
            throw new LifecycleException("Injected failure at " + point);
        }
    }
}
