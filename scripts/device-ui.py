"""Small adb UI helper. Use only a disposable emulator/test device selected explicitly."""
import argparse
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('operation',choices=['dump','tap','screenshot','input'])
p.add_argument('value',nargs='?')
args=p.parse_args()
import os
adb=str(pathlib.Path(os.environ['ANDROID_HOME'])/'platform-tools'/'adb.exe') if os.name=='nt' else 'adb'
def run(*items,raw=False):
    return subprocess.check_output([adb,'-s',args.serial,*items],text=not raw)
if args.operation=='screenshot':
    pathlib.Path(args.value).parent.mkdir(parents=True,exist_ok=True)
    pathlib.Path(args.value).write_bytes(run('exec-out','screencap','-p',raw=True))
elif args.operation=='input':
    run('shell','input','text',args.value.replace(' ','%s'))
else:
    for attempt in range(3):
        run('shell','rm','-f','/sdcard/nest-ui.xml')
        run('shell','uiautomator','dump','/sdcard/nest-ui.xml')
        try:
            root=ET.fromstring(run('shell','cat','/sdcard/nest-ui.xml'))
            break
        except (subprocess.CalledProcessError,ET.ParseError):
            if attempt==2:raise
            time.sleep(1)
    nodes=list(root.iter('node'))
    if args.operation=='dump':
        for n in nodes:
            a=n.attrib
            if a.get('text') or a.get('content-desc') or a.get('class')=='android.widget.EditText' or a.get('checkable')=='true':
                print(a.get('text') or a.get('content-desc') or ('<input>' if a.get('class')=='android.widget.EditText' else '<check>'),a.get('bounds'),a.get('class'), 'enabled='+a.get('enabled',''))
    else:
        matches=[n for n in nodes if n.get('text')==args.value or n.get('content-desc')==args.value or args.value.endswith('*') and n.get('text','').startswith(args.value[:-1]) or args.value=='<input>' and n.get('class')=='android.widget.EditText' or args.value=='<check>' and n.get('checkable')=='true']
        if not matches:raise SystemExit(f'No visible node {args.value!r}')
        coords=list(map(int,re.findall(r'\d+',matches[0].get('bounds'))))
        run('shell','input','tap',str((coords[0]+coords[2])//2),str((coords[1]+coords[3])//2))
