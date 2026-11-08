package dev.terrarasa;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Fabric's level load/unload notification, spelled as 26.x's API does. */
public final class FabricLevelEvents {
    private FabricLevelEvents() {
    }

    public static void levelLoaded(MinecraftServer server, ServerLevel level) {
        ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level);
    }

    public static void levelUnloaded(MinecraftServer server, ServerLevel level) {
        ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level);
    }
}
