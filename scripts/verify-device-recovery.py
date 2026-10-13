"""CI-only probe recovery: no third-party private data, no root, no user-profile removal."""
import os
import pathlib
import re
import subprocess
import time

adb=str(pathlib.Path(os.environ['ANDROID_HOME'])/'platform-tools'/'adb.exe') if os.name=='nt' else 'adb'
serial=os.environ.get('ANDROID_SERIAL','emulator-5554')
def call(*parts):return subprocess.check_output([adb,'-s',serial,*parts],text=True)
def check_values():
    users=call('shell','pm','list','users')
    work_ids=[int(x) for x in re.findall(r'UserInfo\{(\d+):(?:Clone Apps|ParallelNest):',users)]
    assert len(work_ids)==1,users
    for user,value in ((0,'PERSONAL_SENTINEL_2026'),(work_ids[0],'WORK_SENTINEL_2026')):
        xml=call('shell','run-as','org.parallelnest.probe','--user',str(user),'cat','shared_prefs/probe.xml')
        assert value in xml,(user,xml)
    return work_ids[0]
work_id=check_values()
call('shell','am','force-stop','--user','0','org.parallelnest.app')
assert check_values()==work_id
call('reboot')
deadline=time.time()+180
while time.time()<deadline:
    try:
        if call('shell','getprop','sys.boot_completed').strip()=='1':break
    except subprocess.CalledProcessError:pass
    time.sleep(2)
else:raise RuntimeError('Emulator did not finish reboot')
call('shell','input','keyevent','KEYCODE_WAKEUP')
call('shell','input','keyevent','82')
call('shell','am','start-user','-w',str(work_id))
assert check_values()==work_id
call('shell','am','start','--user',str(work_id),'-n','org.parallelnest.probe/.ProbeActivity')
print('RECOVERY_PASS: both private probe values survived manager force-stop and emulator reboot')
