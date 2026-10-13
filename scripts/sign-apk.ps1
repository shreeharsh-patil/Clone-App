param(
    [string]$InputApk = "$PSScriptRoot/../app/build/outputs/apk/release/app-release-unsigned.apk",
    [string]$OutputApk = "$PSScriptRoot/../artifacts/release/Clone-Apps-v0.2.0-alpha.apk"
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath("$PSScriptRoot/..")
$keyFile = Join-Path $projectRoot '.signing/clone-apps-release.jks'
$passwordFile = Join-Path $projectRoot '.signing/store-password.dpapi'
if (!(Test-Path -LiteralPath $keyFile) -or !(Test-Path -LiteralPath $passwordFile)) {
    throw 'Local release signing material is missing. Preserve the original key for updates; do not generate a replacement for an existing release.'
}
if (!$env:ANDROID_HOME) { throw 'Set ANDROID_HOME to the Android SDK directory.' }
$signer = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/apksigner.bat'
$aligner = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/zipalign.exe'
$inputPath = (Resolve-Path -LiteralPath $InputApk).Path
$outputPath = [IO.Path]::GetFullPath($OutputApk)
New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
& $aligner -c -P 16 4 $inputPath
if ($LASTEXITCODE -ne 0) { throw 'APK alignment check failed.' }
$encryptedPassword = [IO.File]::ReadAllText($passwordFile)
$securePassword = ConvertTo-SecureString -String $encryptedPassword
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:CLONE_APPS_STORE_PASS = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    & $signer sign --ks $keyFile --ks-key-alias clone-apps --ks-pass env:CLONE_APPS_STORE_PASS --key-pass env:CLONE_APPS_STORE_PASS --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $outputPath $inputPath
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
} finally {
    $env:CLONE_APPS_STORE_PASS = $null
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    $securePassword.Dispose()
}
& $signer verify --verbose --print-certs --min-sdk-version 33 $outputPath
if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed.' }
Write-Output "Verified signed APK: $outputPath"
