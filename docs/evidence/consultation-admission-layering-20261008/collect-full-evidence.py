from pathlib import Path
import json,hashlib,shutil,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[3]
out=Path(__file__).resolve().parent/'full-regression-rbc16'
exit=json.loads((out/'full-backend-exit.json').read_text(encoding='utf-8-sig'))
suffix='20261008-ADMISSION-LAYERING-FULL16'
reports=[];skips=[];totals={k:0 for k in ['tests','failures','errors','skipped']}
xmls=list((root/'backend').rglob('TEST-*-'+suffix+'.xml'))
if not xmls:raise RuntimeError('No actual fresh full-run XML')
for file in sorted(xmls):
    if 'surefire-reports' not in file.parts:continue
    dest=out/'full-reports'/('raw-'+hashlib.sha256(file.relative_to(root/'backend').as_posix().encode()).hexdigest()[:24]+'.xml')
    if dest.exists():raise RuntimeError('Never overwrite copied original XML')
    dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,dest)
    suite=ET.fromstring(file.read_bytes())
    row={'name':suite.attrib['name'],'file':dest.relative_to(out).as_posix(),'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'originalFile':file.relative_to(root/'backend').as_posix()}
    for key in totals:row[key]=int(suite.attrib[key]);totals[key]+=row[key]
    reports.append(row)
    for case in suite.findall('testcase'):
        skip=case.find('skipped')
        if skip is not None:skips.append({'suite':suite.attrib['name'],'test':case.attrib.get('name'),'class':case.attrib.get('classname'),'message':skip.attrib.get('message',''),'actualReason':skip.text or '', 'status':'CONDITIONALLY_SKIPPED_NOT_EXECUTED_NOT_PASSED'})
if len(skips)!=totals['skipped']:raise RuntimeError('Unexplained skipped tests')
if exit['exit']==0 and totals['errors']+totals['failures']:raise RuntimeError('Actual exit contradicts failures')
result={'scope':'FULL_UNFILTERED_CURRENT_SOURCE_REAL_ISOLATED_ENVIRONMENT','reportCount':len(reports),'tests':totals['tests'],'failures':totals['failures'],'errors':totals['errors'],'skipped':totals['skipped'],'totals':totals,'reports':reports,'exitReceipt':'full-backend-exit.json','sourceStability':'source-stability.json','conditionalSkippedAreNotPassed':True}
for name,value in [('full-backend-result.json',result),('conditional-skips.json',{'scope':'ACTUAL_XML_SKIP_REASONS_NOT_EXECUTED','cases':skips})]:
    target=out/name
    if target.exists():raise RuntimeError('Never overwrite result evidence')
    target.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
# Keep TLS handshake and genuine persisted coordinates separate from formal identity/source evidence.
actual=root/'target/handoff-layering-evidence'/suffix
if actual.exists():
    shutil.copytree(actual,out/'handoff-transport-and-persistence-raw')
print(json.dumps({'totals':totals,'reportCount':len(reports),'exit':exit['exit']},ensure_ascii=False))
