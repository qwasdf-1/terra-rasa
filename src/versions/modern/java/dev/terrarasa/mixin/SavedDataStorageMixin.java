package dev.terrarasa.mixin;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla writes saved data in place. It rarely rewrites {@code world_gen_settings.dat}, but Terra Rasa
 * rebinds the seed on every activation, and a torn write of that file makes the world unbootable before
 * any recovery code runs. Only that file is written atomically; all other saved data keeps vanilla's path.
 */
@Mixin(SavedDataStorage.class)
abstract class SavedDataStorageMixin {
    /**
     * Wraps vanilla's in-place write of {@code world_gen_settings.dat} into an atomic replace.
     *
     * <p>NeoForge redirects the same call to its own
     * {@code net.neoforged.neoforge.common.IOUtilities.writeNbtCompressed}, which already writes
     * through a temporary file and atomic move, so the injection has no target on that loader. The
     * injection therefore requires itself to match on Fabric and is skipped where it cannot: the
     * guarantee it exists to provide is already met there.
     */
    @WrapOperation(
            method = "tryWrite",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"),
            require = 0)
    private void terrarasa$writeWorldGenSettingsAtomically(CompoundTag tag, Path path, Operation<Void> original,
            @Local(argsOnly = true) SavedDataType<?> type) throws java.io.IOException {
        if (type != WorldGenSettings.TYPE) {
            original.call(tag, path);
            return;
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".terrarasa-tmp");
        NbtIo.writeCompressed(tag, temporary);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
