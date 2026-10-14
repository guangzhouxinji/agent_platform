# 03A有限回归与冻结退出收口证据

日期：2026-10-04（Asia/Shanghai）。复核ID：ADJ03A-EXIT-SCOPE-REVIEW-20261004-01。责任：本轮执行代理Codex，技术证据范围审查，不冒第三方独立验收/真人审核或业务负责人专业结论。唯一任务状态见总计划，不新建任务台账或定时续行。

## 本轮只做什么

按负责人一次性指令，从原完整失败报告反查20项错误，完成当前新增接线与01/02受影响回归，核查恢复冻结合同；不重做内核/HTTP/页面/持久化，不发供应商请求，不变身份/预算，不触咨询/执法服务、库、状态。03B没有编码或D放行。

## 原20项逐项反查

[逐项可读表](regression-error-scope.md)及[原XML匹配与源码/配置/manifest/库范围](regression-error-scope.json)：每一项都核对原XML SHA、testcase error及实际message，不凭包名汇总推论；原339套1152项、20错误119跳过继续保留，未改skip。

- 6项是独立物理机制可补跑：模板、法规引用、后续迁移、jobpack、固定ID实际业务收集机制、facility V080/V081迁移。当前输入有现成机械验证准入，但必须只用本轮新独立实例/空schema/受控目录，不能写ENFACC_BIZ或5311/5312。固定ID收集复用88～95的实际EnforcementDm8IsolationGuard，校验Docker/卷唯一归属、随机schema、空表及CREATE_NEW文件根标记；现有Guard/测试/业务代码不改。
- 14项须确切当前ENF产品场景：attempt 1、dependency 1、frozen negatives 9、negative runtime 2、recovery runtime 1。需要匹配rule/version、正常操作原件、manifest及实际授权的只读对账范围；不能用旧别案manifest或向产品库播种固定ID代替。

## 隔离补跑尝试：环境未满足，未伪造通过

[实际包装失败](isolated-wrapper.log)及[只读Docker探针](isolated-daemon-current.log)：Docker Desktop Linux Engine管道当前不存在。尝试在查现有本地image阶段失败，未创建新容器/卷/schema、未执行六项数据库测试，没有启动Docker以免顺带恢复其他域服务。六项明确NOT_RUN/ENVIRONMENT_BLOCKED，十四项也保留ENVIRONMENT_BLOCKED，不反复重刷完整20项。

本轮只新增裁决测试支撑脚本，不是业务能力：verify-adjudication-exit-isolated-regression.ps1与PrepareAdjudicationExitRegression.java，精确5411独立端点、现有本地镜像/no pull、随机空schema、新卷/目录、保护生产端口，逐组执行检查；失败保留归属证据、无自动生产清理。首次失败后增强启动前Docker探针以免先建目录/凭据。[语法检查](helper-syntax.json)、[JDK21编译](helper-compile.log)通过；[禁用5311目标机械反例](helper-forbidden-target.log)在JDBC连接前明确拒绝。以上不冒真实隔离DB执行成功。

首次失败准备的四份未绑定任何数据库/业务身份的随机DPAPI辅助凭据及空TEMP目录仍在受限私人目录；仅这些本轮对象的清理调用被工具安全策略拒绝，[限制记录](failed-preflight-cleanup-limitation.json)，没有改用别的工具绕过，也没有删除旧model keys、历史案件或原件。未创建任何定时清理/续行。

## 当前源码受影响回归：实际179项通过，证据范围分开

[输入快照](../../adjudication-product-20261003/adj-exit-20261004-01-build.json)、[1193源文件精确SHA一致](current-source-binding.json)、[原报告与时间索引](current-regression-index.json)：

- [受影响回归](affected-regression.log)：23套129项，0失败/错误/跳过；当前03A新增身份维护控制器/错误映射、材料validator/run store/HTTP，与01/02领域、并发、HTTPS完整生命周期、异常/恢复；Shared ProductIdentity初始化、注册、Keys、mTLS Proof、Issuer、Grant/水位、来源用途与权限；ENF架构边界及原HTTP机械回归。
- [兼容边界回归](compatibility-boundaries.log)：7套50项，0失败/错误/跳过；CON HTTP/model chain机械、V2边界HTTP/filter/disabled、正式工作面路由，以及ENF隔离Guard反例。
- [接线与调用图复核](wiring-compatibility-review.json)：确切13个新增/导入/Shared身份接线类SHA与上轮实际HTTP部署构建完全一致。本轮没有改共享业务代码或其他域测试；独立ADJ组件扫描不将新增维护控制器导入CON/ENF。

机械H2/内存/本地HTTPS测试与真正DM8/正式HTTP产品I分别记录，不能把179项通过称全仓通过，也不能以包名无错误称所有CON/ENF兼容完成。原20项物理/当前产品对账仍未清除。未跑03C页面、未代真人审查。

[准确报告同步](accurate-targeted-report-sync.json)只同步9项新增ADJ身份/错误报告到对应canonical位置；新报告真实mtime保留、旧XML备份、完整失败报告不覆盖。不是修改时间或checker以冒新全量。

## 第二次HTTP run的冻结恢复合同复核

[具名范围复核](recovery-contract-review.json)绑定现行ADR SHA及§10第5、6项/原生恢复补充。冻结合同明确“先组件真实调用与DM8组件恢复，再可信HTTP双库负例”，要求物理组件恢复I和实际API新run重启恢复，但没有要求每次追加HTTP run都重新执行同一原生导入。因此本轮不重复原生备份，不新增供应商请求，也不修改D或扩大灾备承诺。

原native-recovery-02只证明第1次组件run，不能称其含第2次正式HTTP run；第2次已有实际服务重启/DM8读回完整view SHA一致证据，二者范围不同。缺钥/损坏/固定期限/过期拒读及原生旁表恢复沿已实际证据限定复用，不冒全案件、多run批量或权威库灾备。本轮Docker不可用也未执行新恢复，原证据不改。

最近可核账本累计2次、输入1374/输出2298、保守cost 0.021132元，本轮零请求、未读写新账本。最后实际head5/input4/material-2@v1撤回、旧候选stale、产品读403的历史证据继续作禁止采用的安全依据；本轮未重新查询运行库，不把历史快照冒新鲜运行可用性。没有复活来源或把恢复审计当现时可读/可采用。

## 退出结论与下一具体动作

03A不能完成；本轮不降低冻结的回归退出，179项已选机械回归通过不消除20项缺口。当前剩余明确是6项新隔离物理补证所需Docker环境，以及14项确切ENF产品上下文/授权对账，详见逐项表的退出条件。无账号/材料用途/供应商/预算重复批准阻断。下一步只在独立环境可用时补六项，在执法负责范围有同场景真实证据后对十四项逐一审查；不能由本轮代理启动其他服务、播种数据或借旧manifest。若未取得，这一轮在准确缺口处停止；03B/03C/03D不开始。

本轮修改前[verify-before.log](verify-before.log)真实退出1/TEST_EVIDENCE_STALE，47文件41契约一致。当前源码新回归与准确报告同步后[verify-before-plan-update.log](verify-before-plan-update.log)真实退出0；此前失败不覆盖，结构通过不冒专业业务正确性或完整回归通过。总计划更新后的最终结果单独保留在verify-after-plan-update.log。
