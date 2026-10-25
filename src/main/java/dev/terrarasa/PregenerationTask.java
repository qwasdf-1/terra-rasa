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
        return Pregenerator.order(this.radiusChunks).length / 2;
    }

    boolean isComplete() {
        return this.next >= total();
    }

    PregenerationTask copy() {
        return new PregenerationTask(this.dimension, this.centerChunkX, this.centerChunkZ, this.radiusChunks, this.next);
    }
}
