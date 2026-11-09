package dev.terrarasa;

import java.util.Optional;
import java.util.UUID;

/**
 * Collision-free identities for staged dimensions.
 *
 * <p>A staged copy of {@code ns:path} for run {@code id} is {@code terrarasa:stage/<id>/ns/path}. Vanilla
 * resolves a dimension's storage as {@code dimensions/<namespace>/<path>}, so all of a run's staged data
 * lives under {@code dimensions/terrarasa/stage/<id>/} and never touches a canonical directory.
 */
public final class StageKeys {
    public static final String NAMESPACE = TerraRasa.MOD_ID;
    public static final String STAGE_PREFIX = "stage/";

    private StageKeys() {
    }

    public static ResourceIdentifier stageId(UUID runId, ResourceIdentifier target) {
        return ResourceIdentifier.fromNamespaceAndPath(NAMESPACE,
                STAGE_PREFIX + runId + "/" + target.namespace() + "/" + target.path());
    }

    public static boolean isStage(ResourceIdentifier id) {
        return NAMESPACE.equals(id.namespace()) && id.path().startsWith(STAGE_PREFIX);
    }

    /** Returns the run that owns a staged dimension identifier. */
    public static Optional<UUID> runOf(ResourceIdentifier id) {
        if (!isStage(id)) {
            return Optional.empty();
        }
        String rest = id.path().substring(STAGE_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(rest.substring(0, slash)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
