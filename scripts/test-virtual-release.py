"""UI smoke test of a signed/minified release on a fresh disposable test device."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--reboot', action='store_true')
p.add_argument('--existing', action='store_true', help='Reinstall the host and verify the two fixtures from the previous result')
args = p.parse_args()
out = ROOT / 'artifacts/virtual-runtime/evidence/release-api36'
out.mkdir(parents=True, exist_ok=True)
adb_base = ['adb', '-s', args.serial]


def adb(*cmd, timeout=60):
    return subprocess.run(adb_base + list(cmd), check=True, capture_output=True, text=True,
                          encoding='utf-8', errors='replace', timeout=timeout).stdout


def boot():
    end = time.monotonic()+180
    while time.monotonic()<end:
        try:
            if adb('shell','getprop','sys.boot_completed',timeout=10).strip()=='1':
                adb('shell','input','keyevent','82')
                return
        except subprocess.SubprocessError:
            pass
        time.sleep(2)
    raise RuntimeError('Boot timed out')


def nodes():
    for attempt in range(3):
        try:
            adb('shell','uiautomator','dump','/sdcard/virtual-release-window.xml',timeout=30)
            xml = adb('shell','cat','/sdcard/virtual-release-window.xml')
            return list(ET.fromstring(xml).iter('node'))
        except (subprocess.SubprocessError, ET.ParseError):
            if attempt==2:raise
            time.sleep(1)


def find(text, contains=False):
    end = time.monotonic()+30
    while time.monotonic()<end:
        for node in nodes():
            value = node.get('text','')
            if (text.casefold() in value.casefold()) if contains else (text.casefold()==value.casefold()):
                return node
        time.sleep(.2)
    raise AssertionError(f'No visible UI text: {text}')


def tap_node(node):
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',node.attrib['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))


def tap(text):
    tap_node(find(text))


def screenshot(name):
    adb('shell','screencap','-p','/sdcard/virtual-release.png')
    adb('pull','/sdcard/virtual-release.png',str(out/f'{name}.png'))


def create(value):
    tap('Add App');tap('Clone Apps Probe');tap('Clone (1)')
    find('Actual Guest Probe Activity')
    identity=find('Data:',contains=True).get('text')
    instance=re.search(r'instances/([0-9a-f-]{36})',identity).group(1)
    field=next(node for node in nodes() if node.get('class')=='android.widget.EditText')
    tap_node(field);adb('shell','input','text',value);adb('shell','input','keyevent','4')
    tap('Save guest value');find('Saved: '+value);screenshot(value.lower())
    adb('shell','input','keyevent','4');tap('Open Home')
    return instance


boot()
for name in ['Clone-Apps-v0.3.0-prototype.apk','isolation-probe-v1-signed.apk']:
    adb('install','-r','-t',str(ROOT/'artifacts/virtual-runtime'/name))
adb('shell','am','start','-W','-n','org.parallelnest.app/.MainActivity')
if args.existing:
    previous=out/'result.json'
    ids=json.loads(previous.read_text())['instances'] if previous.exists() else json.loads((out/'created-instances.json').read_text())
else:
    ids=[create('RELEASE_ONE'),create('RELEASE_TWO')]
assert ids[0]!=ids[1]
(out/'created-instances.json').write_text(json.dumps(ids),encoding='utf-8')
adb('shell','am','force-stop','org.parallelnest.app')


def verify(stage):
    for value,identity in [('RELEASE_ONE',ids[0]),('RELEASE_TWO',ids[1])]:
        started=adb('shell','am','start','-W','-a','android.intent.action.VIEW','-n','org.parallelnest.app/.MainActivity','--es','instance',identity)
        (out/f'{stage}-{value.lower()}-start.txt').write_text(started,encoding='utf-8')
        find('Saved: '+value)
        assert identity in find('Data:',contains=True).get('text')
        screenshot(f'{stage}-{value.lower()}')
        adb('shell','input','keyevent','4')


verify('after-force-stop')
if args.reboot:
    adb('reboot');boot();verify('after-reboot')
(out/'result.json').write_text(json.dumps({'result':'PASS','instances':ids,'force_stop':True,'reboot':args.reboot,
    'host_apk_sha256':hashlib.sha256((ROOT/'artifacts/virtual-runtime/Clone-Apps-v0.3.0-prototype.apk').read_bytes()).hexdigest(),
    'api':adb('shell','getprop','ro.build.version.sdk').strip(),
    'abi':adb('shell','getprop','ro.product.cpu.abi').strip(),
    'execution':'emulator' if adb('shell','getprop','ro.kernel.qemu').strip()=='1' else 'physical device'},indent=2),encoding='utf-8')
(out/'runtime.log').write_text(adb('logcat','-d'),encoding='utf-8')
for process in ['org.parallelnest.app','org.parallelnest.app:guest0','org.parallelnest.app:guest1']:
    (out/f'memory-{process.rsplit(":",1)[-1]}.txt').write_text(adb('shell','dumpsys','meminfo',process),encoding='utf-8')
print('RELEASE_UI_PASS: signed/minified APK, two real guest Activities, separate persistent values, restart'+('/reboot' if args.reboot else ''),flush=True)
