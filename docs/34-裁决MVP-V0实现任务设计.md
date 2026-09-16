# 裁决 MVP V0 实现任务设计

版本：V0.1  
日期：2026-09-16  
关联任务：`MVP-ADJ-V0-01`  
目标实现任务：`MVP-ADJ-V0-02`  
设计输入：`docs/29` 至 `docs/33`、ADJ-04/05/06/10/11/12 需求契约。  
设计性质：仅为合成、内存、纯 Java 的裁决 V0 闭环申请窄范围 D 门；不批准 HTTP、DM8、真实中心 API、真实模板/文件、模型、专业法律规则或正式流程实现。

## 1. 本次实现范围

```text
合成案件/材料固定版本
  → 当前授权检查
  → 材料检查（主张、待核实、缺项、冲突）
  → 合成法规候选（时间/程序/地域限制）
  → 人工测试身份选择/修订引用
  → 合成结构化草稿（固定输入/来源映射）
  → 人工修订或采用候选重生成
  → 测试身份确认/退回
  → 来源失效后旧版本保留、新草稿/确认拒绝使用
```

所有输入和输出使用 `V0-SYN-ADJ-*`，固定标记 `SYNTHETIC_V0` 和 `NOT_OFFICIAL_DECISION`。应用和测试不依赖网络、真实文件、模型服务、HTTP 或数据库；测试身份仅验证可信上下文、版本和审计边界。

## 2. 纳入需求条款与实现映射

| 需求 | 本次纳入的受限行为 | 设计证据 | 自动测试 |
|---|---|---|---|
| ADJ-04 | 本地工作记录与中心案件/正式状态分离；固定材料版本；主张/待核实/缺项/冲突分离；资料变化不覆盖历史 | `docs/29`、`docs/30`、`docs/33` | 正常/资料不足/无权来源失效、版本回放、无正式字段 |
| ADJ-05 | 仅对合成法规候选保留确切版本/位置、时间/程序/地域限制和疑点 | `docs/31`、`docs/32` | 候选可追溯；不自动成为法律适用 |
| ADJ-06 | 选择、修订和移除引用均版本化且有理由；不能以历史版本替代现行输入 | `docs/31` 至 `docs/33` | 引用追加、理由必填、版本冲突、失效传播 |
| ADJ-10 | 使用合成内部草稿结构和稳定章节锚点，固定模板输入版本 | `docs/29`、`docs/32`、`docs/33` | 草稿带模板/来源映射；不含正式模板、签章或文号 |
| ADJ-11 | 允许保存不完整草稿；关键内容回链材料/引用；修订不覆盖历史 | `docs/30` 至 `docs/33` | 缺项草稿、版本差异、来源映射、无虚构事实 |
| ADJ-12 | 仅测试复核记录，针对确切草稿版本，退回理由必填；不推进正式状态 | `docs/30`、`docs/31`、`docs/32` | 测试确认/退回、资料不足阻断确认、无正式决定 |

## 3. 明确排除

- ADJ-04 的中心案件同步、受理/分配/转派、期限、流程、办结、归档、真实回执与中心写回。
- ADJ-05 至 ADJ-09 的真实法规、专利解析、有效性与证据专业分析、法律适用和任何专业结论。
- ADJ-10 的真实模板配置/审核/发布、DOCX/PDF、字体、渲染、中心样例和文件存储。
- ADJ-11 的真实模型生成、导出、正式复核提交、签发衔接、送达、正式文件或文号。
- ADJ-12 的真实人员角色、领取/转派、多级专业审核、签发回执、正式状态和归档。
- 全部真实中心 API、SSO/DataScope、数据出域、DM8、索引、HTTP 和浏览器页面。

上述排除内容仍保留在 41 项台账，不能因本次 D 门、代码或测试被标为已实现、已验证或已验收。

## 4. 拟实现边界

```text
cn.xinji.agentplatform.adjudication
  domain/
    AdjudicationWork, MaterialInspectionVersion, LegalCandidateSetVersion
    CitationVersion, DraftVersion, TestReviewRecord, AdjudicationSnapshot
  application/
    AdjudicationApplicationService, AdjudicationStore
    AdjudicationAuthorizationPort, CaseMaterialSourcePort, LegalKnowledgePort
    SyntheticTemplatePort, DraftGenerationPort
  adapter/out/memory/
    InMemoryAdjudicationStore
  adapter/out/synthetic/
    V0AdjudicationAuthorizationAdapter, SyntheticCaseMaterialSourceAdapter
    SyntheticLegalKnowledgeAdapter, SyntheticTemplateAdapter, DeterministicDraftGenerationAdapter
  test/
    AdjudicationApplicationServiceTest, AdjudicationArchitectureBoundaryTest
```

来源端口和合成模板端口仅为测试夹具；返回类型必须区分案件材料、法规候选、模板和草稿片段。不得修改咨询/执法公共契约，也不得提取通用 `Evidence`、`Review`、`Task` 或 `Runtime` 模型。

## 5. 最小测试清单

| 测试 ID | 场景 | 必须断言 |
|---|---|---|
| ADJ-V0-T01 | 正常合成闭环 | 固定输入、检查、候选、引用、草稿和测试复核完整；每项可回链来源；无正式状态。 |
| ADJ-V0-T02 | 材料缺项与冲突 | 缺项/冲突并存，草稿可不完整，`CONFIRM` 被拒绝；不补造事实。 |
| ADJ-V0-T03 | 开始工作无权限 | 不创建工作、不读取正文、不产生候选/草稿。 |
| ADJ-V0-T04 | 来源读取无权限 | 不返回受限内容、定位或缓存正文。 |
| ADJ-V0-T05 | 法规或材料来源失效 | 历史版本保留并需复核；新草稿/确认不使用失效来源。 |
| ADJ-V0-T06 | 同键同输入 | 返回同一工作或版本，不重复追加记录。 |
| ADJ-V0-T07 | 同键不同输入 | 返回 `IDEMPOTENCY_CONFLICT`，原结果不变。 |
| ADJ-V0-T08 | 引用/草稿版本冲突 | 并发请求至多一方形成新版本，另一方 `VERSION_CONFLICT`。 |
| ADJ-V0-T09 | 引用修订与历史回放 | 理由必填；旧引用、旧草稿和差异均保留。 |
| ADJ-V0-T10 | 测试复核边界 | 测试人员来自可信上下文；记录绑定确切版本；没有签发/裁决字段或方法。 |
| ADJ-V0-T11 | 错误分类 | 无权、来源失效、资料不足、部分结果、非法动作与技术失败可区分。 |
| ADJ-V0-T12 | 架构边界 | 领域/应用核心不依赖 Spring Web、DM8、HTTP、文件、模型 SDK 或其他域。 |

## 6. 退出条件与 D 门

`MVP-ADJ-V0-02` 只有在代码、T01 至 T12 自动测试、架构边界检查和 `node scripts/verify-all.mjs` 均通过后，才能标记“已完成”。其结论必须明确为合成/内存代码测试，不能回填任何 ADJ 正式需求的 D/V/A。

| D 门检查项 | 结论 |
|---|---|
| 稳定任务、依赖和退出条件 | 已具备：`MVP-ADJ-V0-02` 依赖本任务，退出条件与 12 项测试固定。 |
| 原需求与 R 门 | ADJ-04/05/06/10/11/12 均为需求就绪，基线摘要未变化。 |
| 输入、输出、来源、权限、版本、异常与 AI/Rule/Human 边界 | 已由 `docs/29` 至 `docs/33` 覆盖；未确认专业/正式分支已排除。 |
| V0 样例 | 正常、资料不足/冲突、无权限/来源失效三类合成样例固定。 |
| 新增业务行为 / MUST_NOT 违规 | 0 / 0；确定性合成候选与草稿仅为测试适配，绝不输出法律或正式决定。 |

结论：`MVP-ADJ-V0-02` 的 D 门只对本文第 1、2、4、5 节限定的合成内存闭环通过，可以开始领域、应用端口、内存仓储、合成适配器和自动测试。不得将此结论解释为 ADJ-04/05/06/10/11/12 整项通过 D 门，也不得开始 HTTP、DM8、真实文件/接口/模板/模型、专业规则或正式流程实现。
