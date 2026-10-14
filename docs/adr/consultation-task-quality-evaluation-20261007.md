# ADR-CON-TASK-QUALITY-01：咨询留存任务级证据评测的限定技术D

日期：2026-10-07；技术负责人：主会话开发团队。状态只见docs/08。来源：本轮用户“咨询任务级质量交付”指令、docs/09§4/5、CON-12已过R、docs/71已批准的最小真实检索解释D、ADR-AGENT-BINDING-02及ADR-CON-MODEL-FENCE-01。不是CON-12全部D或专业批准。

## 1. 当前MVP、依赖和边界

归属IMP-AGENT-CONSULTATION-01 / IMP-AGENT-EVALUATION-01咨询子范围，任务FIX-CON-TASK-QUALITY-EVAL-01。仅离线读取已获准保留的HTTP/DM8验证原件，不调用API、数据库、浏览器、检索或模型，不读取正式到期对象，不更改业务规则或授权。整体三域Golden仍等待IMP-AGENT-WORKBENCH-01；该咨询子切片只需VER-CON-AGENT-BROWSER-CHAIN-01及VER-AGENT-GOLDEN-CONTRACT-01已通过的实际子能力。因此在原主计划内登记这个依赖切片，不解除整个IMP-AGENT-EVALUATION-01的最终专业/三域门禁。

## 2. 固定输入与防篡改

固定评测合同governance/evaluations/consultation-task-quality-v1.json只定义样本、预期机械条件、输入原件路径/SHA和覆盖分母，不维护项目进度。V1固定两个真实历史任务：04窗口转子叶片TITLE检索的非空第一页（40母集/20成员/1三词匹配）；首轮robot vacuum的NO_RESULT空页（40母集/0成员，不能说母集0篇或证明其他来源也无专利）。两者各为一个Task，不把其同任务恢复读或工程变异算新业务样本。

文件必须位于工作区docs/evidence，逐层拒绝symlink/reparse、绝对路径、遍历、重复样本ID/Task/Run、缺文件、错误SHA、无效JSON、未知合同版本和额外顶层合同字段。SHA不匹配即不能采用任何自报passed标记；manifest hash及读取的所有原件SHA进入输出。CLI不覆盖已有报告；输出不得写回输入或requirements，不复制凭据、原文或模型正文。只输出绑定ID、数量、指标判定/原因、源文件SHA与范围限制。

## 3. 判定合同

- 完成：检查consultation-agent / CONSULTATION_RETRIEVAL / CONSULTATION_COMPLETION/v1；Task/Goal版本、Input引用、Scope、Run、结果集与Turn/SHA一致。Runtime COMPLETED与业务Task/官方状态分开；PARTIAL仅对应现有NEED_HUMAN/partial-source-coverage。Task终态不由评测器写入。
- 证据：实际成员不重复，每成员具匹配Citation；回答引用是实际成员的非空合法子集（空结果允许空引用），源ID、文献/源版本、原文SHA、定位和可选字段定位精确对应。按服务端ObjectMapper现有JSON字节顺序验证task/检索/引用/回答Observation SHA；拒重复引用或用相同ref不同SHA伪装。保留原JSON字段顺序，不能用排序后的JSON替换原规范序列化。
- 工具：实际定义和计划只允许PatentSearch/search、EvidenceAssessment/source-read、AnswerDraft/answer-draft；Input指向同Goal。不把固定三步骤/0次重规划冒动态决策。
- 结果：非空回答与实际结果相关的结构化关联，记录候选母集/本页/词汇匹配分母。词汇命中不是专业相关性，结构绑定不是自然语言事实正确性。专业准确性/语义质量和人工成本分别NOT_ASSESSED / NOT_OBSERVED，不给虚构分数。
- 权限：逐条检查留存真实失权HTTP原件指向同Task/Run、时间与scope；不只信总结true。旧请求的成功只算历史观察，currentAuthorization始终NOT_EVALUATED，不借离线证据发当前READ。
- 恢复：直接比较原始前后SQL Task/Run/revision/search/fence/turn/request摘要；确切绑定到本任务，复核1Run/1检索/1解释/1Turn及修订连续。授权新Grant和新的分页cursor是正确的当前能力，不纳入“所有数据库不变”断言。不能把终态恢复冒外呼中崩溃或旧物理快照恢复。

八维为completion、evidenceSupport、resultAccuracy、toolUse、replanAbility、humanIntervention、errorRecovery、outputQuality。每维单独记录技术PASS/FAIL、未观察或待专业判断；固定分母2任务，失败/缺失不得移出分母。工程反例另计，不纳入真实任务通过率。报告分别显示mechanicalPassed与professionalAccepted=false、overallAcceptance=INCOMPLETE，禁止生成整体成功标记。

## 4. 接口与工程出口

模块scripts/lib/consultation-task-quality.mjs导出evaluateConsultationSuite(workspaceRoot,manifestPath)，返回固定分母、输入SHA、逐任务checks/metrics与未覆盖分支；加载/验证失败应返回失败结果而非丢弃样本。默认CLI无网络，无隐式库连接；--verify执行固定套件仅验证已批准机械合同，专业待验不升级；--report PATH写全新工程报告，--require-professional遇未验收返回非零。未知参数失败关闭。

工程出口：两个真实留存任务复算；反例覆盖错Task/Input/Run/结果SHA、伪造引用/Observation SHA、非法工具、缺失样本/路径/SHA、篡改恢复账本、错误拒绝路径、不完整专业证据及重复样本。先固定这个D与分母再实施；独立测试智能体只修改单独测试文件，不改源证据/合同/主计划或评测实现。

## 5. A/B/C与剩余决定

A：离线机械关联、来源引用、真实拒绝和恢复账本复算，已有D立即实现。B：固定分母、SHA、防遍历和八维诚实输出为上述最小技术细化。C：专业Golden rubric/样本及具名验收、证据缺口驱动动作与预算/人工确认、后台租约/取消/身份以及旧快照/外部回执恢复，各在docs/02本轮申请中明确，不由这份技术D放行。默认保持当前固定请求内执行和未知外呼拒绝重发，不增API/迁移/业务状态。

## 6. 专业评测就绪索引（2026-10-07，限定技术细化）

任务FIX-CON-PROFESSIONAL-READINESS-01，前置FIX-CON-TASK-QUALITY-EVAL-01，父任务仍为IMP-AGENT-CONSULTATION-01及IMP-AGENT-EVALUATION-01。来源为本轮用户明确的“咨询专业评测就绪与最小合同决策”指令；这是现有离线技术R/D的索引交付细化，不新增专业标准、产品接口、模型用途或当前授权。

核对依据：docs02 P0-08已要求来源明确、版本固定、输入/预期/实际/未处理项及分母，明确负责人只验范围/流程/来源/异常/人工与禁止行为，专业阈值不能虚构；docs71§7～9的Golden只记录技术任务，人工成本/专业效果未验；model-workspace-golden-v774原件显式notPerformanceOrProfessionalOrBusinessAcceptance=true；上一机械报告的professionalAccepted=false。现存技术Golden不能自动升级为这两个Task的专业预期。专业责任方后续若指出已有具名同范围标准，应复核其原件并复用，不要求重新批准仍适用的事实。

交付：复用evaluateConsultationSuite及固定两个样本；机械检查失败时拒绝生成审阅索引。增加只离线、全新输出的--review-packet PATH，不能与--verify或--report混用。索引内用原件路径/SHA/JSON Pointer明确指向Goal、来源、候选、回答、引用、当前原始Run与恢复账本，保留实际Task/Goal/Input/Run/result SHA及母集/页/词汇匹配分母。逐成员指向原有citation及其locatorRef，但不读取data/原文、不复制回答或标题、不对URL发请求。依次列出相关性、覆盖充分性、回答准确性、引用支持及专业完成判断的问题；问题是待责任方回答的审阅请求，不是评分rubric或专家标签。固定2样本及未覆盖场景不更改，不把新增结构性索引算第三个业务样本。

索引专业规则绑定为null并明确尚缺具名专业证据；professionalAccepted=false、overallAcceptance=INCOMPLETE、currentAuthorization=NOT_EVALUATED，不提供写“通过”的入口、不接收任意自报rubric/approved/score，不猜阈值或自动采纳人类结论。后续实际rubric/责任证据的导入、有效范围验证及专业结果计算需对应专业合同到位再过D；本切片只准备精确证据坐标和输入请求。动态、后台和外呼恢复是docs02三个独立申请，不阻本索引。

安全和工程出口：复用已批准的路径/SHA/重复键/UTF8检查，输出沿用CLI全新文件及路径边界；生成时再次核对所用字节SHA和安全路径，避免沿用先前passed后读到变更原件。反例须覆盖错原件、缺文件、伪造结果、路径/junction、重复引用及任何专业升级入口。固定两个真实样本均有可解析且SHA一致的指针，输出无原文/模型回答/凭据；只运行工程专项及verify-all，不触及正式API/身份/DB/来源/模型。

专业审阅输入补充（同一限定D）：已留存input-title-readback.json包含40篇公开TITLE快照；03窗口task-result-review.json包含20条标题/文献ID/rawSHA核验记录；04窗口citation-evidence-inheritance.json是其跨窗口原字节坐标继承说明。为使专业人能够实际查看标题，仅在索引中固定这三份现存原件SHA并引用/records、/checkedCitations及其/title，不复制TITLE正文，不读data/原文或业务API。非空样本逐成员与旧核验记录按documentId+rawSha唯一匹配，旧taskId/runId及旧专业结论不能当当前样本验收；空样本40篇TITLE快照只供限定语料覆盖审阅，不证明全球无资料。继承说明的allMatch不得替代逐文献rawSha核对。新辅助文件不增加真实任务分母、不带专业标签；任一原件缺失/错SHA或无法唯一定位时拒生成索引。现有真人Golden空白表及技术报告NOT_OBSERVED均只作为缺证依据，不代填。
