# 历史结果隔离专项交接证据（2026-10-07）

任务来源：主计划 FIX-CON-HISTORICAL-RESULT-ISOLATION-01、docs/adr/consultation-historical-result-isolation-20261007.md，以及本轮限定文件/禁止运行共享验证的明确授权。本文只记本专项实现与实测证据，不另维护任务状态或改变 R/D/验收状态。主计划由主会话更新。

## 交付边界

- ProductHistoricalResultIsolation.java：只读原始结果回执、成员/字段事实，先校验持久化摘要，再使用历史 source/contract/decision/epoch/field/model 与执行时间判定构建见证；不取当前版本替代历史；缺精确见证、未来见证、冲突见证均隔离。可闭合见证还校验历史模型版本绑定、向量原字节摘要/维度/归一化、文献数及构建摘要。没有固定三个业务 ID。
- ProductPatentSearchPages.java：在调用方当前 Task/Source 检查后的 prepare/read 边界拒绝，位于 title/field citation 原件读取之前。ProductPatentSelection.select/read、ProductRetrievalDialogue 新解释及历史解释读取已通过 searches.read 共用此边界，未修改这些类。
- ProductPatentSearchReceipts.java：read 为产品消费/重放入口，调用方原有当前 worker/source 权限检查仍先执行；readOriginalEvidence 仅给隔离/严格恢复读取原始证据，避免递归，不是对外开关。原回执不改。
- ProductComparisonDocuments.java：完成当前 DISPLAY/COMPARISON 权限检查后，对有 resultSetId 的引用拒绝隔离结果，包含 historical=true 的读取。比较准备、已存输入的结果/报告读取和委派 Worker 文献重载已有调用此方法。独立精确原件引用无 resultSetId 时不被一律禁止，仍走既有权限/原件校验。
- ProductPatentSearchRecoveryVerifier.java、ProductModelTurnRecoveryVerifier.java：严格 verify/verifyTurn 仍失败；另增只读 report，不把缺见证变为严格成功。只捕获专用 Unverified，不吞原有非法状态/原件/摘要错误。缺见证时报告 overallIntegrityVerified=false、fullOriginalValidationCompleted=false；不能把这个部分报告当完整恢复验收。模型恢复报告接线已编译，未作真实留存/模型整链验证。
- 机械错误 HISTORICAL_RESULT_UNVERIFIED 仅返回稳定原因，不返回未授权正文。只读报告包含结果/tuple/字段/缺项、具体选择及模型回答依赖 ID；比较/续跑以受影响消费路径说明，未枚举所有 comparison/input 间接依赖 ID。

## 实测（不是真实历史业务验收）

- javac --release 21：exit=0。JDK 路径、完整编译参数在 javac.args；只输出本目录 classes，没有 Maven/共享 target 写入。警告为 Jupiter 工程 runner 使用既有过时 API，不是编译失败。
- JUnit Jupiter 5.10.5 独立 engine：15 successfulTests，0 failedDescriptors，0 skippedTests，exit=0；逐项见 junit.log。
- classpath-reference.txt 复用 consultation-start-mtls-20261007/build-05 的已保存 javac 参数/冻结 classes 与原依赖 jar；未下载、复制或覆盖共享依赖。
- 覆盖精确历史参数断言、其它 admission 不替代、晚于执行的构建、冲突及同事实重复见证、闭合的无关 tuple 可消费、legacy 未记录 model 不从唯一/当前构建猜测、坏结果摘要、坏构建摘要、坏向量及不符构建摘要硬拒绝；产品回执重放与严格检索恢复入口保持红灯；独立报告不吞坏摘要。
- ProductHistoricalResultIsolationFixture 是断开真实 DataSource 的工程自有 JDBC 行适配器：一条自有候选/字段事实/模型/向量及自有依赖，任意 UUID。所有未建模查询与写入均报错。不是三份实际历史结果，不是 DM8 SQL 正确性或生产集成证据。
- engineering-own-original.bin 是工程自有记录副本；原字节留存、读取副本及按相同工程事实恢复适配器的字节对比成功，original/recovered SHA 相等，见 engineering-copy-sha.txt。工程自有元数据摘要不宣称真实专利原件采集或来源用途许可。
- engineering-recovery-report.json 是上述工程场景产生的实际只读报告，不是现行数据处置清单。
- 初轮 12 成功/1 失败原因是工程 JDBC 适配器遗漏 RowCallbackHandler 重载，已补全并保留 junit-attempt-01.log。后续成功历史也保留，没有改写初次失败成通过。
- 只读手工需求摘要核对：47 记录文件无新增/删除/摘要差异，requirements-baseline-readonly.json；这不替代 verify-all 或语义审核。

## 未覆盖与主会话后继验证

1. 未访问 DB、容器、凭据或现行数据；未打开许可未证明、用途过期或保护包中的历史原件。三份实际原始结果与真实历史复制验证均未完成，不计为真实样本通过。
2. 当前 Issuer/Grant/Source 批准整链、实际 API/UI、选择/引用/解释/比较/委派续跑端到端均未运行。已有调用链只作源码核对与新增挂点编译，不宣称这些全链测试已通过。
3. legacy 语义结果没有在结果证据中记录确切 model 时保守隔离，即使当前或唯一历史构建看似可用也不推定闭合。无直接 resultSetId 的间接衍生文本/后续输入，没有新增猜测式依赖图或全任务封锁；此类依赖需主会话按产品合同另核对。
4. 缺见证报告对该结果不会继续宣称原件/专业重算已完整验证；坏存储结果/字段摘要仍在分类前硬拒绝，闭合分支保留原严格恢复原件/专业重算。缺见证之后尚未读取的原件错误没有被证明不存在。
5. 未运行 verify-all、Maven、共享 target 或全量回归；没有调整主计划、SQL/迁移、脚本、Identity/Grant/Source批准/Start/Gateway。没有确认需求状态、来源合法性或接受产品门禁。
6. 只读 report 为包内恢复检查方法，未增加产品 HTTP 报告页面或路由。请主会话在获准恢复上下文调用；它不授予读取许可。

## 冻结与接收

新增/修改的 8 个 Java 文件及 runner 的 SHA/行数见 frozen-files.json，均小于等于300行。交接后本执行者不继续改代码、不继续下一功能，不与主会话实际整链工作并发修改。由主会话做全量验证、实际许可/依赖核对与主计划证据登记；若发现问题，保留本轮输出并登记后继修复。
