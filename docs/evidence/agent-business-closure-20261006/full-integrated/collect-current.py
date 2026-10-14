from pathlib import Path
import json,re,shutil,hashlib,xml.etree.ElementTree as ET,datetime
root=Path.cwd();out=root/'docs/evidence/agent-business-closure-20261006/full-integrated'
exit=json.loads((out/'full-round02-exit.json').read_text(encoding='utf-8-sig'))
started=json.loads((out/'full-round02-input-check.json').read_text(encoding='utf-8-sig'))['startedAt']
start=datetime.datetime.fromisoformat(started).timestamp()
log=(out/'full-round02-unfiltered.log').read_text(encoding='utf-8-sig',errors='replace')
names=set(re.findall(r'\[INFO\] Running (\S+)',log))
if not names:raise RuntimeError('No actual suites')
reports=[];totals=dict.fromkeys(['tests','failures','errors','skipped'],0)
allxml=list((root/'backend').glob('**/target/surefire-reports/TEST-*.xml'))
for name in sorted(names):
 candidates=[p for p in allxml if p.name=='TEST-'+name+'.xml' and p.stat().st_mtime>=start-1]
 if len(candidates)!=1:raise RuntimeError('Fresh default XML not unique: '+name+' '+str(candidates))
 source=candidates[0];suite=ET.parse(source).getroot()
 if suite.tag!='testsuite' or suite.attrib['name']!=name:raise RuntimeError('Suite mismatch')
 relative=Path('reports')/(hashlib.sha256(name.encode()).hexdigest()[:24]+'.xml');target=out/relative
 target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
 counts={k:int(suite.attrib[k]) for k in totals}
 for k,n in counts.items():totals[k]+=n
 reports.append(dict(name=name,**counts,file=relative.as_posix(),sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
result={'scope':'FULL_BACKEND_UNFILTERED_ALL_REACTOR_MODULES_THREE_DOMAIN_REQUEST_EXECUTION_20261006','totals':totals,'reports':reports,'startedAt':started,'finishedAt':exit['finishedAt'],'unfiltered':True}
(out/'full-round02-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
print('FRESH_FULL_RESULT',totals,'reports',len(reports),'exit',exit['exit'])
