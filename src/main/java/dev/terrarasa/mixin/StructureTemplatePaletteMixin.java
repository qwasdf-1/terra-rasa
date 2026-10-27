package dev.terrarasa.mixin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Minecraft's lazily populated structure palette cache safe during parallel chunk generation. */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructureTemplatePaletteMixin {
    @Shadow @Final @Mutable private Map<?, ?> cache;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void terrarasa$useConcurrentBlockCache(CallbackInfo ci) {
        this.cache = new ConcurrentHashMap<>();
    }
}
