# Security review, 13 October 2026

This is a code/dependency review and emulator verification, not a third-party penetration-test certification.

## Runtime boundaries

- No internet permission in the merged application manifest. No ads, analytics, VPN, accessibility service, notification listener, broad storage permission or QUERY_ALL_PACKAGES.
- Android supplies independent work-profile UID/private storage. The manager uses a profile-owner controller only after user and Android system consent; no device-owner/root/exploit path.
- Incoming work commands require owner/profile checks, callback creator identity, HMAC over operation parameters, bounded timestamps and persistent nonce replay rejection.
- Replies also require HMAC over their nonce, status, message, profile and app lists. Mutable callback capabilities cannot fabricate authenticated successful records.
- Private PackageInstaller broadcasts provide terminal results without background activity launches. Only a foreground operation Activity opens consent. Unavailable consent hosts fail and abandon pending sessions.
- APK paths are canonicalized, constrained to Android package directories and matched against the selected package. Android validates APK signing and split consistency. There is no arbitrary file-shuttle or guest-private-file extraction.
- Rust validates bounded metadata, schema, IDs, packages, names, dates and uniqueness before writes/imports; no handwritten unsafe blocks or foreign-app hooks.
- Android Keystore AES-GCM protects the profile bridge key. Legacy PIN records are removed during migration; launch locks are absent from the current source.
- Backup exports manager metadata/settings only, excluding bridge keys. Restore reconciles against real installations and preserves existing instance IDs.

## Permissions

Current manager permissions include REQUEST_INSTALL_PACKAGES, REQUEST_DELETE_PACKAGES, BLUETOOTH_CONNECT, BLUETOOTH_SCAN with neverForLocation, and the AndroidX generated signature receiver permission. Biometric dependencies and permission requests have been removed. No INTERNET, location or broad storage permission is requested by the manager. The separate first-party hardware probe declares its own test permissions.

Debug builds clear the managed profile's debugging restriction for tests on devices where the user enabled USB debugging. Production builds explicitly reapply the default debugging restriction, including after an update from debug. This difference is disclosed before provisioning. Unknown-source installation is permitted within this controller's own profile, and Android's source-install permission/confirmation is still explicitly handled by the user.

Bluetooth sharing is allowed in this controller's own profile. No personal/global restriction or Bluetooth contact-sharing policy is changed. Guest apps request their own Nearby devices permissions; Clone Apps does not grant them silently. The manager's separate Bluetooth screen requests CONNECT/SCAN through Android's Nearby devices consent. Classic discovery is user initiated, capped at 15 seconds and cancelled on lifecycle stop/navigation. Only its own discovery is cancelled; no background service or polling is added. Device details are bounded to 64 discovery records in memory and excluded from diagnostics/backups. Pairing uses the Android platform consent flow. File sharing delegates one user-selected content URI to Android's share menu with a temporary read grant; file/remote URI schemes are rejected and no URI is retained. The independent test probe declares only CONNECT and checks state/paired-device count after OS permission consent.

## Dependency records

The OSV public batch API was queried for **173** locked dependency versions: 37 Cargo graph packages and 136 resolved Maven coordinates. **0 versions returned advisory records** at 2026-10-13 13:17:58 UTC. Source: https://api.osv.dev/v1/querybatch; reproducible check: scripts/check-advisories.py. The raw local result is artifacts/advisory-check.json (an evaluation artifact, not a runtime upload feature).

Only public source dependency coordinates were queried, never a phone's installed-app list or private data. Coverage is limited to OSV's available records and version matching; absence of records does not prove absence of vulnerabilities. GitHub Dependabot alerts were not enabled/accessible with the current CLI scope and are not claimed to have been audited.

External virtual-engine risks and licensing/provenance exclusions are documented in FEASIBILITY.md. No virtualization/hooking engine is included.

## Executed checks

Native schema rejection, forged command/reply rejection, Keystore encryption, the Compose home screen, Bluetooth lifecycle/permission consent and selected-file URI sharing boundaries passed on the Android 16 emulator (seven checks). The earlier first-party debug probe demonstrated separate private values, rootless launching, freeze/resume, recovery and work-only deletion; see the test report for the source version corresponding to that evidence. Test inspection was confined to that fixture. No production app credentials or protected third-party data were read.

The current release is signed with a persistent local release key; see SIGNING.md for storage and update identity details. Remaining stable-release gates include physical ARM64/OEM devices, physical Bluetooth accessories, notification/sign-in cases and representative performance measurements. See TEST-RESULTS.md.
