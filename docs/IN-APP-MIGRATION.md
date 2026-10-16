# In-app migration status — 16 October 2026

This branch replaces new work-profile creation with an original first-party Activity hosting prototype. It does **not** provide general third-party Android app virtualization.

## Implemented

- `NestApplication.engine` injects `VirtualContainerEngine`; new clone operations never fall back to the profile controller.
- `CloneEngine.status()` and creation stages are engine neutral. The ViewModel no longer requires a profile bridge or prohibits duplicate packages.
- APK/signature/hash checks, actual guest Activity execution, two lazy subprocess slots, per-instance preferences/files/SQLite/cache, startup acknowledgement, stop, deletion, update and crash retry.
- Room migration 1→2 and Rust metadata schema 3 preserve old records and support unique virtual users/UUIDs and multiple numbered copies.
- Compose picker/progress/dashboard/instance actions connect to the runtime. New setup/provisioning controls and the provisioning Activity manifest entry are removed.
- Legacy rows and existing profile data are retained. Legacy recovery is separate and does not import old app data.

## Proof and remaining gates

The copied probe APK's own Activity and lifecycle run in a host subprocess. Separate persistent values in two instances and preservation of the original are checked by instrumentation. See the [compatibility matrix and evidence](VIRTUAL-COMPATIBILITY.md) for each actually executed device/API result.

No requested external engine passed the completed gates; see the [source/provenance audit](VIRTUAL-RUNTIME-AUDIT.md). The permitted smallest-prototype fallback was therefore implemented. It is not a change to an availability boolean or a shortcut to the original package.

Arbitrary third-party Activities, Services, Receivers, Providers, Binder IPC, native/split guest libraries, guest hardware bridges, accounts and GMS remain unsupported. Android's public Instrumentation Activity API has missing system linkages; those cannot be replaced with truthful success messages. The [architecture](VIRTUAL-RUNTIME.md) documents the exact APIs and limitations.

A full release/merge still requires a proven legally reusable general runtime, the physical ARM64/device matrix, two permitted ordinary third-party app executions, and real hardware/permission/background-service tests. Emulator results and CI configuration do not satisfy those missing gates. The implementation remains on its dedicated feature branch.
