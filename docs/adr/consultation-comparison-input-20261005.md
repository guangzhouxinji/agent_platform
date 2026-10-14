# 专业对照 ComparisonInput 限定物理设计

## Input返回前现时准入窄修（2026-10-13）

责任Codex，对应FIX-CON-INPUT-READ-TERMINAL-ADMISSION-01；R沿REQ-CON-R-CORE-12，D沿本篇原创建/读取合同及产品对照D §5的消费前现时授权。InputApplication.read原生封套/候选/事件末次复读之后仍须验证同一输入的确认特征、准备与现时材料、Task READ和最早有效期限。末次存储复读期间恰到期、READ撤回或Source不可用必须拒绝。返回前材料验证结束之后再核当前Task及READ、Input/特征/准备/当前材料最早期限，避免最后授权或序列化消耗剩余期限仍成功；当前Task与末次材料认证Task不相等也拒绝。历史读取继续允许旧Task版本，仍需当前Task及相同原材料，不复活旧权限。复用原409/404错误，不新增路由、状态、迁移、模型调用或授权；创建/消费共用的verify只加强相同终末守卫，保留原CAS/事务/重放语义。

反演为现有实际H2 Input/候选/事件装配，存储第二次read取得原生结果后改变受控时钟、READ或Source端口；先保存原失败，再验证同反例拒绝、稳定原回执/原生行及历史读取不变。受影响范围含Input创建/重放/消费、Start和父/祖先读取；组件端口不能证明真实DM8隔离、账号、Source许可或运行交付。无新增业务未决，仅该窄修技术D闭合；所有运行暂停保持。

日期：2026-10-05；IMP-CON-PRODUCT-COMPARE-01 / PC-COMPARISON-INPUT-01。依据已通过原对照D §2/5/6和prepare/特征确认限定D，不重开Task/Source/身份D。只实施正式创建、读取、不可变输入和候选执行槽，不借现有检索Worker授权发布对照。

请求为expectedTaskVersion、featureRevisionId/featureRevisionNumber/packageSha、previousComparisonId、methodVersion及Idempotency-Key。只接受PATENT_FEATURE_MATRIX_V1；不接受客户端矩阵、授权状态或隐含模型输入许可。Task COMPARE和READ均须通过ProductConsultationAccess；已确认FeatureRevision须仍为本Task current，包SHA/父版本/行/审计闭合。新请求锁Task，前comparison指针CAS；同键同载荷且仍具当前权限重放同回执，异载荷409。

公开创建/读取回执仅返回comparisonId/inputSha、Task版、FeatureRevision/包SHA、purpose/method、截止和实际逐候选进度；内部现时授权快照/Grant主体与ID不外露。进度是持久执行槽的真实状态，不以QUEUED称已分析。完整矩阵/报告由后续结果端口消费，不在创建回执中伪造。

从FeatureRevision的preparation重读目标和候选，创建时要求当前Goal/Input/Task版本；确认原文与materialSha相同，允许现时授权代数推进，但不得替换文献、原件、权项分支或确认特征。新输入固定本次完整权限与material快照、FeatureRevision/packageSha/原准备关系、purpose和methodVersion。期限为Task/Feature/preparation/当前材料各自期限最小值，保留沿本Task既有ref/version/evidenceSHA。无SAVE仍仅保存引用/定位/哈希/权项关系与用户确认说明，不保存第三方全文。

V095/U095仅新增con5_comparison_input、con5_comparison_candidate、con5_comparison_current、con5_comparison_event。input关联同Task的FeatureRevision及preparation；唯一Task/主体/request；候选槽绑定同preparation的原准备候选(document_role='CANDIDATE')，初态QUEUED不代表Worker执行或分析。current指针及创建事件与input/全部候选同事务；最后权限/Task/Feature/材料复核失败全回滚。native候选投影与canonical包交叉校验，缺行/篡改/父错绑拒绝。消费前再次验证current指针/Feature/Goal/Input/权限；历史读取只核当前读取权与相同材料，不复活旧权限。U095在有input历史时第一步拒绝，不删除历史回退；空表才允许逆迁移。

正向：同Task确认版创建→确切候选槽→同回执重放→授权读取。反向：旧特征/旧Task/旧comparison指针、不同请求载荷、越权、材料/包漂移、过期、缺事件表/末尾失权全回滚、候选丢行、非空逆迁移拒绝。以上已反演闭合，释放本限定物理实现D；不宣称真实DM8 I、模型/Worker执行、完整矩阵或专业B已通过。Worker后续必须独立核正式服务证书、双主体COMPARE Grant、精确委托、COMPARISON源范围及围栏，不能把本输入或QUERY历史当权限。

## Worker授权与精确委托接线的限定技术D（2026-10-07）

归属IMP-CON-PRODUCT-COMPARE-01、原产品对照D §5/6。任务PC-COMPARISON-WORKER-GRANT-01与PC-COMPARISON-WORKER-DELEGATION-01；技术负责人开发团队。本细化不发正式授权，不新增专业方法/模型用途，不改原QUERY链。

1. 维护通过已有证书SUBJECTS+GRANTS同一主体/组织路径给活跃AGENT服务签固定Task COMPARE/TASK_WORK，使用当前Task授权版/DataScope/独立Grant水位/现有事件和幂等。新的固定comparison-task-grants路径不接受客户端action/purpose；原task-grants仍只有WRITE/TASK_WORK。Task层COMPARE与来源COMPARE/COMPARISON分开，前者不授权读CLAIMS或入模。无表变更，沿现有workload allowed_purpose=TASK_WORK。
2. 人类显式为当前comparisonId/inputSha/单候选创建委托，不把服务JWT伪装人类。人类须READ+COMPARE及当前原件用途；真实session复核，服务与人类都须精确Task COMPARE Grant、Task授权版及期限。委托过期不能超过Task/Input/workload/两个Grant；请求SHA绑定Task、comparison/inputSHA/candidate、服务、期限和动作，异载荷拒绝。原WRITE委托及搜索Worker不接受此COMPARE委托。
3. V098/U098仅增加con5_comparison_delegation绑定表，delegation_id PK/FK→con3_worker_delegation、comparison_id/candidate_id复合FK→con5_comparison_candidate；保存inputSha、bindingSha和期限，不存原件/Token。bindingSha参与旧委托receipt的独立水位覆盖requestSHA。Task锁内核当前input/Feature/Goal指针，原委托、事件、回执和绑定同一主库事务；绑定写失败不留半委托。独立水位包住主库commit，不能外层事务包水位change造成未提交发布。重放须核当前input及原绑定，不覆盖旧包。撤回由同一当前人类COMPARE权限/确切委托版完成，原绑定保留。
4. 新绑定只证明授予哪一个输入/候选的意图，不是租约、Source用途凭证或矩阵提交能力。后续Worker实际consume必须重核服务证书、双主体当前Grant、委托、Task/Input/Feature/候选、逐源COMPARISON及精确围栏；未接线前不开放结果POST，也不标COMMITTED。新增维护Task能力不放行service来源Scope签发、MODEL_INPUT或后台Worker。
5. 正向反演：固定COMPARE签发→有当前人类/服务Grant→当前ComparisonInput及候选→确切委托/绑定→幂等→撤回。逆向：WRITE不能替代COMPARE、异对象/旧input/失权/期限/真实session失败拒绝；绑定写失败事务回滚、旧绑定篡改对requestSHA核验失败、非空逆迁移第一步拒绝，空表才可DROP，保留原委托历史。该工程切片不冒真实DM8/TLS/I或专业B。两遍技术核对在原Task R/D内闭合，释放以上代码及限定物理C；真实应用迁移另核运行准入。

本轮实现细化：原WRITE task-grants的请求摘要保持原字节协议（隐含旧固定WRITE动作），新COMPARE路径加COMPARE_TASK_GRANT前缀；两路径不能同键互借，已有WRITE回执能继续按原请求对账。Task COMPARE委托的读取/撤回入口位于当前Task的patent-comparisons/{comparisonId}/worker-delegations；读取仅回意图坐标和期限，存储ACTIVE不是现时执行准入。撤回只核当前人类Task COMPARE与session及原绑定，不为撤回去读旧Goal/原文；跨主体、跨Task或WRITE/COMPARE动作不符拒绝。

V098绑定保存毫秒精度期限，bindingSha对Task/comparisonId/inputSha/candidateId/serviceSubject/expiresAt/固定COMPARE的canonical包取SHA；原con3 ISSUE回执requestSHA同时包含该bindingSha和人类主体，现有独立授权水位覆盖它。故绑定即使本地重新计算SHA，也必须吻合不可改写的原委托回执。新签发必须INSERT绑定；重放必须找到并验证原绑定，丢失时拒绝，不自动补造。委托事务回调内核当前Input/Feature/Goal原生指针及完整Input包；该窗口的独立Grant权威为PENDING，不能构造人类JWT重读原文或把未commit授权发布。事务结束后再次走现时人类consume核完整材料，不把Task COMPARE/意图绑定当Worker租约。任何未来Worker/source/租约/矩阵能力仍依原D单独消费，非本切片已完成范围。


## Worker现时Task授权与输入意图核验的限定技术D（2026-10-07）

PC-COMPARISON-WORKER-INTENT-CHECK-01沿原对照D §5/6，只交付内部执行前置，不开放HTTP读取原件/结果。TLS客户端证书必须由可信容器提供并满足clientAuth及有效期；双主体须当前精确COMPARE/TASK_WORK、issuer/组织/Task/dataScope/授权版/epoch，ACTIVE AGENT SERVICE无密码且workload有效。原WRITE检索授权类保持原用途，不泛化成任意动作。

内部核验只返回delegation/comparison/inputSha/candidate/service/期限坐标：读取原绑定并校验原不可变ISSUE回执SHA；输入包、当前Task版本、Feature/Goal/Input指针和候选必须一致，候选状态与输入候选集通过原InputStore完整性检查。Task/Grant/委托/输入期限及前后授权水位均需现时重检，拒绝在窗口内授权漂移、撤回、输入切换、绑定替换。不得伪造JWT读原文；不读取/返回原件或矩阵正文、不改变候选状态。后续实际执行仍必须独立逐源COMPARISON消费、候选租约/attempt/fence及提交事务，未经实现不能运行。

技术反演：精确当前证书+双COMPARE+原回执+当前输入正向通过；反向包括缺TLS/clientAuth、错issuer/组织/范围/epoch、WRITE替代、撤回/到期、错inputSha/candidate/service、旧Input/Feature/Goal和本地篡改、末尾水位/Task变化。限定R/D继承父任务及已完成输入/委托D，不发正式批准、不创造专业阈值或恢复规则。


## 正式Worker逐源原件消费的限定技术D（2026-10-07）

PC-COMPARISON-WORKER-SOURCE-CONSUME-01继承原产品D §5/6及当前Input/精确委托D。可信TLS客户端证书、双现时Task COMPARE和精确候选意图先核；内部已授权Actor只用于当前委托人读取权消费，不生成JWT、不向客户端接受Actor/原件正文。用户方案/特征及来源读取前另核委托人的精确当前Task READ（issuer/组织/dataScope/Task授权版/epoch/期限），不能靠创建时权限。

同Task当前ComparisonInput只消费本次目标及确切候选，候选DocumentRef从原准备命令按既有source/document/version键定位，不让调用方另选文献；其他候选原件不顺带读取。逐源CLAIMS的DISPLAY与COMPARISON及本Task同用途Scope/Grant必须均为现时，来源PENDING/撤回/目的缺失拒绝。使用原正式原件读取/字节SHA/定位/引用权项图组件，不回退TITLE/摘要或旧QUERY；保存输入的原MaterialDocument和目标Span与现时原件重新推导一致，权限代数可变但原件/特征不得换。无MODEL_INPUT/SAVE隐授。

原件读前及返回前重核Task READ、精确输入/候选委托、Task双COMPARE/证书和来源/期限；返回仅内部服务的一个候选材料，不能作为HTTP正文或结果提交。过期/撤源/材料漂移拒绝，不改QUEUED、不创建COMMITTED，不启动模型或后台Worker。技术正反演含正常获权确切原件、READ/DISPLAY/COMPARISON任一撤回、跨Task/错候选、变更SHA/字节位置、旧输入、末尾水位变化。沿现有许可工程输入验证，不称正式DM8/TLS/业务验收。


## 逐候选独立attempt/租约限定物理D（2026-10-07）

PC-COMPARISON-CANDIDATE-LEASE-01沿原产品D §5/6/8，不复用search-only任务槽。V099增加不可变绑定的con5_candidate_attempt（Task/comparison/candidate/inputSha/taskVersion/cancelEpoch/worker/delegation/requestSHA/originalsSHA/fence/开始与截止）及con5_candidate_lease（每comparison/candidate一个current槽与精确attempt/token）。FK关联同Task输入、候选和原精确委托；request按comparison/candidate/服务主体/key唯一，异载荷拒绝。Task行与候选槽依序锁定，首个QUEUED领ACTIVE/RUNNING；显式失败后新键领新attempt/token，历史失败保留；其他候选槽独立不受影响。期限取请求duration与当前材料/授权截止最小值，不给超期限token。

领取前及主库事务结束前消费同一个确切Worker材料包；current token校验重新消费现时权限/原件，核同Task/Input/current特征/Goal/候选、cancelEpoch、服务/委托、材料SHA和租约截止，原件或来源范围变更拒绝。相同请求仅在原attempt仍ACTIVE且当前材料和权限有效时重放；失败/过期/旧token不能被原键复活。材料指纹仅含目标Span、候选材料及来源用途坐标/哈希，不保存第三方原文或Token。

本切片允许内部显式稳定errorCode标FAILED，保持原矩阵未知项与模型失败分离，不用失败当零分；输入取消/Task版变化/租约到期后旧token不得处理结果。到期ACTIVE改为RECONCILE_REQUIRED且候选标FAILED，随后抛需对账；不自动换attempt重发未知外呼，不定义未批准外呼恢复。原失败候选只能新键重试，成功COMMITTED无新领取。此切片无publish/Runnable旁路，未连接产品矩阵事务前不能COMMITTED。

U099非空历史第一步拒绝，空表才按FK顺序回退，保留原输入/委托/事件。正反演含A/B独立领取、同键重放/异载荷拒、合法失败与新键重试、旧围栏/错人/错委托/取消版/过期拒、末尾撤源/失权整笔回滚、非空逆迁移拒绝。仅释放本物理C；不发正式迁移/运行批准，不替模型披露、矩阵持久化及专业验收。

## 候选产品计算及矩阵结果接续技术D（2026-10-07）
归属PC-COMPARISON-MATRIX-CALCULATE/COMMIT/READ-01，沿原产品D §4～8及现有R；技术执行负责人开发团队。本细化不签发模型/来源权利，不开启后台Worker或未知外呼重发。

计算使用统一ModelGateway、部署受管的确切模型配置及部署控制证据文件。模型批准文件须具名责任方/批准时间/起止/确切Task及inputSha/候选集合/模型配置版本/披露类别与调用上限，文件reference/SHA在控制证据根核验；配置、期限或范围不符拒绝。人类方案原文及已确认特征只有在该明确输入披露批准内可入模。专利源CLAIMS逐次另核MODEL字段用途及确切SourceScope COMPARE/MODEL_INPUT现时Grant；此固定用途适配只消费额外模型披露权，不由原COMPARE/COMPARISON推出。模型与结果正文的保存还须逐源SAVE字段用途，未获SAVE不能持久化引用正文/模型原件。本轮不新增来源授权签发旁路。

提示与输出采用PATENT_FEATURE_MATRIX_V1：每候选权项分支×确认特征一行，模型只返回关系/理由/相同点/差异/不确定性和候选原文字符跨度；跨度转换为实际原件字节Evidence并交PatentComparisonMatrix.validate。模型不得选择另一候选/输入、改变确认特征、创建法律结论或评分。实际分析范围显式记录，NOT_FOUND仅已处理范围；响应结构/额外字段/漏行/引文/版本/禁结论均失败关闭，不修补成全部UNKNOWN。

V100为计算原件围栏与结果实体：con5_matrix_invocation按attempt唯一，保存确切请求SHA/批准SHA/模型配置版本/状态及许可内原始响应；按批准SHA计所有已预留调用，Task锁内预算检查。预留在事务内持久后，外呼在事务外且仅一次。已有RESERVED且未收到响应只报MODEL_INVOCATION_UNRESOLVED，不重发；RECEIVED可以在现时授权有效时重读已有响应校验，不重新调用；非法已收到响应标REJECTED并按已知失败合同处理，网络未知/超时/末尾失权不标可盲重试。没有供应方对账证据不提供未知结果恢复动作。

con5_matrix_report/row/citation/receipt：报告绑定Task/comparison/candidate/Input/attempt/token/生成主体/方法/模型/批准SHA/版本/原件响应SHA/期限；行与引用有精确父FK及稳定ordinal。提交锁Task/候选current，先核原输入/权限/原件/租约，再校验已收到原始响应和矩阵，结果正文与digest/所有行/引用/回执、候选及attempt/current COMMITTED同事务，末尾再次核现时源/SAVE/模型用途与预算批准。任何缺行/错绑定/末尾失权回滚全部提交；A已提交/B失败保留A；已有确切提交回执可在当前权限有效时读回，不另发模型。

正式TLS Worker领取及结果端点只接收确切scope/租约坐标，不接受任意矩阵或配置正文。报告GET沿人类Task READ及原Input读取，重核当前DISPLAY/COMPARISON/SAVE及包SHA、行/引用/回执/attempt绑定后返回，撤权不泄漏历史正文。相同比较Input下结果随候选独立不可变保存；目标/特征/输入变更仍归新Input，不静默覆写旧报告。工作台消费已有正式入口，显示确切编号、结果/来源/版本和真实部分状态；不添加数值评分、开发身份或自动后台推进。

正反复审：许可、独立Grant、批准证据、模型能力/预算、源保存用途各自缺失不调用或不提交；字符到原始字节转换、完整分支/特征分母由领域核；调用前围栏与提交事务分离；未知结果不变键；完整矩阵/回执/FK坏件拒绝；每候选独立结果，无专业或生产验收外推。物理工程C与真实DM8 I/正式运行分别取证，V100未应用不称正式发布。

依赖粒度技术校正：原子存储工程依赖已完成候选租约及已有领域矩阵合同，不依赖模型适配器先完成；计算与存储可在主会话不重叠职责内接续实现，但正常提交验收必须同时有计算与原子闭合证据。候选原生状态使用V095既有COMMITTED，不发明COMPLETED新状态。

固定模型用途签发适配：现有issueSourceScope只接受QUERY/DISPLAY/COMPARISON，不能靠维护者原COMPARE授予MODEL_INPUT。PC-COMPARISON-MODEL-GRANT-01沿同一SOURCES+GRANTS同主体/组织/证书维护命令，新固定comparison-model/authorize仅映射COMPARE/MODEL_INPUT与CLAIMS的MODEL用途，requestSHA明确含MODEL_INPUT，复用原Scope/current/read/水位/幂等和审计；客户端不能自选purpose或借此签SAVE/其他领域。授权时仍须已有获准现时字段用途，不代签源权利。无新身份/来源/Grant实体或迁移，旧COMPARISON路径不变。正式签发须有具名责任与现时范围，工程测试不签正式对象。


## 咨询输入工作台及prepare请求对账限定技术D（2026-10-07）
归属PC-COMPARISON-INPUT-WORKBENCH-01，继承原产品对照D §2/3/5/6及prepare/特征/Input物理合同，不签来源或模型批准。
现有正式工作台从已合法保存的Selection选择目标/候选确切文献版本，用户明确选择已保存的用户原文或目标专利；当前Goal查询表达式不能自动成为技术目标。逐候选明确所选权项，不猜编号；prepare展示确切原文/权项分支与缺口。以原文可视选择生成UTF16跨度和稳定特征ID，用户编辑说明并显式确认；坐标/字节与最终合法性仍由服务器核验。前端从当前服务上下文取得Goal/Input/已保存用户消息及特征/Comparison current CAS坐标，不让用户填写内部SHA或JSON；未获当前READ不返回。
新增只读context响应仅含当前Task/Goal/用户消息、前特征revision与前comparison指针；通过既有GoalReader和TargetReader现时读取及前后复核，不读取过期Goal/正文，不扩大源用途。原确认/创建API继续按现时WRITE/COMPARE、原件、包和CAS合同处理。
原prepare POST没有幂等或确切读取入口，未知提交不能安全重发。V101增加con5_preparation_request，绑定Task/actor/key/requestSHA/preparation，主Task锁内与现有prepare及文献投影同事务。请求SHA含确切Task/人类主体/key/Prepare；同键异载荷409，同键重放重核当前原件/Goal并返回原preparation，不创建第二份。缺表或末尾失权全部回滚。人类确切GET preparation仍重核同Task原件和当前期限，回执不代替权限；确认/创建已确认后只GET原ID。U101有请求历史时拒绝回退。旧无键内部prepare仅为已有直接服务调用兼容，正式HTTP必须提供稳定Idempotency-Key；不让无键HTTP成为旁路。
UI写入Packet保存在当前会话内存，同Task/Goal/选择/目标/特征上下文锁定；未知响应不释放Packet、不换键，显式同键对账。收到错误绑定的200仍保持未知，收到有效确认后立即撤除写入围栏，后续GET失败只重读确切ID。身份/Task/Goal/文献选择变化清除敏感展示并作废迟到响应；不声称退出取消服务器写入，不自动重试CAS冲突。工作台创建Input仅显示QUEUED真实状态并关联原结果组件，不在浏览器调用mTLS Worker或自行建立后台启动命令。
技术负责人开发团队；本D只闭合原R/D的客户端消费/幂等物理接线，专业方法、保留及正式部署权不变。工程正反例验证、正式DM8应用和真实业务I/B分别取证。

## 对照发行就绪限定技术D（2026-10-07）

归属PC-COMPARISON-SCHEMA-STARTUP-01、PC-COMPARISON-RELEASE-PREFLIGHT-01、PC-COMPARISON-RELEASE-PACKAGE-01，沿既有R/D及启动失败关闭合同，技术责任开发团队。当前仅mTLS Worker执行接口有合同；新增人类启动/领取/后台命令未明确，继续门禁，不由本D发明权限或外呼恢复。

SchemaGuard以SELECT显式必需列FROM表WHERE1=0在启动阶段核现有物理表列，不查询业务行、不DDL、不认定迁移历史。正式Identity+Source Engine启用时对照输入服务已经注册，因此基础V094/V095/V098/V099/V101结构须齐备；计算flag启用额外核V100。缺结构抛启动失败，不在请求内才发现缺表或半提交。计算启用时还在Bean创建阶段实际验证原有批准文件SHA/现时期限及受管HTTPS/OPENAI_CHAT_COMPLETIONS配置，不等第一次外呼才发现缺批准；运行期间原逐次核验仍保留。探针只证明结构可访问，类型/主外键/已应用版本及DM8适配仍由正式迁移记录/物理验证证明。

现有容器脚本的ValidateOnly和实际部署共用离线配置核验，必须在任何Docker操作之前完成无副作用配置检查。新增明确OfflineOnly只做配置前检；原ValidateOnly继续运行既有Docker身份/证据/挂载原件检查后不部署，不能把原检查语义降为离线配置。OfflineOnly/ValidateOnly同时指定拒绝。明确计算enabled=true时核模型批准ref/SHA及凭据引用和既有Worker受管配置，不能将ref/SHA当任务/来源现时批准。缺项拒绝而不是发默认授权。保留原部署身份/来源文件根/原DB对象/镜像digest/只读挂载/失败回退边界。

发行使用当前workspace必要源码，不沿旧密封基线overlay遗漏新增文件。主会话固定构建前后backend/db/scripts输入SHA并用现有bundle-consultation profile构建咨询专包；证据固定实际jar/入口及必需类、迁移原件SHA/顺序/前置与禁止非空逆迁移。限定工具只做检查/生成工程证据，不连接DB、不迁移、不部署、不签决定、不拷凭据。缺输入或旧包类缺失拒绝，不让声明替代实际字节。正式迁移/运维/来源/模型与专业验收独立门禁。


## 输入结果精确消费与目标只读核验技术细化（2026-10-07）

FIX-CON-MATRIX-INPUT-BINDING-01沿既有Input不可变回执与矩阵读取D：输入工作台成功GET确切Input后，将comparisonId和inputSha作为同一绑定交给结果区；不得仅依据相同comparisonId接纳其他Input结果。绑定变动撤下旧摘要/报告并失效迟到响应，恢复只GET，不新增Worker或模型行为。已有按编号读取入口保留，但当前已确认输入关联不可静默丢弃SHA。工程负责人主会话；UI语义不改变权限与专业判定。

PC-COMPARISON-TARGET-METADATA-01仅执行用户本轮允许的原目标限定只读检查：先据正式配置和既有实例证据核exact容器ID/loopback端点/现时运行状态，再使用原受管连接身份、固定SELECT系统目录/迁移日志；不读取业务行，不复用旧readback中的Task/Grant/来源查询，不执行旧脚本的启动/停止。无迁移日志时仅证明结构观察，不自动认定已应用版本。连接凭据保留受管目录/临时进程，不写证据。迁移、备份写、部署重启与新业务准入另核批准。


## 固定包限定发布与保护验证（2026-10-07）

PC-COMPARISON-APPROVED-RELEASE-01依据用户本轮明确项目环境运维批准，固定包SHA f7f6410e…、原两库/应用exact ID及五脚本沿原申请；14:00～16:00北京时间执行DDL/必要停服替换，窗口前仅非破坏性准备。保护必须先证明可用、源数据/文件一致、失败关闭及旧包兼容边界；旧保留合同到期不续期或伪造专业维护声明，不把应用回包当schema逆迁移/新写覆盖。发布计算保持关闭，不激活来源/Task/额度/无人值守。

VER-CON-COMPARISON-RELEASE-COMPAT-01由独立执行者只在compatibility证据目录验证固定新旧Jar/SQL/旧读取依赖、H2正反约束和20环境错误逐项影响；不得将静态/H2当DM8/备份可恢复/业务验收，真正DM8前置与数据保护由主会话核验。工程证据与真实发布分开；无法确认兼容则保持写入关闭，不自动回灌旧库或执行非空逆脚本。

## 普通用户启动到受管Worker交接：技术细化与剩余决定（2026-10-07）

归属IMP-CON-COMPARISON-USER-START-D-01，依据本轮用户第五节设计授权及现有ProductComparisonWorkerDelegations、ProductComparisonWorkerTaskAuthority、ProductComparisonMatrixController。该授权允许详细设计，不把现有ACTIVE委托等同发起命令，也不批准新来源/模型预算；本节不是全部D通过或已实现。

请求只携带确切Task版本、comparison/Input SHA、候选编号和原幂等键，不能带serviceSubject、服务URL、证书、模型配置或任意矩阵。服务主体、HTTPS目标和客户端证书仅从现有受管配置选择并核验；复用真正mTLS HTTP链，不合成HttpServletRequest证书，不伪造人类JWT。服务切换不自动沿用旧命令。普通用户现时身份/组织/确切任务执行权和Input候选绑定是独立准入；服务Task COMPARE、精确委托、当前来源DISPLAY/COMPARISON/MODEL_INPUT/SAVE及批准模型/预算逐项取交集，不自动扩大授权。

命令先以原请求摘要和幂等键持久化可追踪交接回执，再领取现有候选attempt/lease/token并调用现有服务器计算/原子提交链。回执需绑定issuer/human/task/version/Input SHA/candidate/配置服务/原键/实际delegation与attempt坐标；相同键不同载荷或不同主体冲突，不能消费另一任务的迟到响应。具体物理表和回执观察字段在上述权限及执行边界决定后补最小D，不在当前未决阶段写迁移或业务API。

未知交接或执行返回先读取原回执、原attempt与已提交结果；只有已证明未发出且当前合同允许时才可能重交，不能将网络错误等同明确失败后换键重算。已有COMMITTED仅GET恢复；当前INPUT/QUEUED/RUNNING不展示为矩阵完成。候选失败不得覆盖其他已提交候选。命令GET仍核Task READ及逐来源用途，历史回执不替代当前权限。复用既有lease/未知外呼围栏，不能将这个人类入口改为后台领取、定时续租或无限重试；本节不改变取消、正式状态或人工决定。

仍需产品/安全责任依据的最小决定集中为两项：
1. 现有Task COMPARE的批准语义是否同时涵盖普通用户对确切Input/候选发送启动命令，或需要独立发起动作。建议复用当前COMPARE并在每次显式命令时重核精确Input和全部交集，但不能只据已有委托合同宣布已确认，也不复活旧Grant。
2. 当前切片是否采用一次人类请求仅同步交接一个已选候选、断线/超时后保留未知并仅对账的限定模式；如采用，责任方需明确允许的执行时限/用户取消后业务边界。建议先保留未知拒绝盲重发、无后台自动接续。Q-CON-BACKGROUND-03及Q-CON-RECOVERY-04中的未决定部分独立保留，不要求专业评测/评分先完成。

已批准的受管服务选择、权限/用途取交集、原键回执、未知查回和浏览器不持mTLS凭据不重复索取；缺项只门禁新启动命令分支。当前工作台输入/矩阵读取和服务侧已获准执行路径保持，普通用户一键计算仍未实现。本轮固定A包不包含该新增入口，不能把后续源码静默换入已批准包。
## 普通用户单候选显式启动：已确认决定后的详细D（2026-10-07）

依据主计划IMP-CON-COMPARISON-USER-START-D-01及用户补充原件user-supplementary-approval-1418.json六项决定，旧“仍需两项决定”保留为历史，不再现时门禁。实现切片PC-COMPARISON-USER-START-01；主会话负责共享接口/配置/迁移/前端，执行者仅独占新命令存储及其测试。源权、模型批准、正式运行预算不由本D签发。

HTTP命令POST /v2/consultation/tasks/{taskId}/patent-comparisons/{comparisonId}/candidates/{candidateId}/start：正文仅expectedTaskVersion和inputSha，原Idempotency-Key必需；拒绝服务、证书、URL、模型、任意矩阵及未定义字段。GET同路径/start-commands/{commandId}及原键查回都核当前Task READ、确切Input/candidate/actor绑定；不返回受保护正文，只返回回执坐标，结果正文仍走现有逐来源现时读取链。POST现时COMPARE+READ消费现行已确认Input，重核Task版/cancel_epoch、Input SHA和候选；不存在客户端“已授权”字段。

V102单表con5_start_command：command_id、task_id、comparison_id、candidate_id、input_sha、task_version、cancel_epoch、actor_issuer、actor_subject、request_id、request_sha、service_subject、delegation_id、attempt_id、fencing_token、lease_until、status、error_code、created_at、expires_at。原键在Task/issuer/human范围唯一，FK绑定Input和候选、委托、attempt；原请求/服务/身份/输入坐标不可改。状态ACCEPTED、DISPATCHING、RECONCILE_REQUIRED、COMMITTED、FAILED、CANCELLED。无模型正文/第三方原文/凭据。写入Task行锁后重核版本/取消及当前Input；事务内预留命令、事务外真正mTLS交接；原键重复只查回，绝不再次发送。另一新键也不得越过现有同候选ACTIVE/UNKNOWN/COMMITTED围栏。

受管服务/产品HTTPS origin/TLS双向密钥只复用受管部署配置；配置缺项或身份证书失效拒绝启用，浏览器不持证书。HTTP timeout沿既有受管90秒上限，lease从受管部署显式参数取得且有限；未设置时功能关闭而非默认无限。当前Input、Task、用户/服务精确COMPARE Grant、模型批准与各来源有效期取最短可用窗口；领取后的实际lease_until不足模型配置timeout时不得外呼。一次请求只处理一个候选，无定时领取/续租/fan-out。

持久化DISPATCHING在外呼之前；委托仍由现有issueBoundComparison使用真实当前human产生，不把ACTIVE解释为发起权。远端claim使用command_id派生固定幂等键，实际attempt/token/lease回执核Input/candidate与服务后持久化，再同attempt调用已有execute。HTTP异常一律保留原键/attempt并标RECONCILE_REQUIRED，不猜未发送、不换键，不自动重发。GET仅从原键的候选attempt及矩阵回执对账；COMMITTED须同Task/Input/candidate/attempt/fence/reportSha，否则拒绝展示；RECONCILE_REQUIRED不显示成明确失败或可盲重试。

用户取消沿已有Task cancel_epoch/fence，不增加浏览器断线取消；迟到提交由现有事务重核拒绝。命令GET在当前取消代数变化时返回CANCELLED坐标，不能抹除已发供应商调用/费用/原矩阵历史，也不承诺外部撤回。超时与取消分开，操作上下文变化撤下迟到响应；确认写入后的GET失败只恢复读取。Agent完成、人工待审、业务办结仍分开。

并发/失败反例：双请求同键只有一个预留/发送；同键异载荷冲突；错Task/Input/candidate/人类不能查回；预留之后进程中断不重新dispatch；claim回执丢失以原键查候选attempt；execute回执丢失仅查已提交矩阵；撤权/撤源/取消/不足窗口禁止下一次动作；A成功B失败只恢复B；任何阶段缺表/绑定漂移失败关闭。工程测试可mock真实端口的外呼结果，但不能计正式模型/TLS/业务分母。固定A包完全不变，新V102/启动接口不是本次五脚本发布范围。

模型外呼窗口细化：不改变现有Authority记录或Input材料摘要。SourceAvailability用当前核验后的合同/admission/字段用途最短valid_until；PatentDocumentAccess对已授权MODEL_INPUT的确切grant/scope/Task读最短截止并重核当前权威；Permit有效截止取这些与批准/材料期限交集。新模型外呼前租约/Permit/材料均必须严格晚于now+受管模型timeout。窗口不足为已知未发外呼失败，保留预留/失败审计而非UNKNOWN或伪造调用；已收到响应重放不再次外呼。不把此次技术改动替换入固定A。

已知未外呼窗口失败与响应拒绝必须分开：仅首次reserve返回NEW的执行者可对NULL响应的RESERVED做REJECTED，并在同一Task锁/主库事务关闭候选FAILED；不以仅接受RECEIVED的reject路径冒已知失败，不单独提交围栏半状态。重复UNKNOWN预留没有该所有权出口，仍拒绝盲重发。

普通用户启动实现细化（PC-COMPARISON-USER-START-01）：新StartApplication/Controller只接受expectedTaskVersion、inputSha与精确路径候选，POST持久预留后选择受管worker-subject及模型configuration.version，经既有真实mTLS transport调用claim、execute各至多一次。V102部署显式开关默认关闭，缺表/证书/信任根/有限lease或lease不大于模型timeout则启动失败关闭；不是固定A五脚本的一部分。前端既有输入工作台由受管VITE_CONSULTATION_COMPARISON_START_ENABLED发行开关显示单候选显式发起，保持原键、读取对账，不持服务凭据，不自动轮询或重发。新组件及合同文件名在Windows不区分大小写的文件系统下必须不同（ComparisonStart.tsx / comparisonStartContracts.ts）。

实际Worker领取前复用materials.consume与model admission校验精确TLS服务主体、用户委托、原件、独立MODEL_INPUT及MODEL/SAVE用途交集，核剩余执行窗口和当前模型预算；该预算读查仅为领取前拒绝，真正预算仍由外呼前reserve事务原子计数。领取不是COMMITTED。对账GET先核当前Task READ、精确issuer/human/Task/Input/candidate；取消代数已变时仅返回CANCELLED坐标，不为显示取消去读取被失效的Goal或正文；非取消结果仍重核输入和逐来源DISPLAY，并由矩阵闭合验证对账已提交原attempt。失权清空UI，上下文迟到响应不采用。工程mock传输、H2事务与组件测试只证明控制合同，不计真实TLS、模型或专业验收。

刷新/重新打开启动区不丢对账入口：latest只按当前Task READ、精确Task/Input/candidate及实际issuer/subject寻找原键，不让普通用户手填内部幂等键，不返回其他主体命令；无原键时仍重核确切Input/来源读取。浏览器显式启动前先一次性查询既有回执，存在则只展示/对账，不POST换键；另有明确“恢复此候选已有回执”读取按钮。页面刷新不取消命令，RUNNING也可被选中读取自身原回执；新写仍仅QUEUED/允许FAILED且经服务器围栏。已COMMITTED候选在本地选项排除，未知不能“返回选择”解锁重发。没有后台轮询。

### 普通用户启动存储的隔离DM8物理验证细化（2026-10-07）

VER-CON-COMPARISON-START-DM8-01复用已完成的持久5492归属/资源合同，仅新STARTMAIN/STARTEMPTY及各自最小运行账号，先核不存在再初始化实际必要祖先与V095/V098/V099/V100/V102。固定存储及21列schema合同，不改变产品方法；真实验证Task version5与authorizationVersion17不混用、同键重放、dispatch至多一次、精确delegation/attempt/fence/receipt绑定、非法/迟到/非空逆迁移失败关闭、空独占逆迁移及跨schema/运行DDL拒绝。工程数据明确自有，凭据留vault；禁止修改现行CONPROD/CONAUTH、原Source链或固定A，禁止正式API/模型及来源授权。只证明DM8存储工程，不证明受管mTLS整链、正式迁移或新功能发行。

### 普通用户启动NULL围栏约束最小前向修复

真实DM8反例证实V102旧CHECK的fencing_token>0在NULL时为UNKNOWN；attempt非NULL时可错误保留NULL fence，包括COMMITTED。既有D要求完整attempt/fence/lease，不涉及新业务决定。保留已应用V102及原失败，不覆盖原编号；新V103仅ADD CK_CON5_START_FENCE_PRESENT CHECK(attempt_id IS NULL OR fencing_token IS NOT NULL)，与原V102正数/lease/delegation/status检查取交集。不DROP旧约束、不自动填值或修改历史；存在非法旧行时ADD失败，停止依赖功能。U103仅空command历史允许DROP新增约束，非空拒逆，完整历史和旧UNKNOWN保留。启用普通用户启动须当前schema存在已启用新增约束，否则装配失败关闭，metadata探针不改业务数据。H2复现旧弱点，再验证新CHECK拒绝NULL、COMMITTED NULL、零/负围栏、非法历史前向失败及合法行SHA不变；子智能体B在自有5492新后继原件验证，原V102证据不覆盖。A批准的五脚本、包与现行库不变；V103真实目标部署另批。

实际DM8 ALL_CONSTRAINTS通过runtime账号可读，STATUS=ENABLED且SEARCH_CONDITION将IS NOT NULL规范化为NOT (IS NULL)；见v103-fence-successor-01/runtime-dictionary-after.json。启动探针仅接受本确切逻辑的两种已证规范表达，不接受nullable正数式、IS NULL、附加OR TRUE等弱表达；首次探针因字典重写而失败原件保留，修探针后只恢复验证，不重放已经成功的V103。

## 2026-10-07 实际Issuer/Grant/Source安全边界工程接续

VER-CON-USER-START-MTLS-01沿现有R/D，仅在新自有隔离工程环境使用产品Issuer及真实OAuth签发/JWT decoder/当前会话、ProductGrants及水位、Source合同/用途/epoch/撤回、实际受管服务注册、mTLS和持久命令，不改生产授权语义。工程主体与原创输入注明用途，通过既有产品命令登记，不伪造外部人员；部署维护身份依据实际自有证书/CA和隔离引导合同建立。通过真实HTTP控制器创建Task/Goal/prepare/确认Input，不预填COMMITTED、不直接调用业务Service冒整链。模型不获准时必须在实际模型批准门禁停止，不调用任何外部模型；取消/原键对账/现时读取负例不能靠mock权限提供。工程JWT来自产品Issuer安全过滤链，浏览器客户端不持服务证书。H2可证明机械工程边界，不冒DM8正式部署或正常模型业务完成。


### 普通用户启动持久交接竞态最小修正（FIX-CON-START-HANDOFF-RACE-01）

沿普通用户启动现有D及取消/未知结果合同，审查发现bindAttempt为原键GET恢复允许过期后的只读对账，因此不能同时充当新execute发送准入：claim耗尽原命令/租约窗口后，旧StartApplication仍可能发送execute。另有结果读取返回和迟到回执闭合前只在更早边界核授权的问题。不得把修正改成GET要求COMPARE或禁止合同允许的历史已提交回执对账。

新增存储层只读requireLiveHandoff：同既有Task→command行锁顺序，重核当前Task/Input/版本/cancel_epoch、原命令截止、DISPATCHING状态、绑定委托的当前authorization_version/ACTIVE/原窗口；已取得attempt时核相同候选/Input/worker/delegation/epoch/token/lease和当前ACTIVE槽，过期/替换/取消/失效拒绝。每个claim或execute HTTP前先复用现时human COMPARE与材料消费，再核此持久围栏；不持数据库锁跨HTTP。检查后仍可能发生撤回，远端既有领取/模型/提交现时重核仍为最终副作用围栏，不承诺撤回已发外呼。

Start收到迟到结果后在闭合命令回执前重核human COMPARE及当前材料；未知/到期不标明确未发送或自动重发，原键和attempt保持RECONCILE_REQUIRED。GET只按现时READ和来源DISPLAY重新读取确切Input，在绑定/已存在真实回执对账前与返回前重核；取消仅返回坐标，不能为展示取消读取失效Goal。GET无claim/execute/model请求，也不要求COMPARE，不写新矩阵。禁止预填COMMITTED冒真实任务效果；新增持久化负例只采用自有工程ACTIVE/UNKNOWN资料，无模型/CLAIMS正向许可。

唯一修改者主会话负责StartApplication/Commands及既有应用测试、主计划；子智能体仅独立新file-H2交接竞态测试。无新迁移、前端重构、授权或专业决定；专项后固定源码，RBC10当前自有受限隔离环境的全量与构建由主会话统一执行。实际传输/真实Issuer/来源整链仍各按原证据范围，端口替身不能算真实mTLS完整业务。

同切片结构终验发现Commands/新增竞态测试分别超300/400硬限，原verify失败保留。只读SQL委托/ACTIVE attempt核验提取为ProductComparisonStartHandoffGuard，调用仍位于原Task→command锁及当前事务内；纯JSON坐标/摘要校验提取为ProductComparisonStartReceiptChecks，不接授权或持久化端口。测试按竞态用例与独占file-H2生命周期fixture拆分，原46用例及实际关库重开保持。未改变HTTP/数据/身份/来源/专业合同；结构修正后的专项和新未消费RBC11全量单列取证，RBC10不可重放，不继承其源码版本为当前版本。

### 普通用户启动工作台回执消费闭合（FIX-CON-START-CONSUMER-CLOSURE-01）

沿现有启动D及最新Application/Receipt合同，不增加API或服务行为。视图上下文绑定会话/Task/Input SHA及Task版本，变化立即屏蔽旧回执并使迟到响应失效。临时disabled只暂停视图操作和当前异步接收，保留原请求；恢复后原键仅GET，不重新POST。明确401/403/404沿既有onDenied清空结果并锁住本上下文操作，不能在父组件尚未卸载时发起新键。409/网络/不合法回执不宣称成功或已取消，保留原键，只允许查回；没有已知键的latest失败仅继续latest读取。

只有latest显式receipt:null证明不存在已保留命令才可进入本次用户明确启动的预留请求；false/undefined/错误绑定不能当null。回执重核精确Input、候选、原请求载荷/路径/键、类型和状态；过期执行截止不直接推断READ失效，GET仍由服务器当前READ和来源合同裁定，不用客户端时钟禁掉获准历史对账。开始重新GET即撤去旧回执展示，失败不得留下旧COMMITTED或伪装继续成功；UNKNOWN显示原状态，不新键重发。

CANCELLED仅展示坐标/取消语义并允许原键GET，不自动触发矩阵读取，也不提供同取消Input的新启动入口。COMMITTED绑定成功才通知现有结果读取关联；返回候选选择本身不是新的成功回执或自动执行。原件/专业/模型/现时权限仍由服务器核验，浏览器不持服务凭据。本子切片不修改取消业务状态或人工职责，不计正常模型任务完成。

退出：真实组件→现有request/fetch适配的边界负例与绑定测试、完整前端测试、咨询/通用构建和前后verify-all；fetch替身只是组件网络工程，不冒正式HTTPS/mTLS/浏览器业务验收。后端源码不改，继承RBC11版本与范围，不重跑或消费RBC资源。主会话独占ComparisonStart.tsx/comparisonStartContracts.ts及主计划；独立执行者仅新增ComparisonStart.consumer.test.tsx。

消费者结构治理按frontend特征目录TSX硬限220执行（不是Java测试400）。新增消费测试曾223行被实际拒绝，已只拆分网络fixture/hooks到comparisonStartConsumer.testFixtures.ts，断言主体逐字SHA一致；不压行/删例。默认并发完整前端出现两个原执法用例5秒超时；保持源码和超时阈值，用受限maxWorkers=2专项及未筛选完整前端复验，不改全局配置、不筛测试、不把先前失败覆盖。两构建使用独立本批输出目录，临时仅工程构建VITE_CONSULTATION_COMPARISON_START_ENABLED=true随后还原，既有运行配置/发行物不替换；不把构建开关当正式运行批准。

### 明确失败与未知调用围栏闭合技术细化（FIX-CON-COMPARISON-FAILURE-CLOSURE-01，2026-10-08）

沿现有固定逐候选计算/单用户命令D，不增许可、权限、后台或未知外呼恢复。发现StartCommands.knownFailed仅按attempt FAILED投影/释放新命令；但原生invocation RESERVED代表可能已发出，人工或服务显式fail不能抹去该未知事实。明确失败须同确切attempt读取原生围栏：不存在invocation证明本产品受管模型未dispatch；或REJECTED且EXECUTION_WINDOW_INSUFFICIENT对应空响应/空SHA；或REJECTED且MATRIX_RESPONSE_INVALID对应保留响应及SHA一致。RESERVED、RECEIVED、COMMITTED、缺失/错误响应关联不能推断允许新外呼，保持对账门禁。command另核原request=start:commandId及受管configurationVersion，不能借其他attempt失败解除围栏。

新候选领取在Task锁及原失败槽下复核同一原生事实，未知不得换键再领；原键仍保留既有规则，不自动重发。GET状态投影只在已证明明确失败时显示FAILED；缺证仍原RECONCILE_REQUIRED/DISPATCHING，不将用户提供errorCode当权威结果。取消及COMMITTED优先，不抹实际调用或预算。非法模型响应的invocation REJECTED、attempt/lease/candidate FAILED改为同一既有主库事务；回调失权/原件变化或失败全部回滚，不能留半REJECTED。仅执行前新预留owner原已批准关闭路径保留。无新表/迁移/数据补录。工程输入仅反例/自有file-H2与获准隔离DM8，不外呼模型。

主会话唯一修改上述共享产品类；验证含正常已确认未外呼失败、已确认坏响应、未知RESERVED/RECEIVED拒换键、错配置/请求/SHA、原键查询不发HTTP、拒绝末尾失败回滚、A成功不受B失败影响及重连持久观察。三域并行文件分离，全量统一一次源码固定后执行。工程证据不冒真实CLAIMS/mTLS计算或专业验收。

失败闭合细化补充：显式候选fail在事务内先核原生围栏，RESERVED/RECEIVED未知或未完成对账拒绝FAILED写入；已有损坏/旧FAILED槽也不得绕原生围栏换键。初始原键查回只读取坐标/取消信息，当前Input与来源READ通过后才核保留响应SHA，避免未准入先消费正文。现有ComparisonStart只展示服务器绑定FAILED及确定性原因，区分模型未发出/收到响应但未提交，不退款或抹实际调用；未知仍原键GET，无自动重算。主会话独占该组件及测试，前端源码固定后统一全量与两构建。


## 咨询输入/交接与计算准入分层（PC-COMPARISON-ADMISSION-LAYERS-01，2026-10-08）

依据本会话项目用户本轮明确选择及[原始范围记录](../evidence/consultation-admission-layering-20261008/user-product-decision.json)。沿既有咨询R与普通用户启动D，只细化装配、已确认未领取的受管交接回执；无来源/模型/身份批准扩大。

1. 输入、prepare/人工确认/不可变Input仍用现有Source DISPLAY/COMPARISON、精确Task和现时权限；矩阵读取/领域校验/结果存储独立装配，不依赖模型凭据。新显式受管 `product.consultation.comparison.calculation.enabled` 默认false，仅计算部署启用时才装配并核原有模型批准/凭据/timeout；显式错误计算配置仍失败关闭。旧 `comparison.enabled` 保留产品矩阵读取/路由意义，新包不得将原值隐式当新计算批准。
2. Start服务由受管 `start.configuration-version` 固定交接配置版、现有worker-subject/真实mTLS引用/有限lease选择，浏览器不提供。模型批准存在且计算显式启用时，仍绑定真实model configuration.version并要求lease大于其timeout；没有计算装配时，V102 configuration_version存明确 `HANDOFF_ONLY:<交接版本>` 标识，不是ModelGateway配置/批准。模型配置/批准未存在不能虚构，也不提供假凭据。
3. 无计算装配的用户POST只持久预留V102、发放既有精确委托，再至多一次调用真实mTLS的 `/v2/consultation/tasks/{task}/patent-comparisons/{comparison}/candidates/{candidate}/start-commands/{command}/handoff-receipts`。服务消费当前Task/精确委托/Input及DISPLAY/COMPARISON原件，独立重核用户READ及服务COMPARE；接收不读取MODEL_INPUT或发出模型披露，不调用claim/execute。每个请求仍仅一个候选。计算装配已获准时沿原claim/execute链，分别重核MODEL_INPUT/批准/预算，不更改其围栏。
4. V105仅新增 `con5_start_handoff_receipt`：确切command/Task/Input/candidate、原start键与请求SHA、受管交接配置版、不可变回执JSON/SHA及接收时间，FK至V102。接收事务按Task→command锁序重核当前Input/取消代数/存活窗/精确委托，禁止已有attempt或ACTIVE lease；Source/权限首尾回调复核。确定无计算的回执与command FAILED/error=COMPARISON_MODEL_ADMISSION_REQUIRED同事务闭合；仅是命令已确认未领取，不将候选改FAILED/RUNNING，不写attempt/lease/invocation/report。缺表、绑定错、途中失权、末尾异常全回滚。旧V102/V103/V104及历史不改。U105非空拒逆。
5. 人类原键GET仍先核Task READ、同Input/来源；仅在确切V105原件/SHA/受管版/command已知未领取闭合后投影ADMISSION_REQUIRED（attempt/fence为空），不能单凭error或FAILED推断Worker接收。取消投影优先，不为显示取消读取旧Goal/正文；旧COMMITTED/unknown各沿原合同。网络超时可能未接收或已接收，保留原键与请求，不换键重发；后者原GET从持久V105查回，未有V105仍RECONCILE_REQUIRED。
6. 确定ADMISSION_REQUIRED可返回候选选择并由用户显式另发请求；不会GET自动补领、审批后自动执行或后台轮询。新写始终现时核完整权限；未知和未闭合不能解锁换键。前端只展示“服务已接收，计算准入未满足；未领取/未计算”，不关联矩阵，不称任务完成。
7. 迁移只工程验证，不应用现行CONPROD/CONAUTH。必要专项：无模型真实Spring装配及输入/读取可用；显式计算缺批准仍拒；V105持久接收/原键重放/异载荷/源码配置绑定/Source撤回/末尾回滚/取消/断线查回/重连/无新attempt与结果；真实mTLS传输与真实Issuer/Source整链证据分开，CLAIMS用途缺证时正向仍门禁。源码固定后新RBC未筛选全量/前端回归/构建/verify-all；不复用RBC15新源码证据，不打新发行包。

主会话唯一修改共享security/product、V105/U105、前端Start契约及主计划；没有独立已过D写入范围则不启动子智能体。父任务不关闭，正式来源、模型、部署及专业验收各自准入。

发行配置兼容细化：现有固定代际A及历史候选的离线校验不得被新装配选择弱化。部署helper未出现新CALCULATION_ENABLED键时保留旧模型/批准校验；后继分层配置明确填false（或具当前计算部署依据时true），false仅取消模型凭据/批准文件装配要求，不取消身份/来源/TLS/受管服务与有限窗口校验。新分层Start明确配置交接configuration-version；不修改旧配置原件或打包。当前Java实际缺省仍为计算false，后继发行须显式固定开关与代际差异。

同候选显式新请求细化：现有latest优先恢复会阻止已确认未领取后的用户新命令。仅当前界面已绑定ADMISSION_REQUIRED、用户明确返回选择并再次发起时，先GET复核同command/key/Input的ADMISSION_REQUIRED原件，再生成新键POST。未知、回执改变、上下文切换、暂停/失权或仅GET不得进入新POST。不自动补算，不增加权限；服务器继续精确当前准入和原证据检查。此细化属于PC-COMPARISON-ADMISSION-LAYERS-01现有消费者出口。


## 最终产品工作台业务化反馈限定D（2026-10-08）

对应PC-COMPARISON-PRODUCT-FEEDBACK-01；依据既有输入/矩阵读取/单候选交接D及本轮用户最终产品展示决定。R/D只覆盖展示与消费，不授新来源、模型、部署或专业方法。

1. 既有正式咨询入口不新增页面、角色或状态。正常路径不显示幂等键、command/attempt/fence、原始枚举或候选摘要。Start仅使用经prepare/Input绑定验证的文献号和确切版本作为标签；缺少该展示上下文时按原Input候选顺序给可区分名称，不推断文献内容。候选值/原键/请求及绑定逻辑仍保持原字节。
2. 已创建Input主体展示创建结果及逐候选中文状态；比较编号/Input SHA/确认版本仍可在追溯详情查阅，不要求普通用户填写。矩阵主体展示特征原文、真实对应关系/理由/引用和版本，技术引用坐标/SHA移入可展开追溯详情；不删除可核验引用或改变专业语义。已有按分析编号读取保留为历史读取入口，当前已关联输入不要求重新填写。
3. 中文反馈严格对应ACCEPTED（请求已接收，不断言Worker领取）、DISPATCHING（正在交接，不断言计算）、RECONCILE_REQUIRED（结果未知，仅查询）、ADMISSION_REQUIRED（服务接收但未取得计算准入，未领取/未计算）、COMMITTED（结果提交，不代表专业验收）、FAILED、CANCELLED。未知状态不默认成功。候选QUEUED仅待计算，不冒后台调度。
4. 不修改原请求/幂等/回执及授权消费。取消、撤权、上下文切换、迟到/未知结果、新键前fresh GET围栏保持；显示变化不触发POST，不自动重试或模型调用。错误信息采用既有安全错误及可操作中文反馈，不对普通用户展示内部错误码。
5. 退出验证包括各阶段中文反馈、原请求键/attempt/候选SHA不直接渲染、确切文献标签与内部candidate绑定、创建及矩阵关联、真实缺口/部分失败/未知/取消保留、原键GET与权限撤回。前端组件测试不冒真实Issuer/Grant/Source/浏览器整链。仅隔离工程验证，无现行迁移或发布。


## 已核验通用采集字段投影限定D（2026-10-08）

对应FIX-CON-CAPTURE-CLAIMS-PROJECTION-01；继承原件定位、字段投影、来源现时用途交集及准入分层D，不创建新产品或开发专用路径。

1. 既有无schemaVersion采集manifest合同的确切字段投影补齐。显式未知schemaVersion拒绝；manifest/records版本、记录数、records摘要、sourceId、唯一文献及DETAIL原件库存必须闭合。全部库存原件先验证，包含过滤掉的文献；不存在部分成功返回。
2. 原件只投影现有native JSON字段title/abstract/claimsText/full_description中请求字段，使用既有JSON token字节定位；CLAIMS版本严格为RAW_SHA256:原件摘要。授权仍由现时Source/Task/主体/服务用途交集决定，格式适配不产生授权。
3. 固定项目自编工程材料载体若需权项句法编号，仅依据原locator claim:1将正文逐字保留于claimsText中的1.分隔载体，另保留原pointer/原文本SHA/载体坐标可追溯映射；不是厂商采集、真实专利或专业结论。原manifest/payload不改，副本保留不超过原合同及本次2026-10-08 18:00批准。实际生成和使用前重检许可。
4. 此切片不新增表、持久状态、模型配置、后台执行或业务权限。正反例覆盖非空CLAIMS、确切坐标、错版本/SHA/数量/sourceId、重复记录、缺原件、过滤外篡改、未知格式失败关闭。共享文件由主会话独占。
5. 无模型链独立沿已通过准入分层D：服务接收及ADMISSION_REQUIRED原键查回，lease/attempt/模型/矩阵均为0；本字段投影专项不能代替实际Issuer/Source/mTLS链。源码冻结后新未消费完整回归及verify-all。


## 仅DISPLAY来源Scope原回执限定D（2026-10-08）

对应FIX-CON-DISPLAY-ONLY-SCOPE-01。真实链02已经由实际维护身份登记确切工程Source，但旧Scope签发强制QUERY，在合法仅DISPLAY/COMPARISON来源上拒绝。沿既有Source用途交集、精确Task和授权职责，不扩大用户批准。

1. 原维护authorize/reauthorize入口仍核SOURCES+GRANTS同主体、组织、当前Task及Source版本/epoch。仅根据当前具名字段用途的有限有效窗口签发QUERY/SOURCE_QUERY和/或DISPLAY/SOURCE_DISPLAY；至少一项，均缺失则拒绝。无QUERY准入不得签QUERY。COMPARISON仍独立原入口，不隐含MODEL_INPUT。
2. 既有Receipt保持scopeId/scopeVersion/queryGrantId/displayGrantId，未签发用途字段null，不新增状态/开关。原键重放按实际非空Grant独立核现时subject/status/期限/epoch、确切组织/Task/scope/action/purpose，不把缺失QUERY当成功，也不重新签发。异载荷仍冲突，末尾权限和Source围栏不变。
3. V106仅前向将V056的QUERY必需约束细化为QUERY或DISPLAY至少一个；不改旧行/旧回执/旧脚本。旧非空QUERY记录兼容，禁止在已存在DISPLAY-only行的库自动逆迁移。仅新的自有隔离环境应用V106；现行目标、新发行均另行批准。
4. 回执校验拆为小型持久化协作者以保持现有文件大小边界，不替代ProductGrants/Watermark检查、不建立通用框架。无正确物理约束时事务失败关闭，不越权补QUERY。
5. 专项保留既有QUERY-only/双用途、增加DISPLAY-only正常与同键重放/撤权/无用途拒绝；真实链必须再经Issuer/JWT、Source产品入口、prepare/Input与mTLS。源码冻结后RBC19完整未筛选回归、工程构建和verify-all；当前A包、服务和现行库不动。


## 真实原件重读值一致性围栏限定D（2026-10-08）

对应FIX-CON-HANDOFF-MATERIAL-VALUE-01；继承既有交接提交前/后的现时原件、Source、权限、Input、取消和回执原子性D。真实链03/04已完成准备/确认/Input，真实Worker HTTP到达handoff但409；产品Loaded记录内的OriginalClaimText是每次从原件新建的解析对象，record默认equals比较实例身份，不是已批准的原件一致性合同。

1. 比较Input全值、Actor现时版本/Grant代数/期限、束期限、目标全文的确切版本/ref/pointer/原SHA/byte span/span SHA及文本、targetPatent/candidate各自reference/material/authority值。不得只比较文本、只比较ID或只用旧快照；原件仍每次从受控Root按SHA读取并重新授权。
2. 新值比较必须保留store.accept事务内重复重读及末尾重核；真正版本、原件、坐标、权限、取消、期限漂移仍拒绝并回滚。无新增状态、假回执或Model/attempt。范围仅零模型handoff的正常重读比较；不得据此删除其他安全检查。
3. 正向实证必须同链真实TLS/Issuer/Source/单候选Input/Worker接收/持久回执/原键GET/服务重启。专项覆盖相同字节的不同解析实例、改变原件和坐标拒绝；随后新RBC完整回归。现行服务、库与固定包不变。


## 正式工作台直接文献选择限定D（2026-10-08）

对应PC-COMPARISON-DIRECT-DOCUMENT-WORKBENCH-01。用户本轮确认最终产品工作台无需检索即可选择已有获准确切文献；沿既有prepare直接DocumentRef和人类单候选交接D，不改变业务/法律规则。

1. GET /v2/consultation/tasks/{taskId}/patent-comparisons/documents，offset>=0，limit 1..200（默认100）。返回taskId/taskVersion/goalId/goalRevision/inputId/expiresAt/documents/nextOffset。每项key/sourceId/documentId/documentVersion/sourceVersion/rawSha/claimNumbers/claimsText/gap；key为sourceId+换行+documentId+换行+version的SHA。不伪造结果集或Selection，不建立新持久表/状态。只读取当前Task有效来源Scope，逐源核CLAIMS DISPLAY与COMPARISON及主体精确Grant，Task READ和COMPARE及当前Goal两端重核；过期/撤权/来源漂移失败关闭，不返回部分未核实清单。原件和SHA经现有文件/领域读取核验，不用TITLE或QUERY作为许可前提。清单按确切key稳定排序分页；页间是现时重读不是冻结授权，每次prepare再重核版本/原件。
2. 复用ProductComparisonDocuments读取原件及来源授权。清单提取确切原件显式权项编号，不臆测权项1；领域分支不明时保留原文及真实gap，不能拼造可计算材料。缺CLAIMS/非法原件失败关闭。单次清单受既有原件读取预算及最多4000文献库存约束，超限拒绝而非静默截断。
3. 工作台提供已有检索选择与直接获准文献选择，明确切换并使旧准备/确认/Input失效。直接引用的resultSetId/selectionId/selectionRevision/memberId均null；前端验证来源/文献版/sourceVersion/rawSha/key及prepare绑定，服务器仍为授权和引用最终依据。选择及权项由正常控件完成，不要求用户填JSON/SHA。无来源时明确缺口，不假造候选。分页结果核Task/Goal，错绑定拒绝；上下文变更/失权清除材料，迟到响应不得回填。
4. 保持原键写入、未知对账及确认后仅GET恢复。清单没有写入，不赋MODEL_INPUT；无模型交接仍仅ADMISSION_REQUIRED，不产生租约/attempt/矩阵。原mTLS和取消围栏不变。
5. 主会话负责后端/共享配置/主计划/迁移协调/全量；前端可独立负责人，仅ComparisonInputWorkbench、comparisonInputContracts及必要新增前端测试，不并发写共享文件。专项覆盖无Selection正常选择、错绑定/版本/撤源/迟到/切换，真实安全HTTP清单与prepare消费；冻结后新未消费全量、前端及两构建、verify-all。真实浏览器只在批准有效窗口内；源码不部署，V106不应用现行库。

6. 实际浏览器装配核对发现正式/app/consultation和retrieval-workspace只读路径仍绑定model.enabled，且workspace配置把读取与检索执行mTLS凭据一起装配。沿准入分层D细化：正式页面/工作台GET及历史对话读取仅依赖Identity/Source/workspace与其真实保留/数据校验；模型adapter和检索执行workflow继续独立model.enabled门禁。没有模型端口时禁止invoke，无假端口/凭据。GET保持现时权限及原历史引用校验，不因模型缺配置跳过旧对话或来源检查；无检索服务配置的POST明确503，不启动检索。既有有模型部署合同不改变。此为同切片必要装配修复，非新增业务权限。

### 矩阵单报告末尾目标原件一致性修复（FIX-CON-MATRIX-REPORT-TARGET-RECHECK-01，2026-10-08）

沿既有结果读取D，仅修复单报告GET末尾只比较候选材料/Input、未比较目标原文的遗漏。目标读取本身核消息SHA，但不能证明首次校验报告时与返回前两次读取间原文未变。先以实际H2消息原文及合法新SHA在读取边界改变的反例复现，再补与概况读取同一目标原文一致性守卫；不倒填结果、不要求MODEL_INPUT读取旧报告、不修改权限/模型/SQL/前端。保留旧报告及SHA，变化时失败关闭；反例使用原机械组件/受控模型端口，不当真实业务结果或历史三样本证明。
