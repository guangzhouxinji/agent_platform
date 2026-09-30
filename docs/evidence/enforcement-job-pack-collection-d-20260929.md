# DATA-ENF-JOB-PACK-01 最终产品采集与自动组包 D

日期：2026-09-29  
具名技术责任：Codex  
适用任务：`ARC-ENF-JOB-PACK-COLLECTION-01`、`IMP-ENF-JOB-PACK-COLLECTION-01`、`DATA-ENF-JOB-PACK-01`

## 1. 产品边界

本机制是唯一执法 V2 最终产品的一部分，用于把项目已冻结的产品合同、项目可准备的评价/证据模板，与真实业务人员在线提交并经核验的事实合并为一个不可变任务包版本。它不复制 MATERIAL、EVIDENCE、HANDOFF、PROCEDURE 或 CONDUCT，也不替代这些能力。

项目可自主确定结构、校验与评价模板，但不能生成或代签真实任务、来源权利、岗位任命、人工批准、人工回执或业务评价。开发样例只能验证机制，不能使包进入 `READY`。

## 2. 任务场景与固定结构

首个场景为“知识产权执法线索辅助核查”：从获准真实任务和来源材料出发，引用既有 V2 的线索、材料、待证事项、证据关系、程序检查、行为分析和人工交接版本，输出供真实业务人员复核的辅助任务包，不形成法定结论。

每个包必须包含以下六组真实事实：

1. `TASK`：任务标题、目的、组织、外部任务 Ref/版本、原始任务附件摘要、提交人及提交时间；
2. `SOURCE_RIGHTS`：来源 Ref/版本、权利人或授权方、允许用途、地域/范围、有效期、许可凭证 Ref/摘要；
3. `ACTOR_POSITION`：人员身份 Ref、组织 Ref、岗位名称、任命或授权依据 Ref、有效期；
4. `APPROVAL`：真实批准人对当前包版本作出的批准/退回/修改决定、理由、方法和时间；批准人必须与提交人不同；
5. `HUMAN_RECEIPT`：真实人员提交的原文回执、提交人岗位、时间及关联任务；系统不得生成正文；
6. `BUSINESS_EVALUATION`：真实评价人按项目模板填写的完整性、可追溯性、可操作性、事实准确性和人工节省五项评价、依据及结论。

项目自动提供但不冒充真实事实的内容包括：场景定义、字段 schema、五项评价模板、正常/异常/边界案例、预期输出清单、既有产品对象引用槽位、完整性规则和验收规则。

## 3. 状态与版本

状态只由服务端计算，任何 API 和页面均不得接收可写状态字段：

`DESIGN_READY -> COLLECTING -> WAITING_DATA -> READY -> RUNNING -> PASSED / FAILED`

- schema 发布即 `DESIGN_READY`；创建包后为 `COLLECTING`；
-首次保存后，只要任一必填事实缺失、未核验、已过期、被撤回或冲突，即为 `WAITING_DATA`；
- 六组事实均由当前授权的核验者核验、摘要一致、有效期当前有效且批准针对最新包版，才为 `READY`；
- `READY` 提交事务内产生唯一集成触发回执并进入 `RUNNING`，同包同版本重放返回同一回执；
- 只有真实 `NEXT-V2-INT-ENF-01` 执行回执可以写入 `PASSED/FAILED`；失败保留原因及输入版，不覆盖历史。

每次内容变化生成新修订并使旧批准、回执或评价不能授权新修订。任务包固化时保存规范 JSON SHA-256、各证据 Ref/摘要和触发回执。

## 4. 权限和人工边界

- 所有入口使用当前 V2 登录和现时 Grant；资源类型为 `ENFORCEMENT_JOB_PACK`，动作按 `CREATE/COLLECT/VERIFY/APPROVE/READ/TRIGGER/COMPLETE` 分离；
- 人员身份来自 JWT subject，提交的 `actorRef` 必须与当前主体一致；岗位是带证据的待核事实，不因登录自动成立；
- `APPROVAL`、`HUMAN_RECEIPT`、`BUSINESS_EVALUATION` 只能由相应登录主体主动提交，服务/JOB/Agent 不得代写；
- 核验者不得核验自己提交的事实，批准者不得批准自己提交的任务；
- Grant 撤回、证据过期、摘要漂移或依赖对象失权后重新计算为 `WAITING_DATA`，不得继续触发；
- 项目身份机制证明登录主体，不证明其现实岗位；现实岗位只有核验证据闭合后才计入完整性。

## 5. 输入、输出和失败行为

输入 API 接收：包创建信息、分组事实、证据元数据和原件、核验决定、批准、人工回执、评价、集成结果。原件按 SHA-256 保存并与修订绑定。

输出包括：当前派生状态、修订、每组完整性发现、缺失/冲突/过期列表、不可变组包摘要、触发回执和集成结果。正常输出不得宣称违法、处罚、法定期限或现实岗位成立。

失败必须无副作用：无 Grant、跨组织、本人自核/自批、摘要不符、证据缺失、过期许可、旧修订批准、重复键异载荷、非 READY 触发、非 RUNNING 完成均拒绝；数据库、文件、事件和回执数量不得增加。文件已暂存但事务失败时进入可重试孤儿清理，不得被组包引用。

## 6. 正反例与验收

正例：六组事实分别由真实主体提交并由不同的当前授权核验者确认；来源许可覆盖任务用途且未过期；批准、回执、评价均针对最新修订；系统自动固化摘要、转为 `READY` 并只触发一次集成运行。

固定反例：缺许可凭证；虚构/测试岗位；提交人自核；提交人自批；系统生成回执；过期许可；附件摘要漂移；批准针对旧版；缺一项评价依据；重复键异载荷；失权后触发；失败集成被写成通过。所有反例保持 `WAITING_DATA` 或明确拒绝，且无禁止副作用。

D 退出条件：上述 UI/API/数据/状态/权限/失败/人工和验收合同完整，现有 V2 架构可承载，真实事实缺失被建模为运行期 `WAITING_DATA` 而不是产品 D 阻塞。结论：`ARC-ENF-JOB-PACK-COLLECTION-01` 通过，允许实现；不表示 `DATA-ENF-JOB-PACK-01` 已 READY，也不触发真实集成。

## 7. 实现与执行证据

唯一最终产品实现已落在 `/v2/enforcement/job-packs` 与 `/app/enforcement/job-pack`：

- `V062__enforcement_v2_job_pack.sql` 固定不可变修订、回执、事件、受控原件登记和唯一集成触发；
- `JobPackService` 服务端派生状态、内容版、完整性发现、异人核验、自批拒绝、过期/用途/评分/旧版检查和自动触发；
- `EnforcementJobPackController` 使用当前 V2 JWT 与精确 `ENFORCEMENT_JOB_PACK` Grant，原件实际写入执法私有文件根并重读 SHA-256；同 Ref 同字节重放，异字节冲突；
- React 工作面提供六组真实事实、原件上传、逐项核验、状态进度、缺口和触发回执，不提供手工 READY/PASSED 控件，不预置人物、岗位、批准或回执。

2026-09-29 实际结果：

| 层 | 命令/产物 | 结果 | 禁止副作用 |
|---|---|---|---|
| C | `JobPackServiceTest` | `tests=2, failures=0, errors=0, skipped=0`；缺项 WAITING_DATA、本人自核拒绝、六组异人核验只生成一个触发、真实结果回写 | 自核失败不增加修订；不接收客户端状态 |
| 真实 DM8 I | `JobPackDm8IntegrationTest`，专用 `codex-enf-i-business:5311` | `tests=1, failures=0, errors=0, skipped=0`；真实文件字节可读，enf8修订/回执/事件各2，对账后清理 | 本地机制输入保持WAITING_DATA，触发记录0；未用H2/Mock/Stub/Skip冒充DM8 |
| HTTPS | `JobPackHttpsTest` | `tests=1, failures=0, errors=0, skipped=0`；真实登录、无Grant 403、签发后创建、multipart原件、SHA、事实提交、读取、撤权后403 | 缺其余五组事实时触发记录0；该HTTP协议测试不冒充真实业务任务 |
| 前端 | `npm test -- --run`、`npm run build` | 15文件45测试全过；生产构建成功 | 无开发专用路由/身份/状态按钮 |
| 浏览器渲染 | Playwright 1440x900、390x844 | `enforcement-job-pack-{desktop,mobile}-20260929.png`；入口、标题、登录态、导航无重叠/溢出 | 未登录不提交真实性数据 |

Codex 具名技术复核结论：`IMP-ENF-JOB-PACK-COLLECTION-01` 的冻结范围通过开发验收。当前没有真实六组事实，产品状态必须为 `WAITING_DATA`，没有自动触发 `NEXT-V2-INT-ENF-01`；这不是测试失败，也不能改写为 READY/PASSED。

## 8. 真实业务采集收口

2026-09-29 在同一最终产品入口 `/app/enforcement/job-pack` 完成六步采集向导收口，不改变服务端事实合同、状态机或自动触发条件：

- 六组事实始终可见，逐项显示当前状态、必填内容、对应证据、提交人/时间、核验人/时间、核验说明及是否通过异人核验；
- 当前步骤只提交一组真实事实，原件仍通过既有私有文件存储并保存 SHA-256；已提交事实显示不可变原件 Ref、文件名和摘要；
- 技术缺口码在工作面转换为逐项缺失、未核验、过期、用途不足、旧版关联或评分无效说明；系统不自动生成真实性内容；
- 六组事实全部通过异人核验后，既有服务端事务自动固化版本、切换 `READY` 并写入唯一 `NEXT-V2-INT-ENF-01` 触发；任一项缺失或失败继续 `WAITING_DATA`。

专项验证结果：

| 层 | 结果 | 结论 |
|---|---|---|
| 工作面向导 | `EnforcementJobPackV2Page.test.tsx` 1/1；前端全量16文件46项通过；生产构建通过 | 六步状态、必填/证据、提交与核验溯源、缺口及无误触发均闭合 |
| 领域 C | `JobPackServiceTest`：`tests=2, failures=0, errors=0, skipped=0` | 缺项、自核拒绝、自动触发与结果状态保持原合同 |
| 专用 DM8 I | `JobPackDm8IntegrationTest`：`tests=1, failures=0, errors=0, skipped=0` | 使用执法专用 DM8；修订/回执/事件/文件对账且触发数为0 |
| 登录 HTTPS | `JobPackHttpsTest`：`tests=1, failures=0, errors=0, skipped=0` | 只证明登录产品协议链；不替代专用 DM8 I 或真实业务事实 |

当前真实采集计数仍为0/6，异人核验计数0/6；六组均为未提交。`DATA-ENF-JOB-PACK-01` 保持计划`BLOCKED`、产品`WAITING_DATA`，`NEXT-V2-INT-ENF-01` 未触发。

全仓校验单独记录：本轮开始时 `node scripts/verify-all.mjs` 曾被无关的 `backend/apps/platform-api/src/test/java/cn/xinji/agentplatform/security/product/ProductIssuerHttpTest.java` 403行超过400行硬上限截断，本任务未修改该文件。最终复跑时该文件已由并行工作变为400行，需求基线、治理自测、626项主计划、结构和专项脚本全部通过，`verify-all` 退出0。执法专项的真实I和零跳过结论仍以本节独立报告为准，不以全仓结构通过替代。
