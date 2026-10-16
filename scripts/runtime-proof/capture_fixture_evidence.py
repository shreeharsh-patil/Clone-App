"""Capture this experiment's fixture data on an explicitly selected emulator.

No root, clear-data, uninstall, or original-app data access. This helper refuses
physical devices and registries containing apps other than the explicit fixtures.
"""
import argparse
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tarfile

parser = argparse.ArgumentParser(__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
adb = str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb.exe') if os.name == 'nt' else 'adb'
host = 'org.parallelnest.app.experimental'
fixtures = {'org.fossify.math', 'org.fossify.notes', 'org.parallelnest.splitfixture'}


def run(*command, binary=False):
    result = subprocess.run([adb, '-s', args.serial, *command], capture_output=True, check=True, timeout=60)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace').strip()


def capture(*paths):
    raw = run('exec-out', 'run-as', host, 'tar', 'cf', '-', *paths, binary=True)
    with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
        archive.extractall(args.output, filter='data')


if run('shell', 'getprop', 'ro.kernel.qemu') != '1':
    raise SystemExit('Use the isolated test emulator; physical-device data capture is not automated')
args.output.mkdir(parents=True, exist_ok=False)
capture('databases')
with sqlite3.connect(args.output/'databases/nest-clones.db') as database:
    database.row_factory = sqlite3.Row
    rows = [dict(row) for row in database.execute('select * from instances')]
if not rows or any(row['packageName'] not in fixtures for row in rows):
    raise SystemExit('Guest data capture requires a registry containing only the test fixtures')
capture(*(f"blackbox/data/user/{row['virtualUser']}/{row['packageName']}" for row in rows))
report = {
    'serial': args.serial,
    'api': run('shell', 'getprop', 'ro.build.version.sdk'),
    'abi': run('shell', 'getprop', 'ro.product.cpu.abi'),
    'shell_identity': run('shell', 'id'),
    'instances': rows,
    'code_sha256': {},
    'split_code_sha256': {},
    'fixture_preferences': {},
    'guest_databases': {}
}
for pkg in sorted({row['packageName'] for row in rows}):
    report['code_sha256'][pkg] = run('shell', 'run-as', host, 'sha256sum', f'blackbox/data/app/{pkg}/base.apk').split()[0]
    directory = f'blackbox/data/app/{pkg}'
    files = run('shell', 'run-as', host, 'ls', directory).splitlines()
    report['split_code_sha256'][pkg] = {
        name: run('shell', 'run-as', host, 'sha256sum', f'{directory}/{name}').split()[0]
        for name in files if name.endswith('.apk') and name != 'base.apk'
    }
for file in args.output.glob('blackbox/data/user/*/org.parallelnest.splitfixture/shared_prefs/*.xml'):
    report['fixture_preferences'][file.relative_to(args.output).as_posix()] = file.read_text(encoding='utf-8')
for file in args.output.glob('blackbox/data/user/*/*/databases/*.db'):
    with sqlite3.connect(file) as database:
        tables = {row[0] for row in database.execute("select name from sqlite_master where type='table'")}
        report['guest_databases'][file.relative_to(args.output).as_posix()] = {
            table: database.execute(f'select * from "{table}"').fetchall()
            for table in sorted(tables & {'history', 'notes'})
        }
(args.output/'evidence.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
