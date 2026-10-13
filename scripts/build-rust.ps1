param([string]$Ndk = "$env:ANDROID_HOME\ndk\27.1.12297006")
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$nativeBin = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
if (!(Test-Path -LiteralPath $nativeBin)) { throw "NDK not found: $Ndk" }
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $nativeBin 'aarch64-linux-android33-clang.cmd'
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = Join-Path $nativeBin 'x86_64-linux-android33-clang.cmd'
# 16 KiB ELF segment alignment for Android 15+ devices.
$env:RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
Push-Location $taskRoot
try {
    foreach ($target in @('aarch64-linux-android','x86_64-linux-android')) {
        cargo build --manifest-path rust-core/Cargo.toml --locked --release --target $target
        if ($LASTEXITCODE -ne 0) { throw "Rust build failed for $target" }
        $abi = if ($target -eq 'aarch64-linux-android') { 'arm64-v8a' } else { 'x86_64' }
        $dest = Join-Path $taskRoot "app/src/main/jniLibs/$abi"
        New-Item -ItemType Directory -Force $dest | Out-Null
        Copy-Item -LiteralPath "rust-core/target/$target/release/libparallelnest_core.so" -Destination $dest
    }
} finally { Pop-Location; Remove-Item Env:RUSTFLAGS -ErrorAction SilentlyContinue }
