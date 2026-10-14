from pathlib import Path
p=Path('docs/adr/single-database-recovery-assessment-20261007.md')
with p.open('a',encoding='utf-8') as f:
 f.write('''

### 9.23 Docker实际DNSNames精确围栏名守卫兼容窄修D（2026-10-10）

`FIX-SINGLE-DB-FENCE-DNS-NAMES-01`依赖既有单库工程R/D；技术责任Codex。46真实目标在确切Internal networkId 047a31…ad65下Aliases=null、DNSNames=[原source完整name,原短ID]；原JDBC库存通过其既有Name合同，但native围栏只读Aliases而在执行class/S HARE/native前拒绝。当前Docker原始网络结果及失败日志为具体输入，不把运行失败转成业务规则。官方Moby api/types/network/endpoint.go公开EndpointSettings定义亦包含DNSNames（本次只作为字段模型来源，不宣称运行daemon与master同版）。

同Task详细实现：仅single-database-native-fence-container.mjs中的确切networkId下DNS名匹配与其专测。若该网络存在DNSNames字段，只接受非空纯字符串array中精确同jdbc alias；空/null/非法类型/错误名必须拒绝，不允许由Aliases兜底遮盖。只有DNSNames字段完全缺失的旧检查结果才沿既有Aliases精确数组合同。禁止接受db.Name全局匹配、跨networkId、子串/IP/模糊名。其余actual64ID/Running/owner/readonly/nonroot/CPU/memory/类与Java源SHA/同连接SHARE、现时批准/完整writer/维护锁保持完全不变；不改JDBC、部署、Docker网络、DB或恢复比较器。

退出：真实DNSNames/Aliases=null正例及DNSNames缺失旧Aliases正例；空/null/非法值/mixed/wrong name/其它network命中/当前DNS与旧Aliases冲突反例全拒；原专测回归。随后只读prepareContainerFence对原source/ff/network/class真实检查（不执行返回invocation，不取JDBC锁或native导出），必须通过才可在新明确操作回执下再次source snapshot。第一次明确在原生前失败不当未知dexp重放，原日志/锁/所有失败不覆盖。当前父A/B/C及独立restore仍不关闭；操作返回必须先完成9.22原精确IP/两透传原字节与严格TLS恢复，不因新修源改变既有咨询25/裁决39/执法44发行。
''')
p=Path('docs/08-总开发实现计划.md');s=p.read_text(encoding='utf-8');lines=s.splitlines();idx=next(i for i,l in enumerate(lines) if l.startswith('| FIX-SINGLE-DB-MAINTENANCE-RETURN-01 |'))
lines.insert(idx+1,'| FIX-SINGLE-DB-FENCE-DNS-NAMES-01 | 实际Docker DNSNames精确网络围栏名兼容窄修 | P0/M0.6 | 进行中 | P0 | ARC-AGENT-SINGLE-DB-ENGINEERING-D-01 | IMP-AGENT-SINGLE-DB-LIFECYCLE-SCRIPTS-01 | 否 | 原native source/task/network/class/资源与完整writer约束不变；只兼容实际DNSNames字段 | - | [窄修D§9.23](adr/single-database-recovery-assessment-20261007.md)及46实际alias拒绝原件 | 主会话独占两个围栏源码/专测；退出正反例及原source/ff实际只读prepare通过，不执行JDBC/native | 已登记真实Aliases=null/DNSNames含原完整name、原守卫仅Aliases的前native失败；现在先修精确字段匹配与专项，原失败不覆盖，不修改业务或恢复判定。 |')
p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
