package dev.terrarasa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DimensionPolicyTest {
    private static final ResourceIdentifier NETHER = ResourceIdentifier.withDefaultNamespace("the_nether");
    private static final ResourceIdentifier END = ResourceIdentifier.withDefaultNamespace("the_end");
    private static final ResourceIdentifier MODDED = ResourceIdentifier.fromNamespaceAndPath("examplemod", "caves");
    /** A host mod's persistent dimension, such as a lobby. */
    private static final ResourceIdentifier HUB = ResourceIdentifier.fromNamespaceAndPath("examplehost", "hub");

    @Test
    void overworldIsAlwaysConstructedFirst() {
        DimensionPolicy policy = new DimensionPolicy(List.of(NETHER, DimensionPolicy.OVERWORLD, END), Set.of(HUB));
        assertEquals(List.of(DimensionPolicy.OVERWORLD, NETHER, END), policy.resettable());
    }

    @Test
    void unknownDimensionsAreNeverResettable() {
        DimensionPolicy policy = new DimensionPolicy(List.of(DimensionPolicy.OVERWORLD), Set.of(HUB));
        assertEquals(DimensionClass.EXTERNAL, policy.classify(MODDED));
        assertEquals(DimensionClass.PERSISTENT_SERVICE, policy.classify(HUB));
        assertEquals(DimensionClass.STAGE, policy.classify(StageKeys.stageId(UUID.randomUUID(), DimensionPolicy.OVERWORLD)));
    }

    @Test
    void preflightRejectsUnsafeConfigurations() {
        List<ResourceIdentifier> stems = List.of(DimensionPolicy.OVERWORLD, NETHER, END, HUB);
        assertTrue(new DimensionPolicy(List.of(DimensionPolicy.OVERWORLD, NETHER, END), Set.of(HUB))
                .validate(stems).isEmpty());
        assertEquals(1, new DimensionPolicy(List.of(NETHER), Set.of(HUB)).validate(stems).size());
        assertEquals(1, new DimensionPolicy(List.of(DimensionPolicy.OVERWORLD, MODDED), Set.of(HUB))
                .validate(stems).size());
        assertEquals(1, new DimensionPolicy(List.of(DimensionPolicy.OVERWORLD, HUB), Set.of(HUB))
                .validate(stems).size());
    }

    @Test
    void stageKeysAreUniquePerRunAndRecoverable() {
        UUID run = UUID.randomUUID();
        ResourceIdentifier stage = StageKeys.stageId(run, NETHER);
        assertEquals("terrarasa:stage/" + run + "/minecraft/the_nether", stage.toString());
        assertEquals(run, StageKeys.runOf(stage).orElseThrow());
        assertTrue(StageKeys.runOf(NETHER).isEmpty());
    }
}
