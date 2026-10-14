from pathlib import Path
p=Path('docs/adr/single-database-recovery-assessment-20261007.md')
with p.open('a',encoding='utf-8') as f:
 f.write('''

### 9.21 当前source原生保护阶段操作D（2026-10-10 09:06北京）

沿9.20阶段准入澄清，仅细化既有`IMP-SINGLE-DB-NATIVE-FENCE-01`实际source snapshot，不新授恢复/业务/身份/C权限。责任Codex当前主会话，依据原resource-approval01管理员完整停写原生保护及原窗口、conditional-scope-approval02、45真实优先级原件。当前唯一source精确f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f；source-snapshot-config46.json已通过原native配置和现时准入validator，source-maintenance-responsibility46.json明确全部选定源部署/admin/文件维护的受托独占责任；source-writer-registry46.json登记三原应用及唯一确切维护锁。不以当前运行清单推断已停止，不以host连接数0推断全局围栏。

原件均在evidence/single-database-installation-20261009/approved-execution/mainline-execution46：09:02前当前资源/三文件根/maintenance进程及六schema真实库存exit0；与45库存逐字段0差异，15账号/674对象授权及三原应用+DB+两透传+工具七角色PID/StartedAt/image完全未变。两透传及ff均readonly、仅非秘密只读挂载、无DB秘密环境字段；两透传不成为直接库/文件写入者。source仅dmserver，ff仅sleep；host已建立连接只作时点旁证。完整职责由主会话主动承担并冻结本次所有其它维护/Grant/DDL/迁移/资料及并发入口，不靠旁证推断权利。当前业务Input/Source/普通身份仍空，仅安装锁种子/维护/已批准client登记数据；必要业务文件为空，无TLS/master/凭据进入封包。

实施合同：先原三ID/current labels/image/no-restart与source/窗口硬核验，逐一stop --time 30，并inspect确认Running=false/Restarting=false/PID0后才snapshot。任何未知结果先对账，不盲重发。保留逐步原始事件；原CLI取得同连接SHARE围栏、逐次assertHeld和现时期限、六schema完整前后库存相等；120秒/128MiB/单文件/native加密及AES-GCM原约束不放宽。wrapper/native密钥仅消费45原DPAPI引用，临时env使用后恢复，公开日志无值。只执行一次snapshot CLI，新唯一single-final-source-protection46.xjsnap和source-snapshot-receipt46.json不覆盖，失败保留原native/包/锁/原始结果，不自动重试或删除。

退出：source DM8本阶段始终不停止、不创建/启动restore，不会两库在线。snapshot自然终止后，确认新维护锁已不存在且ff无活动java维护进程，才依次启动本次已明确停下的原三ID，image/原运行配置不改，不重包、不bootstrap、不切连接；未知状态先inspect，不处理未知进程。严格咨询CA/SAN/server pin GET、原三应用实际Running/PID及最新完整库存与停止前对账，记录任何新审计变化，不以旧包覆盖。未能确认围栏释放/运行恢复则保留现场并报告，不假称回滚或整体通过。必须在原2026-10-10 18:00北京截止内退出。

限定D结论：本节及确切配置/职责/当前数据闭包使source snapshot阶段具备执行准入；真实停止/SHARE/native导出/封包/返回原应用仍分别待执行输出。独立restore的真实ID/empty库存/currentDecision/包SHA在其阶段闭合；raw LAST_CHANGE敏感性7项离线反例只证明比较器边界，不证明实际恢复等价或失败。已安装达梦手册原字节与页码/SHA留存；尚未获得LAST_CHANGE及SREF目录字段可忽略的权威语义，现比较器完全不改、未知差异严格拒绝。source snapshot不是独立可恢复、A/B/C或业务验收退出。
''')
p=Path('docs/08-总开发实现计划.md');s=p.read_text(encoding='utf-8');s=s.replace('计划版本：V10.98','计划版本：V10.99',1)
lines=s.splitlines()
for n,l in enumerate(lines):
 if l.startswith('| PREP-SINGLE-DB-FINAL-PROTECTION-01 |'):
  lines[n]=l[:-2]+' 46接续：当前09:02完整库存exit0，与45逐字段0差异/15账号/674权限；七角色原PID/StartedAt/image未变、三文件根仍空。source阶段具名独占职责/三原writer及锁/现时配置已按[限定操作D§9.21](adr/single-database-recovery-assessment-20261007.md)闭合；执行source保护后才谈独立恢复。原始目录敏感性7项离线通过，不改比较器或冒实际restore失败。[当前对账](evidence/single-database-installation-20261009/approved-execution/mainline-execution46/mainline-readonly-reconciliation46.json)。 |'
 if l.startswith('| IMP-SINGLE-DB-NATIVE-FENCE-01 |'):
  lines[n]=l[:-2]+' 46当前源阶段继续实际验证：原完整停写保护批准、具名维护责任/三原应用精确ID/新锁和受管引用均已绑定，按D9.21一次原生snapshot并可逆返回原三应用；原生/恢复/业务状态分别取证，不提前关闭本项或A/B/C。 |'
 if l.startswith('| VER-SINGLE-DB-CON-NORMAL-INPUT-01 |'):
  lines[n]=l[:-2]+' 46实际09:02严格无客户端证书GET：正常页面/本域Issuer/login均200，原CA/name/server pin通过，正常username/password表单存在；不冒浏览器UI或人类确认，未登记/激活/Task/Grant。[原始回执](evidence/single-database-installation-20261009/approved-execution/mainline-execution46/consultation-public-tls46-result.json)。 |'
p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
