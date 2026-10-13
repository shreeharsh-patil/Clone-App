Clone Apps alpha: an original Kotlin/Compose work-profile manager with a Rust metadata validator.

Work profiles support one additional copy of a package. Experimental virtual containers are disabled. App compatibility varies; consult docs/TEST-RESULTS.md for the exact tested configuration. No physical-device/OEM claim is made without recorded execution evidence.

The current source is branded Clone Apps with a new artistic blue/violet adaptive launcher logo. The home screen uses a compact three-column grid and Add App tile. Device Apps supports search, multiple selections and an Install count; progress reports actual installation stages. Metadata uses Room, preferences use DataStore, and the Rust schema preserves clone numbering and engine identity. Launch locks and PIN features have been removed.

Bluetooth features inside Clone Apps include operation-specific Nearby devices consent, paired/discovered classic-device lists, bounded foreground scanning, Android pairing requests, radio enable/settings actions and a selected-file picker/share-menu flow. Choose Bluetooth in Android's share menu to send a file. Physical pairing and completed transfer need hardware testing; the emulator tests cover permission, actual discovery/cancellation and URI grant boundaries. Each work app obtains its own hardware permissions through Android; diagnostics distinguish API availability from proven physical behavior.

Install `app-debug.apk` for evaluation. `app-release-unsigned.apk` cannot be installed until signed. If `Clone-Apps-release.apk` is attached, it is signed using the repository owner's release secrets. Keep the same signing certificate for updates. Matching complete source is attached as `Clone-Apps-source.zip`.

Do not remove a work profile unless you intend to permanently delete all its app data. Manager backups include metadata only.
