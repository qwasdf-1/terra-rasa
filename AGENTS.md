# Terra Rasa Agent Guide

Terra Rasa is a server-side Fabric library for preparing seeded worlds and
replacing loaded levels behind vanilla's canonical dimension keys. It is
consumed by host mods such as Soul Link. The host owns gameplay, player
movement, run state, and the decision to reset session state.

## Read first

- `README.md` and `docs/README.md` for setup and navigation.
- `docs/project-reference.md`, `docs/current-state.md`, and
  `docs/architecture.md` for scope, implementation, and lifecycle ownership.
- `docs/host-integration.md` before changing how a host prepares or activates a
  run.
- `docs/reference.md` for utilities, persistence, compatibility, and recovery.
- `docs/publishing.md`, `gradle/minecraft-targets.json`, and the corresponding
  `src/versions/<target>/` overlay for release and version-specific work.

## Project rules

- Keep the library boundary narrow: it prepares and activates worlds, scopes
  seeds, and recovers interrupted storage promotion. Do not move host policy,
  player routing, or gameplay rules into the library.
- Activation must preserve the durable manifest/journal and rollback/recovery
  guarantees. Treat directory promotion, level retirement, and startup recovery
  as one lifecycle contract.
- Keep shared lifecycle policy in common source. Isolate unavoidable Minecraft
  API differences in legacy/modern source overlays and preserve equivalent
  behavior across the configured targets.
- The exact target matrix is `gradle/minecraft-targets.json`: Minecraft
  1.21.11 and 26.1, 26.1.1, 26.1.2, 26.2, and 26.3. Java is pinned per target;
  use the matching build artifact in each host.
- Keep optional integrations optional. In particular, Chunky must remain a
  runtime-detected integration rather than a mandatory dependency.
- Update `reference.md`, `host-integration.md`, or `publishing.md` when public
  contracts, persistence, compatibility, or release behavior changes.

## Documentation and operator records

- `AGENTS.md` is canonical; root `CLAUDE.md` is a compatibility symlink.
- Keep implementation claims tied to source, integration tests, target
  configuration, or release artifacts. Host-owned behavior belongs in the host
  documentation.
- `docs/issue-ledger.md`, `docs/open-work.md`, `docs/TODOS.md`, and
  `docs/work-register.md` are local operator records, excluded per checkout.
  Record evidence separately from hypotheses and do not close issues without
  operator confirmation.

## Verification

Run focused tests for touched contracts. Shared compatibility changes must be
checked across both the 1.21.11 legacy overlay and 26.x modern overlay; lifecycle
or storage changes need the relevant integration coverage and startup-recovery
checks.
