"""Generate notices from resolved Maven coordinates and Cargo's locked graph."""
import json
import os
import pathlib
import subprocess
import xml.etree.ElementTree as ET

root=pathlib.Path(__file__).resolve().parent.parent
metadata=json.loads(subprocess.check_output(['cargo','metadata','--manifest-path',str(root/'rust-core/Cargo.toml'),'--locked','--format-version','1'],text=True))
out=['Clone Apps third-party runtime dependency notices','See LICENSE for original project Apache-2.0 terms.','']
for pkg in sorted(metadata['packages'],key=lambda x:x['name']):
    if pkg['name']=='parallelnest-core':continue
    out.append(f"Rust: {pkg['name']} {pkg['version']} | {pkg.get('license')} | {pkg.get('repository') or ''}")
    folder=pathlib.Path(pkg['manifest_path']).parent
    # Distribute all upstream license/notice files, preserving attribution.
    for f in sorted(folder.glob('*')):
        if f.is_file() and (f.name.upper().startswith('LICENSE') or f.name.upper().startswith('NOTICE') or f.name.upper().startswith('COPYING')):
            out.extend(['',f'--- {pkg["name"]}/{f.name} ---',f.read_text(encoding='utf-8',errors='replace')])
coords=json.loads((root/'build/runtime-coordinates.json').read_text())
cache=pathlib.Path(os.environ.get('GRADLE_USER_HOME',pathlib.Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
ns={'m':'http://maven.apache.org/POM/4.0.0'}
for coord in sorted(coords):
    group,artifact,version=coord.split(':')
    poms=list((cache/group/artifact/version).glob('*/*.pom'))
    licenses=[]
    for pom in poms:
        tree=ET.parse(pom)
        licenses.extend((x.findtext('m:name',default='',namespaces=ns),x.findtext('m:url',default='',namespaces=ns)) for x in tree.findall('./m:licenses/m:license',ns))
    if not licenses:
        # These are the only release coordinates with parent-inherited license declarations.
        if group.startswith(('androidx.','org.jetbrains.','org.jetbrains.kotlinx','com.google.errorprone','com.google.guava')):licenses=[('Apache-2.0','https://www.apache.org/licenses/LICENSE-2.0')]
        else:raise RuntimeError(f'Review missing license declaration for {coord}')
    out.append(f'Maven: {coord} | '+ '; '.join(f'{name} {url}' for name,url in licenses))
out.extend(['','--- Apache License 2.0 (AndroidX, Kotlin, Material, Coroutines and Apache-licensed crates) ---',(root/'LICENSE').read_text()])
dest=root/'app/src/main/assets/third-party-notices.txt'
dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text('\n'.join(out),encoding='utf-8')
print(f'Wrote notices for {len(coords)} resolved Maven coordinates and {len(metadata["packages"])-1} Cargo packages')
