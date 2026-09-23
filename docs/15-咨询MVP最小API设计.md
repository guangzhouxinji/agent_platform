# 咨询 MVP 最小 API 设计

版本：V0.1  
日期：2026-09-15  
关联任务：ARC-09-CON-API  
前置：[咨询 MVP 用例与服务设计](14-咨询MVP用例与服务设计.md)、[领域行为与不变量](13-咨询MVP领域行为与不变量.md)。

本文件是 V0 合约设计，不是最终 REST 标准或代码。所有路径、字段和同步方式可在 D 门通过后按技术 ADR 调整；业务结果分类、来源追溯、服务端授权和禁止行为不得在未回溯原需求时改变。

> 2026-09-22 定位更新：本文件只保留为历史 V0 API 合约，不能直接作为负责人确认后的首段任务型闭环 API 设计。持久任务状态、结果集快照、迭代检索、证据充分性、技术对照、部分完成、纠正/分支和人工回流的 API 语义必须在新的详细设计任务中重新通过 D 门；本轮不新增或冻结 API。

## 1. 全局边界

- API 版本暂定为 `/api/v0`，仅覆盖“专利相似检索/技术参考”。
- 调用方身份从服务端认证上下文取得；请求体不得声明角色、组织、DataScope、授权结果或中心资源内容。
- 写入请求必须带 `Idempotency-Key`；服务端将其与当前请求者、会话（如有）和规范化请求内容绑定。同键同内容返回原结果，同键不同内容返回冲突。
- 回答中的专利信息只来自当前获授权且仍可使用的来源。服务端不信任客户端传入的候选、来源定位或模型生成内容。
- V0 允许同步完成处理；若后续改为异步，只能增加处理引用和查询能力，不能改变下文结果语义或借此引入通用 Task 平台。

## 2. 最小端点

| 端点 | 用例 | 最小请求 | 最小响应 | 约束 |
|---|---|---|---|---|
| `POST /api/v0/consultations` | UC-01 | `question`、可选受控 `attachmentRefs`、`Idempotency-Key` | `consultationId`、`outcome`、可选 `answer` / `retrievalRequestRef` | 服务端创建会话并处理首条问题；附件仅可传受控引用 |
| `POST /api/v0/consultations/{consultationId}/inputs` | UC-02 | `content`、可选受控 `attachmentRefs`、`Idempotency-Key` | 同上 | 仅会话当前请求者可补充；更正关键条件不覆盖旧检索请求 |
| `POST /api/v0/consultations/{consultationId}/continue` | UC-02 | 无业务请求体、`Idempotency-Key` | 同上 | 仅会话当前请求者可请求跳过可选上下文；服务端判定是否允许，客户端不得传入或猜测条件 |
| `GET /api/v0/consultations/{consultationId}` | UC-01 至 UC-04 查询 | 无业务请求体 | 当前会话视图、输入摘要、未决项、最近回答及关联来源链接 | 每次读取均做当前授权检查；不返回模型隐含推理或未采用全文 |
| `GET /api/v0/consultations/{consultationId}/answers/{answerId}` | UC-04 查询 | 无业务请求体 | 确切辅助回答版本、限制、候选摘要和来源链接 | 返回前复查会话归属和当前来源可用性 |
| `GET /api/v0/consultations/{consultationId}/source-links/{sourceLinkId}` | UC-04 来源定位 | 无业务请求体 | 来源定位、当前可用性和允许展示范围 | 重新授权；不以链接 ID 绕过资源访问；失效时不返回缓存正文 |

不提供：客户端直接创建检索任务、直接提交候选、直接提交回答、权限管理、中心专利写入、人工复核决定、正式意见或法律结论接口。

## 3. 结果对象

所有创建/补充请求和读取的最新处理结果使用同一 `outcome` 语义：

| `outcome.type` | 最小内容 | 业务含义 |
|---|---|---|
| `NEEDS_CLARIFICATION` | `issues`、`questions`、各问题的输入来源链接 | 缺少必要条件、指代不清或存在冲突；未执行检索 |
| `OUT_OF_SCOPE` | `message`、`supportedScope` | 当前不属于 V0 专利技术参考；未调用未实现工具 |
| `ACCESS_DENIED` | `message`、不泄露正文的 `decisionRef`、`traceRef` | 当前没有可用授权或授权不可判定；未读取受限来源 |
| `NO_RESULT` | `retrievalRequestRef`、`actualScope`、`limitations` | 在明确实际范围内未返回候选，不等于不存在相关技术 |
| `PARTIAL_RESULT` | `retrievalRequestRef`、可使用候选、`actualScope`、`limitations` | 部分来源/步骤失败、范围受限或跳过可选上下文；只给出有依据部分 |
| `SOURCE_UNAVAILABLE` | `retrievalRequestRef`、受影响范围、`limitations`、`traceRef` | 已用或拟用来源失效，回答已降级或停止；不等于权利失效 |
| `TOOL_FAILURE` | 不含源正文的 `message`、`traceRef` | 工具故障、超时、限流或源能力异常；不等于无结果 |
| `ANSWER_READY` | `answer`、`retrievalRequestRef`、`actualScope` | 形成带来源、明确限制和辅助性质的回答 |

`answer` 仅包含：`answerId`、`version`、`content`、`sourceLinks`、`limitations`、`professionalReviewRequired` 和可展示的候选摘要。每个 `sourceLink` 至少含 `sourceLinkId`、来源类型、稳定对象引用、版本引用和位置；不携带未经当前授权确认的全文。

## 4. 参数与校验

| 项目 | 服务端要求 | 来源 |
|---|---|---|
| 问题/补充内容 | 非空；原始文本保留；模型提取不能覆盖用户文本 | CON-04、CON-11、CON-12 |
| 附件引用 | 必须是受控引用并在服务端校验会话归属和当前权限；不接受客户端上传的“已解析内容” | CON-12 |
| 检索目的 | V0 固定为技术参考；其他目的返回范围外或补问，不默认变为新颖性/侵权分析 | CON-04；V0 |
| 技术条件 | 只有来自用户输入或明确补充确认的条件可进入检索请求；冲突或无法形成请求的必要缺失先返回补问。仅缺可选上下文时可由服务端形成有限请求并披露限制 | CON-04、CON-11、CON-12；项目负责人 2026-09-19 V0 决策 |
| 请求幂等 | 所有写入绑定请求者、会话和内容；冲突不能静默覆盖或重复工具调用 | CON-04、CON-12 |
| 来源访问 | 候选召回、回答读取和来源链接解析均使用当前授权；前端不能传“允许访问” | CON-02、CON-04、CON-12 |

## 5. 错误与隐私边界

| 情况 | API 行为 |
|---|---|
| 请求格式不合法 | 返回参数错误；不创建会话或检索请求 |
| 会话或来源不属于当前请求者 | 返回不泄露资源存在性的拒绝结果；不得仅凭 ID 放行 |
| 幂等键内容冲突 | 返回冲突，并保持原请求和原结果不变 |
| 授权拒绝或不可判定 | 返回 `ACCESS_DENIED`，不返回正文、匹配片段、缓存或候选详情 |
| 来源失效/撤回 | 返回 `SOURCE_UNAVAILABLE` 或 `PARTIAL_RESULT`，不把失效说明为权利失效或无结果 |
| 检索/来源工具失败 | 返回 `TOOL_FAILURE` 或 `PARTIAL_RESULT`，附追踪引用，不伪造空结果 |
| 回答需要专业采用 | 返回 `professionalReviewRequired=true`；不返回“已受理/已审核/正式意见” |

## 6. 三样例 API 验证

| 样例 | 请求 | 预期 API 结果 |
|---|---|---|
| 正常 | `POST /consultations` 提交完整技术方案和技术参考目的 | `ANSWER_READY`；候选、实际范围和每项匹配依据均有来源链接 |
| 可选补充 | `POST /continue` 请求跳过未填写的可选上下文 | `PARTIAL_RESULT` 或 `NO_RESULT`；检索请求仅使用原问题，并明确未补充的上下文和实际范围 |
| 冲突 | `POST /consultations` 或 `POST /inputs` 提交温度冲突内容，或 `POST /continue` 请求跳过冲突 | `NEEDS_CLARIFICATION`；包含两个冲突来源，不包含检索请求/候选 |
| 无权限 | 提交可检索条件但授权端口拒绝 | `ACCESS_DENIED`；不含专利正文、候选、匹配片段 |
| 来源失效 | 读取回答或来源链接时，来源复查不可用 | `SOURCE_UNAVAILABLE` 或降级后的 `PARTIAL_RESULT`；失效候选不在回答依据中 |

## 7. 暂不冻结

不冻结认证技术、HTTP 状态码、分页、事件协议、超时、重试、异步处理引用结构、附件上传、错误码格式、API 网关、OpenAPI 文件、中心 API 映射和前端页面。它们属于后续技术任务，且不得改变本文件的业务结果和访问边界。
