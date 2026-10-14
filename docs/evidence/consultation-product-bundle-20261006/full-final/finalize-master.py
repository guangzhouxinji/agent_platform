from pathlib import Path
import json,datetime
root=Path.cwd();base=root/'docs/evidence/consultation-product-bundle-20261006';full=base/'full-final'
exit=json.loads((full/'full-round02-exit.json').read_text(encoding='utf-8-sig'))
result=json.loads((full/'full-round02-result.json').read_text(encoding='utf-8-sig'))
assert exit['exit']==0 and result['totals']['failures']==0 and result['totals']['errors']==0
assert exit['unfiltered'] is True
counts=result['totals'];report_count=len(result['reports']);inputs=json.loads((full/'source-inputs.json').read_text(encoding='utf-8-sig'))
timestamp=datetime.datetime.fromisoformat(exit['finishedAt'].replace('Z','+00:00')).astimezone(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y-%m-%d %H:%M')
p=root/'docs/08-总开发实现计划.md';s=p.read_text(encoding='utf-8')
s=s.replace('evidence/consultation-product-transport-20261006/full-final/full-round02-result.json','evidence/consultation-product-bundle-20261006/full-final/full-round02-result.json',1)
s=s.replace('evidence/consultation-product-transport-20261006/full-final/full-round02-exit.json','evidence/consultation-product-bundle-20261006/full-final/full-round02-exit.json',1)
old='- 2026-10-06 12:05（Asia/Shanghai）最新未筛选全后端Maven全部Reactor模块成功：'
pos=s.index(old);end=s.index('\n',pos)
s=s[:pos]+f'- {timestamp}（Asia/Shanghai）最新未筛选全后端Maven全部Reactor模块成功：{counts["tests"]}项、{counts["failures"]}失败、{counts["errors"]}错误、{counts["skipped"]}条件跳过，退出0；{report_count}份当次XML、{len(inputs["files"])}项后端/构建输入SHA前后一致。本轮正式咨询发布包实际JAR字节校验及227项前端/构建输入稳定性分别留证，不把后端回归当三域浏览器/Worker/模型证明。原12:05的1577/0/0/40及更早1556/0/160/40、1543、1541、1509等原件保留；新构建后的TEST_EVIDENCE_STALE和隔离实例首次准备6001失败也保留。条件跳过不代表验证或业务验收。'+s[end:]
s=s.replace('旧1577绿色不能覆盖本轮新构建修改','本轮新构建修改已由最新未筛选回归独立覆盖，但工程绿色不解除现时来源/正式权限或实际部署门禁')
lines=s.splitlines()
for i,l in enumerate(lines):
 if l.startswith('| FIX-CON-PRODUCT-BUNDLE-01 |'):
  l=l.replace('| 进行中 |','| 已完成 |',1).replace('| - | [限定技术D]','| 2026-10-06 | [限定技术D]',1)
  l=l.replace('历史v750 JAR不包含当前Runtime链；现有bundle-frontend仅index，正式路由所需consultation.html未被打包；不复用旧封存覆盖脚本当当前发布来源',f'[本轮工程原件](evidence/consultation-product-bundle-20261006/verification.md)：显式咨询构建及独立类目录已实现；12项资产边界测试、81项前端测试、通用/咨询重复构建、实际JAR集合/SHA及混包明确拒绝已验证；最新未筛选{counts["tests"]}/0/0/{counts["skipped"]}退出0。当前源码正式JAR已形成，未覆盖旧受管发布配置或启动旧API；真实链门禁见父任务，不把构建当部署/业务验收')
  lines[i]=l
p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
v=base/'verification.md';v.write_text(v.read_text(encoding='utf-8')+f'\n## 最终工程结果原件\n\n`full-final/full-round02-exit.json`与`full-final/full-round02-result.json`来自同次未筛选Maven：{counts["tests"]}项 / {counts["failures"]}失败 / {counts["errors"]}错误 / {counts["skipped"]}条件跳过，{report_count}份实际XML，退出0。{len(inputs["files"])}项后端/构建输入SHA前后一致；完整跳过/排除库存逐项保留。所有Reactor成功只证明工程范围，不能冒正式运行和三域业务任务完成。\n',encoding='utf-8')
print(timestamp,counts,report_count,'master updated from actual originals')
