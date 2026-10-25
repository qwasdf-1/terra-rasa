package dev.terrarasa.mixin;

import net.minecraft.server.ServerFunctionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerFunctionManager.class)
public interface ServerFunctionManagerAccessor {
    /** Set by vanilla at startup and on reload: the next function tick runs {@code #minecraft:load}. */
    @Accessor("postReload")
    void terrarasa$setPostReload(boolean postReload);
}
