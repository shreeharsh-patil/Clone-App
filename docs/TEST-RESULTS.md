# Executed test results

Evaluation date: 13 October 2026. Clone Apps v0.2.0-alpha targets Android 13–16 (API 33–36). The user requested emulators; no physical phone, headset or BLE accessory was available. Builds/API observations are separate from physical hardware acceptance.

## Current local checks

| Check | Recorded result |
| --- | --- |
| Rust host tests | 12 passed, 0 failed; strict metadata schema 2, duplicate identifiers/environments, package/path/name bounds, profile/engine/clone-number validation. Rust 1.99.0 GNU host. |
| Kotlin unit tests | 6 passed, 0 failed: three installed-app catalog filtering/selection checks, two metadata restore identity/collision checks and one unavailable-engine policy check. |
| Android 16 debug instrumentation | 7 passed, 0 failed: Compose home, native metadata round-trip/duplicate rejection, forged bridge command/reply rejection, Keystore encryption, Bluetooth runtime permission/discovery cancellation, selected-file temporary URI read-grant boundaries. |
| Compile | Debug manager, minified/resource-shrunk release, instrumentation APK and separate first-party probe built successfully. |
| Lint | App debug/release and core/clone-engine/ui/probe debug lint passed with zero errors. Dependency-version, API deprecation and noncritical style warnings remain visible in reports. |
| Native alignment | ARM64 451,544 bytes and x86_64 497,088 bytes; both native ELF segments and APK entries retain 16 KiB alignment, including the signed release APK. No physical 16 KiB device was tested. |
| Release signing | Persistent RSA-3072 certificate; APK Signature Scheme v3 verified by apksigner for minimum API 33. Public certificate and private storage/update instructions are in SIGNING.md. |
| Signed release installation | Installed and launched on a separate clean Android 16 x86_64 emulator, emulator-5562. Further provisioning/UI observations are recorded below when completed. |
| Dependency notices/advisories | Notices regenerated for 136 Maven and 37 Cargo coordinates. OSV queried 173 dependency versions at 2026-10-13 13:17:58 UTC; zero returned advisory records. This is not a security certification. |

New branding, adaptive launcher icon and actual compact-grid screens were captured from Android; docs/branding/android-app-icon.png and docs/screenshots/home-v2.png are real screenshots. Room imports the earlier metadata into the updated debug installation while retaining existing instance IDs/names. The application ID remains org.parallelnest.app for upgrades.

## Current cloud release gate

The workflow builds both native ABIs, Kotlin/Rust tests, all relevant lint checks, debug/release/probe APKs, and instrumentation on APIs 33, 34, 35 and 36. API 36 additionally exercises actual profile provisioning, Android install consent, independent fixture values, suspension/resume, force-stop/reboot/update recovery and work-only deletion. Final v0.2.0-alpha run evidence is recorded here after completion; older runs below do not certify the new Room/UI implementation.

## Historical v0.1.0-alpha lifecycle evidence

[Run 38383222620](https://github.com/shreeharsh-patil/Clone-App/actions/runs/38383222620), commit abff6fe, passed all API 33/34/35/36 jobs. API 36 passed the complete provision/install/separate-private-values/freeze/resume/reboot/update/delete flow. [Run 38392006605](https://github.com/shreeharsh-patil/Clone-App/actions/runs/38392006605), commit 3ed8f9, also passed all jobs after manager Bluetooth changes. These runs precede the Room/module/UI migration.

The original local Android 16 fixture had personal UID 10216 and work UID 1010216, profile serial 10. The personal value PERSONAL_SENTINEL_2026 and work value WORK_SENTINEL_2026 were independently stored; each private XML lacked the other's value. Only the first-party debuggable fixture was inspected with supported run-as, never third-party data. Work deletion left the original private XML unchanged. These identifiers describe that execution, not every installation.

That execution exposed installer background-activity restrictions, profile-local sideload policy, cross-profile filter direction, asynchronous suspension updates and Android 16's automatic return from source-install settings. The engine uses authenticated explicit broadcast callbacks, normal foreground consent and actual policy/package state checks. The application does not silently approve permission or installation prompts.

Work Bluetooth CONNECT consent was granted in the work fixture while denied in the personal fixture. The emulator returned Bluetooth API accessible/on/0 paired devices. Manager classic discovery/cancellation and Bluetooth sharesheet availability were observed. No accessory connected and no file transfer completion was claimed.

## Hardware compatibility limits

The backend runs genuine packages in Android's managed profile with their normal UIDs, permissions and system APIs. Read-only diagnostics report actual permission/policy/service snapshots. No capability is marked Supported and tested solely because Android exposes an API. Hardware details appear in diagnostics/individual clone information.

The shipped probe screen exercises private-value isolation and Bluetooth CONNECT API access. Additional camera/location/microphone/network/job/BLE helpers in source are not a completed hardware acceptance suite and are not represented as passing tests. Emulator responses or synthetic sensors/cameras cannot establish physical hardware compatibility.

Still required for stable release:

- Physical ARM64/OEM phones, real rear/front camera capture/video calls, GPS/approximate/precise/background location, microphone/speaker/headset routing and privacy-toggle/concurrency behavior.
- Classic pairing, Bluetooth file-transfer completion, headset calls/microphone/controllers, BLE scan/GATT connection/reconnect with a real accessory.
- Representative third-party sign-in/GMS/FCM/push, notification actions/sounds, foreground/background/Doze jobs, Wi-Fi/cellular/VPN/DNS/HTTPS/WebSocket transitions.
- SAF/photo picker/MediaStore/cross-profile URI grants, NFC/sensors/accounts/Credential Manager/autofill/biometric/default-role workflows as applicable to each target app.
- Full profile provisioning/isolation on Android 13–15; their baseline tests alone do not establish that full flow.
- Representative release-build performance and battery measurements on phones.

Work profiles support one extra copy per package. Virtual containers/unlimited copies and arbitrary app-data backup are unsupported. Manager backups contain metadata/preferences only. Releases remain alpha prereleases until the necessary device evidence exists.
