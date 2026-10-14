# 裁决大模型材料整理最小设计

版本：V1.3（供应商例外批准及03A限定D）；日期：2026-10-03 +08；技术设计责任：Codex。对应ARC-ADJ-LLM-MATERIALS-01 / IMP-ADJ-V2-TASK-03。本文件是设计证据，不是任务状态台账。首轮限定授权已由负责人明确批准（本文件末§9为现行结论）；供应商训练用途例外也已明确批准，现行§10通过03A限定D；此前未决文字仅历史语境，不据此再阻同一授权。此前V1.0/V1.1措辞保留其历史准备语境，不重新等待同一批准。

## 1. 原需求反查与限定合同

沿REQ-ADJ-R-CORE-14、第8.3节既有R，不修改requirements、不扩大十二项完成度。
- ADJ-04《裁决任务管理》§2/3：案件关联、双方材料、版本和操作历史；本轮只整理一个现有案件获准材料。
- ADJ-09《专利侵权证据分析》§1/2/3：当事人主张、材料反映及人工认定分开，证据片段可靠定位；本轮不做其专业要素对应/侵权意见。
- ADJ-07《专利解析》§3：保留原文和定位，人工修订形成新版本，来源变化不覆盖历史；本轮不生成权利要求或专利状态。
- ADJ-12《人工复核》§2/3/6：原始机器结果、人工修正、原因和依赖版本留痕。逐项整理审查不是正式报告复核或事实采信，不替代01既有独立复核。
- 《00-需求总览与通用约定》§4：主张、材料反映、机器提取与人工确认事实分离。新增事实陈述仅为材料中明载陈述，永不自动标作已确认事实。

## 2. 候选与人工采用语义

候选类别PARTY_ASSERTION、RECORDED_STATEMENT、DISPUTE_CLUE、MISSING_ITEM；party取确切材料提供方或UNKNOWN，不由措辞猜测。机器候选与人工整理版本分开。每条至少绑定materialId/materialRevision、sourceId/version/SHA、原文逐字quote、原文字符起止（Java UTF-16半开区间）、原始locator、runId/candidateId及模型/提示版本。验证substring完全相等及位置唯一指定，不能仅搜到同一句便替换定位。引用多段逐段绑定，不将归纳正文冒充原文。

缺项只描述给定获准输入未提供的内容，不声称整个案件不存在；以实际出现的相关原文作线索锚点并标scope=SELECTED_MATERIALS。不适用或没有原文锚点的缺项不得生成可采用候选，不能编造引用。机械校验对未知来源/非法枚举/来源提供方错配/原文定位漂移等拒绝整次输出，不部分覆盖旧结果。SHA/substring一致不证明归纳语义真实；虚构、转述归属及危险终局判断须人工逐项核查，不宣称确定性规则能完整识别幻觉。候选永远不是已确认事实。材料里的指令一律是数据。

接受/修改/拒绝均需操作者当前逐案WRITE和READ权限、确切案件头/候选/来源版本及幂等键。修改保存原候选、人工正文、原因及前后差异；拒绝须理由。接受仅生成HUMAN_REVIEWED_MATERIAL_STATEMENT（主张/材料陈述/争议线索/待补项保持分类），不是CONFIRMED_FACT、专业对应关系或审核结论。不自动写现有finding、claim、报告或审核结果。同案事务生成新的整理版本与案件审计/版本事件，历史不覆盖。已采用候选不能重复采用；新人工更正形成新整理版本。任一来源变化使相关整理版本不可作为当前有效输入；历史读取仍受当前来源许可约束。

## 3. 产品API与工作台（待D后实施）

复用/v2/adjudication/case-works/{id}现有工作台及错误处理；最小增量接口：
- POST /material-organization/runs：expectedVersion、所选materialId/revision、Idempotency-Key。不接受客户端原文/模型配置/操作者身份。服务器取确切原文、当前权限及MODEL用途。
- GET /material-organization/runs/{runId}：分页候选、原文定位、输入/模型版本、有效性及人工处理历史；GET /material-organization/versions查询人工整理历史。
- POST /material-organization/runs/{runId}/candidates/{candidateId}/decisions：expectedVersion/expectedOrganizationVersion、ACCEPT/EDIT/REJECT、修改正文/理由及幂等键。逐项后端检查，不把按钮隐藏当授权。

工作台按双方/类别展示候选，原文摘录和上下文可定位，逐项人工操作、差异与理由、来源版本/失效状态可见；长文纯文本，不展示JSON替代业务体验。版本冲突保留人工未提交输入，失权/撤源立即清敏感内容。生成失败不换掉此前成功结果；无固定候选、测试身份、自动事实确认或自动重试外呼。

## 4. 模型、用途、授权和保留

可复用模型配置审查和OpenAI兼容HTTP技术；旧OpenAiConsultationAnswerAdapter依赖咨询候选，ManagedModelConfigurationService含旧管理身份及回退，不能原样作为裁决产品授权/失败合同。接裁决领域端口，通信在model-deepseek适配层；不新建通用Agent平台、不反向依赖咨询域业务。

当前application.yml配置deepseek:deepseek-chat及https://api.deepseek.com/chat/completions，docs02§83记本机凭据存在。这不是裁决实时连接证明。运行必须显式enabled、固定模型/endpoint/configVersion、裁决用途策略、预算/超时/输入输出上限；缺配置关闭，不能回退固定答案。不读取/输出密钥证明可用性。

现有CaseSourceAuthority只准READ/SAVE/ANALYZE，CaseGrantController只准CREATE/READ/WRITE/HUMAN_REVIEW。新增裁决独立MODEL用途许可及逐案模型执行授权，不由ANALYZE或WRITE自动推导。许可记录用途/来源版本/字段范围/允许provider和endpoint/非保密等级/批准人证据/期限/撤回epoch；主体沿真实issuer/组织/ADJ_CASE/TaskScope/DataScope检查，不复用旧测试主体或CON授权。实际授权由现有维护权限路径作受控增量，兼容保留原动作语义。共享身份内核不改，若确有必要须先兼容分析及CON/ENF真实回归。

仅经逐件批准的项目自有或有权许可非保密单案材料；不外送姓名、账号、组织、案件内部ID、grant/token、文件路径、密钥、第三方隐私及未获准附件。发送局部无敏感source标签、材料提供方、获准文字和精确偏移，不谎称去标识即取得用途权。真实单案未获准时可用获准替代输入做组件验证，产品合同不降级。

未决参数见docs02本轮裁决条目：模型费用上限、材料MODEL/出域用途与原始请求/原始输出/人工版本/审计/备份保留。既有开发半年或咨询Goal保留不得自动继承。保留配置应有reference/version/SHA、起算/有效期及备份恢复/到期隔离；失权拒读不删历史。供应商外部保留与训练事实未核实，不能承诺零保留。

## 5. DM8、原子性及恢复

不持DB事务等待模型。生成前取案件/源/授权generation及输入SHA，外呼后重新核现时主体、逐案许可、来源MODEL/READ/SAVE、版本、期限和预算，再在同库事务下锁案件/CAS保存原始最小请求、原始输出、结构候选、输入快照及审计。撤权/撤源/版本变更/格式错误/超时/DB失败不覆盖原成功产物，不返回未合法提交的结果。幂等相同输入返回合法历史回执，不同输入409；并发重复键不重复采用。外呼重试不自动开展，调用已发出却未收结果记不确定，不能谎称未消费费用。

拟新增裁决专用run/candidate/decision/organization_version记录，复用V086案件事务/CAS/审计及原文存储；确切DDL和Codec兼容需D二审，无本轮迁移。run保留model/config/prompt版本、requestSHA、source快照、原始输出；decision包含真实actor、原因、原候选/修正正文和输入版本。JSON/CLOB不丢长文，重启核SHA、引用substring、候选/采用关联、审计序列与现时授权；损坏拒读、不自行修复业务事实。备份纳入新表并做DM8恢复，不称内存模拟为持久化。

## 6. 退出实证

正例真实模型调用→候选核原文→真实工作台逐项接受/修改/拒绝→API/DM8新版本→重启读回。反例包括跨案/方别混淆/幻觉/偏移漂移/缺原文/来源撤回和调用中撤回/撤权/版本冲突/重复采用/非法状态/模型失败与DB回滚不覆盖。适用来源与授权前后检查需要真实API/DM8验证；机械模型故障仅证明防御。

对获准单案人工整理基线记录重要主张遗漏、方别混淆、虚构陈述、定位错误、人工修改量和实际人工耗时。基线须由独立人工实际形成/核查；未实测的人工效率标未测，不造数。真实案件资格/专业规则/业务验收独立；替代输入不能称真实案件价值验证。保留01/02浏览器与DM8回归及CON/ENF回归，记录精确源码窗口/条件跳过，verify-all单项成功不冒全仓。D退出须docs02阻断有责任方完整授权证据、端口/DDL兼容复核和最终产品合同语义复核；此前不写业务代码。


## 7. 授权前兼容复核（未通过D，不实施）

- 已核State只含材料/权项/finding/citation/report及审计，CaseWorkCodec用严格字段与SHA链；禁止改写旧payload或历史SHA去塞新组织字段。新run/candidate/decision/organization_version作为裁决旁表，带caseId和所锚定workVersion/源SHA，旧State格式/V086数据不迁移覆盖。
- 人工采用不是addMaterial/selectClaim/addFinding或报告修订。拟添加裁决领域的窄审计操作（workVersion+1、单个Audit、现有材料/报告全部不变）；原private next不能被控制器越权拼接State替代。人工整理独立版本不作为01报告输入，此轮不增加inputVersion、不宣称01报告已消费整理内容。未来若纳入报告须单独D及旧稿失效设计。
- 新DAO与JdbcCaseWorkRepository共用现有DataSource/TransactionManager、REQUIRED外层事务；候选决定/新整理快照/案件CAS和Audit/receipt须同提交同回滚，不得先完成案件事务再异步保存整理。锁顺序服从现有command-lock→case-head，再锁本run/候选；外呼在事务外。03A/03B实施时验证死锁/两个操作者竞争/保存中故障；此处不称事务已经实现。
- 生成run绑定源/案件确切输入快照，不能由新工作版号冒认改变输入。同源其他人工决定改变head后，后续决定可携带新expectedVersion，但必须再查输入快照/选定材料仍相同；源/材料/上下文变化需要新run，不能换引用复用旧候选。旧run仅供现时获准历史查看。
- 新MODEL来源用途拟用裁决专用旁表，保留CaseSourceAuthority旧READ/SAVE/ANALYZE含义和已有行。维护权限仍由ProductMaintenanceProof.SOURCES核组织；模型许可拥有独立generation，前后均校验，不能只查旧source generation。MODEL撤回拒读/采用关联回合和派生整理版本，不扩大至无关联原材料或01报告；原材料沿旧权限检查。
- 新逐案MODEL_EXECUTE只在裁决allowlist增量，CASE_AUXILIARY purpose/ADJ_CASE/当前issuer/subject/session/组织/TaskScope/DataScope/grant epoch不放宽；调用至少现时READ、模型执行grant及各源READ/SAVE/ANALYZE/MODEL。人工采用现时READ/WRITE、来源读取/保存和有效候选，不因历史模型执行人身份取得权限。不写共享ProductGrants/Watermark内核或咨询/执法allowlist。
- 新controller错误合同独立范围：400非法输入/枚举，403失权/用途撤回（不返回原文），404不存在/跨案对象，409版本冲突/重复决定/幂等冲突，413输入上限，422不能定位，429本地预算/次数限制，502模型格式拒绝，503配置/模型/DB不可用。日志不得写敏感原文/令牌，失败只保留允许的内部技术状态；不能重用当前ResponseStatusException统一维护身份错误而把模型错误冒成维护身份拒绝。
- model-deepseek新裁决适配器只依赖裁决模型端口和通信/JSON技术库，不能导入咨询Registry/候选/授权服务；无ManagedModelConfigurationService回退，不能调用旧generate伪装材料整理。确需抽取共用HTTP仅在限定兼容D及CON/ENF回归通过后，不在本轮大重构共享平台。

## 8. 模型/计价与03A准入修正

2026-10-03已取官方公开文档并保留原HTML/文本/SHA/UTC时间，见[授权前准备证据](../evidence/adjudication-llm-materials-20261003/authorization-preparation.md)。当前实取价格表为deepseek-flash(V4.1-Flash)与deepseek-v4-pro(Pro-0813)，不再将项目旧deepseek-chat配置称当前可用模型。拟建议选deepseek-flash非思考模式，尚未批准；不修改现有运行配置或探测外呼。请求model、响应model、供应商模型版本证据及配置版分别保存；供应商别名换模型或返回不可识别版本时拒绝合法落库并保留可允许的失败记录，不自动切备用模型。

价格快照允许按批准的最坏计价预留费用、次数和token额度。必须计全部输入包络/系统提示及可计费输出（含思考）；请求未知计费结果保留预算预留，不能当零费用释放重复发送。budget reservation在独立裁决记录原子竞争，达到额度停止；使用唯一request key并关闭传输自动重试，同key处理中返回冲突，已成功且现时有权才回放。429/502/超时不会默认无限retry。价格有效期/模型映射缺项拒绝外呼，现有10元/20次提案并非已授权。授权后的已知计价tokens累计以原始usage回执核对，截断原输出不得标为完整成功。

本地30天提案仅本轮替代输入验证对象/衍生，不能变更01/02案件或正式审计保留；到期删除、独立技术操作记录与备份恢复隔离的详细合同仍需D二审批准。供应商条款并未证明API零保留或账户不训练，第三方境内服务仍属项目数据出控制域；不得拿本地期限承诺供应商行为。准备文本许可为OFFLINE_PREPARATION_REVIEW，不允许自动注册MODEL。

03A开工须负责人明确选择材料/模型/预算/本地保留；授权维护方按当前产品主体实际授予逐案MODEL_EXECUTE与源MODEL；预算/config/source/retention证据版本齐备；确认旁表/事务/恢复/错误合同和负例设计，D通过后编码。当前只完成准备审阅，不据此D放行。原始业务范围/专业规则/真实案件价值A及01/02完成状态不变。


## 9. 本轮批准及剩余D二审（2026-10-03，现行结论）

### 9.1 已确认，不重问

负责人本轮明确批准，来源/责任/版本/范围见docs02§84末和[批准原件](../evidence/adjudication-llm-materials-20261003/user-approval-v1.json)。单案项目自有去敏替代材料、DeepSeek官方、材料整理验证，预算10元/20次/累计输入100000/输出20000，任一达到停止；新增模型记录及相关备份30天，不删除旧案件历史；经现有受控维护流程配置逐案模型执行许可。模型选择授权开发核实；固定deepseek-flash（当前文档DeepSeek-V4.1-Flash）、非思考模式、temperature=0、JSON响应、单次max_tokens=2048、不启用tools、不重试、不备用回退。具体证据[固定模型设计](../evidence/adjudication-llm-materials-20261003/fixed-model-design-v1.json)，不改CON配置或借咨询MODEL权。

### 9.2 预算准入合同

总预算按不可变approvalRef/版本/SHA+确切单案绑定，不能以新runId/重启/另一个subject/复制配置绕开总上限。DM8持久准入记录为每次外呼原子预留calls+1、计价input/output最大上界与费用；仅在当前配置/主体/来源/供应商用途门满足后预留。达到任一上限拒绝新调用；无法保守计算整请求tokens（含提示/JSON框架）或计价就不发送。备用模型或自动retry不得消耗第二次未登记调用。

usage成功有凭据才将预留结算为真实累计，保存prompt/completion/reasoning/cache明细与原始usage，非思考不意味着可以忽略服务报告的计费token。网络不确定/失响应/格式异常的已发送调用保持最大预留，费用未知不释放额度；需运营具实际usage凭据对账，不猜零。并发预留/恢复核budget ledger和run唯一键，不允许预算由Java内存或环境变量重置。计价有效性从官方原文版本固定，过期/改变或模型响应版本不可核验拒绝发送/合法落库；本轮公开高峰未命中价格2元输入/8元输出（百万tokens），保守累计token上限数学估算0.36元不是余额或真实消费证明。

### 9.3 03A端口与旁表细化（设计，不是迁移）

裁决领域拥有MaterialOrganizationModelPort/OutputValidator；应用负责CurrentCaseAdmission、CurrentModelSourceUse、ModelBudgetAdmission、OrganizationRunRepository，适配器只输出原始内容/usage/模型回执。模型无法直接写库/权限/事实。application读取现有trusted JWT/access及服务器材料，不接受客户端正文/endpoint/actor。候选结构保留sourceTag→真实material/source映射，mapping不外送案件/组织/identity，确切quote/UTF16半开区间可核查；source side为提供方，与材料中转述的说话方分别记录，转述归属未知即UNKNOWN而非模型猜认。

拟旁表adj3_model_scope（approvalRef/SHA、caseId、不可变限额/期限）；adj3_model_use及generation（逐源/version/SHA、provider、用途、批准证据/epoch）；adj3_model_budget_event（requestKey、reserve/settle、token与DECIMAL费用、uncertain）；adj3_material_run（runId、caseId、workVersion/inputSHA、requestKey、真实actor、本地源map、模型/config/prompt/price/retention版、最小请求/原输出/结构候选/usage CLOB及SHA、createdAt/expiresAt）。唯一(caseId,actor,requestKey)和approvalRef的预算预留共同阻重试。03A候选作为该run不可变候选集，03B再加decision/人工版本，不提前实现接受/页面。

CLOB必须使用Reader/Writer或驱动长文本，恢复逐字/SHA/候选定位及对象关联检查；run未COMMITTED不供产品成功读取。reserve事务锁准入/预算短事务，外呼释放所有DB锁；commit事务依既有command锁→case锁→budget/run锁验证全部授权generation/输入SHA。限定审计事件不改旧State字段/旧payload/SHA，仅新workVersion和Audit追加；run写入与事件原子。任一失败回滚新成功回执，不替換旧COMMITTED run；已发出调用的budget留存独立短事务不随业务回滚抹掉费用。

### 9.4 30天与备份恢复

每条新增模型正文/请求/候选/usage/raw输出期限为createdAt+30个24小时，来源期限较短则较短；到期先拒读/采用并安全删除只属于本轮模型表/文件。不得删除/重写V086案件/原文/既有历史或其旧备份；新增审计引用可保留在既有案件历史，但不嵌入模型正文以逃避30天。授权/预算准入有效窗口不可从恢复时间重新起算，过期不重启同一批准计数。

新模型备份必须独立可过期对象/密钥边界，不把未受30天治理的现有整库备份当此新增表合规备份；备份作业排除新增模型正文后另行按期限备份，或使用只在同一期限可恢复的加密对象。恢复先读可信当前时间/授权/截止/原始createdAt，过期模型对象不得复活；密钥/备份可访问路径也须30天关闭/删除。实现前先验证具体部署的备份配置能遵守，不擅改或删除已有整库历史备份。机器时钟/保留配置不可信或删除失败则关闭模型分支并告警，不延长TTL。03A验证须包含跨重启/备份恢复、过期及损坏拒读；不能只调用一个DELETE就称备份合规。

### 9.5 供应商实质例外与D结论

已实际GET官方公开privacy/terms/open-platform-terms及pricing（非模型API），原HTML/SHA/时间见[核实manifest](../evidence/adjudication-llm-materials-20261003/approval-provider-review/manifest.json)。开放平台协议把通用用户协议纳入，而通用第4.3条存在条件性训练/优化用途许可；未取得本API账号可适用的不训练排除证据，产品端开关是否覆盖API也无证据。不把一般政策关于境内运营个人信息的存储说明冒成API全部数据境内/零保留证明；项目外控制域处理与跨国境分别表达。

负责人限定用途是材料整理验证，不能默许拓宽为训练。唯一新增问题已精确提出；若批准接受该条，仅本轮替代材料/输出，不得迁移给真实案件或其他来源；若不接受，需要适用API账号的排除证据，不换供应商、不开不受控绕过。本轮限定用途中预算/材料/30天本地新增记录/受控授权已确认，不再阻断同一批准。

语义复核：机器/人工/正式结果分离、不可变原文、预算、事务/失败、恢复和权限合同已细化；仍有上述新增供应商用途阻断。**D未通过，03A未编码/未调用**，不把结构文档齐全冒设计门通过。此设计进展与未决只由总计划控制；专业业务A、ADJ十二项成熟度、01/02/CON/ENF状态不变。


## 10. 03A限定D最终复核与编码准入（2026-10-03，现行）

责任方明确接受供应商4.3处理，来源/版本/范围见docs02§84末与[供应商批准](../evidence/adjudication-llm-materials-20261003/supplier-approval-v1.json)。仅本轮去敏替代包；不对真实案件或其他领域继承。当前03A的用途/费用/保留/供应商问项均已闭合，不再次问同一授权。

最终技术合同：
1. 材料模型端口只收服务器选定的sourceTag/提供方/获准原文，不收内部身份。输出候选是原文中主张/记载陈述/争议线索/有锚点缺项，不是人工确认事实。定位由确定性validator从模型指定exactQuote的唯一出现位置核查；保留UTF16半开区间、源版本/SHA/原locator，重复同句须模型明确start/end，否则拒绝。未知来源/方别/枚举/原文漂移/空输出/截断/多余JSON字段拒绝整轮；归纳语义仍须人工，不能吹为幻觉检测证明。
2. provider固定deepseek-flash，官方HTTPS/nonthinking/JSON/无tools、无automatic retry/fallback，server config及approved政策版本记录。JDK HttpClient和现有适配器通信/超时技术兼容，不引用咨询业务候选/Registry或其授权。max_tokens=2048，单请求JSON UTF8<=16384字节；预算预留输入上界=全请求UTF8字节+4096固定双消息框架余量，输出按服务端max_tokens。若providerusage超出预留、缺usage或不同型号，保存不确定最大预算并关闭本policy进一步外呼，不把token估计当精确实际usage。
3. 预算批准Ref不可变且绑定单案；DM8锁表内原子预留/结算/不确定及幂等，计价微元整数（每token缓存未命中输入2微元/输出8微元，保守不使用低峰折扣）。重启/多个操作者/失败不会重置总calls/input/output/cost。失败已发出调用不退次数；未知费用保持预留，只有实际usage凭据可结算。
4. 03A正式组件包括领域validator/model port、真实provider适配、裁决专用budget/run DAO与过期密钥加密存储；HTTP接入只有当前JWT READ/MODEL_EXECUTE、精确案例/材料和READ/SAVE/ANALYZE/MODEL来源用途全通过才启用。现有source允许枚举仅增加显式MODEL值（旧行不获得它），沿独立源generation检查；原Grant允许枚举仅裁决增加MODEL_EXECUTE，不改共享身份内核。原始case State不新添字段；run落库的窄审计事件后续同事务，不偷偷绕过原repo。
5. 新模型payload AES-GCM加密，run UUID独立256bit key在受限服务账号目录而非DB或备份；key含可信createdAt/expiresAt，原payload/binding为AAD，读取前核到期/源权限。新备份只含密文及到期信息，不包含key目录；到期删除新key与model表记录，恢复后先expiry purge，不能从恢复时起算期限。保留30天只新模型对象，旧案/其备份不删；恢复缺key/篡改/过期失败闭合。本轮物理I须实测后方可称组件恢复通过，当前D只是合同准入。
6. 先实现这些03A组件并执行校验/真实获准输入模型调用/DM8组件恢复，再接可信HTTP及双库负例；组件测试不代替API授权证明，API完成前03A不得完成。03B人工采用和03C页面不提前编码。实际外呼组件验证仅当前已获准包，使用真实费用budget DAO，不用内存绕过预算；不声称其证明当前业务主体/专业判断。

设计二审结论：AI/人/规则边界、用途证据、版本/来源/权限、限额、异常/幂等、存储/恢复与分段验收可追溯；当前03A限定D通过，可编码。真实I/V及API/DM8恢复证据仍待实际执行，不以D通过冒完成、不开启未配置产品分支。


### §10实施复核补充：批准引用全局绑定与模型JSON重复字段拒绝

当前限额绑定不可变approvalRef，而非任由caseId/证据SHA编辑生成新预算。V091仅新增模型预算approval_key唯一约束，已存在配额仅用确切已知policy_key回填该引用，不清零calls/token/cost；有未对账旧配额时禁止新case绕过创建。当前物理ADJLLM20261003同一范围用无新外呼恢复验证，旧案例历史表不改。重复JSON字段整轮拒绝，不以解析器“末值胜出”覆盖源/方别。属于原D预算/原文完整性合同的防御细化，不改变用途、模型、费用或人/AI边界。

### §10实施复核补充：03A原生独立备份导入验证（2026-10-04）

本轮复核仅落实§10第5项既有D合同，不扩批准单案、用途、模型、身份或保留期限。使用DM8原生dexp/dimp仅导出ADJLLM20261003两张V090/V091旁表到本轮新建独立DM8实例；不停止源库/咨询，不连接执法实例、不恢复或改写运行库/权威库。恢复schema同名但从真实备份导入现存唯一批准及累计账本，不能重新初始化零额度或作为业务执行库。

数据库导出不含应用逐run解密密钥；原生导出另加密，备份文件和导出密码存放隔离，逐run密钥不复制进备份或证据。备份最大有效期继承现存policy/run最早期限，不从导入日起算；本次验证完成即删除本轮新备份和新恢复实例，只保留SHA/时间/计数/测试报告，不留可延长30天的业务副本。既有源库、案件历史、原密钥不删除。

验证顺序：只读源账本→独立原生导入→恢复额度唯一绑定/原密文SHA→通过原有分离密钥重载并逐项校验原文/定位→缺钥/密文损坏拒读→仅在独立恢复库及空测试密钥目录用到期时钟验证先清理/拒读→复查源记录/预算不变。到期测试不能把未来Clock用于原密钥目录，以免清理真实源密钥。该I仍是旁表恢复，不替代现时业务主体与正式HTTP全链。恢复实例不提供API/模型网络调用。

### §10最小兼容D补充：独立裁决正常注册与委托操作主体（2026-10-04）

负责人主体选择授权已记录于docs02§84末。选定project-adjudication/adj-deployment-maintainer-20261004、业务操作账号adj-case-operator-20261004；后者是负责人委托的交互操作账户，不是模型/Agent签名账户，不代表现实人员身份资质认证。既有ProductSubjects以HUMAN表示密码登录账户、区别SERVICE工作负载；该技术枚举不能提升为现实真人/专业审核认证。本轮没有HUMAN_REVIEW或审核动作。

现有独立ProductAdjudicationApplication不扫描咨询/执法，导入ProductIssuer/GrantWatermark/Client，但未提供独立主体注册/激活/签名Key维护HTTP控制器。不能拿CaseApiFixture的直写subjects/sessions或手签JWT作正式注册。补裁决本地CaseIdentityAdministrationController，只在显式identity-administration.enabled及固定organization配置启用，复用ProductMaintenanceProof/Subjects/SigningKeys正常流程：POST /v2/identity/subjects需SUBJECTS和当前组织，activate需可信TLS/一次性activation，POST /v2/identity/keys/rotate需KEYS和当前组织。错误不得暴露secret；禁止提供直接session/JWT/Grant写入口。既有01/02 API/内核/页面不改；共享Identity内核、CON/ENF代码/部署/组织/权限不改。

当前首次部署：issuer https://localhost:5445；现有5301/ADJLLM20261003保留V090/V091唯一approval和calls=1，其余既有身份/案件DDL仅在此专属schema增量；独立新权威实例5370/ADJAUTH20261004运行id2独立维护/水位及ADJ来源表，服务ADJAUTH_SVC/备份ADJAUTH_BKP/部署owner独立。沿ProductIdentityBootstrap离线初始化，只在空身份域执行并审计，固定独立CA/维护cert SHA/DN和SUBJECTS/GRANTS/KEYS/SOURCES（KEYS仅签名初始化）；数据库管理员只建实例/schema和执行已审DDL，不写业务Grant。根信任/密码及逐run密钥分离保存在受限目录。证书28天，精确Grant和来源有效期不超过2026-11-01T16:00:00Z；CREATE/READ/WRITE/MODEL_EXECUTE只本案TaskScope/DataScope。正常注册OAuth public client、一次性activation、PKCE登录；不继承咨询授权，服务重启不重建账户/许可/预算。

最小D复核结论：当前授权明确、角色和真实人员边界明确；既有空域初始化/证书校验/审计及现时Grant合同可复用，补控制器为ADJ独立部署的必要适配，不是新共享身份平台。仅本轮注册/运行分支D通过，可实施；正式同案I/负例及受影响回归仍须实际执行，结构检查错误不抵消也不冒完成。

本轮真实服务角色实证发现DM8跨schema插入来源event的外键需要父表REFERENCES权限（-5506）；在独立ADJAUTH20261004为既定ADJAUTH_SVC仅补adj2_source_use的REFERENCES DCL。属于上述最小角色兼容补全，不改业务许可、不直写来源状态、Grant或旧案；原503及回滚保留，后续仍走正常mTLS来源登记。
