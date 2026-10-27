# Architecture map

Terra Rasa has two cooperating lifecycle services:

1. `WorldPreparationService` creates isolated stage identities and storage,
   finds the vanilla spawn, loads the configured spawn area, optionally
   pregenerates, saves and closes the stages, and publishes a ready manifest.
2. `CanonicalActivationService` validates the manifest and dimension policy,
   retires old levels, journals directory moves, promotes staged data, creates
   ordinary `ServerLevel` objects at canonical keys, and validates the new
   spawn. Failures roll back or are reconciled by `StartupRecovery`.

Hosts drive preparation from server ticks and call activation on the server
thread after moving players out of dimensions that will be replaced. The host
owns player flow and run state; Terra Rasa owns world preparation, storage
promotion, seed scoping, and recovery.

The legacy and modern source overlays isolate the obfuscated 1.21.11 API from
the 26.x API. Shared lifecycle policy should stay in common source. Use
`host-integration.md` for the host contract and `reference.md` for utility and
storage details.
