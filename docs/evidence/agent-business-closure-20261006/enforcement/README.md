# 执法原生 Agent 启动与现时准入证据（2026-10-06）

本目录属于 `IMP-ENF-AGENT-WORKSPACE-START-01` / `VER-AGENT-THREE-DOMAIN-ADMISSION-01` 的执法分域证据，不是任务状态台账。任务状态、门禁和退出结论仅由主会话登记到 docs/08。本次没有修改主计划、共享 panel、三个 Controller/Runtime、共享配置、requirements，也没有创建工作树、提交或清理原有未提交改动。

## 1. 实際代码交付

唯一修改的既有产品文件：`frontend/src/features/workflows/EnforcementDocumentDraftV2Page.tsx`。
新增同目录执法专属文件：`enforcementAgentStart.ts`、`enforcementAgentStart.test.ts`、`EnforcementDocumentDraftV2Page.test.tsx`。改前原字节保存在 `EnforcementDocumentDraftV2Page.before.tsx`。

- 正式既有文书工作台向原 `document-drafts/{draftId}/agent-runs` 提交 `{document: 当前选中可消费 Manifest, procedure: 原生 CheckCommand}`。保留 manifest 的全部来源坐标及可选 conduct/penalty，不重建、裁剪或填充演示输入。
- `caseRef/caseVersion` 只从 manifest 精确派生并以只读字段展示；程序 id/ruleId/ruleVersion/calendarRef/calendarVersion 全部显式输入，无默认。程序规则不取文书规则。规则版本要求可精确表达的正整数；真实批准、来源权限和绑定仍由后端决定，前端格式通过不代表业务准入通过。
- 换文书/输入清除程序坐标、旧文书/候选/审核结果及 Run 关联。组织/身份失效时清空。当前请求或结果不确定时锁定输入；创建只使用共享 panel 留存的原 key/body 重试。登出后迟到响应不回填结果。
- 消费主会话提供的 `onView(view|null)`，新请求/scope/Run ID 切换立即清除旧关联。只有真实观测 `document-draft:<当前文书id>/<确切revision>` 才提供人工交接；先核 task.id 和 suffix 第一段解码后的 runId，一致性错误按本地 409 隐藏，不清除身份、不宣称失权。
- 交接按钮读取服务端确切文书版本，比较全部 manifest 引用/version/SHA 后进入原人工修订、提交审核、授权审核和 DOCX 交付区；不会自动采用、自动提交/通过审核、处罚、签发或送达。
- 既有共享 `/execute` 按钮只发一条请求，消费真实响应；无浏览器 advance 循环，无新增后台 Worker。本目录没有实现或冒称持久无人值守运行。

## 2. 实际工程验证及限制

- 改前 `node scripts/verify-all.mjs` 退出 0：`verify-before.log`。改后实际退出 **1**：`verify-after.log` / `verify-after-exit.json`；主计划健康检查报 `TESTS_FAILING:project`、`TEST_EVIDENCE_STALE:project`，独立检查仍继续，47基线文件/41契约一致。没有用47项focused通过覆盖全局红灯，没有篡改主计划/历史全量结论。结构检查与业务/法律验收分开。
- 两个执法测试文件最终 focused Vitest **47 项全部通过**：`focused-tests-final.log`，实际退出和输入前后 SHA 见 `focused-validation.json`。真实共享 AgentTaskPanel 被调用；域 API 和 Ant Design 展示控件为明确测试替身，故这不是产品浏览器 E2E、OAuth/HTTPS/DM8 Agent 正向闭环或专业验收。
- 仅上述页面/helper/test 及其直接导入的 focused strict TypeScript `--noEmit`，退出 0：`focused-typecheck-final.log`。未执行全量前端 build、共享 Maven 或全后端构建，交由主会话统一集成。
- 同 Task 错 Run 的 read/advance/execute 三个反例均证明隐藏结果、保留登录、没有失权提示和不确定锁；helper 同时覆盖解码的第一路径段和创建时 task.id。额外覆盖缺必填、无默认、来源/SHA/版本、切换清除、401/403清屏、不确定原 key/body、迟到响应、同文书确切交接、跨案/跨文书拒绝以及单次 execute。
- 初次 focused typecheck 缺 jest-dom matcher 声明，原失败保留于 `focused-typecheck-round01.log`；测试文件显式 import `@testing-library/jest-dom/vitest` 修复，没有改共享 Vitest 配置。
- 原生查询新增到期分组时曾使用 DM8 保留字别名 ROWS，SQLSTATE 42000 / -2007；原件 `native-select-round04-sql-alias-failure.log` 与当时旧 runner 的退出记录均保留。该轮不计完整核验成功；已改合法别名、让数据库/SQL失败导致非零退出，最终完整 SELECT 为退出0且无失败标记。失败不是数据库停止、授权变化或业务成功。

## 3. 文书 / 程序现时准入：直接原生 SELECT，不从咨询外推

最终文书准入数据库快照：

- 业务库：**2026-10-06 14:05:22.687718 +08:00**。
- 独立授权库：**2026-10-06 14:05:23.192335 +08:00**。

原件：`native-select-final.log`、`native-select-exit.json`，只读查询源 `NativeAdmission.java`。日期列以本次数据库时间/时区解释，不用旧测试时钟。

| 核验对象 | 本域原生结果 / 有效期 | 责任边界与继续条件 |
| --- | --- | --- |
| DOCUMENT_DRAFT Grant | 历史28条；ACTIVE12条，但现时未过期0条。ACTIVE最晚截止 **2026-10-03 22:27:40.649757**。当前授权指针及ACTIVE subject联结后，文书/程序目标用途合计0条 | 原受管身份/授权维护方负责正式范围及当前主体的精确授权，不是前端/Agent；本轮不续期、不签发、不复用其他purpose |
| PROCEDURE_CHECK Grant | 历史13条；ACTIVE12条，但现时未过期0条。ACTIVE最晚截止 **2026-10-02 04:50:37.411617** | 同上，程序规则/日历授权独立，不借 DOCUMENT_DRAFT 或 JOB_PACK 替代 |
| 产品会话 | 190条记录，现时有效0条；最晚 expires_at **2026-10-03 20:40:41.432112** | 正式身份系统/本人正常登录负责当前会话；本核验不发token、不创建session或模拟主体 |
| ENF3 Source / Material | 各4条，当前未过期均0条；共同最晚截止 **2026-10-04 12:52:26.343600** | 原来源权利/保留责任方提供当前合法用途与准入证据，授权维护方执行对应现时授权；不得直接改物理截止或把公开访问当许可 |
| ENF3 delete watermark | 4条持久化READY，但当前仍有效READY为0；最晚 valid_until **2026-10-04 12:52:26.343600** | 来源/保留权威系统负责当前水位；READY文字不代替有效期，不能用恢复旧快照续权 |
| Runtime部署 | ENFACC_BIZ 的 `AGENT_RUN_HEAD` / `AGENT_RUN_REVISION` 现存表数 **0/2** | 主会话/受管部署维护方按已批准部署治理补齐schema、开关及密钥准入；本轮禁止迁移/DDL/DML，没有实际部署这些表 |
| 原API/程序配置 | 所核原执法HTTPS端口5444～5465无listener；原受管模板目录77文件、文书历史存储46文件、原材料文件根30文件、document-draft-runtime为空 | 物理文件存在不证明可消费manifest。没有当前运行实例可读配置，故程序source-file、approved-basis、case-input-file、Runtime开关/密钥及模型配置标为**未实证**，不从历史启动脚本猜值；由部署维护方提供精确当前绑定，专业/来源责任方确认原生程序规则和日历 |

结论仅限此文书/程序用途：当前不能执行正式 Agent 正向Run。代码路径与focused工程合同已实现，但没有把拒绝、简单读历史、SQL计数或mock运行冒作真实执法完整闭环。

具体真人责任人及新的批准没有本次可用具名证据，不能填名或代签；上表是基于现有产品端口的责任类别，需主会话对接原受管责任方落实。

## 4. JOB_PACK 独立接续核验（不是文书备用权限）

最终专项数据库时刻：业务 **2026-10-06 14:11:39.858157 +08:00**，授权 **2026-10-06 14:11:40.014312 +08:00**。原件 `job-pack-select-final.log`、`job-pack-select-final-exit.json`；只读源 `JobPackAdmission.java`。本次额外核当前指针/主体/会话、包/待办/触发、事实用途与日粒度期限、注册原件及真实字节SHA、独立授权历史水位；没有启动该分支或写产品代码。

### 4.1 16条live Grant具体属于什么

只属于以下两个 **FUNCTIONAL_VALIDATION** 包，每包8条：CREATE1、COLLECT2、READ2、VERIFY2、TRIGGER1；全部 version=1，task_scope/data_scope精确等于该包ID。当前epoch指针及ACTIVE subject联结仍16条，**不等于16个任务或16个有效会话**。

- `ENF-JOB-FUNCTIONAL-20260929-01`：当前包revision12 / contentRevision2 / 持久化RUNNING；Grant逐动作截止范围 **2026-10-06 19:46:06.839154 ～ 19:46:10.106341**。
- `ENF-JOB-FUNCTIONAL-20260929-02`：当前包revision11 / contentRevision2 / 持久化RUNNING；Grant逐动作截止范围 **2026-10-06 20:33:35.702637 ～ 20:33:37.757136**。
- 与当前session联结有效数 **0**。两个原功能触发均为 `NEXT-V2-INT-ENF-01` / PENDING；原触发是功能验证记录，不能改解释成正式集成启动或已完成执行。

### 4.2 来源 / 事实 / 权限水位

- 每包 TASK、SOURCE_RIGHTS、APPROVAL、HUMAN_RECEIPT、BUSINESS_EVALUATION 五类事实均为VERIFIED；原提交/核验角色不同，批准、回执和评价targetContentRevision均为2。注册证据匹配，实际文件根原件3/3 SHA匹配（每包）；本次只读字节，无写入/复制业务正文到证据。
- 两包 SOURCE_RIGHTS 的 allowedUses 均仅 **FUNCTIONAL_VALIDATION**，validFrom=2026-09-29、validTo=**2026-10-06**。按现有 JobPackService 的**日期粒度**规则，在数据库日期2026-10-06尚不属于过去日；专项重算必要字段、核验状态、用途、截止日、target版本、批准和评分结构，输入findings为空。原合同没有时分秒截止，**不能捏造23:59:59或扩展到次日**；实际恢复运行还须核应用时区/当前日期，日期检查不证明法律许可或真人验收。
- JOB_PACK 的证据注册/私有原件验证路径不同于 DOCUMENT_DRAFT 的 ENF3 Material/Source 保留路径；因此不能把 ENF3材料过期泛化成此两功能包来源也过期，也不能反向把此功能许可转成文书、程序、REAL_INTEGRATION权限。
- 独立授权水位generation=2340 / READY / 无pending intent，前后相同。按当前 ProductGrantHistoryBatch 五段原字段顺序及 ProductHistoryDigestFraming 的UTF-16长度/UTF-8字节重算完整history，**摘要匹配**；只输出布尔核验，不输出issuer/subject/密钥/token/凭证/原历史字段。未把单个MAX(AFTER_EPOCH)=59误当全局generation2340。

### 4.3 当前D允许什么、现在能否运行

依据现行 docs/08 中 DATA-ENF-JOB-PACK-01、ARC/IMP-ENF-JOB-PACK-COLLECTION-01、ARC/IMP-ENF-REAL-BUSINESS-COLLECTION-03、NEXT-V2-INT-ENF-01，以及既有 `enforcement-job-pack-collection-d-20260929.md`、`enforcement-functional-readiness-d-20260929.md`、`enforcement-real-business-collection-d-20260929.md`：

1. **已能安全继续且本轮实际完成**：只读SQL、当前授权/用途/待办/触发/注册文件与SHA对账。此证据不是完整业务闭环。
2. **已有D及当前输入支持的有限功能分支**：上述两功能包在专项核验日期/当前精确Grant截止内，输入和水位未发现阻止原FUNCTIONAL_VALIDATION操作的缺口；可由主会话按既有批准恢复原产品部署、由原获准主体正常登录后，再重核当前水位/会话/日期及逐动作权限，接续原范围内读取/采集/异人核验。**本快照没有有效session、没有原运行API，所以现在不能实际调用产品运行；本轮禁止产生session/写DB，未替代执行。** 要续接既有PENDING功能触发，还必须核原执行回执/幂等与目标执行入口，不能凭包中RUNNING重发触发或宣称业务完成。
3. **不能拿来接续文书Agent**：这16条不是DOCUMENT_DRAFT/PREPARE、PROCEDURE_CHECK、材料/程序来源授权；当前限定D没有 JOB_PACK→当前DocumentManifest/CheckCommand 的替代转换授权，不新增fallback实现。
4. **真实公开业务分支不能自动进入完整执行**：原ENF8_REAL_BUSINESS_TASK共12项，11未领、1已领，全部WAITING_REAL_HUMAN_ACTION；已领 `ENF-RBC-CNIPA-GUIDANCE-CASE-1` / `ENF-JOB-CNIPA-GUIDANCE-CASE-1` 为REAL_BUSINESS_COLLECTION，包revision1。原公开输入COLLECTION_ALLOWED且实际原件SHA匹配，但仍确切缺 ACTOR_POSITION、APPROVAL、HUMAN_RECEIPT、BUSINESS_EVALUATION 四类；该真实包不属于现存16条live Grant的范围。真人岗位、异人批准/核验、人工原文回执与评价须真实责任主体提供，授权维护方确认该包的精确权限。公开材料的Collection Allowed不生成上述事实，不转换成正式业务完成。

这里明确区分：**文书/程序当前不能跑；JOB_PACK不是全域阻塞，但功能包也未具备当前运行会话；公开真实包另有四组真人事实及精确授权缺口。** 没有把任一分支的失败、成功或source用途外推给其他分支。

## 5. 原服务与数据保护 / 冻结交接

原容器完整ID：

- 5311：`d8ec0bbb93433e53153ab107b3415cf6d59beaa54aa443ac1fb03dd2b0a0242e`，`codex-enf-i-business`。
- 5312：`2aaabfbef2b9d6cc02616d002a9fb693371186515c8c4146a395d01f283ce767`，`codex-enf-i-authority`。

两者本轮前已运行并绑定127.0.0.1；本轮从未启动或停止任何数据库/API/Worker，收尾保持运行。受管配置只读于 LOCALAPPDATA/AgentPlatform/enforcement-product；凭据通过原受管SecureString在进程环境中临时注入，结束清除，未写证据。仅SELECT/metadata、只读文件SHA与事务rollback；无DDL/DML/迁移/续权/签发/触发/修改现实事实。

四个领域前端文件最终SHA、测试输入稳定性与集成接口见 `frontend-freeze.json` / `focused-validation.json`；此后不再改领域前端，主会话统一build。共享onView/execute由主会话拥有，本轮只消费既有props，不冻结或修改共享文件。证据文件完整清单见 `delivery-manifest.json`。

运行项目强制前后 `verify-all` 时，现有脚本自动再生成 `docs/project-plan.json`、`docs/project-progress.html` 两个派生看板（命令日志中明示）。本轮没有手工编辑它们或主计划，没有恢复/覆盖其他会话共享修改；这是验证工具的既有副作用，需主会话在统一集成时按当前主计划和全量证据重新对账，不能当作额外实施文件或手写状态账本。

尚不是正式业务完成：实际正式文书Agent创建→execute→持久化/恢复→当前权限/来源→真人文书复核/采用→正式交付验收，没有正向原生实证；后台Worker、专业正确性、真人岗位/决定、法律或来源用途批准、真实业务采用均不由focused测试替身或只读核验代替。
