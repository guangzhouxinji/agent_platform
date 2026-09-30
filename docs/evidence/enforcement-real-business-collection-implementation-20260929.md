# 执法真实业务采集待办实现与验收证据

## 1. 结论

- 执行编号：`ENF-RBC-20260929-03`。
- `IMP-ENF-REAL-BUSINESS-COLLECTION-03`按窄 D 完成：唯一最终产品实现已增加公开候选自动导入、待办列表、现时 grant 领取、公开输入绑定、领取审计和五项真实性工作面；旧手工六事实任务包保持兼容。
- 首任务固定为`ENF-RBC-CNIPA-GUIDANCE-CASE-1`，任务包 ID 为`ENF-JOB-CNIPA-GUIDANCE-CASE-1`，来源为`ENF-PUBLIC-CNIPA_GUIDANCE_CASE_1_INTERPRETATION`。
- 产品机制完成不等于现实人工事实完成。当前没有可证明的非测试真实人员实际操作，故`DATA-ENF-JOB-PACK-01 = WAITING_REAL_HUMAN_ACTION / BLOCKED`，`READY=0`，`NEXT-V2-INT-ENF-01`未触发。

## 2. 实现证据

- 合同：`JobPackContract`增加`WAITING_REAL_HUMAN_ACTION`和可追溯`PublicInput`；公开输入包只等待`ACTOR_POSITION / APPROVAL / HUMAN_RECEIPT / BUSINESS_EVALUATION`，旧包仍等待原六事实。
- 领域：`JobPackService.createPublic`固化采集批次、URL、机构、抓取时间、原始 SHA、文件键和公开事实；领取人不得自批，提交人不得自核，内容版漂移仍失败关闭，最终核验继续用既有单次触发器。
- 持久化：`V066/U066`增加`enf8_real_business_task`及不可变领取事件；任务分配和工作流状态分离，领取使用 CAS 和幂等指纹；修订、回执和事件状态列扩到 32 字符以容纳冻结状态名。
- 输入 Adapter：`PublicCollectionTaskImporter`只接受`PUBLIC_DATA_READY + COLLECTION_ALLOWED`候选，逐原件读取并复算 SHA 后写产品文件根；缺文件、路径越界、摘要漂移均拒绝启动。
- API：`GET /v2/enforcement/job-packs/collection-tasks`与`POST .../{taskId}/claim`；领取前核当前 JWT 对确定任务包的`CREATE/COLLECT/READ`精确 grant，并在建包前重读原件 SHA。
- 工作面：`/app/enforcement/job-pack`登录后显示待办、任务/来源/状态/SHA、组织和领取；公开包显示当前操作者及四项人工步骤，不再要求独立许可证文件，也不把公开输入显示为内部人工事实。

## 3. 实际执行结果

| 层 | 命令/环境 | 结果 | 报告 |
|---|---|---|---|
| C | Maven `JobPackServiceTest` | `tests=4 failures=0 errors=0 skipped=0` | `backend/enforcement/target/surefire-reports/TEST-cn.xinji.agentplatform.enforcement.jobpack.JobPackServiceTest-ENF-RBC-20260929-03.xml`，SHA `a0d844d196a00ac98284eaa506395e73a8d7083e780a8d8b142ef08584620399` |
| HTTPS | 真登录/PKCE/JWT、授权前403、现时grant领取、重放 | `tests=1 failures=0 errors=0 skipped=0` | `backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.enforcement.jobpack.JobPackHttpsTest-ENF-RBC-20260929-03.xml`，SHA `a03e298868bdad2fab54b721f0e07420636c6ffad7e72760800d58544dd01b23` |
| 真实 DM8 I | `codex-enf-i-business:5311`全新一次性独立 schema、真实 JDBC、产品文件根 | `tests=1 failures=0 errors=0 skipped=0` | `backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.enforcement.jobpack.RealBusinessCollectionDm8Test-ENF-RBC-20260929-03.xml`，SHA `bba0ecfe06b73acc8642efd199192fbda9edda0dc7e9155890331a6977730c50` |
| 前端 | Vitest 全量 | 16 文件、47 测试通过 | `npm test -- --run`退出0 |
| 构建 | TypeScript + Vite | 生产构建通过 | `npm run build`退出0 |
| 全仓 | 需求摘要、计划、结构、专项数据校验 | 全部通过 | `node scripts/verify-all.mjs`退出0（状态更新前） |

真实 DM8 I 实际从现有候选 JSON 读取 12 个官方候选、读取 12 份原始文件并复算 SHA、创建 12 个`UNCLAIMED / WAITING_REAL_HUMAN_ACTION`待办；随后仅用具名机制测试主体领取首任务，验证一条领取事件、一条公开任务包修订、原件可读、同键重放不增量、异主体二次领取拒绝、集成触发为0。一次性 schema 在测试后删除；该机制主体不计真实业务人员。

## 4. 禁止副作用与真实性状态

- 无 grant 的领取返回403且不建包；重放只返回同一领取，不增加事件/修订；异主体领取被拒绝。
- 公开任务包初始状态为`WAITING_REAL_HUMAN_ACTION`，只含公开输入，不含伪造岗位、批准、回执或评价，`enf8_integration_trigger=0`。
- 当前执法专用业务库身份审计只发现两名本轮程序线 I 测试账号；没有将其计为当前真实操作者或真实岗位。
- 当前五项：操作者未确认、岗位授权未通过、人工批准未完成、正式回执未完成、业务评价未完成。
- 唯一剩余外部真实性条件：至少两名具现时岗位授权的非测试真实业务人员在产品内实际登录，分别完成领取/岗位/回执/评价、独立批准和逐项异人核验。系统随后会自动 READY 并触发 NEXT，无需再改代码或下指令。

## 5. Codex 具名技术复核

- 复核人：Codex；日期：2026-09-29。
- 复核范围：公开/内部事实分离、单实现边界、现时身份与 grant、领取并发/重放、文件 SHA、人工职责分离、旧包兼容、READY/触发条件、DM8/HTTP/XML零跳过及页面可访问语义。
- 结论：冻结范围实现与验证通过，允许完成`IMP-ENF-REAL-BUSINESS-COLLECTION-03`；不得完成`DATA-ENF-JOB-PACK-01`，不得启动`NEXT-V2-INT-ENF-01`。
