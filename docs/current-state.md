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
host's choice to reset session state. Those belong to the consuming mod. Version
0.3.0 supports dedicated and integrated servers. For single-player or LAN, the
host must have the mod installed in the client profile. Remote LAN guests do not
need Terra Rasa when it is embedded in a host mod. A rendered 26.2 integrated
single-player smoke test through Soul Link passed through Terra Rasa activation.
Full lifecycle checks on the other targets and vanilla LAN guest login remain
pending; current automated test evidence covers dedicated servers.
