# Executed test results

Evaluation date: 13 October 2026. This document distinguishes builds/API tests from real work-app lifecycle evidence. No physical phone or OEM device was connected; the user requested emulator testing.

## Verified builds and automated checks

| Check | Result / evidence |
| --- | --- |
| Rust host unit tests | 10 passed, 0 failed. Bounded input, strict schema/fields, malformed packages, duplicate identifiers/environment pairs, invalid profiles, serialization. Stable Rust 1.99.0 GNU host. |
| Kotlin unit tests | 1 passed, 0 failed (`EnginePolicyTest`). Unverified virtual engine is unavailable and profile copy limits are disclosed. |
| Local Android instrumentation, API 36 | Final baseline: **5 passed, 0 failed** (`OK (5 tests)`, 6.542 s in the last direct instrumentation invocation). JNI round-trip/duplicate rejection, encrypted Keystore/PIN retry-delay test, forged command/reply rejection and Compose interface. Earlier four-test XML results are under app/build/outputs/androidTest-results/connected/debug/. |
| Cloud Android instrumentation, APIs 33, 34, 35, 36 | All four baseline jobs passed at commit `77bd7e5`. [Actions run 38370561909](https://github.com/shreeharsh-patil/Clone-App/actions/runs/38370561909). These were the four baseline API/security/UI tests, not full profile isolation tests. |
| Debug and release compilation | Both APK variants built locally and in the successful cloud baseline build. Release is unsigned without production signing secrets. |
| Android lint | Debug/release: 0 errors. Version-update, explicit synchronous security preference writes and related noncritical warnings are retained in reports. Target SDK 36 is deliberate, not an obsolete-target workaround. |
| Native ABIs | ARM64 and x86_64 libraries are included. ARM64 native library 451,544 bytes; x86_64 495,496 bytes at this build. |
| 16 KiB compatibility | Both ELF PT_LOAD segments and uncompressed APK entries passed scripts/verify-apk.py alignment checks for debug and release APKs. No physical 16 KiB-page device execution claim. |
| Package installation | Debug APK and first-party probe APK installed on Android 16 emulator. Manager launched, Rust library loaded, actual Compose screenshots captured. |

## Local provisioning evidence and fixes

Dedicated `ParallelNest_API36` Android 16 Google APIs x86_64 emulator, WHPX acceleration, original 1080×2400 screen overridden to 432×960 / 168 dpi for UI verification. Existing unrelated emulators were not used for the app test.

1. Fresh manager/probe install and real onboarding screenshot.
2. Manager consent checkbox and Android **Accept & continue** work-profile screen.
3. Android created user 10 and assigned `org.parallelnest.app/.engine.NestAdminReceiver` as profile owner. Profile named ParallelNest; personal user 0 preserved.
4. Initial role-holder updater failed on the Google image. Fixed with documented `EXTRA_PROVISIONING_ALLOW_OFFLINE`, retaining OS consent.
5. Reversed cross-profile filter direction detected and corrected to `FLAG_MANAGED_CAN_ACCESS_PARENT` (parent-origin commands may be resolved by managed-profile activities).
6. Android's default `no_install_unknown_sources` profile restriction blocked installation. The DPC now clears that restriction in its own profile for user-selected installation. Debug-only profile debugging is enabled for first-party fixture tests; production builds retain the default debugging restriction.
7. Personal probe persisted `PERSONAL_SENTINEL_2026`. No work-value separation was claimed at this point; the full execution test below must pass first.
8. The shared SDK emulator runtime was moved/partially changed during the session, interrupting local execution. SDK-manager retries installed an isolated runtime under the workspace, and local execution resumed on emulator-5560. No root command was used.

## Actual local isolation and lifecycle results

| Scenario | Executed result |
| --- | --- |
| Install a genuine second probe | Profile-local PackageInstaller installed `org.parallelnest.probe` in user 10 after Android's source-install permission and install confirmation. |
| Isolated launching | ParallelNest's work engine launched the work app. Personal UID **10216**, work UID **1010216**, work serial **10**. |
| Independent persistence | Personal private preferences contained **PERSONAL_SENTINEL_2026**; work private preferences contained **WORK_SENTINEL_2026**. Each lacked the other's value. The first-party debug fixture only was inspected via supported `run-as`; no third-party data was inspected. |
| Freeze / resume | Android package suspension applied in user 10; manager displayed “Work app frozen”. Launch resumed it; the work value remained. Android's asynchronous policy application is now awaited using package broadcasts before reporting success. |
| Manager force-stop / emulator reboot | scripts/verify-device-recovery.py returned **RECOVERY_PASS** after checking both private values before and after force-stop/reboot. |
| Deletion confirmation and isolation | Both manager data-loss dialog and Android uninstall dialog were exercised. Work package disappeared from user 10; personal package remained installed in user 0 and its private XML retained **PERSONAL_SENTINEL_2026**. |
| Fresh reinstall and completion | Reinstalled through the final callback implementation. Manager immediately registered the real installed copy and reported “App installed in a separate work profile”, without manual synchronization. |
| Final authenticated deletion test | RecoveryDeletionTest passed directly on the local emulator: **OK (1 test)**, 5.724 s. It verified a successful engine result, no available work probe afterward, and byte-for-byte unchanged original XML. |

The device test exposed Android 16 blocking a terminal activity PendingIntent after installation. Terminal callbacks now use a private broadcast receiver, while a foreground work Activity hosts consent. Activity result codes are deliberately ignored because Android's confirmation UI may return RESULT_CANCELED after an otherwise successful operation; PackageInstaller's terminal status is authoritative. These fixes were rebuilt and exercised with a fresh installation.

## Full lifecycle execution gate

The current API 36 CI test goes through system provisioning consent, installs the probe using the production work-engine implementation, launches it, writes **WORK_SENTINEL_2026**, and asserts the personal/work XML preferences contain only their own sentinel. It then suspends/resumes the work app, checks both values after manager force-stop and emulator reboot, reinstalls the manager APK to exercise update preservation, and deletes only the work copy while verifying the original preferences are unchanged.

Local isolation/recovery/deletion have been executed as recorded above. Cloud full provisioning/isolation/freeze/resume passed in [run 38376338733](https://github.com/shreeharsh-patil/Clone-App/actions/runs/38376338733); its job then failed copying evidence because the destination folder did not exist. That packaging error was fixed; cloud post-test reboot/update/deletion remains pending until the corrected run completes. Test source: WorkProfileFlowTest, RecoveryDeletionTest and scripts/verify-device-recovery.py. Real screenshots/logs are uploaded as instrumentation-results-api-36. A successful baseline UI test is not substituted for this gate.

## Not verified / remaining stable-release gates

- Physical ARM64 phones, manufacturer Android variants and physical 16 KiB devices.
- Full profile provisioning/isolation on Android 13–15 (only baseline API/security/UI tests were run on those versions).
- Three or more virtual copies: unsupported, engine disabled and not bundled.
- Popular third-party sign-in, notification delivery, GMS dependencies, split/dynamic delivery edge cases and OEM system apps.
- Biometric hardware prompt/notification entry-point behavior on physical devices; PIN/Keystore behavior is instrumented.
- App-data backup/restore: unsupported. Manager metadata/settings backup only.
- Battery drain, scrolling frame performance and representative physical-device startup/memory benchmarks. No invented performance improvement is claimed.
- Removal/quiet-mode behavior has defensive handling in code, but every OEM lifecycle path is not claimed tested.
- Production signing and a persistent update identity; release credentials have not been provided.

Until all necessary device evidence is recorded, releases are **alpha prereleases**, not production/stable certification.
