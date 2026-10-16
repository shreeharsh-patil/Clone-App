# Clone Apps — in-app Activity prototype

Clone Apps is an Android 13–16 Kotlin/Compose application with a Rust metadata validator. This feature branch implements a limited, original guest Activity runtime. It executes the first-party isolation probe APK inside host subprocesses with separate persistent data per instance.

**This is not a complete third-party app cloning engine.** General APKs, native/split guests, Services, Providers and hardware bridges remain unsupported. The app shows that limitation and rejects unsupported apps before registration. The five requested engines were reviewed; none passed both provenance and technical gates. See the [candidate audit](docs/VIRTUAL-RUNTIME-AUDIT.md).

New clones require no work profile, root or second Android user. The original app is never used as a substitute for a virtual launch. Each guest shares the host Android UID; directory separation is not OS sandbox isolation.

## Try the prototype

Install the host and the separate first-party probe with matching signing identities. For debug builds:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r isolation-probe/build/outputs/apk/debug/isolation-probe-debug.apk
```

Open **Clone Apps → Add App → Clone Apps Probe → Clone (1)**. The runtime copies and verifies the unchanged probe APK, loads its actual Activity and registers only after successful guest startup. Return to the dashboard and repeat to create another numbered instance. Save different values in each guest; preferences, files and SQLite belong to their UUID directories. Tap a dashboard icon to launch that instance. Long press for rename, icon style and deletion; instance details include stop, update and shortcuts.

Only the first-party probe is compatible with the current runtime. It must carry the guest protocol marker and the same certificate as the host. Other installed launcher apps appear with their unsupported reason. Installing a probe with an unrelated signing certificate does not bypass the gate.

Locally signed prototype artifacts are in `artifacts/virtual-runtime/` when the existing private signing material is available. `Clone-Apps-v0.3.0-prototype.apk` retains application ID `org.parallelnest.app` and the release signing identity. Install `isolation-probe-v1-signed.apk` with that release host. Debug and release certificates differ: do not uninstall an existing app or replace its signing identity to force an update.

## Implemented and tested scope

- Actual copied APK Activity instantiation and lifecycle under two lazy subprocess slots.
- Multiple UUIDs and virtual user IDs for one package; separate files, preferences, SQLite, caches and immutable APK revisions.
- Guest startup acknowledgement, stop, selective deletion, re-launch and same-signature guest updates that retain data.
- Compose discovery/search/selection, creation progress, dashboard launch, renaming, numbered instances and instance-specific shortcuts.
- Explicit Room 1→2 migration preserving historical rows and pending requests. Rust registry schema 3 with schemas 1/2 upgrade support and multi-instance regression tests.
- Traversal checks, symlink-safe deletion, package/signature/hash validation and explicit unsupported permission decisions.
- Metadata backup/restore; original Bluetooth manager/file-sharing UI, themes, licenses and diagnostics.

See [architecture and exact Android API limits](docs/VIRTUAL-RUNTIME.md), [compatibility and test evidence](docs/VIRTUAL-COMPATIBILITY.md) and [release signing](docs/SIGNING.md). Physical ARM64 results must be recorded separately from emulator evidence. Nothing here claims Play Services, login, Play Integrity or hardware-backed attestation support.

## Existing installations

Historical work-profile data and records are preserved. **Settings → Legacy work-profile recovery** appears when those records exist and can launch an existing legacy copy. New clone operations always select the virtual engine. The setup/provisioning route is removed; existing profiles are never automatically deleted or imported. Do not remove a profile to try this prototype. Historical behavior is documented in [v0.2.0 notes](docs/LEGACY-WORK-PROFILE.md).

## Build

JDK 17, Android SDK/build tools 36, NDK 27.1.12297006 and the pinned Rust toolchain are required. Set `ANDROID_HOME`.

```sh
cargo test --manifest-path rust-core/Cargo.toml --locked
bash scripts/build-rust.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :core:lintDebug :clone-engine:lintDebug :ui:lintDebug :isolation-probe:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :isolation-probe:assembleDebug
python3 scripts/verify-apk.py app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `scripts/build-rust.ps1` and `gradlew.bat`. The runtime has no imported native hooks; Rust libraries are rebuilt for ARM64 and x86_64 with 16 KiB ELF alignment. APK alignment is verified separately. Dependencies remain locked.

## Repeatable device tests

Use a disposable test device with USB debugging. The harness installs test APKs and writes a first-party probe sentinel; it never requests root, clears app data or provisions a profile.

```sh
python3 scripts/test-virtual-runtime.py --serial emulator-5556 --api 36 --reboot --host-update
```

The harness checks actual guest UI, two instances, preferences/files/SQLite independence, original-app preservation, lifecycle, update, deletion, the full Compose flow, migration, traversal, existing security/Bluetooth tests, force-stop, reboot and host APK replacement. It records the device API/ABI/fingerprint, APK hash, screenshots and logs under `build/virtual-evidence/api-N/`.

To test a genuinely newer guest, build the probe with `-PprobeVersionCode=2`, retain that APK separately, and pass `--guest-update-apk path/to/probe-v2.apk`. Start the harness with the v1 probe at its usual build path. CI performs these steps for APIs 33–36; configuring a matrix does not itself establish a passing run.

For a physical test phone that already uses the production signing identity, use the matching signed instrumentation kit instead of the ordinary debug APKs:

```sh
python3 scripts/test-virtual-runtime.py --serial <phone-serial> --api <phone-api> --reboot --host-update --host-apk artifacts/virtual-runtime/Clone-Apps-device-test.apk --test-apk artifacts/virtual-runtime/Clone-Apps-device-tests.apk --probe-apk artifacts/virtual-runtime/isolation-probe-v1-signed.apk --guest-update-apk artifacts/virtual-runtime/isolation-probe-v2-signed.apk
```

The kit's host is debuggable for instrumentation; use the minified prototype APK for normal use. Check the phone's installed probe version before selecting a baseline: the harness rejects a downgrade or signing mismatch rather than uninstalling or clearing data. Physical evidence is saved under `build/virtual-evidence/api-N-physical/`.

No merge is authorized by a green build alone. General third-party hosting, two permitted third-party app tests and physical ARM64 feature coverage remain acceptance gates.

## License

Original code is Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Unresolved third-party virtualization code and binaries are not bundled. Dependencies have their own notices in the application.
