from pathlib import Path
p=Path('docs/adr/single-database-recovery-assessment-20261007.md')
with p.open('a',encoding='utf-8') as f:
 f.write('''

9.24实际执行补证（09:21北京）：两原透传及三原应用按精确ID停止并inspect PID0；新一次snapshot CLI退出1，原始safe code=NATIVE_PROCESS_REFUSED_OR_TIMED_OUT，未生成有效package/receipt。通用代码没有绑定失败命令阶段，不能从时间或/tmp无single-native文件推断唯一根因或保证native未开始。当前不再发dexp；完整事件及源现场保留，未启动restore/停source/触及旧环境。原维护锁已释放、ff无java，source仅原dmserver；七原角色已按原IP/image/两文件SHA顺序返回，预算7247757312bytes/3850000000NanoCPU。只读native前提实际证实timeout/dexp存在、/tmp属1000可写，不证明native完成；原阶段仍失败未退出。

当前下一安全工作为`ARC-SINGLE-DB-NATIVE-FAILURE-DIAGNOSTIC-01`：主会话先冻结精确source/ff/阶段及原生子进程证据D，分别标识target/resource/writer检查、JDBC围栏获取、围栏前后库存、native exec/read、释放；明确成功/非零/超时/未知的类型、不可重复副作用边界及有限退出。只允许稳定阶段enum、实际时间/exit或unknown、字节计数/安全SHA/确切非秘密目标引用，不记录argv/password/native key/环境值、native/JDBC原始正文或未经筛选exception。未知不能升级为成功或无副作用；原完整writer/资料许可/锁/现时决定/128MiB/120秒及恢复比较器不删不放宽。D退出须专项秘密反例、首次确切子进程自然终止/有限处置、不杀未知进程、防重复及原始失败保存的证据方案；未过此同Task详细D不改共享runtime/native/CLI。未来实现task由唯一主计划控制，D未齐不得写或执行新保护命令。
''')
p=Path('docs/08-总开发实现计划.md');s=p.read_text(encoding='utf-8');lines=s.splitlines()
for n,l in enumerate(lines):
 if l.startswith('| IMP-SINGLE-DB-NATIVE-FENCE-01 |'):
  lines[n]=l[:-2]+' 46-02在精确DNS修复/实际prepare/原完整返回后进行了独立新一次CLI；三writer与两透传实际停止，但返回NATIVE_PROCESS_REFUSED_OR_TIMED_OUT且无有效封包/receipt。失败命令阶段未明，不冒唯一根因、未native或可恢复，不再发dexp。锁释放/七原角色及原IP/image/预算真实返回；随后进入安全阶段诊断D，不盲重试。[实际执行](evidence/single-database-installation-20261009/approved-execution/mainline-execution46/source-protection-execution46-02-result.json)。 |'
 if l.startswith('| PREP-SINGLE-DB-FINAL-PROTECTION-01 |'):
  lines[n]=l[:-2]+' source阶段详细配置/完整职责已闭合并实际调用；保护尚失败，无现时有效package或独立restore证据，不能关闭A/B/C。原生目录比较器保持严格，当前下步先精确命令阶段诊断D。 |'
 if l.startswith('| VER-SINGLE-DB-CON-NORMAL-INPUT-01 |'):
  lines[n]=l[:-2]+' 两次维护后的实际正常入口/DB一致性由本轮返回原件单列，不将运维pause/resume当普通身份或原文确认。真实实际账号使用/凭据控制/选区确认仍最小人类事实差异，不重索已批准两材料用途或技术UUID。 |'
idx=next(i for i,l in enumerate(lines) if l.startswith('| FIX-SINGLE-DB-FENCE-DNS-NAMES-01 |'))
lines.insert(idx+1,'| ARC-SINGLE-DB-NATIVE-FAILURE-DIAGNOSTIC-01 | 单库真实保护失败命令阶段与秘密安全诊断详细D | P0/M0.6 | 待开始 | P0 | FIX-SINGLE-DB-FENCE-DNS-NAMES-01、ARC-AGENT-SINGLE-DB-ENGINEERING-D-01 | IMP-AGENT-SINGLE-DB-LIFECYCLE-SCRIPTS-01 | 否 | 46-02真实通用命令拒绝/超时原件、原数据/运行已返回；只诊断技术失败，不放宽许可/围栏/超时/容量/恢复等价 | - | [阶段设计准则及实际失败](adr/single-database-recovery-assessment-20261007.md)；细化D尚待退出 | 主会话独占；退出为稳定阶段类型、确切子进程自然终止/未知防重发、秘密不入日志和原始失败保留的详细证据方案 | 当前下一安全可接续任务；owner=Codex受托技术责任。阶段未知不是新增人类授权缺口；D未齐不改runtime/native/CLI或发dexp，不杀未知进程/删锁或失败现场。 |')
lines.insert(idx+2,'| FIX-SINGLE-DB-NATIVE-FAILED-COMMAND-DIAGNOSTIC-01 | 单库保护命令阶段安全证据实现与专项 | P0/M0.6 | 待开始 | P0 | ARC-SINGLE-DB-NATIVE-FAILURE-DIAGNOSTIC-01 | IMP-AGENT-SINGLE-DB-LIFECYCLE-SCRIPTS-01 | 否 | 同Task详细诊断D退出后才能实施；保持原批准/数据/锁/失败边界 | - | 依赖上一详细D，当前不宣称D通过 | 主会话独占共享适配/专项；退出为正反例、无秘密和真实阶段证据，未明确现场不重发native | 仅登记稳定后继ID/依赖/退出；未开始、未修改诊断代码，不拿普通mock当真实DM8保护完成。 |')
p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
