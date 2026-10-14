from pathlib import Path
import json,hashlib,datetime
r=Path('docs/evidence/single-database-installation-20261009/approved-execution/mainline-execution46')
preflight=json.loads((r/'actual-fence-metadata-preflight46.json').read_text(encoding='utf-8-sig'))
(r/'fence-dns46-test-result.json').write_text(json.dumps({'format':'LIMITED_ENGINEERING_VERIFICATION/v1','taskId':'FIX-SINGLE-DB-FENCE-DNS-NAMES-01','command':'node --test scripts/lib/single-database-native-fence-container.test.mjs scripts/lib/single-database-lifecycle-native.test.mjs','exitCode':0,'tests':136,'pass':136,'fail':0,'skip':0,'rawLog':'fence-dns46-tests.log','rawLogSha256':hashlib.sha256((r/'fence-dns46-tests.log').read_bytes()).hexdigest(),'sourceSha256':preflight['sourceSha256'],'actualMetadataPreflight':'actual-fence-metadata-preflight46.json','actualShareFenceOrExportClaimed':False},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
p=Path('docs/08-总开发实现计划.md');s=p.read_text(encoding='utf-8');lines=s.splitlines()
for n,l in enumerate(lines):
 if l.startswith('| FIX-SINGLE-DB-MAINTENANCE-RETURN-01 |'):
  l=l.replace('| 进行中 |','| 已完成 |',1).replace('| - | [窄修D§9.22]','| 2026-10-10（Asia/Shanghai） | [窄修D§9.22]',1)
  lines[n]=l[:-2]+' 限定恢复VERIFIED：七原角色同ID/image/原Internal IP及6.75GiB/3.85CPU已真实恢复；两透传原两SHA/正常应用当前Started实证，09:15严格咨询三GET200和完整六schema库存exit0/摘要与停止前完全一致。[返回原件](evidence/single-database-installation-20261009/approved-execution/mainline-execution46/maintenance-network-return46-result.json)、[TLS](evidence/single-database-installation-20261009/approved-execution/mainline-execution46/consultation-public-tls46-return02-result.json)、[库存相等](evidence/single-database-installation-20261009/approved-execution/mainline-execution46/post-network-return-inventory-equality46.json)；只关闭本次原运行返回窄修，不关闭保护/独立恢复/业务或A/B/C。 |'
 if l.startswith('| FIX-SINGLE-DB-FENCE-DNS-NAMES-01 |'):
  l=l.replace('| 进行中 |','| 已完成 |',1).replace('| - | [窄修D§9.23]','| 2026-10-10（Asia/Shanghai） | [窄修D§9.23]',1)
  lines[n]=l[:-2]+' 限定IMPLEMENTED/VERIFIED：只改精确网络DNSNames优先、字段缺失才旧Aliases的守卫；136/136专项(0失败/0skip)，原source/ff/network/class实际prepare的两只读命令通过，返回invocation未执行。[工程回执](evidence/single-database-installation-20261009/approved-execution/mainline-execution46/fence-dns46-test-result.json)、[实际只读preflight](evidence/single-database-installation-20261009/approved-execution/mainline-execution46/actual-fence-metadata-preflight46.json)；SHARE/native/独立恢复仍分开验证，原失败不覆盖。 |'
p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
p=Path('docs/adr/single-database-recovery-assessment-20261007.md')
with p.open('a',encoding='utf-8') as f:
 f.write('''

9.22/9.23限定退出补证：09:15已恢复七原角色同ID/image/原Internal IP和原两透传JAR/config SHA，预算6.75GiB/3.85CPU；严格咨询三GET200与完整六schema/15账号/674权限库存回读exit0、摘要43763a66…与暂停前完全相同。DNS守卫窄修136/136工程专项及原source/ff/class两条真实只读prepare通过，未执行返回invocation。只完成这两技术窄项，第一次原生前失败/TLS拒绝/IP轮换原件完整留证，真实source原生保护及独立恢复仍待分别实施。

### 9.24 明确前native失败后的新source保护安排（2026-10-10）

既有IMP-SINGLE-DB-NATIVE-FENCE-01接续9.21，原批准/window/目标/六schema/15账号/受管引用不变。第一次46已明确在native之前守卫拒绝，Java/JDBC围栏/native均未开始，package/receipt均不存在，锁释放与全原运行已按9.22实际核验，不能冒作未知native重放。9.23实际只读准备通过后，允许新确切一次CLI尝试，独立新config/output/receipt/events(46-02)，保持原失败原件不覆盖、无自动重试。

本次暂停先按确切ID停止原两透传并inspect PID0，再停止三原应用并inspect PID0，避免其在已知DNS临时缺失期继续解析；这只是既有原角色可逆运维不新设资源。source保持在线，ff只消费原native/同连接SHARE适配。snapshot自然结束后锁消失/ff无java才返回：按原consultation→consultation透传→adjudication→adjudication透传→enforcement顺序，逐项原IP/image/两非秘密SHA、实际当前Started及预算严格核验；未知或IP差异只留现场，不改targetIp或重包。完整最新库存与暂停前摘要、严格TLS与原账号/权限核验再退出。独立restore仍0，原dump/key期限10-10 18:00北京不延长；保护失败/未知只保留原始现场，实际native已开始时不再发dexp。新实际source成功与独立恢复通过/父A/B/C退出仍分别记录。
''')
