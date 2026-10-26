package dev.terrarasa;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla's {@code MinecraftServer.setInitialSpawn} search over chunks that are already loaded, in the
 * same spiral order, examining a few chunks per tick. Staged chunks never tick, so spreading the search
 * cannot change its result.
 */
final class SpawnSpiral {
    private static final int SIDE = 11;
    private final ServerLevel level;
    private final ChunkPos center;
    private final BlockPos fallback;
    private int x;
    private int z;
    private int dx;
    private int dz = -1;
    private int step;

    SpawnSpiral(ServerLevel level, ChunkPos center) {
        this.level = level;
        this.center = center;
        int height = level.getChunkSource().getGenerator().getSpawnHeight(level);
        if (height < level.getMinY()) {
            BlockPos worldPosition = center.getWorldPosition();
            height = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldPosition.getX() + 8, worldPosition.getZ() + 8);
        }
        this.fallback = center.getWorldPosition().offset(8, height, 8);
    }

    /** Examines up to {@code limit} chunks; returns the spawn once decided, otherwise null. */
    @Nullable BlockPos advance(int limit) {
        for (int examined = 0; examined < limit; examined++) {
            if (this.step >= Mth.square(SIDE)) {
                return this.fallback;
            }
            if (this.x >= -5 && this.x <= 5 && this.z >= -5 && this.z <= 5) {
                BlockPos candidate = PlayerSpawnFinder.getSpawnPosInChunk(this.level,
                        new ChunkPos(MinecraftCompat.chunkX(this.center) + this.x, MinecraftCompat.chunkZ(this.center) + this.z));
                if (candidate != null) {
                    return candidate;
                }
            }
            if (this.x == this.z || this.x < 0 && this.x == -this.z || this.x > 0 && this.x == 1 - this.z) {
                int previous = this.dx;
                this.dx = -this.dz;
                this.dz = previous;
            }
            this.x += this.dx;
            this.z += this.dz;
            this.step++;
        }
        return null;
    }
}
