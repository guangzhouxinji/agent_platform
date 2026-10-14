import json,datetime,hashlib,xml.etree.ElementTree as ET,zipfile,sys
from pathlib import Path
root=Path('docs/evidence/adjudication-workspace-20261002')
run=root/sys.argv[1]
r=json.loads(run.read_text(encoding='utf-8-sig'))
if 'exitCode' not in r:r['exitCode']=0 if r['domainExitCode']==r['apiExitCode']==0 else 1
scoped=run.name=='post-record-run.json'
a=datetime.datetime.fromisoformat(r['startedAt']).timestamp();z=datetime.datetime.fromisoformat(r['finishedAt']).timestamp()
items=[];groups={}
for p in Path('backend').glob('**/target/surefire-reports/TEST-*.xml'):
 if not (a<=p.stat().st_mtime<=z):continue
 s=ET.parse(p).getroot()
 if p.name!='TEST-'+s.attrib['name']+'.xml':continue
 if scoped and not (p.parts[1] in ('consultation','enforcement','adjudication') or s.attrib['name'] in ['cn.xinji.agentplatform.adjudication.casework.'+n for n in ['CaseWorkHttpsLifecycleTest','CaseWorkHttpsNegativesTest','CaseWorkRecoveryFailuresTest','CaseWorkConcurrencyTest']]):continue
 counts={k:int(s.attrib.get(k,0)) for k in ['tests','failures','errors','skipped']}
 module=str(p).split('\\target\\')[0].replace('\\','/')
 group=groups.setdefault(module,{k:0 for k in counts})
 for k,v in counts.items():group[k]+=v
 items.append(dict(path=p.as_posix(),suite=s.attrib['name'],sha256=hashlib.sha256(p.read_bytes()).hexdigest(),**counts))
assert items,'No current canonical reports'
prefix=run.stem.replace('-run','')
archive=root/(prefix+'-xml.zip')
assert not archive.exists(),'Refusing report archive overwrite'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as f:
 for item in items:f.write(item['path'],item['path'])
summary={**r,'suites':len(items),'totals':{k:sum(i[k] for i in items) for k in ['tests','failures','errors','skipped']},'modules':groups,'selection':'Canonical XML modified within this actual execution window; older and suffix reports excluded','reportArchive':archive.as_posix(),'archiveSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'reports':items}
(root/(prefix+'-summary.json')).write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:summary[k] for k in ['exitCode','suites','totals','modules']},ensure_ascii=False))
