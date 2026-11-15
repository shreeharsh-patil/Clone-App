# Clone Apps v0.5.1

This prerelease uses the first-party probe runtime. It preserves existing runtime records and lets the matching-signed probe run as separate instances without a work profile. The general runtime remains an opt-in source build.

- Bluetooth permissions are requested when needed. The manager cancels owned scans when it leaves the foreground. The updated matching-signed probe requires consent for each clone before entering the BLE path.
- The dashboard and clone details show supported capabilities. The Separate app setup guide opens Android Settings for native Private Space.
- Android 13–16 emulator checks covered guest execution, storage, migration, and recovery. Physical Motorola, Xiaomi, and Bluetooth accessory validation remains open. See the [audit and capability matrix](https://github.com/shreeharsh-patil/Clone-App/blob/v0.5.1/docs/RUNTIME-REPAIR.md).

Download the signed APK below for Android 13 and later on ARM64 or x86_64.
