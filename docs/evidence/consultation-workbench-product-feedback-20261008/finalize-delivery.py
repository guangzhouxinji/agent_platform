from pathlib import Path
import json,hashlib,datetime
root=Path(__file__).resolve().parents[3];ev=Path(__file__).resolve().parent
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
now=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat()
full=load(ev/'full-regression-rbc18/full-backend-result.json');stability=load(ev/'full-regression-rbc18/source-stability.json')
front=load(ev/'frontend-full-result.json');frontExit=load(ev/'frontend-full-exit.json')
assert full['failures']==0 and full['errors']==0 and stability['unchanged']
assert load(ev/'full-regression-rbc18/full-backend-exit.json')['exit']==0
assert all(frontExit[k]==0 for k in ['testsExit','buildExit','consultationBuildExit'])
assert front['numFailedTests']==0 and front['numPassedTests']==front['numTotalTests']
assert load(ev/'backend-compile-exit.json')['exit']==0
assert load(ev/'verify-implementation-exit.json')['exit']==0
stop=load(ev/'rbc18-stop-exit.json');assert stop['exit']==0 and stop['markerPreserved']
assert (ev/'original-service-final.txt').read_text(encoding='utf-8-sig').strip()=='2b9ed145accef3b09f10fca0987b1de915f314e3b268541e92f895104ce562ef|true'
packages=load(root/'docs/evidence/consultation-admission-layering-20261008/preserved-packages-final.json')
for package in packages:
 assert sha(Path(package['path']))==package['sha256'], 'Never silently replace a fixed package'
(ev/'preserved-packages-final.json').write_text(json.dumps(packages,ensure_ascii=False,indent=2),encoding='utf-8')
delta=load(ev/'source-delta-from-rbc17.json');assert not delta['backendChanged'] and not delta['dbMigrationsChanged']
receipt={'taskId':'PC-COMPARISON-PRODUCT-FEEDBACK-01','recordedAt':now,'implemented':True,'engineeringVerified':True,'businessAccepted':False,
 'actualDelivery':['Formal consultation input panel renders bound document/version and Chinese candidate state, with Input SHA/version in closed trace details',
 'Explicit single-candidate start shows business feedback without request key, candidate digest, raw state or attempt; exact internal request and GET identity unchanged',
 'Existing matrix results render actual feature text, partial completion, real failure/unknown, source quotation and result version; technical coordinates/SHA remain in trace details',
 'No new persisted state, route, permission, model method, temporary page or scheduling behavior'],
 'codeFiles':[x['file'] for x in delta['changes']],
 'focusedEvidence':'frontend-focused-fixed-exit.json','frontend':{'tests':front['numTotalTests'],'passed':front['numPassedTests'],'failed':front['numFailedTests'],'unexecuted':front['numPendingTests'],'evidence':'frontend-full-result.json','builds':'frontend-full-exit.json'},
 'backend':{'totals':full['totals'],'xmlReports':full['reportCount'],'evidence':'full-regression-rbc18/full-backend-result.json','skipReasons':'full-regression-rbc18/conditional-skips.json','sourceStability':'full-regression-rbc18/source-stability.json','conditionalSkippedAreNotPassed':True},
 'compile':'backend-compile-exit.json','finalGovernance':'verify-final-exit.json',
 'evidenceBoundary':'Component/fetch consumer tests and full engineering regression only; not actual browser, real native CLAIMS Source/Issuer positive chain, professional matrix or production deployment',
 'realClaimsPositiveChainExecuted':False,'realModelCalls':0,'newAttemptsOrMatrixComputedByThisSlice':0,'newPackages':0,'currentEnvironmentMigrations':0,'deployments':0,
 'currentService':'Existing exact consultation container remains running; unchanged deployment does not include this source update',
 'otherDomains':{'enforcement':'No new feature claimed: no independent eligible normal draft/handoff slice selected; exact current Task/Source/grant evidence required for real chain',
 'adjudication':'No new feature claimed: 03B current source/material positive adoption gate retained; no 03C work'},
 'remaining':['Existing exact native CLAIMS source mapping/use/contract/epoch/scope/validity request is still NOT_GRANTED_BY_THIS_FILE',
 'Real Issuer/Grant/source prepare-confirm-Input-mTLS-handoff-original-key positive chain remains unexecuted',
 'Calculation/lease/attempt/nonempty matrix/professional acceptance require own admission; no model budget authorized',
 'V105 official target migration and successor deployment not authorized; fixed candidates preserved',
 'Grant recovery responsibility/S1-S2 and actual dimp ownership/data/DDL/writer scope remain separate gates']}
(ev/'engineering-receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
p=root/'docs/08-总开发实现计划.md';s=p.read_text(encoding='utf-8');assert '计划版本：V10.33' in s
s=s.replace('计划版本：V10.33','计划版本：V10.34',1)
lines=s.splitlines()
for i,line in enumerate(lines):
 if line.startswith('| PC-COMPARISON-PRODUCT-FEEDBACK-01 |'):
  fields=line.split('|');fields[4]=' 已完成 ';fields[10]=' 2026-10-08 '
  fields[11]=' [限定展示D](adr/consultation-comparison-input-20261005.md#最终产品工作台业务化反馈限定d2026-10-08)；[正式启动代码](../frontend/src/features/consultation/product/ComparisonStart.tsx)；[工作台](../frontend/src/features/consultation/product/ComparisonInputWorkbench.tsx)；[结果读取](../frontend/src/features/consultation/product/ComparisonMatrixResults.tsx)；[专项](../frontend/src/features/consultation/product/comparisonPresentation.test.ts)；[实际工程回执](evidence/consultation-workbench-product-feedback-20261008/engineering-receipt.json) '
  fields[13]=f' DONE（仅已实施＋工程验证）：普通用户候选显示已核文献号/版本、阶段中文反馈；原键/attempt/摘要不直接展示，Input/结果/引用坐标移入追溯详情但原件及后台绑定不变。原键GET、未知不得重发、明确取消、跨上下文迟到与部分失败边界保留。前端{front["numTotalTests"]}项全通过及两构建退出0；新RBC18完整未筛选后端{full["tests"]}项0失败/0错误/{full["skipped"]}条件跳过、{full["reportCount"]}XML、源码前后SHA一致，跳过仍未执行；后端工程编译通过，不新打包。CLAIMS来源决定仍缺，真实身份/来源prepare→确认→Input→受管交接正向链未执行，不外呼模型、不部署。下个准确出口仍VER-CON-USER-START-MTLS-01现有来源映射申请齐备后执行；执法/裁决无新增已获准独立功能，不冒完成或扩03C。 '
  lines[i]='|'.join(fields);break
else:raise RuntimeError('Stable task row not found')
s='\n'.join(lines)+'\n';p.write_text(s,encoding='utf-8')
print(json.dumps({'planVersion':'V10.34','task':'PC-COMPARISON-PRODUCT-FEEDBACK-01','backend':full['totals'],'frontend':front['numTotalTests']},ensure_ascii=False))
