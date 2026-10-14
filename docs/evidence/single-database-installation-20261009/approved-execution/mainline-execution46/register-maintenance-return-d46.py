from pathlib import Path
p=Path('docs/adr/single-database-recovery-assessment-20261007.md')
with p.open('a',encoding='utf-8') as f:
 f.write('''

### 9.22 原维护暂停后精确网络及透传返回窄修D（2026-10-10）

`FIX-SINGLE-DB-MAINTENANCE-RETURN-01`依赖既有单库工程R/D，仍在原受托source保护操作中恢复原资源；不新增容器/网络/权限或改发行/透传配置。46真实第一次source snapshot在prepareContainerFence的网络名守卫拒绝(EXACT_CONTAINER_FENCE_ALIAS_REQUIRED)，发生在class读取/持SHARE/native导出前；源码边界与原safe CLI日志可定位，未产生package/receipt。维护锁已消失、ff无java，三应用原ID/image已返回，但Docker动态地址变为enforcement .4/adjudication .5/consultation .6，而原地址是consultation .4/透传679 .5/adjudication .6/透传1bf .7/enforcement .8。两原透传在原应用停止期间因UnknownHostException失败关闭(PID0/exit1)，不是运行成功；第一次正常TLS返回GET因透传已停拒绝连接，保留原始失败，不冒整体运行返回。证据source-protection-execution46-result/events、consultation-return-state46、application-return-network46及source原始资源发现。

同Task详细合同：主会话暂停其它业务/维护写入，核当前source/ff不变、原三应用确切ID/image/原网络、两透传确切exit1/PID0和原readonly两挂载SHA不变，核原窗口/预算。只按确切原ID可逆停止三当前应用并inspect PID0，无删除/重包/改网络/改配置。依据原网络布局依序启动原consultation9b→原consultation透传679→原adjudication4f→原adjudication透传1bf→原enforcemente0；每次立即对真实唯一Internal网络的原IP和原image核验，应用保持原no-restart/原存储及env，透传消费原目标ID/DNS/IP及原两SHA不变。IP分配是待验证输出，不推定顺序必然成功；任何未知或IP/字节/预算漂移即停止本分支、留现场，不盲重发或改targetIp绕守卫。应用需在本次StartedAt之后真实Started日志及当前Running证明确认后才启动其透传；有界只读就绪检查，不签业务请求或新身份。

退出：七原角色同64ID/同image/同文件及原IP、≤7GiB/4CPU、source未停且仅1在线DM8；两透传实际Running/PID和严格原咨询TLS/Issuer/login200及最新完整库存回读；任何未知/new-write不能回灌旧包。该窄修只恢复原允许运行角色，不修publisher产品守卫、扩大egress或改证书。全部前后失败保留；此后再单独修DNSNames守卫并重新核实际source保护，不能用本次返回当原生保护/独立恢复/A/B/C或业务验收通过。
''')
p=Path('docs/08-总开发实现计划.md');s=p.read_text(encoding='utf-8');lines=s.splitlines()
for n,l in enumerate(lines):
 if l.startswith('| IMP-SINGLE-DB-NATIVE-FENCE-01 |'):
  lines[n]=l[:-2]+' 46第一次真实三writer停写已证，但CLI在SHARE/native前以EXACT_CONTAINER_FENCE_ALIAS_REQUIRED拒绝，未生成包/receipt，未重试。源库未停/锁释放，三应用同ID/image已启动；两透传因停写时DNS缺失失败关闭、动态IP轮换导致完整返回尚未通过，立即进入FIX-SINGLE-DB-MAINTENANCE-RETURN-01，原失败及TLS拒绝保留。 |'
idx=next(i for i,l in enumerate(lines) if l.startswith('| PREP-SINGLE-DB-FINAL-PROTECTION-01 |'))
lines.insert(idx+1,'| FIX-SINGLE-DB-MAINTENANCE-RETURN-01 | 单库维护暂停后原动态网络及原两透传精确返回 | P0/M0.6 | 进行中 | P0 | ARC-AGENT-SINGLE-DB-ENGINEERING-D-01 | IMP-AGENT-SINGLE-DB-LIFECYCLE-SCRIPTS-01 | 否 | 原三应用/两已批准透传/同原字节及IP/原预算窗口；未知不重试、不改targetIp | - | [窄修D§9.22](adr/single-database-recovery-assessment-20261007.md)；46实际失败原件 | 主会话独占；先恢复原运行，退出七原角色同ID/image/原IP、严格TLS和完整库存一致 | 已登记源snapshot拒绝及透传UnknownHost/PID0/exit1、原三应用动态IP轮换；只进行原资源精确顺序返回，不新建/重包/改业务/原网络或配置，不假称原生导出成功。 |')
p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
