# Host integration

Terra Rasa supplies the world lifecycle. The host mod decides which dimensions belong to a session, when a new session starts, where players wait, and which player or game state to clear.

## 1. Define the dimension policy

Create one `DimensionPolicy` for the host and register it before Minecraft opens the world's levels:

```java
import java.util.List;
import java.util.Set;

import dev.terrarasa.DimensionPolicy;
import dev.terrarasa.PinnedSeed;
import net.minecraft.resources.Identifier;

DimensionPolicy policy = new DimensionPolicy(
    List.of(
        DimensionPolicy.OVERWORLD,
        Identifier.withDefaultNamespace("the_nether"),
        Identifier.withDefaultNamespace("the_end"),
        Identifier.fromNamespaceAndPath("expedition", "vault")
    ),
    Set.of(Identifier.fromNamespaceAndPath("expedition", "lobby"))
);

PinnedSeed.setPolicy(policy);
```

The Overworld must be resettable. Every resettable or persistent dimension must have a registered level stem. A dimension cannot be both resettable and persistent. The library classifies all other dimensions as external and leaves them alone. A custom dimension that should be recreated for each session must be explicitly added to `resettable`.

`PinnedSeed` stores the world seed it first sees. Resettable levels follow each prepared run's seed; persistent and external levels keep the stored seed so new chunks remain consistent with their existing terrain.

## 2. Create the services at server start

Use the server's world root to create `WorldPaths`, then create one preparation and activation service for that server:

```java
WorldPaths paths = new WorldPaths(
    server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
);
WorldPreparationService preparation = new WorldPreparationService(
    paths,
    6,                         // chunks around the spawn to preload
    Map.of(),                  // optional per-dimension pregeneration radii, in blocks
    Duration.ofMinutes(15),
    Duration.ofSeconds(60),
    player -> sendToServiceDimension(server, player)
);
preparation.setReadyLimit(3);    // keep up to three closed future runs; default is one
CanonicalActivationService activation = new CanonicalActivationService(
    paths, Duration.ofSeconds(60)
);

preparation.loadFromDisk(server, policy);
```

Call `loadFromDisk` after server levels have opened. Set the ready limit before loading. Terra Rasa already reconciles its activation journal before vanilla opens any dimension. `loadFromDisk` adopts up to that many staged runs only if each is complete and its game version, selected data packs, world-generation settings, and resettable level definitions still match. Adopted runs are ordered FIFO using their durable creation time.

The `sendToServiceDimension` callback is a last-resort evacuation target if a player was manually teleported into a staging dimension. It is not a substitute for moving normal players out of all resettable dimensions before activation.

## 3. Prepare and observe progress

Start a preparation on the server thread:

```java
preparation.begin(server, seed, policy);
```

Call `preparation.tick(server)` at the end of each server tick while your host is active. It prepares one run at a time and creates one staged level at a time, spreading spawn search, chunk loading, generation, and saving across ticks. The service keeps a FIFO queue of up to three closed, durable runs; configure its capacity with `setReadyLimit(...)` (default one), inspect it with `readyRuns()` and `readyCount()`, and activate the oldest with `takeReady()`. A `RunManifest` appears in `readyRuns()` only after every staged level has been saved and closed. `setPregenerationRadii(...)` changes the configuration captured by preparations begun afterward.

If a start is requested while optional pregeneration is running, call `finishSoon()`. It stops the background area work once the prepared spawn is usable; unfinished pregeneration continues in the live dimensions after activation. `constructNow(server)` is optional when the host already has players waiting: it constructs any levels that are still in the creation phase immediately, which can move that non-spreadable work into a planned pause.

At startup, adopt any compatible manifests that fit the configured pool. If preparation fails, `lastFailure()` gives the reason. `fail(server, reason)` cancels an active preparation and removes its staged levels. `discardReady(runId)` and `discardAllReady()` remove queued runs and their staged data. If activation rolls back, `restoreReady(manifest)` puts that run at the front of the queue.

## 4. Activate a ready run

Before activation:

1. Move every player out of every resettable dimension into a loaded persistent service dimension.
2. Do any host-specific state capture or reset while the old level objects are still live.
3. Take the ready manifest with `preparation.takeReady()` and call `activation.activate(server, manifest, policy)` on the server thread, outside level iteration.

Activation checks that the policy matches the manifest, that every resettable level is empty, that persistent levels are loaded, and that the filesystem supports atomic directory moves. It retires the old levels, promotes staged data under the original dimension keys, rebinds the server seed, and constructs new vanilla level objects. The returned `Activation` contains the run id, seed, spawn, and timings.

If activation throws `LifecycleException`, the previous worlds are restored in process and the host may return the manifest with `preparation.restoreReady(manifest)` if it remains valid. A fatal rollback failure halts the server so startup recovery can reconcile the journal rather than opening mixed world data.

## 5. Reset state and return players

`SessionReset` is optional: call it only when the new session should behave like a fresh vanilla world.

```java
SessionReset.resetServerState(server);
for (ServerPlayer player : players) {
    SessionReset.resetPlayer(server, player, GameType.SURVIVAL);
}
```

Run player reset while each player's current level is still open. The host then chooses the new spawn or any custom entry flow. See [Library reference](reference.md#sessionreset) for the full reset list and preserved settings.

## 6. Notify mods that retain level references

Fabric's load and unload events still fire. Mods that cache `ServerLevel` objects should also use Terra Rasa's paired replacement events:

- `CanonicalLevelEvents.BEFORE_RETIRE` runs on the server thread before any old level closes. A listener may cancel activation by throwing `LifecycleException`.
- `CanonicalLevelEvents.REPLACED` provides the replacement levels after a successful swap and after a rollback. Rebind cached references here; listener exceptions are logged because activation has already completed.

Terra Rasa includes an optional Chunky adapter. It cancels active tasks against worlds being replaced and updates the selected world after the swap.

## Example and checks

The compact [test host](../testhost/src/main/java/dev/terrarasa/testhost/Host.java) wires the policy, services, commands, and lifecycle callbacks together. `./gradlew integrationTest` runs that host against a real dedicated server.
