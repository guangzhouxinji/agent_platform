# 执法 MVP 最小 API 设计

版本：V0.1  
日期：2026-09-15  
关联任务：`ARC-09-ENF-API`  
前置：`docs/23-执法MVP用例与服务设计.md`、`docs/22-执法MVP领域行为与不变量.md`。  
设计性质：合成 V0 的最小 HTTP 合约；不代表真实中心 API、最终 REST 标准或 OpenAPI 交付件，不冻结 DM8、异步运行模型、认证协议或代码。

## 1. 全局边界

- API 候选前缀为 `/api/v0/enforcement`，只覆盖一条已有线索到本地辅助分析版本及测试身份审核记录。
- 身份、机构和 `requestId` 由服务端可信上下文提供；请求体不得传角色、DataScope、授权结果、审核人员或“正式决定”字段。
- 所有写操作必须携带 `Idempotency-Key` 请求头；同主体、端点语义和规范化输入绑定，同键同输入返回原结果，同键不同输入返回冲突。
- 所有修订、确认或退回操作必须携带 `expectedVersion`；版本冲突不覆盖当前数据。
- 客户端只能提交权威资源标识/受控上传引用，不能提交“已验证来源”“已采信证据”“已确认事实”或模型产出冒充服务端结果。
- V0 可以同步返回结果。后续若因耗时改为异步，只能增加技术执行引用和查询端点，不改变业务结果、版本、异常或人工边界。
- API 不提供权限管理、正式案件状态、证据采信、立案、违法/侵权认定、程序决定、处罚、文书签发、送达、执行或中心写回端点。

## 2. 通用响应外壳

成功响应：

```json
{
  "requestId": "req-local-001",
  "resultType": "WORK_ITEM_CREATED",
  "data": {},
  "limitations": ["SYNTHETIC_V0", "NOT_OFFICIAL_DECISION"]
}
```

失败响应：

```json
{
  "requestId": "req-local-001",
  "error": {
    "code": "SOURCE_UNAVAILABLE",
    "message": "当前来源不可用",
    "retryable": true,
    "fieldPath": null,
    "traceRef": "trace-local-001"
  }
}
```

`message` 和 `traceRef` 不得包含凭据、受限正文、磁盘路径或可用于绕过资源访问的信息。HTTP 状态码的最终映射留到实现任务，但相同业务错误必须保持稳定 `code`。

## 3. 最小端点

| 端点 | 对应用例 | 最小请求 | 最小响应 | 关键约束 |
|---|---|---|---|---|
| `POST /api/v0/enforcement/work-items` | OpenEnforcementWork | `clueRef`、可选 `caseRef/rightRefs`；请求头幂等键 | `workItemId`、固定引用、`version=1`、范围限制 | 服务端读取并校验来源；不接收正文、权限或正式状态 |
| `POST /work-items/{workItemId}/clue-analyses` | GenerateClueAnalysis | `expectedWorkItemVersion`；请求头幂等键 | 解析版本、候选字段、来源定位、缺失/冲突、`priority=TO_BE_EVALUATED` | 仅服务端来源/分析端口产生候选；V0 不接受客户端优先级规则 |
| `POST /work-items/{workItemId}/clue-analyses/{analysisId}/actions` | ReviseOrReviewClueAnalysis | `action`、`expectedVersion`、可选 `changes`、`reason`；请求头幂等键 | 新解析版本或审核记录、确切被操作版本、测试范围标识 | `RETURN` 必须有理由；可信人员来自上下文 |
| `POST /work-items/{workItemId}/evidence-references` | RegisterEvidenceReference | `sourceKind`、`sourceRef` 或 `controlledUploadRef`、关联引用；请求头幂等键 | 本地引用、源版本、收集信息/缺失原因、完整性、派生关系、可用性 | 不接受客户端“真实性/合法性/证明力/已采信”字段 |
| `POST /work-items/{workItemId}/evidence-references/{evidenceRefId}/refresh` | RegisterEvidenceReference/失效传播 | `expectedVersion`；请求头幂等键 | 当前可用性、新来源引用版本或受影响结果引用 | 只刷新权威状态，不改写历史证据版本或审核记录 |
| `POST /work-items/{workItemId}/relation-sets` | BuildEvidenceRelations | 解析版本、证据版本数组、规则版本引用；请求头幂等键 | 关系集版本、固定输入、事项/关系、时间线、缺口、冲突、未处理项、完整性 | 规则由服务端规则端口取得；不接受客户端候选关系作为已确认事实 |
| `POST /work-items/{workItemId}/auxiliary-analyses` | GenerateAuxiliaryAnalysis | 解析/关系集/证据/规则/可选权利状态版本；请求头幂等键 | 分析版本、逐项事实/陈述/推断、依据、缺口、不确定性、受限结果和非正式标识 | V0 不输出严重程度、处罚或正式程序 |
| `POST /work-items/{workItemId}/auxiliary-analyses/{analysisResultId}/actions` | ReviewAuxiliaryAnalysis | `action`、`expectedVersion`、可选 `changes`、`reason`；请求头幂等键 | 新分析版本或审核记录、`notOfficialDecision=true` | Agent/模型不能形成可信人工动作 |
| `GET /work-items/{workItemId}` | GetEnforcementWorkView | 可选 `include` 查询项 | 工作项摘要、各对象最新可访问版本引用、缺口/冲突/需复核原因 | 每次读取当前授权；不返回模型隐含推理 |
| `GET /work-items/{workItemId}/{resource}/{resourceId}/versions/{version}` | GetEnforcementWorkView | 无业务请求体 | 确切解析/证据引用/关系集/分析版本及允许展示的审核记录 | `resource` 仅允许固定白名单；不使用“最新”替代历史审核对象 |
| `GET /work-items/{workItemId}/source-links/{sourceLinkId}` | GetEnforcementWorkView/来源打开 | 无业务请求体 | 来源标识、固定版本、定位、当前可用性和允许展示内容 | 重新授权；客户端不能拼接文件路径；失权时不返回缓存正文 |

除创建工作项外的路径省略相同 `/api/v0/enforcement` 前缀。V0 不提供批量处理、通用任务队列、通用规则编辑、证据物理删除和中心回写。

## 4. 写请求字段边界

### 4.1 资源引用

`clueRef/caseRef/rightRefs/sourceRef/ruleVersionRef` 只允许包含调用方获知的来源类型、稳定对象标识和请求版本。服务端必须从对应端口读取并确认来源、实际版本、关联、正文和当前权限；客户端传入的标题、正文、来源名称或版本声明不作为权威事实。

### 4.2 人工动作

`action` 的 V0 候选值：

| 被操作对象 | 允许动作 | 必要输入 | 输出语义 |
|---|---|---|---|
| 线索解析版本 | `REVISE`、`CONFIRM`、`RETURN` | `expectedVersion`；修订内容或退回理由 | 新版本或绑定确切版本的测试审核记录 |
| 辅助分析版本 | `REVISE`、`SUBMIT`、`CONFIRM`、`RETURN` | `expectedVersion`；修订内容或退回理由 | 新版本或绑定确切版本的测试审核记录 |

具体角色和合法状态转换尚未由 P0-04/09 确认。V0 适配器只允许显式测试身份验证机制，并强制返回 `scopeLabel=SYNTHETIC_PROCESS_TEST` 和 `notOfficialDecision=true`。

### 4.3 修订内容

修订请求只能引用原字段/分析项并提交新的人工值、来源说明或理由。不能修改权威来源标识、源版本、机器原始版本、机器产出者、既有审核人和时间。服务端创建新版本并保留前后关系。

## 5. 主要响应对象

### 5.1 `clueAnalysis`

- `analysisId`、`version`、`inputClueRef`、`previousVersionRef`、`revisionKind`。
- `subjectCandidates[]`：`roleAsStated`、`name`、可选有来源 `creditCode`、`sourceLinkId`。
- `suspectedFacts[]`、`timeCandidates[]`、`placeCandidates[]`、`evidenceMentions[]`，每项带 `sourceLinkId`。
- `missingItems[]`、`conflicts[]`、`machineOutputRef`、`createdAt`。
- 不包含 `illegalSubject`、`isIllegal`、违法概率或正式优先级决定。

### 5.2 `evidenceReference`

- `evidenceRefId`、`version`、`sourceRef`、`relatedResourceRefs`、`controlledResourceRef`。
- `collectionInfo` 或明确 `missingReason`、`integrityInfo`、`originalRef/derivedKind`。
- `availability` 和 `unavailableReason`；不返回真实磁盘路径。
- 不包含服务端自动产生的真实性、合法性、证明力或采信结论。

### 5.3 `relationSet`

- `relationSetId`、`version`、`inputManifest`。
- `issuesToProve[]`；V0 只使用合成检查项，不冒充法律要件。
- `relations[]`：事项、证据版本、`SUPPORT/OPPOSE/CONFLICT/MISSING`、理由、`sourceLinkId`。
- `timelineEntries[]`：明确 `OCCURRENCE/COLLECTION/UPLOAD` 时间语义、原始表达和可选标准化值。
- `gaps[]`、`conflicts[]`、`unprocessedItems[]`、`completeness`、`needsReviewReasons[]`。
- 不包含“完整有效证据链”或自动采信结果。

### 5.4 `auxiliaryAnalysis`

- `analysisResultId`、`version`、`inputManifest`、`previousVersionRef`。
- `itemAnalyses[]`：`confirmedFacts[]`、`partyStatements[]`、`machineInferences[]`、支持/排除依据、来源链接、缺口和不确定性。
- `auxiliaryOutcome` 仅允许 `SUSPECTED_SUPPORT/EXCLUSION_BASIS/MATERIALS_INSUFFICIENT`，并包含逐项原因。
- `unprocessedItems[]`、`failureKinds[]`、`needsReviewReasons[]`、`machineOutputRef`。
- 固定 `notOfficialDecision=true`；V0 不含 `officialDecision`、`penalty`、`officialProcedure` 或中心状态。

### 5.5 `reviewRecord`

- `reviewRecordId`、`subjectType`、`subjectId`、`subjectVersion`、`action`。
- `beforeRef/afterRef`、`reason`、可信 `actorRef`、`actedAt`。
- V0 固定 `scopeLabel=SYNTHETIC_PROCESS_TEST`、`notOfficialDecision=true`。

## 6. 业务结果类型

| `resultType` | 最小内容 | 含义 |
|---|---|---|
| `WORK_ITEM_CREATED` | 工作项和固定来源引用 | 本地辅助工作已创建，不等于立案 |
| `CLUE_ANALYSIS_READY` | 解析版本、来源、缺失/冲突 | 候选解析可供人工核实，不等于事实认定 |
| `EVIDENCE_REFERENCE_READY` | 证据引用、版本、完整性、可用性 | 已形成受控引用，不等于真实合法或采信 |
| `RELATION_SET_READY` | 关系集、时间线、缺口/冲突 | 候选关系可供人工检查，不等于完整证据链 |
| `AUXILIARY_ANALYSIS_READY` | 逐项分析、受限分类、来源和限制 | 待人工审核的辅助版本，不等于正式结论 |
| `REVIEW_RECORDED` | 审核记录和确切对象版本 | V0 测试动作已留痕，不等于专业或正式决定 |
| `MATERIALS_INSUFFICIENT` | 有依据部分、缺口、补证项、未处理项 | 无法完整分析，不等于“不构成” |
| `PARTIAL_RESULT` | 成功部分、失败种类、未处理项 | 处理不完整，不能显示完整成功 |
| `SOURCE_UNAVAILABLE` | 受影响来源引用、需复核原因、追踪标识 | 来源失效，不等于证据无效或业务不成立 |
| `ACCESS_DENIED` | 不泄露正文的决定引用、追踪标识 | 当前无权访问；不会返回受限内容 |

## 7. 稳定错误码

| 错误码 | API 行为 |
|---|---|
| `INVALID_INPUT` | 返回字段路径；不创建或修改对象 |
| `UNAUTHENTICATED` | 缺少可信身份；不接受请求体身份替代 |
| `UNAUTHORIZED` | 不泄露资源正文或可推断存在性的细节 |
| `NOT_FOUND` | 本地资源不存在或按安全策略不可披露 |
| `SOURCE_NOT_FOUND` | 当前有权查询且来源确认对象不存在 |
| `SOURCE_UNAVAILABLE` | 来源撤回、失效或不可访问；与空数据分开 |
| `UPSTREAM_FAILURE` | 中心/文件/规则服务技术失败；不伪装空结果 |
| `UNSUPPORTED_CONTENT` | 材料格式不可处理，加入未处理项 |
| `RULE_NOT_APPLICABLE` | 固定规则不适用；不是“不构成” |
| `MATERIALS_INSUFFICIENT` | 事实/证据/历史状态不足，返回缺口 |
| `PARTIAL_RESULT` | 部分项目失败，返回成功部分和未处理项 |
| `VERSION_CONFLICT` | 预期版本过期，不覆盖当前内容 |
| `IDEMPOTENCY_CONFLICT` | 同键不同规范化输入，不重放旧结果 |
| `INVALID_ACTION` | 对象或当前 V0 范围不允许该动作 |
| `REASON_REQUIRED` | 退回或需要理由的修订缺少原因 |
| `ANALYSIS_FAILURE` | 候选输出结构无效或处理失败，不产生伪结果 |

## 8. 来源访问与隐私

1. 所有业务响应只返回本地 `sourceLinkId`，不返回磁盘路径、中心凭据或可绕过鉴权的永久 URL。
2. `GET source-links/{sourceLinkId}` 根据当前主体、机构、动作和源资源重新授权；历史工作项所有权不足以放行来源正文。
3. 无权限时返回 `ACCESS_DENIED`，不返回受限片段；来源失效时返回 `SOURCE_UNAVAILABLE` 并标记受影响结果需复核。
4. 允许展示的来源内容必须绑定实际源版本和具体位置；模型产生的“来源”被拒绝。
5. V0 不实现真实文件上传/下载。合成受控资源只验证接口边界，不能升级 P0-07。

## 9. 三样例 API 验证

| 样例 | 请求序列 | 预期结果 |
|---|---|---|
| 正常 | 创建工作项 → 生成/确认解析 → 登记两份证据 → 形成关系集 → 生成辅助分析 → 确认确切版本 → 读取 | 每步返回固定版本；来源可打开；辅助分析和审核均带非正式/合成限制；无中心状态写入。 |
| 资料不足/冲突 | 创建 → 解析 → 登记三份材料 → 关系集 → 分析 → 退回 | 冲突来源同时存在；未知值为空；不可解析材料为未处理项；返回 `MATERIALS_INSUFFICIENT/PARTIAL_RESULT` 和补证事项。 |
| 无权限 | 创建或证据/来源读取时授权拒绝 | `ACCESS_DENIED`，不含正文、片段或缓存。 |
| 来源失效 | 已有关系/分析后刷新证据，或读取来源时发现失效 | `SOURCE_UNAVAILABLE`；旧版本保留并需复核；新关系/分析不继续使用失效证据。 |
| 幂等 | 同键同输入重放；同键不同输入重放 | 前者返回同一业务结果，后者 `IDEMPOTENCY_CONFLICT`。 |
| 并发审核 | 两个请求使用同一 `expectedVersion` 修订 | 一个成功形成新版本，另一个 `VERSION_CONFLICT`；历史不覆盖。 |

## 10. 当前不提供的 API

- 中心线索、案件、证据、权利、法规或规则的写入/删除 API。
- 权限、用户、角色、机构或 DataScope 管理 API。
- 证据真实性、合法性、证明力或采信决定 API。
- 正式立案、违法/侵权认定、程序选择、处罚、文书、签发、送达、执行或正式状态 API。
- 全量线索发现、规则配置、流程合规、处罚建议和文书生成 API。
- 通用 Agent、Workflow、Task、Run、Prompt 或模型配置 API。

## 11. 暂不冻结

不冻结认证技术、具体 HTTP 状态码、分页、异步任务、回调/事件、超时重试、上传协议、文件限额、OpenAPI 文件、中心字段映射、前端页面、数据库和部署。后续技术决定不得改变本文的可信身份、来源、版本、异常、人工审核对象和正式决定边界。

## 12. 需求一致性复核

| 检查项 | 结果 |
|---|---|
| 涉及需求 | ENF-04、ENF-05、ENF-07、ENF-11 |
| 用例映射 | 11 个端点覆盖 `docs/23` 的 8 项用例；未增加业务能力 |
| 可信边界 | 身份、权限、来源、规则、人工人员和正式状态均不能由客户端请求伪造 |
| 无来源业务行为 | 0；路径、错误码和外壳是技术设计，业务字段均回链领域对象/用例 |
| MUST_NOT 违规 | 0；无自动采信、正式认定、处罚、中心写入或失权内容读取端点 |
| 未进入范围 | DM8、真实中心 API、真实认证、异步 Task、文件能力、专业规则和代码 |
| 验证范围 | 合约语义与三类合成样例；未执行 HTTP、真实接口、权限、模型或业务验收 |

结论：最小 API 能表达 8 项应用用例和三类 V0 样例，且没有向客户端开放权限、来源、人工决定或正式状态的伪造路径。该结论只完成 `ARC-09-ENF-API`；下一步可以设计逻辑持久化，仍不得直接写 DM8 SQL 或业务代码。
