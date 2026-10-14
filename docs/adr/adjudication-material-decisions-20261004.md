# 裁决候选人工整理版本：03B当前详细设计

版本V1.0；日期2026-10-04；技术设计责任Codex。对应IMP-ADJ-V2-TASK-03B及ARC-ADJ-LLM-MATERIALS-03B-D。任务状态仅总计划。

## 1 原需求/R和现有能力

承接REQ-ADJ-R-CORE-14已过R、原材料整理ADR§1/§2/§3/§9.4。原ADJ-04§2/3案件双方材料/版本/历史、ADJ-07原文及定位、ADJ-09主张与材料反映/人工认定分离、ADJ-12§2/3/6机器/人工修正和审计，均只覆盖人工材料整理子集，不新增专业侵权/事实采信/正式审核。

03A技术退出另具证；复用现有MaterialOrganizationService.read、CaseWorkRepository.Access/Scope/mutate、CaseSourceAuthority、ProductGrantWatermark、CaseWorkCodec、MaterialRunVault。领域依赖现有材料Candidate/Input，不依赖DM8/JWT/供应商。模型及Agent不调用人工决定写端口，后台不默认接受。

## 2 当前授权、输入与启用边界

HTTP操作者沿正式PKCE登录/可信JWT、受控委托交互账户取得当前逐案READ/WRITE。读取模型候选复用03A现有READ/MODEL_EXECUTE及READ/MODEL来源许可，不弱化其撤权拒读；写入还检查SAVE/ANALYZE、当前source generation、Grant generation。MODEL_EXECUTE不用于发送新请求：人工流程零外呼、零新预算预留。这是继承既有模型候选读取门加逐案WRITE，不是给咨询权或测试身份赋权。

当前已知case head5/input4/material-2@v1撤回、run stale，继续拒绝采用；Docker目前不可用，不能声称当前产品I。详细设计允许实现已获准材料/合法当前输入的通用分支，不以旧许可、新别名、测试身份或旧候选启用该case。实际正向HTTP/DM8人工采用需另有正常准入的当前有效输入及候选、现时权限；没有这些，本案正向分支保持关闭，不阻塞纯领域实现、不把组件测试冒产品I。没有授权新供应商用途、另案预算或复活来源。

## 3 决定与人工版本语义

只允许ACCEPT/EDIT/REJECT。客户端只能给expectedWorkVersion、expectedOrganizationVersion、action、humanText、reason和Idempotency-Key。不收actor/source/kind/side/model/status/confirmedFact/officialState；严格JSON拒绝未知/null primitive/浮点版本/重复字段/尾随。

服务端从授权case和原run获取candidate/Input，复查原文SHA、源id/version/locator、提供方、sourceTag、UTF16半开定位及原quote。run.inputVersion必须等于当前case inputVersion，引用必须仍是当前active材料，不能借定位相同忽略来源版本变化。期限与trusted clock当前有效。

ACCEPT保存原候选正文；不得携humanText覆盖它。EDIT须非空humanText和用户提供reason；原机器statement/quote/source永不改。REJECT须用户reason，humanText必须缺省，不生成人工材料陈述。各项显式动作形成新整理版本和case版本事件；没有任何CONFIRMED_FACT/专业finding/claim/report/review/officialState写入。固定semantic为HUMAN_REVIEWED_MATERIAL_STATEMENT或REJECTED_CANDIDATE、分类/方别继承原候选；MISSING_ITEM及其他内容都限定SELECTED_MATERIALS，不扩为案件全部事实。

每个run/candidate首次处理只能一次，幂等重放返回同回执不追加版本。人工更正走独立revision命令：确切baseVersionId+expectedOrganizationVersion+EDIT+reason，保留前版，不重跑模型。已拒绝版本不能伪装首次接受；重开/撤销裁决不在本范围。

文本长度是可配置技术防护（初始正文/原因2000 UTF16，允许1..100000范围），无默认理由/默改方别/自动补缺项。输入文本逐字保留，不trim后冒原文或专业真值。

## 4 HTTP契约

POST /v2/adjudication/case-works/{id}/material-organization/runs/{runId}/candidates/{candidateId}/decisions；POST /material-organization/versions/{versionId}/revisions；GET /material-organization/versions（受控分页）/GET确切versionId。最小处理结果含versionId、caseId、runId、candidateId、caseWorkVersion、organizationVersion、inputVersion、decision、semantic、kind/side、source绑定、humanText/原candidate及reason、createdAt/expiresAt。所有正文只在READ及当前来源链成立时显示；401/403/404/409/到期及引用漂移给稳定原因码，不回显秘密/原供应商正文。

旧历史/失效版本能追溯但不供当前采用；撤源后整个既有case按原01/02守卫拒读，不为新功能提供旁路。03C才做候选工作台，本轮无新页面、原01/02页不改。

## 5 API/数据/事务（后续集成不能省）

新增ADJ旁表adj3_material_decision_version保存version UUID、确切case/run/candidate、organizationVersion/baseVersion、workVersion/inputVersion、actor identity、requestKey+fingerprint、policy/source/input binding、AES-GCM密文/密文SHA和createdAt/expiresAt；唯一case+organizationVersion、case+actor+requestKey，首次处理唯一run+candidate（修订为独立baseVersion链）。不插入adj3_material_run，不改变总calls/token/cost，避免破坏03A账本计数。

沿原命令锁→case head→model budget/read→人工整理head/row顺序；事务前预检不得在外层事务先锁case再去抢命令锁（避免8槽命令锁反序死锁）。在CaseWorkRepository.mutate回调中重新校验当前run/source/Grant和人工head、保存新人工version与只含引用ID的Audit，同一primary连接/TransactionTemplate原子提交；来源/Grant外置水位仍按已有前后复核，不冒跨库分布式事务保证。冲突/撤权/撤源/引用漂移/缺钥/落库失败全回滚新case头/人工body/回执，历史不覆盖。并发/失效竞争需新测试证明，不能用纯领域测试代替。

不修改旧State结构、不改专业finding/报告/ReviewState；整理动作仅追加版本/审计，inputVersion不增（没有改变原材料/专业输入），因此不把一次人工整理当专业分析或报告重生成。Audit只存opaque版本/候选引用和动作，不存机器正文、humanText或reason。旧案件历史不删除。

## 6 30天与恢复派生决定

负责人明确新增模型记录/备份30天。人工整理仍来自本轮model candidate，因此新人工payload（原候选、人工正文、理由/差异）作为模型派生对象继承父run最早expiresAt，不从采用/修订日期重新计时；最多createdAt+30天且不晚于父run/来源批准截止。此技术决定采取已批准范围内更严格的期限，不声称批准了人工正文无限留存。

新payload独立UUID key、AES-GCM/AAD绑定case/run/candidate/版本与期限；key不在数据库/备份。到期拒读/修改并仅清理新派生表/keys；既有case Audit的最小引用元数据保留，但不能用它重构候选/人工正文逃避期限。没有删除旧V086案/原文/历史的授权。新备份按同期限和密钥分离；恢复先到期清理、缺钥/损坏/关联/CAS不一致拒读。实际DM8和恢复I待集成实测，不以此D文稿称完成。

## 7 验证分层与本轮边界

03B-C1先实现纯领域ACCEPT/EDIT/REJECT/明确人工修订、双版本、逐字source/quote、stale/期限、重复决定、语义不升级及保留机器结果；配置上下界可维护。它不代表API/DM8/页面已完成。

后续必须补受控HTTP/现时角色、同库原子回滚/幂等、跨run/case/source、撤权/撤源/版本竞争、30天/恢复及真正正向人工采用；当前撤源案只允许负例，不人为复活。01/02和精确Shared/CON/ENF受影响回归继续保留，不将无关20项失败冒通过或添加业务前置。

## 8 逐条D复核结论

R来源、机器/人工/事实边界、明文来源定位、权限与继承门、双版本与幂等、同库事务/锁序、失败不覆盖、最早期限及旧历史保护、恢复/当前禁用分支、API给后续页面复用均已明确。当前设计无需要重新询问既有账号/预算/供方/用途的决定；已知撤源是确定拒绝条件，不是假装取得许可。限定03B详细D通过，可开始C1领域实现与后续受控接线；正向本案I仍因当前来源/候选/环境不可用而不能验证，3B/3C/3D不因此标完成。新的材料授权/留存扩展若未来需要，必须正常流程另证，本设计未授予。


## 9 C2物理复审（2026-10-05）

原R与§3～6保持不变，技术责任Codex。当前最大迁移V/U092（咨询用途）；本切片登记V093/U093，只新增ADJ表，不修改旧迁移。

- adj3_material_decision_head：case_id FK旧案件，organization_version单调计数，不含模型/人工正文；case head锁后创建/锁定。不因派生payload到期归零。
- adj3_material_decision_version：UUID PK、case FK、baseVersion自引用FK；case+organizationVersion、case+actor identity+requestKey、case+run+candidate+first marker唯一（修订marker NULL）、baseVersion唯一防分叉。run/policy/source/input SHA、work/input version、actor、request fingerprint、created/expires、密文与SHA均冻结。run不设物理FK，因为现有30天父run清理独立执行；写读均通过现有budget校验/load与当前候选读取门证明真实COMMITTED父关联，不能用孤儿行恢复正文。
- 全量Decision正文/原因在独立key目录AES-GCM封装，AAD为canonical行绑定(case/run/candidate/version/base/policy/source/input/actor/request/期限等)，密文SHA另校验。key UUID与父run不同；到期只清理新派生行/keys，按organizationVersion倒序移除self FK。head和旧案件历史不删。逆迁移先添加恒假CHECK：非空派生表或非零head即拒绝，绝不清空行。
- command key为case/当前identity/用户key的SHA，复用旧8槽命令锁。应用不在mutate之前开启外层事务；由现有mutate开启同primary REQUIRED事务，回调按case→budget load→整理head/row验证并落库。人工row先写，再案件snapshot/audit/receipt；任一SQL失败全部回滚。key文件不是数据库事务成员，失败仅删除本次新key（提交成功而响应失权不删除），崩溃孤儿key由同期限清理。
- 写前/回调末/返回前复核独立来源generation、当前Access/Grant。独立authority是外置系统：提交之后撤权可导致响应503/403，但已提交回执不回滚；同键重试仅现时授权允许后返回确切版本。不得将这一边界描述为跨库分布式原子保证。
- 历史列表afterOrganizationVersion+limit(1..50)，稳定升序，仅返回受当前case/候选/source/期限链检验后的版本。确切详情返回currentOrganizationVersion/currentInputVersion/stale/isCurrentCandidateDecision，历史仍可核查但stale不供写采用。body严格限定expectedWorkVersion/expectedOrganizationVersion/action/humanText/reason，duplicate JSON在本Controller隔离parser拒绝，不改变共享ObjectMapper。
- 配置派生key根须独立于模型key根，默认关闭写能力；由正式部署显式启用，不创建新身份/角色/源授权。首轮组件机械行为使用测试目录H2及模型端口替身，不形成产品替代接口、不新增付费调用；DM8正逆/重启/物理恢复为NOT_EXECUTED直到真实环境和合法当前候选具备。

物理D复审：字段、约束、保留、AAD、锁序、回执竞争、逆迁移与外置水位提交后边界已明确，无需扩原授权；C2准入通过。不是DM8或专业验收。


C2最早期限接线复核补充（2026-10-05）：03A原run期限可能晚于当前来源Use.validUntil。C2不得因此延长来源批准范围；通过ADJ CaseSourceAuthority新增只读permittedUntil口，按现有确切Scope/Source/ACTIVE/用途/SHA及generation返回选入run的全部材料最早来源截止；人工Run视图取min(父run.expiresAt、全部选入来源截止)，C1规则不变，后续修订继续继承同一最早截止。原require/来源注册/模型run预算和模型key全部不变。该只读ADJ扩展的兼容性由01/02、03A及既有Shared/CON/ENF精确C复跑，不借外置来源快照作为永久批准；物理I仍另证。读取事务也统一primary REQUIRED、case→budget→整理行，避免并发写时读取混合头。此补充是既有§6最早截止的具体接线，不是新的授权或保留规则。

新人工createdAt/expiresAt统一UTC毫秒精度（向前截断，不晚于任何来源截止），以避免DM8/H2 TIMESTAMP精度导致AAD与行绑定漂移；不改既有父run/来源元数据或模型密钥。

逆迁移拒绝守卫顺序复审：先检查整理head必须为0，再为派生version表添加恒假空表检查。正常派生提交必使head非零；即使payload已到期清理，非零head第一步就拒逆，不向空version表残留禁止新增的约束。未发生人工版本时两表方可逆；不删除旧审计或重置head来规避守卫。DM8 DDL副作用与拒逆仍须物理I验证，本轮H2只证明机械约束顺序。
