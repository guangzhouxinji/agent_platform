# 咨询按需人工受理与原任务回流：当前源证核对和限定详细设计

日期：2026-10-09。技术责任：开发执行者 Codex。对应既有任务 `ARC-CON-PRODUCT-HUMAN-D-01`，任务及D状态只在 `docs/08` 维护。本稿不是新实施计划、人工平台或已过D的契约；未解决的确切差异只在 `docs/02` 的 Q-CON-HUMAN-FINAL-01 汇总。没有业务API、DDL、任命或运行放行。

## 1. 来源与本次准入

业务原文为只读 CON-12《知识产权咨询对话流管理需求文档》§5/§8 DF-08/§10.7～8，以及 CON-06《专利相似度判断需求文档》§3～4/§6～9。两份ready契约的源SHA本次重核与原件相同；逐条相关18个MUST/MUST_NOT及输入SHA见 `docs/evidence/single-database-installation-20261009/approved-execution/consultation-human-d22-source-inputs.json`。本稿不修改原文字段、17/41项最终范围或任何条款。

设计继承 docs/69 §1.3～1.4身份D、§4人工基础草稿，已通过的 `consultation-product-comparison-20261002.md` §3/§5～7技术对照D，以及 docs/55当前授权/事务/版本与docs/56的逻辑命令。docs/02 §38已委托开发团队设计五项产品协议；这不是专业人员任命或接收事实，也不使本Task详细D自动通过。真实任命/当前权限/资料和专业接受约束实际启用或I/B，不能笼统拿“尚无真人”停止协议源证核对。

本轮只做需求事实、既有D接口边界、限定详细设计和差异收敛。普通查询及现已批准咨询启动不依赖本人工分支。当前人工实现仍不得编码；不从结构READY或本稿产生实现准入。

## 2. 逐条事实与已有设计落点

| 原文/契约 | 已明确的义务和对应既有设计 | 本稿不推断的内容 |
|---|---|---|
| CON-12-MUST-022；CON-06-MUST-010 | AI辅助与正式决定分开；机器报告默认未复核，正式采用须中心指定专业人员复核。比较D保存机器/人工版和确切报告引用 | 不从登录、owner、mTLS维护者或普通READ Grant推出专业审核/正式判断权 |
| CON-12-MUST-023、MUST_NOT-007、DECISION-MUST-004；DF-08 | 请求、真实受理、决定分开；绑定确切任务/产物版的可信人员记录或真实接收回执才可证明交接；不默认外部通知 | 不把HTTP202/发送成功/模型建议/“待领取”当已受理；无渠道不伪造接收号 |
| CON-12-TASK-MUST-007；CON-06-TASK-MUST-007 | 原文列正式法律/业务判断、高风险、重要证据冲突、关键证据缺失、AI能力/权限之外及用户明确要求；交接带目标、确认事实版、任务状态、范围、候选/证据/冲突/失败/权限/未决项 | 不新增阈值、强制人工节点、判定算法或把所有资料缺口一概转接；本稿只列原文类型，不裁定受理条件 |
| CON-12-TASK-MUST-008 | 人工结果回原任务，使用户能查状态、补材料、获得结果、继续对话；组织、接口、时限仍待证据 | 不默认内部/外部首渠道、不填写服务期限或自行决定回流生效规则 |
| CON-06-MUST-011、DECISION-MUST-001；比较D§7 | 原目标链继续，纠正产生受影响版本；机器原件与人工修订不覆盖；新机器不能继承旧人工审核 | 不让旧包迟到意见审核新报告，不把查询成功直接变成成果已采用 |
| CON-06-MUST-012、MUST-017、TASK-MUST-004 | 重要材料不足列未执行项，缺权项不能冒完整对照；已知局部成果可与缺口并存 | 不用摘要补权项、不用人工请求补齐缺失资料事实、不强制普通咨询先做人审 |
| CON-06-MUST-016；PC-08 | 专家样本分别评价特征完整性、证据准确性、意见合理性；方法/样本/差异需责任证据 | 不以机械TITLE核验、编译、模型logit或账号命名作为专业质量/资质证据 |
| CON-12-TASK-MUST-004及docs/55状态分层 | 任务九状态、业务执行、受理/复核和官方状态分开；部分完成/证据不足/技术失败不混同 | 不引入统一“人工状态”覆盖业务Task，不推进签发/处罚/裁决等官方状态 |

## 3. 可承接的最小技术详细设计

### 3.1 确切输入、包与版本

仅承接已在源文/既有D中的逻辑坐标：原Task/目标/确认事实版本，所选对象和分析输入版本，实际结果/报告版本与摘要、证据及原文定位/来源版、冲突/失败步骤、当前缺口、请求目的和允许动作。所有正文仍经现时权限和来源用途交集取得；不能根据历史已读、任务owner或包内存档Grant开放。英文名仅解释旧D词汇，不冻结新物理字段或中心格式。

比较分支引用现有比较D的确切reportVersion/reportSha、机器方法版及人工父版；一般资料查询不凭空创建比较报告。机器原件、人工原稿、人工修订分别不可变并保留父版/差异/提交主体/时间/依据。资料/任务纠正的依赖影响沿现有比较D处理，不重写原证据；无法确认相容性的旧包不能自动并入当前成果。

首包的实际Task、组织、受理渠道和业务用途尚未核定。本稿不把旧ROTOR/TITLE工程案例、开发REQUEST_GAP/OPINION_GAP或旧接收人改名为首条正式人工包。

### 3.2 可信身份与动作边界

身份沿项目正式Issuer/PKCE和现时会话；组织、动作、资源版和用途从当前权威取得，不接收浏览器自报操作者。人、服务和Agent分开：工作负载不能充任人工受理/专业复核人，Agent只可整理和草拟。职责模板不等于已任命人员，也不自动授包范围。

请求/读取、领取/受理、人工修订、专业复核、正式采用分别核对应职责及精确授权；“受理人”不自动等于复核人或正式采用者。实际动作集合与岗位规则未核前保持其依赖分支关闭。不把执法异人规则移植到咨询，也不自行裁定owner自领或同人复核是否合法；见Q-CON-HUMAN-FINAL-01。

### 3.3 逻辑命令与可见性

docs/56已有 `RequestHuman / WithdrawRequest / AcceptHuman / RecordHumanResult`，本稿只建立其来源映射，不选择新URL、请求字段、DB表或对外接口。比较D已有报告reviews契约不是独立人工受理流程已实现的证明。

普通查询仍可交付具证据的辅助内容及限制。系统提出需要人工的理由，与用户请求、可信受理记录、人工稿、复核/采用记录分别呈现。未接通只展示获准指引/未受理，不自动通知或制造已接管。具体受理时点、退回/撤回影响、未知外部回执和首包回流事务尚需在本Task详细D补足；不能以“CAS可实现”替代业务效力判断。

docs/69§4的“真正提交才记受理”须与AcceptHuman和意见提交区分，在详细协议中写明其指向哪次受理事实/事务，而不是凭实现者理解选一种。任务状态和受理事实仍分层，不能因有人写意见就隐含正式受理或正式采用。

### 3.4 原任务回流与恢复

沿现有 `AnswerRevision` 分层成果思想保存目标、已做事项、证据/产物版本、逐项结论或未知、限制、未完成项和继续动作，并精确标注人工意见的归属；不覆盖机器原稿。旧包、新输入和新报告不能借历史受理/审核事实自动确认当前版。原Task成果完成判定仍按主要目标、必要证据和限制披露，不由本人工模块代写。

逻辑命令沿docs/55的幂等/预期版、当前授权和来源epoch、审计及事务约束；恢复不重放外部发送或人工决定，不用备份内Grant覆盖当前权威。具体回流的原子对象、CAS/锁序、撤回竞态及未知回执对账须在首包/效力规则明确后单独详细设计；本稿不新建冻结Runtime或DB模型，不提供未经证据支持的保留/删除/超时默认值。

### 3.5 失败与验证设计

身份失效/无精确权/权威不可核/当前版冲突沿既有身份D处理，不能伪装为空待办。无受理能力与未收到可信回执显示未受理或待核实，不表现为成功。外部系统和正式业务状态不因本域请求改变。模型/工具故障、资料不足、真实无结果、当前授权受限与人工处理中分别解释。

后续D反演必须包含：普通查询不强制人工；请求未接通；双人抢同包；未领写意见；领后撤权/来源撤回；旧包迟到；纠正后旧人工意见；同键冲突与未知回执；撤回和回流竞争；恢复旧Grant/旧审核；服务主体冒人；受理权限冒专业审核/正式采用；局部成果被误标完成。这里只列验例义务，尚未执行或验收。

## 4. 已有专业责任证据的准确边界

已读2026-10-07专业准备包和任务质量包：professional-review-index-v1.json范围是留存原件离线机械审查，分母2，professionalAccepted=false、currentAuthorization=NOT_EVALUATED，reviewerEvidence/rubricEvidence/professionalLabels均null。既有audit还明确不是全仓不存在专业事实的证明。它们支持原件/质量准备工作，不支持本Task具名受理、任命、当前包Grant或正式判断权。

本轮新编译的正式咨询main有230项Bean定义、61个MVC映射；在已解析的名称/映射范围内未发现Human-named注册类型或human/reviews-named路径。这是配置定义元数据检查，不是实时请求或所有动态入口不存在的证明。源码中的DevHumanAcceptanceStore及V2HumanAcceptanceConfiguration仍带development条件，未进入该正式定义装配；不把其历史成功当本人工实现或受理事实。

不重新请求五项协议设计委托。需要组织/专业责任方提供的实际任命、适用范围/有效期、正式审核/采用权与真实受理样例，仅针对相应实际启用或专业验证；新目标正常身份/客户端登记由原A/B接续正常完成，不从注册结果自动推出这些专业事实。

## 5. 本轮可用出口与后续界限

本稿提供逐条源事实核对、18条相关契约输入、准确的专业证据清点和最小详细设计边界；不是完整D退出。精确差异、责任类型、解除条件和安全可继续项统一在docs/02 Q-CON-HUMAN-FINAL-01，状态只回写docs/08既有任务，不生成第二Trace/Gate/Review账本。

编译/装配工程已独立退出；人工详细设计仍可继续在该范围内核第一包、受理事实与失效/回流语义，不能写业务API/迁移或宣布现实人员已任命。674授权自然退出时优先回到新鲜权限、正常身份、咨询首个真实入口和三域各自已准入运行验证；人工分支未准入不阻断普通咨询。

## 6. 内部工作台领取受理与版本回流的技术细化（2026-10-13）

本节承接docs/02 Q-CON-HUMAN-FINAL-01的两次人类答复V1及原话，业务范围仅内部咨询处理。首渠道和领取/撤回/退回/版本归属已有依据；第1～5节的同范围未知描述保留为2026-10-09历史，不再阻塞这些技术细化。本节是开发团队的派生技术设计，不证明代码、真实人员、专业复核或正式采用已完成，也不独立签全Task D。

### 6.1 最小包和职责

内部请求以原Task、目标/事实、Input、候选、报告及证据/来源的确切版本引用构成不可变包；正文继续从受控原存储读取，不复制历史Grant作为访问许可，不用“最新报告”替代领取版。无比较报告的咨询包只能列实际已存在成果和缺口。请求理由及允许目的仍采用原文触发类型和用户明确意图，不能把该答复扩大为新专业判断场景。

请求人与领取人均由正式当前人类身份取得；请求需原Task工作权，读取每项正文需现时READ及来源逐用途许可。领取另需精确组织/Task/包版本的HUMAN_ACCEPT，意见另需相应HUMAN_REVIEW及包正文权限；这两个既存动作不等于专业资质或正式采用权。ProductAccessPolicy已拒绝SERVICE执行这两动作，可复用；尚未存在正式人工资源适配器或任命，不从枚举反推实现。2026-10-13用户V2允许请求人自行领取并处理普通辅助意见，必须记录真实操作者且不得标为独立复核；此结论替代此前owner自领未决判断。专业复核依独立规则接入，不创建默认人、专业角色或授权旁路。

包保留引用原Task和所含对象的有效保留依据与最早使用期限；无覆盖包/意见的保留依据时拒绝写相应正文，不增加默认TTL或延长来源许可。实际首包的组织、目的及允许动作仍须核明，不能由旧开发案例命名生成。

### 6.2 受理和意见分层

| 操作/观察 | 派生技术结果 | 业务依据与边界 |
|---|---|---|
| 提交内部请求 | 追加不可变包、待领取记录和命令回执 | 请求不是受理；不通知外部渠道 |
| 领取确切包 | 同事务CAS写唯一领取人、领取包版、受理事件及回执，提交成功才报告已受理 | 用户V1“成功领取确切包后算受理”；docs/69的“真正提交”在此指领取事务提交 |
| 双人抢领 | 仅同一包预期处理版的CAS赢家受理，败者冲突且无受理事件 | 技术细化，不由前端待办显示决定归属 |
| 提交意见 | 当前领取人向领取版追加不可变意见和原Task历史回流引用 | 不覆盖机器原稿，不代写专业复核、正式采用或Task完成 |
| 撤回/退回 | CAS终止该次处理并追加原因/主体/时间/原版事件 | 用户V1；原包及已提交意见保留，不隐含取消整个Task |
| 旧版意见 | 仅保留领取版历史引用；当前新版标需复核，不自动升级旧意见 | 用户V1；历史可见性仍重新鉴权 |

“本次处理结束”不允许原回执重新开放同次处理；若继续请求，必须由当前有权人显式创建关联的新请求，重新核包版本和目的，不自动转派或续领。是否允许该新请求及其业务目的仍按首包允许动作审查，不从可做CAS推定。

### 6.3 提交边界、纠正及未知结果

沿docs/55与现有Task/比较D的命令回执、预期版和事务约束设计，未选新URL/SQL/通用工作流。写前认证当前人类会话及精确权限，读取原Task和包引用闭包，校验状态、期限、来源epoch及授权代次；以固定Task→处理记录的顺序锁/CAS，不在事务内调用模型。领取提交前重核上述事实，领取事件/投影/回执同事务，失败不留半次受理。

撤回、退回与意见写入竞争同一处理版：终止先提交则后来的意见写入拒绝；意见先提交则其不可变历史保留，随后终止阻止继续处理。此规则只细化用户“结束本次处理”和已有不可变证据要求，不销毁已提交意见。任务纠正不把旧包改为新包；对尚未终止、仍有现时历史读取/写入权限的旧领取包，意见仅向其原版历史回流，当前成果保留新版需复核标识。Task取消/到期、处理已终止、授权/来源无法确认时拒绝新写，不能以“保留历史”为由接纳迟到越权写入。

意见与同原Task的回流引用、版本归属、事件、回执须同一业务事务提交；若当前Input已变，事务只追加旧版历史引用和新版需复核依据，绝不推进当前机器报告/人工复核指针。Task业务完成、正式状态和受理状态分别所有，不借回流更新官方状态。回执响应前再次验证授权/来源/最早期限，继承本轮Input返回边界修复经验而不复用其测试当人工证据。

同键同摘要在重新鉴权后查询原回执；异摘要冲突。领取或意见提交丢响应时客户端显示待核实，经原键查服务端持久回执确定结果，不换键重领、不再写第二意见。原处理已终止时只能展示终止历史及原受理事实，不能通过旧成功回执复活。重启/恢复依赖不可变包、处理事件、意见/回流引用和回执闭包核验，以及备份外现时授权/来源水位；缺引用、回退水位或未知提交保持关闭，不自动重放人工决定。

### 6.4 D反演与未交付出口

本节逐项预期为：请求成功而无领取仍未受理；两人同版仅一受理；领取丢响应由原回执还原；未领取/他人写意见拒绝；终止先赢则意见拒绝、意见先赢则历史保留；纠正后旧意见只属旧版且新版需复核；撤权/来源失效/期限恰到拒绝；SERVICE冒人拒绝；受理人冒专业审核/正式采用拒绝；旧备份/旧回执不复活处理。以上是设计推演，尚未执行人工自动化或真实流，不冒组件验证。

当前人工D出口仍需在原ARC任务完成首包目的/职责/允许动作与保留依据的限定、命令/数据/UI/失败/恢复详细落点和逐条反演语义审查；实际人员、当前Grant及真实领取样例归后续启用/I，专业责任/样本归专业验证/B。IMP-CON-PRODUCT-HUMAN-01不得提前写API、迁移或代码。父COMPARE及普通咨询沿原已准入路径，不因这项文档进展冒运行交付完成。

## 7. 首场景具体命令、数据和工作台设计（2026-10-13）

### 7.1 决定、输入和工程增量

业务来源为docs/02 Q-CON-HUMAN-FINAL-01的V1和V2；V2原答复引用 `request_user_input_async/call_a52745c46ba641ea909a4a39c8df8c4b/0`，责任方当前项目用户，日期2026-10-13 Asia/Shanghai，范围本项目内部工作台。首场景为普通辅助意见，无专业复核或正式法律决定效力；请求人可自行领取并处理，保存真实操作者，本人处理不标独立复核。专业分支保留原需求义务且待独立规则，不把普通意见冒专业审核。第1～6节历史未决表述在同范围由本节替代。

本增量关闭的缺口是首包只有边界摘要、缺具体消费契约；当前必要性是V2已明确允许行为，可继续详细D。复用Task/Input/报告不可变引用、ProductConsultationAccess、ProductAccessPolicy、ProductGrants和现有前端原键恢复；新增仅为领域人工包绑定、处理/意见/原Task回流关系及消费者。退出观察为18条设计反演有具体落点、未决保留范围可集中确认；不是运行能力。真实首Task ID、操作者和资料授权在功能启用前核实，不预置工程材料或固定候选。

包来自当前用户显式普通辅助请求；系统可按原需求列提示理由，但专业触发不得转换成已完成专业复核。服务器从原Task取得目标和确认事实版本、Task状态、实际Input与候选、已有报告和证据定位、冲突/失败/未执行项、来源/用途限制和未决项。每项附对象类型/ID/版本/SHA及存在或缺失原因；报告可为空，缺权项必须保持缺失，不能补摘要。浏览器只能提交选择坐标和理由，组织、DataScope、实际作者及来源事实由服务端受信存储导出。首场景只读原件、追加普通意见/修订、撤回/退回和原任务历史回流，不修改原资料、机器输出、专业review或官方状态。

### 7.2 逻辑记录及不变量

下表是待实现领域契约，不是已存在DB表。物理DDL须在D通过且IMP任务依赖满足后确定。

| 逻辑记录 | 必需数据及唯一性 | 不变量 |
|---|---|---|
| HumanPackage | packageId、不可变packageRevision、组织/Task/DataScope、请求人issuer/subject、请求时间/理由/普通辅助目的、上述完整manifest及规范化摘要、保留依据ID/版本/SHA、使用/保存截止 | 同包版内容不可变；引用闭包摘要变化必须新包；不复制来源正文/凭据/历史Grant，不借SHA获得保存权 |
| HumanHandling | package坐标、独立processingVersion、WAITING/CLAIMED/WITHDRAWN/RETURNED、真实领取人issuer/subject、领取时间、终止原因/时间、最新意见引用 | 一包一次处理只有一个领取人；只有领取事务提交才CLAIMED；这是处理状态，不覆盖Task九状态 |
| HumanOpinionRevision | opinionId、包版、领取人、真实提交人、时间、前意见ID、普通意见正文/逐项依据/未知/限制/继续动作、正文SHA、来源依赖、独立复核=false、保存依据/截止 | 领取人追加不可变版本，不覆盖机器或旧意见；提交人必须等于当前领取人；本人处理标识由身份比较计算，不接受客户端自报 |
| TaskHumanReturn | 原Task、包版/Input/报告版、opinionId、回流时间、CURRENT_VERSION或HISTORICAL_VERSION的归属依据 | 当前性从服务器实际版本比较得出；旧意见仅历史，新版需复核；不写当前报告review/官方采用指针 |
| HumanEvent / CommandReceipt | eventId/单调序号、确切包版/处理前后版、动作/主体/时间/依据；issuer/subject/Task/命令种类/原键、规范化请求SHA、结果对象/提交序号 | 事件和回执与相应业务变更同事务；同键异载荷409；事件不存token、原文或意见正文；回执历史不代表现时权限 |

packageRevision是授权资源版；processingVersion是并发CAS版，不随每次领取/意见使Grant失配。意见更正需expectedOpinionId与processingVersion，旧意见及差异保留。意见提交本身不隐含结束处理；撤回/退回才执行V1的终止语义。重新请求是用户显式的新包及新处理，关联原包并重新核全部事实，不自动续领。

### 7.3 命令与查询契约

拟议路由位于既有 `/v2/consultation/tasks/{taskId}` 下；这些URL是本ADR设计，尚无已实现端点。所有写带原 `Idempotency-Key`，不得由通用api.request的缺省随机键隐式重发。正文/回执响应均no-store。

| 命令/拟议路径 | 输入、授权与前置 | 成功及拒绝结果 |
|---|---|---|
| RequestHuman，POST `/human-requests` | expectedTaskVersion、选定Input/报告坐标、理由、purpose=ORDINARY_ASSISTANCE；原Task现时WRITE及全部引用读取/来源用途/保存依据；服务端生成包坐标 | 201返回包摘要/WAITING/处理版/回执，不称受理；新包/处理/请求事件/回执同事务。未知坐标或未批准目的400，版本变化409，缺保留覆盖拒绝写 |
| AcceptHuman，POST `/human-requests/{packageId}/claim` | packageRevision/manifestSha、expectedProcessingVersion；确切包HUMAN_ACCEPT及读取权，当前人类身份；WAITING且未终止/过期 | 200返回真实领取人/时间、CLAIMED和新处理版/回执；双领CAS败者409无受理事件；SERVICE拒绝。自领允许且selfHandling=true，独立复核=false |
| RecordHumanResult，POST `/human-requests/{packageId}/opinions` | 包版/SHA/处理版、expectedOpinionId、普通意见/逐项证据/缺口/限制；领取人、确切包HUMAN_REVIEW及来源/读取/保留核验 | 201追加意见、TaskHumanReturn、事件/回执和处理版；未领/非领取人拒绝；旧领取版仍有效可回历史，新版提示需复核；终止/Task取消或期限到达拒绝 |
| WithdrawRequest，POST `/human-requests/{packageId}/withdraw` | 确切包版/处理版和原因；真实请求人、原Task WRITE及包范围授权 | 200终止为WITHDRAWN，已提交意见保留；重复原键只查原结果；已终止的新命令409不复活处理 |
| ReturnHuman，POST `/human-requests/{packageId}/return` | 包版/处理版和原因/缺项；真实领取人及现时HUMAN_ACCEPT | 200终止为RETURNED，原任务可补料后显式新请求；非领取人/WAITING/已终止拒绝，不自动转派 |
| ReadPackage / ReadOpinions，GET `/human-requests/{packageId}`、`/opinions` | 原Task与确切包现时READ，逐项来源/用途/期限；历史同样重新授权 | 包摘要、真实受理/终止、普通意见及归属；来源受限时不返回不可安全分离的正文，可返回获准受限占位；无权404不泄漏包存在 |
| ListRequests，GET `/human-requests?cursor=...` | 当前Task范围READ；逐包可见性，待领取还核HUMAN_ACCEPT；游标绑定主体/筛选及版本 | 授权过滤先于分页和计数；无法核权503，不返回伪空待办；只显示确切包状态，不推断领取结果 |
| ReadCommandReceipt，GET `/human-command-receipts/{key}?command=...` | 原issuer/subject/Task/命令作用域及当前权限；返回正文另核来源 | 已提交返回原对象坐标与历史结果；确切不存在为NOT_FOUND，服务不可核503/UNKNOWN；仅查回不能生成意见、重领或结束处理 |

共通错误沿现有产品边界：401重新登录；404不可见；409重新读取/核对确切版；400输入或目的不合；503权威/存储不可核；传输中断为UNKNOWN而不是失败。接到确定不存在的原键结果后，用户可显式按原键继续同载荷；UNKNOWN期间仅GET，不换新键重发。清理后旧键不能被当全新命令；到期资源拒绝，保留期内清理标记保留原作用域和摘要。

### 7.4 可信资源、提交顺序与恢复

ProductConsultationAccess.ResourceFacts已支持资源类型/ID/版和精确组织、Task、DataScope，不是仅Task授权；ProductGrants.Scope已有这些坐标和HUMAN_ACCEPT/HUMAN_REVIEW动作，不新增授权平台或默认Grant。增量是由咨询域已持久化包取得 `HUMAN_PACKAGE/packageId/packageRevision` 的可信适配；请求创建以原Task工作权限准入，生成包不自动签包Grant。维护职责按既有正式流程签精确Grant；无Grant可展示获准未受理状态，但不能领取。适配器不得用请求body、包历史Grant或Agent声明构造允许事实。HUMAN_REVIEW在本分支仅表示写普通意见的动作授权，专业资格/任命另判。

本域锁序Task→HumanHandling→Opinion/Return→Event/Receipt；只锁相关原Task/包，来源/授权遵循各自既有D锁序而不跨域反向持锁。先认证主体/会话和独立水位，解析可信包并核当前资源/来源/保存权；事务内先锁命令作用域查原回执，同键同载荷重验后返回原结果，异载荷409，仅新命令再核预期Task/处理/意见版并CAS写完整闭包。提交前核当前授权/来源和期限，失败回滚所有本域记录；不得在合法原键重放前用第一次提交已改变的processingVersion拒绝它。意见与撤回/退回争同一处理版，终止先赢则意见拒绝，意见先赢则保留后再终止。包引用当前版在回流事务重读，变为旧版只写历史归属，不推进新版review。

现有ProductGrantWatermark只有currentGeneration/assertUnchanged/change，不存在通用业务guard。重复代次核验不能证明独立权威与本域数据库之间不存在所有提交竞态；新人工写入的权威线性化/撤权竞态遵循docs/55与身份D，缺证明时不签完整D/运行写入准入，不虚构分布式事务或扩大维护身份。正文返回前重新核当前身份/READ/来源及最早期限；历史回执可以说明过去提交，不能授权展示正文。

本轮有界源码审查确认：ProductGrants.revoke用lockEpoch的FOR UPDATE锁精确id2_current_epoch再更新Grant/epoch；ProductSubjects.disable先锁id2_subject再撤销该主体会话；ProductComparisonScopeGrants先锁Task再锁主体。可选最短单主库绑定是在基础设施适配器内按已核顺序锁Task、真实主体/会话、排序的精确授权epoch及来源现时记录，再进入HumanHandling的本域CAS；域通过端口取得受保护事务，不直接依赖身份表或DM8。必须逐一证明同一DataSource/事务管理器和所有撤销/来源变更写者锁相同行，期限在提交前重新检查；授权锁持有期间撤权主库提交等待，本域提交后撤权生效，PENDING/源失联则本域回滚。此是派生技术候选，尚未证明来源/独立会话撤销全部写者及恢复清理绑定，不能声称只给查询加FOR UPDATE便全部闭合。需要的反演为业务锁先赢、撤销先赢、独立PENDING先出现、事务回滚释放、主体停用及会话单独撤销、来源撤回、确切期限到达；验出不同数据源或写者不遵守时关闭该绑定，沿docs/55凭证协议另审，不要求用户决定锁序。

恢复校验包manifest、处理事件链、意见父版/真实操作者、Task回流和命令回执的完整闭包；恢复前从备份外取得当前授权/来源/清理水位，缺失或回退保持关闭。重启未知提交按原键查持久结果，不补造受理、不重放人工决定；存储事务失败返回确定失败仅在证明未提交后成立。清理水位/备份过期拦截和权威提交竞态尚须在原任务设计审查明确可执行绑定，不能用现有Grant水位冒完整删除/来源水位实现。

### 7.5 内部工作台与失败交互

复用ConsultationProductPage的当前Task/Input/报告坐标与选择失效epoch，AntD表单/表格和现有lucide图标。原任务增加普通辅助请求及历史意见视图：请求确认展示确切输入/报告版本、目标、实际成果/冲突/失败/缺口、用途限制与请求理由；提交后WAITING只呈“待领取”。待办按当前授权过滤，详情取得确切包后领取；详情只在成功回执后呈“已受理”和真实操作者。自领显示“本人处理”，意见标“普通辅助意见”；专业复核/正式采用不因普通意见自动改变。

领取人意见表单包含逐项意见、证据定位、未知/缺口、限制与继续动作；撤回/退回需明确原因并展示所结束的包版，成功后禁止继续同次处理。意见修订选择前意见版本，不覆盖原稿。当前成果与旧版历史分开：旧意见显示其包/Input/报告版，新版需复核提示可回到原Task补料/确认新Input并显式请求；不把旧意见放进当前review组件。普通咨询仍可不请求人工而继续已有成果路径。

切Task/Input、退出或401/404时清空受限正文和草稿内的资料摘录，迟到响应按epoch丢弃；不能将浏览器缓存当撤权后历史。表单本地草稿仅当前受控页面内存，不默认localStorage保存正文。写前固定键/载荷/包版，提交中禁重复；丢响应显示待核实，复用ComparisonStart原键GET恢复模式，确认不存在才提供显式继续，当前版本变化要求重新读包。409聚焦版本冲突，503保留错误状态并提供重新读取，错误role=alert/aria-live且聚焦对应字段；无权限不保留全文草稿。键及非正文恢复坐标的跨刷新保存须有同范围保留依据，未批准前不自动持久化。界面不提供专业审核或正式决定按钮，不把待办空列表当权限服务故障成功。

### 7.6 未覆盖保存对象的待确认建议

已检查ProductRetentionPolicy仅约束Task元数据的依据/版/SHA/maxAge；docs/55六自然月明确开发期，模型历史规则亦非普通人工意见依据。exact-input-use-approval01只批准两份确切替代材料及CLAIMS的DISPLAY/COMPARISON，截止2026-10-14 18:00 +08:00，未明确包/意见保存许可。首次使用须逐对象先复用确实覆盖的现有规则；不得把180日建议续给上述材料，也不宣称全仓不存在其他保留规则。

以下提案于2026-10-13获用户V3“确认上述范围和期限”，来源 `request_user_input_async/call_6bae067ceef144ecafba8bd743a73160/0`；责任方当前项目用户，范围仅本项目内部普通辅助意见，确切原问/结论见docs/02同项V3。它现为该范围产品保存决定，不是已实施配置/清理或新物理授权；专业/官方档案不在此范围。

| 保存类别 | 建议范围/期限与起算 | 到期处理及追溯影响 |
|---|---|---|
| 业务包及意见 | 项目自有manifest引用/版本/SHA、请求理由、人工意见各不可变版/依据/限制、Task回流；180日。处理中从最后已提交处理变更起算，读取/失败重试不续期；撤回/退回后固定从终止时起算且不再续期；不得超原Task允许保存终点 | 到期先关闭新写、正文读/导出/恢复；随后受控清理正文、附件（首场景无附件）、引用投影/索引。不能再看全文差异或重放该包；历史“保留”是在获准期限内，非永久 |
| 最小操作追溯 | 包/意见ID和摘要、动作类型、真实issuer/subject ID、时间、处理版/结果、原键载荷摘要/回执坐标、清理结果/失败原因；不存姓名、凭据、原文或意见正文。每项365日从事件提交时固定起算，不因读取/新事件续期；原Task/身份规则更短则取更短 | 仅有权审计访问，不供业务正文恢复；能说明谁在何时领/退/撤/提交及清理，不能重建意见内容。到期清除关联标识和摘要；统计若无单独去标识许可亦清除，不默认留永久墓碑 |
| 来源内容及依赖意见片段 | 原文不复制。确需引用/保存来源衍生内容时，按原Source许可/用途和更早使用/保存截止；不可分离的意见整段按最早截止，不能仅标自主撰写延长来源权 | 原权到期/撤回即时停止展示与恢复，按原规则清理；可保留不含内容的受限占位只有在元数据保存被覆盖时成立。全文追溯可能提前消失，旧版仍不迁成新版复核 |
| 备份/清理失败 | 项目自有新增对象备份建议最长30日滚动；原资料更短或未允许备份则服从原规则，不增加第三方备份权。到期当刻逻辑关闭，清理任务24小时内首次尝试并记录结果，失败保持关闭并重试/报责任管理员 | 不宣称备份可即时逐条物理删除；记录最迟轮换日及未完成清理，恢复前核备份外到期/清理依据并再清理，禁止重新开放过期正文。无现成可执行绑定则作为功能启用前恢复阻断；不执行删除/轮换，本轮物理暂停继续 |

按V3记录本次答复/日期/版本/责任方/范围，逐对象绑定原Task和Source覆盖交集；不在V3且无现有覆盖的类别拒绝相应持久写/恢复。清理事实与授权独立水位不能混为一物；若追溯记录也到期，不假设仍可永久按旧ID还原，但不复用包ID/命令作用域且所有旧资源到期拒绝。此规则的确认不等于清理代码、第三方备份权限或实际删除成功。不可变包保存原政策/来源硬截止，最后变更时刻和effectiveRetentionUntil由处理事件追加及当前投影计算，不改写manifest或旧意见；Task/Source固定更早截止不因滑动180日延长。

## 8. 18条契约的语义反演和剩余依赖

下表是设计正反例，不是测试执行或完整能力已实现证据。源文/契约和SHA沿第1节源证，D状态唯一在docs/08；同一原需求的其他能力仍由原任务承接。

| 条款 | 具体落点 | 正例及预期 | 反例及预期 | 确切剩余依赖 |
|---|---|---|---|---|
| CON-12-MUST-001 | §7.1/7.3/7.5原Task视图及返回引用 | 同Task查意见后补料继续对话，意见仍绑原包 | 切Task后旧响应到达，epoch丢弃，不能串入新会话 | 会话/消息独立API由原CON-12任务；此分支需D/IMP依赖，不以人工查询替代全项 |
| CON-12-MUST-022 | §7.1/7.2普通意见标识 | 普通意见返回但机器报告仍未专业复核 | 点自领/提交即正式采用，禁止写该状态 | 正式复核岗位/规则另接，普通分支不依赖专业任命 |
| CON-12-MUST-023 | §7.3 AcceptHuman/回执 | 确切包领取事务提交后显示真实操作者/受理 | 只有请求201或丢响应未知，不显示已受理 | 真实人员及包Grant在启用前；外部接口/编号不在首场景 |
| CON-12-MUST_NOT-007 | §7.3 RequestHuman、§7.5待领取 | 无包Grant显示获准未受理指引 | 模型称已转交或发送外部通知，拒绝该陈述/动作 | 接收能力装配及真实回执验证，不默认外部通知 |
| CON-12-MUST-031 | §7.1/7.6 | V2普通场景/V3保存有可定位决定；保留按已批准逐对象配置 | 将开发六个月/材料许可当人工保存配置，拒绝 | §7.6已获V3；时限/SLO/预算等原配置决定仍另门禁，不新增默认验收值 |
| CON-12-TASK-MUST-004 | §7.2处理状态分层/§7.5缺口 | 部分候选完成且有意见，Task继续披露部分完成 | CLAIMED使整个Task已完成，拒绝状态代写 | Task九状态/成果判定由原域实现，不借人工CAS证明 |
| CON-12-TASK-MUST-007 | §7.1 manifest | 用户明确普通求助，带目标/事实/状态/候选/冲突/失败/权限/未决 | 正式判断触发却只收普通意见便标专业完成，禁止 | 专业触发的独立组织流程未决；普通包逐对象核V3/原权交集 |
| CON-12-TASK-MUST-008 | §7.2 Return、§7.3查询、§7.5 | 原Task查确切意见/退回原因，补料显式新请求 | 意见回另Task或自动移到新版，事务拒绝坐标不符 | 内部人员/Grant启用前核；服务时限无批准SLA，不承诺 |
| CON-12-DECISION-MUST-004 | §7.2/7.3受理记录及回执 | 请求WAITING→真实领取CLAIMED→意见分层可查 | 以意见/HTTP202补造领取或正式决定，拒绝 | §7.4权威提交/恢复绑定技术审查，真实受理I另证 |
| CON-06-MUST-001 | §7.1/7.2意见证据，继承比较D | 对人工选定候选引用确切原文行，列差异和未知 | 以检索排名代逐项证据或意见无定位称完整，拒绝完整标记 | 比较实现/实际获准输入和方法验收由父COMPARE，不造新算法 |
| CON-06-MUST-010 | §7.2意见不可变、§7.4不写review | 自领意见记录真实人、本人处理、普通辅助 | 旧意见调用既有reviews审核新报告，保持409当前版守卫 | 专业审核规则/指定人员另门禁，不需普通自领独立任命 |
| CON-06-MUST-011 | §7.2/7.4版本引用 | 单候选失败保留其他成果，纠正建新Input/关联包 | 新意见覆盖旧机器稿，或旧包自动续成新版，禁止 | 既有父COMPARE重算/失败原键能力复用；人工D/IMP尚未退出 |
| CON-06-MUST-012 | §7.1缺失、§7.3退回/撤回 | 重要材料缺失返回不完整及未执行项，允许求助 | 人工受理将未执行项抹掉称完整成功，禁止 | 完整异步比较服务沿原COMPARE；取消/专业复核各自门禁 |
| CON-06-MUST-016 | §7.1普通范围、§8验证分类 | 机械反例注明非专业；专家将来独立固定样本/分母/版本评价 | 18行映射或编译通过冒意见合理性已验收，禁止 | Q-CON-QUALITY-01专业样本/方法/阈值责任证据，非当前普通保存设计前置 |
| CON-06-MUST-017 | §7.1目的、§7.5不可用分支 | 无图像/法律资源/专业人员能力按对应待补齐显示 | 用普通操作者/旧开发样本补专业资质，拒绝 | 原资源/对象性能/环境及各专业能力独立准入，实际启用逐分支核 |
| CON-06-TASK-MUST-004 | §7.1缺权项包、§7.5限制 | 缺权项只列线索/证据不足，意见继续披露 | 摘要代权项输出完整范围判断，拒绝 | 真实权项用途/现时Source在消费前，普通意见不豁免 |
| CON-06-TASK-MUST-007 | §7.1专业触发区分 | 关键缺口提示需专业复核，普通意见仅提供辅助 | 请求人自领后被标独立专业复核，禁止 | 专业岗位/流程待独立规则；V2普通自领规则已确认 |
| CON-06-DECISION-MUST-001 | §7.2/7.4历史Return | 同Task第二次纠正保留原机器和人工版，旧意见只回历史 | 人工意见覆盖前版或推进新报告review指针，拒绝 | 继承父COMPARE不可变纠正链；历史按V3及更短原权期限保存 |

下一工程审查在本ADR核权威提交的现有可执行锁序、恢复/清理绑定及上述语义反例，不能以结构检查自动签D。普通保存范围已获V3，不再索同类批准；专业流程、实际人员/Task/资料授权分别在对应分支核实。当前只有设计证据，源码实现/人工组件验证/部署/真实流/业务验收均未新增。

## 9. 现有提交和恢复机制的有界复核（2026-10-13）

源码依据为platform-api的ProductGrants.revoke/lockEpoch、ProductSubjects.disable、ProductAuthorizationService.save/remove、ProductIdentityController.logout、ProductClients.revoke、ProductSigningKeys.revoke、ProductSourceAdmission.admit/withdraw、ProductSourceWatermark.change及ProductSourceEngineConfiguration。这里只核可复用的写者/Bean绑定，不执行DB事务或宣称已有人工事务端口。

单主库候选的完整适配锁序应在Task之后、HumanHandling之前加入：id2_key_control（按Issuer）→当前JWT的kid对应签名键→当前client→真实subject→确切sid/jti会话→排序的id2_current_epoch精确动作scope→排序的con4_source_current/任务SourceScope现时绑定。此顺序涵盖密钥撤销先锁key_control、client撤销先更新client再撤会话、主体停用先锁subject再撤会话；logout和OAuth移除直接UPDATE同一会话行，数据库行写锁同样与读取FOR UPDATE竞争，不要求它们先持显式查询锁。Grant撤销以精确epoch锁串行，来源变更先设独立PENDING、事务更新source_current并封存history_sha；人工事务不改变权限/来源事实，只在取得相同行锁后由现有策略重核。缺行/重复/旧版、不同DataSource/不在同事务的适配均拒绝。多包同一Task先锁Task；多scope/source统一坐标排序，查询或等待超时回滚并显示可核实失败，不能跳过锁。

ProductSourceEngineConfiguration将来源watermark主库和业务组件接到同一个注入JdbcTemplate/PlatformTransactionManager，这是源码装配候选证据；人工配置尚不存在，部署仍必须验证最终同一事务连接，不能据注入名冒实际DB同一性。独立水位读取失败/PENDING即关闭本域提交；在锁保持期间重核policy、来源用途和时间，并提交本域完整闭包；水位后变只作后续访问门禁，不能抹掉已提交事件。现有签名验证/会话有效期和原Task取消/期限继续生效，旧包允许历史回流不豁免取消/撤权。需要对上述实际全部写者进行同库并发/回滚与末次返回反例后才称组件/DM8验证；本轮没有把行锁机制推演冒执行成功。

现有ProductTaskBackupAuthority.capture/assertUnchanged记录Grant与Source当前水位，并调用ProductTaskRecoveryVerifier检查原Task闭包；ProductTaskBackupBundle校验备份expiresAt/retentionRef/version。它们没有上述HumanPackage/Handling/Opinion/Return和清理闭包，也没有完整人工删除水位，不能直接当人工恢复完成。最小增量是在现有咨询恢复核验端口追加这五类坐标/摘要/父版/真实主体/事件和回执一致性，及V3生效依据/每类计算截止；备份清单只包含获准未到期对象，不拷来源正文。到期过滤先于恢复后可见指针，过期正文在隔离恢复中清理，失败保持隔离；随后才允许正常现时READ，绝不从旧包重领或重写意见。

清理须在现有受控维护/恢复生命周期接入仅人工对象范围的事件：先记录清理意图及截止依据、关闭该对象访问，再清理正文/受控索引/回流正文投影，提交完成或失败/重试结果；操作追溯不记录被删正文。备份外必须保有该恢复批适用的现时清理/到期依据；不能用Grant/Source水位替代，也不能在未具备该依据时开放回灌内容。追溯到期后不永久保留清理墓碑：只接受在现时合法备份期限内的恢复包，以可信时钟和V3/原硬截止重新计算，过期/时钟不可核/政策未知均拒绝。物理删除、备份轮换和恢复实际操作仍遵守原暂停及目标运行准入；这是待实现接线，非默认后台清理器。

命令去重还需验证receiptRetentionUntil不早于其原资源可接受新写/重放的最后期限。现有TaskCreationService给Task固定expiresAt并限制于批准maxAge，可复用为原请求作用域硬上限；包/意见回执同时受该Task期限约束。正文180日先清理时，获准最小回执仍阻止原键再生包；全部追溯清除后原Task或确切包必须已不可写，不从缺行自动重建旧scope。若实际Task可写期限长于V3允许的最小去重记录保存期限，拒绝该人工写入配置并说明确切不相容项，不能延长审计/保留永久键或把缺旧键当全新操作；应在原Task契约内另审相容的命令作用域设计，不更改Task状态语义。

本轮反演已审：重放先于新预期版校验；注销/停用/密钥/客户端/Grant/来源分别存在需锁定的主库写对象；PENDING不能退回旧权限；旧意见不经当前reviews；有限追溯与恢复不依赖永久墓碑。尚未成立的事实为人工适配实际连接/全部锁顺序/清理外部依据和闭包运行验证，属于Codex工程交付而非待用户业务授权。现有IMP人工的父COMPARE/IDENTITY实现依赖未完成，当前不得提前编码；全ARC D仍保持未过，首普通场景V1～V3合同歧义已消除，专业/正式采用分支仍独立关闭。

## 10. 普通辅助首场景D1结论和可实施合同（2026-10-13）

本节完成原ARC-CON-PRODUCT-HUMAN-D-01的普通辅助首Task设计审查，替代§9把“尚未有人工代码/实际连接/运行验证”列作D阻断的判断；C/I/B证据仍必须后续取得。来源为CON-12/06 ready契约、docs/02同项V1～V3、docs/55§6、docs/69§1/§2/§4/§5及§9列出的现有源事实；派生技术责任Codex。普通首场景五项D均通过，且§8的18条语义映射继续适用；不释放专业复核、外部接收、正式采用或全CON-12/06能力。新场景不得借普通D1继承任命/用途/服务SLA。

### 10.1 五项判定与验收责任

| 设计项 | 明确合同及D判定 | 正反例及后继验证责任 |
|---|---|---|
| 实际适配装配 | D通过：咨询域只依赖HumanTransaction/CurrentHumanFacts/OriginalReferences/Receipt/RetentionAuthority端口；platform-api基础设施适配采用现有主JdbcTemplate及对应DataSourceTransactionManager，启动核manager所管DataSource与JdbcTemplate相同且非备份外authority连接。当前身份/资料从真实存储取得，任何参数不符/迁移缺失关闭人工路由，普通咨询不受其装配影响 | 相同源与manager成功；两不同源、非事务回调、未验证主体/包事实拒绝。Codex做装配/回滚C；运行责任方核实际schema/权限和DM8 I，不是D前置 |
| 完整锁序 | D通过：§9锁序冻结为Task→Issuer key_control→签名kid→client→subject→sid/jti→排序精确授权epoch→排序source_current/TaskSourceScope→命令作用域→HumanHandling→Opinion/Return/Event/Receipt。先核当前会话、水位，再取锁并复读，旧回执在新版本CAS之前查；新命令核目的、正文/引用保存许可、Task/包期限及处理版。提交线性化以受锁保护的本域事务为准，前后权限检查不能追回已发送字节；外部独立水位PENDING/未知拒绝继续，不声称分布式原子事务 | 业务先取相关锁则撤销主库写等待；撤销先提交则业务复读拒绝；外库先PENDING则业务拒绝；超时/死锁回滚整笔且原键查回。Codex对真实写者并发/未知/回滚做C，DM8隔离I另准入，不要求先有实现才过D |
| 回执期限相容性 | D通过：包硬Task使用截止T不可续期；本次事件审计截止A=min(事件提交+365日、已批准更短审计保存界限)。每次RequestHuman/新意见等写入必须满足now<T且T<=A，确保原命令资源仍可写时回执不被提前清除。正文B=min(处理中最后提交变更+180日或终止时+180日、Task内容保存截止、Source内容保存截止)，读取不续期。B可以早于T，但仅在正文清理后保留至A的最小回执/受限摘要，B之后该包新写拒绝；A到期后T也已到期，旧Task/包ID拒绝重建。长于该相容窗口的实际Task配置拒绝人工写，不擅延审计 | T恰等A允许配置；T>A拒绝，now==T/B/A各关闭相应动作；正文先清理、原键重放只元数据，审计清除后旧Task命令拒绝。Codex做时间/重放C，产品接受人验证展示；无新业务事实未决 |
| 备份外清理依据 | D通过：在既有独立authority库新增仅人工清理scope的投影/事件，记录组织/Task/包、单调generation、READY/PENDING、intent、截止依据/政策SHA、清理闭包摘要、事件固定审计截止。清理适配先独立CAS置PENDING→主库事务标该包不可读写并写意图/回执→按原受控清理协议清理正文/索引/回流正文投影→主库封存实际清理结果→独立推进READY及摘要。任何未知保持PENDING，缺据拒恢复。原主体/GRANTS/KEYS/SOURCES四职责不能推出清理权限；清理/备份使用docs69§5既有受控部署维护身份和明确对象范围，实际最小账号/权限必须安装前核，无在线产品清理按钮/自动授权 | 主提交前失败可证明未变才释放PENDING；主提交后独立推进失败拒恢复；正文删除失败维持关闭且记录重试；scope/政策不匹配拒绝。Codex负责迁移/适配/C；部署和权限责任方负责I，不新增此次物理授权 |
| 恢复闭包 | D通过：§9“备份只包含未到期对象”修正为沿docs69§5原生整库导出，不在封包中伪称过滤行。到期内容仅在已有正式清理实际完成后出快照；仍存行必须具全部物理保存覆盖，否则拒封包。清理暂停不允许冒清理成功。包寿命<=30日且<=覆盖所有仍存对象的物理保存截止；获准仍存历史审计不因业务使用到期自动丢弃。隔离恢复先核包认证/期限、当前Grant/Source及人工清理scope代次/摘要，再核包manifest/处理事件/意见父版/真实人/Task回流/回执闭包；任何恢复正文到期或已清理须在隔离目标完成清理核验才开放允许部分，失败保持隔离 | 整库含未覆盖已过期正文拒封包；旧scope/缺闭包/孤儿意见/未知intent拒开放；相同manifest/原意见和现时权限允许；过期包、时钟不可核、30日超限拒绝。Codex实现恢复C；独立DM8/完整保护返源I单独准入；普通用户B不代专业B |

外部清理事件按V3每项365日或更短已批准审计界限保留，不永久保存墓碑。独立scope只有仍存在合法备份/可写包时可核：其validUntil不超过适用审计界限；清除scope必须证明所关联最后备份已过期且T已到期。恢复包引用已清除/未知scope直接拒绝，不从主快照重新初始化authority。scope初始化只能在独立核查本Task无已有人工历史后由受控安装程序完成；出现旧快照/未知清理历史必须拒绝初始化。备份期限短于追溯并不自动授权其他行多保存30日，所有仍存行逐对象核覆盖。RPO/RTO、实际介质/岗位/清理执行窗口仍约束对应I，未被普通D1替代。

### 10.2 两遍语义复核结论

正向按V1～V3走显式请求→WAITING→唯一真实人领取→普通意见/不可变修订→原Task确切版回流；请求人自领标本人处理且永不独立复核。纠正仅将旧意见归历史，新版需复核，撤回/退回仅结束本次处理，不取消Task或覆机器/官方状态。逆向逐§8核未领/异人/服务冒人/旧包别名/正文越权/专业冒采用、竞争终止、未知原键重发、短审计长Task、旧备份复活及封包假过滤，均有唯一拒绝或受限结果。两遍审查没有剩余普通场景业务设计事实需要用户代答；全部实际权限/资料/岗位证据只门禁对应功能启用或专业分支。

结论：普通辅助首Task D1通过；原ARC任务以该首场景限定设计退出，不等于专业/外部/全CON项D。§7拟议API/data/UI由此具普通范围设计依据；C/实际DM8/部署/真实流/B仍未实施或完成，不以本节反演表冒测试执行。

### 10.3 实现依赖核对和首个可执行增量

IMP-CON-PRODUCT-HUMAN-01原依赖不改，COMPARE及IDENTITY不标DONE。身份现有Issuer/PKCE、主体/session/client/key、精确ResourceFacts/Grant和双库水位已有源码及C/历史I；当前PENDING/EXPIRED、安装发行/真实正常链是启用缺口，不证明需要重造身份。比较现有Input/机器报告/原字节引用、父版/多代沿用、现时Source守卫、纠正/显式失败重试及原键GET已有源码/专项；模型预算0、正式DM8和专业验收是相应运行出口，普通无报告包不依赖它们。真正源码增量是普通人工不可变包坐标、处理/意见/终止/CAS、保存窗口、事务/回执/清理恢复适配和UI；旧PatentHumanAction只允许UNAVAILABLE fixture，不能充当产品状态机。

第一可执行子项PC-CON-HUMAN-ORDINARY-DOMAIN-01仅实现咨询域不可变处理/意见转换和V3期限计算，输入是后续受信端口取得的真实人/包绑定/硬截止事实，输出供同事务存储使用；不授权限、不提供替身存储/路由或声称领取已受理。它只依赖ready契约和本普通D1，不调用Issuer、COMPARE执行器、Source读、模型或运行实例，故原未退出的父运行出口不构成此源码切片前置；原IMP完整门禁继续。验收为自领真实人非独立复核、双领预期版/异人/未领/终止后意见拒绝、意见不覆史/旧版回流标记、读取不续期/恰到/原权更短/长Task短审计拒绝。完成后立即返回原IMP，下一真实增量是HumanTransaction现时授权/锁适配及回执持久闭包，不用更多纯工具代替它；其增量D/任务登记及原依赖范围须再次依事实核明。

### 10.4 请求阶段Task事务适配的窄D

普通领域16项C后已返回原IMP。PC-CON-HUMAN-TASK-TRANSACTION-01的必要缺口是当前ProductConsultationAccess为授权查询，未提供普通人工请求的主库锁/回调/末次失败整笔回滚；增量只实现RequestHuman的Task/身份/WRITE阶段，不宣称它覆盖后续包HUMAN_ACCEPT/REVIEW、Source引用读取或回执存储。后续请求应用还必须在同一事务完成来源/保存/包闭包守卫；不能只取得本阶段permit就提交完整人工包。

合同：仅现时确认目标的IN_PROGRESS Task，从主存储导出组织、DataScope及authorizationVersion，不取浏览器组织/资源事实，不把Task业务version当Grant资源版。用现有真实JwtDecoder重新验证原token，解析会话绑定kid及registered client ID，锁Task→组织→key_control→kid→client→subject→sid/jti→确切Task WRITE epoch；核当前key/client/HUMAN/session、活动组织和Task期限/状态、精确WRITE/TASK_WORK，回调取得可信Task和真实Actor。新command预期Task版本由后续请求应用核，旧键回执需先查，不能本适配提前做预期版拒绝。回调后重解token/重读身份与Task事实/重核WRITE/独立代次和全部时间，失败整笔回滚；禁止嵌入已有事务以免末次核验后上层尚可继续写，使用本适配独占的主事务边界。DataSource与manager不相同、主库等于authority、未注册客户端、工作负载冒人、旧scope均拒绝。source许可/清理authority不在此阶段，后续完整应用未实现前无人工路由接通。

同主库组织行加入Task之后的锁序以阻止现时组织变更；既有身份Source/Grant写者没有反向锁旧Task的路径，本适配只覆盖单Task单token/单WRITE，后续多包/多Source沿§10.1锁序。正例是可信Task内回调提交；反例是回调导致会话/Task/期限/权限失效时业务写回滚、注销先提交拒绝、业务先锁会话则注销主库提交后发生、主DataSource不匹配/已有外层事务拒绝。Codex负责H2真实表/签名token/独立水位的C及现有Access回归；DM8/安装/部署/I/B另准入。窄D通过，无未决业务事实；依赖普通D1和已完成领域子项，复用身份/Task已有源码，不释放完整IMP原未过运行出口。

装配限定纠正（2026-10-13）：独立authority指独立池及备份外scope，不等于必须另一DM8实例。已有AuthorityDataSourceSettings和docs69已准入shared-instance：同URL时必须不同schema、不同账号并且各自池；同池/同schema/同账号拒绝，schema或账号无法核实也拒绝。ProductHumanTaskTransaction复用该既有规则，不改变部署拓扑，不从JDBC URL推断物理别名隔离。最初C只覆盖两H2库和同库同schema拒绝，未覆盖shared-instance；该缺口由FIX-CON-HUMAN-TRANSACTION-ASSEMBLY-01补真实同实例两schema两账号允许及混用拒绝。ProductGrantWatermark只增加主DataSource访问器，其原授权SQL/水位协议未变；当前能力清单必须在保存原字节后机械更新此确切SHA和受影响SQL行锚点，不能改历史运行/批准原件或增加Grant动作/表/角色。该窄修D通过，无新业务事实。

### 10.5 请求包持久化阶段的窄D（2026-10-13）

Task事务23项C后回原IMP。下一必要缺口为RequestHuman尚无不可变manifest、WAITING投影、最小请求事件和原键回执的原子持久闭包；PC-CON-HUMAN-REQUEST-STORE-01只实现这一存储阶段，来源/资料/保存许可导出和完整应用提交仍是后继必需依赖。复用真实Task事务、OrdinaryHumanHandling/Retention、现有ProductComparisonJson的严格结构解析/规范化摘要机制，不增通用平台、替身路由或默认Grant。

物理设计V109仅新增con6_human_package、con6_human_handling、con6_human_event、con6_human_receipt。包主键(package_id,package_revision)，确切Task外键及真实请求人外键；规范化manifest包含服务器导出的Task/组织/DataScope/Task业务版、包版、请求人、绑定/证据引用（无原文）、请求理由、ORDINARY_ASSISTANCE、政策ID/版/SHA和已核硬截止/创建时间。manifest_sha覆盖这些全部字段，不含自身摘要。处理投影只从WAITING/version1开始；事件序号1/REQUESTED/before0/after1与真实请求人/时间/manifest摘要和请求摘要同事务。回执主键(Task,issuer,subject,command,request_id)，命令本阶段仅REQUEST；结果包版/事件序号和固定audit_until，无正文/token。包正文与180日body_until独立，审计取365日/原Task更短，ReceiptCompatibility不满足拒写。读取、重放不更新创建时刻或期限。

存储适配仅在已活跃且该DataSource绑定的Task事务内调用，接收可信Context及后续已核事实manifest，逐字段绑定Context、V3时限和规范化摘要；不得将它当Source/保存权适配。原键在新命令预期Task版之前查询：同摘要原结果且重新核政策/时限/包闭包，异摘要冲突；无键结果才新写。保存后重读SQL原生坐标/摘要、WAITING初始投影、请求事件及回执闭包；失败整笔回滚，不发布API/装配使用Bean。领域后续领取/意见将扩展处理事件链，此阶段读取只证明请求历史，不把请求回执的WAITING当现时未领取，更不重置已有投影。到期正文拒读，过期回执拒查，不把已清理键当新键（功能启用前仍须完整清理scope/恢复守卫）。新旧资料依赖/报告缺失原因由真实loader后继生成，存储不会虚构事实。

窄D通过：正例真实Task事务写包/投影/事件/回执后原键查回；反例缺任一闭包、摘要/Task/主体/时间坐标串改、异载荷键、非绑定事务、180/365/更短期限恰到、末次Task事务拒绝回滚全部表，均明确拒绝。Codex负责源码迁移及H2组件，不部署、不清理、不扩Task/Source权限，不冒完整人工I/B。该存储阶段依赖已实现Task事务和普通D1，不等待运行父任务DONE；完整IMP原依赖不改变。

### 10.6 当前目标与用户事实引用阶段的窄D

请求存储34项C后回原IMP，必要增量PC-CON-HUMAN-TASK-FACTS-01是从真实con3_goal_current/revision、con3_search_input、con3_message和GOAL_CONFIRMED原回执导出绑定，消除后继包使用调用者自报目标/事实ID的可能。此阶段仅返回已确认用户消息/目标/Input的ID、版、SHA及政策/最早期限，不输出或复制原文，也不读取外部Source原件；不以source_scope中的来源ID认定存在材料或授权。实际比较Input/报告/失败/来源引用仍由后继loader加入，缺报告可表达但此部分查询不声称完整manifest就绪。

使用原token重新解码，issuer/subject须等于Task事务Context，Task持久facts须完全一致，原Task当前READ必须允许；在绑定主事务内重读现时Task/Goal指针及原始消息，确认goal/input同Task/组织/目标版/Task业务版、同请求SHA、同query/source_scope，真实确认人匹配消息人、原GOAL_CONFIRMED回执匹配目标ID/Task版/请求SHA/创建时刻。消息SHA按原UTF-8字节核；目标SHA是原确认请求摘要，不虚称目标正文单独摘要。全部对象期限/政策ID版SHA保存进服务器输出，GoalBinding=goalId:revision；FactBinding=messageId:contentSha；InputBinding=inputId:requestSha；最早期限包含Task、Goal、Input、消息。查询末次重解原token、Task/Goal指针/READ及期限；不能把该只读查询当完整提交前Source锁。后继完整请求应用在写前及末次提交重加载/比较并核原Source/save/清理authority；Task WRITE阶段若扩到多READ/Source锁须按§10.1整体锁序调整。

窄D通过，Codex负责组件：现时确认关系允许且输出无正文，缺指针/回执、旧Task版/旧scope、Goal/Input不一致、消息字节与SHA不同、确认主体/时间或政策缺失、恰到/READ撤权/异token均拒绝；原件保存不变。没有新的业务事实或物理授权；真实来源与保存权未核前仍不得把该输出提交为完整人工包。

### 10.7 完整请求组装的源码落点与第一依赖（2026-10-13）

#### 10.7.2 统一请求授权与来源锁窄D（2026-10-13）

PC-CON-HUMAN-REQUEST-LOCKS-01沿普通D1及本节完整锁合同，实现现有Task事务的内部完整请求入口，不增加HTTP/Bean/权限动作。该增量的必需性是旧入口先锁WRITE，无法保护后继READ及Source读取；复用真实Task/身份、ProductSourceAvailability、ProductPatentDocumentAccess及两个独立水位。派生设计责任Codex，无新增业务决定。

在Task/身份锁后，内部引用规划器只导出完整来源ID和字段用途集合，不消费正文；数据库导出每个当前scope的确切ID/版本/Task版本/组织/Source合同决定/epoch/rowVersion/期限及指针rowVersion。同源字段合并；比较字段保留独立集合，不使同源普通字段自动要求COMPARISON。Task READ/WRITE与Source DISPLAY/SOURCE_DISPLAY、必要COMPARE/COMPARISON按(domain,type,id,action,purpose)排序一次锁epoch；随后依sourceId锁所有source_current，再锁全部TaskSource current及确切scope。锁后/返回前复读scope、原epoch及全部权利见证，不补前序锁；调用方在消费真实引用后还须验证引用集合不变，锁组件不自行证明规划器覆盖了全部材料。

见证复用字段DISPLAY/SAVE及比较字段COMPARISON、精确scope DISPLAY/COMPARE、现时Task READ/WRITE，逐字段派生useUntil并取scope/Task/JWT截止。保存所有当前Active/原授权/最短期限，末次必须相同且now严格早于截止；独立PENDING/撤权/来源或scope漂移均整笔回滚。SAVE不成为Grant动作。拒绝混用主DataSource或外层事务。旧WRITE阶段入口保留已验证语义，完整请求只使用新入口。

窄D两遍反查通过，仅这一内部事务增量准入；退出观察为真实签名JWT/主独立迁移表允许、无READ/缺SAVE/缺COMPARE/PENDING/期限恰到/原坐标变化全回滚及撤权主写者等待，退出立即回真实原件/Input/report和结构化manifest组装。源码/C不冒完整提交、DM8、部署或普通用户可用。

#### 10.7.3 普通原资料结构化引用窄D（2026-10-13）

PC-CON-HUMAN-MATERIAL-REFERENCES-01沿本节完整组装合同及§10.7.2窄D实现普通原资料引用阶段。输入只含source/document/documentVersion与字段集合，不含路径、摘要、来源版或权利。内部函数必须取得同一完整请求Plan的持锁见证且字段包含于已规划集合；复用ProductCollectedPatentTitles.loadFieldEvidence核完整原件，再匹配唯一真实文献/版本，导出SOURCE_DOCUMENT、有类型的字段locator/SHA、Source合同决定/capture摘要/epoch/rowVersion、确切scope版和最短已核期限。输出不含原文、token或历史Grant许可。

消费前后各核当前Plan与真实原件引用，来源/Grant/期限或原字节变化、重复文献/未知版本/未规划字段均拒绝整笔提交；对照衍生引用仍需后继Input/report专门认证。普通资料不要求COMPARE，现有来源DISPLAY/SAVE和原件验证不能被省略。本技术窄D两遍反查通过，责任Codex，无新增业务事实。退出观察=真实受控文件SHA/实际迁移表及签名JWT下普通引用正向、原字节串改/未知版本/字段越界/末次变更与四表回滚反例；退出立即回完整结构化manifest/Input/report组装，不把本阶段当人工产品API。

#### 10.7.1 清理scope持久闭包窄D（2026-10-13）

PC-CON-HUMAN-CLEANUP-SCOPE-01仅实现内部基础设施存储及核验，无Bean/HTTP入口、无物理清理。主库V110的con6_human_cleanup_scope和独立V006的id6_human_cleanup_scope保存同一scopeId、组织/Task、generation、状态、intent、政策ID/版/SHA、historySha、createdAt/validUntil；主库另有con6_human_cleanup_event，保存规范化事件JSON及SHA，事件不含正文或凭据。初始事件为INITIALIZED、generation=1，绑定真实维护操作者引用和固定auditUntil；空scope不等于可恢复的旧Task。

初始化还必须调用InitializationAdmission端口核当前维护对象权和备份外可信新Task/无历史证据；数据库空行检查不能替代此证据。该端口无默认实现或运行绑定，未知/清除/恢复Task即拒绝。C的受控许可仅验证持久边界，不能当实际权限；后续受控安装适配的证据读取及核验须另审后接通。本增量不实现清理writer，generation>1未知事件闭包全部拒绝，不能假称已覆盖后续清理恢复。

初始化是仅供后续受控维护装配调用的内部方法：独占主事务先锁真实Task，拒已存在scope、任何人工包/事件/回执历史；独立唯一键INSERT PENDING是初始化CAS，碰撞即拒绝而不覆盖。主事务写同一scope及初始化事件并封摘要，提交后凭精确intent/代次/政策/摘要CAS独立READY。任何阶段失败保留PENDING或缺失状态，普通读取/恢复不能重试初始化、自动释放或复活；初始化没有对外权限证明，实际维护对象授权与历史核查须在启用前完成。未知独立历史、恢复快照和已清除scope不得调用初始化。

初始validUntil由获准审计硬截止和Task可写截止/最后合法备份截止核定：now < validUntil <= min(初始化事件+365日、原更短审计截止)，且validUntil不得早于Task可写截止或最后合法备份截止。读取不延长；后续清理事件或扩展期限需另审具体写者，不在本增量实现。事件摘要由现有ProductComparisonJson规范化结构及SHA组成有序链；主行historySha、全部实际事件、独立historySha必须完全一致。现时核验先读独立、读取主行和全部事件并重算、再读独立，要求两次独立相等、两处完全一致、READY/无intent、政策匹配和now严格早于validUntil及每事件auditUntil；末次assertUnchanged复用同核验。恢复调用只接受现时独立scope和快照确切generation/historySha，不从快照补行。主/独立DataSource必须不同池，复用现有same-instance不同schema/账号规则。

窄D通过，派生技术责任Codex，普通业务V1～V3无新增未决事实。C必须使用真实两处表验证初始化、重复/已有历史拒绝、缺失/PENDING/代次/政策/事件篡改/末次变化/恰到拒绝及迁移逆向。该出口只消除完整请求的清理依据存储缺口，仍不代表完整请求、实际维护、恢复或人工API/UI可用；退出后立即返回§10.7完整锁序与真实引用组装。

装配窄修及结构修复后回原IMP，当前缺口为三个已验证阶段之间尚无完整提交应用。以下为Codex派生技术合同，沿普通D1/V1～V3；不新增业务效力、SAVE Grant动作、维护身份或实际操作许可。完整组装须作为一个业务出口核验，不能将单独查询器接成可提交人工入口。

请求输入仍为§7.3的预期Task版、选定Input/报告坐标、理由和普通目的；新增资料选择只能携带现有对象坐标，不携带组织、作者、授权、水位、截止或自报SHA。服务端从TaskFacts取得Goal/消息/SearchInput，从ProductComparisonInputStore取得指定不可变ComparisonInput及当前指针，从MatrixStore/MatrixReader取得指定已提交报告的原生坐标、原始存储摘要和候选/attempt/receipt闭包；报告空缺保留真实缺项。选定ComparisonInput必须与当前Task/Goal/SearchInput一致，reportId必须属于该Input和候选，不能取latest别名或仅凭前端reportSha。当前请求不将祖先报告包装成当前COMMITTED；显式沿用引用沿已实现ParentEvidenceReader的确切链认证，记录原Input/report及归属，不补新报告。

资料引用分两种消费：普通原资料只核所选字段DISPLAY和SAVE、现时Task READ及精确SourceScope DISPLAY/SOURCE_DISPLAY；比较Input/报告及其衍生字段保留原比较成果读取所需的DISPLAY/COMPARISON/SAVE及精确SourceScope权限，不因普通辅助目的消除原许可。ProductComparisonDocuments/PreparationLoader要求COMPARISON并生成比较内容，不能直接用于普通原资料读取。普通路径复用ProductCollectedPatentTitles.loadFieldEvidence及ProductSourceEvidenceFiles.verified，按source/document/version匹配真实原件与所选字段定位，不调用模型/比较解析，不以TITLE代CLAIMS。输出使用有类型的引用记录，保存对象类型/ID/版/原字节SHA/实际locator、Source合同/决定/epoch/rowVersion、精确scope版、政策及使用/保存截止；无来源正文、token或历史Grant授权。manifest升级使用显式结构和规范化解析，不用拼接字符串编码结构化对象。缺权/未存在的材料只能形成可安全分离的缺项，不保存其正文或假摘要；已选择但不可认证的绑定拒绝本次提交。

原ProductHumanTaskTransaction只先锁WRITE，不能通过在回调里再加READ/Source锁冒完整排序。完整请求在Task/身份锁后由内部组装器根据受锁Task和真实选择坐标列出全部精确epoch键，按(domain,resourceType,resourceId,action,purpose)稳定排序锁Task READ/WRITE与实际SourceScope各必要动作；规划只读坐标，不先返回来源正文或认可新命令。随后按sourceId锁source_current，按Task/source/scope坐标锁task_source_current和确切scope行，复读完整材料集合，集合/指针或scope改变整笔拒绝，不在已持后序锁时补前序锁。ComparisonInput创建和MatrixStore提交均持Task锁，可复用该写者边界；来源启用/撤回与scope写者先独立PENDING后主UPDATE，主行锁使已进入主事务的写者等待，外库PENDING/未知仍在各阶段及末次重核拒绝，不宣称跨库线性化。原请求键在新Task业务版CAS之前查回；旧键只走其原坐标重新授权，不用当前选择替换原包。

清理scope入口采用组织/Task坐标，包坐标属于该scope下的清理事件，解决未来packageId不能在创建前由安装者逐包初始化的问题。独立authority投影包含scopeId、组织/Task、generation、READY/PENDING、intent、政策ID/版/SHA、清理闭包摘要和固定validUntil；主库镜像及不可变清理事件绑定同一scope，事件另含确切包版/对象类别/截止依据/结果摘要/发生时刻及固定auditUntil，无正文。请求写入必须核主/独立scope一致且READY、无intent、现时政策及截止有效，提交后重核scope代次/摘要；普通请求追加不推进清理generation或清理历史摘要。初始化仅由原受控安装/维护适配在独立核查该Task无人工历史/旧清理scope后进行，正常请求不自动初始化；恢复不从快照补独立scope。实际最小账号/确切对象覆盖在安装启用前核，不让GRANTS/SOURCES旧角色自动取得清理权。

清理沿§10.1的独立PENDING→主事务关闭对象访问/写意图→清理正文与依赖投影→主封存结果→独立READY推进。失败/未知保持关闭，清理尝试和结果分开记录，24小时首次尝试义务不由一条计划记录冒执行；当前暂停下不运行物理删除或后台清理。独立scope及事件适用V3每事件固定365日/原更短审计界限；scope.validUntil须覆盖获准仍可写对象和合法备份而不超过该界限，不能由读取续期。清除scope必须证明最后合法备份已到期且原Task不可写；未知/已清除scope拒恢复。原生整库备份仍对所有实际存行逐对象核覆盖，不在封包器中伪过滤人工行。

完整应用验收必须观察：无比较报告的真实普通请求四表原子提交；指定真实Input/原报告/Source版本引用与摘要闭合；只有DISPLAY但缺SAVE拒绝，普通原资料不要求COMPARE，而比较衍生内容保留原许可；READ/WRITE/Source撤销主库写者竞争、独立PENDING、末次恰到、引用集合变化和scope缺失/漂移全回滚；同键查回不重建/不重置处理；到期正文仅获准最小元数据回执，未知scope旧快照拒开放。源码/组件出口与DM8、部署、实际人/资料和业务验收分别记录。工程第一依赖为Task级人工清理scope的持久闭包与只读准入，再完整锁规划和真实引用组装；不能先接API/UI再补此守卫。该详细落点不等于这些组件已实现，下一增量需按同一主计划登记后编码。


### 10.8 完整请求组装的具体落点（2026-10-13）

源码落点补充（ADR-CON-HUMAN-REQUEST-WITNESS-20261013，Codex技术派生）：请求命令的资料坐标、字段集合和报告ID按稳定次序规范化，SourceUse字段用不可变排序集合，使重启后的manifest摘要不依赖JVM集合迭代次序。历史同一性固定原Goal/Input、原件SHA/定位、Source合同决定与硬期限；旧SourceScope及当时JWT/Grant访问截止只作历史观察，新的当前scope/Grant仍须完整认证，不把旧授权当现时许可。新提交末次见证则连当前scope及访问期限一起比较，漂移全回滚。祖先报告固定保存截止取原报告和确切Input链原生期限；当次ParentReader访问窗口单独由loadChecked返回，逐次收集后在最后水位核验之后再判恰到，不写入manifest或当保存权。Task自身retention_ref/version/evidence_sha/created_at/期限另与部署批准政策核对，不能只依赖Goal/消息政策。以上沿§10.8既定权限、重放及期限合同，不改变V1～V3业务决定。

原键只读落点（同一§10.8.4合同）：标准接口刷新恢复仅需Task/requestId，不能要求浏览器长期保存理由或资料选择。内部lookup按真实issuer/subject/Task/REQUEST/requestId读取原生requestSha，复用receipt/event/package最小闭包；不存在返回未找到，绝不调用创建或换键。到期/null正文沿空Source计划只核现时READ/WRITE、scope和audit，不解码manifest。正文可用时仅在内部读取已存manifest，重建原expectedTaskVersion、comparison/report与材料字段坐标、reason/purpose，重新按同一规范摘要算法核原生requestSha，再复用排序锁与历史引用认证。规划和操作各复读一次；缺失/漂移/权限失效拒绝，无新包或期限续写。这是现有请求组装出口的按键查回适配，不开放HTTP或初始化scope；接口及工作台仍需下一装配D。

物理落点补充（同任务派生D，2026-10-13）：V111在con6_human_package新增可空body_access_until，写入时为min(body_until、taskWriteUntil、sourceUseUntil)，与manifest公式复核；未有此依据的旧行保持NULL，不自动从body_until回填。原键先读原生回执/请求事件/包最小列，body_access_until为空或已到期时只核最小审计闭包，不解析manifest_json；有期限且仍有效才允许正文认证。该列不延长保存或访问权，不替代现时Source/Grant/scope。U111仅反向迁移源码，实际执行仍受删除/恢复准入约束。此为到期正文不再解析而原键可合法对账的必要索引，不新增业务状态；完整请求任务已有R/D涵盖该持久化差异，测试须覆盖NULL旧行、截止恰到、索引串改和过期无效JSON不触发正文解码。

PC-CON-HUMAN-REQUEST-ASSEMBLY-01沿普通D1/V1～V3实施；本节为Codex派生技术决定，不新增业务事实或运行许可。必要差异已从真实源码核实：ProductHumanRequestStore.Manifest仍使用List<String>；replay调用read并强制正文有效，无法返回正文到期而回执仍合法的最小结果；executeRequest末次只复核Task/身份/Plan，尚未包含Goal/Input/report与清理scope见证。目标为同一原Task一次真实请求四表原子提交及原键对账，允许无比较报告；API/工作台、领取/意见、实际初始化/部署/业务验收是后续出口。

1. 输入与结构：命令只收预期Task版、可选确切comparisonId、指定reportId集合、普通资料Selection坐标、理由及ORDINARY_ASSISTANCE。报告选择必须带comparisonId，无报告不取latest或制造失败；新请求显式无比较Input保留真实缺项。键摘要由规范化命令、Task、真实issuer/subject和原键导出，不能由客户端提供。结构化manifest分别保存TaskFacts的Goal/消息/SearchInput引用、普通SourceDocument引用、ComparisonInput与Report引用及缺项；每类包含原生ID/版本/SHA和该类必要归属、政策及硬截止。不得将对象序列化成字符串当有类型引用。领域Packet所需List<String>只作为这些已认证结构的稳定坐标投影，不能成为许可或原件依据；manifest仍不复制原文、矩阵行正文、token或历史Grant。严格解析及全字段摘要沿ProductComparisonJson，未知结构拒绝，不自动重写历史包。
2. 新请求闭包：在同一executeRequest内先读取真实TaskFacts。选定comparisonId必须等于InputStore.current(task)，InputStore.read核原生输入/候选/事件；Preparation的Prepare.goalId/goalRevision/inputId/expectedTaskVersion及Input.taskVersion必须匹配该TaskFacts，不能只校Task ID。复用InputApplication.read及其材料/特征/期限复核，不绕过当前权利。报告通过MatrixReader.report和MatrixStore原生行/引用/attempt/lease/invocation/receipt闭包认证，校comparisonId/inputSha/candidateId/resultVersion及原始reportSha；祖先报告必须走已有ParentEvidenceReader确切链，以历史引用归属记录，不能标为当前COMMITTED。planner从真实Preparation的target/candidates导出比较资料的必要CLAIMS SourceUse，与显式普通Selection合并后一次排序锁，持后序锁时发现未规划引用整笔拒绝，不补锁。
3. 保存见证：原Task/Goal/消息/SearchInput沿真实ProductRetentionPolicy核配置来源/版/SHA/截止；项目自有新增引用与理由沿V3，第三方资料仅接受已有确切字段SAVE规则。SourceAvailability.useUntil逐字段分别核DISPLAY、必要COMPARISON及SAVE截止；SourceReference.validUntil包含Grant/scope访问截止，不能把它等同来源保存权。Bounds分别绑定写/使用/保存/审计/备份依据，取原Task/Source更短期限，不把180/365/30日续给来源，不默认无限。无法证明类别覆盖即拒写。Task级清理scope必须事先存在且主/独立READY、同组织/Task/政策/代次/事件SHA、无intent且未到期；请求不调用initialize，不推进清理代次。
4. 原键路径：在新命令预期版或当前选择校验前，从同Task/真实请求人的REQUEST回执定位原结果，异载荷摘要冲突。定位只在本笔绑定主事务内部消费最小坐标，尚未授权时不输出。仍可读正文时只认证原包保存的确切坐标、现时许可和scope，不能用新Goal/选择重建原包。正文到期只允许现时真实身份和原Task现时READ/WRITE下的原请求人取得仍在固定auditUntil内的最小REQUEST回执：包ID/版、manifestSHA、请求事件序号/时间、原键摘要和原结果类型，无理由、事实、Source定位、意见或token。该结果从原生receipt及REQUESTED事件交叉核Task/主体/摘要/时间/期限，不解析或恢复过期manifest；缺行、摘要冲突、未知scope或审计恰到均拒绝。不能把回执中的初始请求事实当现时WAITING，更不能重置处理投影。审计到期删除后旧Task已超允许写期，禁止当新键继续写；不留永久墓碑。
5. 提交与返回：复用现有四表save，不改变请求≠受理。完整事实、所选Input/report及引用集合、各policy/截止、实际原字节与scope在保存前后重读，纳入Task事务末次见证回调；最后一遍来源原件消费之后仍核Grant/Source独立水位及各Deadline，任何变化在主事务提交前抛错并回滚四表。若提交已成功但HTTP响应身份/期限失效，只拒绝暴露结果，保留原键供合法对账，不能声称提交回滚。请求内部不调模型/外部通知/维护适配；只有完成当前出口及标准装配D后才注册产品Bean/API。

上述细D的反例落点明确：真实普通无报告提交；确切Input/report归属与原生SHA闭合；普通仅DISPLAY/SAVE允许而比较缺COMPARISON拒绝；所选Goal/SearchInput/报告串Task拒绝；旧键在新版出现后仍只回原结果、异载荷冲突；正文过期仅最小回执、审计恰到拒绝；原件/引用集合/保存截止或独立scope末次漂移四表全回滚；清理scope缺失/未知或混用主事务拒绝。受控H2/原文件/签名JWT的组件验证不代实际授权或DM8。窄D由Codex按现有最终合同和源码两遍反查通过，无新增待答业务问题；实现与上述反例尚未执行，不冒产品能力。普通组装源码必须同时实现完整闭包，不先开放无守卫API。

原键解析顺序补充（同一任务，Codex技术派生）：planner消费历史manifest之前，先只读核现时Task READ和WRITE，不提前获取epoch锁、不改变后续完整排序锁和末次核验。Store行映射器在读取/解析manifest_json前再核原生body_access_until非空且严格晚于现时，覆盖查询途中恰到边界；解析后仍核manifest期限公式和原生列一致。以上落实既定当前权限与到期不解析合同，无新增业务范围。

### 10.9 普通请求标准HTTP装配D（2026-10-13）

PC-CON-HUMAN-REQUEST-HTTP-01为原人工父步骤的标准适配，依赖§10.8请求组装源码/C出口；CON-06/CON-12普通R/D1沿用，不启用领取、意见、专业或外部渠道。Codex根据既有Controller、安全链及构造绑定两遍反查通过本技术D。必要差异是现有内部应用无正式入口，复用全部请求事实/锁/来源/存储，不造第二套服务。退出为真实Spring安全过滤链、签名JWT及真实主/独立H2下允许和拒绝HTTP闭包；不替代DM8、工作台或真实人员验收。

1. 接口固定POST `/v2/consultation/tasks/{taskId}/human-requests`，严格JSON解码同Command，Idempotency-Key必填；GET同路径仅requestId查询，未知404零写。路由明确加入identitySelf JWT认证链，TLS运输门复用，不落Worker或维护链。请求失败不能自动换键，POST成功只说明请求已记录，不表示受理。
2. 返回专用DTO：receipt包含taskId/packageId/packageRevision/manifestSha/eventSequence/requestSha/createdAt/auditUntil，省略内部actor/scope/水位；可选body包含taskVersion/reason/purpose、原Goal/消息/Input引用、所选普通材料的source/document/version/fields坐标、comparisonId/原reportId列表、真实gaps及bodyAccessUntil。只表示REQUEST_RECORD或RECEIPT_ONLY，不输出当前WAITING或已受理，不直接序列化manifest或来源正文。旧版正文只能按原坐标认证；到期body为空且不输出理由/Source定位。
3. POST提交后用原键lookup重新核当前身份/授权/来源/期限再返回DTO，失效拒显不声称已提交回滚。GET复用同完整lookup。非法JSON/参数400；JWT失败401；未知原键404；原键异载荷以专用异常映射409（不根据异常文本分类）；证据/权限/存储不可核验统一503且不暴露异常文本。客户端将503视为结果未知，保留原键只读对账，不自动新写。
4. 功能默认关闭：product.consultation.human-request.enabled；启用必须同时具备product.identity.enabled、product.source.engine.enabled和product.consultation.comparison.enabled，后者提供现有比较只读组件，不要求模型执行开关。明确错误缺前置，不静默注册半套Bean。配置内部一次建立同Clock的TaskTransaction/Locks/Facts/Store/Cleanup/Application，沿既有构造实现，不修改旧事务Bean或混用池；ComparisonReferences复用InputApplication/MatrixReader/ParentEvidenceReader及主库。
5. 政策读取既有product.consultation.retention.*、goal-retention.*；人工新增product.consultation.human-request.retention.ref/version/evidence-sha256明确绑定V3，缺失拒启动，180/365/30日不参数化放大。配置不填测试占位值、不自动初始化scope；Cleanup的InitializationAdmission在此入口始终拒绝。启动只读探测V109～V111四表/清理表与独立V006所需列，零写，不自动迁移。实际政策原件/包Grant/来源启用仍按运行准入另核。
6. 验证包含匿名/错误JWT/非TLS零写、真实POST/同键重放/GET未知和旧版原坐标、坏参数/异键负例、到期最小回执、缺scope/政策/schema拒绝、提交后撤权响应拒显但原数据保留。受控H2维护初始化和替身比较边界须明示；不得将替身装配当完整生产Spring或DM8证据。每文件300行限制，复用现有测试fixture与Spring/MockMvc安全链，不另造测试平台。

### 10.10 普通请求工作台装配D（2026-10-13）

PC-CON-HUMAN-REQUEST-WORKBENCH-01依赖标准HTTP源码/C出口。沿§7.5/§10.9普通合同，复用ConsultationProductPage、AntD样式、现有request/epoch、已核Selection/ComparisonInput/MatrixReport；不接旧V2模拟人工入口。Codex两遍源码反查确认以下技术D，不新增业务决定。UI/UX技能仅用于标签、异步反馈及明确恢复动作，不另建视觉系统。

1. 当前任务内增加普通辅助请求面板，说明请求记录不代表已领取、不具有专业复核或正式法律决定效力。理由必填且<=4000字符；预览当前已确认Task版/目标、显式选择的已有保存文献与字段、可选已核当前ComparisonInput和已核确切报告。材料坐标从真实Selection/Workspace导出，不让用户填写JSON/摘要/授权；字段来自当前Source声明，SAVE和COMPARE仍由服务器核。无比较/报告允许并显示实际缺项。MatrixResults只在已有报告通过bindMatrixReport后提供“选择此矩阵用于人工请求”，输出最小comparisonId/inputSha/reportId/expiresAt；切Task/输入/失权/清空使选择失效，不输出来源正文副本。
2. 协议helper使用严格DTO字段/类型/枚举/Task绑定、原键requestSha与合法期限检查。复用matrixDigest的规范UTF8 SHA，对规范命令（材料/fields/reportIds按Java同序UTF16词典排序）和真实token解析的issuer/subject/HUMAN、taskId/requestId计算与后端同一摘要；token解析只作本地分区/绑定，权限仍由服务器判。正文重建原命令再次核摘要，回执仅接受本地已保存的原requestSha。校验失败不显示正文或清除未知原请求，过期正文立即清屏，审计恰到不呈回执。
3. 浏览器恢复复用现有Agent的sessionStorage模式但独立最小普通请求键：按issuer/subject/Task分区，不按当前Goal/Task业务版分区，避免新版丢原键。只保存原key/requestSha及主体/Task绑定、首次记录时间和固定到期；不存token、理由、资料/报告坐标或正文。固定到期为min(原Task截止、首次记录+180日)，沿V3项目自有包引用类别取更短期限；读取/同键对账不续期，失效或到期移除。保存失败、未知/损坏记录失败关闭，不先发POST再补恢复记录。服务器仍逐次核权限和审计期限，本地记录不是许可。只有明确读取成功并核绑定后，用户才可显式开始另一请求；旧请求没有撤回或取消含义。
4. 每次显式新提交先生成并持久保存key/SHA，再发送唯一POST。超时/网络/无法解析成功/503/409等结果未知保留原key，只提供GET查回，禁止自动换键或后台重发POST。刷新/重启/新Goal后自动或显式GET同Task/原key恢复，未知404不创建也不证明原POST永久未执行；保留原键供继续查回，不让404自动解锁新写。400输入确定拒绝可以修正后显式新提交；401/403清除正文/未提交理由，原最小恢复线索仍按期限保留且只供同主体重新登录后GET。用户退出不是服务端撤回。内存原提交正文不持久化，恢复不需重建POST。
5. 所有异步步骤按token/Task/Task版本及组件epoch绑定，切换或卸载后迟到响应不回写；当前Goal改变会撤销新请求选择，但原键历史读取只展示原版本，不改归属。面板busy/未知恢复阶段锁定其他任务写入；查回按钮不得被自己的锁禁用。退出登录始终可以清本地身份/正文；成功旧请求显示“请求已记录，未证明受理”，到期仅“仅保留请求回执”，不凭REQUESTED事件渲染当前WAITING。请求理由和反馈有可见标签/role alert/status与键盘可用按钮，复用响应式样式。
6. 组件反例覆盖成功/网络未知/503/格式错/原键摘要或Task不符、存储失败零POST、刷新仅GET/新版原键、异主体隔离、截止与失权清屏、切换迟到、资料与可选报告确切绑定，以及现有页面/矩阵受影响回归和咨询构建。浏览器真实运行仍需运行准入，不能以mock HTTP组件冒实际人/资料/DM8验收。技术出口后立即回父步骤准备当前包/配置/实际scope初始化及失败恢复准入，不重问V1～V3。


§10.10编码与到期补充（Codex技术派生，2026-10-13）：实际JDK21/Jackson2.17.2向量证实其canonical的控制字符使用大写hex，不能直接用JSON.stringify摘要。普通请求独立采用同UTF16对象键/资料顺序及Java同控制转义，字面反斜杠文本不重写、合法代理对原样UTF8；不改既有矩阵摘要合同。孤立UTF16代理在Java getBytes会有损替换为问号，故普通Command的reason及所选字符串坐标必须在解码/构造时拒绝（HTTP400、零请求写），前端在存key/POST前同样拒绝且可更正；不改变其他业务输入或全局canonical。此为确切原键摘要完整性的必要修复，沿HTTP/工作台既有R/D，不新增业务决定。独立FIX-CON-HUMAN-REQUEST-UNICODE-01只解除本工作台的跨端绑定阻塞，完成立即回本任务。验例包括Java实测SHA、实际控制字符vs字面转义、合法emoji、孤立高/低代理在理由/材料/报告坐标拒绝以及已有HTTP/组装受影响回归。

工作台保存原键/read-back成功后立即从组件state清除已提交理由、资料和输入/报告选择；本次POST仅消费已捕获命令。180日原键先于长Task到期时同样清除，避免移除恢复记录后重新显示旧草稿。SHA/输入核验失败与存储故障分开：前者零POST/零恢复记录且可修正；后者继续失败关闭；截止恰到拒绝而不误称存储损坏。未知结果原键GET合同不变。


### 10.11 受管初始化的新生见证与持续许可（2026-10-13）

PC-CON-HUMAN-INITIALIZATION-WITNESS-01为普通父步骤运行准备的第一必要差异，复用正常ProductTaskCreationService与已有ProductHumanCleanupScope，不另造Task创建/人工业务流程。只读两遍已确认：创建服务的前置与事务内两条receipt重放不区分新建；主事务afterCommit仍可能早于独立watermark READY；Cleanup初始化仅一次admission不足以覆盖运行许可随后失效。Codex派生技术D在既有普通R/D1范围通过；实际人员/目标/维护许可不由本节批准。本次源码出口只提供可信新生观察与各写入阶段许可守卫，不把它当受管命令入口或实际scope已就绪。

1. 正常public create的输入/输出/幂等/当前组织CREATE授权不变。增加仅包内维护适配使用的observed调用，必须无外围活动事务且提供非空observer。实际directory.insert分支在原Task/event/READ Grant/creation receipt事务中形成不可变创建事实；只有整个watermark.change成功返回、主事务完成且独立READY核验成功后才交observer。两条旧receipt路径均不产生事实，异常/独立PENDING/主回滚不交付；不能只凭HTTP成功、数据库空表、旧JSON或旧receipt补发。事实绑定原创建requestId/SHA、Task/组织/真实issuer/subject/数据scope/原Task截止及保留依据/Grant，不包含token/私钥/正文。
2. observer的现时维护许可检查与新生通知分离。维护模式在进入、实际新建前、主事务返回前及独立READY后的交付前核同一现时许可；任何失败拒绝继续，已提交但后续失败不得声称回滚。维护许可由后续受管适配提供真实run/目标/lease/操作范围/期限，普通create不因此获得维护权限。后续受管适配只能把本次通知转为同进程/同lease的一次性内存见证，首次initialize尝试消费；重启/旧记录不能重建它，本节不实现重试或恢复旁路。
3. Cleanup的InitializationAdmission保留一次新Task无历史认证，另新增可重复的requireCurrent(now)，缺实现默认拒绝。在独立PENDING前、主事务提交前、独立READY CAS前及返回核验前调用持续许可；不重调已消费的无历史认证。期限与原Task有效性继续取原有有限边界，任何不确定保持关闭；主提交失败保留独立PENDING，主已提交后许可失效也保持PENDING，不自动READY/重试/删除。产品HTTP配置的初始化拒绝回调保持关闭。
4. 工程正反例：真实正常创建新分支在主与独立已完成时交唯一事实、两条重放不发、外层事务拒绝、主回滚/独立失败/维护许可恰到无有效事实；Cleanup在PENDING前失权零写、主提交前失权主回滚且独立PENDING、READY前失权主已提交但独立PENDING、正常闭包仍可current复核。现有请求HTTP与应用fixture只能显式声明受控测试维护许可，不成为生产默认值。源码/C出口完成后立即回原父步骤的受管运行适配/发行权限与失败恢复，运行人员/真实Task授权/DM8仍另核。

实现边界复核：observed入口限定同一主DataSource的JDBC事务管理器和REQUIRED传播；两种入口都拒绝主/独立DataSource已绑定的外围资源，覆盖SYNCHRONIZATION_NEVER未设置同步标志的实际事务。Cleanup独立READY CAS、current核验及最后持续许可检查处于同一独立短事务，检查抛错回滚本次READY转移至既有PENDING；不能通过事后反写或删除补偿伪造回滚。主库已经提交的事实仍保留。相关真实两库H2反例由root统一执行，生产HTTP初始化拒绝不变。

### 10.12 同进程一次性受管初始化编排（2026-10-13）

PC-CON-HUMAN-INITIALIZATION-ORCHESTRATION-01只实现受管运行适配的必要内核，依赖§10.11新生见证/持续许可源码组件出口，归同一普通人工父交付。复用createObserved、正式JwtDecoder、CleanupScope及原事务，不增加业务HTTP入口、不把注入测试lease当实际维护批准。Codex派生技术D在既有普通R/D1范围通过；后继操作专用fence、真实人员/目标/部署许可仍另核。

1. 包内编排实例只有一次执行机会，进入执行即消费，不论参数错、旧回执、主事务失败、初始化失败或结果未知均不能在该实例重试。每次实例私有Witness同时实现CreationObserver和InitializationAdmission，每次专属Scope以该Witness构造，不替换共享组件的admission。Witness不对外提供反序列化、导入或set方法；finally清事实引用并关闭实例。
2. 唯一顺序是非空持续维护lease现时核验→正式decoder验证原token→正常createObserved→仅本次created通知给出的事实→消费见证→scope.initialize。两条旧receipt路径即使create结果成功也拒绝初始化。真实维护主体与Task主体分别绑定，不能相互替代。创建服务仍核当前组织CREATE/身份，编排不新增Grant或代用户确认Goal。
3. 显式CurrentLease端口无默认放行实现；固定Binding包括runId、leaseId、目标/主独立库标识、维护主体、批准动作、批准依据SHA与截止。每次requireCurrent将同一不可变Binding和现时时间交真实适配复核，并在回调后重新取Clock拒截止；以后适配必须验证这些标识确实对应注入DataSource和现时排他控制，字符串本身不构成授权。固定动作为本次新Task及人工scope初始化，不借用SUBJECTS/GRANTS/KEYS/SOURCES旧权限。无真实适配时产品路径保持关闭。
4. 私有Witness核完整创建事实与本次输入/输出：requestId、Task/组织、真实issuer/subject、dataScope、Task及授权版本、截止和保留依据、READ Grant；SHA使用正常服务产生的事实/原回执合同，不另造业务摘要。初始化固定人工Policy、operator、auditUntil/backupUntil/validUntil；第一次initialize尝试之前即消费事实，内部一次无历史认证再核Task/组织/Policy/operator及同lease。不得用空表、外来fact、原创建JSON或旧journal补生。
5. 持续许可委托贯穿create和initialize所有既有检查。成功只在scope完整返回后形成Task结果与scope结果；异常向上保留，不能声称主Task已回滚或自动重试READY、删除、清旧史。编排不导出token、正文、私钥或可重放见证；未知结果由后继受管回执准确报告。Task和scope既有有限期限保持，lease额外更短截止优先。
6. 工程出口需真实两库H2/正常创建与scope链：首个新建仅一次成功、旧回执拒绝且不初始化、同实例重复执行拒绝、decoder拒绝零写、同lease失权/恰到、初始化参数失败后不可复用、主提交后失败保留真实状态、主体/政策坐标不符拒绝及普通HTTP仍拒绝初始化。适配端口可受控替身，明确不是DM8/实际维护准入/CLI/部署实证。退出即回操作专用fence/装配、发行最小权限及保护恢复准备。

事务终点说明：Scope独立事务内失权回滚READY转移为PENDING；如果Scope已完整提交返回、编排最后许可复核才失效，两库READY事实已经提交，编排只拒绝成功返回并关闭实例，不声称数据库回滚、不反写PENDING。分别以独立连接观察已提交状态的反例取证。编排构造还核创建服务与Scope的主/独立DataSource同实例，不由Binding字符串代替实际装配绑定。


### 10.13 正常新Task的人工范围初始化（2026-10-13）

ADR-CON-HUMAN-NATIVE-CREATE-20261013，技术责任Codex。两遍源码复核确认§10.7/10.11/10.12的maintenance-only属于派生技术选择，不是docs02 V1～V3业务限制。当前父步骤需要正常用户新建后可请求人工，逐次全库离线fence不是必要业务依赖。本节在既有普通R/D内替代“仅维护模式”的同范围约束；旧维护内核不作为生产入口，原scope/read/request失败关闭不放宽。PC-CON-HUMAN-NATIVE-CREATE-01的限定D通过，实际部署/真实身份/资料及运行验收另核。

1. 正式POST /v2/consultation/tasks沿原Input/Result/Idempotency-Key；human-request.enabled时显式装配正常创建适配器，缺bean或schema不能静默退回。服务器选既有人工policy；客户端无维护许可或任意scope截止字段。正式decoder复核原token并要求当前真实HUMAN、有效key/client/session和当前组织CREATE/TASK_WORK；不凭主体字符串授权。Task写前核其期限不超过365日及原Task政策；scope validUntil/auditUntil固定Task expiry，backup上界取min(Task expiry,now+30日)，不声明已经备份，不延原资料权。审计真实创建主体，不虚称维护人员。
2. 复用createObserved的真实insert通知和私有一次性见证，每个正常调用私有实例；主Task及独立Grant READY后才消费本次fact并初始化。严格核Task/创建命令/原生receipt/TASK_CREATED/READ Grant及政策。两条重放保持原Task成功合同且绝不初始化；旧Task/JSON/重启不可补生。Task结果不增加human-ready字段。scope失败用既有503，Task可能已提交；重放返回原Task不表示人工就绪。所有异常保留真实PENDING/READY状态，不补偿删除/伪回滚。
3. 正常CREATE的自有Grant PENDING须有不可伪造包内OwnedChange证明：只在独立CAS成功后私有构造，绑定watermark实例、issuer、generation、intent、原historySha、线程与活跃回调；任何坐标变化/其他watermark/线程/回调结束拒绝。public change复用同一协议；自有合法主事务写后不以旧historySha要求主历史不变。observer事务内阶段另调requireOwnedCurrent，默认仍委托原持续守卫，现有维护测试合同不变。生产owned CREATE仅允许绑定主事务、组织CREATE/TASK_WORK，调用相同授权SQL/领域decision，不新增ignorePending开关，不放行他人PENDING。普通permits仍强制READY。
4. 锁与现时见证：新建事务在组织锁后按已有key_control→kid→client→HUMAN subject→sid/jti→精确CREATE epoch顺序锁定；原生身份实现尽量提取复用，现有撤销以同epoch互斥。核真实验签Jwt的sid/jti/kid/issuer/subject及JWT/session/key/Grant截止、身份状态、Grant代次与Task expiry覆盖。事务内两次比较不可变现时见证；入口和独立READY后使用完整decoder及正常授权。Scope持续回调同样核正式当前身份/组织CREATE及各截止；持后序Task锁时不得再追加前序身份锁造成倒序，采用现时读取/水位前后核验并保留末次拒绝语义。完整scope提交后的失权仅拒响应，不篡改已提交事实。
5. 生产对象固定DataSource与创建/水位/Scope实例绑定。不得在同一JVM热切恢复目标后继续消费旧birth；运行恢复必须停止原服务/JOB等全部writer并销毁旧进程，仅全新隔离目标启动，沿docs69§5，不开放动态重绑定或持久化见证。恢复保护必须覆盖真实WAITING请求/原生回执、当前来源许可与有限正文，不从空scope测试推实际保护已完成。
6. 出口：正式正常新建→scope→原结果、两类replay无补生、HUMAN/会话/密钥/client/CREATE撤销及恰到、Task期限不兼容写前拒绝、自己PENDING可核/他人PENDING拒绝、跨实例线程和关闭proof拒绝、scope各失败真实双库状态、现有维护内核及请求回归；正式controller分流与开关/缺配置失败关闭。仅组件替代DB/身份不冒真实运行。退出立即回父步骤发行清单、最小权限与实际WAITING保护闭包，不继续扩离线初始化CLI。

§10.13现时授权补充：Scope阶段必须保留本次新建READ Grant当前性，不能只在每阶段重新核组织CREATE而接受READ已撤的新版授权。正常适配在created通知完成时固定Grant generation，后续初始化各回调及成功响应前assertUnchanged该generation并核当前身份/CREATE；任何授权变化均拒绝继续，允许保守拒绝无关变更，不自动重试或补scope。Task行锁后只读复核，不追加前序身份锁。

固定新生Grant generation之前还须核fact所指原READ Grant：ACTIVE、current_epoch确切current_grant_id/authorization_epoch与Grant generation一致、主体/组织/Task/resource/dataScope/version与fact严格相同及截止有效；独立水位在这次查询前后不变。不能在新READ已撤后固定新版generation，从而漏掉通知前的失权窗口。

§10.13生产时钟精度补充：真实Clock.systemUTC配置反例暴露JSON纳秒与默认TIMESTAMP精度不同，初始化自校验拒绝。新生成scope事件时间统一先取毫秒精度再作JSON/SHA及双库TIMESTAMP，不截断实际授权/到期检查时钟。传入Task expiry和scope/audit/backup截止必须精确到毫秒，超精度在写前400/参数拒绝，绝不由数据库隐式四舍五入延长期限。正常创建服务器backup上界向下取毫秒；原Task/其他入口不作全局精度改写。此为本Task真实配置接线的必要修复，真实Clock反例及精确非毫秒拒绝需通过。


### 10.14 普通人工请求发行元数据（2026-10-13）

PC-CON-HUMAN-RELEASE-METADATA-01由Codex负责，依赖§10.13源码/C完成，仅关闭当前父步骤的迁移/最小权限/配置清单缺口。已有R/D及实际SQL两遍反查支持本限定D通过；不执行迁移、DCL、删除、部署或默认启用，不替代实际WAITING保护恢复/到期清理准入。

1. 只登记CONPROD的V109四请求表、V110两scope表、V111正文访问deadline列，以及CONAUTH的V006独立scope表。沿现行实例序列V106→V109→V110→V111与CONAUTH V004→V006；逐原字节SHA、表/全部列/命名约束前后检查与FK引用存在检查，七表加入knownObjects/installedTables/finalChecks。复用extractSqlFacts与历史生成器列解析进行增量合并，不运行历史全量生成器覆盖当前profile；无名约束不虚构名称。
2. 四SQL均CREATE/ALTER ADD，无DML/破坏句例外，forward-originals保持不动。source-review仅增加四项仅咨询绑定；现有三域对象/迁移/权限不退删或扩到其他域。全部元数据相互SHA必须一并同步。
3. CONPROD_SVC对六新表仅SELECT/INSERT；CONAUTH_SVC对ID6_HUMAN_CLEANUP_SCOPE仅SELECT/INSERT/UPDATE。CONPROD.CON3_TASK_CREATE_RECEIPT补SELECT，用正常创建私有见证的原生回执查询作证。CONAUTH_BAK对新独立表的SELECT沿已有allInstalledAuthorityTables派生，无回灌权限。其余既有对象权限沿当前清单，不为未来领取/意见/清理添加UPDATE/DELETE，不增加系统权限、跨schema授权或角色。
4. 仅咨询配置清单增加product.consultation.human-request.enabled及retention.ref/version/evidence-sha256。模板开关默认false，政策引用在开关true时必填且仅引用批准V3/实际材料；不填工程policy/测试身份占位，不用模板存在推断已经启用。真实部署必须进一步绑定现时配置、政策原件、发行包、对象状态、最小权限、保护恢复及到期首次清理能力。
5. 出口复用现有安装/源目录/权限/恢复准备检查，并补确切正反：编译只咨询7表/1旧SELECT、无DELETE/额外UPDATE/跨域扩权、备份SELECT覆盖新独立表、缺迁移/列/权限或篡改SQL SHA拒绝、开关off不要求人工政策、on缺真实输入仍不能自动启用；结构和规定完整总检据实。完成即回父WAITING保护与有限清理执行接线，不把离线manifest当实际运行准入。

### 10.15 保留源的普通WAITING恢复核验（2026-10-13）

PC-CON-HUMAN-WAITING-RECOVERY-01为父普通请求保护步骤的必要解阻项，由Codex负责；依赖§10.14离线发行出口，原CON-06/12 R及V1～V3业务决定不变。两遍源码审查确认现ProductTaskRecoveryVerifier不含人工七表；本限定技术D通过，复用现时源权限和纯原生校验，只实现source-only源码/C，不执行物理备份、切库、删除或启用。

1. 输入显式绑定主/独立源DataSource、当前issuer、human与Task/Goal/Message已批准政策、原件根及有限backupUntil。固定只读入口由ProductTaskRecoveryCheck显式source-only模式接入；旧isolated-target模式不自动冒本证明。结果仅数量、闭包摘要、最短截止及businessActivation=false，无正文/凭据，无授权授予；物理使用仍须本批现时维护scope与连续停写。
2. 全七表先读标量元数据：四请求表、主Scope/事件及独立Scope。任何NULL/恰到/逾期正文访问、body/audit/valid截止或Task截止、非法时间关系先拒绝，再解析manifest/event JSON。两侧Scope集合严格相等，逐Task复用current核READY/无intent、generation/policy/history/唯一INITIALIZED事件及Task归属；合法新Task无人工包仍核scope且可通过，不初始化或补生缺失记录。不得过滤过期对象后冒完整备份。
3. 从四表全集按包ID/版双向覆盖，核唯一WAITING/version1、REQUESTED/seq1/0→1、唯一原REQUEST回执；manifest规范SHA与所有原生Task/org/dataScope/version/requester/时间一致。真实历史actor须HUMAN且与主体事实绑定，不要求历史人的当前会话或Grant ACTIVE。提取现在线resolve/key的纯command重建/摘要helper共享；以原requestId与manifest重建requestSha，同时比event/receipt，禁止伪造Context/JWT走在线权限方法。
4. 原Task及历史Goal/Message/SearchInput引用按原生版本/回执/摘要/内容/时间与获准各政策核验；旧包可保留旧Task业务版本，不依赖当前Goal指针。材料复用已核原文件与字段locator。可选Comparison/report/祖先链复用input/matrix原生Store和纯闭包，逐原对象截止先正文、SHA/回执/候选/祖先坐标一致；不重新外呼模型、不改变专业规则。
5. 来源用途集合从所选材料与比较/报告原件推导，再与manifest pins精确比对，不能信自报集合完整。被引用Source须现时ACTIVE、主独立水位一致、Task source scope坐标有效；逐字段复用requireFields/useUntil核DISPLAY/SAVE及必要COMPARISON，原文件版/SHA/locator不漂移。全库Source沿assertAllCurrent语义，合法无关WITHDRAWN不判损坏；全库Grant历史/水位仍须一致。已撤回或更短上界不沿用旧Rights，实际业务重开仍正式JWT重新授权。
6. backupUntil必须now<截止<=now+30日且不晚于全部仍存正文/审计/scope事件及原资料保存边界；复用OrdinaryHumanRetention计算并在末尾再核时钟、Task/Source/Scope事实及摘要未变。源侧摘要覆盖完整四表、scope和核验引用事实；空库可合法通过但不得冒真实WAITING证明。
7. 组件出口覆盖真实SQL四表正向/资料/合法空scope、缺receipt/handling、孤儿、状态/actor/摘要/时间篡改、过期先JSON、Source撤回/少pin、Scope独立PENDING/漂移及末次失效；历史人后来撤权可保留完整历史但不因此获得正文访问。首轮失败保留，测试替代DB不冒DM8/真实维护。退出立即回父，同次完整writer停写窗口中源前后核验绑定snapshot/inventory，隔离目标只做全量typed行/CLOB/NULL/时间等值，最终返源重核；独立源遗失后的旧包灾备不在此证明范围。

§10.15原Goal摘要范围补充：Goal原requestSha计算的是原Input JSON，持久filters已大小写/空白/排序/null规范化，故一般不能从规范行逆推出原请求字节。恢复不猜原格式、不改历史SHA或Goal写入；核原Goal/Input/GOAL_CONFIRMED回执摘要互证及原生字段/政策/时间关系，将requested_fields/features/filters等全部实际原生内容指纹加入本次源侧闭包摘要。父同轮typed inventory前后/隔离/返源等值补实际存储字节保障；该限制不适用于人工REQUEST命令，其保存坐标足够精确重建。

源侧命令使用ProductTaskRecoveryCheck --source-waiting；连接、issuer、原件根、政策与截止全部来自独立PRODUCT_HUMAN_SOURCE_前缀，缺项/未知参数拒绝，不回落旧target输入。policy原件SHA前后核验；输出SOURCE_ONLY_WAITING、businessActivation=false、sourceAndFenceBindingProven=false，供父runner在实际源身份/连续停写批准核实后绑定，不能单独当物理准入。

§10.15末次现时期限补充：当前Task-source scope与DISPLAY/COMPARISON等使用许可可能早于SAVE保存界限到期。References将本次现时使用的最短截止作为独立executionUntil传递至Verifier Summary与source CLI；两遍摘要比对后、CLI所有政策/水位末检后仍须clock<executionUntil。它只约束本次核验执行，不能收窄或延长原保存Bounds，也不能代替backupUntil。反例在末次scope/原件核验后跨过此截止，必须拒绝成功摘要。

§10.15比较原生期限闭包补充：依照真实InputApplication.create的min(feature/preparation/materials)生成合同，恢复显式核每个inputUntil不晚于其feature/preparation/每prepared document和目标message原生截止；不从正常创建推断恢复行必然一致。nearest祖先选择读取到的全部报告（含未选报告）及各原input另汇总referencedSaveUntil，要求本次backupUntil不越该界，并纳入末次executionUntil。它不改所选Comparison历史expiresAt公式，也不要求历史manifest保存Bounds小于本次为祖先判定额外读取报告的期限；只约束本次完整核验和封包。

### 10.16 WAITING源核验与原生认证封包绑定（2026-10-13）

PC-CON-HUMAN-WAITING-SEAL-01为同父保护步骤的必要增量，依赖§10.15源码/C及遗漏检查收口；CON-06/12 R与V1～V3不变。Codex两遍核现snapshot CLI/lease/export/seal/openSnapshot与Java入口：原流程缺人工摘要、保存截止及原件完整绑定，本限定技术D通过。仅源码/C，不执行物理封包、迁移、切库、删除或业务启用。

1. 在现snapshot CLI显式接humanWaiting配置，配置SHA必须绑定现approval；主/独立源schema、账户、issuer及同一shared-instance JDBC与已审批咨询deployment/target严格匹配。复用实际assertLiveOwnedTarget和同一native lease，读取本次lease worker实际绑定/停写proof。固定Java/JAR路径与SHA，无shell，有限进程/输出，密码仅从具名环境取并只传必要子进程环境。Java仍输出source-only两个false；父封包另证明真实source/fence绑定，不篡改其意义。
2. 必须先在lease内跑源核验再读取完整inventory/原生导出，导出与文件读取后再核第二遍；两次语义summary/配置/JAR/原件集合/lease绑定一致，非空packages才称WAITING保护。保留两份原stdout SHA与认证内容，不要求Map序列化外层顺序相同。源CLI epoch秒小数按原文本精确处理，禁止浮点向上延长；时间比较和包期限保守取毫秒，不改原业务保存政策。
3. 受控evidenceRoot的全部regular文件（含三policy）须在现获准files集合内，逐相对ref/SHA核对，根及中间目录拒链接/重定向，有限容量。所有原件树写者及该根的维护写者须有完整责任来源并纳同一已批准writer集合/维护停写范围；数据库锁不自动证明文件不可变。未知写者或缺目录稳定性依据拒绝，不自动复制未获准文件或延权。前后重新枚举/哈希只作停写之外的漂移检测。
4. AES-GCM认证metadata/payload绑定两次源原输出、summary、source target、配置/Java/JAR/政策/原件集合摘要、实际worker/lease证明及同轮完整inventory摘要。expiresAt取现数据库/文件/approval与human backupUntil最短者；executionUntil独立限制本次核验/导出/封包，所有末次检查新读时钟，到期不发布成功回执。openSnapshot重核认证人工内容和期限/库存绑定，不只依赖旁路receipt。
5. 非人工旧包仍使用既有流程；没有该完整绑定的包不得声称WAITING保护通过。本增量不增加目标业务启动；隔离目标继续按完整typed库存/原件等值证明，返源仍需当前源核验先于writer返回。实际非空恢复是首个受控人工流程的I出口，不是human=false候选构建的循环前置；off包发布仍须现有存量保护/回退和实际操作准入。
6. C出口包含同fence先核再inventory/export/后核、非空/漂移/错源或配置/漏原件/原件写者缺项/过期与精度、认证内容篡改、包期限上界、旧包回归与失败释放；真实Java核验由§10.15两库真实政策组件证明，Node外部进程/native端口替身不冒实际DM8。出口立即回父完整隔离比对/返源和清理接线，不新建通用恢复平台。

§10.16源地址工程补充：源核验Java在获准本机路径运行，部署JDBC可能使用Docker内网别名，不能把两者字符串相等冒实际同源。显式human配置可指定唯一IPv4回环JDBC，两主/独立连接必须同值；同lease内用有界docker inspect核实际目标ID/owner/运行态、部署别名所在确切网络及该容器内部端口到127.0.0.1的唯一已存在发布端口，必须与human配置确切相等。前后重核并将稳定映射事实纳认证source内容。不给新网络/端口授权、不自动探测替代地址、不改部署配置、不复制原件；无实际映射立即拒绝。原件树责任证据逐writer保留自己的owner/source，维护writer仍严格绑定现维护独占依据，不强迫全部不同责任方共用同一来源字符串。上述是Codex技术派生决定，业务V1～V3及物理暂停不变。

§10.16库存完整性补充：human封包额外核全部schema的TABLE/BASE TABLE与rows条目一一对应，逐表具有非负count、SORTED_TYPED_COLUMN_ROW_SHA256/v1和合法contentSha256；人工主六/独立一表不可缺，包与scope计数须与源Java摘要相符。该校验复用真实SingleDatabaseLifecycleJdbc既有typed行摘要，不另造数据导出器。打开认证包时重复核，拒绝把仅行数、空库存或漏表包标为WAITING保护。

### 10.17 WAITING认证包隔离恢复及引用等值（2026-10-13）

PC-CON-HUMAN-WAITING-RESTORE-01依赖§10.16源码/C；CON-06/12 R与V1～V3不变。Codex依据现restoreSnapshot/native import/openSnapshot/compareRestoreInventories与独立源码复核通过本窄D；只源码/C，不产生物理恢复、独立authority灾备或业务启用许可。

1. 显式配置humanWaiting={format:HUMAN_WAITING_RESTORE/v1,bindingSha256,sourceConfigurationSha256,scope:SOURCE_PRESERVED_ORDINARY_WAITING}，approval.humanWaitingRestoreConfigurationSha256核确切配置SHA。配置与认证包human metadata须双向存在匹配，缺项/旧包混配/错源配置或binding拒绝。current及末次成功前持续pin本配置/批准，不只入口校验。
2. 保持现独立确切空目标、批准包SHA/密钥、原生导入/未知停止/维护锁及失败现场。人工原件映射的relativePath必须等于认证before.files的ref；目标fileRoot不得等于、位于或包含源evidenceRoot，且复用现防链接/全新目录/文件SHA规则，避免导入后原manifest指针失效或破坏保留源原件全集。
3. 目标导入后复用完整库存与原件核验，在成功日志/回执前另以认证before的summary核assertHumanWaitingInventory：全部表typed摘要及七表/包scope计数。已有catalog profile继续完整typed对象/权限语义比对，无profile沿既有精确库存等值；不把目标伪装source运行Java来借现时独立权威。
4. journal及最终receipt附认证bindingSha、sourceConfigurationSha、原/目标库存SHA、scope及businessActivation=false。只证明此隔离等值，不声明源已经返还可用、不启动writer。保存期核现包expiresAt及当前恢复批准/文件权利，不把旧executionUntil当恢复新授权或保存上界。
5. 组件用真实自有AES包/文件与现原生端口替身，覆盖允许、缺/错/漂移配置、旧包混配、错相对ref/源根侵入、导入后缺表/摘要/数量差异、过期/失败释放和partial场景；保留旧恢复回归。出口立即回父返源先核后writer及有限清理接线，真实非空DM8恢复另I，业务B不变。

§10.17异步漂移复核补充：人工恢复目标fileRoot/files及批准files与配置绑定一并固定；每次current在lease.assertHeld前后都重核初始decision，已开包时同时复核人工原件ref及源根隔离。防末次lease回调自洽换binding，以及native import后改fileRoot向保留源目录写入。两项负向组件必须分别拒最终成功、在restoreFiles调用前拒绝且源树不新增。技术责任Codex，日期2026-10-13，原合同无扩展。

### 10.18 保留源在writer返回前的全量等值核验（2026-10-13）

PC-CON-HUMAN-WAITING-RETURN-01依赖§10.17源码/C，CON-06/12 R及V1～V3不变。依据现native acquireFence/inspector/openSnapshot及旧continuousCase75先writer后核验缺口，Codex独立复核后审定本窄D。只实现源等值核验库与真实CLI，不重跑旧ACTR、不修改历史manifest、不自动恢复writer、不执行物理操作。父控制器随后必须在writer返回前消费本次新鲜核验并另核运行准入，TLS仍在writer返回之后；本子项不声称已完成该父装配。

1. 显式sourceReturn配置格式HUMAN_WAITING_SOURCE_RETURN/v1，含packagePath/packageSha256/bindingSha256、operationEvidencePath/operationEvidenceSha256、restoreTargetId/restoreToolId/ownerTask；approval.sourceReturnConfigurationSha256绑定其canonical。保留当前已批准snapshot原生围栏/Java源核验配置而不执行export；额外返源批准明确只读验证范围，旧snapshot或ACTR批准不能默认当返源批准。全配置/批准固定，所有异步前后重核，原包及父操作证据原字节SHA前后固定。
2. 目标及工具确切ID互异且不等于源或writer；实际docker inspect证每项ID/owner、Running=false、Restarting=false、Pid=0，前后及成功前重核。源actual target/全writer停写/文件责任/维护锁沿现native及inspector。父操作证据可以是失败或未知恢复的receipt/journal，本入口只绑定其原字节，不把它解释为成功；隔离目标成功不是安全返回保留源的硬前置。实际资源预算/host责任与writer放行仍归父本次准入。
3. 认证打开原包，必须具人工绑定且等显式binding SHA。新native lease及全新inspector实例，先inspect(before)，再完整typed inventory及所有包内原件真实读核，后inspect(after)。同次新lease两份semantic一致；与原包仅比不变source/配置/Java/JAR/政策、包scope计数和closure、backupUntil、完整human文件集合，不把新worker或本次executionUntil强等旧值。新worker身份不得等历史worker。库存按原target及全部schema/accounts严格inventoryDigest等原认证库存，补七表typed约束。
4. 全部认证fileDigests必须与当前c.files/approval.files一一对应path/SHA/license；所有包内原件（包括人工根外）均须前后防链接/容量限制真实SHA读取，当前useUntil有效。人工根再由inspector核完整枚举及三政策。package/操作证据路径拒链接，有限读取；CLI新receipt/journal不得落人工保护根内，不覆盖任何源文件/包/操作证据。
5. 每异步后新读时钟核原包expiresAt、当前批准/database/files、human backupUntil及新summary.executionUntil；旧executionUntil仅原导出证据，不限制新验证。release及waitClosed全部成功后仍核配置/原件/场景/时限，journal和receipt落盘/fsync后再核；失败不得留下有效成功回执，不自动重试或启用writer。失败保留实际原成功阶段/未知及安全退出错误，不将release失败冒成功。
6. 输出HUMAN_WAITING_SOURCE_RETURN_RECEIPT/v1，绑定原包/人工binding/父操作证据、source/库存/全部原件、当前两份Java原输出及新worker/lease证明、核验时刻与有限截止；verificationOnly=true、businessActivation=false，不携正文/凭据。C覆盖旧包缺绑定、错源/目标仍运行、非human原件改动/漏件、库存/闭包漂移、新worker与旧相同、过期/末次漂移、release/waitClosed及publish失败。实际DM8返回、writer/TLS、清理与业务验收未由本C替代。

§10.18组件与独立源码复核补充：原生worker的invocationSha256仅由command/args计算，同一源新进程必须允许命令摘要相同，按pid+createdAt识别历史进程复用。inventoryDigest仅含target/schemas/accounts，返源另严格比较顶层restoreCatalog及nativePrivileges并在回执绑定各自SHA，不能用表摘要等值掩盖权限目录漂移。文件打开前后及最后路径身份核dev/ino/size/mtime，CLI最终durable日志包含于publish，由库末次全部原件/配置/期限复核覆盖。上述为Codex技术修正，不新增业务授权。

### 10.19 有限清理执行接线及历史读取窄D（2026-10-13）

ARC-CON-HUMAN-CLEANUP-EXECUTION-D-01返回普通人工父步骤，业务依据为V1～V3，技术责任Codex。三路只读复核实际V109/V110/独立V006、RequestApplication/Store/Witness、CleanupScope及WaitingRecoveryVerifier，确认关读不是物理清理，单INITIALIZED链亦不能承载后续删减。本节不解除物理删除暂停。完整执行器/迁移/调度/恢复D继续收口；以下明确的历史读取增量通过窄D，不据此放行清理writer、Grant或产品启用。

**必要差异与复用**：现四表正文仅manifest_json；body_access_until是使用截止，body_until才是保存截止。已准入包的taskWriteUntil=taskSaveUntil=Task.expiresAt，receiptCompatibility及scope界限使审计期限实际被Task截断，不宣称全部保留365日。正文清除须保留有效追溯依赖骨架；Task终止后的receipt→event/handling→package→cleanup_event→主scope→独立scope顺序不延长任何期限。不能因一个包正文到期删除整个Task或重算其他包。复用现保留域、两库READY/PENDING、真实Task锁序及原生回执，不造通用生命周期平台。

**执行器的最短路线**：选择明确人工动作的持续维护lease，复用现初始化操作的固定目标/真实主体/批准SHA/截止和前后现时核验方式，但初始化、SOURCES或Task WRITE均不能授权清理。实际适配须另外绑定精确scope、动作、选择摘要、政策和本批操作依据；无适配默认拒绝。暂不扩所有SERVICE workload的目的和Grant发行。按原dueAt记录firstAttemptDueAt=dueAt+24h，首次真实执行进入前独立持久PENDING intent及firstAttemptAt；主事务失败不抹掉首次尝试。扫描/计划/缺授权失败不能冒物理清理已尝试。启动补扫、有界分页、失败不饿死其他对象；超24h据实报告，不重置时钟。

**终结边界**：到Task/scope截止后不可调用current或解析过期manifest/event JSON来取得清理资格；只使用获准必要标量、精确目标和独立TERM intent删除残留。不为写新清理事件把audit_until延为now+365，不插违反旧CHECK的伪事件。失败保留不可读待删PENDING而非新合法审计期；重启仅现时准入下对账原intent与实际残留，不补READY/INITIALIZED。最后合法备份过期且原Task不可写后删除独立scope，无永久墓碑。备份轮换及完整CLEARED骨架恢复仍需同父后继接线，不以历史祖先关系允许恢复旧正文。

#### 10.19.1 PC-CON-HUMAN-CLEANUP-HISTORY-01：真实历史读取限定合同

该子项依赖现已源码/C的CleanupScope/WAITING返源，关联CON-06/12；直接消除父清理升代会误拒其他有效包的缺口。本段只读链及在线历史请求见证，没有清理写者、DDL、调度、Bean新增或物理操作。既有第1代INITIALIZED原JSON及event_sha和H1=sha(List.of(initialization))保持原字节与算法。未知新格式一律拒绝。

1. 新固定BODY_CLEAR事件及选择类型定义于ProductHumanCleanupHistory。事件绑定format=HUMAN_BODY_CLEAR_EVENT/v1、scope/organization/task、generation/previousGeneration/previousHistorySha、intent、原Policy、真实Operator及批准摘要、firstAttemptAt/occurredAt/auditUntil/scopeValidUntil与有序非空选择；禁止正文/凭据。每个选择只含确切packageId/revision和原四表最小追溯标量、原bodyAccessUntil及固定CLEARED后态；manifest_sha/原created/body_until/Task版/请求actor/键摘要/REQUESTED1与WAITING1及audit_until不改。保留标量摘要排除会被清空的bodyAccessUntil。选择按包ID与版排序、不重复；所选包保存期限已到、审计/Task/scope仍有效，否则拒绝本BODY_CLEAR类型而不延时。
2. g>=2的event_sha=sha(event)，Hg=sha(BodyClearLink(format=HUMAN_CLEANUP_CHAIN/v1,scopeId,generation,previousHistorySha,eventSha))。严格连续+1，固定scope/task/org/policy/created/valid，前驱摘要与每行SHA/时间一致；firstAttemptAt<=occurredAt<=now，occurredAt不早于所选bodyUntil，事件auditUntil受原scope/Task及365日上界，读取不续期。所有事件在读取JSON前先核原生occurred_at/audit_until及scope期限。不得从事件自报的Grant/批准SHA推当前维护授权。
3. CleanupScope.current复用内部verifiedHistory，按独立before→主scope+全事件链→独立after核READY/无intent、完全一致及现期；链条所有清除选择须与当前原生四表的NULL正文/NULL访问截止和不变追溯标量逐项匹配。缺目标、重复命中、无法证明完整四表、非法后态拒绝。第1代保持原现有行为；不得因升级reader支持未知恢复或已purge差集。
4. requireBodyUnchanged(historical,expectedCurrent,packageId,revision,manifestSha)必须将historical精确匹配已核链的某个READY祖先，不能仅generation较小。祖先之后没有命中此确切包版的清除，当前PRESENT原生manifest非空、SHA及Task/org匹配；最后assertUnchanged仍严格等expectedCurrent。清其他包可读自己的有效包；清理自己的包后即使旧正文被写回亦拒绝。requireRestorable仍要求当前snapshot精确相等，不扩为祖先许可。
5. RequestWitness仅历史模式将cleanup全等比较替为上项证明，确切包身份来自已核Stored；facts/gaps/comparison/material/source等原比较不变。新提交/同次末检查仍refs精确等值。Application在首次读取旧正文前先核当前scope，receipt-only不凭NULL自动认可合法清除，现有权限和期限不削弱。无当前精确scope时不解码正文；不会为读取补初始化或修复历史。
6. WaitingRecoveryVerifier本段继续拒任何含CLEARED/未知清理数据的整库恢复请求，不删除其原全行/完整正文/原生期限/两遍摘要/Source准入拒绝规则。后继只有明确实现PRESENT+CLEARED最小骨架+合法purge差集后方能放行该恢复范围；不跳过已清包凑counts，不通过重写manifest使旧正文复活。
7. C出口用真实两库H2/原表/事务和已标明的自有夹具，证明原第1代回归、同Task A已清B未到期仍读、A旧正文写回拒绝、伪祖先/跨Task/错包/漏代/重复/前驱或SHA漂移/独立PENDING及末次变化拒绝、到期先标量拒绝不读CLOB、来源变化原比较仍拒绝。测试人工构造清理事件只用于验证读取合同，不声称实际清理执行或授权。通过立即回父正文/终结writer、首次尝试、备份轮换与清理后恢复，不以本窄C关闭完整人工任务。


§10.19.1源码语义复核与组件出口：真实Scope先全量事件标量后CLOB，逐代使用新鲜Clock；BODY_CLEAR还核首次尝试不早于各原保存截止、WAITING正文不超180日、审计不超365日及真实Task、更短所选追溯界，原生请求人类型HUMAN但不要求历史人当前ACTIVE。已清选择与主四表逐项一一对应，未清包仍经原Task授权及来源/事实见证。Application已将scope提前至planner与主回调resolve之前，每次body前后复核，NULL访问截止须确切清除证明；按原访问期限自然到期仍只回原最小回执。此为源代码两遍审阅及代理独立复核结论，非专业或实际维护批准。

2026-10-13 20:07:53北京，JDK21/Maven ProductHuman*Test完整Reactor成功，24类361项0失败/错误/跳过；确切XML及输入稳定性见本批verification。包含真实Application原键B正文/A最小回执、历史伪造与复活拒绝、零正文读取观测及全部现有人工初始化/创建/HTTP/恢复消费者。自有H2和签名组件身份、人工构造清除事件只验证读取合同；无产品清理writer/DDL或实际DM8。规定完整后检另收，不把组件出口冒运行完成。

#### 10.19.2 返回执行器父步骤的已核差异与待收口技术D

本段是同父技术设计输入，不放行未明确的writer/迁移/准入代码。真实源码排除native snapshot围栏复用：SingleDatabaseFence持全表SHARE锁，会与另一连接BODY_CLEAR冲突；其备份权限也不能授权删除到期数据。初始化CurrentLease仅端口，通用owner/source非空字符串不是实际操作者证明。复用实际目标/独占锁/当前配置原件SHA与期限检查，但清理使用自己的行锁、独立CAS和动作准入，不移用restore批准或永久attempt marker。

2026-10-13完整后检cleanup-history-post-verify.log自然exit1：2244项2231通过、11失败、2既有skip，后继未执行。11失败同因历史读取变更未同步现行发行源码证据；不是既有健康两红。窄修仍归PC-CON-HUMAN-CLEANUP-HISTORY-01：保存原application-capabilities/installation-profile字节，仅刷新Scope的11条SHA及真实SQL行锚、补headers及四表/Task/Subject已有SELECT的新增读取证据，再同步源码profile聚合SHA；不新增对象、角色、权限、运行配置或已部署profile。受影响离线授权/安装回归及完整后检必须重新取证。

锁协议经两路源码交叉核对：独立短事务锁维护登记再scope，核确切意图后提交PENDING并释放；第二独立authority事务只持维护登记FOR UPDATE，不提前锁scope，然后主事务按Task→Organization→主scope→稳定排序包闭包锁定。主TransactionTemplate实际commit返回后，才在仍持维护登记锁的authority事务中CAS READY。入口拒外围事务/资源，使用各自确切DataSource；现撤销UPDATE同登记行与主提交串行。普通请求Task后读scope，故反向持scope等Task不可采用。PENDING对账复用同序及窄内部主全后态验证，不调用必拒PENDING的业务current形成循环。三个阶段提交未知分别保留，只有锁后全前态可同intent续作，全后态只补READY，混合保持关闭；真实DM8锁证据仍未取得。

有限多主体写者仍待父完整D：ENTERED只证明进入，不能冒已发出SQL或24小时实际尝试；最终新V2须区分原进入者、实际清除者及仅补READY者。有限追加attempt固定原intent/选区/原retainUntil，续作不续期；UNKNOWN不推定成功或回滚，到期不解析旧JSON而走TERM必要标量残留。此段不放行writer/DDL。

维护认证最短候选沿ProductMaintenanceProof的实际mTLS与当前登记，显式HUMAN_RETENTION角色及确切scope/action批准；初审时枚举/Bootstrap白名单无该角色（后续§10.19.4已补源码/C），现表宽度可容纳而不需SERVICE/Grant迁移。不能将SOURCES自动映射为它，不能对既有登记重跑空库bootstrap。认证应一次取得主体/组织/证书/version稳定证明，后续无副作用核验；现requireOrganization的二次查询不能当同一证明，反复require会向无有限期限的通用audit写入，不能冒V3追溯已覆盖。真实人员、显式角色登记与当前批次操作批准只在启用前核，不是所有源码工作的外部前置。

BODY_CLEAR的具体待实现协议已缩至主V112可空正文、独立V007有限intent/attempt、主scope/event原表。独立PENDING保留已完成g/Hg，固定intent内target=g+1与原生before选择、原dueAt/firstAttemptDueAt、真实firstAttemptAt及原有限审计界；主事务只清所选manifest/access、保持四表追溯标量并追加最终成功event/Hnew；主提交具证后独立READY。失败不抹firstAttempt、不换选区、不刷新截止。重启重新获现时准入并排除旧执行者仍运行，只区分精确全前态、全后态和未知；全前态可同intent续做，不宣称旧动作回滚；全后态只补独立READY，不重复删除；混合/缺失保持关闭。旧初始化PENDING必须显式区分，不能猜为清理或自动补READY。新增列前必须修现Scope无column-list INSERT；逆迁移不得恢复旧正文或抹清理历史凑通过。

不能把“首次人必须永久续做”固化为产品规则：续作需当前真实操作者与确切批准，分别记录首次尝试者、实际执行者和只补READY的对账者，各自真实动作；有限尝试记录不覆盖历史，不将C的对账伪记为B的清除。当前BODY_CLEAR/v1仅表达单一Operator的确切证据，未来多操作者writer须明确新事件版本及独立有限attempt，保留旧字节，不先用v1假装全部支持。到原审计/Task/scope终点不解析或复制过期身份JSON，进入必要标量TERM残留清理，不创造now+365或永久墓碑。

期限到达后的BODY_CLEAR不得循环要求“到期后新完整可恢复旧正文备份”：既有全行原生封包会正确拒绝过期对象，不得过滤行或延长旧包换通过。合法旧包到最短截止即失效；准备好关读/禁止恢复、独立意图、原子事务与未知对账后，仅在另行获准真实操作下清理到期正文。失败只对账，不复活旧正文。当前WAITING恢复仍拒CLEARED；首次真实清理之前须完成新PRESENT/CLEARED骨架及合法差集的完整备份/恢复合同与源码出口。物理删除暂停不变；上述均是Codex工程判断，无新增用户业务事实或实际操作授权。

#### 10.19.3 来源较短审计边界修正限定D

FIX-CON-HUMAN-SOURCE-AUDIT-01沿CON-06/12既有R及V3，责任Codex。V3“全部服从原Task/Source更短期限”未豁免来源关联追溯；现Bounds仅将Source SAVE投影到正文/备份存在缺口。复用已认证Plan.rights逐字段SAVE：audit=min(Task,scope,每个Source Rights.saveUntil)。不取包含facts/comparison正文期限的save聚合，不把短DISPLAY/Grant当保存截止。无Source自有追溯不变；Source SAVE短于Task写期时沿原T<=A兼容检查在四表写入前拒绝，不缩Task/延Source绕过。完整重放verifyBounds核旧不可变界限，末次变化整笔回滚。

旧receipt-only无可读Source见证，不能宣称改公式即兼容旧宽audit数据。现发行证据记载人工功能尚未实际部署，首次发行须核新鲜人工库存，并采用绑定本修正的新技术Policy tuple；旧tuple scope在locate/正文前由现精确政策比较拒绝，不改旧scope/manifest或业务V3。未完成该发行准入时不启用旧范围。组件出口：短SAVE四表零残留、SAVE等T允许、短DISPLAY允许、无Source短facts仍原审计、末次SAVE缩短回滚、旧政策过期正文回执也先拒。仅源码/C，不冒实际旧库已迁或删除。

同D反向恢复核对：生产Bounds另一个构造点WaitingRecoveryReferences必须将逐字段已认证SAVE最短值投影到独立audit，不用facts/comparison聚合save。复用WaitingRecoveryVerifier已有六Bounds比较，拒绝旧audit过宽包恢复；反例设Source SAVE晚于facts正文截止但早于Task审计截止，仅审计界过宽仍拒，不靠正文先到期掩盖缺口。不改领域默认公式、不重写原manifest/封包、不放行CLEARED恢复。

#### 10.19.4 专用真实维护身份锁限定D

PC-CON-HUMAN-MAINTENANCE-PROOF-01沿父CON-06/12既有R/V3，Codex两路复核本窄D；只身份锁组件，不开放清理API/DDL或运行。ProductMaintenanceProof增加显式HUMAN_RETENTION及withRetentionProof(request,callback)：确切authority DataSourceTransactionManager、入口拒ambient actual/synchronization/绑定资源，独立事务SELECT唯一登记FOR UPDATE，从同一快照核真实mTLS主体/组织/证书SHA/角色/version/证书有效期。私有构造lease绑定owner/线程/active/真实事务资源；每次requireCurrent及callback正常返回前重核，finally失效；通用require/requireOrganization对新角色在audit前拒绝，不能写无限通用audit冒本范围追溯。

Bootstrap仅显式白名单允许新角色，不改默认角色、不向现登记续授，不新增角色发行API。父适配负责精确scope/action/selection/政策/批准及有限追溯，且必须等主事务真正commit结算才返回callback；本组件本身不证明主业务提交。出口用现证书/H2夹具证明旧角色/伪header/错过期证书、同版换组织或登记变化、逃逸/跨线程/错误DS/ambient拒绝，真实两连接撤销与锁区间串行及bootstrap非空仍拒。父PENDING/UNKNOWN/主commit与实际DM8另证。实现不得将本组件认作已具清理权或完成清理执行器。

同一共享Proof源码已有三域共9条发行证据，允许仅同步这些原SELECT/UPDATE/INSERT的SHA及实际行号，咨询追加新登记FOR UPDATE的SELECT证据；对象/权限/默认角色不变，其他域不开新功能。源码profile仅同步capabilities聚合摘要，历史发行和已部署配置不动。root保存差异并统一验证，不让代理改共享发行文件。


§10.19.3/.4组件出口：2026-10-13本批407初跑仅1自有Source夹具seal错误，修后该类7通过、其余400同源通过，产品源不变；维护28负例/真实双连接锁及来源在线/恢复审计反例均通过，发行99全过。证据与原失败详本批verification，完整后检单独取证。父下一步只复用nativeBody/History.verify及真实lease：候选主事务先结束→独立PENDING提交→新维护锁内main完整前/后态及真实commit→独立READY。须先审定V2/有限attempt/动作批准绑定，不用业务current核PENDING，不把PreparedClear回调结果冒已提交。

### 10.20 首批候选与默认关闭发行分层（2026-10-13，Codex）

本节纠正§10.14(4)派生发行门禁的适用层，业务V1～V3及实际删除暂停不变。ProductHumanRequestConfiguration/Controller条件仅true，TaskController在false强制不调用human adapter；默认off正常Task不产生人工scope或四请求表记录。V109/V110/V111/V006只增结构，不初始化人工业务行。故当前候选可先构建，完整BODY_CLEAR、多操作者V2与CLEARED恢复不作为其前置。默认off实际发布必须先现场核实际开关、人工库存为空（或存在数据已有有效保护/到期处置）、当前旧系统保护及新包失败恢复、准确迁移/对象/最小权限、目标与有效运行准入；不能只凭开关跳过未知库存或现有义务。

开启功能之前即须全部有限处置准入，因为普通新Task创建已经写两库scope/初始化事件，不能延到首次人工POST。首包若实际TaskUntil不晚于最早bodyUntil+24小时，且已获准且可按时执行本Task有限TERM及副本到期处理，则正文先即时拒读、Task终点统一TERM；无需产生中间CLEARED。未证该不等式或TERM可执行则不能后置相应清理能力。完整分包BODY_CLEAR/多操作者V2/清后完整恢复按实际启用该分支时再准入。TERM/副本清理代码仍缺，删除暂停仍不可被条件部署授权覆盖。

当前构建candidate01仅代表独立咨询JAR及SQL/配置sidecar；候选构建没有执行测试、部署或DDL。新增商标/提示词后续源码不会倒灌为该冻结包的已实现功能。实际保护旧c88于2026-10-13 18:00北京到期，不以它批准当前升级。下一步先核现场库存与当前保护准备，复用有效历史工程恢复证据但不复用过期物理准入。

### 10.21 冻结候选的可达入口与有限发行差额（2026-10-13，Codex）

本段沿REL-CON-START-SUCCESSOR-BUNDLE-01收敛当前4ecb0bdb候选，不更换包、不授物理操作。只读源码核明：ProductIdentityController仅identity.enabled控制整类，activation-recovery仍注册并可由合法SUBJECTS维护者调用ProductSubjects.recoverActivation；无独立关闭该路由的现成配置。ProductSubjects构造不查新receipt只说明V107不是启动依赖，不能把缺人员或当前未授权当作路由隔离。现候选保持该入口时，V107仍是运行合同依赖。V108仅CalculationConfiguration启动probe受calculation=true控制，LineageController和ParentEvidenceReader的报告读取路径仍存在；当前库存零报告不是未来不可达证明。

故本包不采用“能启动即免V107/V108”的路线；先补精确发行元数据/受限安装D。已核人工七表与17项权限之外，V107仅新receipt的CONPROD_SVC SELECT/INSERT，V108两表con5_matrix_dependency_material、con5_matrix_dependency_original各SELECT/INSERT（实际LineageStore第36/41/54/60行），合计当前至少23项缺失权限。V107是原CHECK受控DROP→ADD→新表CREATE，必须沿专属partial/UNKNOWN核对，不能放宽通用DROP或仅把ID加进通用迁移列表。V108是原两CREATE/FK的完整结构与最小权限核验。人工四迁移、V107、V108及其现时前后库存须同一确切候选范围声明；V112/V007/V113提示词/商标持久化不混入本包。

本步骤复用已通过的U107三例及确定partial原件，不再做隔离QA或默认增加新开关/候选。正式V107安装仍遵激活恢复ADR§15.2/15.3的封闭CONPROD入口、合法升级差异返源、新receipt完整保护、新鲜有效保护及确切新空恢复目标准入；跨grantor证明和暂停边界不因本源码结论消失。当前没有完成上述正式安装D，也未执行DDL/DCL/启停/新目标或删除。默认off只后置人工启用前的有限TERM/副本处置，并未关闭这些既有身份/比较入口。

父交付检查点：排除了以human/calculation开关替代运行依赖隔离的假设；下一动作限定为上述本包发行差额及封闭安装/返回合同，不重新扩完整BODY_CLEAR平台。真正启用人工仍按§10.20核有限到期处置和实际人员/资料/Grant。

### 10.22 冻结人工候选的安装声明限定D（2026-10-14，Codex）

ARC-CON-HUMAN-CANDIDATE-INSTALL-CONTRACT-01沿REL-CON-START-SUCCESSOR-BUNDLE-01及ARC-CON-RECOVERY-INSTALL-RELEASE-D-01，只封闭4ecb0bdb候选可审核的安装范围声明；不宣布正式安装D通过。必要增量是把§10.21六迁移、完整SQL结构与23项现知权限差额绑定同一候选/库存原件，供下一封闭CONPROD入口及合法返源D使用。复用candidate01的candidate-receipt/current-inventory/installation-delta及既有V107元数据诊断器，不重建JAR、不重做U107三例，不新造比较器、执行器或通用恢复平台。2026-10-14 root审定并在主计划登记本任务后实施；限定出口为[独立installation-contract.json](../evidence/consultation-human-ordinary-20261013/candidate01/installation-contract.json)及原字节/摘要核证，固定候选和SQL/profile均不改。

声明固定源目标f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f、DM8 8.1.4.170、CONPROD/CONAUTH、候选SHA和三个证据SHA，明确库存采集为2026-10-13历史观察而非当前准入。六原件仅CONPROD V107/V108/V109/V110/V111与CONAUTH V006；逐项保留路径、全文件SHA、完整原DDL及按原顺序分段的语句SHA。语句摘要定义为UTF-8编码的去首尾空白原语句加分号，内部原空白保留；全文件SHA仍绑定原始字节。结构化列包括原序、类型/长度、原显式nullability及PK派生非空，约束包括原名（未命名为null）、类型、有序成员、引用schema/table/有序成员和原CHECK正文。原DDL是结构解释依据，不能用对象名/数量替代完整定义；不猜DM8生成CONS名/对象ID/时间，不将未知变换当已准许差异。

V107只含CK_ID2_SUBJECT_EVENT_OPERATION从原五值到包含REISSUE的六值CHECK及八列ID2_ACTIVATION_RECOVERY_RECEIPT，其PK(issuer,actor_ref,request_id)、FK(issuer,subject_id)→ID2_SUBJECT及版本CHECK准确保留；原DROP→ADD→CREATE次序和逐步提交不改。V108含两lineage表、匿名PK/UNIQUE及三FK；V109四请求表及全部列/键/FK/CHECK；V110两主cleanup表；V111只向新package增加nullable TIMESTAMP body_access_until及原截止CHECK；V006只建独立ID6_HUMAN_CLEANUP_SCOPE。共十张新增表、十四条原语句，V111是已由V109新建表上的追加，不计另一张表；不含V112/V007/V113或清理候选迁移。声明中的排列保留原件次序，不宣称整体物理执行顺序已获准；旧外部FK目标及执行前条件仍须后继入口核实。

权限取installation-delta的17项原件逐项保留，另以ProductSubjects.java:166/197原SQL支持receipt的CONPROD_SVC INSERT/SELECT，以ProductComparisonLineageStore.java:36/41/54/60支持两lineage表各INSERT/SELECT，合计23项，全部无GRANT OPTION。CON3_TASK_CREATE_RECEIPT的SELECT是既存表上的独立合法增权，声明单列EXISTING_OBJECT_PRIVILEGE_DELTA；CONAUTH_BAK仅ID6_HUMAN_CLEANUP_SCOPE SELECT，不能扩至business。其余原权限不得重放或更改；不增用户、角色、系统权或PUBLIC权。此数是固定原库存的已知差额，执行前须重新现场盘点，不冒现时最终写集。

声明包含原件refs、SQL/结构摘要、精确权限增量及尚未证明项目，所有executionAuthorized、installationReady、migrationExecuted、grantsExecuted、applicationStarted、newProtectionVerified、sourceReturnVerified、independentRestoreVerified均为false；不自含SHA，由root外部核证文件全SHA以避免自引用。它不是受管profile或任何现有物理CLI可消费的批准manifest，不自动修改发行profile/capabilities，不调用planner授执行权，不启动应用。

本轮不实现合法差异比较。后继窄D仍须明确完整raw字段闭包、数组多重集、旧行typed摘要、nativeObjects/索引/依赖及生成约束来源映射，逐条消耗声明允许的变更后严格比较所有剩余字段；不得按表/对象名过滤整组记录。GRANTOR仍严格相等，未知差异拒绝。现67/68只供隔离ACTR66，不改namespace充正式入口；通用DROP禁界不变。cross-grantor证明/暂停、确切新空恢复目标准入、新鲜源保护、真实维护writer和运行范围只门禁对应物理操作；源码声明完成不解除它们。安装后新对象全源保护与独立恢复、封闭CONPROD单步/UNKNOWN处置、合法升级返源及人工启用前TERM/副本处置仍待闭合。

父交付检查点：本声明将候选缺失迁移/权限从分散原件汇成确切可审核安装范围，移除元数据范围歧义；尚无DDL/DCL、部署或真实人工流。下一回既有安装D，审定声明绑定的CONPROD单步及完整返源闭包，不开展新通用平台。

### 10.23 正式CONPROD V107单步SOURCE_ONLY详细D（2026-10-14，待root审定）

本节是既有ARC-CON-RECOVERY-INSTALL-RELEASE-D-01的技术设计增量，责任Codex，来源为§10.22原件声明、激活恢复ADR§15.2/15.3和下列现有源码；不是新增业务决定、实现任务或物理操作批准。当前交付缺口是固定人工候选安装时V107的逐步提交、partial/UNKNOWN及升级源合法返源没有正式受限入口；现在必要是安装范围已固定而该入口仍阻断原父发行。复用原SQL、已完成隔离实测与库存/保护能力，出口为root可逐项审定的源码合同及有限反例，审定前不据此编码。通过本SOURCE_ONLY D也只允许另行按主计划登记的源码实现与组件验证，不代表完整安装D、真实DM8、部署或人工流已通过。

**10.23.1 精确范围与复用位置。** 固定源目标为§10.22的f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f、DM8 8.1.4.170、执行owner/schema均CONPROD，固定候选4ecb0bdb0b140ebdd487664cc8540cdf99ec86315fa09835b725fcd9d97d306d。V107原文件SHA为1b36de0c01ad6b5fd1bba76a0a4e43f3050974d1f66101f41d2cb90cbf042cde，三语句只消费installation-contract.json原顺序和trim加分号的UTF-8 SHA；不得接收调用者SQL、重写命名空间、改原文件或增加通用DROP许可。受限操作只包含DROP_OLD_OPERATION、ADD_RECOVERY_OPERATION、CREATE_RECEIPT，以及无写入的核对；本节不新增正式U执行口。receipt的CONPROD_SVC SELECT/INSERT是后继精确权限步骤，必须独立前后观察及journal，不能混入V107一步或重放原674。V108～V111及CONAUTH V006仍为本候选整体声明的其他步骤，本节不以V107三步成功替代其执行D。

| 现有位置 | 可直接复用的合同/能力 | 必须补齐或保持的边界 |
|---|---|---|
| scripts/lib/consultation-activation-recovery-migration.mjs | 原V/U pin、OLD5/RECOVERY6/ABSENT/OTHER、历史及UNKNOWN诊断 | 现输入只是未认证标签；由受信原始观察派生后才能消费，metadataTruthProven/executionAuthorized现有false不改成自授许可 |
| scripts/java/ConsultationActivationRecoveryJdbcStep.java | FrozenSql原字节校验、单步前后守卫、fsync journal和失败分类模式 | 现Scope/Namespace/SYSDBA/789636连接只属ACTR66；不修改成正式范围，不沿用只查表存在/计数的state作为完整结构证明 |
| scripts/java/SingleDatabaseLifecycleJdbc.java及scripts/lib/single-database-lifecycle-runtime.mjs | 真实inventory、captureRaw、assertInventory、typed行摘要、owner等于schema规则 | 既有inventory的targetId来自请求，不能自证真实目标；完整观测需真实资源/连接绑定。evaluateInventoryChecks不证明CHECK完整表达式；通用FORWARD_PINS及DROP拒绝界不放宽 |
| scripts/lib/consultation-activation-recovery-entry.mjs | 现时资源/worker/session核验、host journal RPC、返源前后两次完整库存模式 | 原ACTR入口/原source保护/配置pin均保留；releaseAfterSourceReturn要求旧全库存相等，不能直接承接升级源 |
| scripts/lib/single-database-restore-catalog.mjs、single-database-recovery-origin.mjs、single-database-restore-comparison.mjs、single-database-restore-semantic-map.mjs、single-database-lifecycle-snapshot.mjs | 完整catalog类型/覆盖、origin核验、受证生成标识映射、sealSnapshot/openSnapshot | 原restore比较要求同对象集合，不是升级比较器；无获证来源映射不能忽略字段。现未具备本次升级origin的自动可信生产证据；下述生成及核验闭包必须实现后验证 |

**10.23.2 可信观察和实际owner。** 未来正式入口必须在取秘密或SQL动作前，绑定唯一root维护runId、实际容器完整ID/启动代次、准确网络目标、受控JDBC URL、受控观察工具/类字节及配置SHA、DM8产品/版本和当前许可窗口。执行连接必须从JDBC实际metadata核user=CONPROD、current schema=CONPROD、autoCommit=true；不以用户输入schema或伪造targetId替代，不准SYSDBA执行DDL。禁止连接重定向或以旧隔离工具配置代用。完整六域/15账号观察使用既有获准维护观察通道，与DDL owner连接分别证明身份及同一实际数据库；不能假定CONPROD owner可读DBA/native字典，更不能为观察临时加DCL。观察权限或同库证明不足即在SQL前拒绝，这不要求先做暂停中的owner实验才能完成离线源码设计。

每次观察先后重核同一父维护栅栏、实际进程/连接/资源代次，调用固定真实producer，原始stdout一次有界捕获并以新文件独占写入、同步、SHA绑定；parsed对象须与该raw完全一致，拒绝截断/缺项/重复键/不安全数值/未知字段/错误或complete=false。保留完整六域/15账号的schema数组、全部账号/角色闭包、nativePrivileges、restoreCatalog原类别/列/类型/覆盖和typed行摘要，采用既有32 MiB观察上限，超限停止而非截断。既有schemaFields的objects、columns、constraints、foreignKeys、indexes、sequences、views、triggers、dependencies、rows、nativeSchemaObjects、jdbcColumns、indexColumns、checkConstraints、constraintColumns均需闭包；catalog按原profile全部类别核验。四个派生回执字段inventorySha256/restoreCatalogSha256/foreignKeyDependencies/fullNativeForeignKeys须先重算核对，不能将自报摘要当内容。raw只按已有维护证据权利/期限保存，不新增正文复制或永久保留依据。

操作CHECK由原CHECK正文、列/约束类型/状态/成员及native表示共同判定；receipt由八列有序类型/长度/非空、完整PK/FK/version CHECK、索引/依赖/native对象及行摘要共同判定，禁止只凭名称/数量判EXACT。原五值和六值的识别必须保持字符串字面量大小写和值，不直接复用隔离recognize对整段SQL大写/去引号的宽变换。可接受的DM8表示变换仅取已有固定版本原始证据能证明且不改语义的形式；未知表达式归OTHER。计数由同库受控查询取得十进制BIGINT文本，receipt不存在时receiptCount=null，非法operation、REISSUE和receipt历史分别记录；存在历史不得创造空表冒修复。

**10.23.3 单语句执行及有限状态。** 每次调用最多提交一个原语句，不接受批次、自动续作或自动重试。父维护栅栏全程禁止新旧应用、定时/host worker及其他DB writer；观察连接只读，DDL连接是唯一被准入的例外。SQL前取得完整before raw，按前项认证并与上一步已证明after完整相等，再将run/attempt/step/原SQL SHA/before raw SHA/完整结构摘要/连接与资源代次/fence身份及当前scope绑定写PREPARED并fsync；紧邻execute再核live guard、连接和原SQL字节。SQL返回后重新获得完整after raw并验证只发生本步允许差异，保存原始结果/SQLState/vendorCode的脱敏分类和after SHA，再写COMPLETED并fsync。只有这一链闭合才允许父入口申请下一步；成功响应不替代after证明，DDL不称事务原子。

| 实际前态及历史 | 本次唯一候选语句 | 必须证明的后态 |
|---|---|---|
| OLD5、receipt ABSENT、REISSUE=0、非法operation=0 | 原DROP_OLD_OPERATION | 原operation约束及其精确native闭包消失，receipt仍缺失，其他结构/行/权限全同 |
| operation ABSENT、receipt ABSENT、REISSUE=0、非法operation=0，且属于同一受证安装链的DROP后态 | 原ADD_RECOVERY_OPERATION | 仅原具名六值CHECK及其精确native闭包建立，receipt仍缺失，其他全同 |
| RECOVERY6、receipt ABSENT、REISSUE=0、非法operation=0，且属于同一受证安装链的ADD后态 | 原CREATE_RECEIPT | 八列/全部键与CHECK、完整派生结构均确切，receipt空，其他全同 |
| RECOVERY6、receipt EXACT、无未知结构/操作 | 无DDL；记结构已齐 | 仅SCHEMA_READY_NOT_RUNTIME_READY，不据此放行writer |
| OTHER、来源链缺失、非法operation、缺表但有历史、任一未解释差异 | 无DDL；HOLD | 保持维护与原证据，精确说明阻断 |

该表是首个正式安装所需的窄路径。旧现场外来partial、receipt已存在但operation缺失、逆向中断等不因planner能提出候选而自动获得修复许可；必须回原安装D对实际来源/结构另审，不能泛化为任意partial维修。已存在receipt/REISSUE历史的结构核对不删除、不清空；完整U的隔离实测可复用为证据，但不自动授正式逆向执行权。

**10.23.4 journal、UNKNOWN及有限只读核对。** 复用CREATE_NEW、路径无重定向、独占锁、fsync模式，新增正式记录须绑定维护run与不可复用attemptId、单调序号/前条摘要、语句SHA、before/after raw引用及结论；文件名或caller传来的CLEAR不能清除未知。PREPARED无法可靠落盘则不调用SQL；只有证明SQL调用尚未发生的本地guard拒绝才记NOT_EXECUTED。execute一旦进入、连接断开/超时、响应丢失、after观察失败、后条件不符或完成记录未可靠落盘，均保留UNKNOWN，原错误不覆盖。进程异常时未闭合PREPARED等同未知；不能因新建run绕开已有未闭合维护记录。

核对只消费原attempt和原before，先确认原执行连接/进程已停止且没有仍可提交的调用，再在持续父fence下做一次有界只读窗口：窗口首尾完整raw须一致，SQL/目标/工具/原journal绑定重核。若现状严格等于本步唯一合法后态，可追加RECONCILED_APPLIED及证据；若严格等于原before且原调用终止可证明，可追加RECONCILED_NOT_APPLIED；都保留原UNKNOWN，不能改写为原执行成功。其余为UNRESOLVED，禁止写入/释放。核对本身不提交SQL；下一次单步由父入口在新鲜许可、保护、fence下另行准入，新attempt引用核对结论及同一前态，不自动重放。核对超时/证据不足保持HOLD，不循环扫描、按时间猜测或执行完整U。

**10.23.5 完整合法增量、父fence及新保护基线。** 同源升级差异必须从完整before/after逐项消费，数组按带重复次数的多重集核对，不能按表/对象名删除整组记录。V107每步只允许表中声明的约束移除/重建或receipt新增；所有旧业务行typed摘要、原subject/event、其他六域、15账号及权限保持相等。原约束被DROP/ADD引起的对象ID/创建时间、约束列以及新增receipt的索引/FK对象必须由原语句、同一attempt的真实时间区间、native对象归属/父子关系、完整结构及唯一映射共同证明；本具名CHECK没有已证hiddenTexts/索引/依赖变化，具体只能按下列10.23.5a矩阵消费。不猜CONS名、不以时间区间单独证明来源，不全局抹去LAST_CHANGE/CRTDATE或将已有对象ID重新映射。旧未受影响对象严格保持。真实DM8出现未列明变化即拒绝，按保存的raw回D解释，不补万能预期差异口。

V107三步中的nativePrivileges必须完全不变，包括GRANTOR。后继两权限及候选其余迁移要分别有受准步骤及完整变化证明；最终候选升级旧→新证明按§10.22全部十四原语句/十新表/二十三已知权限构成闭包，执行前新鲜盘点决定真实尚缺项，既存同项不重复授权，不能把声明中未执行部分直接计成功。每项变更必须被且仅被一个原语句或确切权限步骤消费；所有未消费raw字段、多重集成员、旧数据和权限严格相等，未知跨grantor差异拒绝。这里只冻结V107单步及与完整候选闭包的接口，不宣布其他步骤源码D已通过。

每步成功、失败或子进程退出均不能释放父维护栅栏；通用executeLifecycle及sealSnapshot的finally release只可结束它们自己明确从属的子租约，不能复用为父writer许可释放。父维护记录是持久状态，进程退出/时限过期不恢复writer，不删除失败锁；接管必须核原run、原错误、已提交步与当前实际资源。partial无法证明完整合同、保护失效或恢复失败时，保留现场和历史，不开旧writer、不用旧包覆盖、不DROP新表；资源返源running与产品可用分开。

先证明完整旧→新合法增量及新origin（原历史和原来源证明保留，新文件追加实际成功迁移/约束来源及完整native覆盖），再用现有assertRecoverySource/recoveryBinding及sealSnapshot形成覆盖全部六域/15账号、新表、typed行和权限的新认证基线；新origin的生产/核证尚需源码实现，不以人工填journalChainValid=true代替。新保护包必须从同一持续fence内的新库存产生，前后库存/文件/origin一致，包含实际native导出，遵原权利/TTL，不续旧保护、不回填旧包。新包后的独立恢复与返源使用该经证明的新基线；返源前后完整raw严格匹配该基线及当前资源/TLS，而不是再次容许升级差异。原68未变源入口不修改；新返源接口必须同时绑定旧→新证明、新包、新origin、同一run与精确源。实际独立恢复通过及父发行其他现时门禁齐备之前，writer保持关闭。

**10.23.5a DROP/ADD具名CHECK的字段级消费矩阵（本节只封闭两步，待root审定）。** 事实源为[candidate01当前库存原件](../evidence/consultation-human-ordinary-20261013/candidate01/current-inventory.json)，SHA c0d4c26a572c08bbfb10008090a5a1a97dc5eee762b9879eaf46d7f8bb04a990。定义N=CK_ID2_SUBJECT_EVENT_OPERATION，T=ID2_SUBJECT_EVENT，S=CONPROD。历史OLD5实际native约束ID=134219153、父表ID=1060、SCHID=150995944、CRTDATE=2026-10-08 23:08:03.868851；这些数字/时刻是原件定位事实，不硬编码为未来当前状态。未来从可信before按(S,N)唯一定位C0，再由C0.TABLEID定位同S的T及其SCHID；缺失、重复、跨schema同名混入都拒绝。ENFPROD/ADJPROD也有同名N，必须完整保留，不能按N全局过滤。

OLD5原件中三处表达式（compat constraints.SEARCH_CONDITION、checkConstraints.SEARCH_CONDITION、nativeConstraints.CHECKINFO）逐字相同，E5为`OPERATION IN ('REGISTER', 'ACTIVATE', 'DISABLE', 'SERVICE_REGISTER', 'SERVICE_DISABLE')`。原V107 ADD字面量顺序固定，设计E6只在末尾增加`, 'REISSUE'`，其余字符同E5；这是原SQL导出的严格候选表示，不冒已经采集的ADD raw事实。执行判定先要求三处相同且分别精确为E5/E6，不trim、upper全段、去引号/括号、排序IN成员或更改字面量；其他等价书写也UNKNOWN。若后继需支持表示差异，应另以实际raw和无损token规则审定；现rewriteRestoreSql只证明标识符替换不动字符串/注释/空白，不能据此开放本具名N或表达式变换。

| raw位置及唯一键 | DROP允许消费 | ADD允许消费/逐字段要求 | 其余字段及关系 |
|---|---|---|---|
| schemas[S].objects，以(name=N,type=CONSTRAINT)定位 | 恰好删除这一条两字段记录 | 从缺失增加恰一条同名同type记录 | 其他所有对象多重集严格相等，无表/索引对象增删 |
| schemas[S].constraints，以(OWNER=S,TABLE_NAME=T,CONSTRAINT_NAME=N)定位 | 恰好删除原完整一行，必须已证明CONSTRAINT_TYPE=C且SEARCH_CONDITION=E5 | 增加完整一行，键及CONSTRAINT_TYPE=C固定；SEARCH_CONDITION=E6；STATUS=ENABLED、VALIDATED=VALIDATED、DEFERRABLE=NOT DEFERRABLE、DEFERRED=IMMEDIATE；BAD/DELETE_RULE/GENERATED/INDEX_NAME/INDEX_OWNER/INVALID/RELY/R_CONSTRAINT_NAME/R_OWNER/VIEW_RELATED均null；LAST_CHANGE只绑定新nativeObjects.CRTDATE | 列集合必须与该20字段原件完全相同；不把所有constraints.LAST_CHANGE归一化；除这一行外完全相等 |
| schemas[S].checkConstraints，以CONSTRAINT_NAME=N定位 | 恰好删除(CONSTRAINT_NAME=N,SEARCH_CONDITION=E5)一行 | 恰好增加两字段行(N,E6)，表达式与前/下行交叉相等 | 其他CHECK记录完全相等，重复N拒绝 |
| schemas[S].constraintColumns，以(OWNER=S,TABLE_NAME=T,CONSTRAINT_NAME=N,COLUMN_NAME=OPERATION)定位 | 恰好删除这一条，POSITION必须null | 恰好增加同一五字段行，POSITION=null，不改成1或字符串null | 本C型约束只有该一列；其他约束列保持，不因同表而删除全部 |
| restoreCatalog.categories.nativeConstraints.rows，以(SCHEMA_NAME=S,CONSTRAINT_NAME=N,ID=C0.ID)定位 | 恰好删除完整12字段行：TABLEID=原T.ID、COLID='-1'、TYPE$='C'、VALID='Y'、INDEXID/FINDEXID/FACTION/TRIGID=null、CHECKINFO=E5 | 增加恰一完整12字段行，SCHEMA_NAME/CONSTRAINT_NAME/TABLEID/COLID/TYPE$/VALID和四null字段同左；CHECKINFO=E6；ID=C1.ID须与新增nativeObjects唯一相等 | C1 ID是实际新观测的无损十进制字符串；ADD前不得被任何当前native对象占用，不能由旧数加一推算；不允许改引用父表/绑定索引或触发器 |
| restoreCatalog.categories.nativeObjects.rows，以(SCHEMA_NAME=S,NAME=N,ID=C0.ID)定位 | 恰好删除完整18字段行；须TYPE$=TABOBJ、SUBTYPE$=CONS、PID=T.ID、SCHID=T.SCHID、VALID=Y，CRTDATE=compat LAST_CHANGE；其余值见下文 | 增加恰一18字段行，SCHEMA_NAME/NAME/TYPE$/SUBTYPE$/PID/SCHID/VALID固定同左；ID=C1.ID；CRTDATE必须等新增compat LAST_CHANGE并绑定本步真实观察/执行时间证据；其余值采用下文严格不变候选，无证变换UNKNOWN | 只允许本具名新约束的ID/CRTDATE新值；不是将旧对象全图重新编号，不套独立恢复project的时间/ID归一化 |

nativeObjects原N剩余字段实际为VERSION='0'、INFO1='1'、INFO2='-1'、INFO3='0'、INFO4=null、INFO5={binaryBase64:'AQAAAAAA'}、INFO6={binaryBase64:'AwAAAA==' }、INFO7=null、INFO8=null；ADD的严格候选值逐字同此，binaryBase64必须原bytes相等，不按“看起来像flags”屏蔽位。原父表T是独立nativeObjects行（历史VERSION='4'、INFO3='1157425104234283008'）；它的全部18字段，以及T的columns/jdbcColumns、行数/typed摘要、tables目录记录都在未消费集合中，当前无证据允许VERSION/INFO*/CRTDATE等随DROP/ADD变化。若实际发生变化，结果必须UNKNOWN并保留原件，不能扩大本矩阵以猜测DM8版本计数规则。C1是否重用已经删除的C0 ID不得单靠数字作结论：必须在紧邻ADD前证明该ID未占用、新约束六处关系与journal一致；无此证明拒绝。

其他raw位置明确零允许差额：S.dependencies历史为[]，DROP/ADD均必须仍完整相等；所有schema的foreignKeys/indexes/indexColumns/sequences/views/triggers/rows/nativeSchemaObjects以及其他对象的compat/native记录全同。nativeIndexes没有INDEX_ID/TABLE_ID指向本C0.ID的成员，C0.INDEXID/FINDEXID/TRIGID皆null，故不能以本CHECK为由增删或改变任何nativeIndexes/索引列/FK。viewDefinitions、indexExpressions、hiddenColumns、hiddenTexts、hiddenDependencyCount、hiddenObjectInfoCount、hiddenTableCounts的列metadata及全部rows均保持相等；所有catalog类别columns(label/jdbcType/typeName)也必须原样相等。hiddenTexts由SingleDatabaseLifecycleJdbc.RESTORE_HIDDEN限定于六域自引用内部UTAB/TRIG，本CHECK为TABOBJ/CONS，不在该查询集合；原件12条hiddenTexts不是本CHECK正文，原件无C0.ID项。这证明本profile内没有本CHECK hiddenTexts变化许可，不能宣称已覆盖全部SYS.SYSTEXTS或SYS.SYSDEPENDENCIES。compat DBA_DEPENDENCIES与hidden计数是各自已记录范围，无新全native依赖采集不得冒完整任意依赖证明。

DROP比较算法只在before精确取出上述六行各一份，证明行间ID/父表/时刻/表达式一致，再将该六行从before的副本中按完整行值各消费一次；after这六处必须无该S/N约束，剩余全raw多重集与after严格相等。ADD比较先证明before六处都不存在、原链与父表未漂移，独立验证after上述六行并从after副本各消费一次，然后与before剩余全raw严格相等。category/schema/root字段集合不能删减；数组排序比较须保留重复次数；键/同名冲突或残留ID引用拒绝。四项派生回执先各从该次完整raw重算，随后仅这些派生字段允许按各自值变化，其他root身份/完整性/账号/权限字段不变。必须执行剩余集合全等，不能把六个类别整体视作允许差异。

隔离ADD现有证据边界：[774eb834原journal](../evidence/activation-recovery-design-20261010/entry-admission68/run-774eb834-c8bf-4b4d-af4a-3c61bcaea4ab/attempt.jsonl) SHA 0621c1097c925c2aeb587064a75b905735731c11178535f5643cc42789d93acc，V_ADD_NEW的原SQL SHA=24ffa4b1fc4218a5abf45f89f169de6fe1e901f02811249b4f3d4a2af55e0796，PREPARED/COMPLETED分别2026-10-12T14:36:16.663Z/14:36:24.219Z，能证明受限执行记录。其[partial-native97](../evidence/activation-recovery-design-20261010/continuous-fence75/run-774eb834-c8bf-4b4d-af4a-3c61bcaea4ab/partial-native97.json)是ADD前bootstrap对象清单，不含兼容/native完整列；[target-inventory-7](../evidence/activation-recovery-design-20261010/continuous-fence75/run-774eb834-c8bf-4b4d-af4a-3c61bcaea4ab/target-inventory-7.json)只含六正式schema，不采ACTR66_EMPTY；隔离state/FixtureGuard校验未把ADD后原SEARCH_CONDITION/INFO*/父表目录变化落为完整raw。因此已有执行成功不证明本矩阵ADD正向字段闭包，特别E6实际render、新约束INFO*、父表VERSION/INFO*的真实差额仍未获完整原件覆盖。本SOURCE_ONLY可实现的只是上述精确形状与UNKNOWN拒绝算法，不能宣布真实DROP/ADD比较已验证；更不能据此宣布CREATE_RECEIPT、两GRANT、六迁移整体比较器D通过。未经root审定及相应源码任务登记仍不编码。

**10.23.6 源码审定出口与物理阻断。** root可据本节及10.23.5a证据边界单独判定实际可准入的SOURCE_ONLY范围并按既有计划登记最小实现差额；本节当前状态仍是待审，不变主任务状态，完整比较器D不因原则描述或两步矩阵自动通过。后续组件反例应针对实质失效：伪造target/owner或caller标签、完整结构/字面量/旧行变化、遗漏native成员或重复多重集、SQL前journal失败、SQL后响应/journal丢失、原调用未终止时核对、UNKNOWN换run/自动重试、partial释放fence、GRANTOR变化、旧基线冒新保护；成功例验证获准单步的原SHA、完整闭包及有限核对。组件输入只证明算法/合同，不冒真实DM8或来源授权。旧U107三例与确定partial实测复用，不重复演练或构建固定候选。

cross-grantor等价证据、确切新空恢复目标、有效新鲜源保护与当前维护/运行准入仍在物理动作前逐项满足；cross-grantor/DCL/owner实验、新目标/未准切库及物理删除继续受原暂停约束。本节不能启动真实连接、取秘密、DDL/DCL、停止/部署应用或生成短期激活材料；人工启用另守TERM/副本处置及实际人员/Task/Source/Grant。父交付本次只移除V107源码设计粒度不足，未取得运行能力；root审定后回原安装步骤推进已准源码差额，物理缺口不伪装成全部工程须等待用户。
