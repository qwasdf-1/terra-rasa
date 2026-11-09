package dev.terrarasa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * Identifies the generation context a run was prepared under: game version, enabled data packs, the
 * structure flag and the encoded definition of every level stem the run replaces. A prepared run whose
 * fingerprint no longer matches is rejected and re-prepared.
 *
 * <p>Stems outside the run are left out on purpose: a mod creating a dimension at runtime adds a stem,
 * and that must not throw away a prepared run that does not contain it.
 */
public final class RegistryFingerprint {
    private RegistryFingerprint() {
    }

    public static String compute(MinecraftServer server, DimensionPolicy policy) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        update(digest, "version=" + MinecraftCompat.gameVersionId());
        update(digest, "structures=" + MinecraftCompat.generateStructures(server));
        server.getPackRepository().getSelectedIds().stream().sorted().forEach(pack -> update(digest, "pack=" + pack));

        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        List<Map.Entry<ResourceKey<LevelStem>, LevelStem>> entries = MinecraftCompat.levelStemEntries(server).stream()
                .filter(entry -> policy.resettable().contains(MinecraftCompat.stemId(entry.getKey())))
                .sorted(Comparator.comparing(entry -> MinecraftCompat.stemId(entry.getKey()).asString()))
                .toList();
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : entries) {
            update(digest, "stem=" + MinecraftCompat.stemId(entry.getKey()));
            String encoded = LevelStem.CODEC.encodeStart(ops, entry.getValue())
                    .mapOrElse(JsonElement::toString, error -> "unencodable:" + error.message());
            update(digest, encoded);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
