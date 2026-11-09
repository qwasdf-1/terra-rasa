package dev.terrarasa.mixin;

import java.util.function.Consumer;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Invoker("forEachBlockTickingChunk")
    void terrarasa$forEachBlockTickingChunk(Consumer<LevelChunk> consumer);
}
