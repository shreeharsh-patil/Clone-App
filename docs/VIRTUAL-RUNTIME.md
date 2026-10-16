# In-app Activity runtime prototype

New cloning operations follow:

```text
Compose dashboard → CloneEngine → VirtualContainerEngine
  → GuestPackageManager → GuestRuntimeManager
  → private GuestSlot0Activity / GuestSlot1Activity
  → copied APK's real GuestProbeActivity + GuestContext
  → files/virtual/instances/<UUID>/{apk,files,preferences,databases,cache,code_cache}
```

## Scope and trust

This is the task's first-party proof-of-concept fallback. It is not a general replacement for BlackBox/VirtualApp. Only `org.parallelnest.probe` with the host signing certificate, protocol marker and known Activity entry is accepted. Ordinary apps remain visible with an explicit unsupported reason and cannot become fake dashboard clones. There is no root operation, second Android user, work-profile provisioning, APK package rewriting, original-app launch substitution or attestation bypass.

Processes `:guest0` and `:guest1` are lazy and have **the host's UID**. Each slot hosts one foreground Activity; opening another instance mapped to that slot tears down the preceding Activity. More than two stored instances can exist, but simultaneous execution is limited to two slots. Guest code must be trusted: a Context wrapper and separate directories cannot confine malicious same-UID code using raw paths, native syscalls, direct framework calls or shared static process state. There are no filesystem hooks or hidden API exemptions.

## APK import and updates

Launcher discovery uses intent-scoped PackageManager queries. Import re-reads installed package metadata instead of trusting a stale picker path. Package/version/signing identity and protocol/entry are checked. Split metadata is detected and rejected: split execution is not implemented. Native library ABIs are inspected in the ZIP; any native guest library is rejected. The pure framework probe deliberately carries no Compose native libraries.

Selected base APK bytes are copied unchanged into a private immutable revision named by SHA-256. The output is marked read-only before writing/loading to satisfy modern dynamic-code requirements. Length is bounded to 128 MiB. A second installed-package check detects updates during copying. At launch the hash, package, version and certificate are checked again. Archive parsing is never considered proof of execution.

Updates import another immutable revision with the same certificate/package, reject downgrades, stop the selected Activity and persist a non-ready state. An atomic metadata pointer selects the new revision. Only a successful Activity create/start/resume permits a ready registry state. Failure restores the old pointer and preserves data. A crash mid-update leaves a non-ready record that must be explicitly reverified by launch. Superseded APK revisions are retained until deletion, avoiding unsafe deletion of code potentially in use; revision garbage collection remains a performance limitation.

## Actual Activity hosting and its limits

`GuestHostActivity` loads the Activity class with DexClassLoader, using the platform parent loader rather than including the probe as host code. `Instrumentation.newApplication`, `newActivity(Class, Context, IBinder, ...)` and public lifecycle calls create and invoke actual guest framework objects. The real host Window layout token is used; a fabricated Binder renders some views but fails Activity identity callbacks.

The guest Activity's **content** is attached under the single host system Window. Nesting a guest DecorView causes cyclic accessibility parents; an integration test caught and fixed this. Autofill and content capture are disabled on the embedded content. Create/start/resume/pause/stop/destroy callbacks are forwarded; tests read lifecycle records written by the guest APK. Host configuration recreation reloads the guest from its immutable APK and saved persistent state.

Android documents this `Instrumentation.newActivity` overload as intended for local tests and explicitly notes missing system linkages. It does not register an arbitrary guest package with ActivityTaskManager or make its Services/Providers system components. This is the exact reason arbitrary Activities, Compose activities, guest tasks, activity results, dialogs/new windows, dynamic `setContentView` replacement, custom Application objects, Services, manifest Receivers, Providers, Binder services, jobs and foreground services are **not supported** by this prototype. The admitted probe uses simple framework Views and retains its own view references. Do not broaden the package allowlist and claim those features work.

References: [Instrumentation API](https://developer.android.com/reference/android/app/Instrumentation), [Android 16 implementation](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/app/Instrumentation.java), [dynamic code loading](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading), [application sandbox](https://source.android.com/docs/security/app-sandbox).

## Operation results and recovery

Explicit, unexported components and single-use PendingIntent callbacks route only a UUID and result capability. The receiver matches the pending nonce and expected instance UUID. A 20-second deadline yields failure rather than assumed success. Creation registers only after the guest process acknowledges successful Activity resume. Stop completes after host Activity destruction; deletion then removes only that UUID directory and registry row. Deletion walks without following symlinks. Guest crashes are restartable from persisted metadata; no guest is prelaunched at host startup.

Import and registry operations run on IO coroutines and are serialized by an engine Mutex. Cancellation cleans up a failed new import. A host process death before registry commit can leave an unregistered directory: this is retained rather than silently adopted as a working clone. Automated orphan adoption/cleanup is not implemented. A missing directory is displayed as missing. Runtime readiness describes the supported prototype contract, not the installed compatibility of every APK.

## Data model and upgrade safety

Room migration 1→2 adds virtual user, runtime, compatibility, version/ABI and environment identity columns; it replaces the old package/profile unique index with an engine-aware environment key. Existing UUIDs, names, timestamps, pending requests and work-profile rows are preserved. The migration test creates a genuine v1 database, upgrades it and inserts two virtual copies alongside its old row. No destructive migration is configured.

Rust registry schema 3 permits bounded, unique virtual UUIDs/users and numbered copies, preserving strict package/name/metadata validation. It upgrades schemas 1/2 and retains their work-profile semantics. Virtual restore matches UUIDs already backed by runtime data and only restores presentation metadata; it cannot fabricate a clone or import original app data. Metadata export does not include guest data.

`NestApplication.engine` selects the virtual implementation. The old profile controller is separate and exposed only through Legacy recovery when old rows exist. The provisioning Activity/intent query, setup route and creation consent are removed. No new path creates a managed profile. The historical device-admin receiver, authenticated work bridge and REQUEST_DELETE_PACKAGES declaration remain for existing owners/recovery; removing the admin component would break older installations. Existing profiles/data are never wiped or automatically imported.

## Hardware, permissions and remaining work

The guest permission policy reports camera, microphone, Bluetooth, location, notifications and network permissions as denied even if the host has a grant. No guest permission is silently granted. This is a capability policy for trusted cooperative code, not complete permission interception; no guest hardware bridge is implemented. The manager's existing Bluetooth UI/file-sharing feature remains separate and uses normal Android consent.

Camera/video, microphone/audio routing, Classic/BLE, location, Wi-Fi/network, content URI/file picking, notification actions, jobs, foreground services, clipboard, share and media must remain unsupported until explicit component and permission bridges exist and actual device tests pass. Google account login, Play Services, Integrity and hardware attestation are not implemented, spoofed or defeated.

Full third-party hosting requires a legally audited modern runtime, its complete Java/native build provenance and real Activity/Binder/component/filesystem mediation for APIs 33–36. Physical ARM64 and at least two permitted third-party apps remain release gates. A passing emulator probe cannot satisfy those gates.
