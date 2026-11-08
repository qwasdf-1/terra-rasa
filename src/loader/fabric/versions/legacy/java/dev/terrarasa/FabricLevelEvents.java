package dev.terrarasa;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Fabric's world load/unload notification, spelled as 1.21.11's API does. */
public final class FabricLevelEvents {
    private FabricLevelEvents() {
    }

    public static void levelLoaded(MinecraftServer server, ServerLevel level) {
        ServerWorldEvents.LOAD.invoker().onWorldLoad(server, level);
    }

    public static void levelUnloaded(MinecraftServer server, ServerLevel level) {
        ServerWorldEvents.UNLOAD.invoker().onWorldUnload(server, level);
    }
}
