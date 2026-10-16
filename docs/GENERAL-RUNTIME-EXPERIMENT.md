# General app runtime experiment — 16 October 2026

## Status

The opt-in `generalRuntime` build replaces the cooperative probe host with an
adapted, source-built package/activity/user runtime. Installed launcher apps can
be selected without the old probe package allowlist. APK import, actual guest
Activity resumption, and registry persistence are required before a clone is
reported as created. This does **not** establish compatibility with every app.

The experiment installs as `org.parallelnest.app.experimental`, version
`0.4.1-experimental` (7). It can coexist with the published
`org.parallelnest.app` prototype. Production signing, existing production data,
and legacy work profiles are preserved. The existing 0.4.0 experiment remains
the published build; this 0.4.1 candidate is not yet a public release.

## Build from source

Use JDK 17, SDK/build tools 36, NDK 27.1.12297006, CMake 3.22.1, Python 3.12+,
Git, and the repository's Rust toolchain. Set `JAVA_HOME` and `ANDROID_HOME`.
On Windows:

```powershell
.\scripts\build-rust.ps1
python scripts/prepare-general-runtime-proof.py
.\gradlew.bat -PgeneralRuntime=true :app:assembleDebug :app:assembleRelease
```

Preparation fetches the exact revisions in
[`sources.json`](../scripts/runtime-proof/sources.json), verifies existing
checkouts, applies the recorded Dobby Android patch, and generates the modules
under ignored `.research/general-runtime-proof/`. Unexpected local source
changes cause preparation to stop. Existing checkouts are never reset or
cleaned. The generated sources may be inspected before Gradle executes.

The ordinary build without `-PgeneralRuntime=true` continues to select the
original probe engine. Separate dependency lockfiles prevent the experimental
backend from changing the default app's dependency resolution.

### Provenance and adaptations

| Source | Pinned revision | License in source |
| --- | --- | --- |
| TeguFy/blackbox-android | `bf1623b7d45157bbc87be46a29352dd6bbbde9fa` | Apache 2.0 |
| jmpews/Dobby | `5dfc8546954ce3b3198132ab13fddb89ee92cdd7` | Apache 2.0 |
| tiann/FreeReflection | `550e36e464539fe59fcc6e49c8f54bfbf376dbfe` | Apache 2.0 |
| hexhacking/xDL | `1e0b6254165a2ddcbd32f77a371700c69155acf8` | MIT |

Upstream hook archives, shared libraries, JARs and DEX files are excluded from
source copying. Dobby and xDL are built from pinned C/C++/assembly source.
FreeReflection's embedded bootstrap DEX and the runtime's empty JAR are rebuilt
from accompanying Java source. Dobby's recorded changes repair Android ELF
assembly, Linux API mismatches, and preprocessing. License texts, inherited
notices, the LLVM license for Dobby's inherited code, source revisions and
generator/adaptation hashes are included in the experimental APK assets.
Modified copied source files carry change notices.

The generator removes automatic app preinstallation, remote log collection,
external code injection, automatic device identity changes, class hiding,
crash suppression, and optional spoof/auth hook registration. Core guest
component routing remains. Permission queries forward real host permission
decisions; no fabricated Integrity, account or hardware success is added.

Full installed APK sets are copied privately and made read-only. The package
parser receives the complete set, and versioned package metadata preserves
split names, paths and revisions across process death. Adding another clone
does not replace shared APK code. Importing a different code version while
clones exist is rejected; shared-code updates are not implemented.

Each clone gets a separate virtual user and data directory inside the host.
These are runtime identities, **not Android OS users or work profiles**.
Guests share the host Linux UID. This is not an Android security sandbox for
mutually untrusted apps.

## Executed checks

All general-runtime device results below are from an Android 15 / API 35
**x86_64 emulator**, using normal ADB shell UID 2000. No root, clear-data,
original-app uninstall, attestation workaround, or modified third-party APK was
used. These results do not carry over to other APIs or ARM64 phones.

| Check | Observed result |
| --- | --- |
| Fossify Calculator 1.4.0, unchanged official APK | Actual UI executed `42+7=49`; history stored in clone 1; clone 2 initially blank |
| Fossify Notes 1.7.0, unchanged official APK | Two clones saved different note text in separate SQLite databases |
| Host APK update | Both Notes values and Calculator history retained; original clone identities retained |
| Reboot recovery | Data retained; guest reopened after host update. Initial synchronous startup timed out during a busy emulator boot and prompted the startup fix below |
| Complete split APK import | First-party fixture installed as base + separate install-time feature; guest loaded feature-only Activity, Java code and resources |
| Two split fixture clones | Clone 1 counter `1`; new clone 2 initially `0`, then independently incremented to `2` |
| Selective deletion | Removed split clone 2 and its virtual user directory; remaining five clones, database contents, shared APK hashes and clone 1 counter preserved. Original split app still opened at counter `0` |
| Minified release code | Existing split clone restored counter `1`; fresh split clone opened at `0`; Notes clone 1 restored its saved text |
| Cold process recovery | Minified host after force-stop: `COLD`, `TotalTime 918 ms`, `WaitTime 931 ms`; Notes guest reopened |
| Build / metadata | Experimental debug and minified release built; default probe debug built; all 21 Rust registry tests passed |
| Native packaging | All eight packaged ARM64/x86_64 libraries passed ELF and APK ZIP 16 KiB alignment checks; no 16 KiB physical-device result claimed |

Calculator APK SHA-256:
`7c5443c22d6d62c67dfe80df33ea9161ff0fa440f92590bcb45d7008b57613ed`.
Notes APK SHA-256:
`5a56e0e39cc488e1f3b947d3801006d3b7450ec73c67f03195c64c5fd3b6bced`.
Private imported APK hashes matched these official inputs. Sources are the
Fossify Calculator 1.4.0 and Notes 1.7.0 GitHub release assets.

Evidence is local under `artifacts/general-runtime/integrated-*`, including
registry snapshots, fixture SQLite content and imported APK hashes. The
fixture-only capture helper refuses physical devices and registries containing
apps outside its explicit test set. It does not read originals' private data.
The split fixture is generated from repository source and is **not** counted
as one of the two ordinary third-party apps.

The [machine-readable result](evidence/general-runtime-api35.json) records the
device fingerprint, exact APK hashes, source manifest and remaining checks for
version 0.4.0. The [0.4.1 launch-fix result](evidence/general-runtime-launch-api35.json)
records the updated build and its emulator check. The previously published
production-signed candidate was
`artifacts/general-runtime/candidate/Clone-Apps-v0.4.0-experimental.apk`, SHA-256
`48bbcf2cb792e61b68a814d0be6331cc2e91914541401af5dd2999290a35b381`.
The new local production-signed 0.4.1 candidate is
`artifacts/general-runtime/fix-v0.4.1/Clone-Apps-v0.4.1-experimental.apk`,
SHA-256 `2eee47bf67c03634901eae4bc33786d2726b873bb1ed79ea7043a7fa485c8867`.
Both certificates match the existing production identity. Emulator minified
execution used a debug-signed copy to retain that test installation's data;
the 0.4.0 test copy's non-`META-INF` ZIP entry hashes matched its production
candidate exactly. Do not uninstall an existing app to force a signing-key
change.

### Startup fix

The host now initializes its runtime on a background thread so provider startup
cannot block `Application.attach` and the dashboard. Guest processes retain
their required synchronous initialization. Guest hooks are installed only in
guest processes; successful reflection initialization is cached per process.
A slow-start notice leaves the pending connection active and readiness
refreshes automatically. This avoids making a slow connection a permanent
error. Fresh boot verification of this final revision must be recorded
separately from the earlier reboot/data-preservation result.

### Clone launch responsiveness fix (0.4.1)

The clone launch request crosses synchronous runtime Binder services while a
guest process and its Activity are prepared. The previous adapter dispatched
that request on the dashboard main thread and only began its timeout after the
request returned, so a slow launch could trigger an Android not-responding
dialog. The request now runs on `Dispatchers.IO`, and the 25-second deadline
covers both the runtime request and the guest-resume handshake. A timeout
reports which stage did not finish and leaves the dashboard responsive.

On the Android 15 / API 35 x86_64 emulator, the minified 0.4.1 test build was
installed over version 0.4.0 without clearing data. The host cold-started in
499 ms after a force-stop. Opening the saved Fossify Notes clone resumed its
guest Activity and showed its existing note. Android reported no ANR. The
device UI automation bridge returned no accessibility root for the guest
window, so this check used the screen capture and activity/process state. This
does not replace the pending physical ARM64 and fresh-reboot acceptance checks.

## Remaining acceptance gates

- Physical unrooted Android 15 ARM64 phone: no physical ADB device connected at
  the last check. An ARM64 native build is not physical execution evidence.
- General-runtime execution on Android 13, 14 and 16. The separate probe
  prototype's API 33–36 results do not validate this backend.
- Broader native IO/component behavior, sign-in, content URIs, notifications,
  jobs, services, camera, microphone, Bluetooth and background recovery.
- 32-bit-only native guests are rejected by this 64-bit build. Apps requiring
  unavailable Google services, hardware keys or Integrity may remain incompatible.
- Shared-code guest updates and complete native path interception are not
  certified. Some upstream JNI hook helpers are absent; a native library
  loading in Calculator does not establish arbitrary native app compatibility.
- The production-signed candidate needs physical execution and a fresh reboot
  check of the final revision before replacing a public asset. Minified
  execution, cold-process recovery and selective deletion are recorded above.

These limits must remain visible in the app and release notes. Do not present
an imported APK, a launch request, a dashboard row, or an emulator-only result
as proof that all apps work.
