# 执法 V2 功能可运行收口 D

- 任务：`ARC-ENF-FUNCTIONAL-READINESS-D-06`。
- 日期：2026-09-29。
- 决策来源：项目负责人本轮明确“先把执法 V2 做成真正能用的产品”，允许开发/验收身份、可控测试主体、脱敏数据和项目测试任务验证完整功能链，但不得冒充正式业务验收。
- 需求依据：执法总览第3节要求身份来自可信认证上下文，第5节要求人工修订、审核与版本可追溯，第6节要求页面调用同一服务接口并覆盖失败状态；测试数据须明确标识并与真实业务验收分记。

## 1. 状态分层

项目控制状态固定为三条独立结论，不新增第二套产品实现：

1. `FUNCTIONAL_READY`：同一最终产品的页面/API/DM8/文件/权限/版本/审计/失败和恢复链可由用户实际操作完成。
2. `TECHNICAL_VALIDATED`：上述链路已有真实HTTPS、DM8、文件及零跳过技术证据。
3. `BUSINESS_ACCEPTANCE_PENDING`：真实岗位、正式授权、专业评价或生产准入尚未完成；该状态不阻塞功能实现和技术验证。

测试输入或测试身份产生的任务包、回执、评价与触发必须保持`FUNCTIONAL_VALIDATION`用途，证据只能支持前两项，不能提升第三项。

## 2. 当前审查结论

| 范围 | 当前结论 | 本轮处理 |
|---|---|---|
| V2 MATERIAL / EVIDENCE / HANDOFF / PROCEDURE / CONDUCT | 已有最终产品服务、页面、DM8、HTTPS、共同恢复及独立I；属于真实实现，不是测试壳 | 保持冻结，不重写业务语义 |
| 线索发现、线索解析、旧规则页 | 当前页面仍调用`/api/v0`，只能作历史开发资产，不能算V2最终产品闭环 | 登记后继V2任务；本切片不把其冒充完成 |
| ENF-12～17 | 需求R已就绪，但产品D未通过且没有最终产品API/DM8/页面 | 保持未实现；分别过D后才能编码 |
| Job Pack / DATA入口 | 同一产品API与DM8真实存在，但把功能触发和正式真人验收共用一个门，且Subject、组织、岗位等由用户手填 | 本切片修复可信身份上下文、功能验证用途和状态表达 |

## 3. 冻结实现合同

1. 继续使用现有`/v2/identity/me`、`/v2/enforcement/job-packs/**`、同一`JobPackService`、DM8表、文件存储、回执、事件与唯一触发，不增加开发专用路由、免鉴权分支或第二套Service。
2. 页面登录后读取可信身份上下文；Subject只读展示，组织从当前有效Job Pack Grant自动取得。领取任务不得再要求用户手工输入Subject或组织。
3. 服务端提交岗位/回执时以JWT Subject覆盖调用方同名字段，岗位事实的组织以任务包组织覆盖；客户端不能伪造这两个身份字段。
4. `FUNCTIONAL_VALIDATION`任务包使用明确标记的测试身份与测试输入：要求任务、来源用途、异人批准、人工回执和业务评价；不要求`ACTOR_POSITION`，回执不要求重复填写personRef/positionTitle。任务、来源用途与测试性质仍须有证据和版本。
5. `REAL_BUSINESS_COLLECTION`保持原四项真人事实与异人核验合同；本轮不自动填写岗位、批准、正式回执、评价或核验，不把现有A/B动作升级为业务验收。
6. 功能验证事实全部异人核验后可按现有机制产生一次`NEXT-V2-INT-ENF-01`触发并进入`RUNNING`；其purpose和证据明确为测试，只验证触发机制。正式业务验收仍单独保持pending。
7. 同键重放、版本冲突、自批/自验、失权、证据摘要漂移、缺字段和恢复后的回执/触发对账沿用现有失败关闭语义。

## 4. C/I/E2E退出条件

- C：功能验证包不要求岗位事实；可信Subject/组织不能被请求体覆盖；同人批准/核验仍拒绝；最后异人核验只产生一个触发。
- HTTP/UI：OAuth/PKCE登录后自动显示身份与授权组织；领取和创建不要求手工输入Subject/组织；功能验证与正式业务验收有醒目标记。
- I：继续使用执法专用DM8和文件根，核包版本、事实、回执、事件、唯一触发及禁止副作用；不以H2、Mock、Skip替代。
- 验收：本任务最多判`FUNCTIONAL_READY / TECHNICAL_VALIDATED`，`BUSINESS_ACCEPTANCE_PENDING`必须继续独立显示。

上述输入、输出、权限、失败、测试和验收边界已完整，且不依赖新的法律、专业或正式岗位决定。`ARC-ENF-FUNCTIONAL-READINESS-D-06`通过，允许启动`IMP-ENF-FUNCTIONAL-READINESS-06`；ENF-12～17仍不得编码。

## 5. 实现与专项验证结果

- 最终执行编号：`ENF-FUNCTIONAL-BROWSER-20260929-02`；功能验证包：`ENF-JOB-FUNCTIONAL-20260929-02`。
- 使用两个明确标记为`FUNCTIONAL_VALIDATION`的测试主体、两个独立浏览器上下文和两套真实OAuth/PKCE产品会话，实际完成13个页面步骤；浏览器HTTP失败为0。测试主体不是正式业务人员，全部结果只用于功能与技术验证。
- 页面经`/v2/identity/me`取得可信Subject和Grant；组织由精确`CREATE/JOB_PACK`授权确定。服务端覆盖客户端身份字段，功能验证页面不再要求手填Subject、组织、岗位或授权Ref。
- 五项功能验证事实均从产品页面提交并由另一测试主体核验。包最终为`revision=11`、`contentRevision=2`、`state=RUNNING`，仅产生一次`NEXT-V2-INT-ENF-01`功能联调触发，触发绑定revision 11；未手工修改数据库。
- 专用DM8实读11个Revision、1个CREATE回执、5个SUBMIT回执、5个VERIFY回执、11个审计事件和1条`PENDING`触发记录。三份原件的存储文件SHA-256均与产品记录一致。真人包`ENF-JOB-CNIPA-GUIDANCE-CASE-1`仍为revision 1、触发0，未被本轮测试修改。
- 浏览器、DB、文件和触发对账证据目录为`%LOCALAPPDATA%/AgentPlatform/enforcement-product/evidence/ENF-FUNCTIONAL-BROWSER-20260929-02`。关键摘要：`result.json=dfbfc64aeed9da2b2b08d3d5408bf4b39309b471a0bda2cdd5a464573c5157d4`；`functional-running.png=b7873bc4be144e177e306d7b4ccc7c827fba0b26aa4636cce38570a120701ae0`；`db-reconciliation.txt=28314caf003e09637ee80d019a2a2c2054018bdd93d2b4b184b2b4951c3305cd`；`file-reconciliation.json=d480bcfe4906c8c7cf5700eaa3a5f34ce4d2ad3313738f64a2ddd52930cd90b0`；`reconciliation-summary.json=6bf0f67cd8c45d3f56cc7bcb074969751a3228ee5be88d37a22d1f35d92e2d29`。
- 后端专项：`JobPackServiceTest` 6/0/0/0，`JobPackHttpsTest` 1/0/0/0，`JobPackDm8IntegrationTest` 1/0/0/0；均为tests/failures/errors/skipped。前端四个suite共11项全部通过，0失败、0待定、0跳过，`frontend-tests.json` SHA-256为`b04fe7059909f3b3fbb6a878595ec43f9d1e5d094911185b7eca76908182a1a4`；生产构建通过。

## 6. 具名技术复核与最终判定

2026-09-29由Codex完成具名技术复核：身份与授权来自产品认证上下文；测试用途在页面、API与持久化结果中保持可辨；版本、回执、事件、文件摘要和唯一触发相互一致；测试包未污染真人包；未使用H2、Mock、Stub、Skip或数据库直写替代产品闭环。结论：

- `IMP-ENF-FUNCTIONAL-READINESS-06 = 已完成`。
- `DATA-ENF-JOB-PACK-01 = FUNCTIONAL_READY / TECHNICAL_VALIDATED`。
- `BUSINESS_ACCEPTANCE_PENDING`继续独立保持；本轮测试触发不构成正式业务`NEXT-V2-INT-ENF-01`验收启动。
- ENF-01～11既有冻结范围不重开，ENF-12～17仍须各自通过产品D后才能编码。

计划回写后的`node scripts/verify-all.mjs`确认47个需求基线文件与41行需求台账一致，非法活动依赖、`TESTS_FAILING`和`TEST_EVIDENCE_STALE`均已消除；640项计划校验通过。执法页面按结构门将表单契约/显示辅助拆至`EnforcementJobPackV2Model.ts`，页面190行、模型85行，拆分后前端2个文件11项测试和生产构建再次通过。全仓最终仅余无关咨询文件`ProductPatentSearchExecution.java` 312行超过300行硬限；按边界不修改咨询代码。执法本轮独占证据及零失败、零跳过结论保持有效。
