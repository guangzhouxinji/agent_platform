from pathlib import Path
import json
r=Path('docs/evidence/single-database-installation-20261009/approved-execution/mainline-execution46')
equal=json.loads((r/'post-source02-inventory-equality46.json').read_text(encoding='utf-8-sig'))
tls=json.loads((r/'consultation-public-tls46-source02-post-result.json').read_text(encoding='utf-8-sig'))
assert equal['wholeInventoryEqual'] and equal['accounts']==15 and equal['objectGrants']==674
assert len(tls['requests'])==3 and all(x['status']==200 and x['strictTlsAccepted'] for x in tls['requests'])
p=Path('docs/08-总开发实现计划.md');s=p.read_text(encoding='utf-8');lines=s.splitlines()
for n,l in enumerate(lines):
 if l.startswith('| IMP-SINGLE-DB-NATIVE-FENCE-01 |') or l.startswith('| PREP-SINGLE-DB-FINAL-PROTECTION-01 |'):
  lines[n]=l[:-2]+' 最新失败后退出实证：严格咨询CA/SAN/server pin三GET均200；09:24完整六schema/15账号/674权限回读exit0，摘要43763a66…与两次暂停前完全一致，未回灌/重放迁移或授权；只是原运行返回，source snapshot/restore仍未通过。[末次TLS](evidence/single-database-installation-20261009/approved-execution/mainline-execution46/consultation-public-tls46-source02-post-result.json)、[末次库存相等](evidence/single-database-installation-20261009/approved-execution/mainline-execution46/post-source02-inventory-equality46.json)。 |'
p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
p=Path('docs/adr/single-database-recovery-assessment-20261007.md')
with p.open('a',encoding='utf-8') as f:
 f.write('\n9.24末次退出实证：09:24完整六schema真实库存exit0，15账号/674对象权限、完整摘要43763a66…与两次暂停前完全一致；严格咨询原CA/name/server pin三GET200。未回灌历史包/权限、未重放迁移、未创建身份/Task/Grant/资料，模型/计算/矩阵0。仅证明当前原运行及数据返回一致，不改变failed source snapshot/native restore或专业/验收状态。原件链接见主计划46末次TLS/库存。\n')
