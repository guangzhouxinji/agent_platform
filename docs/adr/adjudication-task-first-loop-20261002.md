# ADR：裁决首案闭环内核（限定 D1）

2026-10-02；技术决策责任人Codex，业务授权来源为本轮项目负责人明确要求独立并行任务轨道并自主合理实现。此文是详细设计/技术证据，不是第二份任务状态台账。唯一状态见docs/08中的IMP-ADJ-V2-TASK-01及01A～D。

## 1. 已有代码与边界复核

现有adjudication/domain/AdjudicationSnapshot及application/AdjudicationApplicationService依赖V0SourcePort和合成AuthorizationPort，候选和草稿由SyntheticAdjudicationSourceAdapter固定提供，reviewDraft写TestReview。旧DM8的adj_*及本地source目录、React BusinessWorkspaces只对应旧合同，均原样保留，不扩其许可。docs/58 §1～3给出V2领域数据和API方向；§5的CASE-001只是开发输入，不提供可信身份/逐案grant或专业法则。第8.3节R已固定，全能力D未通过。

咨询正式ProductConsultationApplication拥有Task/Search/SourceScope等；执法ProductEnforcementApplication有独立组件扫描与文书/材料等本域所有权。新裁决内核不依赖这两域，不读取/写入其事实，不把共享文件字节等同裁决采信。不改任何共享类、生产路由、安全链或数据库。后续接线必须复用已验证JWT/session及当前grant端口，不能复制V0自报身份。

## 2. 当前最小产品合同与条款归属

- ADJ-04/09：工作案件由调用者提供稳定id、机构/数据范围、案件名称、两方主体以及来源；材料为逐提交过程的独立记录（同hash不合并提供方），来源定位/版本/内容hash/输入性质保留；缺材料/缺侧只提示，不认定事实。案件工作没有受理/归档/正式状态。
- ADJ-05/06：本切片不实现法律自动召回；允许授权人直接选择具确切版本和定位的原文、理由、适用时点/地域/程序，原文与解释分离。其为人工选择记录，不证明法律适用。缺原文/来源则拒收；缺选择可生成不完整报告，不能提交复核。
- ADJ-07：人工提供所选权项原文/版本/定位及必要特征清单；特征必须逐字出现在原文内。本切片不运行OCR/模型解析，不自动确认权项，不把文本拆分等同专业解释。
- ADJ-08：不由材料推官方法律状态、有效/无效或稳定性结论；报告明确状态和新颖性/创造性未核验。该未实现能力在总任务保留，不伪造通过。
- ADJ-09：授权人输入具体特征与材料的支持/反向定位，定位摘录必须存在于该案可用材料文本；销售等资料用途明确为人工说明，关系只作辅助记录，非采信。规则只核每个必要特征是否有支持、是否有反向/冲突与缺证，不判侵权/等同。
- ADJ-11/12：辅助报告以实际案件/材料/权项/引用/逐特征关系构成结构化章节和缺项；完整性只是本切片工作输入完整性，不是法定文书完整性。报告固定输入版本及hash；提交/复核绑确切报告版与hash；编辑者与复核者必须分离；批准只为辅助报告REVIEWED_NOT_OFFICIAL，退回保留理由/审计；无签发/官方状态命令。

## 3. 领域数据、命令、事务与失败

新增adjudication.casework包，不使用旧V0模型。不可变State为同案聚合，保存version/inputVersion、CaseInput、Material、Claim历史集合、Finding、Citation、Report和Audit。材料补正显式supersedesMaterialId保原提交链；当前分析不消费已替换材料。列表均防御拷贝；输入字段必需、重复ID、版本/摘要、跨案材料/权项、特征与引用来源严格验证。Source含id/version/locator/hash及PUBLIC/DEIDENTIFIED/USER_PROVIDED/PROJECT_AUTHORED性质；此枚举只记录输入性质，不发行许可。真实内容校验采用UTF-8 SHA-256，禁止hash与正文不符。材料补正用新id/来源版本保旧；同来源id/版本/定位不同hash或输入性质拒绝，不能把PROJECT_AUTHORED改称PUBLIC；输入变更递增inputVersion，历史Report保原输入和内容，旧报告不得提交/复核。处理过程中没有外部模型调用或Agent写审核。

纯领域命令接受已由应用边界验证的actor：open/addMaterial/setClaim/addFinding/addCitation/generateReport/submit/review。此处actor是审计参数，不是身份认证手段；不得直接暴露客户端actor。应用接线的授权/来源/存储端口与HTTP/数据库事务需01B限定D核证，不在本内核伪造授权“可用”。整个命令返回新State，校验失败返回异常而原State完全不改。人改指定章节产生新Report/作者及理由，保原引用映射与缺项；限制声明不可移除，不允许文字修改清空诊断。后续存储必须整体CAS提交State+Audit+Report/Citation绑定，事务内后核授权/来源；幂等键须绑定actor/案/命令/inputVersion/body摘要，不套内核纯函数为持久幂等。

Report含确定性contentSha、inputVersion、作者/审查涉及人、状态/完整性/缺项、逐章节正文和Source引用。审核状态只作用确切版；未提交不得审、自审不得过、材料更新不得审旧版、hash不符不得审、退回不能沿旧决定跳转批准。来源失效/撤回由01B当前来源守卫关闭访问与写入，不以历史快照授权当前读。

## 4. 可配置与禁止默认

Policy为版本化requiredSections及maxTextLength；只允许调整报告结构和尺寸，不允许配置关闭双人复核、跳过必要特征或把辅助意见升级裁决。机器只处理确定性完备/冲突检查。法规适用、官方状态、技术稳定性、等同、质证/采信、真实角色/法定流程、正式模板/保留/删除均不由本ADR确认。本轮业务细则不足不阻内核代码；产品显示准确缺项并安全继续，不默认为业务事实。

## 5. 验收与反演

必须覆盖最小同案操作链、双侧分别保存、无证生成不完整辅助稿且禁提交、必要特征缺证/反向冲突、不在原文特征拒绝、材料定位错误/摘要错误/跨案拒绝、同字节不同提交保留、旧报告失效但历史不改、自审/未提交/错hash/重复审拒绝、退回留痕及输入集合不可变。原创工程测试不是公开真实业务数据，不能证专业效果、身份权限、HTTP、持久/重启或页面可操作。

单人技术反演结论：限定领域内核D可实施；它不依赖未确认专业规则就不执行这些分支，未确认项明确保留。完整ADJ D、01B/C接线与真实I/B仍需独立证据，不以结构映射或机械C升级。


## 6. 已实施证据定位（不维护任务状态）

实现分离CaseWorkContract/AdjudicationCaseWork/CaseWorkIntegrity，满足现有文件尺寸限制；41项裁决机械测试和两域模块回归原件见[实施证据](../evidence/adjudication-task-20261002/implementation-verification.md)。actor审计输入不是身份认证；没有本机DM8/API/UI/专业验收证据，不开放客户端自行指定actor。具体实施/验证状态只回写docs/08。
