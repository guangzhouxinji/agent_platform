# 裁决 MVP 最小 API 设计

版本：V0.1  
日期：2026-09-16  
关联任务：`ARC-09-ADJ-API`  
前置：`docs/29-裁决MVP-V0产品样例基线.md`、`docs/30-裁决MVP最小领域模型.md`、`docs/31-裁决MVP用例与服务设计.md`。  
设计性质：合成 V0 的内部 HTTP 契约；不是中心 API、正式 REST 标准或 OpenAPI 交付件，也不冻结 DM8、认证协议、真实模板、异步模型或代码。

## 1. 全局边界

- 候选前缀为 `/api/v0/adjudication`，只覆盖合成案件材料至本地草稿和测试复核记录的闭环。
- 身份、机构、`requestId` 与测试范围由服务端可信上下文提供。请求体不得传入角色、授权结论、复核人、正式状态、签发人或“已认定”布尔值。
- 所有写操作必须携带 `Idempotency-Key`。相同主体、端点和规范化输入的重放返回原结果；同键不同输入返回 `IDEMPOTENCY_CONFLICT`。
- 所有会改变既有引用、草稿或测试复核的操作必须传入 `expectedVersion`。过期版本返回 `VERSION_CONFLICT`，不得覆盖当前内容或历史版本。
- `caseRef`、`materialRef`、`legalSourceRef` 和 `templateRef` 只是受控合成来源标识。服务端从输出端口重新读取实际版本、可用性和当前授权；客户端标题、正文、版本或状态声明不作为权威事实。
- 所有 V0 业务结果必须带 `SYNTHETIC_V0` 与 `NOT_OFFICIAL_DECISION` 限制。API 不提供案件受理、事实认定、法律适用、裁决、签发、签章、送达、办结、归档、中心写回或权限管理端点。

## 2. 通用外壳与错误

成功响应：

```json
{
  "requestId": "req-local-001",
  "resultType": "DRAFT_CREATED",
  "data": {},
  "limitations": ["SYNTHETIC_V0", "NOT_OFFICIAL_DECISION"]
}
```

失败响应：

```json
{
  "requestId": "req-local-001",
  "error": {
    "code": "MATERIALS_INSUFFICIENT",
    "message": "资料不完整，不能提交测试复核",
    "retryable": false,
    "fieldPath": null,
    "traceRef": "trace-local-001"
  }
}
```

`message`、`traceRef` 和任何错误细节均不得包含受限正文、中心凭据、磁盘路径或可绕过来源访问的信息。最终 HTTP 状态码留给实现任务，但相同业务情形必须使用稳定错误码。

| 错误码 | 含义与边界 |
|---|---|
| `INVALID_INPUT` | 字段、引用格式、动作或必填理由不合法；不创建或修改记录。 |
| `UNAUTHENTICATED` / `UNAUTHORIZED` | 缺少可信身份或当前无权；后者不泄露材料/法规正文或对象存在细节。 |
| `NOT_FOUND` | 本地工作或版本不存在，或按安全策略不可披露。 |
| `SOURCE_NOT_FOUND` / `SOURCE_UNAVAILABLE` | 已获授权但来源不存在，或来源失效/撤回/暂不可用；均不等于资料为空。 |
| `UPSTREAM_FAILURE` | 合成来源、模板或生成端口技术失败；不得伪装成空结果。 |
| `MATERIALS_INSUFFICIENT` | 缺项或关键冲突使草稿/测试复核不能继续；返回允许公开的缺项与处理范围。 |
| `PARTIAL_RESULT` | 可用部分已形成，但存在未处理项；不得显示完整成功。 |
| `VERSION_CONFLICT` / `IDEMPOTENCY_CONFLICT` | 并发版本过期，或同幂等键绑定了不同规范化输入。 |
| `INVALID_ACTION` / `REASON_REQUIRED` | 当前对象不允许该动作，或退回/修订缺少必填理由。 |

## 3. 最小端点

| 端点 | 对应用例 | 最小请求 | 最小响应 | 关键约束 |
|---|---|---|---|---|
| `POST /api/v0/adjudication/work-items` | `OpenAdjudicationWork` | `caseRef`、`materialRefs[]`；幂等键 | `workItemId`、固定输入版本、`version=1`、限制 | 服务端读取并固定合成版本；不接收材料正文、权限或正式案件状态。 |
| `POST /work-items/{id}/material-inspections` | `InspectMaterials` | `expectedWorkItemVersion`；幂等键 | 材料检查版本、逐项来源、缺项、冲突与未处理项 | 只区分主张、待核实事实、缺项/冲突；不确认事实。 |
| `POST /work-items/{id}/legal-candidate-sets` | `MatchLegalCandidates` | `inspectionRef`、适用时间/程序/地域的受控引用；幂等键 | 候选集版本、确切来源、疑点、范围与限制 | 候选不等于已适用条文；不接受客户端法律结论。 |
| `POST /work-items/{id}/citations/actions` | `ReviseOrConfirmCitation` | `action`、候选/条文版本引用、`expectedVersion`、`reason`；幂等键 | 新引用版本或测试操作记录 | 仅 `SELECT`、`REVISE`、`REMOVE`；修订理由必填，不能改写历史条文版本。 |
| `POST /work-items/{id}/drafts` | `CreateDraftReport` | 材料检查/候选/引用/模板的确切版本；幂等键 | 草稿 ID、版本、结构化章节、来源映射、缺项与限制 | 固定输入快照；不产生文号、签章、裁决、送达或办结。 |
| `POST /work-items/{id}/drafts/{draftId}/actions` | `ReviseDraft` | `action`、`expectedVersion`、受控修改或候选采用引用、可选理由；幂等键 | 新草稿版本、前后引用和差异摘要 | `REVISE`/`ADOPT_REGENERATION` 只追加版本；不覆盖人工内容。 |
| `POST /work-items/{id}/drafts/{draftId}/test-reviews` | `ReviewDraftForTest` | `action=CONFIRM/RETURN`、确切草稿版本、`expectedVersion`、`reason`；幂等键 | 测试复核记录、确切被审版本、`notOfficialDecision=true` | 资料不足、冲突或失效来源阻止 `CONFIRM`；不得推进正式状态。 |
| `GET /work-items/{id}` | `GetAdjudicationWorkView` | 可选固定 `include` | 工作摘要、允许展示的当前版本引用、缺项/冲突/待复核原因 | 每次重新授权；不返回模型隐含推理。 |
| `GET /work-items/{id}/{resource}/{resourceId}/versions/{version}` | `GetAdjudicationWorkView` | 无业务请求体 | 确切材料检查、候选、引用、草稿或测试复核版本 | `resource` 仅允许白名单；历史版本不被“最新”替换。 |
| `GET /work-items/{id}/source-links/{sourceLinkId}` | `GetAdjudicationWorkView` | 无业务请求体 | 来源标识、固定版本、定位、当前可用性和允许展示内容 | 当前授权失败不返回缓存正文；失效返回 `SOURCE_UNAVAILABLE`。 |

除第一行外，路径省略相同 `/api/v0/adjudication` 前缀。V0 不提供批处理、通用任务队列、模板管理、法规写入、文件上传/下载或通用 Agent/Workflow/Runtime API。

## 4. 写入字段与版本边界

### 4.1 材料与法规来源

`caseRef/materialRefs/inspectionRef/legalSourceRef/templateRef` 只包含受控来源类型、稳定标识和请求版本。服务端必须重新校验关联、实际版本、可用性和当前授权。客户端不能把摘要、定位、来源名称、可适用性或材料完整性声明为权威输入。

### 4.2 人工动作

| 对象 | 允许动作 | 必要输入 | 输出语义 |
|---|---|---|---|
| 法律候选/引用 | `SELECT`、`REVISE`、`REMOVE` | 确切候选/条文版本、`expectedVersion`、`reason` | 新引用版本或测试操作留痕；不是法律适用结论。 |
| 草稿 | `REVISE`、`ADOPT_REGENERATION` | `expectedVersion`、受控章节修改或候选草稿引用 | 新草稿版本和差异，不覆盖历史。 |
| 草稿测试复核 | `CONFIRM`、`RETURN` | 确切草稿版本、`expectedVersion`、`reason` | 测试复核记录，固定 `notOfficialDecision=true`。 |

可信测试人员必须来自服务端上下文。V0 不能把 `CONFIRM` 表达为专业审核、裁决、签发或办结；`RETURN` 和任何引用修订理由不能为空。

## 5. 主要响应对象

- `adjudicationWork`：`workItemId`、当前允许显示的输入清单、`version`、`scopeLabel=SYNTHETIC_V0`、各对象最新可访问版本引用、缺项/冲突/待复核原因；不包含中心案件状态。
- `materialInspection`：`inspectionId/version`、固定材料版本、当事人主张、待核实事项、缺项、冲突、未处理项、`sourceLinkId`；不包含已认定事实。
- `legalCandidateSet`：`candidateSetId/version`、候选条文确切版本/位置、时间/程序/地域疑点、限制与来源链接；不包含“已适用”或法律结论。
- `citationVersion`：`citationId/version`、被选条文确切版本、定位、理由、前序版本和来源链接；不包含正式采纳/裁决状态。
- `draftVersion`：`draftId/version`、固定输入清单、合成模板版本、稳定章节锚点、章节内容、来源映射、缺项/冲突、前序版本和差异摘要；固定 `isDraft=true`。
- `testReviewRecord`：`reviewRecordId`、被审对象/确切版本、`CONFIRM/RETURN`、理由、可信测试主体、时间、`scopeLabel=SYNTHETIC_PROCESS_TEST`、`notOfficialDecision=true`。

## 6. 业务结果类型

| `resultType` | 含义 |
|---|---|
| `WORK_ITEM_CREATED` | 已创建本地合成工作记录，不等于案件受理。 |
| `MATERIAL_INSPECTION_READY` | 材料检查版本可查看，未确认任何事实。 |
| `LEGAL_CANDIDATES_READY` | 合成法规候选可供选择，未完成法律适用。 |
| `CITATION_VERSION_RECORDED` | 确切条文引用已版本化，未形成正式采纳。 |
| `DRAFT_CREATED` / `DRAFT_REVISED` | 结构化草稿版本已保存，始终不是裁决文书。 |
| `TEST_REVIEW_RECORDED` | 测试复核已留痕，未形成专业审核或签发。 |
| `MATERIALS_INSUFFICIENT` / `PARTIAL_RESULT` | 仅表达资料或处理范围，不解释为无争议、不适用或案件结论。 |
| `SOURCE_UNAVAILABLE` / `ACCESS_DENIED` | 分别表示来源状态或当前访问拒绝；两者不泄露受限正文。 |

## 7. 三类样例与技术验证

| 样例 | 请求序列 | 预期 |
|---|---|---|
| 正常 | 创建工作 → 检查材料 → 生成候选 → 选择引用 → 创建/修订草稿 → 测试复核 → 读取确切版本与来源 | 所有对象可回到固定合成来源和版本；草稿/复核均带非正式限制；没有中心状态写入。 |
| 资料不足/冲突 | 创建 → 检查含缺项/冲突的材料 → 候选/草稿准备 → 尝试确认测试复核 | 返回缺项、冲突和 `MATERIALS_INSUFFICIENT/PARTIAL_RESULT`；可保存不完整草稿，但不能 `CONFIRM`。 |
| 无权/来源失效 | 创建、读取或生成期间拒绝授权/发现来源失效 | `UNAUTHORIZED` 不含正文；`SOURCE_UNAVAILABLE` 使新草稿和确认不能采用失效来源，旧历史保留并标记待复核。 |
| 幂等与并发 | 同键同输入、同键异输入；两个相同 `expectedVersion` 的写入 | 前者重放原结果，后者 `IDEMPOTENCY_CONFLICT`；最多一个写入成功，另一个 `VERSION_CONFLICT`。 |

验证仅检查合成 API 契约语义，尚未执行 HTTP、DM8、真实身份、中心接口、模板渲染、模型或业务验收。

## 8. 当前不冻结与一致性复核

不冻结认证技术、HTTP 状态码、分页、异步任务、重试、上传/导出协议、OpenAPI、中心字段映射、DM8、前端和部署。后续实现可以增加技术执行引用，但不得突破本文的可信身份、来源、版本、草稿和非正式测试复核边界。

| 检查项 | 结果 |
|---|---|
| 涉及需求 | ADJ-04、ADJ-05、ADJ-06、ADJ-10、ADJ-11、ADJ-12 |
| 用例映射 | 10 个端点覆盖 `docs/31` 的 8 项用例；材料检查、候选与读取拆为可审计资源，没有新增业务能力。 |
| 可信边界 | 身份、权限、来源、法律适用、专业复核和正式状态均不能由客户端或模型伪造。 |
| 无来源业务行为 / MUST_NOT 违规 | 0 / 0；路径、外壳与错误码是技术设计，未提供自动裁决、签发、中心写入或正式状态入口。 |
| 未进入范围 | DM8、真实中心 API、真实模板/渲染、认证、专利分析、签发、送达、办结、归档、代码与业务验收。 |

结论：该 API 能表达三类合成样例的工作、材料、法规候选、版本化引用、草稿和测试复核链路，且不把候选、草稿或测试动作升级为正式裁决。完成 `ARC-09-ADJ-API` 后可设计逻辑持久化；仍不得直接编写 DM8 SQL 或业务代码。
