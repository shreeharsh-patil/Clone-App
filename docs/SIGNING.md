# Release signing

Clone Apps v0.2.0-alpha and v0.3.0-prototype use the same persistent local RSA-3072 release certificate. APK Signature Scheme v3 is verified using the SDK's apksigner for the supported minimum API 33. The release APK is minified, resource-shrunk, and not debuggable. Both supported native ABIs retain 16 KiB alignment after signing.

Public release certificate SHA-256: `e041ea9a514979e5c956a2178dc4f8b8a407ed10cc79beecc5db8c86c1723d05`.

Private signing material is stored under the ignored `.signing/` directory in the owner's workspace. The keystore password is encrypted using Windows DPAPI for the current Windows user. Neither the keystore nor its encrypted password belongs in a source archive, Git commit or public release asset. Keep a secure backup of the keystore and password before changing Windows accounts or replacing the computer; losing the original key prevents updates to existing installations.

To sign a future locally built unsigned release on the same Windows account:

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME='C:\Users\shree\AppData\Local\Android\Sdk'
./gradlew.bat :app:assembleRelease
./scripts/sign-apk.ps1 -OutputApk ./artifacts/release/Clone-Apps-release.apk
```

The script consumes the existing key, keeps the password out of command arguments/output, checks alignment and verifies the signed output. It refuses to generate a replacement key when signing material is absent. Other environments can use the signing secrets declared in `.github/workflows/android.yml` with a securely supplied copy of the same release key. GitHub release signing secrets are not configured automatically by this local workflow.

`artifacts/virtual-runtime/Clone-Apps-v0.3.0-prototype.apk` is the signed product artifact. The separate `Clone-Apps-device-test.apk` and `Clone-Apps-device-tests.apk` are a debuggable host and matching instrumentation APK for controlled physical-device testing. They retain the production certificate so an existing production installation can be updated without uninstalling or clearing data. They are test artifacts, not the release APK. The first-party probe fixtures are also signed with that certificate; the runtime never re-signs imported guest APKs.

The new release certificate differs from the earlier debug alpha's certificate. Android cannot update a debug installation with the release APK. Existing debug users can install the separately provided debug APK with the same local debug certificate. Removing a work-profile controller/profile may delete work data, so do not uninstall it merely to switch certificates without accounting for that data. Fresh installations should use the signed release APK.
