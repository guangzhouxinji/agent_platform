# Skill、Tool 与 API/事件边界

`ADR-V2-02/04/12`。Skill 是有目标、前置条件、产物和停止条件的业务步骤组合；Tool 是受控、可审计的单一能力调用。原需求的 41 项功能不等于 41 个 Skill，Prompt 不等于 Skill，Agent 不直接拥有业务数据或正式决定。

## Skill 契约

```text
Skill {id, immutableVersion, domain, goalTypes, inputContract,
       preconditions, allowedToolRefs, outputArtifactTypes,
       evidenceRequirements, humanGates, failurePolicy,
       budgetPolicy, stopConditions, evalSuiteRef, owner, releaseState}
```

- 咨询首批：`PatentReference`（理解/受限检索/候选/证据）、`PatentFeatureComparison`（确认目标/选权利要求/逐项对照）、`ConsultationAnswer`（按 Goal 汇总并披露限制）；商标、案例另有专用 Skill，不能复用专利文本评分冒充图像或案例法律适用。
- 执法候选：`ClueTriage`、`EvidenceMatterMapping`、`ProcedureCheck`、`DocumentDraft`、`PenaltyProposal`；裁决候选：`IssueAndLawMatch`、`PatentStatusAndRisk`、`ElementEvidenceComparison`、`ReportDraftAndReview`。命名是逻辑边界，不是全部首期实施承诺。
- Skill 可以建议下一步骤、补问和人工请求，不能绕过 Tool 的当前授权和领域不变量。规则、模板、知识源和 Prompt 是不可变依赖版本，不将未发布规则编进 Prompt。

## Tool 契约与动作分级

| 类型/例子 | 输入输出责任 | 权限与重试 |
|---|---|---|
| Source/Query：查专利/商标/案例、法条、案件/线索/证据 | 返回来源对象版本、实际范围、截断、错误、原文定位 | 只读、当前身份/用途授权；可幂等有界重试 |
| Extract/Compare：OCR、特征提取、候选排序、逐项对照 | 输出候选/结构及原文位置、质量和未处理项 | 经批准模型通道；输出不是权威事实 |
| Rule/Evaluate：期限、金额、适用条件、模板完整性 | 返回发布规则版本、输入、每步计算及无法判断原因 | 规则只读执行，规则发布单独有权动作 |
| Artifact/Save：保存草稿、修订、依赖、候选集 | 新版本与冲突；保留原始和人工版 | 领域服务写本地工作对象，不写源主档 |
| Human/Request：ASK、CONFIRM、REVIEW、TAKE_OVER | 发送请求、真实受理/决定回执 | 受理与决定需可信身份，Agent 只可请求 |
| Authority/Sync：正式案件/签发状态读取、受控回执 | 返回外部状态/版本/回执及对账状态 | 只由独立授权适配和人工发起；无审批不得写回 |

每个 Tool 描述 `toolId/version`、输入模式与大小、数据类别/用途许可、授权动作、幂等性、超时/限流、错误分类、输出来源、审计字段、可取消性、成本和回滚/对账方式。默认白名单与最小权限；模型建议的任意 URL、SQL、路径、脚本、工具名无效。文献/上传内容永远是数据，不是 Tool 指令。

## Rule Engine 的领域合同

`RulePackage {ruleId, immutableVersion, domain, sourceAuthority, legalBasisVersion,
scope(region/procedure/object/time), inputs, conditions, exceptions,
calculation?, precedence?, expectedOutputs, approvedBy, releaseState, boundaryTests}`。

- 管理者可编辑草稿、试算和提出发布；有权业务/法律人员审查来源、适用范围、例外、冲突和边界样例后发布。机器/Agent 不编写并直接发布法律规则。未发布、撤销、依据版本不明或范围不匹配的规则不能执行成“通过”，应返回“不适用/无法判断/需人工”。
- 选择规则时先核对事件时点、地域、职权/程序、对象与来源效力，再执行纯确定性条件。多个可适用规则重叠且无批准的优先关系时阻断，不擅取金额更大或结论更确定的规则。自然日/工作日、起算、延期/中止、金额种类、上下限、精度/舍入、减轻/免罚均需有明确规则输入和可重放步骤。
- 结果返回 `ruleVersion, applicability, inputRefs, matchedConditions, exceptions, calculationTrace, missingInputs, conflicts, legalBasisRef`；模型可解释但不能更改数值或适用结果。规则更改只影响新执行；旧分析引用旧版，紧急撤销使当前依赖成果待复核。正式裁量与事实采信仍由有权人决定。
- 发布门需固定正例、缺字段、边界、冲突、历史时点和撤销样例；同版本同输入结果一致。执法 ENF-08/10/15、裁决时点引用 ADJ-01/05/06 的领域细节分别评审，不能共享一套未批准的法律条件。

## API与事件边界

这些是**逻辑命令/事件**，非冻结 URL、数据库字段或中心接口承诺。

| 边界 | 命令/查询 | 事件/结果 | 所有者 |
|---|---|---|---|
| Task/Goal | `CreateTask`, `ConfirmGoal`, `CorrectFact`, `Cancel/Resume`, `GetTask` | GoalVersion、TaskOutcome、Invalidation | 领域 Task + Runtime |
| Source/Evidence | `QuerySource`, `ReadVersion`, `AttachEvidence`, `VerifyLocator` | SourceObserved/Changed/Revoked、EvidenceLinked | 来源适配 + 域证据服务 |
| Work/Artifact | `RunSkill`, `GetRun`, `SaveArtifactRevision`, `GetArtifact` | AttemptFinished、ArtifactProduced/Invalidated | Agent 编排 + 领域服务 |
| Rule/Release | `TestRule`, `Publish/Withdraw`, `ResolveRelease` | VersionPublished/Revoked | 有权规则/配置服务 |
| Human/Authority | `RequestReview`, `SubmitDecision`, `RecordAuthorityReceipt` | RequestAccepted/Answered、OfficialReceiptObserved | 人工系统/权威适配 |

命令带可信认证上下文、目的、期望对象版本与幂等键；错误区分不存在、无权限、来源故障、状态/版本冲突、资料不足和不可用。事件至少 `eventId/correlationId/causationId`、对象/版本、发生与记录时间、数据分类、发出方；内容采用受控引用，消费者必须再鉴权。发布历史不可变，顺序/重复靠版本与幂等处理。正式状态事件只在可信回执核验后产生，Agent 不发布伪造的“已处罚/已裁决”。

## 发布及兼容

Studio 草稿组合 Skill/Tool/Prompt/模型/知识/规则版本，检查权限、循环、缺失出口、样例与 Eval；Release 原子绑定生效组合，旧任务固定版本但运行时权限/紧急撤销实时生效。回滚先重新校验依赖，失败不切换。契约破坏性变化开新版本并做任务恢复/历史读取兼容测试；绝不通过草稿保存直接改变运行。
