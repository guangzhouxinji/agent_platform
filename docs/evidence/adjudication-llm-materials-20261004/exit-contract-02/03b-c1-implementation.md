# 03B-C1领域决定实现与验证证据

日期2026-10-04。仅本子能力实现/机械验证，03B整体状态仍以总计划为准。

## 设计后编码、明确复用

[当前详细D](../../../adr/adjudication-material-decisions-20261004.md)已具证后才新增MaterialCandidateDecisions.java及MaterialCandidateDecisionsTest.java；它们是未来受控API必须复用的领域规则，没有固定候选、测试身份/权限绕过、第二Agent平台或开发页面。只依赖现有MaterialOrganizationModel.Candidate/Input和CaseWorkContract.Source/原SHA函数，不改01/02内核、控制器、共享Identity、其他域代码、运行配置/库/预算。

## 已实际实现

- 显式ACCEPT保留机器正文；EDIT必须正文与用户理由、保留原候选/原文；REJECT必须理由且无人工采用正文。无默认决定或自动采信。
- source id/version/SHA/locator/tag、提供方与UTF16摘录逐字绑定；引用漂移/原文变化、旧inputVersion及跨case拒绝。
- case头与organization头分别精确CAS、重复首次决定拒绝；人工更正必须当前candidate确切base版本，新版本不覆盖旧记录；新UUID不能复用父run/其他决定key。
- 输出semantic只为HUMAN_REVIEWED_MATERIAL_STATEMENT或REJECTED_CANDIDATE，分类继承原候选、scope SELECTED_MATERIALS；绝无CONFIRMED_FACT、finding、报告、正式审核或官方状态字段/写入。
- 新人工payload到期继承父run最早期限，不从采用/修订重算30天；恰到期/时钟回退拒绝。原机器/人工正文及理由只在瞬时结果，AuditReference严格只5项opaque引用元数据，不把正文塞既有case历史。
- 字数可配置、Unicode不损坏、不trim冒原文，空输入/不合法版本/溢出/非法动作/缺理由拒绝。当前State及历史不可变快照，不调用模型/数据库/授权系统。

领域函数不拥有授权：Current为应用根据受控case/模型/来源构造的内部快照，不是客户端JSON或approved布尔值。真实JWT/权限、水位、同库事务/幂等回执/加密DAO及恢复必须后续API接线补齐，不能把本模块当安全网关或完整采用流程。

## 实际测试、范围与源码窗口

[首轮日志](03b-c1-current-tests.log)通过后，源码复核补独立子key、显式sourceTag及接受正文限额防护；[当前加固回归](03b-c1-hardened-tests.log)31套209项、0失败/错误/跳过，其中新领域30项、既有179项涵盖01/02/03A/Shared及CON/ENF精确机械边界。[原XML/SHA/mtime索引](03b-c1-hardened-test-index.json)保留原件。

并行CON之后更新PatentClaimGraph和ProductPatentClaimOriginalTest，所以全仓1201文件窗口并非全与冻结包相同：[差异原件](03b-c1-current-source-binding.json)。没有改它们，也不声称覆盖其新逻辑。自有ADJ输入仍一致；再用新快照只重跑自有四套：[当前领域日志](03b-c1-domain-current-tests.log)4套84项通过，[20个自有输入精确SHA与报告](03b-c1-own-source-final-binding.json)0漂移。这个84项是明确复跑范围，不与209项相加虚增覆盖。

[精准canonical同步](03b-c1-final-accurate-report-sync.json)仅复制新领域30项真实XML到同位置，旧报告保留、mtime不修改；不覆盖原20项完整失败报告，不把其改skip或pass。

## 治理与未完成范围

前置verify-before.log真实0。退出/D/编码前后检查中出现：另一会话CON两个完成项短暂缺证、project-plan.json并行打开失败、TEST_EVIDENCE_STALE，各原件保留；未改它们状态/证据或削弱checker。最近verify-before-final-plan.log仍真实1，仅TEST_EVIDENCE_STALE；[最新全仓源码/报告时间实查](project-freshness-limitation.json)最新文件是CON ProductPatentComparisonAccessTest，比自有原报告晚，不能说当前全仓通过，也不继续无限刷新测试冒新全量。47需求/41契约基线一致；后置verify-final.log单独保留。

本模块没有HTTP/DM8写入/真人采用/页面/灾备I。03B仍进行中，下步是按已冻结D接现有受控API、同库事务与派生加密/期限，补实际API/DM8及适用失败验证。当前旧case撤源/旧候选失效维持；正常正向产品I需合法当前输入/候选和安全环境，没有则拒绝，不造回执、不复活旧来源。本轮0付费调用，未查询/清零/改写旧2次账本，不建定时任务。
