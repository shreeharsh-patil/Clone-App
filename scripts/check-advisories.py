"""Query public OSV for the locked, public build dependency inventory (not phone apps)."""
import datetime
import json
import pathlib
import subprocess
import urllib.request

root=pathlib.Path(__file__).resolve().parent.parent
metadata=json.loads(subprocess.check_output(['cargo','metadata','--manifest-path',str(root/'rust-core/Cargo.toml'),'--locked','--format-version','1'],text=True))
queries=[{'package':{'name':p['name'],'ecosystem':'crates.io'},'version':p['version']} for p in metadata['packages'] if p['name']!='parallelnest-core']
for coord in json.loads((root/'build/runtime-coordinates.json').read_text()):
    group,name,version=coord.split(':')
    queries.append({'package':{'name':f'{group}:{name}','ecosystem':'Maven'},'version':version})
request=urllib.request.Request('https://api.osv.dev/v1/querybatch',json.dumps({'queries':queries}).encode(),{'Content-Type':'application/json'})
with urllib.request.urlopen(request,timeout=60) as response:data=json.load(response)
findings=[{'query':q,'vulns':result.get('vulns',[])} for q,result in zip(queries,data['results']) if result.get('vulns')]
report={'checked_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'source':'https://api.osv.dev/v1/querybatch','dependency_count':len(queries),'findings':findings,'scope':'Public source dependency coordinates only. Absence of OSV records does not prove safety.'}
dest=root/'artifacts/advisory-check.json'
dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps(report,indent=2))
print(f'OSV checked {len(queries)} dependencies; {len(findings)} versions with advisory records')
for item in findings:print(item['query']['package']['name'],item['query']['version'],[v['id'] for v in item['vulns']])
