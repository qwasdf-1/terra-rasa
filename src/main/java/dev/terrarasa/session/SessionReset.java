package dev.terrarasa.session;

import java.util.ArrayList;
import java.util.List;

import dev.terrarasa.MinecraftCompat;
import dev.terrarasa.mixin.ServerFunctionManagerAccessor;
import dev.terrarasa.mixin.StatsCounterAccessor;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Restores run-owned state on live objects. Replacing files is not enough: the server keeps
 * cached live instances (scoreboard, command storage, boss events, timers, stats, advancements), so each
 * is reset through its owner and synchronized to connected clients.
 *
 * <p>Deliberately preserved: game rules, difficulty, whitelist/ops, and anything the host mod keeps outside
 * vanilla's saved data. Data packs are set up again by re-running {@code #minecraft:load}.
 * Map item data is left in place: map items themselves are cleared with player inventories.
 */
public final class SessionReset {
    private SessionReset() {
    }

    /** Server-owned run state. Call after canonical activation, before players enter the run. */
    public static void resetServerState(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();
        for (Objective objective : new ArrayList<>(scoreboard.getObjectives())) {
            scoreboard.removeObjective(objective);
        }
        for (PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) {
            scoreboard.removePlayerTeam(team);
        }

        CommandStorage storage = server.getCommandStorage();
        for (Identifier id : storage.keys().toList()) {
            storage.set(id, new CompoundTag());
        }

        for (CustomBossEvent event : new ArrayList<>(server.getCustomBossEvents().getEvents())) {
            event.removeAllPlayers();
            server.getCustomBossEvents().remove(event);
        }

        MinecraftCompat.resetWorldRunState(server);

        // A new world runs #minecraft:load when it opens. Data packs keep their setup there (objectives,
        // storage, boss bars, self-rescheduling loops), all of which were just cleared, so run it again on
        // the next function tick, exactly as vanilla does after startup and /reload.
        ((ServerFunctionManagerAccessor) server.getFunctions()).terrarasa$setPostReload(true);
    }

    /**
     * Returns a connected player to the fresh-run baseline. Must run while the player's current level is
     * still live (ender pearls and vehicles are discarded through it).
     */
    public static void resetPlayer(MinecraftServer server, ServerPlayer player, GameType gameType) {
        player.closeContainer();
        player.stopRiding();
        player.ejectPassengers();
        for (ThrownEnderpearl pearl : new ArrayList<>(player.getEnderPearls())) {
            pearl.discard();
        }
        player.getEnderPearls().clear();

        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        player.containerMenu.broadcastChanges();
        player.removeAllEffects();
        for (AttributeInstance attribute : player.getAttributes().getSyncableAttributes()) {
            attribute.removeModifiers();
        }
        player.setExperienceLevels(0);
        player.setExperiencePoints(0);
        player.totalExperience = 0;
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0.0F);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.setRemainingFireTicks(0);
        player.setTicksFrozen(0);
        player.setAirSupply(player.getMaxAirSupply());
        player.resetFallDistance();
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        for (String tag : List.copyOf(dev.terrarasa.MinecraftCompat.entityTags(player))) {
            player.removeTag(tag);
        }
        player.setRespawnPosition(null, false);
        player.setLastDeathLocation(java.util.Optional.empty());
        // A fresh world has never shown its credits: the first exit-portal jump of every run rolls them.
        player.seenCredits = false;
        player.setGameMode(gameType);

        player.resetRecipes(server.getRecipeManager().getRecipes());
        resetAdvancements(server, player);
        resetStats(server.getPlayerList(), player);
        server.getScoreboard().resetAllPlayerScores(player);
    }

    private static void resetAdvancements(MinecraftServer server, ServerPlayer player) {
        var advancements = player.getAdvancements();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            AdvancementProgress progress = advancements.getOrStartProgress(holder);
            for (String criterion : List.copyOf(toList(progress.getCompletedCriteria()))) {
                advancements.revoke(holder, criterion);
            }
        }
    }

    private static void resetStats(PlayerList playerList, ServerPlayer player) {
        ServerStatsCounter stats = playerList.getPlayerStats(player);
        for (Stat<?> stat : List.copyOf(((StatsCounterAccessor) stats).terrarasa$stats().keySet())) {
            stats.setValue(player, stat, 0);
        }
        stats.sendStats(player);
    }

    private static <T> List<T> toList(Iterable<T> iterable) {
        List<T> list = new ArrayList<>();
        iterable.forEach(list::add);
        return list;
    }
}
