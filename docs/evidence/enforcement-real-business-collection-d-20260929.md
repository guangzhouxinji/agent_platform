# 执法真实业务采集待办窄 D

## 1. 变更边界

- 任务：`CR-ENF-REAL-BUSINESS-COLLECTION-03 -> ARC-ENF-REAL-BUSINESS-COLLECTION-D-03 -> IMP-ENF-REAL-BUSINESS-COLLECTION-03`。
- 上游：`IMP-ENF-PUBLIC-COLLECTION-ALLOW-02` 已形成 12 个 `COLLECTION_ALLOWED / PUBLIC_DATA_READY` 官方候选；`IMP-ENF-JOB-PACK-COLLECTION-01` 已提供任务包提交、异人核验、人工批准、人工回执、评价、版本、回执、事件和自动触发。
- 本变更只增加公开任务到真实人工流程的待办、领取和公开输入绑定，不复制身份、授权、审批、回执、评价或审计体系，不修改 ENF-01～11 已冻结能力。

## 2. 首个待办

- 候选：`ENF-PUBLIC-CNIPA_GUIDANCE_CASE_1_INTERPRETATION`。
- 产品任务：`REAL_BUSINESS_COLLECTION_TASK / ENF-RBC-CNIPA-GUIDANCE-CASE-1`。
- 任务包：`ENF-JOB-CNIPA-GUIDANCE-CASE-1`。
- 原始来源：国家知识产权局官方页面 `https://www.cnipa.gov.cn/art/2021/4/1/art_2432_170426.html`。
- 固化摘要：`fd1c6ffb91d32829a8393cccdf9197f7e2710d405a80baa932db10bdabf1c562`。
- 选择原因：页面含可定位的案件、机构、处理结果和理解适用正文，可覆盖 ENF-05/06/07/10/11/17 的公开输入边界；不把公开内容解释为当前岗位、内部批准、内部回执或内部评价。

## 3. 最终产品合同

### 3.1 状态与数据

- 待办记录持久化 `taskId/candidateId/packageId/title/sourceUrl/rawSha256/rawFileKey/publicPayload/collectionBatchId/state/claimedBy/organization/claimedAt/version/timestamps`。
- 工作状态为 `WAITING_REAL_HUMAN_ACTION -> READY -> RUNNING -> PASSED / FAILED`；领取是分离的 `UNCLAIMED / CLAIMED` 分配状态，不得因系统建单或测试通过改为 READY。
- 公开输入保存在唯一最终产品任务包的 `publicInput`，包含 URL、机构、发布时间、抓取时间、原始 SHA、原件定位、采集批次及 `COLLECTION_ALLOWED`。`COPYRIGHT_LICENSE / COMMERCIAL_LICENSE / LEGAL_RIGHTS`均不在此推导。
- 公开任务包不再要求旧 `SOURCE_RIGHTS + REAL_INTEGRATION` 人工事实；它只等待领取人、`ACTOR_POSITION`、`APPROVAL`、`HUMAN_RECEIPT`、`BUSINESS_EVALUATION`。旧手工任务包继续维持原六事实合同。

### 3.2 身份、授权与人工边界

- 列表只要求有效产品登录会话；领取必须同时通过领取人对确定任务包的现时 `CREATE/COLLECT/READ` grant，组织来自经 grant 核验的请求，不从公开网页推断。
- `claimedBy`等于当前 JWT subject；`ACTOR_POSITION.personRef`和`HUMAN_RECEIPT.personRef`必须等于当前 JWT subject。
- `APPROVAL`必须由与领取人不同的当前登录主体实际提交；禁止系统写入 `approved=true` 或代签。
- 每项人工事实仍须不同于提交人的当前登录主体执行核验。最终一项核验使任务包 READY 时，复用既有 `TRIGGER` grant 和幂等触发，生成且仅生成一次 `NEXT-V2-INT-ENF-01`。
- 评价模板沿用完整性、可追溯性、可操作性、事实准确性、人工投入五项 1～5 分及依据、结论；系统只校验结构，不自动评分。

### 3.3 API、页面与审计

- `GET /v2/enforcement/job-packs/collection-tasks`：返回公开候选待办及真实人工状态。
- `POST /v2/enforcement/job-packs/collection-tasks/{taskId}/claim`：用幂等键和当前身份领取，核精确 grant，创建/绑定唯一公开输入任务包。
- 现有事实提交、原件上传、异人核验和集成结果 API 不变；页面 `/app/enforcement/job-pack` 增加待办区和领取动作，领取后进入现有采集向导。
- 领取事件另存不可变审计；事实提交、核验、触发继续写 `enf8_job_pack_receipt/enf8_job_pack_event/enf8_integration_trigger`。原件存产品文件根并在读取时复算 SHA。

## 4. 失败行为与禁止副作用

| 场景 | 结果 | 禁止副作用 |
|---|---|---|
| 未登录、过期会话 | 401/403 | 不领取、不建包 |
| 缺精确 CREATE/COLLECT/READ grant 或 grant 已撤回 | 403 | 不领取、不建包、不写人工事实 |
| 原始文件缺失或 SHA 漂移 | 503/冲突 | 不领取、不把公开输入标可用 |
| 并发领取 | 仅首个 CAS 成功 | 不出现双领取或双任务包 |
| 同键同载荷重放 | 返回同一回执 | 不重复事件/包/触发 |
| 同键异载荷 | 409 | 不改变领取人或包 |
| 领取人自批 | 409 | 不写批准事实 |
| 提交人自核 | 409 | 不改变核验状态 |
| 人工事实缺失、拒绝、过期、旧内容版 | `WAITING_REAL_HUMAN_ACTION` | 不 READY、不触发 |
| 最终核验缺 TRIGGER grant | 403 | 不写 READY 快照、不触发 |

## 5. C/I/HTTP/E2E 验收

- C：公开包条件事实集、领取人自批、本人核验、旧内容版、评分范围、重放冲突和零误触发。
- I：专用 DM8 真实写入待办、领取审计、任务包修订、文件 SHA、回执/事件；撤权后失败关闭；`skipped=0`。
- HTTP：真实登录后列表、无 grant 领取 403、具名测试身份仅作机制验证的领取和事实流；不得将测试账号记为真实业务人员。
- E2E：桌面和移动宽度下可发现待办、看到五项真实性状态、领取反馈、表单标签、核验人与提交人、错误和 READY 进度。
- 真实业务完成条件：非测试真实人员实际登录，现时岗位 grant 可核，完成批准、正式回执和内部评价并逐项异人核验。此条件未发生时保持 `WAITING_REAL_HUMAN_ACTION`，但不否定机制 C/I。

## 6. 当前身份审计与 D 结论

- 2026-09-29 对执法专用业务 DM8 作非泄密检查：存在 2 个 ACTIVE subject，登录名均为本轮程序线真实 I 的 `named-procedure-dual*` 测试身份；当前进程未注入可证明现实业务人员的 ENF/DM8/JWT Secret。
- 结论：现有身份、grant、任务包、审批/回执/评价和审计能力可复用；上述输入、输出、权限、状态、失败与验收已闭合，`ARC-ENF-REAL-BUSINESS-COLLECTION-D-03`通过，放行唯一 `IMP-ENF-REAL-BUSINESS-COLLECTION-03`。
- D 通过不代表有真实人员，不把测试身份升级为真实业务身份。实施完成后 `DATA-ENF-JOB-PACK-01`预计仍为 `WAITING_REAL_HUMAN_ACTION / BLOCKED`，直到真实人员实际操作。
