"""Fetch pinned public source for the separate runtime experiment.

Existing checkouts are never reset or cleaned. Unexpected source changes stop
preparation. No upstream build scripts or downloaded binaries are executed.
"""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
CONFIG = Path(__file__).with_name('sources.json')


def git(path, *args, check=True):
    return subprocess.run(['git', '-C', str(path), *args], check=check,
                          capture_output=True).stdout


def prepare():
    sources = json.loads(CONFIG.read_text(encoding='utf-8'))
    patch = CONFIG.with_name('dobby-android.patch')
    for name, source in sources.items():
        path = ROOT / '.research' / name
        if not path.exists():
            path.parent.mkdir(exist_ok=True)
            subprocess.run(['git', 'clone', '--no-checkout', '--depth=1',
                            source['url'], str(path)], check=True)
            git(path, 'fetch', '--depth=1', 'origin', source['revision'])
            git(path, 'checkout', '--detach', source['revision'])
        revision = git(path, 'rev-parse', 'HEAD').decode().strip()
        if revision != source['revision']:
            raise RuntimeError(f'{name}: unexpected revision {revision}; checkout preserved')
        if git(path, 'ls-files', '--others', '--exclude-standard').strip():
            raise RuntimeError(f'{name}: untracked source files; checkout preserved')
        changes = git(path, 'diff', 'HEAD', '--binary')
        if name == 'Dobby-source':
            if not changes:
                git(path, 'apply', '--check', str(patch))
                git(path, 'apply', str(patch))
                changes = git(path, 'diff', 'HEAD', '--binary')
            if changes.replace(b'\r\n', b'\n') != patch.read_bytes().replace(b'\r\n', b'\n'):
                raise RuntimeError('Dobby has changes beyond the recorded Android patch')
        elif changes:
            raise RuntimeError(f'{name}: modified source; checkout preserved')
        print(f'{name}: {revision}')
    return sources


if __name__ == '__main__':
    prepare()
