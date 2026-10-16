# Prototype compatibility and evidence — 16 October 2026

This document describes executed tests of the original Activity hosting fallback. It does not certify a general Android cloning engine. Updated results and APK hashes are recorded in `artifacts/virtual-runtime/` and `build/virtual-evidence/`.

## Platform matrix

| Platform | CPU / execution | Actual probe Activity, two data sets | Force-stop / reboot / host APK replacement | Genuine guest upgrade | Status |
| --- | --- | --- | --- | --- | --- |
| Android 13 / API 33 | Google APIs x86_64 emulator | Passed | Passed | v3→v4 | 21 tests passed |
| Android 14 / API 34 | Google APIs x86_64 emulator | Passed | Passed | v2→v3 | 21 tests passed |
| Android 15 / API 35 | Google APIs x86_64 emulator | Passed | Passed | v1→v2 | 21 tests passed |
| Android 16 / API 36 | Google APIs x86_64 emulator | Passed | Passed | v2→v3 | 21 tests passed |
| Physical ARM64 | No ADB device connected at last check | Not executed | Not executed | Not executed | Physical tests blocked |

All four final runs use the same debug host APK hash below, normal Android shell UID 2000, and the corrected proof cleanup. Each has one comprehensive runtime test, 16 regressions, three recovery tests and one newer-version guest update: **84 passing instrumentation tests** in total. Existing emulator probe data was preserved, so the baseline versions differ. The probe source is unchanged between these version-code fixtures. CI starts fresh with v1→v2.

## Capability matrix

| Capability | Prototype status and evidence |
| --- | --- |
| Real first-party APK Activity | Executed from unchanged copied APK; its own title, package/data identity, UI input and create/start/resume/pause/stop/destroy lifecycle are checked |
| Independent instances | Two UUIDs and virtual users for the same package; different preferences, file bytes and SQLite values |
| Original preservation | Standalone probe is initialized and stopped before the baseline; its original XML is byte-compared after cloning, use and deletion |
| Selective deletion | Deletes only one UUID directory; two other instances and original data remain intact; symlinks are not followed |
| Guest crash | Intentional one-shot probe startup failure returned failure and recovered on explicit retry on APIs 33–36 |
| Same-signature guest update | Same-version APK replacement and genuine newer-version updates of both instances passed without data loss on APIs 33–36 |
| Full Compose path | Add App → probe selection → Clone → actual guest UI → dashboard icon → same guest UUID passed on APIs 33–36 |
| Signed/minified release | Final API 36 external UI test passed for two saved guest values, consecutive ACTION_VIEW shortcut intents, force-stop recovery and reboot recovery. The result contains the exact release APK hash; fresh recovery screenshots are included. Evidence: `artifacts/virtual-runtime/evidence/release-api36/result.json` |
| Room migration | Actual v1 database and pending request survive 1→2 migration; virtual rows coexist with the legacy row |
| Metadata restore | UUID matches preserve runtime identity; presentation restore cannot change storage identity or collapse two copies |
| Guest permissions | Unsupported camera/microphone/Bluetooth/location/notification/network permissions report denied independently of host grants; no positive guest hardware bridge is claimed |
| Manager Bluetooth / file sharing | Existing instrumentation passed on APIs 33–36; emulator observations do not establish a physical radio connection |
| Native guest libraries, split APKs | Detected and rejected |
| Services, Receivers, Providers, Binder, jobs, foreground services | Unsupported; no component test is claimed to pass |
| Camera/video, microphone, audio, BLE/Classic, location, Wi-Fi/network, notifications/actions, file picker/content URIs, clipboard/share, media | No guest bridges; no guest hardware acceptance result |
| General third-party apps | Rejected by package/signature/protocol gate; two ordinary third-party executions not performed |
| GMS/accounts/Integrity/attestation | Unsupported; no security/anti-tamper bypass integrated |
| 16 KiB native alignment | All six packaged Rust/AndroidX ARM64 and x86_64 libraries pass ELF segment and APK ZIP alignment checks in debug and signed release; no 16 KiB physical-device or guest-native execution is implied |

## Evidence and commands

The tested host is version code 4, `0.3.0-prototype`, application ID `org.parallelnest.app`.

| APK | SHA-256 |
| --- | --- |
| Final ordinary debug host | `81980e34752905cb49f00f468bf64dd3fd04f6fc1ee0209293fbd3da2e62c54e` |
| Signed/minified release host | `fb04b395453b69bdb997c5aeda86e05ec2e505d65230af6183172f36c2b3517c` |

The release retains the existing certificate documented in [SIGNING.md](SIGNING.md). The matching first-party signed probe and the separate signed instrumentation kit are in `artifacts/virtual-runtime/`. The release host requires the matching signed probe; the ordinary debug host requires the ordinary debug probe. Do not uninstall an existing installation to change certificates.

`python scripts/test-virtual-runtime.py --serial <test-device> --api <33..36> --reboot --host-update` records actual configuration, host APK hash, test output, screenshots, logs and process memory/CPU snapshots. `--guest-update-apk <newer-signed-probe.apk>` adds a genuine guest version upgrade. These tests operate on the disposable first-party fixture and preserve unrelated application data.

Rust: 19 passing tests with the pinned Rust 1.99.0 compiler. Windows used its already installed GNU toolchain and bundled LLD/self-contained libraries because the MSVC linker was unavailable. Both Android ABIs were rebuilt with NDK 27.1.12297006. Kotlin JVM: 10 passing tests. Debug/release builds and requested app/core/engine/UI/probe lint checks passed. The final signed/minified APK passed the separate API 36 UI test. GitHub Actions was updated; remote CI has not been triggered by a push.

At the final API 36 signed-release checkpoint, after closing both guest Activities, the retained manager/guest0/guest1 processes reported PSS of 22,451 / 19,871 / 19,738 KiB (62,060 KiB combined). The two post-reboot guest startup acknowledgements took 2,185 ms and 616 ms. ActivityManager separately reported 1,362 ms for the cold manager Activity; that value does not include asynchronous guest loading. These are emulator snapshots, not physical-device performance guarantees. Raw memory, launch and runtime logs are in `evidence/release-api36/`.

Previous development failures are retained locally: a fabricated Activity token, nested DecorView accessibility cycle, and an original-fixture lifecycle baseline race. The fixes respectively use the real host Window token, attach guest content under one Window, and quiesce the standalone probe before byte comparisons. Tests were corrected or strengthened; no required test was disabled to produce a pass.

The consecutive-shortcut test additionally caught an Android 14 / API 34 request-order race: the two asynchronous instance lookups could complete in reverse order. `NestViewModel.launchShortcut` now queues the lookup and launch together. MainActivity uses `singleTop` to receive subsequent shortcut extras. The failing API 34 log is retained under `artifacts/virtual-runtime/evidence/development-failures/`; the unchanged regression passed after the fix.

On API 35, the standalone proof test's immediate directory deletion raced the guest's final lifecycle preference write. Its cleanup now uses `GuestRuntimeManager.stop` and awaits the same destruction acknowledgement as production deletion before removing its own temporary directory. The UI/process assertions remain; no deletion error was suppressed. The failure log is retained alongside the shortcut failure.

No merge is performed while the general-runtime, third-party and physical-device acceptance gates remain unmet.
