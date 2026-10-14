from pathlib import Path
import json,datetime,hashlib
root=Path.cwd();base=root/'docs/evidence/consultation-product-bundle-20261006';full=base/'full-after-dashboard'
exit=json.loads((full/'full-round02-exit.json').read_text(encoding='utf-8-sig'))
result=json.loads((full/'full-round02-result.json').read_text(encoding='utf-8-sig'))
assert exit['exit']==0 and exit['unfiltered'] is True and result['totals']['failures']==0 and result['totals']['errors']==0
inputs=json.loads((full/'source-inputs.json').read_text(encoding='utf-8-sig'))
for f in inputs['files']:
 assert hashlib.sha256((root/f['path']).read_bytes()).hexdigest()==f['sha256'],f['path']
counts=result['totals'];n=len(result['reports']);at=datetime.datetime.fromisoformat(exit['finishedAt'].replace('Z','+00:00')).astimezone(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y-%m-%d %H:%M')
p=root/'docs/08-总开发实现计划.md';s=p.read_text(encoding='utf-8')
s=s.replace('evidence/consultation-product-bundle-20261006/full-final/full-round02-result.json','evidence/consultation-product-bundle-20261006/full-after-dashboard/full-round02-result.json',1)
s=s.replace('evidence/consultation-product-bundle-20261006/full-final/full-round02-exit.json','evidence/consultation-product-bundle-20261006/full-after-dashboard/full-round02-exit.json',1)
lines=s.splitlines()
for i,l in enumerate(lines):
 if l.startswith('- 2026-10-06 ') and '最新未筛选全后端Maven全部Reactor模块成功' in l:
  lines[i]=f'- {at}（Asia/Shanghai）最新未筛选全后端Maven全部Reactor模块成功：{counts["tests"]}项、{counts["failures"]}失败、{counts["errors"]}错误、{counts["skipped"]}条件跳过，退出0；{n}份当次XML、{len(inputs["files"])}项后端/构建/报告工具输入SHA前后一致。本轮正式咨询发布包实际JAR及227项前端输入稳定性另证；看板1440/390长标识符溢出已修复，当前源码再次完整回归，不把看板浏览器证据当三域产品浏览器/Worker/模型证明。原12:59与12:05的1577/0/0/40、更早1556/0/160/40及其他回归原件均保留；TEST_EVIDENCE_STALE、隔离实例首次准备6001、看板先红后绿均留证。条件跳过不代表验证或业务验收。'
 if l.startswith('| FIX-PLAN-DASHBOARD-WRAP-01 |'):
  lines[i]=l.replace('额外新未筛选回归原件形成后刷新0.2，不复用12:59输入版本作当前源码证明',f'修改后独立治理及当前源码再次未筛选{counts["tests"]}/0/0/{counts["skipped"]}已通过，0.2已更新实际新原件；旧12:59及原红灯保留')
p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
v=base/'verification.md';v.write_text(v.read_text(encoding='utf-8')+f'\n最后当前源码复验：`full-after-dashboard/full-round02-exit.json`及`full-round02-result.json`，{at}，{counts["tests"]}/{counts["failures"]}/{counts["errors"]}/{counts["skipped"]}，{n}份XML，{len(inputs["files"])}输入SHA前后一致；没有覆盖之前成功或失败原件。\n',encoding='utf-8')
print(at,counts,n,'latest actual unfiltered evidence linked')
