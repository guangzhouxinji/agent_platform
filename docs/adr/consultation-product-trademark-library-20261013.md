# 咨询商标资料最终产品读取合同

版本：D2，2026-10-13，北京。性质：开发团队技术设计，需求及实施状态仅见docs/08；本文不自行通过D或确认第三方授权。D2补§9.7确切导入动作的生产批准适配设计，实施准入仍由root在原任务行复判。

## 1. 来源、边界与本次增量

原文：[商标库管理需求](../../requirements/知识产权咨询助手/商标库管理需求文档.md)，实测SHA256 `0bf6834dcec865fec5fbbc4f25c5b2b85125b3c9ea5a98dc064c19a8dced1301`，与[CON-01 ready契约](../../governance/contracts/CON-01.json)一致。R依据为docs/08的REQ-CON-R-EXPAND-15。完整合同继续适用，不以本次窄切片代替全项。

复用[docs/56 CON-01](../56-V2咨询全能力详细设计.md#CON-01)的数据、筛选、排序及反例；数据来源路线服从docs/02第2、4节2026-09-19本地DM8常规主路径决定，不新增真实中心API/同步/回退。docs/02第37～39节允许设计最终协议和项目正式Issuer，不授予真实资料、人员或岗位权利。

当前缺口是旧`TrademarkLibraryService`读取`records()`全量内存集合、默认DEVELOPMENT_MIXED、单字符串详情标识、未分页历史、X-V0-Actor和开发`maySearch`授权。它们不能装配为正式能力。可复用现有`TrademarkLibraryRecord.ClassDetail`的完整列表语义、日期范围校验及字段映射经验；不复制旧开发数据适配器。

本次最短增量是隔离的最终域合同、分页源Port、现时授权读边界Port及应用服务。退出观察为已授权范围内分页结果保持源身份/字符串注册号/多类别，缺权或源故障失败关闭，独立历史及图样失败不改基本信息；C只能证明这些源码边界。真实API、DM8适配、资料启用、UI、真实流程与B仍须对应实现及证据。

## 2. 对象与数据约束

`RecordRef(sourceId, recordId, version)`定位确切原记录版，三个值均非空；不以名称或注册号猜关联。列表每项必须携带三元Ref，详情/历史/图样入口沿用该Ref。新版由查询明确返回，不暗换旧详情的版。仅注册号或名称即可启动资料查询；类别和主体仅在消歧/具体动作必需时追问，不强制全部填齐。

记录版与来源登记版分别承载：RecordRef.version是记录版；SourceVersion.version是来源元数据/现时水位对应的登记版，可以分别为record-r7/source-s3。scope每个sourceId唯一登记SourceVersion；provenance必须匹配该来源版，历史父子仍核RecordRef。图样及原文Ref是opaque受控坐标，域对象不接受URL、路径或认证参数；Image值对象核64位十六进制SHA256与真实防御性复制字节一致。

`Observation`承载注册号String、名称、图样受控Ref、注册人完整数组、地址、类别数组、注册及有效期日期、源状态/展示状态、源更新时间、原始来源引用。类别承载编码/名称/全部商品服务项目/类似群，不把第一类或第一项目当完整记录。

注册号仅查询输入去首尾空格，源字段不trim、不转数字、不改内部字符；`00012A`往返保真。源缺失保持null/空列表并在页面显示未提供；数组本身空不证明历史能力无记录。日期保存源精度和原文本，完整日使用LocalDate，部分日期不补日、不作完整日边界匹配；解析失败返回明确DATA_PARSE_ERROR或字段异常，不能伪造日期。未识别源状态映射为UNMAPPED并保留原值；到期日期、申请事项、缺记录均不推断法律状态。

`Event`携带父RecordRef、子recordId、子version、类型、变更/申请或受理/决定日期及各自精度、事项、决定编号、处理机构、进展、结果、前后值、原文Ref。变更、异议、无效保持独立；申请与处理结果不合并；无源前值不反推。单条历史读取必须同时验证父子关联和子版本。

`ImageRef`只含受控文件定位及来源版本，不下发凭据或带认证查询参数的外部URL。图样/历史原文需独立当前DISPLAY许可；下载需另核DOWNLOAD，不把预览自动升级下载。

`SourceObservation`返回来源ID、版、实际范围、来源类型/登记证据Ref、源更新时间、本次读取时间、缓存状态及缓存原始获取时间。来源事实来自已登记来源，不固定DEVELOPMENT_MIXED/PUBLIC_LOCAL或“真实中心”。批准替代资料的性质由其登记事实表达；与最终接口、授权和失败合同无关。

## 3. 完整范围筛选与分页

`Query`输入注册号、名称、注册人、类别/状态多选、注册/有效期各单侧或双侧日期、PageRequest、refresh。不同字段AND，同字段多值OR；名称/注册人文字包含，无语义扩展；注册号trim后精确；日期包含端点，反向范围拒绝。类别匹配任一类但返回记录的全部类别明细。

PageRequest默认20，允许10/20/50，页码>=1，游标非空时不得混传非第一页页码。计算offset使用long并校验允许范围，不让int溢出取错页。

`SourcePort.query(AuthorizedScope, Query)`必须先在完整获准源范围过滤，再全局排序/分页。默认注册日期降序、空日期最后、同日期按sourceId再recordId稳定升序。排序不由服务只对当前页实现。查询及可靠总数使用同一读取快照与授权范围，不能返回后删单页越权项再沿用原total。

`Page`含items、页式或游标式分页、可靠总数(可缺失)、hasNext、sort、actualScope、SourceObservation。页式可靠总数必须非负且与已授权全范围相符；游标不伪total，前端只用前后页。源能力不支持完整筛选、全局排序或无条件浏览时返回CAPABILITY_UNAVAILABLE/QUERY_CONDITIONS_REQUIRED及安全说明，不回当前页近似执行结果冒完成。不同source映射或顺序不满足本合同则该source分支不启用，不改默认业务规则。

游标由适配器绑定查询摘要、scope/版本/排序和有效期；条件/来源代次变更时拒绝旧游标，不能在新范围继续旧位置。有效期由装配配置有限值并经过对应适配D，不在本设计虚构来源参数。

## 4. 现时授权和读取边界

`ReadBoundaryPort.read(ReadRequest, callback)`由正式装配实现，ReadRequest仅带可信认证上下文句柄、确切TaskRef、动作类型及域查询/对象引用。组织、主体、Task/DataScope、源版本与许可事实必须从服务端登记读取，客户端actor/organization/scope不作为事实。域模块不依赖JWT/JDBC或共享Access实现。

边界实现先核项目Issuer签名、现时账号/令牌、确切Task READ、当前QUERY或DISPLAY授权，再读取该Task已登记源scope。按稳定顺序锁定相关Grant/源许可行并核独立authority水位、到期和恢复准入；已有授权动作/用途可复用，不新增SAVE Grant动作。QUERY范围限定查询许可源，返回字段另须DISPLAY覆盖。

callback只收到不可变`AuthorizedScope`，包含已核Task/主体及允许源精确版本/字段集合/策略代次，不含凭据；SourcePort执行时必须只读此范围。结果所有Ref及字段在scope内，scope间混杂、缺失源、错版整体拒绝，不静默删条冒完整成功。数据读后及输出释放前边界重新核现时授权、水位/版/到期；中途撤权、来源撤回、水位不可达、恰到期限均失败关闭。历史成功回执不替代当前授权。

callback不能签发grant、改变官方状态或写来源。图样流亦由边界控制开启，有限分块读取按授权实施方案检查撤回并中止；只核首次请求的长期静态URL不满足撤权合同。恢复的索引/文件/缓存不通过现时来源与保留检查不能读。

这是端口合同，不能凭一个返回true的测试替身声称实际安全已接通。对应正式Access/独立水位/源字节适配和HTTP装配须另作D/负例验证；未装配前无Spring Bean、controller或回退路径。

## 5. 历史、图样、刷新和故障

历史能力状态为UNKNOWN、AVAILABLE、NOT_PROVIDED；加载中仅页面请求状态。历史读取结果仅成功时为HAS_RECORDS或NO_RECORDS，NOT_PROVIDED不带伪零计数，ACCESS_DENIED/FAILED是独立错误。详情读能力元数据，不同时触发三类历史查询；任一子项失败不使基本详情失败，也不伪无异议/无无效。

变更历史全范围日期倒序、稳定子id次序、缺日期最后；异议/无效各自稳定分页，不以决定日期缺失省略尚未决定的申请。所有历史父Ref绑定查询详情版；子数据版本另记，不把同名/同注册号事件拼接。单条详情按父Ref+类型+子Ref读取。

图样读取返回受控内容流与媒体类型/摘要/版本；媒体格式、大小、超时依已批准文件配置。缺失、不可预览、访问限制和加载失败分别返回；不影响普通字段。历史原文如未登记或许可不足，保持结构化历史可访问字段并明确原文不可用，不开放任意URL代理。

普通读取披露缓存是否使用及原获取时间；refresh必须绕过已有获准缓存。初始源码切片无缓存且只声明BYPASSED，不虚称缓存实现。读取时间用Clock，本地记录的采集/源更新时间保持原义，刷新不能把旧采集时间改成当前源更新时间。无许可不新增映射、正文、图片、索引、缓存或快照保存；具体保存/保留恢复在对应来源切片准入，V3人工180/365/30期限不移用商标资料。

失败至少区分INVALID_REQUEST(400)、UNAUTHENTICATED(401)、ACCESS_DENIED(403)、RECORD_NOT_FOUND(404)、CAPABILITY_UNAVAILABLE(503)、SOURCE_AUTH_FAILED(503)、SOURCE_RATE_LIMITED(429)、SOURCE_TIMEOUT(504)、SOURCE_UNAVAILABLE(503)、DATA_PARSE_ERROR(502)。认证基础设施不可达用服务失败而非伪身份通过。返回安全说明及requestId，不输出凭据、SQL、内部堆栈、敏感条件或完整响应。历史异议503绝不返回200空records。

应用服务不自动重试，不调用外部模型。来源适配器如需只读重试，使用有限timeout/总deadline/次数配置；仅可重试故障，认证/参数/解析/权限错误不重试，每次仍核授权。参数取值及性能/限流须该适配D和实测，不在此宣布已具备。最小诊断记录操作、时间、结果码、耗时、requestId，不记录注册人查询文本/正文/令牌；审计持久化期限由既有确实覆盖规则约束，未覆盖时不默认永久保存。

## 6. API和页面落点

正式URI沿docs56 `GET /v2/consultation/tasks/{taskId}/trademarks`绑定Task/DataScope；详情为`.../sources/{sourceId}/records/{recordId}?version=`，三个独立`/changes`、`/oppositions`、`/invalidations`分页及其`/{eventId}?eventVersion=`单条；图样`/image`；历史原文按受控Ref读取；refresh由只读参数表达。URI为本技术决定，controller实际实现前统一owner复核现有产品路由规范。

API直接调用覆盖列表、详情、三历史、单条、图样和错误，不依赖前端。保持现有项目Bearer协议和requestId处理；不接X-V0-Actor或硬编码内部账号。版本和对象标识按独立参数编码，不拼接未转义的“source:record”字符串。

内部页面使用独立`ProductTrademarkLibraryWorkspace`，复用已有表格/抽屉/样式和登录组件，不直接继承LibraryWorkspace的开发actor/全total合同。提供全查询条件、类别/状态多选、日期范围校验、10/20/50分页、图样缩略/放大、完整商品服务组、地址/时间/来源区、三历史标签/分页/单条及重新获取。没有源新增编辑删除，不提供相似度/注册结论。

每次条件变化递增query generation并回第一页，React Query键含Task/scope版/所有条件/分页/refresh序列；消费AbortSignal且结果提交再次核generation。重复相同在途提交合并/按钮pending；q1迟到不能替换q2。详情/history键含完整RecordRef和子版，换记录即切键，不复用同source的别记录结果。

失败保留输入；仅同确切查询的此前成功结果可保留并标“更新失败，当前为此前获取的数据”，不得用q1旧数据冒q2结果。无曾成功结果的失败不显示共0条/无命中；成功空页才显示当前范围无结果。权限/来源撤回拒绝时移除旧缓存正文、历史和图样，不继续显示旧结果。总数只在可靠total存在时显示，游标用前后页。

## 7. 实施归属与后置事实

隔离源码所有权：`backend/trademark/.../trademarklibrary/product/domain/`、`.../product/application/`及对应模块测试。本次不修改共享Task/Access、docs02/08/56、旧开发controller或公共source适配器。域新增对象复用语义而非以development record.dataMode默认输出。只实现Port合同与应用行为，无假adapter/固定候选/运行回退。统一owner登记并复核窄D后再源码。

后续真实增量：本地DM8商标映射/关联项目/历史/源时刻读取适配；授权源查询索引与全局稳定分页；正式JWT/Grant/Source水位边界；当前文件字节/历史原文读取；API/内部工作台。是否需新表依据实际已登记本地source结构核查后决定，不盲建另一套来源/权限/索引平台。

实际资料来源、逐字段QUERY/DISPLAY/SAVE及图样权、源状态字典/源版本映射、真实人员Grant、目标DM8实例与恢复、专业/业务样例只阻依赖启用/I/B；不把它们移为上述源中立域合同和源码的统一前置。未批准来源不装配或返回伪示例，未知缓存/保存期限不默认为永久。完整CON01的I/B及MVP-CON-REAL继续保留，不将源码完成改为业务验收。

## 8. 设计反例与证据目标

| 反例 | 可执行预期 | 层与义务 |
|---|---|---|
| 输入` 00012A `，源号`00012A` | Query仅去外围空白，返回原String，数字转换禁止 | 域/C；MUST-003/007 |
| 一记录两类别，各多个项目和类似群 | 任一类别命中返回全部类别/主体/项目 | 域/源/C/I；MUST-003/011 |
| 21条，注册日相同/缺失，跨source同recordId | 全局稳定跨页，无重复/遗漏，空日期最后 | 源/C/I；MUST-008 |
| 本页可匹配而其他页有更多匹配 | 完整scope筛选/排序/计数后分页，不能单页过滤 | 源/I；MUST_NOT-005 |
| `total`未知/游标源 | API不填0；页面前后页，不造共0条 | 域/UI；MUST-009 |
| 反向日期/50以外pageSize/offset溢出 | 400，源Port零调用；端点日期包含 | 域/UI/C；MUST-006/007/008 |
| 异议源抛503 | 明确SOURCE_UNAVAILABLE，无200空列表；基本详情可用 | 服务/HTTP/UI；MUST-011/013 |
| 未提供、未知、成功无记录分别输入 | 三状态不混，未知不计0，不写无异议 | 域/UI/C；MUST-013 |
| 同source两record、两个version历史 | 完整父子Ref隔离，错父/错版拒绝 | 服务/源/C/I；MUST-002 |
| 无效申请有进展且无决定 | 源申请/进展保真，不宣告无效，不改基本法律状态 | 域/UI/C；MUST_NOT-006 |
| q1慢、q2快、q1随后返回 | 仅q2显示；更新失败保留同查询旧结果并标记 | UI/E2E；MUST-010 |
| 读取中撤权/恰到期/独立水位不可达 | 输出前失败关闭；图样/缓存停止访问 | 边界/C/I；MUST-016 |
| 越组织/错Task/QUERY无DISPLAY字段/返错source | 无泄漏、无模型发送、不能静默删页保留total | 边界/服务/C/I；MUST-016 |
| 图样失败或历史局部失败 | 已授权基本字段保持，独立失败可重试 | UI/HTTP；MUST-011 |
| refresh和源暂不存在 | 绕缓存，保留旧观察原时刻；不删历史/推注销 | 源/UI/I；MUST-005、MUST_NOT-004 |

上述为D预期，不是已运行证据。组件检查使用可控Port和Clock验证产品源码，测试Port不成为产品adapter；DM8全局分页/关联/中文查询/故障回滚及正式HTTP/图样撤回需实测I；已授权真实样例逐字段、状态/历史/图片一致性与独立验收为B。结构检查不证明合法性或来源权利。

## 9. 从域合同接到普通用户查询的DM8/Source/API详细增量

本节属于2026-10-13已登记`ARC-CON-TRADEMARK-PROJECTION-D-01`，依赖`REQ-CON-R-EXPAND-15`与已完成`PC-CON-TRADEMARK-READ-DOMAIN-01`，父为`EXP-CON-SEARCH`；仍待root审定，不自行准入存储/API源码或认定完整接线D。预期用户完成现有登录并打开确切咨询Task的商标资料页后，能按条件查询、翻页、查看精确版详情；证明链须包含真实SQL/受控捕获字节、正式JWT与当前Grant负例、HTTP及页面操作，而非再次只运行域16C。原子导入是该查询交付包的必要步骤，不再置于只读适配以后。物理迁移、导入、部署和真实人员/资料启用继续遵守已有暂停及期限。

### 9.1 复用事实与真正缺口

V008已有`kb_source/kb_ingest_batch/kb_raw_record/kb_record_version/kb_field_provenance`及`kb_trademark/kb_trademark_class/kb_trademark_image/kb_trademark_event`。注册号为VARCHAR，多类别有顺序主键，明细CLOB可以结构化解析；无需新造来源平台或再存一份商标主表。它缺注册人关系表及产品Source合同/真实capture与投影的精确绑定，也不能保存全部事件目标字段。

`Dm8TrademarkLibraryAdapter`构造时调用`Dm8KnowledgeBaseJsonReader.records("TRADEMARK")`读取所有PUBLIC_LOCAL规范化JSON，固定内存与dataMode，历史固定未提供；不可复用其运行实例。`LocalKnowledgeBaseImporter`把normalized JSON当raw_content，批次records_sha为派生ID，默认REVIEW_REQUIRED/INTERNAL_DEVELOPMENT_PILOT_ONLY，不能以这些字段证明真实原件、产品许可或现时状态。该旧导入不扩写/原地升级。

`data/trademarks/public-pilot-v1/manifest.json`的来源为USPTO公报派生公开开发样本，licenseReviewStatus=REVIEW_REQUIRED，usageDecision=INTERNAL_DEVELOPMENT_PILOT_ONLY；字段覆盖明确缺注册号、图样、地址、商品服务/类似群、注册及到期日期。当前没有由该manifest证明的产品正向资料；不能拿serialNumber冒注册号，不能因表有行或dataset自称CC0便启用。此事实只挡该资料启用，正式解析/授权/SQL/API可以实现并用明确隔离测试输入验证。

可复用产品能力：`ProductSourceCandidates/Verification/Admission`的登记、字节核验和有责决定，`ProductSourceAvailability.requireFields`的任意精确字段用途矩阵，`ProductSourceScopeGrants`的Task/Source范围及QUERY/DISPLAY Grant，`ProductGrantWatermark/ProductSourceWatermark`的独立authority现时性，`ProductSourceEvidenceFiles`的路径/摘要受控读取，`ProductTaskDirectory/ProductConsultationAccess`的Task READ及现时账号机制。Source field_code并非固定专利四字段，不复用专利TITLE/COMPARISON读取器或新建Grant动作。

### 9.2 捕获闭包及最小数据delta

#### 9.2.1 原件格式、真实字段与版本

原件证据采用现有`ProductSourceEvidenceFiles`的受控路径/摘要读取和`ProductSourceVerification`的generic capture闭包；`ProductCapturedJsonFields.ranges`可核原始JSON token的UTF8位置。当前Java源码未找到名为RawSourceEvidence的类型，本文用“原件证据”描述上述实际机制，不把概念名当已完成商标适配。专利`ProductCapturedSourceFields/PatentFields`的TITLE等业务字段映射不复用到商标。

根manifest字段为`datasetId/generatedAt/source.apiBaseUrl/recordsFile/recordsSha256/recordCount/rawResponses[file,sha256,bytes]`；records文件包含同datasetId/generatedAt和`records[sourceId,sourceRecordId,sourceVersion,capturedAt,detailRawFile,sourceRef]`。sourceId和合同由登记Source决定，登记版严格为datasetId:generatedAt；单记录版严格为`RAW_SHA256:<detail原字节SHA>`，同文件SHA可以由不同稳定recordId定位，但同一capture内sourceRecordId必须唯一，即使原件SHA不同也拒绝重复稳定ID，不能在一次结果快照纳入同记录多个版。禁止缺长度/成员/摘要、重复文件或记录、跨源混入、非法路径和捕获时刻不一致。generic验证不核全部商标字段，专属映射器仍须下面的原token核验；不能加宽generic验证使旧manifest自动通过。

首个拟冻结项目交付格式为`TM-JSON-DIRECT-V1`：原detail必须是来源交付/采集的单个UTF8 JSON对象，不是旧normalized_payload的再序列化；实际来源方须以已登记格式及权利证据交付。该技术格式不是任何provider已支持、已授权或业务方确认的事实。归档若变换原响应，必须另保responseSHA/archiveSHA及可重现变换证据；没有便拒绝，不把归档JSONSHA当原responseSHA。

| 原JSON字段 | 投影与缺失规则 |
|---|---|
| sourceRecordId | 必须字符串且与records稳定ID相同；名称/注册号不能代ID |
| registrationNumber | 字符串原值至VARCHAR，null/缺失保持缺失；不转数字或trim源值 |
| name | 原字符串或null；图形商标不造名称 |
| applicants | 原字符串数组，全部主体/顺序保留，不以分隔符推多个主体 |
| applicantAddress | 原字符串或null，不补注册地址 |
| classes | 数组；每项code字符串、name字符串/null、goodsServices及similarGroups字符串数组，全部类别/项目/顺序保留 |
| registrationDate、validUntil | null/缺失或原字符串`YYYY`、`YYYY-MM`、`YYYY-MM-DD`，分别映射YEAR/MONTH/DAY；按公历严格核有效年/月/日，拒非法或不存在日期；仅DAY入DATE，部分日期原文保真且DATE为null |
| sourceStatus | 源字符串；首版未知值统一展示UNMAPPED，不推法律状态；其他展示映射须mapId/version/SHA证据 |
| sourceUpdatedAt | 源字符串或null，不替换成本次获取时间 |

类型错误拒绝DATA_PARSE_ERROR，不asText强转数值或以空数组掩盖非法结构。首版字段代码与域枚举一致：`NUMBER/NAME/APPLICANTS/ADDRESS/CLASSES/DATES/STATUS/SOURCE_REFERENCE/IMAGE/HISTORY`；不借TITLE/COMPARISON映射。源元数据及sourceRef归SOURCE_REFERENCE，图样/历史未有真实闭包时保持UNKNOWN/NOT_PROVIDED且不造Ref；sourceRef只由受控records坐标解析，不返回原apiBaseUrl或任意URL。字段QUERY/SAVE/INDEX/DISPLAY依实际动作和返回内容核验，来源元数据输出也必须有SOURCE_REFERENCE的DISPLAY覆盖。

实际字段覆盖及缺失从原对象/登记能力读取；每存在字段保存RawRef/SHA、JSON Pointer、原顶层value token UTF8起止和tokenSHA，显式null保留真实null token。缺失字段保存MISSING及原件身份，不造不存在的token坐标或tokenSHA。嵌套类别明细从已验证数组token结构化读取并核数组/对象字段类型、成员数、顺序与完整关系投影；无需为每个嵌套项另造全文定位平台。拒重复JSON键、非法UTF8、尾随内容/位置越界，不能只对解码文本估算原件偏移。

已核旧CSV真实表头为`serial,mark,owner,classes,event,status,filing_date,pub_date,transaction_date`。若后续接该原生格式，serial只作源记录/申请序号，mark作名称，owner作单完整原主体字符串，classes按该来源格式作类别编码，event/status与各日期保持各自原义；注册号、图样、地址、商品服务/类似群、注册/到期日为SOURCE_NOT_PROVIDED。该CSV不是本节JSON合同输入，不通过规范化包装冒JSON原件；CSV正向适配及来源用途需其独立D/证据，不能以现有公开可访问或旧试点许可启用。

#### 9.2.2 原子投影、表字段与幂等回执

复用V008主表，最小新增为`con1tm_capture_request`、`con1tm_record_binding`和`kb_trademark_party`，迁移号由root分配；不新增来源/授权/Task平台。V008 mark_name须通过新迁移允许NULL，不能把图形商标缺名称写空串。存储模式迁移采用单个新值`PRODUCT_SOURCE_LOCAL`：新check允许`PUBLIC_LOCAL/PRODUCT_SOURCE_LOCAL`，旧行及旧导入器不改，新商标产品投影一律写PRODUCT_SOURCE_LOCAL。它只说明项目本地保存的产品来源投影，不表示公开性、替代性、授权或权威性；禁止用PUBLIC_LOCAL装非公开/替代来源。

来源性质另由binding持久化`sourceNature/natureEvidenceRef/natureEvidenceSHA`，sourceNature仅`PUBLIC/RESTRICTED/APPROVED_SUBSTITUTE`，从确切Source admission绑定的capability证据读取并核源/合同/登记版，不由data_mode、URL、provider名称或请求人选择推导。APPROVED_SUBSTITUTE另须该证据明确替代输入的批准责任、范围/目的及许可，不改变产品行为；性质未知或证据不覆盖拒导入。查询Provenance按此已核事实表达，PRODUCT_SOURCE_LOCAL不能直接当sourceType返回。sourceNature和证据进入request/normalized/binding摘要，漂移拒绝。回退迁移只能在证明不存在PRODUCT_SOURCE_LOCAL行和新绑定后恢复旧check及非空约束；否则停止并按已批准保护恢复合同处置，不DELETE、不强改旧性质或把缺名称补成空串。

capture_request的幂等唯一键为organization/sourceId/contractVersion/idempotencyKey；持久化requestSHA、真实maintainer主体与证书绑定、organization/Task/authorizationVersion、decisionId/sourceEpoch/sourceRowVersion、manifestRef/SHA、recordsSHA/inventorySHA、mapId/version/SHA、retentionRef/version/evidenceSHA、expiresAt、recordCount/bindingSetSHA、COMMITTED回执ID与时间。不存凭据、任意调用方actor或新增永久日志。binding唯一键为sourceId/contractVersion/sourceRecordId/recordVersion，含独立source登记版、record_version_id、rawRef/SHA/byteLength、manifestSHA、normalizedSHA/relationSHA、mapId/version/SHA、captureRequestID/expiresAt。party主键为record_version_id/party_order，含完整party_name和源提供的address_text。

binding不能只外键核UUID存在：必须约束或同事务锁/强核对应kb_record_version的domain_code=TRADEMARK、sourceId/stable_record_key/source_version(记录原件版)。源登记版只在binding及con4保存，不塞入记录版。normalizedSHA对指定映射版canonical UTF8 payload计算；raw_content保存真实原UTF8字符串，contentSHA对原字节计算；批次recordsSHA/inventorySHA来自真capture，不用派生ID填。商品/服务及类似群CLOB按JSON写读，kb_field_provenance保存真实token定位/摘要、字段实际值状态；关系SHA按原顺序记录全部class/party/字段定位，包含缺失事实，不以关系主表count代内容摘要。

导入职责限定为固定动作`TRADEMARK_CAPTURE_PROJECT_V1`，不接客户端action/use参数。`ProductSemanticIndexController`已用SOURCES构建获准Source的索引，但此事实仅证明可复用维护证明，不把新动作隐式加入历史授权。入口采用§9.7的`ProductMaintenanceProof.withSourceProjectionProof`锁定同一真实证书维护租约，核subject/organization/certificateSHA/status/version；不先require再requireOrganization拼接两次current结果。

新动作使用`CurrentTrademarkProjectionAuthority`限定端口及§9.7具体生产适配`ProductTrademarkSourceProjectionAuthority`：Binding为operation、真实维护subject/certificateSHA/organization及维护version、Task及authorizationVersion、sourceId/contractVersion/sourceVersion、decisionId/epoch/rowVersion、manifestSHA、mapSHA、approvedScopeSHA、approvalRef/SHA、finiteValidUntil及实际主/独立库身份。`ProductHumanInitialization.CurrentLease`只能复用不可变绑定/检查前后Clock的代码经验，其固定CREATE_TASK_AND_INITIALIZE_HUMAN_SCOPE动作及恢复lease均不具商标导入批准语义。生产适配直接核现有Source admission所绑定的受控capability证据、部署批准pin、真实维护锁租约及独立Source水位；无默认true或调用方自行注入的批准实现。每次requireCurrent核实际连接/现时lease与Clock，写前/提交前/输出前均重核。无现时动作批准绑定关闭导入装配/启用；9.6已经完成的映射包不包含此适配，后继原子投影/正式读取功能包须实现本节及§9.7。实际人员/来源批准与安装pin属启用事实，由执行方生成可审绑定并复核已有批准，不要求用户另制同内容文件。

该限定动作只把已核原件投影到本节白名单表，必须同时核记录级SAVE、查询列/关系INDEX及保留证据；不签Grant、不改变Source准入/官方状态、不导入任意领域或外部抓取。普通Task READ/WRITE、历史SOURCES角色或域测试授权均不能代替动作Binding。普通用户查询仍按Task READ+QUERY/DISPLAY，不授Source维护权限。既有主体/证书撤回即时使新动作失败；动作lease变更/失联/恰到期也关闭，不声称跨库撤权与主commit原子线性化。

原子导入在有限capture全部原件/成员/map先核验后，开启READ_COMMITTED主事务，拒不受控外层事务；核当前Source contract/admission、字段SAVE/INDEX、scope/Task及最短期限、独立Source/Grant水位和恢复覆盖。按与9.4兼容的稳定key锁Task/现时Source/字段用途及目标binding/record key；主事务外独立maintainer事实以前后witness核不可借快照冻结。写入全部raw/version/商标/class/party/provenance/binding和COMMITTED回执，任一成员失败整批回滚，不能半批/仅主表行可读。写前与commit前复核文件SHA、Source决定/epoch/rowVersion、维护职责及最短期限；失败不改Source/Grant或官方状态、不发模型。本设计不自行物理导入。

requestSHA使用下面固定版本canonical结构，绑定主体/组织/Task、原文件摘要/完整成员集合、map与保留，不用字符串分隔拼接。相同scope+key+SHA在当前身份/Source/保留复核后返回原COMMITTED回执，零重复写；同key异SHA为409。相同源记录版/map而原件Ref、normalizedSHA或relationSHA不同为SOURCE_VERSION_CONFLICT，整批拒绝，不覆盖。相同原件及map可复用既有确切kb_version但重新核完整闭包；同原件改map重投影本版拒绝，需后续明确版本合同，不能暗改既有版。失败事务无成功回执；commit后断响应由同key恢复原回执并重核当前授权，不盲重放写入。

`TM-PROJECTION-REQUEST-C14N-V1`采用ObjectMapper构造已验证值的JSON树后紧凑UTF8序列化，不对原件再canonical。根字段完整固定为schema/operation/idempotencyKey/maintainer/organization/task/source/capture/map/nature/retention/expiresAt；所有固定ASCII对象key递归升序，字段不省略、可空值明确null、整数为十进制无小数、SHA全小写64位，身份/Ref原值不trim或Unicode归一化，拒非法代理字符；字符串使用项目ObjectMapper的JSON标准转义且禁用ESCAPE_NON_ASCII，格式选项不由调用方传入。Instant编码为`{epochSecond,nano}`两个整数，避免时区/小数文本差异。maintainer含subject/certificateSHA及动作approvedScopeSHA；task含id/authorizationVersion；source含id/contractVersion/registeredVersion/decisionId/epoch/rowVersion；capture含manifestRef/SHA、recordsSHA/inventorySHA、members完整对象数组（stableId/recordVersion/rawRef/rawSHA/rawBytes/capturedAt）、recordCount/bindingSetSHA；map含id/version/SHA；nature含code/evidenceRef/evidenceSHA；retention含ref/version/evidenceSHA；expiresAt为最短截止。members按stableId、recordVersion、rawRef的UTF8无符号字节字典序逐字段排序，不限制真实记录ID必须ASCII；原detail中的主体/类别/项目及同类数组保留原顺序。审批lease的runId和剩余时长不入requestSHA，保持同确切动作范围重新准入后原键可对账；当前审批证据、维护版和lease另存回执见证并现时核，不可凭原hash续期。

normalized及relation摘要分别使用`TM-NORMALIZED-C14N-V1`、`TM-RELATIONS-C14N-V1`同一树序列化规则；normalized包含本节全部业务字段、原日期精度/原值、来源性质和缺失状态；relations包含完整有序主体、类别/项目/类似群及原字段定位/值状态，不能因UI未显示省略。原字节SHA、tokenSHA照真实字节计算，与上述投影SHA分开。canonical schema/map升级即新合同，首版不隐式兼容或原版重投影。

首版工程上限固定为：单Source capture最多1000记录/2000原文件，manifest≤1MiB、records文件≤8MiB、单detail≤2MiB、完整原件闭包≤64MiB；JSON深度≤32，单字符串≤64KiB，单记录全部数组累计≤10000项、全部关系累计≤100000项/capture。单查询最多8个Source且合计≤5000成员、核验总字节≤128MiB；不符合则413（输入包/维护）或CAPABILITY_UNAVAILABLE（查询范围），绝不截断。HTTP维护请求只含受控Ref/摘要、≤64KiB，不接正文上传或路径/URL。锁等待≤5秒，读取总deadline≤30秒，导入核验加事务总deadline≤60秒，时间上限仍取当前动作lease/Source/Task最短剩余；超时504并按事务/回执事实报告，不能伪未提交。受控配置可下调，首版启动校验拒零/负/超过这些上限；提高上限须更新本D并重新测资源，不能在线开无限值。这些是技术资源限制，不授保存期限或模型预算。

#### 9.2.3 完整查询一致性与恢复

查询投影完整性不是只验最终一页。列表对每个Source只选择其当前获准登记版及核验决定所绑定的确切manifest/records成员集合，不把该source历代capture取并集；这个集合每稳定recordId只含一个记录版。旧记录投影保留不等于进入新列表，确切旧版详情仍须当前scope明确允许。每请求在该有限capture内核inventory成员/count/manifest与records及全部原字节，核binding完整成员集合并重算normalized/SQL查询列/class/party/provenance关系SHA。应有成员缺一、额外混入、索引篡改（包括造成当前页外漏命中）、错来源登记版或raw文件缺失均整体SOURCE_UNAVAILABLE；不能静默过滤问题记录后沿用total。之后才在该固定获准集合执行9.3完整SQLCOUNT/排序/分页。max-bytes/记录上限使用有限受控配置，超过明确失败，不截断冒全库；增量一致性优化须另有不可变投影及现时证明D。

expiresAt为来源合同/admission、字段SAVE/INDEX期限、实际覆盖投影的获准保留截止及适用Task范围中最短者；不填人工180/365/30、无限期限或每读续期。到期禁止正文/投影/历史/图样访问与恢复，物理清理由已有确实覆盖该来源对象的批准处置合同执行；删除暂停期间保持关闭，不自行DELETE。备份规则须确实覆盖新增binding、raw、class/party及回执且有有限截止；覆盖缺失则该Source导入启用关闭，不推断永久保留。

恢复主库的旧COMMITTED/ACTIVE行不能自行合法：每读核独立authority当前Source/Grant的contract/decision/epoch/rowVersion、实际保留/恢复批准、期限/原件及完整成员投影。authority缺失/PENDING/撤回/错代次、原件不可达、恢复的新投影缺有效保护覆盖一律拒绝，不能自动初始化水位、延长期限或重跑导入复活。维护审计写入独立库的失败保留实际副作用，不冒与主事务一起回滚；恢复不得把历史身份/许可当当前事实。

当前详情可接真实基本字段与独立历史/图样能力状态；UNKNOWN/NOT_PROVIDED不能伪0记录。完整事件/图样/单条原文适配后接其已有全项义务，不把本基本信息包称全CON01完成。

### 9.3 实际SQL筛选、分页与详情

实现新的`ProductDm8TrademarkLibrarySource`，依赖JdbcTemplate/ObjectMapper和上述字节绑定读取器。所有SQL从已授权Source scope、当前合同及精确绑定开始，绝不从全部PUBLIC_LOCAL行开始。范围参数从ReadBoundary回调构造，不接浏览器组织/源列表或任意表名。

查询WHERE组合：registration_number精确；mark_name与注册人名包含用`INSTR(column,?)>0`避免用户百分号/下划线变通配符；注册人/多类别用EXISTS，字段间AND、选值间OR；源状态筛选通过已验证映射字典，未映射按UNMAPPED选择，不把expiry推法律状态；日期>=/<=含端点。未提供完整日的数据不冒日匹配。查询涉及的字段核QUERY，返回字段核DISPLAY。

同一主事务及固定已验证binding成员集合内，SQL COUNT后ORDER BY注册日期DESC NULLS LAST、source_id、stable_record_key，再OFFSET/FETCH或目标DM8已验证分页语法。count与page使用相同完整WHERE；类别和注册人EXISTS避免联表多行导致total膨胀/记录重复。查询后按页record_version_id取全部有DISPLAY许可的类别/项目/主体明细，不因筛选某类而丢其他类。不先取一页再内存过滤/排序。

详情WHERE同时核sourceId/recordId/recordVersion/contract binding；源登记版与记录版分别检查。指定旧记录版只在当前scope与保留允许时读取，不能暗切最新版。缺绑定/原字节不同/规范化SHA或关系投影漂移为明确SOURCE_UNAVAILABLE或DATA_PARSE_ERROR；当前范围确实无该记录才404，不将数据库连接错误伪404或空列表。数据变化通过固定绑定/行锁闭包拒绝，不重试任意最新版。

### 9.4 正式读事务、现时授权与HTTP装配

新`ProductTrademarkReadTransaction`实现已有ReadBoundaryPort，复用正式JwtDecoder与当前账号/会话、TaskDirectory、Task READ、Source scope/current/field_use和独立水位。不能直接调用人工WRITE事务当读取权限，也不允许客户端Request凭据中的actor/org字段扩权。

事务固定READ_COMMITTED并拒绝不受控外层事务；先认证并从持久Task取得组织和authorizationVersion，锁Task/账号会话、确切Grant和已登记Source scope/current/field_use/binding成员及查询投影行，全部按稳定key顺序。读取前核Task READ和每source的QUERY/SOURCE_QUERY、DISPLAY/SOURCE_DISPLAY精确Grant；返回前复核同一主体/Task版/每Source合同、决定、epoch/rowVersion、字段用途与最短有效期、独立authority及恢复准入。失败关闭；同实例分池/schema/accounts沿现有authority规则，不因URL相同误拒合法隔离，也不能以相同schema/account冒独立。

多来源现时证据按sourceId分别保存在事务私有不可变witness map，不能用域AuthorizedScope中的单个sourceEpoch代替多源真实epoch。回调只得到授权后的领域SourceScope，适配器绑定实际连接和私有witness；回调结果不能绕开事务终检。正文/映射已经合法存储的只读请求不增加保留期，读取不续期；恢复或到期状态缺失/PENDING/错代次则关闭，不自动初始化或复活历史。

HTTP沿现有产品规范采用`/api/v2/consultation/tasks/{taskId}/trademarks`及`/sources/{sourceId}/records/{recordId}?version=`（本节纠正第6节逻辑URI未带/api的示意），复用Bearer与X-Request-Id。controller只处理参数/安全错误映射，不接X-V0-Actor；服务读取用正式认证主体，来源与组织从Task查。query page1/size20，类别/状态多值，refresh参数明确传递。controller直接调用实际Service/Boundary/SQLSource，不能返回固定候选或注入开发适配器。

新增独立配置只在product.identity.enabled、product.source.engine.enabled及具体trademark读取功能配置下装配，缺数据源、authority、证据root或必需配置启动失败关闭；产品入口不能fallback classpath/memory/DEVELOPMENT_MIXED。此配置是正常功能运行配置，不展示开发产品路径。已有旧bean不参与正式路由，root核组件扫描/安全route匹配，避免旧路径被暴露成产品。

### 9.5 UI衔接、启用门禁和退出观察

后继接第6节独立ProductTrademarkLibraryWorkspace到现有普通用户咨询Task页，可使用已登录会话Bearer与当前Task/source权限，查询条件与详情保持Task，返回原Task不会丢目标。不要求用户进入维护页或输入内部source scope/actor。UI先有可靠分页/详情/来源时刻/失败与重试；图样/完整历史能力按独立真实适配合同接入，未知不显示0。具体前端独有文件由统一owner分配，不能只交controller就宣称用户体验完成。

实际启用须有合法商标capture、逐字段QUERY/DISPLAY（导入另SAVE/INDEX）、责任/保留依据和当前Task成员Grant；这些事实没有则拒绝该source，不挡上述接线源码/隔离C。现有USPTO开发manifest不得自行升级；人工V3期限不移用。无本轮批准不得物理DDL/导入/删除/部署，源码迁移及SQL测试仍可完成。目标DM8 I、正式人员/资料验证及B分别后置取证，不把H2/JDBC组件或模拟HTTP冒真实DM8/业务验收。

退出反例：真实SQL跨21条记录稳定分页与COUNT一致，`00012A`不变；多个注册人/类别同时匹配仍一条且明细完整；名字含`%/_`按文字包含；同source不同record版及source-s3/record-r7分离；tampered索引导致漏命中时整体关闭；旧kb未绑定/许可review required拒绝；Task/source/Grant错版或读取中撤回及恰到期拒绝；数据库503不返回共0条；direct HTTP无权限拒绝且无认证URL/凭据泄漏；前端最后一次查询及详情回Task可操作。组件、真实DM8、HTTP、浏览器、部署、真实流与B按实际执行证据分别报告。

### 9.6 首个可独立审定的源码窄包：原件映射与可信绑定核验

最短源码增量只实现纯`ProductTrademarkCaptureMapper`及不可变`VerifiedTrademarkCapture/ProjectedTrademarkRecord`结果，放在platform-api既有Source捕获映射包内；具体任务ID由root在docs/08注册并窄D审定后实施。输入为明确sourceId/contractVersion/registeredSourceVersion、manifest/records真实字节与Ref、完整raw文件集合（每项Ref/声明SHA/长度及防御性复制字节）、固定TM-JSON-DIRECT-V1 map身份；输入值只是要核的声明，不能自称已授权。Mapper无JdbcTemplate、HTTP、文件读取、Bean装配、Grant、维护动作端口或默认源，不新增DB/API；受控文件读取及现时授权仍由后继真实适配提供。

该包一次核manifest/records身份和完整inventory、原字节SHA/长度/UTF8/重复键/路径规则、每source稳定ID唯一、登记版与记录RAW_SHA256版分离、sourceRecordId原token一致，再按9.2.1投影全部字段/缺失事实及位置，输出真实raw/token/normalized/relation/bindingSet摘要与完整有序关系。任何成员不合格整体失败，不返回部分可导入集合；执行9.2.2单capture资源上限。normalized的来源性质在此只能接独立待核的nature证据原字节并核其schema/source/contract/版/摘要；输出明确其来源事实未经过现时批准，不对nature、SAVE/INDEX或保留作准入结论。requestSHA的Task/维护/决策/最短期限组合留待导入包，不在本包伪造身份或批准。

复用清单：现有ObjectMapper严格结构化解析、`ProductCapturedJsonFields.ranges`真实顶层token字节定位、商标域RecordRef/DateValue/ClassDetail/Observation的已实现值约束、MessageDigest SHA256和JDK UTF8严格解码；generic Source verification的捕获结构作为输入格式核对依据。避免复用旧LocalKnowledgeBaseImporter制造raw的方法、专利字段语义或任何开发Source许可。必要文件集中在原件映射、受限解析/摘要和其组件测试，按已有代码行数守则拆分职责，不新造通用capture平台。

退出证据是实际字节组件输入的正向多主体/多类别/精度/00012A保真，以及重复稳定ID跨SHA、非字符串注册号、虚假/不可能日期、原件或token漂移、错误源/登记版、重复键/非法UTF8/尾随内容、inventory缺件/额外件/超界的整体拒绝。每负例证明原件错误不能生成可用投影，且无DB/HTTP/授权副作用。该成果关闭原件到投影的不可复用缺口，为后继原子导入与只读一致性核验复用同一映射器；只证明源码/组件，不证明源权利、当前授权、存储、API、DM8、部署、浏览器流程或业务验收。9.2～9.5存储/导入/读接线全包D仍单独待root审定，不能因本窄包通过自动放行。

§9.6限定D审定（2026-10-13，Codex）：仅PC-CON-TRADEMARK-CAPTURE-MAPPER-01通过源码D，依现capture、域值约束及两轮只读复核。私有严格mapper开启嵌套重复键/尾随内容拒绝，UTF8 REPORT及孤立代理字符拒绝；不改共享token helper。YEAR/MONTH有效性单独核；sourceRef使用既有安全标识，不拿路径/URL冒域Ref。真实来源性质/许可/保留仍只门禁对应启用，全DB/API D仍未退出。

### 9.7 固定商标投影动作的具体生产批准适配

本节关闭§9.2.2只有未来端口、没有生产批准读取路径的设计缺口。新增`ProductTrademarkSourceProjectionAuthority`实际适配现有`ProductSourceWatermark`、`ProductSourceAvailability`、`ProductSourceEvidenceFiles`及真实`ProductMaintenanceProof`锁租约；不新增审批表、批准状态机、签发服务或任意动作通用平台。root审定本节后可实现适配、原子投影和正式读取源码及隔离组件。具体安装批准pin、维护人员、合法capture和确实覆盖的保留规则在启用前核，不以本文代替这些事实。

#### 9.7.1 批准信任入口与确切范围

沿用`con4_source_admission.capability_ref/capability_sha`的受控capability证据作为动作条款载体，新增的商标解释器只接受schema=`TM-PROJECTION-CAPABILITY-V1`。文件必有sourceNature、natureEvidenceRef/SHA、第三方rights依据、有限保留/备份/恢复依据，和一个固定`projectionApproval`对象；responsibleParty及rights引用必须与同一admission一致，organization/Task/scope/retention必须与其`con4_source_product_decision`一致。natureEvidence另为已完成mapper接受的`TM-SOURCE-NATURE-V1`真实受控文件，source/contract/登记版、code及Ref/SHA逐项匹配；不把新capability字节直接喂给旧nature解析器，不改变已完成mapper合同。既有任意capability文件、只有QUERY/DISPLAY的决定或SOURCES角色不满足本schema，不自动升级。源原件和资料权利仍分别认证，动作批准不授第三方权利。

`projectionApproval`固定字段为operation=`TRADEMARK_CAPTURE_PROJECT_V1`、approvalRef/version/responsibleParty/decidedAt/evidenceRef/evidenceSHA、subject/certificateSHA/maintainerVersion、organization、taskId/taskAuthorizationVersion/scopeId、sourceId/contractVersion/registeredSourceVersion/verificationId、manifestSHA/recordsSHA/inventorySHA/bindingSetSHA、mapId/version/SHA、sourceNature/natureEvidenceRef/natureEvidenceSHA、rightsEvidenceRef/rightsEvidenceSHA、retentionRef/version/evidenceSHA及备份/恢复依据Ref/SHA、primaryDatabaseIdentitySHA/authorityDatabaseIdentitySHA、notBefore/validUntil及approvedScopeSHA。operation不可由请求覆盖；所有标识逐字一致，时间为UTC Instant，截止有限且不得晚于源合同/字段用途/Task/证书及对应保留的最短期限。approvedScopeSHA按§9.2.2相同canonical规则对上述完整字段（仅排除approvedScopeSHA自身）计算；不凭只覆盖sourceId的摘要批准整个capture。字段重复、未知schema、未覆盖项、空期限或无法验证的决定出处均关闭。

批准内容由执行owner依据已有明确批准生成，保留本项目决定的来源、版本、责任方、日期、范围和结论原件；不要求用户重复制作文件。实际批准依据尚缺时，只能准备待审材料，不能登记生效pin。安装配置预置有限（首版最多8项）的`ProjectionApprovalPin(sourceId,contractVersion,capabilityRef,capabilitySHA,approvalEvidenceRef,approvalEvidenceSHA,approvedScopeSHA)`，仅安装owner能在对应启用准入内设置；常规业务/Source维护接口均无pin添加或修改功能，HTTP请求不能携带替代pin。配置默认空使导入关闭，不提供sample pin或默认批准。安装owner须证明运行账号不能写安装配置与受控批准证据；无法证明隔离则导入启用失败。

pin同时核capability和批准原件的真实字节SHA，不把正文中的responsibleParty字符串当独立身份认证。当前Source admission必须已引用同一个capabilityRef/SHA，且`ProductSourceWatermark.current`认证该决定完整历史与独立`id4_source_watermark.history_sha`；仅找到文件、仅找到主库ACTIVE行、或维护调用方自己制作并上传同schema JSON均不足。部署pin是本动作的受控批准信任锚点，Source水位是其现时准入锚点；两者缺一关闭。此约束防止已有SOURCES维护权限自行签发本动作。

capability在Source admission前形成，不能循环嵌入尚未产生的decisionId/epoch/rowVersion。其verificationId、source/contract/登记版、capture摘要及Task/维护范围是被批准的稳定对象；适配在现时水位核验后把实际decisionId/epoch/rowVersion及主/独立Source historySHA补入不可变Binding。运行期间任何一项变化即旧Binding失效，不通过重新拼接新版witness续用旧回调。

#### 9.7.2 真实维护租约与连接归属

在现有`ProductMaintenanceProof`新增固定方法`withSourceProjectionProof(HttpServletRequest, Function<SourceProjectionLease,T>)`，复用`withRetentionProof`的真实TLS客户端证书、独立`id2_maintainer`行锁、事务/ConnectionHolder/Connection/thread归属与证书时效认证，仅固定核`Role.SOURCES`；不复用HUMAN_RETENTION角色，不新增角色登记或放宽既有require。SourceProjectionLease构造器私有、不可反序列化，包含subject/organization/certificateSHA/version/证书截止；每次访问核同一真实事务连接、同一线程、当前ACTIVE角色及原维护版，回调退出立即失效。普通业务JWT不能构造它。

入口须为TLS且已有维护证书认证的固定商标capture投影命令，输入只有§9.2.2受控Ref/摘要/Task/idempotencyKey；authority固定选择安装pin，不允许输入approval、maintainer、action、数据库标识或任意字段用途。root接既有维护安全匹配时限定本动作；普通Task路由不暴露维护导入。维护证书认证通过但pin范围不匹配仍拒绝。

先在事务外对有限capture/批准原件作严格预核，再进入独立authority租约；按id2 singleton、id4 sourceId的稳定顺序持有真实行锁，核id4为READY、intent为空且epoch/historySHA与完整主Source历史相同。不写id4或初始化缺失水位。主投影事务只允许这个已认证租约所拥有的authority连接作为外层资源，开始前不得已有主事务或第三方资源；主事务自行开启READ_COMMITTED，内层验证精确两DataSource/manager/ConnectionHolder归属，仍拒任意调用方外层事务。不能用一般`isActualTransactionActive`检查误拒合法租约，也不能因有该标志便接受任意事务。按§9.2.2稳定主表key取锁，主commit及commit后回执定位在租约回调返回前完成。

生产连接身份从被实际使用的JDBC Connection读取数据库类型/服务器端点、用户和schema，与安装时已核的受控数据库身份描述符一致后才计算identitySHA；描述符不含密码、完整连接串或认证参数。相同主机不同获准schema/account可隔离，主/authority不能是同DataSource或同实际schema/account；不能从HTTP、capability自报值或单独配置字符串推断实际连接已绑定。实现须复用现identity/source装配的实际连接隔离核验；如原核验没有提供完整描述符，补限定连接读取器，不能以默认值填身份。

`requireCurrent(expected,now)`核租约仍活动、同一Connection归属及实际连接身份，Clock调用前后均严格早于certificate/approval/Source/Task最短截止，再用`watermark.current`核同一decision/epoch/rowVersion/history、`availability.requireFields`核完整SAVE/INDEX矩阵及每字段useUntil，并核同Task组织/authorizationVersion/scope、安装pin与全部批准/保留/map/capture真实字节。写前、主commit前和输出前分别调用；本回调不能随新版来源或维护版更新expected。保留证据解释采用既有确实覆盖的政策合同，缺覆盖拒绝启用，不造新的保存期限。

锁等待与总deadline沿§9.2.2；Source withdrawal先占id4进入PENDING时本操作拒绝，若本操作已持id4锁则其现时转移等待本回调释放。源变更/身份撤销的并发反例必须用两个真实数据库连接证明排序，不声称跨库全局原子事务。双连接出现死锁或超时即按实际主事务状态关闭，不重试暗换scope。若主commit已完成而authority结束、最终校验或响应失败，保留原COMMITTED回执及不确定输出事实；不得宣称主写一起回滚，以同键当前准入对账恢复响应。

#### 9.7.3 重放、撤销与源码出口

同键回执只在新一次真实租约、相同requestSHA及上述全部现时批准检查通过后返回；不以历史approvalSHA或原maintainerRef替代认证，不重复插入任何投影行。旧维护版、过期证书/批准、pin缺失或改变、Source PENDING/撤回/新合同、Task版变更或原件/保留缺失均先拒绝，即使回执仍存在。pin移除是配置关闭；确切动作/范围批准撤销通过既有维护主体撤销或Source撤回使独立authority失效，首版不新增仅动作级在线撤销入口。重新添加旧pin或恢复旧主库不能越过现时独立水位；不得自动重准入Source、初始化水位或延长旧批准。后续确实批准新scope须形成新Source决定和新安装pin，并重新核完整capture；不覆盖旧回执。

首个源码出口必须同时包含具体`ProductTrademarkSourceProjectionAuthority`及固定维护租约生产实现、真实JDBC原子投影/回执、完整capture认证和SQL列表/详情；不把空Authority端口或只mock它的正例称为生产适配完成。组件输入可为明确标注的隔离测试资料，批准pin与Source admission/id4水位、真实TLS证书维护登记、Task/保留均由测试安排，产品代码走相同路径，无fixture/default身份分支。

必要反例为：同schema但未pin的自制批准拒绝；pin与Source capability不一致拒绝；同Subject错证书/维护版/组织/Task/auth版/源登记版/map/库身份拒绝；逐字段SAVE或INDEX缺失拒绝；Source主库恢复到旧ACTIVE但独立水位为PENDING/新代次拒绝；执行中真实维护撤销或Source撤回串行；恰到期及外层陌生事务拒绝；同键重放零新写/异SHA冲突；任一capture成员失败全主写回滚；主commit后模拟输出丢失可由同键真实准入查回。随后执行§9.5 SQL/HTTP/UI出口。root规定前后治理检查、实际DM8/启用/部署/真实流/B各自取证；本设计修改本身不构成任何实现或运行通过。

### 9.8 首个真实SQL持久化窄D审定（2026-10-13，Codex）

PC-CON-TRADEMARK-SQL-PERSISTENCE-01实现§9.2～9.3的V008增量迁移V113、完整capture原子投影/原键回执与SQL查询/确切版详情，复用已验证mapper。仅内部包私有ProductTrademark*存储，不自动Bean/HTTP/导入启用；既有可信capture只是完整性事实，权限、Source用途、具体动作批准必须由后续真实应用边界先核。要求精确本DataSource当前事务及固定已核成员集合，拒任意全库查询；完整capture所有索引/关系重算后才COUNT和SQL分页，不能只验当页。§9.7生产批准适配仍需集成审阅，不因本窄D关闭全装配D。H2实际SQL出口含21条跨页COUNT、多主体类别EXISTS不重复/完整详情、页外索引篡改整体失败、同键零重复/异摘要冲突、成员失败整批回滚和数据库故障不伪空；保留DM8与实际来源启用未完成。V113不在本批人工安装profile自动登记或执行，源码与既有商标类只做本delta。

本窄D补全§9.2.2固定request canonical根字段`approvalSHA`，绑定确切批准原件SHA，进入requestSHA并与原生approval_sha列核对；它是技术摘要补全，不是批准已存在或实际动作已授权。临时lease runId/剩余时长仍不入摘要。复用binding须同时认证原capture_request_id的原回执payload/hash/全部原生元数据及原期限闭包，不能只比较两个可同时篡改的expires_at列；原回执先核标量状态/期限，再读取正文CLOB。savepoint回滚失败将精确ConnectionHolder标rollbackOnly，调用方捕获也不能提交半批。

### 9.9 生产Source授权只读认证适配窄D（2026-10-13，root审定）

`PC-CON-TRADEMARK-AUTHORIZATION-01`关闭SQL持久化后第一个缺口：从真实当前Source、独立维护身份、受控证据及安装固定pin核出确切商标投影动作的认证事实。必要性是mapper只证明字节完整，store只证明事务持久化；二者都不能声明当前许可。复用§9.6 mapper、§9.8存储合同及现生产Source watermark/availability/evidence files，不改mapper、SQL、共享Proof/profile，不增加迁移、Bean、HTTP或导入入口。退出是实际双库行、TLS证书和受控文件下的认证/复核正反例；它不使导入可用，也不替代实际资料权利、部署、持锁提交或业务验收。

仅新增同包内部三个职责类：`ProductTrademarkSourceProjectionAuthority`负责真实身份、Source/Task/scope及现时事实闭包，`ProductTrademarkProjectionApproval`负责§9.7.1固定schema与canonical批准解析，`ProductTrademarkProjectionEvidence`负责受控文件完整装载及mapper调用。必要辅助值类型放在相应类内，不新增空Authority端口、通用批准平台或调用者可注入的allow布尔值。首版pin最多8项，构造时防御性复制、拒重复source/contract或冲突项；空集合允许对象存在但每次认证拒绝，既无默认源也无默认批准。

#### 9.9.1 真实身份和来源事实的封闭读取

§9.7.1的projectionApproval完整canonical字段补明：新增`projectionObjects`有序集合固定为`RAW`,`BINDING`,`INDEX`,`RELATION`,`RECEIPT`，以及UTC有限`retainUntil`,`backupUntil`,`recoveryUntil`；这些位于同一个projectionApproval对象内，和全部既有字段一起纳入approvedScopeSHA（仍仅排除approvedScopeSHA自身）。受控批准原件使用`TM-PROJECTION-APPROVAL-V1`，其`scope`必须与projectionApproval去掉`evidenceRef`,`evidenceSHA`,`approvedScopeSHA`后的canonical内容完全相同，避免实际批准脱离capability及循环摘要。实际retention/backup/recovery证据分别使用`TM-PROJECTION-POLICY-V1`，含purpose（RETENTION/BACKUP/RECOVERY）、sourceId/contractVersion/organization/taskId、同一projectionObjects和有限until，以及sourceRef/version/responsibleParty/decidedAt；证据真实SHA受完整批准保护，范围逐项匹配、until与对应批准截止相同。该结构只是已有明确批准的技术承载，不能由source字符串生成许可；具体源政策未获准或不覆盖全部对象时启用关闭。rights原件不因本schema变为第三方权利授予。

现schema事实补明：`con3_data_scope`只含TASK_METADATA及Task/organization关系，没有source或expires_at列；Source关联由当前product_decision的source/Task/scope闭包认证，scope期限使用其所属Task现期限，不能虚构新列。下述Task/source关系均指该闭包。

认证只接受真实`HttpServletRequest`和选择用sourceId/contractVersion；批准主体、组织、Task版、证书、用途、原件路径和摘要都不能由请求替代。调用现`ProductMaintenanceProof.require(request,SOURCES)`保留真实维护审计，另从独立authority的单例`id2_maintainer`读取subject/organization/certificate_sha/allowed_roles/status/version，核实际安全TLS连接及X509叶证书当前有效期、SHA、ACTIVE和精确SOURCES角色，并逐项匹配pin批准的subject/certificateSHA/maintainerVersion/organization。该真实行在整轮认证前后重读并要求完全相同；禁止把`requireOrganization`的第二次快照拼成同一见证。缺TLS、伪请求header、过期证书、角色漂移/撤回/错版本一律拒绝。只读前后认证不能证明撤回与主库提交串行，此限制见9.9.3。

从当前`con4_source_current`关联确切contract/admission/product_decision/verification读取批准完整元组，不查询任意历史最新行；配合`ProductSourceWatermark.current`的独立READY/ACTIVE代次与完整历史认证，拒PENDING、WITHDRAWN、intent、恢复旧主库或history漂移。核现`con3_task`的真实organization/authorizationVersion、现ACTIVE组织和确切`con3_data_scope`的Task/source关系；批准Task版必须逐字匹配真实Task，不能采用历史批准的组织和Task版代替当前行。保持现Source rights状态规则，不发明新的已许可枚举，也不自动更新Source状态。

SAVE核capture实际全部业务字段：NUMBER、NAME、APPLICANTS、ADDRESS、CLASSES、DATES、STATUS、SOURCE_REFERENCE及确实捕获的IMAGE/HISTORY；INDEX只核实际SQL索引及关系使用的NUMBER、NAME、APPLICANTS、CLASSES、DATES、STATUS。每组用现`requireFields`核完整同一Active，逐字段`useUntil`求有限截止；所有组须等于同一个当前Source元组。正文没有IMAGE/HISTORY不生成或补造该用途；捕获存在则必须核SAVE，当前基本信息投影不擅自INDEX这两类。结构性sourceRecordId/sourceRef继续按mapper语义核，不借专利TITLE/CLAIMS字段扩权。

主库及authority真实连接读取受控的server/schema/user身份并计算固定摘要，匹配批准primaryDatabaseIdentitySHA/authorityDatabaseIdentitySHA且满足已有独立库隔离规则；不把口令、凭据URL或原始连接串写入批准/错误/结果。相同server的确切独立schema/account按已批准隔离合同处理，相同DataSource或相同有效schema/account拒绝。没有真实连接元数据时不能接受调用方自行声明的数据库身份。

#### 9.9.2 受控原件、批准和有限期限

从当前admission的capabilityRef/SHA选择唯一固定pin，逐项核pin的capability与approval证据Ref/SHA及approvedScopeSHA。严格结构化解析`TM-PROJECTION-CAPABILITY-V1`及完整projectionApproval，拒重复键、尾随内容、未知schema、缺字段、无穷期限和非固定operation。approval evidence真实字节先由pin SHA认证；capability中的evidenceRef/SHA必须与该固定pin完全相同。approvedScopeSHA依§9.7.1 canonical全字段计算，不能由调用者任意指定；批准原件出处元数据必须非空并与同一批准及admission责任方一致。此动作证据只声明本项目获准动作，不证明第三方权利来源本身。

`ProductSourceEvidenceFiles.verified`读取当前verification的manifest/records及每个records成员实际raw文件，核真实长度/SHA及有限成员/总字节预算，装成已完成mapper的Input；map与独立nature文件实际读取后核mapId/version/SHA、nature source/contract/登记版/code。mapper输出manifest/records/inventory/bindingSet必须逐项匹配当前verification和批准；不存在只按capability声明拼造VerifiedCapture的路径。rights、retention、backup、recovery均读取各自获准引用的真实字节并认证SHA；保留期限采用批准的有限retainUntil/backupUntil/recoveryUntil明确值，且源admission retention tuple与批准ref/version/SHA一致。范围未明确覆盖新增raw/binding/索引/关系/回执对象、期限或备份恢复覆盖缺项一律关闭；引用文件存在与SHA正确不能自行扩大其业务范围。

expiresAt取批准validUntil、Task/scope实际期限、证书notAfter、Source contract/admission、每字段SAVE/INDEX、实际覆盖该投影的retainUntil和recoveryUntil的最短值；notBefore也核现时。backupUntil单独核获准有限覆盖，不拿较长备份期限延长在线访问。所有实际期限须在认证结束时仍有效；无对应有限事实关闭。人工180/365/30规则不适用于商标。本窄包只读取和认证已有批准，不生成生效批准、初始化水位、登记pin、改Task/Grant、延长期限、恢复或清理资料。

#### 9.9.3 只读见证、复核与后继持锁写入边界

认证结果是authority私有构造的包私有`AuthenticatedScope`，绑定产生它的owner、线程、确切固定pin、完整前后事实摘要及有限截止；只提供后继同包应用所需的只读事实，不公开`ProductTrademarkProjectionStore.Context`、写回调或“可直接投影”方法。不得把它命名为lease或GrantedScope。调用者不能构造、改期限、改原件或复用其他owner的scope；Input/字节和列表均防御性复制。scope不是权限缓存，即便认证刚完成也不能据此调用store。

包私有`recheck(request,scope)`重新执行同一TLS主体/maintainer、真实Task/scope、固定pin、当前Source/独立水位、SAVE/INDEX及所有原文件读取与mapper全闭包核验，要求认证结果与原scope完全一致并再次核有限截止；拒跨owner/线程、错证书、错源/版、文件或批准/用途/期限漂移，失败只关闭，不暗换最新批准。现维护审计属于独立authority真实副作用，主库无写入；复核失败不伪称审计一起回滚。

后继真正导入Task必须由root在共享`ProductMaintenanceProof`新增独立SOURCES持锁租约，绑定精确manager/ConnectionHolder/Connection/thread和该当前maintainer行，并在租约内完成主事务提交与终检；还须锁当前Source/Task/scope及字段用途、关联实际数据库身份。只有该应用拥有的持锁上下文才能从本只读事实构造store Context并调用project。此窄包不提供该转换，也不调用store；前后检查不能替代持锁权限或消除commit/revocation窗口。后继另核`kb_source`实际登记映射，不能为外键自动造来源性质或许可标签。HTTP普通读取与维护导入分别继续其自身D，不要求普通用户使用维护TLS渠道。

#### 9.9.4 专项组件出口

专测使用隔离实际JDBC主库/authority现Source行、当前Task/scope行、受控临时证据文件和真实X509证书，复用既有Source测试建库/准入夹具和mapper输入，不写生产DB。正向证实完整固定批准/SAVE/INDEX/真实身份生成包私有只读见证及同证据复核；负向涵盖空pin/变pin、只QUERY、错Task版/org/scope、维护撤回/错cert/expired TLS、Source PENDING/撤回/旧主恢复、off-page raw或map/nature/批准篡改、期限恰到期、跨owner/线程/不同request主体复核及实际双库身份未隔离。每反例证明没有主库投影写、无store调用；现维护审计副作用明确保留。不把组件TLS请求对象/JDBC测试库冒实际部署、DM8、用户HTTP、运行导入或业务验收。

### 9.10 普通Task商标查询、确切详情及页面窄D（2026-10-14，root审定）

`PC-CON-TRADEMARK-READ-API-01`关闭普通用户尚不能通过正式HTTP读取已获准商标投影的缺口。复用已完成域Service/Port、SQL Query/Store.verify、mapper、ProjectionApproval/Evidence及现Task/身份/Source水位。root于本聊天审定以下技术增量，规定前检2026-10-14 01:17:44北京自然终态后实施。源码和隔离组件出口不代表真实资料许可、DM8、部署或业务验收；不物理导入资料，不推进维护writer，不让普通人提供维护TLS证书。

生产增量限定`ProductTrademarkReadTransaction`、`ProductTrademarkReadSources`、`ProductTrademarkReadCaptures`、`ProductDm8TrademarkLibrarySource`、商标专属Controller/Configuration/RouteSecurityConfiguration；必要专属小值类型同包封装。原`ProductTrademarkSourceProjectionAuthority.authenticate/recheck`继续拒ambient，不将其SOURCES/SAVE/INDEX身份路径用于普通读取。原业务Service/SQL内核、共享Access/Proof、SQL迁移不改。

读事务实际READ_COMMITTED，精确DataSource/manager/ConnectionHolder/Connection/thread，拒外层事务和未知资源。实际JWT decoder认证及现HUMAN/账号/session/client/key、Task READ/Task authorizationVersion先核。锁序按现HumanRequestLocks及Grant revoke/Source withdrawal：Task、组织、身份，全部按domain/type/id/action/purpose排序的epoch及对应grant，全部source_current，再scope pointer/scope、contract/字段用途，最后receipt/binding/投影。独立authority只前后认证水位，不另占其行锁；任何PENDING立即关闭，不与先置PENDING的写端逆序占锁。本功能专属JdbcTemplate的锁语句配置5秒JDBC查询上限，Spring主事务30秒及单调计时总读取30秒；共享水位普通读取沿实际事务期限，不宣称更改了共享JdbcTemplate。实际最短有效期进一步收紧；超时不返回半结果。

来源候选只取受管固定商标pin集合与本Task当前source scope交集，最多8源；具体列表来源由服务器推出，HTTP不能传组织、授权范围、pin或receipt。逐源核当前contract/decision/epoch/rowVersion/登记版，以及SOURCE_SCOPE的QUERY/SOURCE_QUERY、DISPLAY/SOURCE_DISPLAY两独立Grant、scope版/Task版/组织/期限。Task READ不授来源用途。无可授权来源返回CAPABILITY_UNAVAILABLE，选中来源的当前事实/原件不完整整体失败，绝不伪0或切开发源。

QUERY字段按实际SQL：固定排序registration_date需要DATES；sourceId/stable_recordKey属于结构定位，不冒业务NUMBER。非空registrationNumber→NUMBER，name→NAME，applicant→APPLICANTS，categories→CLASSES，statuses→STATUS，registeredFrom/To或validFrom/To→DATES。确切详情的source/record/version仅定位，不凭空要求QUERY NUMBER/DATES。ReadBoundary不改现Request结构：私有witness分别持有QUERY和DISPLAY字段期限，SQL适配在使用实际筛选前核QUERY；实际返回的非空字段/关系逐项核DISPLAY。SourceScope.fields只表达获准DISPLAY字段，不能替代QUERY认证。来源缺字段用途不得静默补值或发模型。

当前COMMITTED选择先核Task/org/authorizationVersion、当前Source完整元组、受管批准pin、批准/保留/map/capture完整摘要及有限截止。多个确切匹配回执可由不同合法key形成，按committed_at/receipt_id稳定选择，不任取最新。Store.verify重新核全部成员、页外SQL索引/完整关系及binding原origin回执；后来回执不能延长旧binding截止。缺匹配关闭，不能自动补导入。文件/批准解释复用既有Approval/Evidence，主/独立连接身份实际认证，读取不要求维护者当前证书或重新执行维护动作；原receipt固定期限仍严格生效。每源私有witness含当前水位、scope/grants、批准/文件摘要和选择回执，不能由public AuthorizedScope构造。完成COUNT/分页/详情后再次读取全部当前原件与事实、身份/Task/两水位和期限，完全一致才释放响应，不从新范围暗重试。

正式GET路径为`/v2/consultation/tasks/{taskId}/trademarks`和`/.../trademarks/sources/{sourceId}/records/{recordId}?version=`，沿部署现context对外配置，不硬编码双重/api。Bearer及X-Request-Id，controller不接开发actor。page=1/size=20，size仅10/20/50；沿原domain的400/401/403/404/429/502/503/504，不重新发明错误状态。错误响应只固定code，不暴露SQL、文件路径或凭据；真实已失效session映401，非HUMAN身份映403，工程或数据库异常保持503。全局ProductIdentityTransportFilter仍核TLS和独立水位；正式响应Cache-Control:no-store。HTTP外层包含taskId/taskVersion/expiresAt/data，expiresAt包含所有核验截止及Store.verify给出的binding原origin最短期限，也不得晚于本次要求仍有效的backupUntil。无认证安全路由必须拒绝，功能默认off；启用要求identity/source engine与受控证据/固定pin配置、真实表可用，缺项启动关闭。列表/详情不宣称完整事件/图样已适配；能力保持UNKNOWN，独立原件时刻及本次核验时刻分开，refresh仅重新核验当前获准本地capture，不声称上游抓取更新。

页面位于`frontend/src/features/consultation/product/trademark/`，复用咨询配色/字号与44px控件，显式label、键盘焦点、窄屏卡片、忙碌/错误/可靠分页、完整主体类别和来源版本。props为token/taskId/onBack及可选onSessionExpired；401先清正文，再通知root统一退出会话。筛选仅内存保留，列表进详情再返回保留条件和页码；Task/token变化立即隐藏旧正文并取消旧请求，只最后请求可写回。失败清正文，不将503画成空列表，不保存token/结果到URL/localStorage；到HTTP expiresAt立即清除当前结果。root负责共享Task入口及`?task=<id>&view=trademarks`独立深链、PKCE state/verifier绑定，登录后直接挂商标页面，不以专利retrieval-workspace成功为前提。返回原Task保留Task身份，页面不提供内部Source/actor输入。

技术URI补充（2026-10-14，root审定）：全局传输过滤器拒含百分号的URI路径，而获准原始记录标识可能含中文及符号。因此保留原path详情兼容入口，追加固定`/.../trademarks/records/detail?sourceId=&recordId=&version=&refresh=`，前端通过URLSearchParams使用此入口。三项坐标必须各恰好一个非空值，refresh可省略或恰好一个true/false；重复、残缺、未知参数均400，绝不任取首项。固定路由复用同一DETAIL服务、当前授权及确切版本，不放宽共享过滤器、不授跨来源权限。隔离HTTP使用实际生产Configuration的Bean装配、真实JWT/双库/受控原件，包含中文与`/+%?#&=`标识正例及重复/残缺参数负例；配置装配成功和组件正例仍不冒真实网络TLS/DM8/资料许可。

源码出口必须含同一真实产品链的隔离JDBC+当前Grant/Source+受控原件+HTTP专测、前端筛选/分页/详情/返回/错误/旧请求失效反例。实际资料、人员/Grant、保护/保留和运行准入启用前核；旧USPTO开发capture及本组件合成夹具均不成为实际正例或许可依据。完整CON01图样/历史及专业业务验收继续各自门禁。
