ParallelNest alpha: an original Kotlin/Compose work-profile manager with a Rust metadata validator.

Work profiles support one additional copy of a package. Experimental virtual containers are disabled. App compatibility varies; consult docs/TEST-RESULTS.md for the exact tested configuration. No physical-device/OEM claim is made without recorded execution evidence.

Includes work-profile Bluetooth sharing, an access check and Android Bluetooth settings shortcut. Work apps must obtain their own Nearby devices consent. The emulator probe verified Bluetooth API access; physical pairing/data transfer remains untested. Dashboard scrolling, search retention, frozen-app actions and readable app names were improved. Metadata restore now restores names/styles for existing apps without changing shortcut IDs; authenticated PIN changes clear previous retry delays.

Alpha.2 adds Bluetooth features inside ParallelNest itself: Nearby devices consent, paired/discovered classic-device lists, bounded foreground scanning, Android pairing requests, radio enable/settings actions and a selected-file picker/share-menu flow. Choose Bluetooth in Android's share menu to send a file. Physical pairing and completed transfer need hardware testing; the emulator tests cover permission, actual discovery/cancellation and URI grant boundaries.

Install `app-debug.apk` for evaluation. `app-release-unsigned.apk` cannot be installed until signed. If `ParallelNest-release.apk` is attached, it is signed using the repository owner's release secrets. Keep the same signing certificate for updates. Matching complete source is attached as `ParallelNest-source.zip`.

Do not remove a work profile unless you intend to permanently delete all its app data. Manager backups include metadata only.
