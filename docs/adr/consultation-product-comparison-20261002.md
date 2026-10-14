# 首条咨询专业技术对照产品设计

版本：D1（2026-10-11补§13依赖判定窄D）；日期：2026-10-02；任务：ARC-CON-PRODUCT-COMPARE-D-01。

## 1. 依据与限定

原需求为只读 `requirements/知识产权咨询助手/专利相似度判断需求文档.md` §1～9（PC-01～09），R证据REQ-CON-R-CORE-12；沿用docs/56 CON-06及docs/69 §3，不重新设计五项协议。项目负责人已授权开发团队确定最终产品专业技术方法及工程基线；Codex负责本技术决定，不代签第三方数据权利、专业金标或业务验收。

此D定义最终文本对照产品，不把来源准入当方法设计前置。对应实现仍依赖主计划IMP-CON-PRODUCT-SOURCE-01；真实CLAIMS原文、逐用途许可、模型能力、质量样本未具备时，相应I/B不得通过。旧TITLE×QUERY授权不隐含COMPARE、DISPLAY、SAVE或MODEL_INPUT。外观设计及数值评分保持各自能力边界，不能用文本分数或检索分数补齐。

## 2. 输入、目的与版本

- 目的为TECHNICAL_SIMILARITY、CLAIM_ALIGNMENT、NOVELTY_ASSISTANCE、DESIGN_ASSISTANCE，对应原需求四目的；只前两项具备文本矩阵方法，新颖性辅助额外要求明确相关日期/地域及该法域的依据版本，缺少时只返回资料缺口，不悄悄切换目的。没有图像/视图及独立规则时外观准备返回CAPABILITY_UNAVAILABLE，不创建文本分析。
- 输入至少有同一组织的taskId、expectedTaskVersion、goalRevision、inputRevision、目标、候选及purpose。目标为用户技术方案不可变原文版或来源中的目标专利精确documentVersion；二者都无或同时给出但关系不明时422要求澄清，不能以检索query自动生成目标。
- 候选保存sourceId/contractVersion/decisionId/sourceEpoch/documentId/documentVersion/sourceVersion/rawSha及选定权项；来自检索时另绑定selectionId/revision/memberId/resultSetId，直接指定不强迫先检索。一个申请的不同公开版本不得互换。
- FeatureRevision绑定目标原文SHA、权项闭包版、purpose、用户确认主体/时间及前一修订；每Feature有稳定featureId、原文跨度、上下文、父特征/权项来源及确认说明。自然语言修订保存用户原文，不冒原专利文本；建议仍不构成确认。候选变更不改变旧FeatureRevision，但新分析输入必须重新锁定候选版。
- comparisonId与taskId分别标识分析批次及原咨询任务；纠正/继续仍为同一Task的新输入/分析版，不重开新Task掩盖版本关系。允许用户独立新建任务，但不得覆盖旧历史。

## 3. 原文与权项展开

原文端口只返回精确文献版、字段、原始字节摘要、解析器版、定位映射、质量信息与实际可读范围。定位是原件UTF-8字节半开区间[start,end)，保存字段/权项号/段落或页锚及该跨度SHA；不在规范化文本上计算原件偏移。引用必须可重读并匹配字节，不以URL或模型复述代替定位。

权项解析保留原文和引用表达式，编号不得仅按行序猜测。对每个所选权项递归展开前项DAG；保留独立/从属层级和继承来源，多项择一引用保留分支，不把多个替代分支的特征强行合并为一个权项。缺前项、循环、歧义或跨版引用阻止该权项完整分析；准备响应列明路径及待确认，用户可按原文确认解析修订但不能确认不存在的前项。

说明书支持只是独立证据，不能自动变成权项限定。每个候选权项对照必须取得选定权项及有效依赖原文；摘要/标题不能替代。OCR只有映射/质量满足时可用；未确认质量不造置信阈值，以无法核证标记并请求校对。任何清洗/拆分保留原始字节，派生文本版本独立。

## 4. 最终方法：版本化证据矩阵

方法标识PATENT_FEATURE_MATRIX_V1。每一候选×每一确认特征有且仅有当前结果行（同一特征允许多处证据）。行绑定inputSha、FeatureRevision、candidateVersion、claimBranch、methodVersion与机器生成版本，不能用报告摘要替代。

| 关系 | 必备条件 | 禁止推论 |
| --- | --- | --- |
| 对应 | 有该候选具体原文定位、原文内容、相同点与逐项理由 | 文本对应不等于法律等同或侵权 |
| 部分对应 | 有可定位证据，同时列出实际差异/缺失子项 | 不把未分析部分当不相似 |
| 已核范围内未找到 | 保存实际分析字段/权项/段落范围、解析版、方法、完成事实与限制 | 不宣称文献绝无该内容或穷尽检索 |
| 无法判断 | 缺原文、质量、解析、含义/上下文或方法支持，明确原因 | 不计0分、不假造否定证据 |
| 未分析 | 取消、排队、模型失败或预算未完成，明确运行原因 | 不标完成、不省略结果行 |

AI只建议Feature/span/关系/理由，不写确认、Grant、复核或正式状态。领域校验器核文献/跨度/摘要、引用路径、行完整性及允许的表达；模型返回不存在的span/字段/Feature或完整范围未实际分析时拒该候选提交。严格字符串相等可以作为词面证据，不能规则推定技术语义等同。用户确认的是特征输入，不自动确认机器对应关系。

报告同时保存机器建议属性及未复核状态；允许作为明确标注限制的辅助技术参考。正式采用或法律相关意见由具名有权人工复核其确切报告版。新颖性辅助按单篇单权项分支列覆盖，不把候选A/B证据拼成一个候选；不输出侵权、新颖性、创造性、有效性、授权或驳回结论。

V1不出百分数。保留评分能力缺口及原因SCORE_NOT_CALIBRATED，数值字段不存在而不是0。后续分母/权重/部分对应/未知项规则及专家样本标定另形成同任务技术设计，不从检索score或模型logit借分；本D不宣称PC-05/08效果验收完成。

完整行合同包括relation、sameAspects、differences、reasoning、uncertainty、analyzedRange、citations和生成主体/时间/版本。未知/未分析行的空证据是缺口，不允许伪造引用；有差异但不能定位时只能无法判断。模型响应保留原件，领域校验失败保存错误而不是删掉失败原件。对未找到，analyzedRange列明已实际处理的所有字段/权项分支/span和未处理范围；分块失败不得说全字段完成。

输入/报告摘要沿用现有产品canonical JSON约定（固定版、按键排序、UTF-8、SHA-256），精确包含目标/特征/候选原件与定位、purpose/条件/方法/模型参数及所有行/实际范围/缺口。数组按稳定candidate/feature/branch/位置排序；实际记录中的ID、父版本、运行事实/回执关系另有包摘要共同校验，不能通过去掉关系字段绕过版本核验。消费者重读整包后核摘要，不从分页拼出未经核验的新报告。

## 5. API、授权与执行边界

路径前缀 `/v2/consultation/tasks/{taskId}`，全部最终产品请求链；不注册旧V0/V1或开发profile旁路。

| 相对路径/动作 | 固定输入与结果 | 授权/副作用 |
| --- | --- | --- |
| POST /patent-comparisons/prepare | 输入版、目标/候选/目的→可用性、权项树、特征建议、待补项、准备摘要 | Task READ；逐源DISPLAY/COMPARISON，模型另核MODEL_INPUT；不确认特征或签执行token |
| POST /patent-comparisons/features/confirm | expectedTaskVersion/prepareSha/前FeatureRevision/确认特征/requestId→不可变FeatureRevision | Task WRITE；原文版仍可读，确认CAS/审计同事务 |
| POST /patent-comparisons | inputRevision/FeatureRevision/候选/方法/幂等键→comparisonId/输入摘要/逐候选进度 | Task COMPARE；固定输入、范围及执行槽，不接受客户端结果/授权状态 |
| POST /executions/{attemptId}/patent-comparison-result | 正式Worker/token/精确comparisonId/candidateId→产品计算并提交的回执 | 现有Worker授权/委托/租约/围栏；请求不是任意客户端上传一张结果表 |
| GET /patent-comparisons/{id} 与 /reports/{version} | 逐候选/完整矩阵/原件引用/版本/缺项/限制 | Task READ及逐证据当前来源用途；撤权不得从历史报告泄漏原文或摘要 |
| POST /patent-comparisons/{id}/retries | 当前版/失败候选id/幂等键→新attempt | COMPARE；只重试失败/未分析对象，成功历史不抹除 |
| POST /patent-comparisons/{id}/cancel | expectedVersion/requestId→取消回执 | Task WRITE；旧围栏失效；已提交行保留 |
| POST /patent-comparisons/{id}/reviews | 精确reportVersion/reportSha/人工意见/action/requestId→审核版 | HUMAN_REVIEW及现有职责/精确资源Grant；服务主体禁止，旧版冲突409 |

上述动作复用ProductAccessPolicy枚举，Task资源动作使用TASK_WORK，来源逐字段用途使用COMPARISON/模型另核MODEL_INPUT，不把Task READ当原文DISPLAY授权。ProductConsultationAccess当前仅人类Task适配，Worker必须复用正式Worker端口，不能把服务JWT塞成人类。专用来源范围/用途授权接口需在实现中按此合同适配，通用Grant不得绕过现时来源范围核验。

启动、原件读取、模型调用前及publish/消费前均核当前主体/委托/Task/DataScope/Source/独立水位。失联或PENDING停止，历史scope绝不成为当前权限。模型端口可用能力、版本/hash、输入字段及实际预算写入attempt；模型不能直接写领域表。

## 6. 领域数据、事务与恢复

领域模块依赖ComparisonDocuments、ComparisonSuggestions、ComparisonPersistence、CurrentComparisonAuthorization端口；应用层适配现有原件、模型、DM8及Worker，领域不依赖厂商SDK。逻辑实体如下，物理表/正逆迁移需实现时复审并按仓库下一个未占号迁移创建，不能占用其他域现有V085/V086。

- ComparisonPreparation：task/input/目标及候选版、可用范围、解析版、建议输出原件SHA。
- FeatureRevision及Feature/ClaimDependency：不可变原文/确认/父修订，权项分支与定位完整关联。
- ComparisonInput：comparison/task/goal/input/FeatureRevision/候选版/用途/方法/参数/范围SHA、保留策略ref/version、expiresAt。
- CandidateAttempt/ReportRevision/ComparisonRow/Citation：父子FK，唯一(report,candidate,feature,branch)、稳定顺序、运行状态及错误、机器原件/模型版/范围/证据摘要；人工修订/Review另存actor/父机器版/前人工版/动作及SHA。
- CorrectionDependency：受影响Feature/候选/权项/行及派生报告引用边，历史不可变；幂等回执绑定主体/task/输入SHA/attempt/fence/reportSha。

确认特征事务锁Task/CAS，插不可变版、审计及回执，复用当前Task版本/跨轮规则，不能保持旧Input现时指针。创建分析事务锁Task/Input，固定完整快照及执行槽；模型推理在事务外，成功候选提交在现有execution.publish业务事务中核当前输入/fence/权限，再原子写attempt/报告/全部必要行/引用/回执并COMMITTED。缺表/引用漂移/重复成员/旧token时全部回滚，不能空回调COMMITTED。

每候选独立attempt；A已提交/B故障为PARTIAL，必要矩阵未齐不宣称全批完成。取消之后旧候选token不能发布；取消不删除已完成历史。相同幂等键相同当前scope返回同一回执，不同输入409；过期、撤权或失效后不借重放恢复访问。时间、状态及授权判断服务端产生。

保留沿用当前Task/原文/结果保留策略，显式ref/version及许可的SAVE/审计边界；不得自己加统一永久保留或以全库最早过期限制新包。原文无SAVE只能保留许可范围内的引用/摘要事实，不存被禁文本。结果/原件不可读不等于撤销历史事件。

现有主库+独立现时库+文件联合备份机制直接复用。恢复核FK及唯一约束、Task/输入/特征/权项闭包/报告/人工父版/回执/fence、原件SHA/locator、历史模型/方法、所有digest。恢复不重推模型、不篡改原机器输出，不用历史Grant/Source水位覆盖独立现时权威；恢复后正常/越界/撤回请求与旧token仍需实际验证。

## 7. 纠正与按需人工

修改F2创建FeatureRevision N+1，沿显式依赖边标F2及依赖F2的权项/行/汇总失效；F1无依赖且文献/方法/权限不变可以以carryForwardFrom引用旧行，不能拷贝并伪装重新分析。候选原件或方法版改变影响该候选全部行；目标全版改变无法证明局部关系则该目标全部相关行失效。分析继续创建新Input及围栏，旧报告不可变。

人工修改为独立修订，不写机器行。再生成机器版不得覆盖人工；新机器与旧人工同时存在但旧人工不得自动审核新报告。正式采用、复核或受理不互相蕴含权限；必要受理能力沿docs/69 §4，不在此实现D中额外确定岗位/职责分离。普通查询不强制对照，普通辅助对照不强制人工受理。

## 8. 故障、预算与验证标准

401未认证；404未知/不可访问Task或报告（不泄漏跨组织存在）；403已知自己范围但动作/用途无权；409版本/幂等/旧token/原件版冲突；422输入、权项依赖或必要材料不足；503当前水位/原件/模型基础设施不可用。候选失败保存稳定errorCode及范围，不把模型故障变零分/未找到。准备阶段可返回结构化缺口，不能悄悄创建成功分析。

预算为版本化工程配置，首版最大20候选、200确认特征、最多4000行（引用分支展开超过即422），服务端逐候选应用既有Task/lease截止及模型窗口/文件大小预算；没有无限重试或丢尾截断。超预算说明可分批输入，不能自动略掉必要特征。性能验收须真实数据测prepare/执行/读取p50/p95/p99、最大矩阵、失败率/超时/内存，未实测不称SLO通过；不借Search的300ms指标作为模型对照指标。

| 验证 | 正例及必须反例 | 证据层次 |
| --- | --- | --- |
| PC-01/09 | 直接/选择入口同对象精确版；跨Task/范围、撤回原文、停用/撤权、旧水位拒绝 | C及正式HTTP I分别留存 |
| PC-02/03 | 所选从属项含前项且保层级；循环/缺前项/择一错误合并/假偏移/越界UTF-8/错误SHA拒绝 | 机械原文C不冒真实权项I |
| PC-04/05 | 每特征有行；未找到存实际范围；摘要代权项/多篇拼接/模型百分数拒绝 | C；真实原件与专业评价I/B独立 |
| PC-06/07 | 外观缺能力拒；A成功B故障仍保A；重试B；取消/旧token/重放/提交前撤权 | C和真实Worker/DM8 I |
| §9纠正 | F2只使依赖行失效；F1沿用有父版；新机器不覆盖人工；旧报告复核409 | C及正式持久化/恢复I |
| PC-08 | 特征完整性、证据准确性、意见合理性分别评估，记录专家差异与样本依据 | 专家金标/允许差异未具备不称B |
| 事务恢复 | 提交缺表整笔回滚；全部行/回执/围栏/文件与双库水位一致，恢复后正反请求 | C、真实DM8/联合恢复I分别取证 |

## 9. 两遍反演与D结论

第一遍按原需求§1→9/PC-01→09正向检查：目的、原件、特征确认、每候选矩阵、分数缺口、外观隔离、部分失败、版本冲突、质量评价及全过程权限均在§2～8落位。未把未标定评分或专家指标写成已完成。

第二遍从失败/历史/权限逆向反演：最初“递归展开引用链”若合并择一分支会新增错误权项，§3改为分支保存；自然语言用户修订若冒专利原文会造证据，§2分离用户原文；来源无MODEL_INPUT却调用AI会越用途，§5单独检查；Task版改动若保旧输入/围栏会重放，§6复用跨轮及锁定；F2修订若拷贝F1成新机器会造执行事实，§7显式父行沿用；人工报告被重生覆盖或自动复核新稿均禁止。以上修正均已进入合同，不靠未来提示词弥补。

结论：仅本首条文本对照产品方法/版本/证据/授权/失败设计闭合，可以据此判本D；不声称算法/接口已实施。物理迁移、来源字段用途、真实原件/模型质量、专家接受、数值评分和外观效果仍须分别实际取证；不更改IMP-CON-PRODUCT-COMPARE-01现有依赖。技术参数由开发团队确定，不作为原业务要求或普遍法律解释；未发现新的业务/法律决定被测试替身代签。

已有实际定位/版本/围栏工程证据为同项目[字段页核验](../evidence/consultation-search-20261002/field-pages-verification.md)、[目标与DM8迁移](../evidence/consultation-search-20261002/field-goal-physical-verification.md)、[字段正式提交C](../evidence/consultation-search-20261002/field-submit-verification.md)。这里只证明可复用的原件定位与事务机制，不称已经运行本对照矩阵、从属链、纠正或专业模型；§8是后续实现必须实际执行的验收用例，不冒已通过测试。D通过仅释放设计依赖，不越过未完成的来源实现或完整验证。

## 10. 既定COMPARISON用途的精确适配

2026-10-04实施细化，不重开原D或扩展目的：Registry字段用途码为COMPARISON，来源范围Grant的动作/用途固定为COMPARE/COMPARISON，与ProductAccessPolicy枚举一致。既有QUERY/SOURCE_QUERY及DISPLAY/SOURCE_DISPLAY保持原语义；没有COMPARISON字段决议或精确Grant即拒绝。准入接受此用途码不是对任何来源签发许可，不自动修改历史决议、签授权、续期或允许MODEL_INPUT。

PC-SOURCE-COMPARISON-GUARD-01先复用现时SOURCE_SCOPE关系读取守卫：Task版、Source契约/决议/epoch/rowVersion、组织、主体、范围版、Grant代数及截止全部精确匹配，独立Grant水位查询前后核验；Task主体活跃性由调用方正式ProductConsultationAccess负责，服务主体由正式Worker端口负责，不能单独把此守卫当认证或执行入口。保持原DISPLAY调用兼容，新增包内requireComparison固定映射，不接受客户端自选用途。正式维护签发及API接线是后续同任务切片，未完成前不称产品可操作对照。机械H2/水位故障C只验证查询约束，不冒真实人员/来源用途批准、DM8 I或业务验收。

## 11. 正式维护精确签发适配

PC-SOURCE-COMPARISON-ISSUE-01复用正式`/v2/consultation/source-contracts/scopes/{sourceId}/comparison/authorize`维护路径、产品配置和证书证明；同一具名主体必须同时满足SOURCES与GRANTS，两个角色组织亦须一致。固定动作COMPARE、用途COMPARISON，不接受客户端动作/用途或附带Query/Display/Model。输入为taskId/memberSubject/taskVersion/sourceRowVersion/sourceEpoch/scopeId/scopeVersion/requiredFields/expiresAt/expectedEpoch，Idempotency-Key另传；requiredFields仅当前已实现TITLE/CLAIMS精确字段，不开放新来源字段。维护身份、组织和每个输入字段参与长度前缀SHA，稳定排序字段，不串接可歧义的自然文本。

仅已有当前Task绑定SourceScope可被此命令使用，缺Scope返回缺口，不借此自动签Query。Task、Scope、来源契约/决议/水位与逐字段COMPARISON准入均须匹配；来源产品决议仍绑定同组织/Task。Task仍IN_PROGRESS且未到期，成员为活跃产品人类身份，Task READ及精确DataScope核验沿已有签发核心，服务Worker授权不伪装成人类。所有所需字段的用途期限必须覆盖新Grant期限。当前范围Grant是整SourceScope的COMPARISON，不宣称新增字段级Grant实体；逐次读取仍逐字段核同决议用途，输入requiredFields和完整摘要入已有Grant审计，以便核查本次签发所需字段。

独立Grant水位change包住主库事务，锁Task和成员，事务内重核来源现时状态/字段与范围，复用id2_grant/current_epoch/event签发、幂等与撤权，不建平行授权台账。重放仍核活跃主体、当前Task/范围/用途、Grant状态及现时来源，不用旧回执复活权利；同请求不同字段/范围/期限拒绝。拒绝不留下半个Grant，水位写入中断按既有PENDING关闭访问。幂等重放只有一份Grant/ISSUE事件，独立水位可按既有受控change审计推进，不把代数推进伪称新增授权。持久化无需新增表或迁移；本细化正向复核固定权限映射，逆向复核跨组织、隐含Query、停用重放和字段期限，沿原D通过，仅释放该签发适配C/I，不放行原文入模或业务验收。

物理核对修正：v850实际揭示V037的ck_con4_field_use未包含COMPARISON，因此上述“无需迁移”仅指无需新Grant表，不能忽略既有用途约束。2026-10-04核当前最大V091后，在同签发切片登记V092/U092__product_source_comparison_use.sql，仅为原枚举增加COMPARISON，不改旧迁移/数据。正逆均先添加临时约束、再替换命名约束，确保DM8 DDL非事务场景不出现无约束窗口；逆迁移第一步先校验全部旧值兼容，存在COMPARISON时失败且保留扩展约束，不清理记录。列/主外键/索引/长度/状态和保留策略不变。两遍复审确认授权意义仍由逐字段决议+Grant决定，DDL支持值不等于来源准入；C实测正向接受、未知值拒绝、带新值回退拒绝、无新值往返，真实DM8物理I另待环境，未通过前不称物理验证完成。

## 12. 用户技术方案原件读取适配

PC-TARGET-ORIGINAL-01是§2目标类型的技术细化，不改变最终输入合同：用户须显式指定同Task中已保存的messageId作为技术方案原文，服务不从检索query/目标对象或模型摘要自动制造目标。内部读取适配复用ProductTaskDirectory及ProductConsultationAccess的Task READ，绑定expectedTaskVersion及con3_message.content_sha/expires_at；响应前重读原文和现时权限/Task版。现时没有授权、消息过期/不属于Task即拒绝；保留旧消息不是自动授权其参与新分析。

精确原件标识TASK_MESSAGE:{taskId}:{messageId}，版为USER_MESSAGE_SHA256:{sha}，/raw_text表示已存CLOB字段定位而不是专利JSON或虚构磁盘文件。SHA和spanSHA取该保存原文的UTF-8编码，byteStart/End为该编码的半开范围；不去空格、不规范化、不把中文/emoji字符位当字节位。拒绝非法代理对和拆半字符，字节预算沿已有goal-retention.max-bytes，不另加开发期限/预算。准备链须显式使用该目标适配，特征用户确认仍另有CAS/不可变版/审计，不由读原文动作代签。目标专利分支继续复用Source DISPLAY/COMPARISON，不由此适配开放。机械C不证明曾有真实用户确认或专业效果。

## 13. 纠正后的不可变依赖判定窄D（2026-10-11）

任务ARC-CON-MATRIX-LINEAGE-POLICY-D-01/PC-CON-MATRIX-LINEAGE-POLICY-01；R=REQ-CON-R-CORE-12。依据原需求§9第6项、§3输入特征依赖、PC-07及本ADR§2/6/7。本节只放行纯领域判定，不放行报告保存/迁移/API/模型或人工分支。责任Codex，来源为当前ProductComparisonMatrixCalculation、ProductComparisonInputs、ProductComparisonMatrixResults和PatentCorrectionState源码；技术结论见docs/02第72节Q-CON-LINEAGE-POLICY-01。

### 13.1 已证差异及不可推断的事实

Input已有previousComparisonId，FeatureRevision有稳定featureId/前版/原文/修订；MatrixResults.Package/Row没有沿用和依赖闭包。PatentCorrectionState的图算法只作语义参考，其Fixture级Artifact/权限不是最终产品依赖证据。

MatrixCalculation.request实际传入完整confirmedFeatures、targetText和单候选claimsText，因此当前每行都依赖该调用的全部特征上下文。不能只比较该行自己的featureId来证明F1独立于已改F2。特征集合增加/减少同样改变上下文；候选B改变不当然影响只消费A原件的报告。更窄的特征重算须另设计、版本化并验证模型输入合同，本节不默改现行请求。

当前native invocation只有request_sha，没有不可变逐行依赖原件。不向历史报告补造独立性，不接受模型或客户端自行声明的dependsOn作为闭包；缺依赖原件的既存报告拒绝LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED，原史/指针不动，不是写全部失效或重新调用模型。

### 13.2 纯领域合同

- Snapshot=taskId/comparisonId/inputSha/previousComparisonId、facts、expectedRows。事实键为类型/稳定ID：TARGET、GOAL、PURPOSE、METHOD、CONFIGURATION各一个global键，FEATURE/CANDIDATE为实际ID；值为64位小写SHA-256。FEATURE摘要覆盖userText、原文版/跨度、父特征；CANDIDATE摘要覆盖文献/rawSha/所选权项和解析分支。此摘要不授资料保留权。
- RowKey=candidateId/claimBranch/featureId，分支非空、正数、严格递增。expectedRows须由适配方根据已核权项图/确认特征生成完整矩阵，不由人类客户端缩减；域核ID归属及既有20候选/200特征/4000行预算。非完整业务矩阵不能借纯结构检查被称为完整。
- SourceReport精确绑定原comparisonId/inputSha/candidateId/reportId/reportSha及每行rowSha/dependencies。只支持FULL_FEATURE_CONTEXT_V1；每行闭包至少含五global、全部原FEATURE及自己的CANDIDATE，可含已证实的额外原事实。报告/行/闭包摘要须由可信适配方重读认证，域输入不是HTTP数据或一个客户端approved标记。缺闭包、未知键/版、错Input/候选、重复行、缺候选必要行、两份父报告竞争同候选均拒绝。
- 新Snapshot必须同Task、独立comparisonId/inputSha且previousComparisonId精确指原comparisonId。依赖摘要改变/消失、FEATURE集合增删或原行不在新行域时列入invalidated。其余原行才产生carryForwardFrom；新行域减去沿用行形成recompute。无已核父报告的行只列待计算，不伪称曾失败/完成。
- carryForwardFrom仅为原RowKey/comparisonId/inputSha/reportId/reportSha/rowSha，不复制机器行、不制造attempt/模型调用/完成事实。结果为不可变集合及稳定candidate/branch/feature排序引用。它是机械候选方案，不是授权或提交状态。

### 13.3 现时消费及尚未放行的接线

域类无DB/模型/身份/Clock或权限写端口。应用须先核现时Task/确切Input及父报告READ、逐原件DISPLAY/COMPARISON/SAVE、来源/授权水位和当前沿用权利，写入及返回前再核；历史Grant或父行引用都不是现时许可。重算另核MODEL_INPUT/预算/委托/fence。

最终依赖原件必须与报告/行/原调用回执在同Task锁业务事务中认证保存。新Input沿用方案须父版CAS/完整摘要/历史关系，旧史零写、失败回滚、UNKNOWN不重放。读取须核新Input及每个确切父报告/行摘要和现时原件，不能从引用拼造新机器回执。当前Package旧摘要重编码兼容、依赖原件保存/FK和新引用事务尚未细化；本窄D不释放接线，不加SQL/假存储。人工版独立，机器引用不覆盖人工或自动审核新稿。

### 13.4 两遍语义复核与出口

正向：B变化只重算依赖B的行；A在证据/现时权利成立时可引用原行。当前全特征请求的F2变化使消费该上下文的行重算，不能假造F1独立；新增/删除行、目标/方法/配置变化有确定分支，旧史保留。

逆向：缺闭包、删F2依赖、跨Task/Input、未知依赖版、伪SHA形状、重复父报告/行、缺行、新旧Input重用拒绝；无历史补录。必要C覆盖确切父引用/影响和待算集合、额外依赖、不可变与预算，不冒原件认证/授权、DM8事务/恢复或专业质量。

窄D结论：纯领域机械合同无新增业务/法律未决，Q-CON-LINEAGE-POLICY-01有源/版本/责任/日期/范围，可释放域类及C。完整持久化、依赖原件生产/认证、版本兼容、正式UI/API和I/B不由此通过；组件出口后立即返回父COMPARE收口最短必要接线，不关闭父任务。

### 13.6 不改历史报告字节的依赖原件编码窄D

2026-10-11，责任Codex；PC-CON-MATRIX-LINEAGE-CODEC-01依赖13.5事实生产及纯域策略。来源为MatrixStore.read先核原package_json字节SHA的现行行为、MatrixResults.Package原合同及本ADR§7。只放行内部无I/O编解码C；不放行持久化、API或历史补录。

使用独立DEPENDENCY_ORIGINAL_V1对象，不向Package加字段，不重编码或覆盖旧package_json/report_sha。对象含task/comparison/candidate/input、reportId/reportSha、原requestSha、FULL_FEATURE_CONTEXT_V1、排序事实数组和完整本候选行数组（RowKey、原rowSha、排序依赖键）。事实必须来自本次Captured；报告原字节的SHA、Package坐标及行域必须一致，各行依赖为五global、全部FEATURE和自己的CANDIDATE。不接受HTTP提供事实或凭摘要形状宣称认证。报告配置/调用/授权闭合仍必须由未来业务事务读取原invocation/receipt核验，codec返回只为绑定材料。

编码先解析并核原报告字节及坐标/完整行域；解码先核独立对象原字节SHA及固定版本，再与本次Captured、原报告字节重新生成的确定对象比对，缺行、额外依赖、漂移request/事实/坐标、未知字段及版本均拒绝。数组稳定排序，不序列化Map的结构化键；原行摘要沿json.sha(row)。缺独立对象的旧报告保持可按既有读取合同读取，但不能进入沿用判定；绝不由读取路径补录。

正向复核：不变报告字节/SHA及确切行引用保持；同材料重复编码字节稳定。逆向复核：即使攻击者重算对象SHA，事实/请求/报告/行或依赖漂移仍拒绝；摘要校验不冒来源认证或当前权限。无新法律/业务决定，内部机械D通过，仅释放该codec及必要反例。完整事务、FK、现时授权读取、新Input父CAS仍未D，不据此关闭父COMPARE。

### 13.7 发送前原件存储适配D（2026-10-12）

PC-CON-MATRIX-LINEAGE-STORAGE-01，责任Codex，R沿REQ-CON-R-CORE-12。来源为MatrixExecution发送前reserve、MatrixStore.commit/read、V100及13.5/13.6。缺口是材料须先于发送同新调用保存，响应后不能回填旧调用；不是新增业务授权。本节仅释放内部存储源码及无DB专项，未释放迁移执行/运行接线、父Input沿用CAS或完整纠正。

RESERVATION_MATERIAL_V1含attempt/task/comparison/candidate/input/request/approval、完整非秘密Configuration原件、排序facts/expectedRows数组，复用Captured，不保存请求全文或秘密。配置摘要必须等于CONFIGURATION事实。新调用同主数据源Task锁事务INSERT：native invocation须RESERVED且响应空，全坐标、请求/批准/配置版匹配。仅原reserve新建分支能调用；旧RESERVED/RECEIVED/COMMITTED无原件拒绝、不补史。未来表con5_matrix_dependency_material以attempt_id为主键/FK invocation，material_json CLOB/material_sha CHAR(64)非空；con5_matrix_dependency_original以report_id主键/FK report，attempt_id唯一/FK material，original_json/original_sha非空。正式迁移版本/恢复目录/schema绑定仍须核验；不自行执行DDL或改变旧候选。保留期限沿原Input/报告/来源SAVE，不借新表扩权。

适配读写均要求实际事务且JdbcTemplate主数据源有绑定资源，再锁确切con3_task；无关事务拒绝。材料读核原字节SHA、固定版本、事实/完整行域/配置，并重核native invocation/attempt全坐标。报告证明保存只使用已保存发送前材料；native report/receipt/invocation/attempt须同事务COMMITTED且报告/响应摘要、输入、围栏、批准及配置匹配，再调用13.6编码。证明读重新核材料/原报告字节及native闭合。缺证LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED，不能造空证明/重发/改史；只INSERT，无UPDATE/删除入口。

调用方仍在读写前后核现时Task/来源/身份/lease；适配无授权/模型端口，不是READ入口。配置只作历史调用证据，不当现时许可。原件重建和原生行/引用完整校验复用原Store/Reader，摘要不替代认证。正向复核发送→响应→同事务报告/行/回执→原件；逆向复核无关事务/跨Task/配置重哈希/缺发送前证/迟到或重复提交均拒绝，旧史不变。无新增业务/法律决定，内部适配D通过。无DB双仅证明调用约束，不冒DM8事务/FK/恢复；实际DDL和持久化回归继续暂停。

### 13.8 正式谱系存储源码、恢复映射与发送接线D（2026-10-12）

责任Codex；沿PC-CON-MATRIX-LINEAGE-STORAGE-01及REQ-CON-R-CORE-12，来源为V100/U100、当前最大V107、MatrixStore.reserve/commit、CalculationConfiguration及consultation-comparison-release当前V2代际。现有13.7未覆盖正式安装与发送接线，本节解除这一工程设计缺口，不解除用户暂停。仅编写迁移/接线源码及无连接组件；SQL源码保存不等于执行DDL，实际DM8、H2 DDL、候选构建、发行、模型调用均不执行。

- 正式增量固定V108__product_comparison_lineage.sql/U108__product_comparison_lineage.sql，业务依赖V100；V107属于身份恢复的独立增量，既有安装若要求它仍独立核验，不靠本比较代际宣称身份升级完成。只新增13.7两表及各主键/唯一键/外键，不ALTER旧报告或回填历史。material指向invocation；original指向report及material，attempt唯一；报告与调用同一性由原native闭合及同主数据源事务保证。runtime最小权限仅两表SELECT/INSERT，无新身份或转授权；真实权限声明/安装另按原安装门核，不改已安装674原件。
- U108在任何DROP前分别COUNT两表，任一非空即RAISE_APPLICATION_ERROR；只有停止全部writer且两表均空才允许先original后material移除。无响应材料也属历史，不能因报告为空就删除。DDL非原子，不对部分创建自动重放/逆向；未知或部分状态保持现场，须原安装修复门逐项核实际目录。非空采取forward-only修复，绝不删除依赖证明以便旧包回退。
- 复用single-database-migration-plan精确目标/schema/SQL SHA/完整库存/已应用清单：未来CONPROD增量声明两表和完整约束对象、V100前置及非破坏证据；未知对象或部分状态拒绝。复用restore-catalog原生完整对象、列、PK/U/FK有序两端和完整数据摘要，不创建新恢复工具或降低现行profile。新保护必须同时包含Input/attempt/invocation/report/receipt及两表原CLOB字节/SHA、引用闭合和同点围栏；独立恢复须全目录/权限/数据比较及逐项原件重读。旧266表或旧认证包不外推覆盖新表；实际保护恢复尚未验证，责任Codex。
- 独立比较发行代际SUCCESSOR_MATRIX_LINEAGE_V3继承V2固定集合，追加V108/U108原字节SHA及Facts/Codec/LineageStore、MatrixStore/Execution/SchemaGuard/CalculationConfiguration必要类。旧FIXED_A/V1/V2集合保持原件不变；含谱系必要类的包不能冒旧代际。校验仍仅离线源码/包合同，不固定或构建候选，不证明安装、真实身份/Source、恢复或专业验收；其他身份/域安装增量仍由正式整包安装链覆盖。
- 原Execution从已经consume/permit验证的Loaded、真实exact request和完整config生成Captured；新reserve同Task锁/主事务先INSERT invocation，再INSERT材料，再复核current，提交后才dispatch。已RECEIVED仅使用已有材料且与当前Captured相等，不补史/重新调用；旧RESERVED未知结果维持拒绝。commit在原报告/行/引用/receipt/围栏及invocation转COMMITTED后同事务INSERT证明，再做原终末授权及native读取；任一失败回滚本事务，不删除已收响应。计算装配对两新表做零行结构探测，缺表关闭计算；未开启计算的原Input/历史读取不被新表阻断。旧COMMITTED回放与原报告只读保持原合同，但不得据此用于纠正沿用；后续父Input/CAS与现时沿用读取仍另核D。

本节技术问题已有源码级来源、版本、责任、日期、范围及结论，不是追加业务批准；仅释放上述源码及受控JDBC/无DDL反例。退出观察为新reserve/commit的顺序与失败拒绝、旧调用不补证、缺结构关闭计算、独立代际精确SQL绑定及旧代际不可冒充。无DB反例不能证明真实事务原子性/FK/恢复，完整局部重算和父COMPARE仍未交付。

13.8源码绑定修复D（2026-10-12，责任Codex）：首轮后置全检2048项中4项明确GRANT_SOURCE_CHANGED/MatrixStore原SHA失配而退出，尚未走后续治理；原件assembly-post-governance.log保持。原application-capabilities的19条MatrixStore SELECT定位仍是修改前版本。只允许源码模板这19条path不变的sha256及line机械同步：旧→新17→22、22→27、29→43、72→89、76→93、84→101、88→105、93→110、97→114、107→124、109→126、113→130；分别仍是五表启动COUNT、调用预算/响应、原报告/行/引用/回执/闭合查询及Task FOR UPDATE。旧/新对应原查询正文和方法语义保持；JOIN延续原有同坐标闭合，不把新lineage读取当现有新表授权。除这19个摘要/定位外cap逐叶严格相等；安装源码模板仅capabilitiesSha256一个全文聚合引用改变，旧15账号/674步骤/对象/权限/迁移/检查/数组重数及全部其他字段保持。不加两新表权限或V108至已安装profile，不改primary或历史原件。原cap与安装模板字节先保存至assembly-capabilities-before.json/assembly-installation-before.json；修复专项须断言精确19变化和对应SQL语义、源模板仅一个字段变化及原源SHA闭合，再重跑必要后置全检。该修复只消除当前源码编译引用阻塞，不降低权限检查，不释放真实安装。

绑定修复同批补证：受影响专项完整枚举所有runtimePrivileges源摘要后，确认另有19条SchemaGuard SELECT摘要仍旧；其19个Shape行11—30均未移位、表/列/原V094—V101版号及零行语义不变，只刷新此路径SHA，严禁新增V108对象授权。源定位专项同时核MatrixStore查询锚点与SchemaGuard原Shape锚点。最终允许变化全集为MatrixStore19 SHA+19 line、SchemaGuard19 SHA，共57叶；安装源码模板仍仅一个聚合SHA。其余字段严格等于保存原件；首轮专项仍4红原件assembly-source-pins-tests.log保留，不将先遇到的源失配误当唯一路径，修复后须完整枚举再核。

### 13.9 现时权限下直接父证明读取窄D（2026-10-12）

责任Codex；来源/版本为本ADR D1/13.1—13.8、当前InputApplication.read/bind(historical=true)、PreparationLoader.load、Documents.read、TargetReader及MatrixStore.read源码，R沿REQ-CON-R-CORE-12。ARC-CON-MATRIX-LINEAGE-READ-D-01先放行PC-CON-MATRIX-PARENT-EVIDENCE-READ-01内部认证读取及无连接组件；完整沿用投影/API/UI仍未放行。这消除历史版本读取的工程歧义，不新增业务授权。

- 当前Task必须IN_PROGRESS、未到期且现时READ通过。新Input必须同Task、确切current-pointer、taskVersion等于当前Task；直接父ID只能来自新Input.previousComparisonId且不能自指。两版均复用InputApplication.read认证不可变封套/确认特征/原材料和现时权利。历史父版不要求旧Task版本等于当前版本，不删除MatrixReader.require或consume的现时版本守卫；用户技术方案原件由历史PreparationLoader以当前Task版本读取确切message并绑定旧材料摘要，不把旧版本授权复活。
- 读取在同JdbcTemplate主数据源实际事务及Task行锁内执行，包含零报告情况；无关事务拒绝。两版完整Preparation材料均重读，逐原件DISPLAY/COMPARISON沿Documents现时校验，并核同Source的CLAIMS/SAVE。Source/授权水位、材料、Task或current-pointer末次变化/撤回/到期即拒绝，无历史Grant替代现时许可，无COMPARE/MODEL_INPUT/模型批准或密钥依赖。
- 服务端枚举父Input全部报告，不接受客户端缩减列表；每候选至多一份，未知候选/重复报告拒绝。父候选COMMITTED但缺报告拒绝，不当成未计算；没有已提交报告的候选可以保留无父结果事实，不重发未知调用。每报告复用MatrixStore.read核原行/引用/receipt/native调用围栏闭合，再读取原package_json字节和report_sha，与已认证Package相等且原字节摘要相等。原字节直接传LineageStore.read，绝不canonical重编码旧报告。
- 发送前material中的完整非秘密配置只重建该次历史请求/事实：以现时重读的确切父原件和旧Input调用Facts.capture，与原material捕获严格相等；再由Calculation.validate重建原response对应的完整矩阵，与报告原行完全相等。LineageStore/Codec再次认证原证明及完整行域，错Input/request/configuration/行/依赖或缺证明均拒绝、不补录。返回仅内部两代已认证Input及每报告Snapshot/SourceReport，集合不可变；不生成新报告/attempt、沿用状态、人工结论或模型许可。
- 返回前重读两版Input、全部材料、报告集合及每份native报告，并末次核Task与current-pointer；所有到期仍按原Input/特征/来源/报告期限，不延长。事务只含SELECT/FOR UPDATE，不能写当前指针、历史原件或业务状态。控制JDBC/事务回调反例只证明调用与拒绝合同，不证明DM8隔离、原子性、实际权限或专业验收。

正向复核=旧父Task版本可以在现时READ/Source成立时认证原证明；无模型权限仍可读。逆向复核=无关事务、跨Task/错误父关系、current-pointer竞态、缺COMMITTED报告/证明、重复候选、原字节或配置/完整行域漂移、Source撤回及末次到期均失败关闭。该内部窄D无新增业务未决；公开投影的受管新配置来源、不同父调用配置与完整carry/invalidated/recompute聚合须下一范围D，历史配置不能冒新计算配置。迁移/真实事务/发行/候选/DDL/物理暂停保持。

13.9末次期限反例补证：read-red-components.log/read-red.xml实际18项1失败，证明最后READ回调消耗余下期限时仍返回，已将最终到期检查置于最后READ及current-pointer之后；20项修后原件read-green.xml保留。另据ProductSourceAvailability.useUntil源码确认SAVE截止独立于Documents的DISPLAY/COMPARISON截止，读取两次保存每Source精确SAVE期限并比较，最后按所有期限拒绝，不将SAVE延到Input/展示期限；补充反例见read-final-components.log/read-final.xml。首次测试编译漏静态常量导入原件read-initial-components.log保留，不冒业务红。

返回父投影步骤的有界核查结论：当前ModelAdmission.approval()只提供绑定确切Task/Input/candidates及有效期的模型披露批准，配置来自其中configuration；CalculationConfiguration只在calculation.enabled且该批准验证通过后装配执行。原MatrixReader/Controller没有独立受管新配置读取端口。故本次历史配置只能认证父调用，不能用该批准给普通READ新增模型权限，也不能用历史配置假作未来配置。不需要新业务/窗口批准；剩余为Codex工程D：明确新受管配置来源及输入绑定、缺配置时保持原报告READ可用但依赖沿用分支门禁、同父不同候选配置的逐报告完整集合聚合，再开放正式投影API/UI。当前不编码未过该细节D的公开沿用行为、不固定发行候选。

### 13.10 当前输入沿用投影/API/UI窄D（2026-10-12）

责任Codex；来源为本ADR§7/13.1—13.9、现行ModelAdmission.approval及CalculationConfiguration受管ref/SHA、Facts.capture和正式MatrixResults工作台，R沿REQ-CON-R-CORE-12。本工程决定不新增模型、来源或维护授权；释放PC-CON-MATRIX-LINEAGE-PROJECTION-01源码及无DDL组件，实际运行仍分门。

新配置只来自部署选择的既有product.consultation.comparison.approval.ref/sha256所认证原件；仅调用approval读取元数据，不调用require、MODEL_INPUT、Source MODEL、凭据或Gateway。必须有效、HTTPS/原协议且精确绑定当前Task/Input及全部候选集合；历史配置只认证历史调用，不推作新配置。该装配不依赖calculation.enabled，缺失/过期/错绑定仅拒绝新lineage读取，原results/reports和无模型工作流不变。重算仍独立按原执行许可，不由投影触发。

复用ParentEvidenceReader拥有的主事务/Task锁及全部认证。事务内按每个现时已认证候选原件复用Calculation.request（只构造字符串，不发送）和Facts.capture，核各候选生成同一完整新Snapshot。每份父报告以自己的历史Snapshot与新Snapshot单独调用领域plan，合并沿用与失效，拒绝重复候选/报告/沿用行及跨父坐标；全局recompute=新expectedRows减全部carried，不能合并各报告recompute。原件、两代Input/Task/Source/父证明按13.9末次重核，配置原件末次重读比较；全部有效期限取最早，最终权限读取后再核到期。缺证旧史拒绝零写，无父报告则全部待算。

公开GET /v2/consultation/tasks/{taskId}/patent-comparisons/{comparisonId}/lineage，沿已有JWT/Task READ匹配，不添加开放路由。只返回两代Input坐标/摘要、新配置版/摘要、最早expiresAt、排序expectedRows/invalidated/recompute和原carryForwardFrom；不返原文、配置endpoint、批准正文、秘密、调用或新COMMITTED状态。身份拒绝保留401/404，依赖或配置不可认证统一409/no-store，不披露底层资料。

工作台沿正常结果读取后显式GET该投影，不自动新增请求、不POST或消费预算。浏览器核Task/当前Input/直接父不同坐标、摘要、期限、唯一完整候选×分支×特征行域、沿用与待算互斥且并集等于expectedRows、精确父引用；显示沿用参考/已失效/待重新计算，原行不伪作新机器提交。401/403/404走现有清空/失权出口；409不清除正常报告。Task/token/Input/禁用/卸载或迟到响应清空投影，期限到达清空，不展示缓存。运输校验不冒权限/专业证明。

正向=不同候选历史配置逐份判定、完整行域减沿用、新候选待算及正常报告READ独立；逆向=错Input/候选配置、配置漂移/过期、末次READ消耗TTL、重复父引用、遗漏行/交集、迟到及失权均拒绝。仅只读投影D通过；不实现调度/新Input修改/机器重算/人工评审，真实DM8事务/安装/部署/真实流及专业验收未交付，所有暂停维持。

### 13.11 受影响候选的显式重算消费窄D（2026-10-12）

责任Codex；R沿REQ-CON-R-CORE-12，来源§7/13.10、当前Codec每候选各行同一完整FEATURE/global/本候选依赖闭包、Calculation完整候选请求和StartApplication/Commands原键/围栏/委托/未知对账。此工程D释放PC-CON-MATRIX-RECOMPUTE-START-01源码与无DDL组件，不增加模型/Source/维护授权。

现有原件编码认证后，一个候选的新行域必须全部沿用或全部待算。只有完整expectedRows中该候选非空且全部在recompute、无carried时才允许受影响模式启动；混合分区拒绝，不能切特征请求、扩大全部候选或另造模型方法。新候选无父报告按完整待算候选处理。仍一次明确人类操作一个候选，不引入批量调度、后台Worker或自动重试；全部可沿用时无启动操作，不冒本Input已COMMITTED。

最小增量POST沿现有候选start-commands的/recompute子路由，字段expectedTaskVersion/inputSha/configurationSha，Idempotency-Key沿原约束。先按原requireComparisonActor核当次COMPARE人类，查原键元数据：原键存在则交原StartApplication.start验证确切Task/Input/候选/版本并只读对账，不要求重新认证父SAVE/新配置或再dispatch，避免到期阻断未知结果查回。新键先读13.10完整现时投影，核确切Task/Input/configurationSha/候选全待算/期限，再交原StartApplication.start；不保存第二份启动状态，不改原Start、SQL、命令摘要或回执。原启动仍核consume/当前Task/取消epoch/新指针、reserve/dispatchOnce、正式服务/配置/委托/lease及现时MODEL_INPUT/Source MODEL/SAVE/预算；投影READ和配置摘要不是执行许可。配置来自相同部署ref/SHA原件，不可同版偷换；实际发送前原ModelAdmission和材料重读仍必须通过。投影/新输入变更、过期/失权或缺证拒绝，身份拒绝保留原401/404；其他不暴露资料的400/409沿既有模式。

工作台复用ComparisonStart与原GET latest/原键read：增加所选候选/受影响候选两模式。受影响模式显式GET当前结果及lineage，核当前Input、完整分区和期限，再只列整候选待算且原Input状态允许的候选；提交带该配置摘要至/recompute，原键GET仍用原start-commands。令牌/Task/Input/模式/配置变化拒迟到，投影到期撤去新启动；失联/未知只查原键，不根据投影换键。确定FAILED/ADMISSION_REQUIRED的显式新键仍按既有原回执确认合同，无新retries接口。原普通单候选模式及普通结果READ保持独立，缺配置不冻结无模型原入口。服务端再核，不以浏览器列表作授权。

正向=改变B只启动完整B，沿用A无新报告/调用；新候选单独待算；实际正常提交只由原围栏链产生。逆向=混合/遗漏/非待算/错配置Input/期限/未有当前COMPARE拒绝，旧UNKNOWN原键或响应丢失仍零重发，模式切换须通过原latest发现原命令。此为源码依据的技术决定，不确认模型实际预算或专业效果；最终纠正的父结果整合/人工修订/真实事务和运行验收仍未交付。部署、DDL、候选构建和物理重试暂停保持。

13.11当前完成与父差异分开：lineage.recompute是相对父证明的差异集合，不是当前候选运行状态；工作台同时复用已核当前results，将本Input实际COMMITTED/CANCELLED候选排除新启动，更新选择使用的当前状态而不改不可变Input。RUNNING/未知仍允许原latest查回，不能据差异换键或重发。服务端最后由原StartCommands.available/围栏状态拒绝已完成或活动执行；该限定消费未将父引用伪装成本Input矩阵，也未完成父结果整合。

### 13.12 当前Input绑定的直接父矩阵查看窄D（2026-10-12）

责任Codex；R沿REQ-CON-R-CORE-12，来源本ADR§7/13.9—13.11及当前ParentEvidenceReader.project、MatrixStore.read、MatrixResults和正式结果页面源码。本技术决定只释放PC-CON-MATRIX-PARENT-VIEW-01源码/无连接验证，无新增业务授权。

- GET /v2/consultation/tasks/{taskId}/patent-comparisons/{comparisonId}/parent-reports/{reportId}仅接受当前Input坐标和报告ID；直接父只能从已认证previousComparisonId取得。复用13.9主数据源事务/Task锁内全父报告认证及末次Input/指针/READ/Source/TTL重核，不放宽MatrixReader.require或consume旧版本守卫。不需未来配置、模型批准或MODEL_INPUT；缺谱系证据拒绝，不补史。
- 在project回调内匹配唯一已认证父证明reportId，读取同父native Report及package_json原字节并核原SHA与证明一致；返回当前task/comparison/inputSha、previousComparisonId/parentInputSha、最早expiresAt、父版confirmedFeatures、原Report及originalJson。父特征使用父Input而非新特征文本；原件字节不canonical重编码。回调值只在原终末重核通过后交付。错误身份沿原401/404，缺依赖/未知报告/漂移统一409/no-store，不泄漏底层异常。
- 页面只从已认证沿用引用显式查看，不自动请求。请求绑定当前Input及确切父报告/候选/原SHA；浏览器核原UTF-8字节SHA、JSON内容与Report.result一致、原回执、行摘要/权项特征与引用绑定，复用原矩阵运输/行校验和展示。父报告全文单独标为父版本参考；沿用关系与本Input已提交结果分开，不生成新COMMITTED/人工结论。父特征不替换当前特征。
- Task/token/Input/禁用/卸载或新读取使旧请求失效，迟到响应不得展示；投影或父报告最早期限到达清空正文，异步摘要校验完成后再次核期限。401/403/404沿既有全结果清空出口；409只撤去父查看，不抹正常报告。历史正文同现时材料权利读取，不因旧授权继续显示。

正向=旧父Task版本在现时READ/Source成立时查看、原package含空白仍保持真实SHA、当前结果独立。逆向=跨Task/Input/间接父或未知报告、原字节/行漂移、末次权利/TTL变化、迟到及换Input拒绝。无新SQL/状态/存储/调用/调度；验证仅控制端口、JDBC回调和浏览器运输，不冒真实DM8/身份Source/TLS/专业验收。该范围无剩余业务未决，窄D通过，暂停保持。

13.12父结果整合限定补充：返回父COMPARE核查发现lineage.recompute仅为父版差异，不能把本Input已COMMITTED的差异行继续称待计算。复用正常results已认证候选状态，将该差异集合分为本版已提交、已取消和仍待算，原carried始终为父参考，当前COMMITTED只来自原结果读取而非父引用；不改投影/数据库/启动，不自动请求。本批只增展示分区及正逆消费测试，不借此宣布真实纠正链验收。

### 13.13 多代沿用逐跳机械策略窄D（2026-10-12）

责任Codex；R沿REQ-CON-R-CORE-12，依据原需求专利相似度判断§9第6项、§13.1—13.12及已保存两次只改B诊断。PC-CON-MATRIX-ANCESTRAL-POLICY-01仅释放纯领域策略及无I/O反例；正式多代Reader/API/UI详细D尚未通过，不能据本节开放间接父读取。

- 应用认证层提供从原生报告所属Input到当前Input的完整路径，按previousComparisonId逐跳绑定；2至64个不可变Snapshot为工程预算，超限拒绝而非截断。节点必须同Task，ID及Input SHA不重复。原生报告完整行域、依赖版本及坐标沿单跳策略校验；纯域对象不证明原件认证或权限。
- 每行必须在每一跳的完整行域及依赖事实中保持不变，才保留最初comparisonId/inputSha/reportId/reportSha/rowSha。任一跳失效后不能因后来改回复活；删除再新增、特征集合或配置变更后恢复也失效。当前待算=当前完整行域减存活引用，失效集合保留原始行；不复制报告、不重标祖先为直接父、不伪造中间COMMITTED。
- 复用plan的完整原报告校验和依赖比较。不同候选可有不同原生起点；应用仍须选择该候选最近且已认证的原生报告再逐份合并，不能用远祖覆盖较新原生提交。单报告策略不承担该应用选择或全局聚合。
- 中间CONFIGURATION须来自该Input及适用候选绑定的真实配置证据。当前Envelope/Preparation未保存独立预期配置；另一候选调用material不能自动证明本候选的中间预期配置，不可用祖先或当前approval倒填。缺配置事实拒绝；本批不新增配置档案，不将GET改成写入。
- 正式接线仍需原主事务/Task锁内认证所有节点Input/原件/Source DISPLAY、COMPARISON、SAVE、原报告及配置，末次重读全部节点/指针/READ及最早TTL，前端诚实绑定祖先坐标。该认证来源与运输详细D仍由Codex收口，原直接父API、模型许可、迁移和权限不变。

正向=只改B两次时A保留原报告坐标、B待算、单跳兼容；逆向=中途变更后改回、断链/跨Task/环路/重复SHA、缺配置、错原坐标/证明/漏行/预算拒绝。此D仅解除机械策略歧义，不声称完整纠正、真实DM8、部署或专业验收通过。

### 13.14 中间Input完整批准原件配置认证窄D（2026-10-12）

责任Codex；R沿REQ-CON-R-CORE-12，来源§13.13、ModelAdmission.Approval、LineageStore.Material.approvalSha及ProductSourceEvidenceFiles.verified源码。仅释放PC-CON-MATRIX-ANCESTRAL-READ-01中的内部配置证据读取与无DB反例，完整多代Reader/公开运输D仍未通过。

已认证的另一候选material不能单独证明本候选预期配置，但它固定的approvalSha可定位发送时实际使用的原始批准文件。原文件若精确绑定同Task/Input SHA及该Input全部候选，且完整Configuration与该material一致，则原批准本身（不是候选调用的推断）可证明共同配置。历史文件reference仅由受控部署选择提供，不能从用户请求或批准文件自授；SHA必须取已经由LineageStore.material认证的approvalSha，复用现有证据根/大小/原字节摘要守卫，不canonical重编码文件以产生新批准。

内部configurationEvidence只返回Configuration/evidenceSha/最早expiresAt，绝不返回Permit、消费预算或调用MODEL/Source/权限端口。输入须先经InputApplication.read，material须先经拥有事务内LineageStore.material和原报告认证；该方法另核Envelope自身SHA、Task/Input/comparison/previous坐标及material候选在Input内、完整候选无重复且与批准精确等量、配置全字段/HTTPS/原协议。只绑定标识不替代native报告/行/请求认证。

沿现行approval的负责人/版本/时序/有效期/用途/数据类别验证；历史原件缺失、SHA漂移、错Input/Task、候选范围不全、同版不同配置或已过期均拒绝，不恢复过期范围。expiresAt取Input与批准最早期限；调用者仍须取报告/原件/现时Source/READ等所有期限最早值，并在最终返回前重读原件及到期复核。读取一个旧原件不改变当前部署的approval.ref/sha，不把历史批准用于新的模型调用。

正向=同Input全部候选的真实批准原件支持共同配置事实；逆向=只有B权限或只有B material而无原文件不得替A证明，跨范围/错摘要/错完整配置/过期/晚批准拒绝。无需新增迁移、模型批准或档案写入。后续工程仍须固定部署侧历史reference的精确选择、所有节点/报告/原件末次重读和祖先运输坐标，不提前开放多代API。

### 13.15 多代原件读取与真实祖先运输D（2026-10-12）

责任Codex；来源版本为§13.13—13.14、原需求§9第6项、现有ParentEvidenceReader/InputApplication/LineageStore/ModelAdmission及前端原字节绑定源码。范围仅PC-CON-MATRIX-ANCESTRAL-READ-01，不新增表、调用、批准、档案写入或子任务；本节详细技术D通过，运行暂停保持。

- 部署属性`product.consultation.comparison.historical-approvals`为JSON数组，每项仅`sha256`和`reference`，最多1280项。解析后不可变，重复SHA/引用、未知字段、非小写64位摘要、越界引用或超预算拒绝。它只是证据根内文件选择，不是批准者；实际期望SHA始终取已认证native material.approvalSha。空映射兼容直接父读取；需要中间配置时缺映射拒绝。不构建实际部署配置或候选包。
- 在现有拥有事务和Task锁内先认证当前与直接父，然后仅当当前候选尚缺最近native报告时按previous遍历；最多63历史节点（加当前共64），同Task、ID/Input SHA不重复，超限/环路拒绝，不截断。访问节点的全部native报告、COMMITTED完整性及原件/请求/行/证明继续原认证；每候选只选最近native，较远原生结果不能覆盖最近原生结果。
- 对祖先到直接父的每个中间节点，须至少有已认证native material，通过§13.14原批准完整候选及配置认证形成共同配置；全部native批准配置一致才机械构造该节点完整Snapshot，仅构造已有请求字符串并核事实，无模型调用。无native锚点的中间节点拒绝，不从当前或祖先倒填。原生起点Snapshot来自原报告证明，保留真实原始坐标，逐跳planAncestral失效后不复活。
- 终末重读访问节点Input、Source及期限、native枚举/报告/原字节/material/证明、历史批准原字节与共同配置，再核当前指针/READ、回调配置及最早TTL。任何变化或恰到期均拒绝；同事务不写数据库。期限包括所有访问节点Input/资料/SAVE/native报告/中间批准及当前投影配置。
- lineage与原报告GET都附`ancestry`，顺序为直接父到最远访问祖先，各项comparisonId/inputSha/previousComparisonId；直接父字段仍只表示直接父。GET另给sourceComparisonId/sourceInputSha，特征取真实原生所属Input、originalJson保持原字节。前端先核有界连续/无重复祖先坐标，再要求引用及报告result/receipt绑定同一真实source tuple；不把远祖重标成直接父，不把沿用当当前COMMITTED。不改变原路由、错误码、no-store或迟到/撤权处理。

验收正例为两次仅纠正B且各中间Input有真实共同配置锚点时A沿用最初原报告/行，B进入差异且零新增A调用；反例含缺映射/原件/native配置锚点、冲突配置、断链/环路/预算、原字节/行漂移、Source撤回/TTL及迟到。组件端口和原文件验证不冒真实DM8/部署/专业验收；没有剩余技术来源未决，不重复索批。

### 13.5 现有计算材料的事实生产窄D

PC-CON-MATRIX-LINEAGE-FACTS-01仅机械投影，沿本节纯域合同，不授原件保存或模型权利。输入为现有已核WorkerMaterials.Loaded、受管ModelGateway.Configuration及原新计算reserve的requestSha。先核完整Envelope自身SHA、Prepare中的target及所选候选MaterialDocument与Loaded当前原件、Feature原文映射及MatrixCalculation.snapshot完整分支一致；e.candidates必须与不可变material.candidates逐个key唯一等量匹配，缺口/漏成员/重复或未知方法拒绝。事实/完整行域生成后重新调用原MatrixCalculation.request仅构造字符串，SHA必须等于所传reserve requestSha；不invoke模型、不另造请求模板或额外披露。

TARGET摘要为完整目标Span；GOAL为goalId/goalRevision/inputId；PURPOSE/METHOD为当前固定值；FEATURE为完整确认Feature记录（含userText/ordinal/父关系/原件定位）；CANDIDATE为既有MaterialDocument（含原件和选定分支）。CONFIGURATION为完整非秘密Configuration各字段，purposes/dataClasses用排序数组消除Set遍历不稳定；版本相同但model/endpoint/预算等变化仍产生不同摘要，不读取API key。原件全文只用于既有请求SHA核对，不进入事实map或返回新的披露正文。

每候选每分支×全部确认特征形成expectedRows，交给本领域Snapshot再核完整性/预算；当前Loaded所选候选的原件分支必须与保存材料一致，其他候选只使用已经授权保留的Input材料摘要，不推定其现时权利。产物=Snapshot+原requestSha，暂无原报告/行SHA，不能直接当SourceReport或沿用许可。生产只能由新计算原已核材料调用，在未来同业务事务中绑定原调用/报告；不作为旧报告补录或人类HTTP入口。

正反复核：正常机械投影应含全特征/候选并绑定未变的实际请求；修改userText/父关系或完整配置会改变事实和请求SHA；旧requestSha、目标/候选材料漂移、重复/漏Input成员、分支不符或未知方法拒绝。纯组件测试不冒调用、授权、原件持久化或真实Worker链。上述机械事实有源码依据且无新增业务决定，仅释放本内部生产类及C；保存/读取事务、旧Package摘要兼容和正式局部重算仍按13.3另D。
