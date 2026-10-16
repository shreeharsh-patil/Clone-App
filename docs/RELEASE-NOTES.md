> Historical work-profile release document. The current feature branch uses the limited first-party Activity runtime; see [runtime architecture](VIRTUAL-RUNTIME.md), [candidate audit](VIRTUAL-RUNTIME-AUDIT.md) and [current compatibility evidence](VIRTUAL-COMPATIBILITY.md).

Clone Apps alpha: an original Kotlin/Compose work-profile manager with a Rust metadata validator.

Work profiles support one additional copy of a package. Experimental virtual containers are disabled. App compatibility varies; consult docs/TEST-RESULTS.md for the exact tested configuration. No physical-device/OEM claim is made without recorded execution evidence.

The current source is branded Clone Apps with a new artistic blue/violet adaptive launcher logo. The home screen uses a compact three-column grid and Add App tile. Device Apps supports search, multiple selections and an Install count; progress reports actual installation stages. Metadata uses Room, preferences use DataStore, and the Rust schema preserves clone numbering and engine identity. Launch locks and PIN features have been removed.

Bluetooth features inside Clone Apps include operation-specific Nearby devices consent, paired/discovered classic-device lists, bounded foreground scanning, Android pairing requests, radio enable/settings actions and a selected-file picker/share-menu flow. Choose Bluetooth in Android's share menu to send a file. Physical pairing and completed transfer need hardware testing; the emulator tests cover permission, actual discovery/cancellation and URI grant boundaries. Each work app obtains its own hardware permissions through Android; diagnostics distinguish API availability from proven physical behavior.

Install **Clone-Apps-v0.2.0-alpha.apk** for a fresh installation. This is the minified release build, signed with the persistent RSA-3072 release key; APK Signature Scheme v3 and both native ABIs' 16 KiB alignment were verified. Supports Android 13–16 and ARM64/x86_64. Public certificate details are in SIGNATURE.txt and docs/SIGNING.md.

The release key differs from the previous debug alpha. Existing debug users can update using **Clone-Apps-v0.2.0-alpha-debug.apk**, which retains the earlier local debug certificate. Android cannot install the release APK over a debug installation. Do not remove a controller/profile merely to switch certificates without accounting for its work data.

Matching source is attached as **Clone-Apps-v0.2.0-alpha-source.zip**, with **SHA256SUMS.txt** for every asset. **Clone-Apps-Probe.apk** is an optional first-party storage/Bluetooth API test fixture, not a requirement for using the manager. Its additional hardware helper code is not a completed physical acceptance suite. Private signing material is excluded from source and assets.

Do not remove a work profile unless you intend to permanently delete all its app data. Manager backups include metadata only.
