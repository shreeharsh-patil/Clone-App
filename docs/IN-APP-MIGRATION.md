# In-app cloning migration — engineering gates

Status: **not implemented / not shipped**. Updated 2026-10-13.

## User requirement and current behavior

The requested behavior is multiple independent copies of Android applications **inside Clone Apps**, without provisioning an Android work profile, creating an Android user, or adding a Work tab to the device launcher.

The current `WorkProfileEngine` uses `DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE` and launches real apps in a managed Android profile. Android controls the separate Work tab; changing the Compose UI cannot remove that OS behavior. The disabled `VirtualContainerEngine` is only a capability notice, **not** a guest-runtime implementation.

Do **not** ship the existing engine with its copy/launcher UI relabeled as in-app virtualization, silently hide/rename its provisioning consent, or present host shortcuts as independent clones.

## First nonbreaking migration seam

`NestServices.engine` now exposes the generic `CloneEngine` contract rather than the concrete `WorkProfileEngine`. Profile-only actions (provisioning, reconciliation, work-only diagnostics, legacy metadata update and restore) are explicitly routed through `NestServices.profileEngine`. The current runtime **still** selects `WorkProfileEngine` to preserve existing installs and user data.

This change is an architectural seam; it does **not** enable virtual cloning or remove any existing Work tab.

## Required in-app runtime capabilities

1. Install an explicitly selected, trusted first-party test APK from its base and split APK files into a host-controlled guest package registry, without `ACTION_PROVISION_MANAGED_PROFILE`, root, new Android user, package renaming, or a real system `PackageInstaller` install of a second package.
2. Actually execute the fixture's Activity and its lifecycle in the host's guest process, and show the fixture's own UI. Loading only a plugin class, a screenshot, or a web shortcut is **not** an app clone.
3. Launch two guest instance identifiers for one package. Keep their databases, preferences, file paths and persistent app state separate across host force-stop, reboot and APK update. Delete one without affecting the other or the original app.
4. Provide permission-controlled host-to-guest bridges for the Android components used by the fixture: Activity/Service/BroadcastReceiver/ContentProvider, file picker/content grants, notification channel and PendingIntent routing, background jobs and startup limits. Do not claim a bridge works from API availability alone.
5. Validate behavior for camera, microphone, Bluetooth Classic/BLE, precise/approximate location, Wi-Fi/network access, audio routing, media capture, notifications and notification actions, with permission-denied and revocation cases. Do not silently grant host or guest permissions.
6. Measure cold/warm start latency, memory, CPU, battery and crash recovery on API 33–36, ARM64 and at least one real Android OEM phone. Do not promise every app works; display explicit incompatibility states for unsupported apps.
7. Audit license/provenance of every imported dependency and native binary. Treat virtual processes as having **the host Android UID**, not OS-enforced per-guest sandboxes. Do not defeat third-party anti-tamper, Play Integrity, device attestation or DRM.

## Engine candidate investigation (not a dependency approval)

- [BlackBoxReborn](https://github.com/fdavids77/BlackBoxReborn) publicly claims Android 16 support and an ARM64 physical test, but its README mentions GPL-3.0 while its checked-in LICENSE contains Apache-2.0; dependency/inherited-code provenance must be resolved. The project documents kernel UID mismatch and app-compatibility gaps. A successful upstream CI APK build is **not** evidence of integrated third-party guest lifecycle behavior in Clone Apps.
- [VirtualApp_16](https://github.com/f0restw0w/VirtualApp_16) documents support through Android 14 and explicitly calls its implementation unstable, not production-ready; this does not establish Android 15/16 compatibility.
- [BlackBox](https://github.com/ZENINXOP/BlackBox) advertises experimental Android 15 for its free edition and excludes Android 16 from that free edition, so it is not a proven API 33–36 option.
- Earlier evaluated projects, licensing considerations and existing live-profile test evidence remain in [FEASIBILITY.md](FEASIBILITY.md).

No third-party guest hook code is bundled by this migration seam.

## Release and migration policy

- Develop a tested guest runtime in an isolated feature branch. Keep the production Work Profile implementation available to its existing users until a genuine guest runtime passes the gates above.
- Never auto-convert existing work app instances to virtual instances. Existing Android work-app private data cannot be read by the parent app; preserve user accounts and metadata. Obtain explicit consent for any migration that requires re-sign-in.
- Never auto-delete the Work Profile. Android removes all local work app data if the user manually removes it.
- Allow the new **in-app mode** to be selected only when a real working implementation is installed and validated; never silently fall back from an in-app choice to managed-profile provisioning.
- Gate a release on successful local/CI builds, instrumentation, actual on-device APK execution, security and licensing review. Update README/product messaging only when those claims are proved.

## Acceptance result (as of this commit)

- [x] Generic cloning calls no longer force UI/ViewModel to depend on the concrete work-profile engine class.
- [x] Work-only operations remain segregated to the legacy profile controller, without data migration.
- [ ] Real first-party APK guest installed and launched inside the host.
- [ ] Two independent instances with lifecycle, data separation and reboot/update persistence.
- [ ] Android 13–16 device and permission/hardware integration tests.
- [ ] License, security and physical-device signoff.
- [ ] Default user flow no longer creates any work profile.

Until the unchecked items are verified, the **user's requested native in-app cloning behavior is not fixed**.
