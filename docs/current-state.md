# Current state

Terra Rasa prepares world data away from the live canonical levels, closes and
flushes it, then promotes it during a server-thread activation. A durable
manifest marks a run ready; an activation journal supports crash recovery.

## Implemented

- Configurable resettable, persistent, and external dimension policies.
- Per-run seed scoping and pinned seeds for persistent/external worlds.
- Spawn search, spawn preloading, optional offline preparation pregeneration,
  paced live pregeneration, progress persistence, and restart resumption.
- Bounded FIFO pool of up to three closed prepared runs, with deterministic
  ordering and restart adoption; the default capacity remains one.
- Canonical activation with directory promotion, rollback, startup recovery,
  and listener hooks for mods that retain level references.
- Optional Chunky task cancellation/rebinding integration without a mandatory
  Chunky dependency.
- Separate builds for 1.21.11 and 26.1 through 26.3.

## Boundaries

Terra Rasa does not own gameplay rules, command UX, player destinations, or a
host's choice to reset session state. Those belong to the consuming mod. It is a
dedicated-server library; vanilla clients do not install it separately when it
is bundled into a host mod. Integrated single-player and LAN hosting are
outside the current 0.2 release scope; they need client-capable Fabric metadata
and testing against an integrated server, beyond the dedicated-server checks.
