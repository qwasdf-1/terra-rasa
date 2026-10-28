package dev.terrarasa;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * A circular area of one dimension to pregenerate, and how far it has got. Chunks are generated nearest
 * first ({@link Pregenerator#order}), so {@code next} is the only progress there is to save. Stored in the
 * run manifest while the run is staged, and in {@code pregeneration.json} once it is live.
 */
public final class PregenerationTask {
    public String dimension;
    public int centerChunkX;
    public int centerChunkZ;
    public int radiusChunks;
    /** Index into the nearest-first order of the first chunk that may not be generated yet. */
    public int next;

    public PregenerationTask() {
    }

    PregenerationTask(String dimension, int centerChunkX, int centerChunkZ, int radiusChunks, int next) {
        this.dimension = dimension;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.radiusChunks = radiusChunks;
        this.next = next;
    }

    /**
     * The area a run needs in {@code target}: around the run spawn, scaled into the dimension's coordinates
     * (the Nether's 1:8), or around the origin for the End, whose main island and fight are fixed there.
     */
    static PregenerationTask around(Identifier target, ServerLevel level, BlockPos spawn, int radiusBlocks) {
        int centerX = 0;
        int centerZ = 0;
        if (!target.equals(Level.END.identifier()) && level.getDragonFight() == null) {
            double scale = level.dimensionType().coordinateScale();
            centerX = ((int) Math.floor(spawn.getX() / scale)) >> 4;
            centerZ = ((int) Math.floor(spawn.getZ() / scale)) >> 4;
        }
        return new PregenerationTask(target.toString(), centerX, centerZ, (radiusBlocks + 15) >> 4, 0);
    }

    int total() {
        return totalForRadius(this.radiusChunks);
    }

    /** Checks persisted progress before it is allowed to allocate chunk-order arrays or touch a level. */
    void validate() {
        if (this.dimension == null) {
            throw new IllegalArgumentException("pregeneration dimension is missing");
        }
        Identifier id = Identifier.parse(this.dimension);
        if (!id.toString().equals(this.dimension) || StageKeys.isStage(id)) {
            throw new IllegalArgumentException("invalid pregeneration dimension " + this.dimension);
        }
        if (this.centerChunkX < -1_875_000 || this.centerChunkX > 1_875_000
                || this.centerChunkZ < -1_875_000 || this.centerChunkZ > 1_875_000) {
            throw new IllegalArgumentException("pregeneration center is outside the world border");
        }
        if (this.radiusChunks < 1 || this.radiusChunks > 256) {
            throw new IllegalArgumentException("pregeneration radius must be between 1 and 256 chunks");
        }
        if (this.next < 0 || this.next > total()) {
            throw new IllegalArgumentException("pregeneration progress is outside its area");
        }
    }

    static int totalForRadius(int radius) {
        int total = 0;
        long radiusSquared = (long) radius * radius;
        for (int x = -radius; x <= radius; x++) {
            int zRadius = (int) Math.floor(Math.sqrt(radiusSquared - (long) x * x));
            total += 2 * zRadius + 1;
        }
        return total;
    }

    boolean isComplete() {
        return this.next >= total();
    }

    PregenerationTask copy() {
        return new PregenerationTask(this.dimension, this.centerChunkX, this.centerChunkZ, this.radiusChunks, this.next);
    }
}
