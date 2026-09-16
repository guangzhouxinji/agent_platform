# 执法 MVP HTTP 与 DM8 仓储实现任务设计

版本：V0.3  
日期：2026-09-15  
关联任务：`MVP-ENF-V0-03-DESIGN`  
目标实现任务：`MVP-ENF-V0-03`  
设计输入：`docs/20` 至 `docs/27`、`ARC-10-ENF-PHYSICAL-FIX`、现有执法领域/应用代码。  
设计性质：只为合成 V0 的本地 DM8 仓储和 HTTP 适配器申请窄范围 D 门；不批准真实中心 API、真实身份、文件、模型、专业规则、正式流程或中心写回。

## 1. 本次实现闭环

```text
可信测试请求头
  -> HTTP DTO 校验
  -> EnforcementApplicationService
  -> 当前授权/合成来源端口
  -> EnforcementStore
  -> 单事务追加 19 张 enf_* 表
  -> 重启后按确切版本恢复 EnforcementSnapshot
  -> 稳定 HTTP 结果或错误
```

保持现有领域行为不变。HTTP 和 JDBC 都是适配层；领域与应用包不得依赖 Spring Web、JDBC、DM8 驱动、容器或供应商 SDK。

## 2. 仓储端口收敛

### 2.1 幂等语义

现有端口在实现时收敛为以下语义，具体 Java 方法名允许按现有代码风格调整：

- 查找键：`actorRef + operationCode + idempotencyKey`。
- 保存输入：同一三元组、请求指纹、完整快照和结果引用。
- 同键同指纹返回原结果，不新增领域记录；同键不同指纹抛出 `IDEMPOTENCY_CONFLICT`。
- `operationCode` 由应用用例固定，客户端不能提交。允许不同端点各自使用相同文本幂等键。
- 内存与 DM8 实现必须执行同一语义；不得因适配器不同改变冲突范围。

`StoredWork` 继续返回可信 `ownerRef` 和完整快照。DM8 从 `enf_work_item.owner_ref` 恢复所有者，不从最近操作人、审核人或请求体推断。

### 2.2 写入顺序和原子性

每次 `save` 使用单连接、关闭自动提交，并按下列顺序执行：

1. 查询幂等三元组；命中时校验指纹并直接返回，冲突时不写入。
2. 锁定已有 `enf_work_item`；首次创建时先写全部必要 `enf_source_locator`，再写工作项。
3. 对快照中的来源定位按主键插入缺失项；相同主键不同内容视为数据冲突，不更新旧值。
4. 依次追加 `enf_clue_analysis`、`enf_clue_field`、`enf_clue_issue`、`enf_clue_conflict_candidate`。
5. 追加 `enf_evidence_ref`。
6. 追加 `enf_relation_set`、`enf_relation_input`、`enf_evidence_relation`、`enf_timeline_entry`、`enf_relation_issue`。
7. 追加 `enf_aux_analysis`、`enf_analysis_input`、`enf_analysis_item`、`enf_analysis_issue`。
8. 追加 `enf_review_record` 和 `enf_review_impact`。
9. 增加工作项技术 `record_version`，最后写 `enf_idempotency_record` 并提交。

任一步骤失败必须整笔回滚。不得更新或删除既有解析、证据、关系、分析、审核和影响记录。已存在的相同主键必须逐字段一致；仅凭“主键已存在”跳过会掩盖历史篡改。

### 2.3 读取和无损映射

读取先取工作项和所有者，再按 `(work_item_id, version, item_order)` 读取各历史集合。所有 `source_id` 必须解析成 `SourceLocator`；缺失外键或非法枚举作为存储损坏失败，不转换为空列表。

| 领域数据 | 物理记录 | 读取要求 |
|---|---|---|
| `EnforcementWorkItem`、所有者 | `enf_work_item` + 线索来源 | 保留组织、创建时间、合成范围和可信所有者 |
| 解析版本与候选字段 | `enf_clue_analysis/field` | 按字段种类和顺序恢复主体、事实、时间、地点和证据提及 |
| 缺失与冲突 | `enf_clue_issue/conflict_candidate` | 冲突候选的值、标准化值、时间语义、顺序和来源无损恢复 |
| 证据引用 | `enf_evidence_ref` | 保留来源版本、受控引用、关联、收集缺失原因、三类时间和可用性 |
| 关系集 | `enf_relation_set/input/evidence_relation/timeline_entry/relation_issue` | 固定解析、规则和证据版本；缺口、冲突、未处理项分类恢复 |
| 辅助分析 | `enf_aux_analysis/input/analysis_item/analysis_issue` | 固定全部输入；事实/陈述/推断及限制分离；始终非正式 |
| 人工记录与影响 | `enf_review_record/review_impact` | 绑定确切对象版本，只追加；实现前将字符串影响收敛为结构化领域记录 |
| 幂等结果 | `enf_idempotency_record` | 同事务保存不可变 `snapshot_payload`；回放返回该次写操作的确切快照，不使用进程缓存；普通工作项读取仍从 19 张规范化表重建 |

V0 中可由数据库约束和固定范围安全恢复的常量为 `SYNTHETIC_V0`、`SYNTHETIC_PROCESS_TEST` 和 `notOfficialDecision=true`。其他缺失字段不得猜测。形如 `id@version` 的现有领域引用必须通过唯一解析器校验，格式错误直接失败。

## 3. HTTP 实现范围

### 3.1 可信上下文

- 本地 V0 使用 `X-V0-Actor` 和创建时的 `X-V0-Organization` 请求头模拟可信网关上下文；请求体不接受主体、机构、角色、授权结果或审核人。
- 所有写操作要求 `Idempotency-Key`；缺失或空值为 `INVALID_INPUT`。
- 可选 `X-Request-Id` 只用于响应关联；不得写入业务对象或作为授权依据。
- 这些请求头只用于本地合成验证，不代表真实认证协议通过 P0-04。

### 3.2 纳入端点

| HTTP 端点 | 应用方法/用途 | 最小输入 | 版本与禁止边界 |
|---|---|---|---|
| `POST /api/v0/enforcement/work-items` | `openWork` | 请求头身份/机构/幂等键；`clueId`、`clueVersion` | 不接收正文、权限或正式状态 |
| `POST /api/v0/enforcement/work-items/{id}/process` | 合成 V0 组合调用 `process` | 请求头身份/幂等键；`evidenceIds[]` | 一次原子形成新解析、证据引用、关系集和辅助分析；只组合既有用例，不新增业务能力 |
| `POST /api/v0/enforcement/work-items/{id}/clue-analyses/{analysisId}/actions` | `reviseClue` | `action=REVISE`、`expectedVersion`、`reason` | 路径对象必须是当前被修订版本；旧版本保留 |
| `POST /api/v0/enforcement/work-items/{id}/auxiliary-analyses/{analysisId}/actions` | `reviewLatestAnalysis` | `action`、`expectedVersion`、`reason` | 可信测试审核人来自请求头；固定非正式标识 |
| `GET /api/v0/enforcement/work-items/{id}` | `read` | 可信身份 | 每次重新授权；返回完整可访问历史，不返回模型隐含推理 |
| `GET /api/v0/enforcement/work-items/{id}/{resource}/{resourceId}/versions/{version}` | 精确版本读取 | 可信身份；资源白名单 | 只允许 `clue-analyses`、`evidence-references`、`relation-sets`、`auxiliary-analyses` |
| `GET /api/v0/enforcement/work-items/{id}/source-links/{sourceLinkId}` | 来源定位读取 | 可信身份 | 重新授权；只返回合成来源元数据和受控位置，不返回正文、磁盘路径或永久 URL |

`/process` 是本地合成 V0 的显式组合端点，因为当前已验证的应用服务原子生成四类中间结果。它不替代 `docs/24` 中未来按用例拆分的解析、证据登记、关系集和分析端点，也不得进入真实 API 合约。

### 3.3 本次不实现的候选端点

- 独立 `POST clue-analyses`、`POST evidence-references`、`refresh`、`POST relation-sets`、`POST auxiliary-analyses`。
- 原因不是这些需求已取消，而是当前合成服务没有经验证的分步状态与中间提交行为；强行暴露会制造未经设计的新状态机。
- `MVP-ENF-V0-04` 若需要分步交互，必须先新增稳定任务和 D 门，不得把 `/process` 描述成生产合同。

## 4. 响应和错误映射

成功与失败使用 `docs/24` 的统一外壳。成功 `data` 只由响应 DTO 投影，不直接序列化领域对象，固定携带 `SYNTHETIC_V0` 与 `NOT_OFFICIAL_DECISION` 限制。

| 应用错误 | HTTP | 对外规则 |
|---|---:|---|
| `INVALID_INPUT`、`INVALID_ACTION`、`REASON_REQUIRED` | 400 | 可返回安全字段名，不回显正文 |
| `UNAUTHENTICATED` | 401 | 缺少可信测试身份 |
| `UNAUTHORIZED` | 403 | 不泄露资源是否存在或内容 |
| `NOT_FOUND`、`SOURCE_NOT_FOUND` | 404 | 消息稳定且不含内部路径 |
| `VERSION_CONFLICT`、`IDEMPOTENCY_CONFLICT` | 409 | 原结果与历史不变 |
| `SOURCE_UNAVAILABLE`、`UPSTREAM_FAILURE`、`ANALYSIS_FAILURE` | 503 | 保留稳定业务错误码和 `retryable` 分类 |
| 未分类异常 | 500 | 只返回通用错误和追踪引用；日志不得含凭据/受限正文 |

`MATERIALS_INSUFFICIENT`、`PARTIAL_RESULT`、`UNSUPPORTED_CONTENT` 和 `RULE_NOT_APPLICABLE` 在存在可用结果时是业务结果，不一律转换成 HTTP 失败。

## 5. 配置与装配

- 新增独立 `EnforcementStorageConfiguration`，属性 `enforcement.storage=memory|dm8`，默认 `memory`。
- DM8 实现通过 `DataSource` 获取连接；领域与应用层不读取环境变量。
- `EnforcementApplicationService`、合成来源和 V0 授权适配器由执法配置装配，不复用咨询业务 Bean 或表。
- 测试可替换时钟和 ID 生成器；生产默认值不影响历史版本语义。

## 6. 最小自动测试

| 测试 ID | 场景 | 必须断言 |
|---|---|---|
| ENF-DM8-T01 | 首次保存与重启读取 | 所有者、工作项和空历史一致；进程缓存清空后仍可读取 |
| ENF-DM8-T02 | 正常处理快照往返 | 解析、证据、关系、时间线、分析和来源定位逐字段一致 |
| ENF-DM8-T03 | 冲突/资料不足往返 | 两个冲突候选值/顺序/来源、未知收集原因和未处理项一致 |
| ENF-DM8-T04 | 人工修订/审核追加 | 旧版本保留，确切版本审核可恢复，非正式标识为真 |
| ENF-DM8-T05 | 来源失效影响 | 旧分析/审核保留，新分析排除失效来源，影响记录可恢复 |
| ENF-DM8-T06 | 幂等重放与冲突 | 同操作同键同输入返回原结果；不同输入冲突；不同操作可复用键 |
| ENF-DM8-T07 | 原子回滚 | 注入子表失败后工作项版本、领域记录和幂等记录均不增加 |
| ENF-DM8-T08 | 内存/DM8 合约一致 | 同一应用场景的快照、错误码和所有权结果一致 |
| ENF-HTTP-T01 | 创建、组合处理、读取 | HTTP 状态、结果类型、版本、限制与完整历史正确 |
| ENF-HTTP-T02 | 修订和审核 | 路径对象/预期版本校验，旧版本保留，测试审核边界存在 |
| ENF-HTTP-T03 | 精确版本与来源定位 | 白名单生效；当前授权复查；无正文、路径或永久 URL 泄露 |
| ENF-HTTP-T04 | 输入、权限、版本和幂等错误 | 400/401/403/404/409 稳定映射且无敏感详情 |
| ENF-HTTP-T05 | 正式决定禁止面 | DTO、控制器和应用方法不存在采信、认定、处罚、签发或中心写回入口 |

实现完成前后均运行现有 13 项执法领域/架构测试、咨询 Java/DM8 回归、咨询浏览器回归、治理检查和进度看板检查。

## 7. 明确排除

- 真实中心线索、案件、证据、权利、法规、规则 API 与任何中心写回。
- 真实 SSO、DataScope、权限管理、测试身份之外的角色/机构结论。
- 真实文件上传、下载、OCR、转码、预览、物理路径和文件保留。
- 真实模型、提示词、专业规则、法律要件、效果指标与专业验收。
- 自动证据采信、事实确认、立案、违法/侵权认定、程序选择、处罚、文书签发、送达、执行或正式状态。
- 异步任务、批量处理、分页、性能容量、备份恢复、中心 DM8 兼容和生产迁移。

ENF-04/05/07/11 的需求台账仍保持“未执行/未验收”；本任务只能增加合成、本地、技术验证证据。

## 8. D 门评估

| 检查项 | 结论 |
|---|---|
| 稳定 Task、依赖、退出条件 | `MVP-ENF-V0-03` 已登记，依赖本任务和已完成的物理修正 |
| 原需求与 R 门 | ENF-04/05/07/11 契约为需求就绪，47 文件摘要未变化 |
| 端口与物理映射 | 19 张表的写入、读取、追加、所有者、冲突候选和回滚顺序已明确 |
| HTTP 范围 | 6 个既有候选端点和 1 个显式本地组合端点；未实现端点及原因明确 |
| 身份、来源、版本和错误 | 可信请求头仅限测试；当前授权、精确版本、幂等和稳定错误映射可测试 |
| 新增业务行为 | 0；`/process` 只组合已通过测试的应用方法，不引入正式或专业状态 |
| 未决事项 | 真实接口、权限、文件、模型、规则、中心 DM8 和验收全部排除，不阻塞合成本地实现 |
| 回归与禁止行为 | 13 项现有执法测试保留，并新增 13 项仓储/HTTP 最小检查 |

结论：`MVP-ENF-V0-03` 的 D 门仅对本文限定的合成 V0、本地 DM8 仓储和 7 个 HTTP 端点通过。允许实现仓储端口收敛、`Dm8EnforcementStore`、执法 Spring 装配、HTTP DTO/控制器和自动测试；不得将该结论外推为 ENF-04/05/07/11 整项设计完成、真实接入、专业验证或业务验收。
