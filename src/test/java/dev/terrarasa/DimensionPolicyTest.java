package dev.terrarasa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class DimensionPolicyTest {
    private static final Identifier NETHER = Identifier.withDefaultNamespace("the_nether");
    private static final Identifier END = Identifier.withDefaultNamespace("the_end");
    private static final Identifier MODDED = Identifier.fromNamespaceAndPath("examplemod", "caves");
    /** A host mod's persistent dimension, such as a lobby. */
    private static final Identifier HUB = Identifier.fromNamespaceAndPath("examplehost", "hub");

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
        List<Identifier> stems = List.of(DimensionPolicy.OVERWORLD, NETHER, END, HUB);
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
        Identifier stage = StageKeys.stageId(run, NETHER);
        assertEquals("terrarasa:stage/" + run + "/minecraft/the_nether", stage.toString());
        assertEquals(run, StageKeys.runOf(stage).orElseThrow());
        assertTrue(StageKeys.runOf(NETHER).isEmpty());
    }
}
