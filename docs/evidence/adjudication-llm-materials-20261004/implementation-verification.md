# 03A限定续实施证据（2026-10-04）

唯一任务状态仍在docs/08；本页只记录实施、验证、失败和未执行范围。沿现行ADR§10已通过03A D继续，没有重开全项目规划、没有重新等待用途/供应商/费用批准。

## 本轮实际实施

新增`MaterialModelBackupDm8IT`、`scripts/verify-adjudication-model-backup.ps1`及只允许5369独立恢复库的`CreateAdjudicationRecoverySchema.java`。没有修改业务领域内核/API/页面、没有扩大业务授权、没有改共享代码。ADR§10补充了既有备份合同的具体执行：原生dexp/dimp，仅ADJLLM20261003两张模型表，导出另加密，逐run密钥与数据库备份分离；验证后删除本轮新副本，不删除旧案/原密钥、不延长30天。

[只读现时许可核查](current-permission-request-audit.md)与[runtime原件](runtime-read-only-audit.txt)：现存唯一本轮账本calls=1/input687/output1149/costMicros10566，唯一approval_key已绑定，不冻结。当前已登记维护主体deployment-maintainer-20260928/ORG-CON-PRODUCT/ACTIVE@1，不能为project-adjudication代签。本机已登记正式CONPROD无ADJ2/ADJ3表、无ADJUDICATION grant，没有取得本案可核实当前业务主体/受控凭据。核查不是用户登录认证，也不声称全局不存在其他主体。

首个正式整理请求的路径、材料确切SHA、正常维护入口和当前headVersion读取动作已列在上述许可核查。由于缺少当前主体与对应有权维护入口，正式HTTP整理请求NOT_SENT；新增真实供应商调用0，不借01/02身份，不拼接H2/替代port证据冒整段。

## 独立原生备份导入实证

[native-recovery-02摘要](native-recovery-02/native-dm8-recovery-summary.json)、[原生备份摘要](native-recovery-02/backup-manifest.json)、[真实测试](native-recovery-02/tests.log)、[XML](native-recovery-02/TEST-MaterialModelBackupDm8IT.xml)、[清理摘要](native-recovery-02/cleanup-summary.json)。

- 源库5301专属ADJLLM20261003未停止；只读导出V090/V091两张旁表到原生加密备份。没有恢复源库/权威库，没有触碰执法5444或其5311/5312事故库。
- 新建仅本轮独立DM8实例5369，dimp真实导入，不用内存/H2恢复、不重新生成零预算。同案原run f5448bc3-0709-471f-82f1-95c2a52be6a4及原密文SHA保持一致。
- 候选16条逐项原文/定位重新验证；重新建存储对象读取一致；恢复额度1/687/1149/10566与源一致，换case不能绕过同一approval唯一绑定。
- 备份不包含逐run密钥；使用分离原密钥验证读回。空密钥目录拒读，隔离恢复库损坏密文拒读且不影响源；恢复库到期时钟拒读、先清理过期行。未来时钟只作用于新恢复库和空测试密钥目录，未对原密钥运行未来到期清理。
- 截止仍2026-11-01T16:00:00Z（北京时间11月2日00时），不从导入重算。测试结束，本轮原生备份源临时文件、本地新备份、新恢复实例已删除；保留的只是SHA/计数/时间和测试报告，不留业务副本。原run密钥哈希内部前后相同、源账本/密文不变。
- 这是实际旁表恢复组件实证，不是业务主体、正式API、当前源权限恢复或专业验收证明。

## 回归及失败：不能称全仓通过

| 运行 | 实际结果 | 范围/限制 |
|---|---|---|
| 本轮修改前verify-all | 退出0 | 47需求文件/41契约基线一致 |
| native-recovery-01前置 | 退出1，未导出/未外呼 | 新测试源导致TEST_EVIDENCE_STALE；原件保留 |
| full-backend-regression-01 | 退出1 | 隔离构建未为consultation测试指定真实workspace，捕获数据路径无法解析；保留失败log |
| full-backend-regression-02 | 退出1 | 修正JVM user.dir后，334套/1130项、0断言失败/20错误/119条件跳过；不能称完整回归通过 |
| components-03 | 27项通过 | 当前引用12项+预算15项；不是全仓 |
| native-recovery-02 | 1项真实原生DM8集成通过 | 多个恢复断言；无供应商调用 |
| native-recovery-02前后verify-all | 均退出0 | 结构/基线验证，不抵消完整回归失败 |

[当前完整尝试报告索引](full-backend-02-report-index.json)及归档XML保留SHA/mtime。20错误来自缺少ENF运行输入/环境（ENF_ACCEPTANCE_BUSINESS_URL、ENF_DOCUMENT_RULE_*以及DM8_JDBC_URL/JOB_PACK_DM8_URL等）；未伪造输入、未启动执法产品、未连接事故库满足它们，也未把这些测试改成跳过。119条件跳过是本轮实际结果，不能沿用历史39。本轮按suite名统计CON 83套/335项/0错误/7跳过，ENF及具名组件105套/312项/20错误/53跳过；名字归属统计不是所有跨域测试归属，ENF当前回归不能标无回归。

原先2026-10-03的57专项/真实1次组件/旧01/02浏览器/1133后端39跳过保持历史原件，不冒此次新证据。本轮未再跑浏览器，因为没有页面或01/02业务代码变更；03C未开始。

实际27项专项报告只在同源码快照核验后同步到本地对应surefire报告，保留旧报告及新报告真实mtime/SHA：[同步证据](canonical-targeted-report-copy.json)。完整失败报告保留独立归档，不拿专项消除完整回归结论、不调整检查器或伪造报告时间。[源码窗口](snapshot-source-drift.json)截至核查未变化。

## 尚缺退出实证

当前project-adjudication有权维护主体及本案执行HUMAN账号的受控入口未落实。需要该组织真实维护人正常mTLS操作及执行人正常OAuth2/PKCE登录，不允许借咨询维护身份、不允许数据库管理员扮业务人。随后才能做一轮最小真实模型HTTP同案调用、查询、实际服务重启和通过正常产品命令的撤权/撤源/CAS/跨案/失败不覆盖验证。历史用途/费用批准仍有效，不重复询问；03A保持进行中，03B/C/D不放行。

本轮总计划更新前verify-before-plan-update.log实际0；更新后以verify-after-plan-update.log真实结果为准。所有失败原件保留，结构验证不代表专业业务或完整回归通过。
