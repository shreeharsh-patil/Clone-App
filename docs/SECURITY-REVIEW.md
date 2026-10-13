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
- PIN verification uses salted PBKDF2-HMAC-SHA256, encrypted via Android Keystore AES-GCM, with retry delays. Authentication occurs on every manager launch. Other launch entry points remain outside this lock's scope.
- Backup exports manager metadata/settings only, excluding PIN verifiers/bridge keys. Restore reconciles against real installations and resets manager launch locks as explicitly disclosed.

## Permissions

Merged debug manifest inspected: USE_BIOMETRIC, legacy USE_FINGERPRINT supplied by AndroidX Biometric, REQUEST_INSTALL_PACKAGES, REQUEST_DELETE_PACKAGES and the AndroidX generated signature receiver permission. No INTERNET permission.

Debug builds clear the managed profile's debugging restriction for tests on devices where the user enabled USB debugging. Production builds retain the default policy. This difference is disclosed before provisioning. Unknown-source installation is permitted within this controller's own profile, and Android's source-install permission/confirmation is still explicitly handled by the user.

## Dependency records

The OSV public batch API was queried for **132** locked dependency versions: 37 Cargo graph packages and 95 resolved Maven coordinates. **0 versions returned advisory records** at the recorded check time. Source: https://api.osv.dev/v1/querybatch; reproducible check: scripts/check-advisories.py. The raw local result is artifacts/advisory-check.json (an evaluation artifact, not a runtime upload feature).

Only public source dependency coordinates were queried, never a phone's installed-app list or private data. Coverage is limited to OSV's available records and version matching; absence of records does not prove absence of vulnerabilities. GitHub Dependabot alerts were not enabled/accessible with the current CLI scope and are not claimed to have been audited.

External virtual-engine risks and licensing/provenance exclusions are documented in FEASIBILITY.md. No virtualization/hooking engine is included.

## Executed checks

Native schema rejection, forged command/reply rejection, PIN retry delays and Keystore encryption passed on the Android 16 emulator. A first-party debug probe demonstrated separate private values, rootless launching, freeze/resume, recovery and work-only deletion. Test inspection was confined to that fixture. No production app credentials or protected third-party data were read.

Remaining release gates include physical ARM64/OEM devices, biometric hardware, notification/sign-in cases, representative performance measurements and stable production signing. See TEST-RESULTS.md.
