"""Repeatable emulator/device evidence. Never uses root, pm clear or profile provisioning.

Run only against a disposable test device: this installs the debug host and first-party probe.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
PARSER = argparse.ArgumentParser(__doc__)
PARSER.add_argument('--serial')
PARSER.add_argument('--api', type=int, required=True, choices=range(33, 37))
PARSER.add_argument('--reboot', action='store_true')
PARSER.add_argument('--host-update', action='store_true')
PARSER.add_argument('--recovery-only', action='store_true')
PARSER.add_argument('--guest-update-apk', type=Path)
PARSER.add_argument('--host-apk', type=Path, default=ROOT / 'app/build/outputs/apk/debug/app-debug.apk')
PARSER.add_argument('--probe-apk', type=Path, default=ROOT / 'isolation-probe/build/outputs/apk/debug/isolation-probe-debug.apk')
PARSER.add_argument('--test-apk', type=Path, default=ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
ARGS = PARSER.parse_args()
ADB = ['adb'] + (['-s', ARGS.serial] if ARGS.serial else [])
OUT = ROOT / 'build' / 'virtual-evidence' / f'api-{ARGS.api}'
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args, timeout=180):
    result = subprocess.run(ADB + list(args), capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout, check=True)
    return result.stdout


def instrument(name, classes):
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                 ','.join('org.parallelnest.app.' + c for c in classes),
                 'org.parallelnest.app.test/androidx.test.runner.AndroidJUnitRunner')
    (OUT / f'{name}.txt').write_text(output, encoding='utf-8')
    if not re.search(r'OK \([1-9][0-9]* tests?\)', output) or 'FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output:
        raise RuntimeError(f'{name} failed:\n{output}')
    print(f'{name}: ' + re.search(r'OK \(.*?\)', output).group(), flush=True)


def wait_boot():
    end = time.monotonic() + 180
    while time.monotonic() < end:
        try:
            if adb('shell', 'getprop', 'sys.boot_completed', timeout=10).strip() == '1':
                adb('shell', 'input', 'keyevent', '82')
                return
        except (subprocess.SubprocessError, OSError):
            pass
        time.sleep(2)
    raise RuntimeError('Device did not finish booting')


try:
    wait_boot()
    actual = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    if actual != ARGS.api:
        raise RuntimeError(f'Expected API {ARGS.api}; connected device is API {actual}')
    config = {key: adb('shell', 'getprop', key).strip() for key in
              ['ro.product.model', 'ro.product.cpu.abi', 'ro.build.fingerprint', 'ro.kernel.qemu']}
    config['api'] = actual
    config['execution'] = 'emulator' if config['ro.kernel.qemu'] == '1' else 'physical device'
    if config['execution'] == 'physical device':
        OUT = ROOT / 'build' / 'virtual-evidence' / f'api-{ARGS.api}-physical'
        OUT.mkdir(parents=True, exist_ok=True)
    config['shell_uid'] = adb('shell', 'id', '-u').strip()
    config['host_apk_sha256'] = __import__('hashlib').sha256(ARGS.host_apk.read_bytes()).hexdigest()
    (OUT / 'configuration.json').write_text(json.dumps(config, indent=2), encoding='utf-8')
    host = ARGS.host_apk
    if not ARGS.recovery_only:
        for apk in [host, ARGS.probe_apk, ARGS.test_apk]:
            adb('install', '-r', '-t', str(apk))
        instrument('runtime', ['VirtualRuntimeTest'])
        instrument('regressions', ['GuestHostingProofTest', 'VirtualStorageSecurityTest', 'DatabaseMigrationTest',
                                  'WorkProfileFlowTest', 'RecoveryDeletionTest', 'NativeAndSecurityTest',
                                  'OnboardingTest', 'BluetoothFeatureTest', 'VirtualDashboardTest', 'ShortcutRoutingTest'])
    adb('shell', 'am', 'force-stop', 'org.parallelnest.app')
    instrument('after-force-stop', ['VirtualRecoveryTest'])
    if ARGS.reboot:
        adb('reboot')
        wait_boot()
        instrument('after-reboot', ['VirtualRecoveryTest'])
    if ARGS.host_update:
        adb('install', '-r', '-t', str(host))
        instrument('after-host-apk-replacement', ['VirtualRecoveryTest'])
    if ARGS.guest_update_apk:
        adb('install', '-r', '-t', str(ARGS.guest_update_apk.resolve()))
        instrument('after-guest-version-upgrade', ['VirtualGuestUpdateTest'])
    adb('pull', '/sdcard/Android/data/org.parallelnest.app/files/virtual-evidence', str(OUT / 'guest-ui'))
    (OUT / 'host-startup.txt').write_text(adb('shell','am','start','-W','-n','org.parallelnest.app/.MainActivity'), encoding='utf-8')
    print(f'Evidence: {OUT}', flush=True)
finally:
    for filename, command in [('runtime.log', ('logcat', '-d')), ('memory.txt', ('shell', 'dumpsys', 'meminfo', 'org.parallelnest.app')),
                              ('users.txt', ('shell', 'pm', 'list', 'users')), ('cpu.txt', ('shell', 'dumpsys', 'cpuinfo'))]:
        try:
            (OUT / filename).write_text(adb(*command, timeout=30), encoding='utf-8')
        except (subprocess.SubprocessError, OSError):
            pass
