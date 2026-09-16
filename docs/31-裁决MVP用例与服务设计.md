# 裁决 MVP V0 用例与服务设计

版本：V0.1  
日期：2026-09-16  
关联任务：`ARC-05-ADJ-UC`  
设计输入：`docs/29-裁决MVP-V0产品样例基线.md`、`docs/30-裁决MVP最小领域模型.md`、ADJ-04/05/06/10/11/12。

## 用例与端口

应用层从可信测试上下文取得身份、请求追踪和幂等键，编排当前授权、固定来源版本、领域不变量和原子保存。它不信任请求中的人员、权限布尔值、正式状态或专业结论；模型适配器不能写复核记录、权限、签发或中心记录。

| 用例 | 编排 | 输出与禁止行为 |
|---|---|---|
| OpenAdjudicationWork | 授权读取合成案件/材料固定版本，创建本地工作记录。 | 工作 ID、输入清单、`SYNTHETIC`；不得创建中心案件、受理或正式编号。 |
| InspectMaterials | 固定材料版本，区分当事人主张、待核实事实、缺项和冲突。 | 材料版本/缺项清单；不得确认事实或补造日期、地域、程序。 |
| MatchLegalCandidates | 检查时间、程序、地域后在合成法规范围生成候选。 | 候选、来源、疑点、实际范围；不得自动成为已适用条文。 |
| ReviseOrConfirmCitation | 对确切条文版本执行选择、移除或修订，并要求理由。 | 新引用版本或测试记录；不是法律结论。 |
| CreateDraftReport | 冻结工作、材料、引用和合成模板输入，检查完整性。 | 结构化草稿、来源映射、缺项；不得生成裁决、文号、签章、送达或办结。 |
| ReviseDraft | 基于确切草稿版本保存修订或明确采用候选重生成。 | 新草稿版本和差异；不得覆盖历史或人工内容。 |
| ReviewDraftForTest | 对确切草稿执行测试身份 `RETURN/CONFIRM`。 | 测试复核记录、`notOfficialDecision=true`；不得推进签发、裁决或案件状态。 |
| GetAdjudicationWorkView | 读取工作、历史版本或来源引用时重新授权。 | 获授权投影、版本、缺项和复核历史；失权/失效不泄露正文。 |

| 端口 | 应用层需求 | 适配器禁止事项 |
|---|---|---|
| `AdjudicationAuthorizationPort` | 返回当前测试主体对工作、材料、法规、草稿的允许/拒绝及追踪标识。 | 不拥有业务角色或永久授权事实。 |
| `CaseMaterialSourcePort` | 读取合成案件/材料固定版本、定位和可用性。 | 不确认案件事实或流程状态。 |
| `LegalKnowledgePort` | 返回合成法规候选、确切版本和原文片段。 | 不确认法律适用或生成规则。 |
| `SyntheticTemplatePort` | 返回内部草稿结构和稳定章节锚点。 | 不冒充中心认可模板。 |
| `DraftGenerationPort` | 产生带依据的候选解释或确定性测试草稿片段。 | 不补事实、裁决、签章、文号或专业结论。 |
| `AdjudicationStore` | 原子追加工作、版本、草稿、测试复核和幂等绑定。 | 不拥有中心数据、权限或正式状态。 |
| `IdGenerator/Clock` | 生成本地技术标识和服务时间。 | 不生成中心编号、法定期限或业务事实时间。 |

`INVALID_INPUT`、`UNAUTHORIZED`、`NOT_FOUND`、`SOURCE_NOT_FOUND`、`SOURCE_UNAVAILABLE`、`UPSTREAM_FAILURE`、`MATERIALS_INSUFFICIENT`、`PARTIAL_RESULT`、`VERSION_CONFLICT`、`IDEMPOTENCY_CONFLICT` 和 `INVALID_ACTION` 必须区分。无权不得泄露，来源失效不得当作无材料，资料不足不得解释为不适用或无争议。

正常样例执行八项用例并停在未签发草稿和测试复核；资料不足样例保留缺项/冲突并阻止正式复核；无权/失效样例在每个读取点拒绝或使新草稿不能采用失效来源。

结论：8 项用例和 7 类输出端口覆盖合成裁决 V0 的样例、异常和人工控制点，可进入最小 API 合约设计；不代表 ADJ 需求 D/V/A、真实数据或业务验收。
