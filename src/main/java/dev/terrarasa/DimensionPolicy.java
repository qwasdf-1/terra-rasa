package dev.terrarasa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.resources.Identifier;

/**
 * Explicit classification of configured level stems. Only dimensions listed as resettable are ever
 * retired; everything unknown is {@link DimensionClass#EXTERNAL} and survives untouched.
 */
public record DimensionPolicy(List<Identifier> resettable, Set<Identifier> persistent) {
    public static final Identifier OVERWORLD = Identifier.withDefaultNamespace("overworld");

    public DimensionPolicy {
        // The Overworld owns the primary level data and must always be constructed first.
        LinkedHashSet<Identifier> ordered = new LinkedHashSet<>();
        if (resettable.contains(OVERWORLD)) {
            ordered.add(OVERWORLD);
        }
        ordered.addAll(resettable);
        resettable = List.copyOf(ordered);
        persistent = Set.copyOf(persistent);
    }

    public DimensionClass classify(Identifier dimension) {
        if (StageKeys.isStage(dimension)) {
            return DimensionClass.STAGE;
        }
        if (this.persistent.contains(dimension)) {
            return DimensionClass.PERSISTENT_SERVICE;
        }
        if (this.resettable.contains(dimension)) {
            return DimensionClass.RESETTABLE_CANONICAL;
        }
        return DimensionClass.EXTERNAL;
    }

    /** Preflight problems against the configured {@code LEVEL_STEM} registry; empty means valid. */
    public List<String> validate(Collection<Identifier> configuredStems) {
        List<String> problems = new ArrayList<>();
        if (!this.resettable.contains(OVERWORLD)) {
            problems.add("minecraft:overworld must be resettable");
        }
        for (Identifier id : this.resettable) {
            if (this.persistent.contains(id)) {
                problems.add(id + " cannot be both resettable and persistent");
            }
            if (StageKeys.isStage(id)) {
                problems.add(id + " is a staging identity and cannot be configured");
            }
            if (!configuredStems.contains(id)) {
                problems.add("resettable dimension " + id + " has no configured level stem");
            }
        }
        for (Identifier id : this.persistent) {
            if (!configuredStems.contains(id)) {
                problems.add("persistent dimension " + id + " has no configured level stem");
            }
        }
        return problems;
    }
}
