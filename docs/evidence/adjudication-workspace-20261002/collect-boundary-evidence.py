import json,hashlib,datetime,re
from pathlib import Path
root=Path('docs/evidence/adjudication-workspace-20261002')
b=json.loads((root/'before-boundaries.json').read_text(encoding='utf-8-sig'))
checks=[]
for name,digest in b['hashes'].items():
 p=Path(name);current=hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else None
 checks.append({'path':name.replace('\\','/'),'beforeSha256':digest,'afterSha256':current,'unchanged':digest==current})
plan=Path('docs/08-总开发实现计划.md').read_text(encoding='utf-8-sig').splitlines()
rows={r.split('|')[1].strip():r for r in plan if r.startswith('| ') and len(r.split('|'))==15}
deltas=[];added=[]
for old in b['domainRows']:
 key=old.split('|')[1].strip();new=rows.get(key)
 if old!=new:deltas.append({'taskId':key,'beforeRow':old,'afterRow':new,'note':'Concurrent CON/ENF work observed; this ADJ task did not edit or restore this row'})
 oldrefs=set(re.findall(r'(?:IMP|ARC|REQ)-ADJ-[A-Z0-9-]+',old.split('|')[6]))
 newrefs=set(re.findall(r'(?:IMP|ARC|REQ)-ADJ-[A-Z0-9-]+',new.split('|')[6])) if new else set()
 if newrefs-oldrefs:added.append({'taskId':key,'newAdjudicationPredecessors':sorted(newrefs-oldrefs)})
owned=list(Path('frontend/src/features/adjudication').glob('*'))+[Path('frontend/e2e/adjudication-workspace-product.mjs'),Path('scripts/verify-adjudication-workspace.ps1'),Path('frontend/src/app/AppRoutes.tsx')]+list(Path('backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/adjudication/casework').glob('*.java'))+list(Path('backend/apps/platform-api/src/test/java/cn/xinji/agentplatform/adjudication/casework').glob('*.java'))+[Path('backend/adjudication/src/main/java/cn/xinji/agentplatform/adjudication/casework/persistence/CaseWorkRepository.java'),Path('db/dm8/V086__adjudication_case_work.sql'),Path('db/dm8/U086__adjudication_case_work.sql'),Path('db/dm8/adjudication_source_authority.sql'),Path('scripts/verify-adjudication-api-dm8.ps1')]
cores=[i for i in checks if i['path'] in ['backend/adjudication/src/main/java/cn/xinji/agentplatform/adjudication/casework/'+n+'.java' for n in ['AdjudicationCaseWork','CaseWorkContract','CaseWorkIntegrity']]];assert len(cores)==3;shared=[i for i in checks if '/security/product/' in i['path']]
result={'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'core01AUnchanged':all(i['unchanged'] for i in cores),'core01A':cores,'existingSharedProductSecurityCapturedCount':len(shared),'existingSharedProductSecurityUnchanged':all(i['unchanged'] for i in shared) if shared else None,'existingCheckedFiles':len(checks),'unchangedFileCount':sum(i['unchanged'] for i in checks),'concurrentFileChanges':[i for i in checks if not i['unchanged']],'concurrentPlanRowChanges':deltas,'newAdjudicationPredecessorsInCONENF':added,'adjudicationCheckedFilesIncludingInherited01B':[{'path':p.as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(owned)]}
assert result['core01AUnchanged'],'01A changed; investigate before completion'
assert not added,'ADJ became a CON/ENF prerequisite'
(root/'compatibility-evidence.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print('01A unchanged:',result['core01AUnchanged'],'shared product security unchanged:',result['existingSharedProductSecurityUnchanged'],'new ADJ dependencies:',added,'concurrent changed files:',len(result['concurrentFileChanges']))
