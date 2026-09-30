# V2 逻辑领域模型

只定义可观察业务契约和所有权，不是 ERD/表/现有类图。原始依据：三域需求中的事实、来源、证据、任务、规则、复核及报告；技术推导见 `ADR-V2-01/03/07/10`。通用字段取目标 MVP 最小集合，三域语义不强制同构。

## 聚合与关系

| 概念 | 最小字段/关系 | 所有者与不变量 |
|---|---|---|
| Task/Goal | 目标原文、确认范围/版本、完成契约、子任务及产物引用 | 领域 Task；不改变权威案件/线索/权利状态 |
| Source | sourceSystem、sourceObjectId、document/versionId 或获准快照摘要、获取/业务时间、可访问策略与撤回信息 | 来源适配/知识资源；同名/标题不作稳定 ID；源结果原样留痕 |
| Evidence | evidenceId/version、sourceRef、locator、提取方式、完整性/可读性、提供者、事实用途 | 域内证据关系；文件哈希不证明合法/真实/采信 |
| Claim | 类型、陈述文本/结构、出处、责任主体、所需支持类型、版本 | 咨询陈述/执法疑似事实/裁决事实各域定义；机器 Claim 非官方事实 |
| Support | claimVersion、evidenceVersion、relation(`supports/refutes/context/unknown`)、覆盖要素、未覆盖要素、校核状态 | 域内；同一证据可多项，矛盾并存，证据数量不代替充分性 |
| Citation | 目标产物/段落锚点、原文 source/version/locator、引用方式/用途、校验状态 | 引用服务；原文与概括分开，历史时点不自动套现行法 |
| RuleSet/Decision | ruleId/version、依据、适用时点/地域/程序、批准者、输入/输出/计算步骤 | 领域规则；未发布/冲突/不适用不得自动运行 |
| Artifact | artifactId/version/type、goalVersion、inputs、supportLinks、完整性、复核及可用状态 | 领域成果；重跑新版本，不覆盖人工修订 |
| Review | objectVersion、assignment、evidence/缺口、真实人员决定、修订/理由、依赖版本 | 人工协作；复核状态与正式回执分离 |
| RunAttempt | task/step、实际工具/模型版本、输入摘要、预算/错误、因果事件 | Runtime 审计；不作当前权限凭证 |

`Claim → Support → Evidence → Source + Version + Locator + Provenance` 是**逻辑最小图**，不要求首期使用图数据库或给每个闲聊字词建边。只对可能改变用户行动、专业判断或报告关键事实的陈述强制支持关系。组合证据要明确各证据覆盖的要素和仍缺的要素；反证不能被排序结果吞掉。

## Artifact 生命周期与采用

候选集、事实集、技术/商品服务对照表、证据包、合规结果、文书/报告草稿和复核包由各领域定义必填项。共同包络为 `id/type/domain/goalVersion/inputVersions/contentVersion/supportRefs/limitations/createdBy/createdAt/accessPolicy/invalidationReason/reviewRef`；外部文件另存同一内容版本的摘要、渲染方式和受控引用。`草稿/缺项/待复核/已复核` 与 `来源失效/撤回` 可作为正交维度，避免一个混合 status 隐藏缺项。

创建后允许人工编辑形成新修订，记录前后差异、编辑者、依据和所影响的 Claim/引用；机器重新生成先产出候选版本，用户明确采用后才替换工作视图，**不覆盖人工章节**。导出必须从锁定内容版本生成，失败不丢正文；已复核版实质改动需重新复核。产物“被使用”要有明确下游绑定，候选报告存在不等于被采纳、复核通过不等于正式签发。读取/导出仍重验当前权限，旧 URL 不能复用历史授权。

## 四类事实及证据强度

- `用户/当事人陈述`：保留说话人、时间、版本；不因被 Agent 摘要而成为已确认事实。
- `源记录所载事实`：来源记录实际记载什么；不自动证明记载内容真实、现时有效或该案可采。
- `AI 提取/推论`：必须有输入和定位，标不确定性与未处理材料；模型“置信度”不等于业务准确率。
- `授权人员确认/权威系统回执`：记录真实身份、决定、依据、时点及作用范围；后续内容变更不自动继承旧确认。

咨询技术证据在标题/摘要级只支持主题线索，权利要求级对照要相应原文；商标图形判断要真实可读图样与商品服务资料；案例要具体程序/文书版本。执法证据还涉及收集、保管、合法性/真实性、待证事项和采信；裁决证据还涉及双方意见、争点、权利要求/法条版本及正式认定。充分性阈值由专业责任方逐任务定义 **[待确认]**。

## 血缘与变更

`SourceVersion → extractedFact/Feature → Candidate/Support → Analysis → Artifact → Review` 的边记录输入版本、生成方法、责任者和时间。来源撤回/权限收紧时当前内容访问立即受限；来源正文变更、人工纠正或规则撤销沿依赖传播 `待复核/失效`，但不物理改写历史审计。已正式签发的文件不由平台“撤销”；把变化通知权威办理流程，按补正/新文件规则处理。

读取时的有效权限 = 当前可信主体/组织/用途策略 与 输入来源许可、产物共享范围的交集；确切 DataScope 与保留/删除规则 **[待确认]**。历史运行身份、先前截图或旧链接绝不作为当前授权。跨域引用须目标域重新授权并保留原语义，不把执法证据“已核验”自动映射为裁决“已采信”。

## 领域对象扩展

- Consultation：`ResultSetSnapshot`（顺序、实际范围、查询/排序版本）、`SelectedDocumentVersion`、`PatentFeatureMatrix`、`TrademarkDimensionMatrix`、`CaseDifference`、`ConsultationAnswer`。确认的用户焦点与新结果集候选焦点分开；指代歧义需澄清。
- Enforcement：`Clue`（疑似而非立案）、`Material/PhysicalItem`、`MatterToProve`、`EvidenceChain`、`ProcedureCheck`、`InfringementAnalysis`、`PenaltyProposal`、`DocumentDraft`。待证明事项及证据采信归执法域，正式办案状态归权威系统。
- Adjudication：`CaseReference`、`PartyAssertion`、`DisputedIssue`、`PatentClaimStructure`、`LegalStatusTimeline`、`StabilityRisk`、`ElementComparison`、`LawVersionCandidate`、`ReportDraft`、`ReviewDecision`。状态与风险分栏，引用与适用判断分开。

落地前须用真实/获准样例反推各对象最小字段和版本，不能为抽象优雅一次冻结所有三域数据库。逻辑接口的例子见 [工具与事件](06-skill-tool-spec-v2.md)。
