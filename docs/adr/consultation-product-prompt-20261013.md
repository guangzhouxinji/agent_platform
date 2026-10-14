# 咨询提示词最终产品窄D

日期：2026-10-13。技术设计责任方：Codex。父范围：CON-10 / EXP-CON-CONFIG；任务和准入状态仅由docs/08维护。本文为现有docs/56 CON-10的详细补充，不是新的实施计划，不宣称完整CON-10 D通过。

## 1. 来源、差额与边界

来源为requirements/知识产权咨询助手/知识产权咨询提示词管理需求文档.md §1-8、governance/contracts/CON-10.json当前ready合同、docs/56-V2咨询全能力详细设计.md CON-10、docs/02 §25-27。本轮对原需求全文和当前合同反查；不修改requirements。正式源码从最终合同实现，不复用ConsultationGuidanceService中的固定LOCAL-GUIDANCE-V1正文、开发身份或DEVELOPMENT_DRAFT路由。

当前交付差额是提示词模块没有正式版本、变量校验/渲染、分权或停用/撤销内核；用户授权并行开发后，该差额可在人工交接主线之外隔离完成。可复用的是原文已明确的业务行为、docs/56的PromptRevision概念，以及现有模块分层和Clock注入方式；旧草稿仅作为不得冒正式行为的反例。窄工作结束观察为确切版本渲染与状态判定通过，缺变量、未知变量、越权正文、撤销旧引用及并发冲突拒绝；组件结果不冒实际发布或专业验收。

本窄D只准入纯领域版本/校验/渲染和应用端口合同。HTTP、JWT/权限适配、持久化DDL、真实发布、使用事件存储/统计、模型外呼及页面需对应装配D，不以这里的端口签名宣称已具备。具名发布人、许可模板变量/来源/默认规则、正式正文/中心确认、保留依据或模型通道不明只阻依赖操作；不阻供应商中立、默认拒绝的本内核。

## 2. 版本和字段

PromptRevision保存稳定promptId、revisionId、类型GUIDE/SYSTEM、名称、正文、场景/意图稳定ID集合、适用用户范围引用、语言、GUIDE展示顺序、变量schema、版本号、DRAFT/PUBLISHED/DISABLED/ARCHIVED状态、emergencyRevoked标记、生成来源、创建/修改/发布主体与时间、变更说明、测试记录引用、内容确认引用和保留政策引用。编号作为字符串，不由名称或序号代替；创建后不得修改promptId或类型。版本号与并发代次分开。运行引用包含promptId/revisionId/正文与schema摘要，禁止latest解析。

修订值对象不可变；DRAFT每次保存生成新不可变修订，草稿头以expectedGeneration CAS前进。发布仅从确切DRAFT完成；一次原子事务中校验当前权限、内容确认、变量政策、关联字典和测试记录适用性，更新发布投影并追加真实操作者事件。已发布正文/schema/范围不能原位修改；复制建立新草稿，原revision及原绑定不变。发布新版本不会改已有Agent/Flow绑定；新绑定必须由配置域发布。

内容摘要编码为PROMPT_CONTENT_JSON_V1：使用标准JSON序列化固定顺序结构，绑定kind/name/body、排序后的场景与意图集合、audienceRef/language/displayOrder和完整有序变量定义（含类型/required/source/default/empty规则）。`referenceFor`生成SHA-256，PromptRevision构造时复算并拒绝错误摘要；不拼接字段字符串。身份及版本由Reference另行绑定。变量政策摘要与原件/当前范围认证属于可信VariablePolicy端口，内核Policy值对象不声称自行认证原政策文件；没有可信端口不能把任意Policy对象作为启用依据。

普通停用禁止新绑定、GUIDE新展示/点击，但既有确切SYSTEM固定引用仍可调用。紧急撤销为独立不可逆调用禁用标记：无论固定引用、普通停用或归档历史，所有后续运行调用都拒绝。归档仅改变管理可见性/新增使用准入，不删除历史引用，不解除撤销；历史固定引用的普通停用资格不能从ARCHIVED状态自行推定，需保留其原发布与停用事实。无历史引用的草稿删除仍须有删除/保留权限及装配D；本内核不执行物理删除。回滚配置仅引用另一个当前允许版本，不能恢复已撤销版本。

## 3. 变量schema与确定性渲染

技术语法选择为双花括号占位符`{{variableName}}`；名称采用ASCII字母开头、其后字母/数字/下划线。无表达式、函数、循环、条件、反射、环境变量、脚本或嵌套占位符。出现未闭合/嵌套标记或不合法名称直接报INVALID_TEMPLATE；不静默保留占位符。此语法是Codex技术选择，不冒原文批准的业务变量名单。

每个VariableDefinition包含name、技术值类型、required、sourceRef、允许默认及typedDefault。首窄内核支持STRING/BOOLEAN/INTEGER/DECIMAL/STRING_LIST这些纯值类型；这只是可校验的引擎能力，不批准模板使用任意这些变量。不得以Java字符串转换接受错误类型；INTEGER不用浮点转换，DECIMAL使用精确十进制表示，STRING_LIST保留顺序且逐项检查字符串。null视为缺值；空字符串/空集合是否允许由确切变量政策指定，不默认等于缺值或有效。

变量政策端口必须返回当前获准且版本化的name/type/source/default/empty-value规则；schema与其逐项一致才可进入发布或实际渲染。没有政策/来源授权时拒绝相应启用，不套用人工180/365/30日。未定义占位符、重复schema名称、schema和政策类型不符、未知输入变量或来源不符均逐项报错。定义但未被正文使用的变量允许保存草稿，发布完整性校验指出未使用变量，具体业务是否允许须由模板政策明确；不自行规定通用发布禁止条件。

运行值由可信调用上下文和VariableValuesPort解析，禁止客户端声称sourceRef即获得可信值。预览样例是明确TEST目的输入，不能伪装生产来源。先校验完整schema/政策/所有变量再产生结果；缺值仅当该变量允许默认且默认值经类型/政策校验时使用默认，其他缺值返回MISSING_VARIABLE。可选且无值没有默认的变量不填空串：若正文引用它则报MISSING_VARIABLE；若未引用则不产生片段。无部分渲染结果可进入模型。

解析输出为不可变有序LiteralSegment/DataSegment，DataSegment记录变量名、类型、来源证据引用和值，不递归解析值中的`{{...}}`。诊断预览把数据按JSON值编码并带类型边界显示；正式消费保持结构化分隔，用户/文献始终进入数据消息，不能被适配器拼进高权限系统指令。引擎不把转义等同模型抗注入证明；工具/资源授权仍由执行规则服务独立校验。GUIDE仅输出普通问题数据，不具有SYSTEM指令路径。

RenderResult返回确切revisionRef、schema/policy摘要、模式TEST/RUNTIME、segments及逐项错误；成功与失败互斥。内部SYSTEM渲染内容仅返回给当前授权管理预览者或可信服务身份；普通GUIDE调用既不能查询SYSTEM正文，也不能通过错误、关键词查询、版本对比或统计响应侧漏正文。

## 4. 应用端口与当前授权

PromptRepositoryPort提供exactRevision、管理分页查询、lockedHead、CAS草稿头和事务内事件追加；不得以records全量读取在应用层分页。管理查询编号精确、名称/内容包含、类型/场景/状态过滤；默认updatedAt降序、20条，稳定并列次序为promptId/revisionId。限额上限由已批准技术配置供给，未知不默认无限。普通引导查询只请求GUIDE/PUBLISHED、当前语言/用户/场景范围，并按显式配置顺序和稳定ID排序。

PromptAuthorizationPort逐请求使用已验证主体、机构与资源范围，动作分别为管理元数据读取、内部正文读取、草稿写、预览测试、发布、普通停用、紧急撤销、归档、引导展示、固定版本服务解析。动作是提示词域操作，不新增或伪造共享Grant action；适配层须有现有权限协议的明确映射。Agent/模型不能获得人工发布或撤销操作。无策略/适配器/当前许可、主体不符、期限恰到或版本范围不符默认拒绝，不能用旧v0身份或历史成功放行。

PromptCatalogPort验证场景/意图稳定ID当前存在和允许关联。PromptVariablePolicyPort核当前许可schema；PromptContentApprovalPort核中心确认与确切正文/schema摘要，不把测试通过当内容批准。PromptRetentionPort返回有来源/版本/责任方/日期/范围的有限期限及清理/恢复约束；缺政策阻保存持久对象，但不阻无持久输出的纯语法验证。

PromptBindingPort证明既有固定引用于停用前由有效配置发布建立，不能相信调用方传existing=true。调用时在同一持久读取边界锁定确切修订/撤销代次并核当前主体/内容/变量政策/期限，返回前再核；模型或工具真正执行前再次核同revision与撤销代次。服务检测撤销立即停止后续调用，并把不可用事实交给配置域；不自行选择新模板或伪造模型结果。原合同允许配置回退或明确不可用，实际回退顺序与用户文案由配置域获准策略提供；未配置时返回TEMPLATE_UNAVAILABLE，不推定继续旧版。

## 5. 失败、事件、保留与恢复

错误码为INVALID_TEMPLATE、UNDEFINED_VARIABLE、MISSING_VARIABLE、VARIABLE_TYPE_MISMATCH、VARIABLE_POLICY_UNAVAILABLE、INVALID_ASSOCIATION、CONTENT_APPROVAL_REQUIRED、VERSION_CONFLICT、ACCESS_DENIED、TEMPLATE_UNAVAILABLE、RETENTION_POLICY_UNAVAILABLE及DEPENDENCY_UNAVAILABLE。变量错误只返回变量名/规则类别，禁止回显实际用户或文献值；无内部正文权限时不返回模板摘要内容、占位符片段或对象存在性细节。存储故障返回可追踪故障，不能冒空列表或不存在。

后续持久实现将状态变化、不可变修订、事件、requestId摘要回执放在同主事务。相同作用域原键且相同操作摘要只读对账，不重复发布/累计；原键异内容报冲突。未知提交结果先读取准确原键/版本回执，不能盲目重放。重启恢复须核正文/schema原字节摘要、固定配置引用、当前权限、撤销状态及当前期限；历史证据不授现时权限，旧史缺摘要或政策拒绝，不自动补初始化或复活。

GUIDE展示/点击、SYSTEM渲染成功/调用成功/失败分开事件类型，测试事件明确TEST且排除正式使用频率。eventId需按可信主体/确切修订/类型/目的作用域去重，重复异内容冲突。统计同时返回时间范围、截至时间和延迟，不使用一个混合使用次数。本窄D不确定保留期限，不保存正文值、模型样例或业务变量输入到操作摘要；使用事件、测试记录、正文版本及审计各自需要现有覆盖规则或单独确认，未覆盖不默认永久。归档不能扩大来源许可或替代到期访问关闭；恢复不得使到期记录重新可见。

## 6. 预注册反例与最短源码增量

| 反例 | 预期观察 | 来源 |
|---|---|---|
| 模板含未定义占位符、重复schema或嵌套表达式 | 发布/渲染拒绝；无残留占位符结果 | PM-02、原文§3 |
| 缺required/正文引用变量且无允许默认；错误类型/未知输入 | 逐项拒绝，模型调用次数为0 | PM-02、TASK-AC-001 |
| 值含新占位符、系统指令、工具请求或JSON分隔字符 | 仍是DataSegment，不递归，不获得权限 | PM-01/05 |
| 普通用户按ID、内容关键词、版本对比请求SYSTEM | 拒绝且无正文/实际值泄漏 | PM-01/07 |
| 新版发布；旧固定引用 | 仍使用旧revision，不隐式换版 | PM-03 |
| 停用后新绑定/新GUIDE展示与既有SYSTEM固定引用 | 前者拒绝，后者核真实既有绑定后允许 | PM-04 |
| 紧急撤销后命中缓存/旧会话/恢复/固定引用 | 所有后续调用拒绝，不自动换模板 | PM-04、TASK-AC-002 |
| 发布途中失败、旧expectedGeneration、同键异正文 | 无部分发布/覆盖，原回执可准确对账 | PM-03/07、DM8条款 |
| 权限/政策/期限在末次复读变化或恰到 | 拒绝返回正文/执行 | SEM-AC-003 |
| 真实DM8不可用 | 明示未验证；H2组件不冒兼容验收 | SEM-AC-001 |

最短真实源码增量为`consultation/prompt/domain`内不可变PromptRevision、VariableDefinition、TemplateRenderer、PromptLifecycle及结构化结果/领域错误，配合`consultation/prompt/application`内中立端口契约。首轮只编译并运行纯领域组件反例，不启用HTTP或存储。该增量使用最终状态/版本语义，可由后续真实装配直接消费；测试样例仅是反例输入，不形成固定候选产品路径。

隔离所有权为本文、上述全新prompt包及全新prompt测试包；不修改共享Task/Access、旧guidance、docs/08/02/56、pom、迁移或共享输出。主owner登记实际窄任务/复核D后再授权代码；统一前后verify-all及集成由主owner执行。

## 7. 仍需事实与独立复核

CON-10-OQ-001正式正文/中心确认阻模板正式发布；OQ-002具体变量名单/来源/默认规则阻相应模板政策准入，不把本技术语法当答案；OQ-003具名角色映射阻实际发布/正文访问；OQ-004有限保留依据阻持久保存/清理；OQ-005实际回退策略阻该策略启用，模板撤销拒绝本身已有合同；OQ-006实际环境/模型阻真实DM8/模型验收。均不标已确认，不向用户重复索取已确认人工规则，人工V3期限不能扩用至提示词。

第二遍反查修正：SYSTEM结果不得用一个拼接字符串默认进入system消息，改为结构化literal/data；普通停用资格不能只信existing布尔值，改为确切配置绑定证明；ARCHIVED不可暗推固定引用资格，保留原发布/停用事实；未用变量是否阻发布不能无依据增加规则，改为模板政策判断。业务状态由原需求拥有，纯技术实现不覆盖真实角色、许可、保留、回退和专业内容事实。

## 8. 提示词草稿工作区业务决定与装配窄D

业务决定来源为本执行聊天实际人类答复`request_user_input_async/call_CpDCEIjrpk5VOTToANjZIlBF[0]`，用户原文“确认上述授权边界与期限。”；本段由root向prompt_contract代理准确传达，不冒代理直接读取人类聊天工具。日期2026-10-13 Asia/Shanghai，版本PROMPT-DRAFT-WORKSPACE-DECISION/v1，责任方为当前项目产品用户。适用范围仅本项目咨询提示词内部草稿工作区、项目自有新增对象，不代签第三方权利，不涉及正式发布、模型外呼或官方决定。技术设计责任方为Codex。

本节归属已登记`ARC-CON-PROMPT-OPERATION-D-01`，依赖`REQ-CON-R-CORE-12`和已完成`PC-CON-PROMPT-DOMAIN-01`；任务状态仅docs/08维护。工作区五动作授权和90/180/7日保存规则已按§8.7确切提问确认；不增加元数据独立权限。既有§2-3内核窄D和12C、此次业务确认均不直接替代完整装配D或启用事实。

首包候选为真实JWT下创建/复制/保存人工草稿、确切版本读回/历史、获准变量TEST预览及内部工作台。原只读/预览候选在此扩为该可操作首包，保留其中可信来源/当前授权/失败关闭约束；不另建通用提示词平台。源码装配、组件验证、部署、真实流程及业务验收分别取证。

### 8.1 已核事实和不可复用的范围

- `ProductConsultationAccess`验证issuer/subject/sid/jti、id2_subject/session/current_epoch/精确Grant和独立水位，仅支持既有ProductAccessPolicy动作及TASK_WORK；它没有提示词Permission映射。现有TASK READ/WRITE不能由服务推定为系统正文读取、预览、发布或撤销授权。
- `ProductMaintenanceProof`仅有SUBJECTS/GRANTS/KEYS/SOURCES，都是身份/源维护职责，不是提示词内容批准或管理角色。旧MODEL_CONFIG_ADMIN_REQUESTER和v0默认主体不得复用。
- `ProductRetentionPolicy`明确只覆盖Task元数据。人工V3仅覆盖普通意见/包/回流，开发六个月仅属历史；此次独立决定首次覆盖本首包项目自有草稿及最小非正文追溯/回执与备份。正式测试记录、使用统计、发布版本不由此获批。
- §2-3的PromptRevision摘要、显式Policy和结构化渲染已经有12项组件证据；只调用内核不足以证明Policy可信，文件/权限适配必须自己核原件和现时状态。
- 现有JWT chain只匹配列举路径，不自动覆盖新prompt路由。后继必须新增独立且确切匹配的JWT安全chain，不依赖空AuthenticationPrincipal或默认路由规则。

### 8.2 最短边界及实际操作

人工草稿可以保存尚未取得正式变量/正文确认的声明，但必须保持DRAFT；语法/类型检查不构成批准。实际变量预览须匹配当前获准schema及TEST样例来源，缺政策只拒绝该预览，不把未知变量政策扩大为保存所有人工草稿的统一前置。草稿保存按本节已确认业务范围、实际Grant和装配D执行，来源资料另核保存用途许可。

发布、普通停用、紧急撤销、归档、GUIDE面向普通咨询用户展示/点击、固定SYSTEM生产解析、模型试运行及正式使用统计另按各自准入实施。草稿工作区不得以预览或保存形成已发布状态，也不将AI生成候选自动上线。首包不保存预览样例/渲染结果，不冒完整持久测试记录或模型测试管理；可保存无正文的最小预览诊断，范围和期限见§8.5。

技术接口为工作区下创建/列表，提示词下保存新修订/复制，确切读回/历史与仅TEST预览，详见§8.8。创建/保存/复制带原键和CAS预期代次；元数据与历史是READ的输出子集，不另设权限，SYSTEM正文不对无READ的主体开放。独立PromptRouteSecurityConfiguration须精确匹配这些路径并执行oauth.jwt解码；不能依赖既有chain自动覆盖。响应Cache-Control:no-store；错误不含正文、样例值或原件路径；无部分渲染进入模型。

### 8.3 受管修订与政策认证

复用`ProductSourceEvidenceFiles`的受控root/maxBytes/原SHA读取批准依据，不新增文件存储或平台配置库。批准原件引用与SHA来自服务端受管绑定；客户端提交草稿内容不等于可提交批准、路径或授权映射。读回由DM8不可变修订认证§2内容摘要，保存政策须有来源/版本/责任方/日期/范围；正式变量预览另外认证当前变量政策原件引用/SHA。正式正文确认另门禁发布，不误要求所有人工草稿已经中心确认。缺所需依据即拒依赖操作，不内置模板、角色或期限。

已批准证据的结构与读取格式由技术D确定，证据里的许可内容由责任方拥有；文件只证明给定声明与SHA一致，不自授权限。受管配置必须固定预期审批原件SHA和确切对象范围，禁止PromptRevision所在包同时自行提供并宣称可信授权。正式适配应从审批原件投影schema/source/default/empty规则，并核policySHA；不能直接反序列化一个Policy DTO后当批准。读历史文件仍须核当前期限/撤回依据，不让重启重新延长。

预览样例来源必须由确切政策明确允许TEST样例，不允许用TEST模式绕过来源许可。样例值只供内存渲染，不进入日志、回执、持久记录或模型；服务器按schema校验全部样例，变量值不构成角色/工具/数据权限。前后两遍核相同修订/所需政策/当前权限及期限；任何变化均拒绝回正文或segments。

### 8.4 已确认组织工作区边界与技术映射

**已确认业务边界**：仅项目内部、按组织隔离，分别授予创建、读取、保存、复制和变量预览五动作权限；创建不自动授后续或发布权。元数据、列表和历史查询使用同一READ权限，没有第六项元数据独立权。谁拥有哪项权限由真实授权方逐主体发放，不默认所有用户或创建人全权限；实际人员/凭据和实际Grant在对应功能启用前核实，不要求用户现在提供账号名单。

**Codex技术映射，须root装配D复核**：持久工作区登记给出UUID格式workspaceId、organization、scopeVersion和有限有效期，客户端不决定归属。Grant的domain为CONSULTATION，task_scope和data_scope均为`PROMPT_WORKSPACE:<workspaceId>:<scopeVersion>`配置命名空间，明确不是业务Task；resource_type为PROMPT_WORKSPACE，resource_id为`<workspaceId>:<operation>`，resourceVersion为scopeVersion。操作后缀固定CREATE/READ/SAVE/COPY/PREVIEW；它是五项已确认权限的机械坐标，不新增业务权限。内容修订版/CAS代次与授权范围版本分开，不因正常保存失去已有授权。

| 已确认动作 | resource_id后缀及既有action | 确切准入 |
|---|---|---|
| 创建首稿 | CREATE / CREATE | 目标工作区独立CREATE Grant；回执不授后续权限 |
| 列表、确切正文/元数据、历史 | READ / READ | 该工作区READ Grant；按持久归属及有效期筛选，不跨工作区 |
| 保存新草稿修订 | SAVE / WRITE | 已有prompt所在工作区SAVE Grant及CAS，不暗授READ |
| 复制为新提示词 | COPY / CREATE | 源工作区READ和目标工作区COPY Grant；两者组织一致，COPY不能用普通CREATE替代 |
| TEST变量预览 | PREVIEW / READ | 所在工作区PREVIEW及READ Grant，另核变量TEST许可 |

purpose使用既有TASK_WORK，沿ProductAccessPolicy不携Source的当前授权分支；这里TASK_WORK是既有身份枚举，scope坐标明确为配置工作区，不把普通Task Grant挪用至提示词。精确匹配issuer/subject/organization/domain/purpose、工作区/授权范围版本及动作坐标；客户端不能选组织、resource/action或用途。真实HUMAN身份/会话、准确id2_current_epoch及Grant、JWT时限和独立Grant水位在返回前复核；跨组织、缺Grant、撤权、恰到均拒绝。SERVICE生产解析另做委托D。

只读源码核查可行性：V032 id2_grant/current_epoch的resource_type/action/purpose是VARCHAR，id2_grant的task_scope/resource_id为200字符、data_scope为300字符、resource_version为BIGINT；Grant唯一键含准确资源/版本/动作/purpose，上述UUID坐标全部小于200。`ProductGrants.issue`对非SOURCE/SOURCE_SCOPE做准确非空验证，不要求业务Task外键，真实`/v2/identity/grants`仍由GRANTS维护证明和独立水位包装；无需新增身份枚举或迁移Grant表。维护者签发Grant不因此取得提示词内容管理权限。`ProductConsultationAccess`已支持精确ResourceFacts查询，但单独复用不足：新适配必须从持久工作区导出facts，核HUMAN/工作区期限及五动作后缀，不接受客户端facts或将服务身份当人；是否复用此类内部查询或独立适配由root负责。

### 8.5 已确认项目自有新增对象有限保存

覆盖复核结论：现有Task元数据政策、人工V3的180/365/30日及旧开发六个月规则均不覆盖本首包；此次独立人类确认依据见§8开头及§8.7。下表按确切提问的90/180/7日实施，不沿用人工期限、不永久保留。范围仅本项目自有草稿及必要元数据；第三方原文/变量来源须另有保存许可，均服从来源更短期限，不扩大原资料用途。

| 最小保存对象 | 已确认期限及固定起算 |
|---|---|
| 草稿正文、完整schema/default、场景/用户范围、不可变修订及内容SHA | 每修订成功提交起固定90日；新修订不续旧版，读取/预览不续期 |
| 稳定编号、所属工作区、草稿头等容器事实 | 仅随仍有效修订存在；最后修订到期关闭该对象，必要最小标识只在获准追溯期内保留 |
| 最小操作追溯、原键回执及无正文的预览诊断 | 每项事件提交起固定180日；真实主体ID、动作/对象/版本/时间/摘要/结果及清理结果，不含正文、样例值、渲染结果或凭据 |
| 项目自有新增对象备份 | 最长7日滚动；不借人工30日或扩大现有保护范围 |

草稿schema中的default是持久模板内容，不是临时预览样例，遵守修订90日。操作摘要仅用于确切版本与回执对账，不保存正文可逆副本或变更全文。预览样例/渲染结果仅请求内存；首包不承诺完整持久测试记录、模型试运行或正式使用统计。若来源资料有更短期限，取更短期限；无来源保存许可时拒保存相应内容，不能把声明为项目自有当权利证明。

**已确认到期处理**：期限恰到立即关闭正文操作和恢复；24小时内首次受控清理尝试，失败记录并保持关闭。备份按7日轮换；恢复前认证当前期限/清理依据属于Codex防止复活的技术职责，缺依据拒绝，不因备份存在复活对象。读取、新修订不续旧期限；预览仅内存，重启、恢复、原键对账或重新认证也不能改变原固定起算。最小审计/回执按各自180日退出，不永久保留墓碑。实际物理删除继续暂停；本确认不授予实际删除操作，也不解除既有部署暂停。

**已确认追溯影响**：清理后不能重建对应全文，追溯到期也清除。技术体现为90日后不能读回对应过期正文/schema或完整差异，180日后不能通过该事件/回执追溯操作者和结果；历史比较/复制应明确不可用，不造缺版、不以新稿替旧史。内容清理后仍有效的最小追溯只能证明动作/版本/摘要和结果。正式发布版本不在本保存范围，后续按其用途单独确定规则。

### 8.6 技术装配退出合同和必要反例

技术后继复用内核及现有真实JWT/独立Grant水位，DM8内保存不可变修订、CAS草稿头、动作事件和确切原键摘要回执；正文写与事件/回执同事务，失败回滚。相同原键同摘要只读对账，异摘要冲突；未知提交先核回执，不盲目重放。批准政策由受管原件认证且必须有限；缺政策不默认期限。完整物理表/锁序/期限与备份外清理依据、恢复核验、API及内部工作台由root窄D收口后实现，不把本业务提案当完整装配D已过。

反例包括有效JWT但只有TASK READ仍拒系统正文/预览；正文Grant不等于preview Grant；普通用户关键词/ID/错误侧信道不泄露SYSTEM；客户端伪造路径/policy/sourceEvidence/组织无效；错原SHA/错contentSha/错schema拒绝；批准依据缺失/被撤回/恰到/末次复读变化拒绝；TEST样例不递归、不变系统权限、不入模/持久日志；新prompt路由确实经过JWT解码；DB/原件故障503而非空/未找到。

增加持久首包反例：仅CREATE不自动获得正文/保存权限；同组织不同工作区Grant不能互用；READ包括元数据与历史，不再要求元数据独立Grant；普通CREATE不能替COPY；旧CAS拒绝且不覆盖新稿；同原键异内容冲突；保存故障无半修订/半事件；新版保存不续旧版90日；读/预览/重启不续期；期限恰到所有正文出口拒绝；过期备份/缺当前清理依据恢复拒绝；工作台迟到响应不覆盖新选择。UI仅消费同一API，不造开发身份、固定模板或权限旁路。

### 8.7 本次确切提问与确认记录

root准确转达的实际提问原文：

> 提示词草稿工作区的具体提案已写入 docs/adr/consultation-product-prompt-20261013.md §8.4～8.7，是否确认：仅项目内部、按组织隔离，分别授予创建、读取、保存、复制和变量预览权限，创建不自动授后续或发布权；项目自有草稿每修订提交起固定保存90日，最小非正文操作追溯/回执每事件180日，新增备份最长7日滚动。均服从来源更短期限，读取/新修订不续旧期；到期即关闭正文操作与恢复，24小时内首次清理尝试，失败保持关闭；清理后不能重建全文，追溯到期也清除。预览仅内存、不入模。此业务范围缺少已有依据，按项目AGENTS.md“不得默定权限/保留行为”需您决定；技术实现由我负责，人员/Grant在启用前核实，不解除物理删除暂停，其他已准入工作继续。

用户原文答复：

> 确认上述授权边界与期限。

该答复确认提问列出的五动作业务授权边界、90/180/7日范围和到期追溯影响；不是对旧草案全部措辞的无条件批准。旧草案曾把元数据列为独立动作，本次以真实提问为准纠正，不新增该权。存储/接口/字段、输入上限和恢复机械核验均属于以下Codex技术设计，不冒用户原话。实际人员/Grant及具体变量TEST许可在对应启用前核实；变量政策只门禁具体预览/发布，正式正文确认只门禁发布。草稿API/UI和持久工作流尚未实现，不能以确认或内核12C冒可用。

### 8.8 Codex接线范围、输入上限和剩余技术D

端点使用同一提示词工作区，不接旧guidance开发路径：`POST /v2/consultation/prompt-workspaces/{workspaceId}/prompts`创建；同路径GET分页列表；`GET /v2/consultation/prompts/{promptId}/revisions/{revisionId}`及`GET .../revisions`确切/历史读取；`POST .../{promptId}/revisions`保存；`POST .../{promptId}/copies`指定源revision和目标workspace复制；`POST .../{promptId}/revisions/{revisionId}/preview`仅TEST。prompt归属从持久行读取；目标组织从持久工作区读取，客户端只能选择ID。POST写操作需Idempotency-Key及expectedGeneration，创建不存在头时预期0；preview只请求内存、不持久样例或渲染输出。

创建/保存/复制成功仅返回准确promptId/revisionId/摘要/代次和回执，不以写成功暗授读取或回显目标正文；之后读回仍核READ。列表/历史属于READ，不向无READ者透露对象名称、正文关键词或版本差异。旧PromptPorts的元数据/正文操作名可作为应用内部区分，但真实授权适配统一映射已确认READ，不再要求两个Grant。

技术防滥用初值：JSON请求总UTF-8字节不超过1MiB，正文不超过256KiB，名称128字符，语言63字符；变量最多64个，每个STRING/default不超过64KiB，STRING_LIST最多256项；场景/意图各最多64个稳定ID，每ID128字符；页大小仅10/20/50、默认20，页码正整数并防乘法溢出。UUID对象ID与最大64字符原键使上述Grant坐标/数据库字段保持有界。超过上限明确返回413/字段错误，不截断、不残留部分修订；这些是Codex技术限制，可经真实输入试测调整，不是业务质量指标或原文规定值。

最小逻辑存储范围：受管工作区登记（组织、scopeVersion、有效期、政策引用）；稳定prompt容器/草稿头；不可变修订（PromptRevision内容/摘要、生成来源、提交主体/时间、expiresAt）；无正文操作事件；确切原键摘要回执。元数据与正文权限合为READ，存储拆分不创造新权限。没有发布表、生产绑定、模型输出、完整测试样例、使用统计或通用配置平台。修订expiresAt取提交+90日、来源保存期限及政策有效边界的最小值；事件取提交+180日及更短来源约束，读取不更新时钟。备份承认上限7日但不执行新物理保护/删除。

首个剩余技术缺口是持久工作区可信登记、真实HUMAN身份/五动作适配、DM8表/约束和完整锁序、不可变修订认证/原键先查/CAS同事务事件、独立有限清理依据/恢复防复活及准确安全chain/API/UI。工作区登记不能由普通请求声称organization/scope/期限而成立；初始受管登记的字段认证和适用维护入口须root收口。恢复必须逐项核当前权限/期限/清理依据并与独立authority闭包一致，缺失/未就绪/代次不符拒绝，不移用人工scope。上列技术D尚待root审定，不以数据表草案或业务确认宣称D全部完成；不需要重问已确认五动作或90/180/7日。

### 8.9 最小持久装配D待审：对象、可信登记与恢复依据

本节至8.13是Codex提出的可实施技术合同，供root在原任务内审定；本次编辑不自行改变D状态、不实施迁移。首个源码出口为内部草稿CREATE/READ/SAVE/COPY及有许可的TEST预览，经同一JWT/API/持久工作流进入工作台。没有发布、生产模板解析或模型调用。实际人员与Grant、具体来源保存许可、变量TEST政策在对应启用前核；部署和物理删除暂停继续有效。

**独立依据的必要性和限度**：主库自身的revision、回执和到期字段只能证明一份备份内部一致，不能证明它未早于后续保存、撤权或清除。只查主库SHA会让旧头/旧回执或已经清除的正文在恢复后再次被认可。因此沿既有两DataSource/独立水位模式，增加仅属于提示词工作区的标量控制投影；不建立备份目录、恢复平台、正文副本或永久墓碑。Grant撤权继续由现id2独立水位负责，提示词投影只证明登记、持久代次、确切修订期限及清除事实。独立库同样被恢复到旧时间或现时依据无法认证时，整个受影响工作区关闭，不能使用两份旧备份互相自证。

主库的最小表及约束如下，迁移编号由唯一owner分配。UUID均用VARCHAR(36)，摘要CHAR(64)，时间使用既有毫秒精确UTC映射，正文JSON只在相应期限和当前授权成立后读取。

| 表 | 确切最小字段与约束 |
|---|---|
| `conp_workspace` | workspace_id PK、organization、scope_version>=1、state ACTIVE/CLOSED、policy_ref/policy_sha、registry_sha、valid_until；不含用户提交的组织或自授权限。 |
| `conp_prompt` | prompt_id PK、workspace_id FK、kind GUIDE/SYSTEM不可变、head_revision_id、generation>=1、scalar_sha、container_until、control_until；确切头指向本prompt修订，禁止原位改变归属和类型。 |
| `conp_revision` | revision_id PK、prompt_id FK、revision_number>=1、revision_json可空、content_sha、record_sha、submitted_at、body_until、audit_until、body_state PRESENT/CLEARED；UNIQUE(prompt_id,revision_number)，body_until<=audit_until；PRESENT正文非空，CLEARED正文为NULL。JSON绑定原PromptRevision全部内容和服务端provenance，不接受客户端替换createdBy/政策/生成来源。 |
| `conp_event` | event_id PK、prompt_id、revision_id、generation、operation CREATE/SAVE/COPY、issuer/subject、submitted_at、request_sha、result_sha、audit_until；只标量无正文，UNIQUE(prompt_id,generation)。 |
| `conp_receipt` | receipt_id PK、workspace_id、issuer/subject、operation、key_sha、request_sha、prompt_id/revision_id、generation、content_sha、result_sha、submitted_at、audit_until；UNIQUE(workspace_id,issuer,subject,operation,key_sha)，不保存原请求或可逆正文副本。 |

独立库仅增加以下三种有限投影；它们不是人工scope，不复制PromptRevision/变量default/正文、模型结果或凭据。

| 表 | 确切最小字段与有限边界 |
|---|---|
| `idp_workspace` | workspace_id PK及主workspace的组织/scopeVersion/政策refSHA/registrySHA/ACTIVE或CLOSED/valid_until；独立control_state READY/PENDING、registry_intent_id、expected_registry_sha、intent_until核初始登记未知提交。登记和关闭由获准受管操作更新，普通请求不能初始化。 |
| `idp_prompt_control` | prompt_id PK、workspace_id、kind、已完成generation/head_revision_id/scalar_sha、state READY/PENDING、intent_id、操作/原键摘要、before_sha/expected_after_sha、intent_until、container_until/control_until。PENDING只表示已保留动作，不能表示主写成功；control_until不超过仍获准的最晚原事件/回执截止。 |
| `idp_revision_witness` | revision_id PK、prompt_id、原record/content摘要、原body_until/audit_until、body_state PRESENT/CLEARED、相应结果/原键摘要及必要真实主体坐标；内容认证摘要随正文覆盖范围到期关闭/清除，此后只留原180日范围内的必要动作/结果摘要；每项期限固定，最多到该操作原audit_until，过期不解析、不返回，清理后不保留永久行。 |

`scalar_sha`采用标准JSON固定字段顺序，认证prompt头、revision的ID/版号/recordSHA/原期限/body状态以及对应事件和回执的标量摘要；按明确ID排序且核原生行数、唯一键和闭包。不得从正文全量读取后才计算授权闭包。`record_sha`认证完整不可变修订与服务端provenance，`content_sha`沿现PROMPT_CONTENT_JSON_V1；两个摘要不能互代。清理正文时保留在原追溯期内必要的操作结果摘要而不保存可逆全文；到原追溯截止必须移除相应witness/事件/回执并推进当时仍有效的控制投影，不重新起算180日。

工作区初始登记是有限、确切目标的维护安装动作`PROMPT_WORKSPACE_REGISTER`：受管输入固定主/权威JDBC身份、workspace UUID、组织、scopeVersion、政策原件ref/SHA、有效期、当前批准原件ref/SHA及真实操作者。沿现mTLS维护者登记核真实证书/主体/组织/version；GRANTS职责只作为执行维护身份条件，另须该具体登记动作的当前批准，不能从GRANTS字符串自动获得登记或内容管理权。登记事务中锁当前维护者行直到主登记真实提交结算，独立登记先PENDING、主提交后READY；失败保持未就绪，原键对账不重建。初始化不得使用普通JWT请求、默认组织、默认政策或重跑空库identity bootstrap。新角色发行、普通用户工作区创建、跨组织转移不属于首包。

批准原件从服务端受管绑定经`ProductSourceEvidenceFiles.verified`取得，严格核格式、来源/版本/责任方/决定日期/覆盖工作区及项目原创人工草稿范围、固定90/180/7上限、当前有效期和撤回依据；文件一致不是自授权证明，必须同时匹配当前独立登记及受管预期SHA。缺保存覆盖只阻保存/复制，不把缺TEST许可扩大为所有草稿的前置。第三方正文/来源输入只有另有确切保存许可才可接入，不以客户端声明“项目自有”放行。首批受管政策和实际登记须功能启用前具证；工程实现负责准备格式与核验，不要求用户另写同内容决定文件。

### 8.10 最小事务、锁序与确切原键协议

应用入口拥有主事务边界，拒已有actual/synchronization或已绑定主/权威资源；两个事务管理器必须各自精确绑定对应DataSource，不允许在调用方事务中延迟主commit。JWT经现decoder核issuer/subject/sid/jti/kid/期限，再用`ProductHumanIdentityLocks.read`核实际HUMAN主体、当前client/key/session及凭据版本。工作区/提示词归属来自持久标量，不信任客户端facts；五动作映射沿8.4。READ包含列表和历史，SAVE不暗授READ，COPY核源READ与目标COPY同组织，PREVIEW核READ与PREVIEW。

主库锁序固定为参与工作区UUID字典序、对应组织UUID字典序、既有身份key-control/signing-key/client/subject/session顺序、精确所需Grant epoch坐标字典序、prompt ID字典序、revision ID字典序。创建在锁定工作区后分配新UUID并以generation=0进入；保存核持久头generation并生成新revision；复制不改变源prompt或其头。所有写、原键对账、初始化/清理同作用域必须遵守这一顺序。缺行/重复/版本漂移拒绝，不自动建epoch、Grant、workspace或控制记录。记录保存为DRAFT，不因语法正确形成PUBLISHED。

写操作依次采用以下有限协议，源码先实现CREATE/SAVE/COPY，不用伪清理事件占位：

1. 在主锁内核当前独立workspace READY、政策/来源许可及五动作权，按准确原键查回执；同摘要则走原回执核验，异摘要409。不存在时核旧头/原生闭包，服务端构造不可变新修订、确切事件与回执结果。提交时间是该次成功持久事务中一次取得并写入的服务端毫秒时间，失败尝试不另生成功事件；期限固定取该时间+90/180日、实际来源许可及受管政策边界最小值，不用响应时间或读取时间重新计算。
2. 独立短事务以原代次/READY/原SHA CAS为PENDING，持久intent、原键/请求摘要、before/expected-after标量闭包和原有限期限，然后真正commit。不得让这一步参与主事务或被外层权威事务吞掉。此时不持跨主commit的authority workspace/prompt写锁，避免普通读者的主锁→authority读反向等待。
3. 主事务在原锁与相同身份/epoch下写revision、CAS草稿头、追加event/receipt；正文与事件/回执一起提交。末次复核JWT/身份/Grant水位、政策原件、独立确切PENDING归属和全部期限，再让主TransactionTemplate实际commit结算。不能仅检查一次权限，不能把回调返回当主提交证据。
4. 主commit已具证后独立短事务核原intent/代次/SHA及主原生全后态，以CAS完成READY和revision witness。READY失败或commit响应丢失保PENDING/UNKNOWN，结果返回503/确切对账标识，不重复写入或误称全部回滚。公开读者见PENDING立即关闭，不等待或展示缓存正文。

PENDING所有权由独立CAS和主workspace行锁共同串行，重启对账前核旧执行者已退出并重新取得当前主体/动作权及相同批准，不能抢活进程intent。原键作用域是issuer/真实主体/workspace/operation/key SHA，key明文只请求内存；request SHA使用完整规范命令JSON，包含原CAS、指定源revision/目标workspace、内容/schema及声明，但不持久该JSON。原回执只能由同主体/同作用域/当前同动作权限查回，deadline恰到拒绝；查回不读取过期正文、不续期。CREATE/SAVE/COPY的返回只含准确标量回执，之后读取正文仍单独核READ。

### 8.11 失败、有限清理与恢复关闭

PENDING对账只接受三种可证明状态：准确全前态、准确全后态、未知。全前态只能在当前授权/期限仍成立时续同intent，不能声称先前事务已回滚；全后态只补原READY/witness，保留原事件/回执/操作者/期限，不再生成revision；混合行、缺回执、头或摘要不符、错误原生NULL、旧执行者未证退出均UNKNOWN并保持关闭。过期意图只可受控对账或清理，不能通过换键重建来绕过未知。COPY即使源已过期，全后态可仅据当时已提交标量补READY；全前态续写必须重新核源READ及源正文期限，禁止读过期源重新复制。

正文读取顺序是现JWT/HUMAN/准确动作权→独立workspace/控制READY及确切主标量闭包→选中revision原body_until→读取JSON并复算record/contentSHA→返回前重核相同身份/epoch/水位/原件/控制代次/期限。任何正文出口、关键词过滤、差异、复制、预览都用该边界。分页直接数据库限定工作区/类型/状态及原有效期限，稳定排序submitted_at DESC/prompt_id/revision_id；不先读全量SYSTEM正文再在应用内过滤。数据库故障503，不能冒不存在/空列表。

在body_until恰到关闭正文、复制、预览和恢复；物理字节存在不授访问。首个后续清理执行器只清选中revision_json/default并建立与独立CLEARED witness相符的原生NULL，保原追溯期限内的最小标量。其实际首次尝试应在dueAt+24小时内，首尝试在获准即将执行动作时独立持久、失败不重置；暂停期间明确报告未执行/逾期，不将扫描或无授权拒绝记成尝试。追溯到期清除原event/receipt/witness，容器随最后有效修订关闭；控制残留处理不得创造新180日或永久墓碑。清理writer/物理轮换未实现前，不能声称本完整保存合同或运行验收已通过。

恢复核验只针对本八表及现Grant水位：先认证当前独立库/工作区登记及政策有效性，再比较主标量闭包/READY代次/各revision原期限和清除状态。当前witness为CLEARED时，备份PRESENT或JSON非NULL拒绝；body期限已到时不读JSON、不重算全文SHA、不补默认期限；trace期限已到时也不打开原主体/回执JSON。缺当前witness、authority备份代次不明、schema不兼容、PENDING或混合状态全部关闭，不能从恢复的主库补造authority依据。7日备份上限不改变对象90/180日截止；具体备份工具、物理清理、隔离DM8恢复验证另循各自已登记任务，不在本首包新建平台。

### 8.12 API、安全链与内部工作台合同

沿8.8五动作端点新增独立`PromptRouteSecurityConfiguration`，准确匹配两组prompt路径并使用现JWT decoder；不落入permitAll维护或v0 chain。所有响应Cache-Control:no-store；请求体先核1MiB UTF-8及字段限额、严格JSON未知字段拒绝，正文/schema诊断不回显用户值。端点DTO只接受内容、声明、CAS及目标ID，不接受组织、授权坐标、批准路径/SHA、操作者或服务身份。

为工作台补同一READ坐标的`GET /v2/consultation/prompt-workspaces/{workspaceId}`，只返回该持久工作区当前归属/version/validUntil及当前主体五动作可用性；这不是第六权限，也不授其它动作。无READ不返回对象名/存在性信息；仅CREATE者可提交创建命令并收到标量回执，工作台保留该受限入口而不强迫先读正文。确切历史端点分页输出有效修订和明确不可用的所选旧版，不用新稿替代；显示名称/正文/摘要均须READ。指定无当前权和不存在采用同一403响应，不以403/404、关键词计数或诊断侧漏SYSTEM。

错误映射固定为401会话失效，403无当前动作权或对象不可用，400严格字段/模板/变量错误，409原键异摘要或旧CAS，413输入超限，503存储/原件/独立控制/未知提交不可核验。变量问题仅变量名和类别，不回值。原键未知响应允许提供当前主体可用的准确原键对账操作，不暴露批准路径或内部SQL。预览接收变量typed值，由当前政策映射TEST允许的source/evidence，不接受客户端自证许可；渲染是Literal/Data结构化结果，不递归、不入模、不写样例/segments日志或回执。

工作台消费新增严格`promptApi`，不能放宽现仅允许/tasks的共享request函数。复用现登录/PKCE的可信会话，入口使用现咨询登录回调，不另造开发身份；root统一挂载到现咨询产品工作台。界面提供工作区选择、可用动作、分页草稿列表、确切版/历史、编辑与CAS保存、源revision→目标工作区复制、变量schema编辑及TEST预览。没有发布按钮或假模板。仅CREATE时提供空白稿提交及回执状态，无READ不能回显服务器正文；SAVE成功后如缺READ，只显示回执而不伪装读回。

客户端以token/工作区/选中prompt/revision和请求epoch绑定异步结果；换区/换版/退出/401/403清除旧正文和预览，迟到响应不能覆盖新选择。提交后保留原key/规范命令直到准确回执，网络未知仅提供同key对账；确认写入但读回失败只重读，不再POST。刷新后按准确ID和当前会话GET读回，不从localStorage恢复SYSTEM正文/样例/权限；未确认请求若刷新丢失本地内存，页面只能查询现持久列表/历史或按已知原键对账，不谎称取消服务器动作。桌面/390px无溢出，键盘焦点和错误可达；重用现布局而不新增功能说明型开发页面。

### 8.13 可审定实施所有权与退出反例

隔离源码拟落在新增`ProductPromptWorkspaceRegistry/Authorization/Transaction/Store/Policy/Application/Controller/Configuration`和`PromptRouteSecurityConfiguration`，纯内核沿原prompt包。各类控制400行以内；拆分只按上述边界，不新建通用注册、权限或恢复框架。前端独占`features/consultation/product/prompt/`下工作台/API/contract/recovery及专属测试；现共享AppRoutes/navigation/咨询页挂载、迁移编号、现发行锚和全部生成/验证输出由root唯一owner处理。此代理不改旧guidance、Shared Task/Access、人工scope或部署。

复用范围已经实码核明：`PromptRevision.referenceFor`及构造认证、`TemplateRenderer`严格语法/结构化TEST渲染、`ProductHumanIdentityLocks`真实HUMAN核、`ProductConsultationAccess.permits`持久派生精确坐标、`ProductGrantWatermark`现时授权水位、`ProductSourceEvidenceFiles.verified`受管原件。现PromptPorts两种read权限在新适配统一READ；其端口签名不充持久实现。现JWT chain和前端request路径不包含prompt，必须新接上述准确边界。

首包源码/组件退出至少具以下有意义证据，统一由root运行并保留失败和跳过：

| 场景 | 必须观察 |
|---|---|
| JWT缺失/伪header/SERVICE、仅TASK权、跨区/跨组织、只有CREATE/READ/PREVIEW之一 | 准确动作拒绝，无SYSTEM正文/对象存在性泄露，不自动发Grant。 |
| CREATE原键成功、原键同摘要、异摘要；SAVE旧CAS/事务故障 | 只有一份确切不可变修订和event/receipt，旧稿不覆盖，故障无主库半记录。 |
| COPY源READ+目标COPY、只有普通CREATE、源到期 | 前者按确切源版建立新草稿；其余拒绝；源头、原权限/期限不改。 |
| body/trace恰到、旧版保存后读/预览/重启 | 原期限不续，先标量拒绝再JSON，过期正文或追溯无读出。 |
| authority PENDING先commit、主commit未知、READY失败、全前/全后/混合对账 | PENDING可独立观测，原失败保留，精确同intent续写/仅补READY/UNKNOWN关闭，不盲重放。 |
| 恢复旧头/缺witness/CLEARED备份PRESENT/旧authority | 禁止补初始化和复活；只证明本范围机械核验，不冒真实DM8恢复。 |
| TEST许可缺失/错原SHA/末尾撤回/变量错type或嵌套数据 | 只阻预览，草稿不冒发布；样例和segments不入持久/日志/模型。 |
| 真实安全chain的HTTP及工作台换区/迟到/刷新/网络未知 | JWT确实解码；创建→回执→READ确切版→SAVE新版本可操作；重试不换键，不重POST已确认写，390px无内容遮挡。 |

该首包退出只允许分别标SOURCE-IMPLEMENTED与COMPONENT-VERIFIED。当前仍无提示词装配代码、DDL、API或UI；root审定本D并登记实际实现任务后才编码。运行出口另须受管工作区/政策原件、实际HUMAN/五Grant、7日备份与有限清理执行准入、当前DM8与真实流程证据；具体变量TEST许可只门禁该预览。正式发布/生产绑定/模型调用及完整CON10业务验收仍未交付，不再提问已确认业务事实。

### 8.14 首个持久化窄D审定（2026-10-13，Codex）

PC-CON-PROMPT-PERSISTENCE-01仅实现§8.9的五主表与三独立标量表、内部真实JDBC存储和不可变修订/原键/CAS及期限前正文读取。迁移为DM8 V112及identity-authority V007，源码SQL不自动安装。存储类不注册Bean/HTTP，不声称已认证身份或批准；调用者必须建立精确本DataSource事务，跨库PENDING/READY由后续应用事务拥有，不从本地成功推断整体成功。按§8.9～8.11字段/期限/闭包完成原子主库保存、冲突拒绝、确切旧版读取、真实SQL分页和独立控制CAS组件；拒无事务、错误连接及错代次。新增包私有ProductPrompt*类和专测，不改现身份/人工/共享路由。真实权限、可信登记、HTTP/UI与恢复装配仍保留原D任务，由root后续审定，不以本存储出口替代。H2 SQL组件验证必须覆盖主事务中途失败整批回滚、两连接CAS、到期先标量拒绝而不读JSON、新修订不续旧期限以及PENDING保持关闭；DM8未执行如实保留。已确认五权/90-180-7不再提问。

§8.14实现细化：PreparedWrite在任何主业务写之前一次生成服务端回执/事件ID、固定时间、修订/结果及全后态摘要，独立PENDING先提交后主库使用相同值；不先提交主库再猜expectedAfter。实际holder同步标志及READ_COMMITTED必核。原键和全部历史闭包均先核纯标量期限，过期追溯不读取主体/回执明细；到期行不是不存在，不据此重建。该窄存储在旧追溯到期或缺失时关闭，尚未实现清理后部分历史闭包更新/连续恢复，不冒完整生命周期或API已交付。

### 8.15 当前批准与五权生产认证适配窄D审定（2026-10-13，Codex）

本节继续ARC-CON-PROMPT-OPERATION-D-01；root已登记PC-CON-PROMPT-AUTHORIZATION-01并于2026-10-13审定本窄生产认证D。当前差额是存储尚不能认证实际调用者、批准依据与工作区归属；这是下一API安全入口的必要前置。复用§8.14真实双存储、现JWT decoder、ProductHumanIdentityLocks、ProductConsultationAccess、ProductGrantWatermark和ProductSourceEvidenceFiles，不新建可被调用者替换为allow的Authority端口。结束观察为真实JDBC身份/Grant/独立登记及受管原件共同核验，越权、漂移和到期先于正文拒绝；不以授权组件冒HTTP、工作台或运行交付。

仅新增包私有ProductPromptWorkspaceAuthorization及必要严格原件parser/不可变见证和专测。入口取得原bearer及UUID目标和固定五动作，从实际数据库和服务端绑定导出facts；不接受调用者提供Workspace、组织、主体、ResourceFacts、Policy、原件路径/SHA或授权结论。受管绑定在构造时完整校验、不可变复制，无默认工作区/期限/模板；存储、access、水位必须绑定同一真实主DataSource，独立存储必须绑定水位使用的同一authority DataSource并满足既有不同JDBC身份约束。此窄步不注册Bean/HTTP，不实施业务写、REGISTER/CLOSE writer、TEST渲染、迁移、恢复协调、清理或部署。

#### 8.15.1 严格批准原件与保存政策

原件均由服务端命名Binding固定ref及预期原字节SHA，经verified读取后用标准JSON严格解析：拒重复键、未知字段、未知枚举、缺字段、错误类型、非规范UUID/SHA和非毫秒UTC时刻。不得先反序列化宽松Policy DTO再把它当批准；SHA核给定原字节，语义另核所有下列字段。Binding只标识已获准的具体对象，不由普通请求或模型生成；Codex负责准备格式及按实际决定生成原件，不要求产品方重写同内容决定文件。

| 原件 | 必须字段及固定检查 |
|---|---|
| PROMPT_WORKSPACE_APPROVAL/v1 | format、decisionId、source、decisionVersion、responsibleParty、decisionDate（ISO日期）、status ACTIVE、validFrom/validUntil、workspaceId、organization、scopeVersion、policyRef/policySha、contentCoverage PROJECT_AUTHORED_HUMAN_DRAFT、operations（CREATE/READ/SAVE/COPY/PREVIEW中明确批准的非空不重复集合）。UUID/组织/scopeVersion与受管Binding和两库登记相同；工作区registrySha必须等于本原件原字节SHA；当前操作必须在批准集合内。来源/版本/责任方/日期/结论完整，不把技术格式本身当真实批准。 |
| PROMPT_DRAFT_RETENTION_POLICY/v1 | format、policyRef、source、decisionVersion、responsibleParty、decisionDate、status ACTIVE、validFrom/validUntil、workspaceId、organization、scopeVersion、contentCoverage PROJECT_AUTHORED_HUMAN_DRAFT、bodyMaxDays=90、auditMaxDays=180、backupMaxDays=7。ref和原字节SHA与批准、Binding及两库政策一致；只覆盖已确认项目自有人工草稿及最小非正文追溯/回执，不扩第三方输入或发布版本。 |

每个Binding包含workspaceId、organization、scopeVersion、approvalRef/approvalSha和policyRef/policySha；ref只由服务端提供。approval/policy有效期均须覆盖当前请求，validFrom不得在未来，validUntil恰到拒绝；持久workspace.validUntil不得超过两份批准有效截止。格式中的decisionDate不虚造本次人类答复的精确时刻。实际原件必须来自有依据的具体工作区决定；既有PROMPT-DRAFT-WORKSPACE-DECISION/v1和五权/90-180-7决定不自动授权任意UUID、组织、人员、来源或维护者，不能编造ACTIVE文件填补启用事实。

本适配只认证批准覆盖范围及上限，不接收正文或来源材料；不能证明某客户端内容确实项目原创。下一保存/复制应用层仍须认证具体输入保存许可及更短来源期限，不能以客户端声明PROJECT_AUTHORED放行。保存期限按原成功submittedAt+90/180日与实际workspace/policy/source边界取小；请求JWT、会话和Grant截止控制现时调用资格，不被误作新的保存政策或读取续期依据。缺TEST政策不影响本步骤对其他四动作的独立认证。

#### 8.15.2 当前撤回权威与两库一致性

文件一致性不是当前授权。每次入口必须读实际idp_workspace，核state ACTIVE、control_state READY、registry_intent_id IS NULL、expected_registry_sha=registry_sha及尚有效，并把组织/scopeVersion/政策refSHA/registrySHA/validUntil与主conp_workspace逐项相等；再核受管当前Binding、两份当前原件及其时窗。独立缺行、PENDING/CLOSED、旧代次/旧SHA、主旧登记、文件缺失/变化或当前绑定不匹配均关闭，不从主库补造独立依据，不接受两份恢复旧库互相自证。

当前撤回的产品权威是独立登记的关闭/未就绪或当前受管批准绑定撤回；适配只读取并拒绝，不自行执行CLOSE，也不以一次原件认证把批准冻结为永久。返回或主提交前复读相同Binding、原件原SHA/严格语义和独立/主登记；变化或恰到拒绝。该步骤没有更新独立登记的入口；真实登记/关闭须后继维护合同及实际批准，不能由测试建行、空库启动或普通JWT请求代替。

未来REGISTER/CLOSE仍依§8.9，但须另审真实操作原件、登记操作者/原键回执及锁：现idp_workspace无登记操作者/原键回执，registry_intent_id在READY清空，不能冒持久操作对账。REGISTER仅新UUID一次安装，CLOSED不得重新ACTIVE；CLOSE应在主workspace锁内先独立CAS为CLOSED/PENDING并提交，使访问立即关闭，再主关闭提交及独立READY，后段失败保持关闭。它们控制当前产品授权，GRANTS角色只满足维护身份条件，另须当前具体workspace/action/operator批准；本节不授实际登记或关闭权。

维护锁后继最小候选为prompt专用独立JDBC lease，在真实同一authority DataSource的专用连接上以READ_COMMITTED、非autocommit读取id2_maintainer singleton=1 FOR UPDATE；核当前真实TLS证书、主体/组织/GRANTS/version及具体操作批准。lease不绑定Spring资源，真实主事务及authority短事务各用另一个连接，避免外层事务吞掉PENDING或把回调返回当主commit。必须持锁跨主真实commit结算、末次重核证书和批准，finally使lease失效并rollback/close释放锁；不可跨线程/保存重用。先有本实例有界准入，再借lease连接和取维护行锁；锁后禁止等待同维护者其他操作，普通五权路径不获取维护行锁，独立短事务不回锁维护行。authority至少能供lease加短事务两连接、主库至少一连接；共享authority现池上限10不等于容量保证，借用/锁等待/网络及整体lease期限必须有限，超时返回不可核验并保留已提交PENDING/CLOSED，不能无限等待或自动重放。具体timeout/cancellation需对应真实驱动验证；容量或超时机制未核不得启用登记。该lease、回执增量及真实验证均不在本授权步骤实现。

#### 8.15.3 五精确动作、事务和末次见证

独立认证入口拥有main READ_COMMITTED TransactionTemplate，拒已有actual/synchronization或已绑定主/authority资源，拒错误事务管理器/连接、手工非同步holder及事务嵌套吞提交。原JWT由实际decoder认证后再核issuer/subject/sid/jti/kid、exp和nbf，以ProductHumanIdentityLocks.read锁实际HUMAN；不从Grant查询构造HUMAN结论。所有所需工作区按UUID排序锁定，组织ACTIVE按UUID排序锁定，再现身份key-control/signing-key/client/subject/session顺序，再准确epoch坐标排序，最后prompt/revision锁。COPY先锁源/目标两个工作区，组织必须相同；prompt归属可预读纯标量，锁定后必须复核，未认证不得读取revision_json。

复用现ProductPromptJdbc.requireTransaction：在本适配创建的main事务回调内核实际同步holder、READ_COMMITTED和已固定JDBC身份，随后store.workspace/head等沿同holder读取及锁定，不另起主事务。原件verified及独立登记查询只有读取、不绑定第二authority事务；身份/epoch锁也沿同main连接，因此与存储约束兼容。授权lease绑定该实际main holder、线程和活动期，其权限/工作区值只能在lease活动期间通过核验方法取得；退出finally失效，所有后续lease方法拒绝，不能把返回的普通标量当下次调用权限。此处没有跨库业务写编排或main提交后READY协调，不声称完整应用事务已实现。

facts完全由持久workspace导出：organization为持久组织，taskScope与dataScope均PROMPT_WORKSPACE:workspaceId:scopeVersion，resourceType=PROMPT_WORKSPACE，resourceId=workspaceId:operation，resourceVersion=scopeVersion，domain CONSULTATION、purpose TASK_WORK。epoch锁精确匹配issuer/真实subject/organization/domain/type/id/action/purpose且须唯一现存；再调用现access.permits认证实际Grant，不建缺epoch，不发行Grant，不挪用TASK权限。当前Grant的scope/version/截止及授权epoch也是末次见证，不只拿epoch锁当允许。

| 操作 | 必需准确坐标 |
|---|---|
| CREATE | 目标CREATE后缀、既有CREATE action |
| READ（含元数据/列表/历史） | 本工作区READ后缀、既有READ action |
| SAVE | 本工作区SAVE后缀、既有WRITE action；不暗授READ |
| COPY | 源READ/READ及同组织目标COPY/CREATE；普通CREATE不能替COPY |
| PREVIEW | 本工作区READ/READ及PREVIEW/READ；这里只认证两权，未具独立当前TEST许可不得返回渲染可用结论 |

开始记录独立Grant水位代次、完整identity见证、各准确epoch/Grant截止、两库workspace及原件见证；结束再次decode原bearer，复核同identity、准确Grant/epoch、水位、全部工作区/原件及各截止。JWT/会话/VERIFY_ONLY key/Grant/workspace/批准/政策期限恰到均拒绝；不记录token/正文/原件路径到返回或日志。入口返回的是限定动作认证见证，不是发布/专业/来源/TEST许可；后续应用仍须在它拥有的同一边界里读取/写入并末次复核，不能持久缓存见证跨请求使用。若需公开execute callback，见证必须绑定当前事务holder/线程和活动期，退出finally失效；callback不能提供新的facts或认证实现，也不能被本步骤称为已接业务API。

#### 8.15.4 拒绝、恢复边界与组件出口

缺HUMAN/准确Grant、TASK-only、错工作区或跨组织、CLOSED或到期返回限定拒绝；存储/原件/独立控制不可核验返回限定不可用，不冒不存在/空列表。无权和对象缺失不返回名称、正文、政策文件路径或差异。恢复的旧主登记、独立缺失/PENDING/旧绑定均关闭；本步骤不修复intent、不重建登记/Grant/期限，不接通用恢复平台，原键未知也不盲重放。部署/物理删除暂停及运行准入均保留。

组件反例使用真实独立H2库、实际id2身份/session/key/client/current_epoch/Grant及两库workspace、受控临时原件和真实SHA；行与证书/原件是C输入，不冒实际启用。至少覆盖：正确单动作及同组织COPY；仅CREATE不能READ/SAVE，仅READ不能PREVIEW，普通CREATE不能COPY，TASK-only/SERVICE/错issuer会话/跨组织/错scopeVersion拒绝；原件重复键/未知字段/自报路径/错SHA/错覆盖/90-180-7扩大/恰到拒绝；主登记和独立登记漂移/PENDING/CLOSED/旧主恢复拒绝；末次原件撤回/会话或Grant截止/水位变化拒绝；无当前事务或错误连接拒绝；全部拒绝发生在任何正文读取前。TEST实际许可、REGISTER/CLOSE writer、HTTP/security chain/UI和真实DM8保持NOT_IMPLEMENTED或NOT_RUN，不标本节D完成即产品可用。root唯一运行前后verify-all与专项，不新增第二验证owner。

### 8.16 确切版本与历史读取API/用户页窄D（2026-10-14，root技术审定）

任务为`PC-CON-PROMPT-READ-API-01`，需求CON-10，依赖已过§8.14持久化和§8.15当前认证。差额是实际JWT请求尚不能进入已有读取、用户也不能通过真实选中资源查看版本；现在复用两个组件接通该入口，结束观察为真实安全chain/签名decoder/两库SQL的确切GET及用户页面刷新、版本选择、失权清除反例。root于2026-10-14审定本节窄D并在规定前检自然终态后准入源码；完整父D、写入、预览、清理及运行验收仍单独退出。

API沿§8.8已有路径：`GET /v2/consultation/prompts/{promptId}/revisions/{revisionId}`返回确切修订；`GET /v2/consultation/prompts/{promptId}/revisions?page=1&size=20`返回历史。UUID须规范，page为正int，size仅10/20/50；不接受正文、权限facts或原件路径。确切DTO包含promptId/revisionId/version、kind/name/body、scenarioIds/intentIds/audienceRef/language/displayOrder、变量定义、submittedAt/bodyUntil。INTEGER/DECIMAL默认值以精确文本运输，避免浏览器Number损失精度；不是新的变量类型或回写协议。历史DTO仅promptId/page/size/total与revisionId/version/submittedAt/bodyUntil。存储history确有SQL COUNT及稳定ORDER BY/OFFSET/FETCH，不新增伪分页或workspace列表。

应用先用独立短只读主事务定位prompt→workspace纯标量，结束后进入§8.15拥有的认证事务，重核归属、锁定头并调用原store.readRevision/history；不在外层事务中嵌套认证，不在认证前读取JSON、名称或主体追溯。所选修订缺失/正文到期或清除以纯标量先拒绝；当前正文认证继续由store的闭包/独立READY+witness/内容摘要核验完成，返回前仍复核lease与受管绑定。不存在与缺当前READ统一403；会话不可用401，输入400，SQL/受管原件/独立不一致503。新增typed PromptAccessDenied仅识别业务拒绝；现HumanIdentityLocks的`Current human identity unavailable`（缺当前HUMAN等）在提示词适配内转403；确切`Current human session required`（撤销/到期等会话拒绝）转专属session异常并返回401，触发重新登录。共享single-row缺失仍使用原通用身份消息，本批不扩大共享类重分类；真实decoder已能拒无当前session。`Bound identity transaction required`及其他工程错误保持503，不得错映403或401。服务端不回显内部异常、token、批准路径或SQL。所有成功/失败响应no-store。

装配默认关闭，配置`product.consultation.prompt.enabled=true`时要求`product.identity.enabled=true`；使用现main DataSource/manager和watermark.independentDatabase精确独立库。现Access类可直接按同main/watermark/issuer构造本适配实例，不注册重复共享Access Bean、不为读取批准原件强启source.engine。受管配置为同前缀`.evidence-root`（绝对目录）、`.bindings-ref`、`.bindings-sha256`。绑定原件严格格式`{"format":"PROMPT_WORKSPACE_BINDINGS/v1","bindings":[{"workspaceId":"…","organization":"…","scopeVersion":1,"approvalRef":"…","approvalSha":"…","policyRef":"…","policySha":"…"}]}`；不超过1MiB、1～128项、UUID/sha规范、正整数代次、重复键/未知字段/重复workspace拒绝。构造时复制不可变Binding；每次应用进入与返回前verified同ref/SHA，变化即关闭，原批准/政策仍按§8.15每次核验。启动只探当前八表所需列，不建workspace、不写Grant或修复依据。

独立PromptRouteSecurityConfiguration在现Order5之前准确匹配`/v2/consultation/prompts/**`及`/v2/consultation/prompt-workspaces/**`，使用现JWT decoder。链内专属transport filter要求HTTPS、拒编码百分号/反斜杠路径，并核当前Grant水位；因为现共享transport filter仅覆盖Task等路径，不能假定它已覆盖prompt。不改共享Task/Access或permitAll链。

页面为`PromptRevisionBrowser`，props为可信会话token、真实选中promptId、可选revisionId、onSessionExpired、onBack。资源由真实保存结果、工作区选中或深链传入，普通用户流程没有UUID/SHA输入框，不造列表或示例模板。显示名称、类型、版本、提交时间、确切正文和变量定义，历史翻页、选择旧版、重新读取与返回咨询任务；无资源则提示未选，无token由共享登录入口接续。首次无指定版本可从已获准历史第一页选择确切最新返回项再GET，不把缓存正文当新请求结果。token/资源变化立即卸载旧会话页面，异步结果绑定当前请求epoch；刷新/网络失败仅GET，401/403/503清除正文和旧历史；bodyUntil到点关闭已显示正文，用户再读仍由服务器判定。无localStorage正文、样例或权限。

共享挂接由root独占：现PKCE state/verifier同记录可追加规范UUID prompt/revision深链，回调state匹配后从可信本地发起记录恢复，拒由回调任意query自造该记录；不新增token持久化。prompt直接读取不以Task retrieval-workspace成功为前置；退出、会话失效及返回任务失效化旧请求。专属页面沿现咨询语义色/字体、可见焦点、44px操作和390px折行，不建立新设计系统。

组件验收使用真实签名JWT、生产条件装配/安全链、两独立H2及受管原件，验证无JWT/伪签名/TLS、仅CREATE/TASK权/SERVICE、原件pin变化/authority PENDING/内容摘要损坏、恰到正文前拒绝、page/UUID、缺失和无权同403。React验证刷新只GET、选中版本/历史分页、token/资源切换丢弃迟到、失权/故障清除、响应坐标错配和默认数值精度。root统一运行；结果未产生前不标COMPONENT-VERIFIED。实际workspace批准/REGISTER、人员Grant、7日备份和有限清理准入只门禁运行，不阻本源码。无真实DM8、部署、真实流或业务验收声明。

### 8.17 CREATE原键与prepared有限持久恢复增量（2026-10-14，待root审定）

本节仅是`ARC-CON-PROMPT-OPERATION-D-01`内父§8.10写应用的待审工程D，不自动准入迁移/代码，也不引入通用恢复平台。当前交付缺口是CREATE独立reserve提交、主库全前态且进程重启后找不到原prompt/intent，重新prepare又改变随机receipt/event ID、原时间及expectedAfter；这是下一CREATE入口的必要解阻项。复用现Store.prepare/append、workspace行锁、主五表及独立PENDING/READY，完成观察是root可据本节审定原键查回、同prepared重建和三态对账的确切实现；实际创建运行、DM8和启用仍未交付。

#### 8.17.1 独立有限请求记录及约束

独立库新增单表`idp_prompt_request`，与原`idp_prompt_control`的reserve在同一独立事务原子提交。仅给control追加当前key不足：后续SAVE会覆盖当前intent，历史CREATE仍须在原回执期内查回。主库复用现五表，不新增恢复表；独立SQL编号由root分配，本提案不预占编号、不改既有V007。

| 字段组 | 具体字段、约束和用途 |
|---|---|
| 原键 | `request_id VARCHAR(36) PRIMARY KEY`（等于原intent_id）；`workspace_id VARCHAR(36)` FK独立workspace、`issuer VARCHAR(300)`、`subject_id VARCHAR(200)`、`operation VARCHAR(16)`、`key_sha CHAR(64)`；UNIQUE(workspace_id,issuer,subject_id,operation,key_sha)。第一实施只准CREATE，operation保留既有枚举，SAVE/COPY须自己的入口D后接，不因字段可容纳即获准。 |
| 对象 | `prompt_id VARCHAR(36)`、`kind VARCHAR(16)`、`target_revision_id VARCHAR(36)`、`expected_generation BIGINT`、`expected_revision_id VARCHAR(36)`可空、`target_generation BIGINT`、`receipt_id VARCHAR(36)`；UNIQUE(receipt_id)、UNIQUE(target_revision_id)，target_generation=expected_generation+1。CREATE固定expected_generation=0、expected_revision_id原生NULL、target_generation=1；现event_id与receipt_id相同，不再保存第二个随机ID。 |
| 原准备与摘要 | `prepared_format VARCHAR(40)`固定`PROMPT_PREPARED_SCALARS/v1`、`request_format VARCHAR(40)`固定`PROMPT_CREATE_COMMAND/v1`、`request_sha`、`content_sha`、`record_sha`、`result_sha`、`before_sha`、`expected_after_sha`、`prepared_sha`均CHAR(64)；所有SHA使用小写64hex，不接受客户端直接提供prepared值。 |
| 固定时间 | `prepared_at`、`body_until`、`audit_until`、`intent_until`、`container_until`、`control_until`均TIMESTAMP，毫秒UTC。CREATE的container_until=body_until、control_until=audit_until；body_until>prepared_at、audit_until>=body_until、body_until<=prepared_at+90日、audit_until<=prepared_at+180日，intent_until<=body_until且<=audit_until。实际更短来源/批准/政策/工作区边界同时取最小；不设置新恢复保存期。 |
| 认证坐标 | `scope_version BIGINT`、`registry_sha CHAR(64)`、`approval_ref VARCHAR(500)`/`approval_sha CHAR(64)`、`policy_ref VARCHAR(500)`/`policy_sha CHAR(64)`、`input_permission_ref VARCHAR(500)`/`input_permission_sha CHAR(64)`、`generation_source_ref VARCHAR(500)`；均为服务端认证后的原坐标，不保存依据正文、客户端路径或凭据。provenance的createdBy/modifiedBy均取subject_id，createdAt/modifiedAt均取prepared_at；CREATE的testRecordRefs为空，changeDescription由本版本服务端固定为`CREATE_DRAFT`，contentApprovalRef取认证后的input_permission_ref，retentionPolicyRef取policy_ref。 |
| 结算 | `state VARCHAR(16)`仅PENDING/READY，NOT NULL；READY只能随原control/witness完整结算在同独立事务推进。原字段不可变；不以一次网络异常永久写一个“已失败”状态，也不存每次轮询事件或续期时间。 |

所有非注明可空字段NOT NULL。原准备时间只为本次不可变修订的提交时间及保守有限期限锚点；PENDING不是成功事件，真实主commit证据另由完整主行闭包证明。修订只有主事务确实提交才存在；响应晚到或全前态重试不重置这个锚点。由此明确§8.10的“提交事务中一次取时”在重启恢复时仍是原事务准备值，绝不使用恢复时间另起90/180日。

该记录属于原最小非正文回执/追溯，最多保存到原audit_until（且服从更短来源期限），PENDING/READY一样固定起算。先仅取audit_until判定，恰到后不解析/返回主体、准备值和依据坐标；不得通过数据库过滤把到期行冒不存在而新建。内容摘要和输入来源坐标不成为过期正文访问能力；到期逻辑关闭、24小时首清理及7日新增备份仍沿§8.7/8.11，物理清理暂停不解除。清除后不保留永久原键墓碑，也不承诺超过该有限窗口的跨清除幂等；旧键不得由客户端自动换键或重放为新操作。

#### 8.17.2 canonical prepared与同键命令

`PROMPT_CREATE_COMMAND/v1`为固定字段对象，包含workspaceId、expectedGeneration=0、kind/name/body、按自然序排列并去重的scenarioIds/intentIds、audienceRef/language/displayOrder及按输入顺序的variables；变量定义含name/type/required/sourceRef/allowDefault/defaultValue/allowEmpty，数值以现领域精确数值合同解析，不经JS浮点转换。输入权限选择只有服务端可解析的来源标识，不接受许可结论、组织/主体/ID/时间/截止/政策SHA。若请求包含来源选择，该标识也进入request SHA。按现ProductComparisonJson递归排序对象键、UTF-8及精确数值规则生成摘要，数组顺序不任意改变；上限仍§8.8，重传必须完整命令、同规范摘要。规范化不偷偷trim正文或变量default。

prepared canonical是带上述format的固定字段对象：8.17.1所有不可变列（排除state和prepared_sha），加原完整before Head（CREATE为JSON null）及目标Head/Header/Receipt的全部现记录字段；对象键递归排序、枚举用名称、Instant用既有毫秒UTC ISO表达、缺失不得替成空串，算SHA-256得到prepared_sha。record/result和`PROMPT_SCALAR_CLOSURE_V1`闭包沿现算法，prepared的新格式不能改变历史三个摘要算法。prepared原件不另存JSON，只存可重算标量；若算法版本无法识别即503关闭，不尝试用新算法猜旧值。

Store.prepare增加仅包内应用可传的准确receipt ID参数；其默认随机入口可继续供已获准调用使用。恢复必须走同一prepare语法、期限、原头闭包、domain reference/content摘要及record/result/after计算，使用原prompt/revision/receipt/时间/provenance和当前重新认证的完整命令，再逐字段比较持久prepared与prepared_sha。禁止直接new PreparedWrite绕过这些校验；API DTO无服务端准备字段。不同摘要409且不新分配ID；同摘要但任一准备值/依据漂移或缺失503并保持原PENDING。

#### 8.17.3 原键入口、锁及有界等待

创建仍用§8.8原POST，`Idempotency-Key`为1～64个可打印ASCII字节且不含空白，服务端只存其SHA。另提供`GET /v2/consultation/prompt-workspaces/{workspaceId}/requests/CREATE`，原键仅同名header传入，不放URL/query或日志；无请求正文、不靠浏览器保存prompt UUID才能定位。当前有效HUMAN/同issuer-subject/准确CREATE、组织工作区及批准必须先认证，CREATE查回不暗授READ。所有返回no-store：缺权/不存在同403；格式400；异命令409；未能证明提交、超时或依据不一致503；只有原READY且标量见证全核的成功返回200准确原回执（首次POST完成可201），不返回正文和受管路径。

GET在原audit期限内只查有限标量：READY可回原结果；PENDING经受控对账证明全后可补READY；全前返回503结果未完成，要求用户保留原键和原完整命令再POST，GET不替用户写正文。POST首先按完整原键读独立request，已有记录沿原ID进入；独立缺失但主receipt存在是不一致503，不能补造独立权威；两边均无才可准备新CREATE。

沿§8.10锁序，应用拥有主事务，首先锁原conp_workspace行并持有到主TransactionTemplate的实际commit/rollback结算。正常写、重试、查回对账和以后同作用域清理均遵守；独立reserve/complete分别为精确权威DataSource的短事务，不能参与或延迟主commit。恢复重新取得同workspace写锁并读已提交主状态即可证明旧主写事务已结算，无需活进程表、进程租约或强杀连接；旧连接仍持锁则等待有界超时503，不能抢intent。主事务超时30秒，每个SQL查询/锁等待上限5秒，权威短事务同样30秒总上限/单查询5秒；确切驱动取消和DM8超时行为必须实测，超时不等于已经回滚或可绕锁。

正常主提交后未及READY的结算者与恢复结算者可竞争；二者都只能拿同原请求在独立事务中串行比较，不能倒持authority锁再等待main锁。返回前复核现JWT/session/Grant/独立水位、原件及截止；主commit后失权或完成响应丢失保未知，不假报回滚。全后结算入口仅接收同调用内完整标量核验产生的内部证据，不开放由DTO或普通caller自报“已commit”。

#### 8.17.4 五表对账与READY幂等

五表全前不是“五表都空”：受管`conp_workspace`必须存在、ACTIVE且与当前独立登记/原scope/registry/政策完全相符。对于原CREATE，`conp_prompt`无原prompt；`conp_revision`对原prompt或targetRevision无行；`conp_event`对原prompt、targetRevision或receipt/event ID无行；`conp_receipt`对原prompt、targetRevision、receipt ID或完整原键无行。逐表核查询，不能仅head==null；任一孤立行、坐标冲突、重复或无法核验均UNKNOWN。独立request/control须同原PENDING、generation0和before摘要，不能从主全空重建权威。

准确全后要求同workspace，现主head=原target代次/revision/container/control/expected-after闭包；revision头的原record/content摘要、submittedAt/bodyUntil/auditUntil/body_state与prepared一致；原event和receipt的ID、issuer/subject、operation、key/request/result摘要、版号及原期限逐项吻合，行数/唯一关系及完整标量闭包通过。只SELECT头/事件/回执和JSON是否为NULL的状态谓词，不读取/反序列化正文或default；PRESENT且JSON原生NULL是混合态。缺回执、缺event、错NULL、仅一部分行、头已前进但原request仍PENDING、未知主commit或被恢复的旧库均不猜测为全后。

全前只允许同原intent且now严格早于intent_until、body_until、audit_until和当前权限/输入许可/政策各期限，重传命令复建原prepared、复核末次认证后续写。任一期限恰到不能续写，不改intentUntil，不换键，不生成新成功事件。全后在now早于原audit_until、control_until及当前身份/同动作/工作区批准的前提下，可在原intent_until或body_until已过后仅补原标量READY/witness；这正是§8.11“过期意图只可受控对账”的例外，绝不恢复正文访问。若源许可已到期，仍须存在覆盖原有限追溯和对账的现时依据；没有则关闭，不把“无需重读正文”解释为无权处置来源。

独立完成事务先锁request/control、核原prepared证据：PENDING时只有不存在witness才INSERT准确原witness，再CAS原control为READY并将request改READY，三个动作同事务提交；若witness已存在，必须所有字段与原结果逐项相等才可视为同次重放，任何不一致关闭。已有READY重复调用只校验原request、原revision witness与主原receipt，返回原结果，不重复INSERT、不新建事件、不改变期限。后续已准SAVE可推进当前control；旧CREATE request保持READY，查回核当前head/control的READY闭包和原revision/witness/receipt，不能要求当前control还停在CREATE代次，也不能用旧CREATE回写当前control。

当前complete把intent过期一概拒绝、重复INSERT witness的实现需要按上述区分改造；不得简单删current(intentUntil)而允许全前过期续写。全后时正文已到期可保原PRESENT标量至独立清理正规推进，但所有正文门仍由bodyUntil即时关闭；若实际已被清理须按真实CLEARED闭包核验，不能把清理结果复写成PRESENT。第一CREATE切片尚无清理writer时遇此非原闭包保持UNKNOWN，交已登记有限清理/恢复分支，不扩本解阻项。

#### 8.17.5 输入保存许可认证与退出反例

工作区批准中的`PROJECT_AUTHORED_HUMAN_DRAFT`只给覆盖边界，不能单凭客户端勾选或字符串证明某正文来源合法。CREATE应用需独立的输入保存许可认证结果，覆盖完整模板正文/元数据及每个非空default的来源；当前CREATE Grant不能替代它。生产适配复用受管原件核验器和部署pin：服务端来源目录根据真实选中来源标识取得许可原件ref/SHA，不接客户端路径、SHA或一段自报permit JSON，不内置固定模板或合成候选替代生产输入。

原件合同`PROMPT_INPUT_SAVE_PERMISSION/v1`严格字段为format、permissionId、source、decisionVersion、responsibleParty、decisionDate、status、validFrom/validUntil、workspaceId、organization、scopeVersion、issuer、subject、operation=CREATE、contentCoverage、contentSha256、generationSourceRef、bodyUntil、auditUntil、policyRef/policySha。来源/版本/责任方/日期须指向真实有权的来源决定，ACTIVE且当前有效；作用域精确匹配现主体/工作区/政策，contentSha256与服务端重算的整份Prompt内容一致，generationSourceRef具确切原始来源链，body/audit不超过获准边界。第三方或混合来源必须其真实许可确实覆盖各部分及用途，未覆盖关闭本次写；项目原创输入亦须可追溯来源事实，不能Codex或模型代认所有权。该合同是保存许可的技术验真表示，不创造新的法律许可或让原件持有人自授权。

验真器输出只在当前事务/调用有效的不可伪造内部见证，绑定同workspace/actor/contentSha、来源和政策refSHA、原更短body/audit截止。首次prepare及提交前首末复核原字节pin、撤回/有效期和相同内容；全前恢复按同原permission重核，许可替换不能静默生成新期限；全后仅查原提交证据及仍适用的有限追溯依据，不重新读取过期输入全文。服务端工程负责目录/原件格式及生产解析，真实资料/责任依据由对应来源责任方在启用前核实；不存在该输入依据时仅拒该写入，不要求重新确认五权/90-180-7，不阻现READ。许可原件若本身含来源正文不可复制到新增请求表；本表仅存ref/SHA且随原audit到期关闭/清理。

root审定后下一写源码出口必须共同覆盖：reserve真实独立提交→主事务失败/未知→对象重建→同键同intent→全前续原prepared或全后仅补READY→重复无第二修订；主五表每种孤立残留、同键异摘要/换主体、当前执行持锁与5秒超时、intent/body/audit各恰到、intent过期全前拒绝而全后在原审计期内结算、双完成竞争、已READY后后续代次中的旧回执、原件撤回/错来源/错default摘要、准备版本不识别均须正反实证。root唯一运行专项与前后verify-all，本代理不运行共享验证。SQL号、D准入、实现/组件/部署/真实流和业务验收分别记录于原主计划；本节审定并实现这个有界差额后立即回CREATE应用/路由，不扩BODY_CLEAR或通用恢复平台。

### 8.18 CREATE原键持久记录窄D审定（2026-10-14，root）

root对§8.17作分步审定：本次PC-CON-PROMPT-CREATE-REQUEST-STORE-01仅准入独立有限请求表、包私有真实JDBC原键存储、prepared标量认证和主Store.prepare的确切receipt ID内部重建入口。差额是主事务尚无行时没有可重启查回的原intent/ID/时间；这是下一CREATE应用必要前置，复用既有ProductPromptJdbc、AuthorityStore.reserve及主五表，不新增主库表或通用恢复服务。采用db/identity-authority/V008__product_prompt_create_request.sql，保留V007原字节；不自动安装、不进入人工候选profile。

字段、唯一键、不可变摘要和最多180日原追溯期按§8.17.1～8.17.2；本窄步operation严格只收CREATE。reserve必须在同一确切authority事务中原子保存request及原control，任一步失败标rollback-only，调用者捕获异常也不能提交半份；查回先原键和audit_until标量判定，期满不得读其他主体/准备值或冒不存在。存储不自行认证HUMAN/Grant/输入许可，包私有值不是权限证据，API/Bean不可直接开放该存储。准备重建仍走现语法/期限/内容/record/result/scalar算法，原receipt ID只在内部传入并规范校验；对持久标量及prepared摘要逐项核对，任何漂移关闭。先提供PENDING原键及原prepared恢复材料，不实现READY writer、五表全后对账或过期intent补READY，不把本出口当完整恢复已实现。§8.17其余应用/许可/HTTP仍待下一整体D审定。

组件出口必须验证真实独立事务reserve提交后重建对象仍定位相同intent/全部不可变标量；同键异摘要/不同主体隔离、重复与中途故障无半control、同prepare固定ID/原时间重建摘要一致、错误准备格式/摘要/有限期限拒绝、到期先标量关闭、不接受外事务/错DataSource。root统一专项及规定后检，源码/组件/运行分列；通过后立即回原CREATE应用的五表对账与当前许可装配，不扩清理平台。实际工作区/来源/Grant及运行准入依原边界，不重复询问已批五权和90/180/7。

本步实现细化：`ProductPromptCreateRequest`仅保存Pending/Receipt/Header及依据标量，canonical展平为§8.17列并补原before=null、after/header/receipt；生成及重建均另核实际revision的record摘要。`ProductPromptCreateRequestStore`复用同一个AuthorityStore的exact JDBC边界，要求调用方已持确切authority事务，直接在main或无事务调用拒绝；它不自行创建外层业务事务、认证主体或提交主库。find只读取并核原PENDING材料及对应control，audit截止后在敏感列SELECT之前关闭；intent到期但audit未到可读材料，reserve仍严格拒绝。表允许未来READY枚举不表示本步实现该writer，当前find对未经本步可核的READY关闭。无生产Bean/启动探针引用V008，现READ不因它未安装增加依赖。专项为`ProductPromptCreateRequestStoreTest`，结果由root统一执行后登记，本文不预称通过。


### 8.19 跨JVM记录摘要稳定与历史读取兼容窄D（2026-10-14，root审定）

`FIX-CON-PROMPT-CANONICAL-RESTART-01`关联CON-10，仅修复§8.14/8.16/8.18已有合同。固定输入的8个独立JVM产生同一content SHA但7种record SHA，原件见`docs/evidence/consultation-delivery-20261014/canonical-probe/results.txt`。Set.copyOf遍历顺序随进程变化，原读取解码后重算record摘要以及原prepared重建因此不可靠；同JVM121项通过不证明重启稳定。本修复是READ/CREATE原键父出口的必要解阻，复用原Store/严格JSON/两现有SQL夹具；结束观察为跨进程五摘要一致、原乱序合法记录仍可读且原字节/摘要不写回，随后立即回§8.17 CREATE应用D。

新PromptRevision将scenarioIds、intentIds防御复制为Collections.unmodifiableSortedSet(new TreeSet<>(...))，保持现内容摘要和领域合同。仅新记录序列化稳定；不改变共享ProductComparisonJson，不重写旧header/witness/scalar closure。读取先沿同Store精确Decimal JSON解析器获得原树，保留原数组顺序核原recordSha，然后仍严格解码PromptRevision并核内容摘要、ID/版本/kind/提交时间及首末当前权威。两集合字段必须为纯文本数组且无重复；未知根/嵌套字段、重复对象键、尾随JSON继续严格拒绝，不因Set折叠而接受非原序列。旧PENDING若只有原标量且不能重建原数组序列，继续摘要漂移关闭，不猜排列、不改旧SHA或续期。 root补充严格性复核：原树核SHA和领域解码后，将原树仅两个集合字段替为领域有序集合，要求其canonical与完整领域对象canonical全等；只允许集合排列兼容，不放宽缺字段、整数浮点强转或其他规范化。加入见证认可的fractional版号/排序值与缺失可空provenance字段反例；精确1.00及原合法旧序正例保留。

源码限领域PromptRevision、ProductPromptStore及原Persistence/CreateRequestStore两专测；无迁移、Bean、HTTP写入口或安装变化。新增反例覆盖旧乱序JSON与真实一致header/witness/closure读取后原件不变、只换数组序或篡改正文拒绝、见证认可但集合重复/未知字段/重复键/尾随仍拒绝、精确小数1.00保留、旧乱序PENDING重建失败且主库零副作用。8个真正独立JVM用固定ID/时间/工作区/原键实际双H2 prepare/reserve/find/rebuild，比较content/record/result/after/prepared五摘要；测试替代输入仅C证据。root独占验证，原后检02:29:16自然exit1（2244/2242通过/2既有skip，30/242/179后继全过，47/41一致，仅原健康两红）作为本窄修复前检，修后仍运行规定后检。


### 8.20 CREATE应用闭包窄D（2026-10-14，root审定）

`PC-CON-PROMPT-CREATE-APPLICATION-01`在§8.19修复组件出口通过后实施，关联CON-10，复用§8.18原键表及现工作区认证，不新增表。差额是现PENDING存储不能完成真实CREATE应用的输入许可验真、主提交后五表对账及READY幂等；必要性来自源码只有存储原语、无当前输入许可适配，且complete无条件插witness/拒全部过期intent。结束观察是实际HUMAN/CREATE、受管原件和真实双库下原键重启恢复、同次原回执、失败零半份；这是正式应用内核，不用固定候选/内存端口替代。HTTP/工作台接线随后继续，源码/C不冒运行交付。

#### 8.20.1 原命令与同原件许可

包内ProductPromptCreateCommand只收expectedGeneration=0、kind/name/body、scenarioIds/intentIds、audienceRef/language/displayOrder、variables与sourceId。workspace由入口选择，组织/主体/修订ID/时间/provenance/期限/许可refSHA不在DTO。沿§8.8大小限制、严格字段与精确Decimal；场景/意图自然排序去重、变量保序，正文/default不trim。requestSha含PROMPT_CREATE_COMMAND/v1、workspace及全部命令含sourceId；content SHA直接复用领域算法（允许只扩大现静态算法的访问可见性，不复制算法），服务器生成修订及provenance。

服务端受管目录使用PROMPT_INPUT_PERMISSION_BINDINGS/v1，严格只有format、bindings；每项只有sourceId（规范UUID）、permissionRef、permissionSha，1～128项、总原件最多1MiB，拒重复sourceId或同ref/SHA多义绑定。目录及许可字节均由ProductSourceEvidenceFiles.verified核部署pin，首末核同一原件，不收客户端路径/SHA。恢复按原Evidence许可ref/SHA反查唯一sourceId，并核原generationSourceRef，不新增request列。

保存许可采用§8.17 PROMPT_INPUT_SAVE_PERMISSION/v1完整字段，细化contentCoverage固定PROJECT_AUTHORED_FULL_DRAFT（完整模板/元数据/所有变量与default均被contentSha覆盖），同原件增加明确traceCoverage=MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY，表示原auditUntil内最小标量追溯用途；不从单独auditUntil数值推定追溯授权。实际决定的来源/版本/责任/日期、项目原创权属及各部分来源链在启用前具证；程序仅验真，不能制作ACTIVE批准自授权。当前仅项目原创完整输入；第三方/混合来源不因该字符串放行。

forWrite核ACTIVE、validFrom<=now<validUntil、CREATE主体/工作区/政策、整份contentSha及now<bodyUntil/auditUntil；forTrace仅读原request标量，核相同原许可坐标/内容SHA/有限追溯范围和now<validUntil/auditUntil，不读取过期正文，不要求body/intent未到。整体许可到期或撤回一律关闭，不增独立trace原件或宽限。私有Witness绑定当前主Lease/线程/holder，字段读取及requireCurrent均要求仍处该lease；Lease可增加仅bound的已认证workspaceEvidence访问器。挂起authority前提取必要标量，authority期间不能使用Witness或Lease。

#### 8.20.2 两次授权跨实际提交

入口拒已有事务，复用authorization.execute(CREATE)取得现HUMAN/准确Grant/工作区批准及workspace写锁。首次POST查询原键：无记录先认证完整输入、分配prompt/revision/receipt/intent UUID和一次毫秒时间，证明主全前，独立原子reserve后主append；已有PENDING依原坐标判态，全前同原许可/命令复建原prepared且各摘要/截止完全相等才续写，全后不写主库，UNKNOWN关闭；READY不新建。新intent为min(原body/audit/许可截止, preparedAt+5分钟)，属于内部有限恢复窗口，不续期、不延任何外部TTL。body/audit取原90/180日、许可、政策、workspace的更短界。

独立事务须用确切main manager的NOT_SUPPORTED显式挂起主上下文（数据库锁继续持有），内层用确切authority READ_COMMITTED短事务；仅另一manager的REQUIRES_NEW不能证明主holder解绑。主同步内authority只读JdbcTemplate会形成合法同步autocommit holder，hasResource本身不等于活动authority事务；准许无借用、非rollback-only且exact元数据/自动提交的受管读holder（resume后空连接由DataSourceUtils受管补足），拒非同步/异物或autoCommit=false。NOT_SUPPORTED内部仍须主及该独立资源均实际解绑，恢复比对原holder身份。期间禁止主Store/Lease操作；恢复后核同主holder/连接及当前权限/原件。锁序main workspace→authority request→control，不反向等待主锁；新增内部边界负责此真实管理器组合，不允许调用者传commit布尔值。主execute正常返回才说明主事务结束；主提交异常按结果未知保原键，不自动换键重发。

POST主提交后再次进入同一authorization.execute，重新认证和取得workspace锁，仅标量对账，独立完成READY后主lease/原件末次复核再回原回执。GET原键直接复用第二边界，全前503不写正文，全后结算，READY核后返回；第一lease不能跨两次execute使用。主commit后失权不假报回滚，已提交事实保留且不回成功。 独立reserve/complete还须提交前期限围栏：在主Lease有效时捕获JWT/session/VERIFY_ONLY key/各Grant/workspace及政策批准的最短截止，并与本动作的输入许可期限取短（reserve含原intent/body/audit，complete只含原audit/control及许可整体有效期）。authority回调前后及beforeCommit同步检查now严格早于该截止，跨点即回滚；不在挂起期间访问主Lease或重新读取主库。恢复后仍执行完整当前状态与原件末核。以独立写中推进Clock跨当前Grant或输入期限的真实SQL反例验证，不能仅测试恢复后返回失败。

#### 8.20.3 五表观察与独立原子结算

观察器只接受当前主事务持锁的受管workspace及原request。全前必须核§8.17.4所有prompt/revision/event/receipt交叉坐标均无行，workspace及独立登记仍一致；不能用head==null替代。全后逐字段比原head/header/event/receipt和完整scalar closure，并以SQL原生NULL谓词核PRESENT正文列非NULL，不取JSON正文。缺任一行、孤立交叉坐标、PENDING头前进、错期限/摘要/JSON NULL或CLEARED均UNKNOWN。

EXACT_AFTER见证私有构造、绑定当前线程/主holder/原request及确切观察器，只有同调用显式挂起主后的独立结算边界可消费，跨请求/换holder/自造记录拒绝；每次重新观察。主锁保持至第二主事务结算，独立回调不访问主库。仅该完整见证可在intent/body到期后结算，仍需原audit/control和当前许可/授权有效；全前绝不越过intent续写。

扩展原RequestStore READY读取仍先audit标量；独立结算事务锁request/control，重核原prepared与原键；PENDING下witness无则插入，有则逐字段全等才重放，control CAS READY与request READY同事务完成，异常rollback-only。READY重放不新插事件/不续期，不要求当前头永远停第一代；后续SAVE已获准并推进时核当前READY闭包及原revision witness/receipt，不回写旧control。原body读取守卫不放宽；追溯标量路径不复用要求body未过期的readRevision。

#### 8.20.4 验证与未交付边界

两类事务各30秒；实际Statement超时为min(5秒,事务剩余秒数)，不足1秒不执行。覆盖工作区/身份/Grant锁和主独立SQL，不能只设置共享JdbcTemplate全局值或漏掉事务超时覆盖行为；沿确切DataSource构造专属JDBC适配，不运行时改共享实例。源码复核发现ProductWatermarkJdbc.buffered会重建普通JdbcTemplate并丢失子类超时；仅对新ProductPromptStatementJdbcTemplate保留专属类型，其余实例及既有buffer配置保持，纳入水位回归，不新增通用框架。H2验证实际Statement设置、竞争及挂起/恢复；DM8取消/锁结算必须实际取证，源码不冒相同运行语义。

组件须覆盖独立reserve提交后主失败及重建原prepared、主提交后READY丢失、主五表每类孤立残留/原生NULL、独立三写中途故障全回滚、双完成竞争、READY及后续代次原回执、同键异摘要/换主体、错误许可/正文/default摘要、首末撤回/失权、intent/body/audit/许可恰到、authority期间主lease拒绝与恢复正例。root统一测试/计划，分文件并行实现；通过后立即接标准POST/GET及正式工作台，不扩清理/登记/维护安装。本批前检复用02:29:16自然结果，最终统一后检；不解除固定人工候选、物理删除或运行准入边界。


### 8.21 CREATE标准HTTP装配窄D（2026-10-14，root审定）

`PC-CON-PROMPT-CREATE-HTTP-01`待§8.20应用组件出口通过后接续；同CON-10，不新增权限、保存期或来源行为。差额是标准工作台尚无可调用CREATE/原键查回入口，复用真实应用、现Prompt安全chain/HTTPS/JWT及已受管工作区/许可目录。退出观察为真实签名JWT+生产Bean+双库HTTP完成准确原回执及错误/到期/重放反例，立即回正式工作台；不冒运行启用。

POST `/v2/consultation/prompt-workspaces/{workspaceId}/prompts`与GET `/v2/consultation/prompt-workspaces/{workspaceId}/requests/CREATE`沿§8.17。原键只能一个Idempotency-Key header，1～64可打印ASCII且无空白；拒多值/重复header及意外query，不能将多头合并成新键。workspace规范UUID。POST仅application/json，以有界UTF8原始读取最多1MiB+1判断413再严格decode，不先无界读全body；字段错误400，异常媒介415。GET无正文/无query。成功统一200原回执，有限输出receiptId/workspaceId/promptId/revisionId/generation/contentSha/resultSha/submittedAt/auditUntil，无正文/许可路径/主体/原键；成功不授READ。异命令typed conflict409，无当前CREATE/不存在403，身份无效401，输入400/413，工程/未知提交503；不按任意异常字符串猜业务状态。全部响应no-store，TLS/路径既有守卫保留。

显式独立开关`product.consultation.prompt.create-enabled=true`，默认关闭；启用必须同时开启现prompt及identity，缺设置或表结构启动失败关闭。复用prompt.evidence-root/workspace bindings；新增input-permissions-ref及input-permissions-sha256仅受管目录pin，不从请求装配。专属bounded JDBC链覆盖Store/Authority/Access/水位/身份锁和内部decoder所需key查询，沿同确切主/独立DataSource及JDBC manager；从相同现identity配置构建只读key/decoder对象，不rotate/导入/改密钥/签Grant。READ现Bean不增加V008/输入许可依赖。启动只SELECT已有V112/V007/V008必要列WHERE 1=0，不执行迁移或工作区登记；不改固定人工候选/发行配置。

组件必须用实际生产Configuration/Controller/security chain、真实decoder/两库、有效与撤回会话/CREATE Grant和受管原件；覆盖原键重复、异命令409、缺Grant/缺对象403、失效session401、错误scope/许可及未知结算503、多header/query/错误UUID/未知JSON/过大body、post成功后无READ拒正文、默认关闭与缺依赖启动关闭。root唯一跑受影响专项和同批完整后检，不以Mock应用controller测试代真实接线。


HTTP首轮反例补核：未受管workspace坐标必须与无Grant统一403，因此当前授权先核受管绑定存在再锁主登记；受管绑定确有该workspace而主登记缺失仍是完整性故障503，不能把真实漂移伪装成不存在。该区分复用已有绑定，不新设权限或存储；缺工作区403及已登记缺行503分别实测。

### 8.22 CREATE工作台窄D（2026-10-14，root审定）

`PC-CON-PROMPT-CREATE-UI-01`关联CON-10；应用组件已通过，§8.21端点与DTO合同已固定，因此本前端源码可与HTTP装配分文件并行。退出仍须HTTP生产组件和本UI合同共同通过，不用模拟响应代替服务端实现。差额是内部人员尚不能通过现正式登录提交空白草稿和取回未知结果；复用现ConsultationFrame/PKCE、PromptRevisionBrowser及样式，不增加后台来源登记、READ自动授权或演示页。本窄项不代表完整工作区列表/SAVE/COPY/PREVIEW已交付，退出立即回父ARC-CON-PROMPT-OPERATION-D-01。

入口为`/app/consultation?view=prompt-create&workspace=<规范UUID>`，目的地严格字段并绑定现PKCE state，拒重复/混合prompt-revision或其他view坐标。工作区ID是选择坐标，不是授权结论；CREATE-only无需先读工作区，页面不虚构工作区名称/动作列表。来源编号输入已取得保存许可的sourceId，不收路径、SHA或许可自报；缺许可由原真实服务拒绝，不编造候选和来源权。来源管理/发现属于后续独立已准入范围，本页可使用责任方提供的准确工作区/来源编号。

使用正常有label的表单：类型SYSTEM/GUIDE、名称、正文、场景/意图稳定ID（逐项）、受众引用、语言、排序与变量定义。空白稿无预置正文。变量支持现五种类型、name/sourceRef/required/allowDefault/allowEmpty；默认值按类型控件输入，字符串不trim，列表逐项，数值保留输入字面文本。INTEGER只接受严格JSON整数语法，DECIMAL只接受严格有限JSON小数或指数语法并保证有小数/指数标记，由受限数值编码直接组成该defaultValue；不能先Number或JSON.parse再序列化丢精度，不能允许任意JSON注入。元数据排序限制现Java int范围；请求完整字段/UTF8大小按§8.8，错误定位字段且不回显敏感值。前端不代替服务端批准。

提交前建立不可变内存请求：同token/工作区绑定、随机UUID原键及完整严格序列化命令。重复点击/正在发送禁第二次请求；先显示可记录的提交编号。成功仅展示九项回执的用户可用信息，响应必须严格字段/UUID/摘要/代次/时间、匹配workspace且追溯未过期；错误回执当结果未知。成功后不自动READ；用户显式打开确切版本链接仍经独立READ，不将提交内容冒服务器读回。确认回执后清理编辑正文及原命令，允许显式新建下一份。

网络错误、响应损坏或503保留原命令/key并锁定编辑，默认提供原键GET查询，不自动POST/换key；用户明确点击“按原内容重试”才能发送原字节和原key。409继续原键查询，不改键碰撞成新稿；400/413/415只在首次POST明确入站拒绝且没有更早未知提交时可回编辑，下一次显式提交才分配新键；已有未知提交后同键重试收到这些码，仍不能证明原提交失败，保持原键/原文和未知状态，不释放为新建。GET查回不等于重试写入，未知/403不宣称服务器已取消。刷新丢失正文内存后，允许输入用户已记录的原提交编号只GET查回，不自动重建或重放正文；编号不放URL。无localStorage/sessionStorage正文/default/许可/原键缓存，不新增客户端保留规则。

token/工作区变化、退出、401/403清正文/回执/原命令并失效请求epoch，AbortController防迟到结果覆盖新选择；401归现重新登录，403关闭本页写入并提示当前权限不可用。回执到auditUntil关闭显示，不通过前端读取续期。键盘可达、可聚焦错误/aria-live状态、提交与恢复动作清楚；沿现16px/44px控件和语义色，390px与桌面网格不横溢。

文件边界：prompt目录新CreateWorkspace/typed transport/command encoder/变量编辑及专测；root独占共享api.ts目的地、ConsultationResourcePage与导航专测。不放宽共享tasks-only request。验证实际组件输入与fetch contract、精确大数/小数、原键未知→GET→同字节重试、重复点击、401/403清除、换工作区/会话迟到隔离、成功不READ、错回执关闭、已知键刷新查回、PKCE深链与原READ/商标回归；root统一TS/咨询构建与规定后检。部署、真实DM8和业务验收仍分别待运行准入。

### 8.23 SAVE原键持久恢复与应用窄D（2026-10-14，root审定）

本节是CON-10已过R和已确认五权/90-180-7范围内的派生技术决定，不新增业务许可。任务PC-CON-PROMPT-SAVE-APPLICATION-01补齐已有草稿追加不可变修订、故障后原键查回的缺口；现在需把CREATE闭环推进到编辑保存，复用Store.prepare/append、真实SAVE授权、AuthorityTransactions及一次性ExactAfter提交证明。出口为真实双库应用和原键/旧CAS/期限/许可/故障反例，随后回父ARC-CON-PROMPT-OPERATION-D-01审HTTP及工作台；本节不准入HTTP、部署、真实资料或完整清理。

应用request(token,promptId,key,command)先以只读标量定位实际workspace，再在当前SAVE授权及workspace锁内核归属；lookup(token,workspaceId,key)只查SAVE原键回执。命令沿CREATE完整内容字段、sourceId、严格JSON/UTF8/大小/精确数值规则，增加expectedRevisionId UUID，expectedGeneration为1至Long.MAX_VALUE-1，目标代次=旧代次+1；kind不可漂移。request SHA使用PROMPT_SAVE_COMMAND/v1，认证实际workspace、路径promptId、完整CAS/全文/schema/sourceId，不收客户端provenance/批准路径。原键作用域继续issuer/subject/workspace/operation/key SHA；CREATE与SAVE同文本key可并存，同SAVE key换prompt或旧CAS冲突。已有同键先对完整requestSHA，再对账，不先拿最新head拒绝旧成功请求。

继续使用idp_prompt_request，不增表或列。后续独立迁移V009__product_prompt_save_request.sql只替换ck_idp_request约束，保留V008原字节和CREATE所有条件；增加SAVE且expectedGeneration>0、准确旧revision、target=expected+1、独立格式PROMPT_SAVE_PREPARED_SCALARS/v1/PROMPT_SAVE_COMMAND/v1、container>=body/control>=audit且container<=control，保留通用状态/时间/范围条件。CREATE canonical字段、格式、before=null及0/null/1完全不变。SAVE canonical.before是认证坐标{promptId,workspaceId,kind,generation:expectedGeneration,revisionId:expectedRevisionId,scalarSha:beforeSha}，不是伪造的完整Head；旧期限由受锁主head及beforeSha闭包认证。requirePrepared逐项核旧六坐标、max(旧container,本body)/max(旧control,本audit)及完整after/header/receipt/recordSHA；重试固定原ID/时间/期限/许可，不重起算。

三态：新请求先核当前主head/closure与独立READY全等，再作旧CAS；ALL_BEFORE要求准确受锁旧head/闭包、独立本次PENDING（首次reserve之前为同旧READY），并证明目标revision/目标generation/event/receipt/原SAVE key所有交叉坐标无新孤行，已有历史保留。仅完整原POST、当前SAVE/原许可仍有效、原intent/body/audit未到可续写；GET不重建正文。EXACT_AFTER的PENDING必须准确目标head、目标revision/header及原event/receipt闭合；主事务返回后第二次授权取得私有一次性proof，独立事务原子补witness/control/request READY。control CAS包含expectedGeneration/expectedRevisionId及原intent全部坐标。原intent/body到期而主已提交可在原audit/当前SAVE/原追溯许可内仅标量结算。READY旧键允许当前generation>=原目标；同代次须原after全等，后继代次须当前READY主独立闭包和原witness/event/receipt同时全等，不回写旧control；后继PENDING或混合状态UNKNOWN关闭。

输入许可复用原目录与严格原件，但operation必须明确SAVE，Lease.action亦为SAVE；CREATE许可不能代替。当前完整新内容contentSha、主体/组织/workspace/政策/期限与traceCoverage逐项核，sourceId仅定位已获准原件。新修订body/audit分别从本固定提交时刻90/180日并取许可更短期限，旧修订及原事件期限不变；容器期限只是各有效修订范围的max，不续旧正文。

创建/修改信息采用保守技术映射：原需求§2创建时间/更新时间及创建/修改人（CON-10 MUST-005/SEM-MUST-008）分别留痕。新revision.createdBy/createdAt继承经认证第1代CREATE非正文记录，modifiedBy/modifiedAt取本次真实SAVE主体及固定提交时间，changeDescription=SAVE_DRAFT；当前generationSourceRef/contentApprovalRef/policy取本次完整输入许可，testRecordRefs为空。新增包私有ProductPromptCreationOrigin，仅返回内部Origin(createdBy,createdAt)：受当前SAVE/主workspace锁/旧head闭包保护，先核首代audit，再核首代Header/Event/Receipt的全部交叉字段及独立witness主体/时间/摘要/期限/原键，issuer必须相同而创建主体可不同；不读revision_json、不要求旧body未到、不向SAVE-only返回历史字段。新请求核READY；原ALL_BEFORE续写核本次完整PENDING且首末control相同，不能调用要求当前READY的正文读取。首代缺失/过期/篡改关闭，不默换创建人。requirePrepared的SAVE分支核修改信息及完整recordSHA，应用重建时再次认证首代origin。

验证需覆盖：正常仅SAVE无READ；A创建/B保存保留A创建信息且无旧正文读取；旧body已到而audit有效仍可凭获准新全文保存且旧期限不变；旧CAS/异key并发仅一获PENDING；独立reserve后主失败原ID/时间恢复；主commit后READY失败且intent/body到期仅标量结算；旧头篡改/目标孤行/首代见证篡改UNKNOWN；SAVE1成功、SAVE2升代仍查回SAVE1；CREATE/SAVE同文本key隔离及SAVE异prompt/异base冲突；原CREATE canonical跨JVM兼容。现scalarSha先audit守卫继续复用，清后闭包演进/物理删除仍未交付。

文件所有权：prompt_review独占Request/RequestStore/Reconciliation及对应反例；human_install_review独占SaveCommand/InputPermission/CreationOrigin及专测；trademark_finish独占SaveApplication及应用反例；root独占本ADR/主计划/V009及全部构建验证。完整后检2026-10-14 03:43:14自然exit1（仅既有TESTS_FAILING/TEST_EVIDENCE_STALE）作为本增量前检，原件create-post-verify.log及exit保留。实际DM8安装/资料Grant/运行准入另核，不更改固定人工候选。

#### 8.23.1 模板语法输入拒绝窄修（2026-10-14，root审定）

SAVE命令复用审查发现CREATE严格decode尚不调用现TemplateRenderer.validateSyntax；旧编译产物的真实decode接受未闭合`{{`，见create-syntax-before.txt，首个source-launcher类加载失败原件单独保留。Store.prepare后置语法失败是未映射PromptFailure，不能满足§8.12模板错误400。FIX-CON-PROMPT-TEMPLATE-INPUT-01仅把现纯语法校验前移到CREATE/SAVE命令构造并转invalid()，HTTP沿现decode异常400/no-store；不增加模板语法、变量替换、发布或业务许可。合法CREATE格式/摘要不变。出口为不闭合/非法变量名/嵌套/多余闭合拒绝及合法字面量/占位符保持，再立即回SAVE应用回归；原compiled probe与组件证据分别记录。

### 8.24 SAVE标准HTTP与精确工作区窄D（2026-10-14，root审定）

前置§8.23应用及语法窄修已通过376项真实组件（03:59:36、1789输入稳定），原件save-verification-result.json。PC-CON-PROMPT-SAVE-HTTP-01补标准传输/部署装配，复用现安全chain、CREATE bounded解析/私有身份链及双库应用；出口是实际生产配置/JWT/两H2的正常与拒绝HTTP，随后回同父工作台，不代真实DM8。

POST采用`/v2/consultation/prompt-workspaces/{workspaceId}/prompts/{promptId}/revisions`，细化早期§8.8路径为显式工作区坐标，避免用户所见工作区与prompt真实归属不同却先写后报错。只调用新增request(token,expectedWorkspaceId,promptId,key,command)重载：严格UUID，标量locate实际工作区，在任何reserve前精确核等，错区统一403/零写；原四参数应用入口供既有内部组件复用，不注册其它HTTP路径。GET `/v2/consultation/prompt-workspaces/{workspaceId}/requests/SAVE`仅原键查回，不收prompt/正文/query。两端均恰一Idempotency-Key（1～64无空白可打印ASCII）、拒原始query/重复header；POST仅application/json UTF8、readNBytes(1MiB+1)先限额；GET读1字节即拒body。响应全部no-store，不自动READ。

九字段回执receiptId/workspaceId/promptId/revisionId/generation/contentSha/resultSha/submittedAt/auditUntil；SAVE的generation使用规范十进制字符串（2..Long.MAX_VALUE）以无损穿过浏览器，不改变CREATE已发布DTO。确定性新请求旧CAS/kind不符抛独立ProductPromptSaveBaseConflict→409/PROMPT_SAVE_BASE_CONFLICT；原键异完整摘要沿typed conflict→409/PROMPT_SAVE_KEY_CONFLICT。其他400/401/403/413/415/503沿§8.21，错误不回正文/批准路径。只有从未未知的首次BASE_CONFLICT才是明确未写，客户端可修改base后新提交；UNKNOWN后任何409仍保原键，不从新一次拒绝推断旧请求未提交。

显式默认off开关product.consultation.prompt.save-enabled，必须prompt.enabled及identity.enabled。CREATE/SAVE开关独立；复用有限私有write components装配以免复制身份/存储链，原CREATE配置行为保持。完整bounded主/独立Jdbc/Store/Access/Watermark/Keys/decoder及精确manager不放宽，现issuer/audience/master-key/verification window，不注册第二共享JwtDecoder，不旋转密钥或签Grant。只SELECT schema列，不执行试探写、DDL或注册。SAVE实际启用前必须核V009已安装及现时身份/资料Grant；V008-only写入拒绝并原子回滚，不称列查询能证明约束升级。无新迁移/清理/运行许可。

验证包括：正确SAVE-only200、九字段generation字符串与精确大代次DTO、同key恢复/两种409、错workspace先于reserve403、未受管403/已登记损坏503、READ-only403、JWT/会话/HTTPS、坏header/媒体/JSON/模板/超限/GET body、默认off及CREATE-only不启SAVE、实际装配无身份写、V008-only拒绝无半份。应用scope/type由prompt_review负责；生产HTTP/配置/专测由human_install_review负责；root唯一计划/验证。

### 8.25 SAVE工作台窄D（2026-10-14，root审定）

PC-CON-PROMPT-SAVE-UI-01依赖已过应用组件和固定§8.24 HTTP合同，因此源码可与HTTP按文件并行，退出必须合并服务端真实证据。差额是用户尚不能操作完整新稿保存与旧CAS恢复；复用现正式PKCE/Frame及CREATE表单控件/精确编码，出口为标准登录深链→完整保存→回执/原键恢复的源码和C，不将模拟HTTP作为真实部署。

目的地`{view:'prompt-save',workspaceId,promptId}`对应`/app/consultation?view=prompt-save&workspace=<UUID>&prompt=<UUID>`，两规范UUID固定，PKCE绑定原目的地，拒重复/混合READ revision或其它坐标。页面不自动READ/history，提供空白完整稿及expectedGeneration十进制文本、expectedRevisionId；当前页面workspace/prompt不可编辑。CAS用BigInt核1..Long.MAX_VALUE-1后原数字token编码，默认数值保现精度，sourceId仍已获准来源编号，不接原件路径/批准声明。提取有限共享PromptDraftFields和纯内容编码复用；CREATE现公开字段/字节/生命周期行为保持，不为两操作新增通用工作流框架。

SAVE POST严格核回执workspace/prompt及冻结expected+1；有冻结命令的GET同核，刷新手填key只核页面workspace/prompt与合法代次，不以新输入CAS或后续head否认旧回执。GET不加prompt参数、不改原键命名空间；另prompt合法回执在本页显示无法核验当前所选对象，不宣称key不存在或保存失败。成功清正文/default/原命令，只显有限回执和显式确切READ链接；后续保存仍空白新全文，可从成功回执带入已知CAS但不宣称永远最新。

同步busy/epoch/Abort及内存冻结key+完整原bytes，UNKNOWN仅GET或用户显式同key/同bytes重试，无自动重POST。首次400/413/415及从未UNKNOWN的BASE_CONFLICT可回编辑；后者保留全文供修正准确base。KEY_CONFLICT及任何曾UNKNOWN后的拒绝仍原键锁定。401/403、token/workspace/prompt变化及退出清敏感内存并隔离迟到；回执到audit关闭，不写浏览器正文/默认值/原键缓存。不接READ页自动带出旧稿，以免无授权或丢失旧正文到期控制；完整编辑/复制后续按各D继续。

控件/错误aria/聚焦/44px及现主题沿CREATE，桌面1280/390实查。至少覆盖无损长代次、数值默认值、首次旧CAS可修正/UNKNOWN后409保原键、双击/原bytes重试、错回执/跨目标迟到、401/403清理、成功不READ及CREATE原套/正式登录目的地回归。trademark_finish独占prompt目录UI/共享有限fields及专测；root独占api.ts目的地、ResourcePage及对应导航专测、全部构建浏览器验证。规定完整后检在本批HTTP/UI完成后统一，期间定向检查输入稳定性分别留证。

### 8.26 COPY确切来源与应用恢复窄D（2026-10-14，root审定）

PC-CON-PROMPT-COPY-APPLICATION-01在CON-10已过R、已确认内部同组织五权和90/180/7期限范围内实施；是ARC-CON-PROMPT-OPERATION-D-01的下一操作。当前差额是不能把获准确切草稿复制成独立新对象并恢复原提交；现在CREATE/SAVE入口已具C，可复用现executeCopy双权/排序工作区锁、Store不可变修订和COPY写原语、原键双库协议。出口为实际身份/两H2应用组件及恢复反例、CREATE/SAVE旧规范字节兼容，随后回父HTTP/工作台窄D。没有HTTP/部署/资料授权/注册/清理副作用；真实输入许可在启用前具证。

**命令及选择坐标。** 新ProductPromptCopyCommand仅expectedGeneration（严格整数0）、sourceRevisionId、sourceContentSha、targetWorkspaceId、sourceId；UUID/64hex/严格UTF8-JSON/重复未知字段/1MiB规则沿现命令。应用request(token,sourceWorkspaceId,sourcePromptId,key,command)，lookup(token,sourceWorkspaceId,targetWorkspaceId,key)。sourceId只选择受管COPY许可，不能客户端自报正文、组织、策略、许可路径、创建者或改名。新格式PROMPT_COPY_COMMAND/v1摘要覆盖上述完整命令及源workspace/prompt；不读源正文即可比较原键摘要。源确切版全量内容（含名称、类型、变量及默认值）原样复制，目标新prompt/revision UUID和代次1，contentSha与命令/源读回/复制许可完全相等。不给默认“副本”名称。旧成功请求不以现源head代次否定。

**最小原键增量。** 在现ProductPromptCreateRequest追加可空CopySource：sourceWorkspaceId/sourcePromptId/sourceRevisionId/sourceRevisionNumber/sourceRecordSha/sourceBodyUntil/sourceAuditUntil。数据库独立V010__product_prompt_copy_request.sql只为idp_prompt_request增加相应7个nullable列及替换原约束，不改V008/V009原件、主库或新增表。COPY须全非null、number>0、body<=audit，目标body/audit各不得超源确切截止；CREATE/SAVE七列全null由V010 CHECK保障。source内容SHA与原目标content_sha相同，不重复列；无跨库或指向将到期源对象的FK。COPY新增PROMPT_COPY_PREPARED_SCALARS/v1；和CREATE同为before=null、expected0/null、target1、container=body、control=audit。Receipt/Event仍operation=COPY，目标原键namespace=issuer/subject/targetWorkspace/COPY/keySha。

保持旧构造和prepared三参数入口，新增COPY重载。只有COPY的columns/canonical包含七列，旧CREATE/SAVE的字段集合、null语义、格式和规范字节全部不改；旧操作查询仍原列，因而不新增V010硬前置，也不声称应用已经检查旧行新增列值。COPY查询必须显式选择七列并逐项核合法和prepared摘要；COPY配置后续必须探针七列并在启用前核V010约束实际安装。修Reconciliation非SAVE分支的CREATE硬编码为原确切操作，beforeControl无control允许CREATE/COPY；所有目标UNKNOWN/EXACT_AFTER证明、单次消费和原子READY仍复用，不变成可伪造提交证明。COPY来源不是待修改控制行。

**源快照及权限。** 新ProductPromptCopySourceSnapshot在当前executeCopy Lease内使用真实源READ+目标COPY，同组织，普通CREATE不能替代。调用load(lease,sourceWorkspaceId,sourcePromptId,sourceRevisionId,sourceContentSha,targetPromptId)，返回私有构造Snapshot及source()/revision()/requireCurrent()。target UUID在源正文读取前分配；续写用原target UUID。先按源/目标prompt UUID排序预锁，再readRevision认证确切头/选版/独立见证、record/content摘要；源prompt必须真实属于源工作区。所有工作区仍由executeCopy按排序先锁，Snapshot绑定同线程/主事务holder/Lease。Lease以requireCopySource/requireCopyTarget分别核现rights中的源READ和目标COPY工作区坐标，防止双工作区内部调用互换角色；不新增任何权利或授权。requireCurrent只读标量、现READY闭包/确切选版/期限，禁止重复读JSON；源头可在不同请求之间推进至后继READY，指定旧revision不变且有效即可，同一请求取得快照后末次必须仍等。源后继PENDING阻fresh/ALL_BEFORE，不能用未变旧正文绕过独立控制。

新ProductPromptCopyInputPermission复用ProductSourceEvidenceFiles及相同受管binding目录格式，但只解析PROMPT_INPUT_COPY_PERMISSION/v1，operation=COPY，contentCoverage=EXACT_PROMPT_REVISION_COPY，traceCoverage=MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY。原件包含来源/版本/责任方/日期/ACTIVE/有限validFrom/validUntil，目标workspace/organization/scopeVersion/issuer/subject/policyRef/policySha/contentSha256/bodyUntil/auditUntil；另绑定源workspace/prompt/revision/number/recordSha/contentSha/bodyUntil/auditUntil以及源scopeVersion/registrySha/policyRef/policySha。generationSourceRef固定为PROMPT_REVISION:<sourcePromptId>:<sourceRevisionId>。现CREATE/SAVE许可解析不放宽。forWrite(lease,command,snapshot)核当前双方工作区及确切源；forTrace(lease,originalRequest)仅认证原CopySource/原提交时刻/固定期限、原件当前有效性及追溯覆盖，不创建Snapshot、不回读源头/正文。sourceIdFor沿原件绑定定位。Witness提供evidence/bodyLimit/auditLimit/requireCurrent；原件或目录SHA/状态变化一律关闭。

目标bodyUntil=min(原submittedAt+90日、源选中Header.bodyUntil、COPY许可bodyUntil及validUntil、双方工作区/批准/政策有限期限)；auditUntil同理取180日/源选中Header.auditUntil/许可auditUntil及双方边界。不得取可能被后续版本抬高的源Head.container/control，不按重试now续期。当前Grant/session决定本次操作可否执行，不能当新业务保存期限。原件有效期及源更短约束不会因为复制扩大。

**事务及恢复。** 每次先executeCopy认证双权→find同主体目标/COPY原key→核完整命令SHA/原CopySource坐标。fresh及ALL_BEFORE才创建Snapshot和COPY写许可；新请求固定target/revision/receipt/intent UUID及提交时刻，prepare目标→证明ALL_BEFORE→独立reserve→恢复主holder后重核快照/许可/Lease→append→末次同快照/许可/期限复核。ALL_BEFORE重建必须保持原全部七源标量、ID/时间/期限/许可/recordSHA；旧intent或源body到期关闭，不换key掩盖未知。独立事务期间只用事先取得cutoff，不在主挂起时调用Lease/Snapshot。主写在当前主事务注册beforeCommit固定截止守卫，最小值含原intent/body/audit及COPY许可body/audit（已含源选版截止）；最后授权/原件核验期间跨截止仍真实回滚主写，已独立reserve保持PENDING。守卫只读固定Instant及Clock，不在挂起独立事务调用Lease/Snapshot。

第一主executeCopy实际返回commit后，第二次executeCopy重新认证双权/原key/COPY trace→目标EXACT_AFTER→一次性proof→独立原子完成。EXACT_AFTER/原READY不查源head/Header/witness/JSON；源body到期或后继PENDING不否定已提交目标，当前源workspace READ及目标COPY、原trace/目标audit/control仍必须有效。仅回标量不代表目标正文仍可读。原target后继SAVE后仍可查原COPY回执；target后继PENDING保持UNKNOWN。lookup显式源workspace只作授权选择，find后须与原CopySource一致，否则统一403/不返回正确源坐标；不引入未认证独立key扫描或target-only COPY例外。

**创建信息联动。** 复制成新对象是§8.4已定语义，故Codex将其createdBy=modifiedBy映射为本次真实复制操作者，createdAt=modifiedAt为固定提交时刻，changeDescription=COPY_DRAFT、testRecordRefs空、来源精确引用/目标批准/政策明确。这是派生技术映射，不冒原需求逐字规定。源作者保留在源不可变历史。ProductPromptCreationOrigin允许首代CREATE或COPY，首Receipt/Event操作必须精确相等且独立witness闭合；后续SAVE保留本目标的首创建者，不要求再次取得原复制源READ或读取原源正文。

**分工与验证。** prompt_review独占Request/RequestStore/Reconciliation及对应COPY存储/恢复专测；human_install_review独占CopyCommand/CopySourceSnapshot/CopyInputPermission及专测；trademark_finish独占CopyApplication、CreationOrigin联动及应用专测；root独占本D/主计划/V010、共享接口协调与全部测试。各方不得并行写相同fixture/输出；可各自新增独立fixture。验证需真实两H2和JWT/Grant/原件，正常COPY-only+源READ无目标READ、跨组织/错源/普通CREATE拒绝、指定旧有效版、源PENDING及恰到拒绝、真实主写失败同原ID恢复、主提交后源过期/后继PENDING仅标量结算、旧target升代仍查原键、错源列/原件SHA/半份状态关闭、COPY→另一操作者SAVE及首操作篡改拒绝。旧CREATE五摘要和SAVE五摘要以本批前真实编译probe为金样，跨进程不变；原失败保留，root受影响回归及规定后检。SAVE结构修复后的统一完整后检自然终态作为本增量前检，不能以仍运行或中途结构失败的检查准入。固定人工4ecb候选不重建，不安装SQL或解除暂停。

### 8.27 COPY标准HTTP与独立装配窄D（2026-10-14，root审定）

PC-CON-PROMPT-COPY-HTTP-01依赖§8.26应用组件出口：569项真实两H2组件05:18:00自然exit0，1811输入稳定，CREATE/SAVE十摘要兼容，见copy-verification-result.json。差额是确切复制尚无标准传输入口；复用CREATE/SAVE有界HTTP、安全链和私有装配。沿本连续COPY批次04:56:58完整前检，HTTP/UI源码收齐后统一规定后检，不将尚待后检或组件通过冒作部署/业务验收。

POST `/v2/consultation/prompt-workspaces/{sourceWorkspaceId}/prompts/{sourcePromptId}/copies`调用既有五字段命令及应用；不允许query，严格唯一Idempotency-Key、application/json UTF8、1MiB有界读、UUID及JSON，拒客户端正文和改名。GET `/v2/consultation/prompt-workspaces/{targetWorkspaceId}/requests/COPY?sourceWorkspaceId=<规范UUID>`只解析raw query，唯一字段、规范小写UUID，不允许重复、百分号编码、分号、加号、多参、空或尾分隔符；不用getParameter触发正文解析，读取一字节发现body即400。两端均no-store；原九字段回执，generation为字符串`1`且核operation=COPY。原键异命令typed conflict映射409/PROMPT_COPY_KEY_CONFLICT，其余错误沿SAVE，无正文/批准原件回显。

默认off的copy-enabled与CREATE/SAVE独立，显式要求prompt.enabled及identity.enabled。只使用copy-input-permissions-ref/sha256，不回落或强迫配置旧CREATE/SAVE许可。ProductPromptWriteComponents有限提取私有Core（不是Bean或通用工厂），旧assemble签名及行为保持；COPY装配只构造其专用许可。保留精确两库manager、有界Jdbc、私有decoder/现身份密钥链，零身份写。CREATE/SAVE列探针维持原集且不增加V010依赖；COPY额外探针七源列。列存在不证明V010 CHECK实际安装；约束安装证据、真实人员/Grant/资料许可仍为启用前置。本批不安装SQL、不部署、不改固定人工候选。

确切READ响应补workspaceId/contentSha二标量，分别取已READ认证的实际workspace及选版Header.contentSha；不从客户端或JSON猜归属。ProductPromptReadView.Revision.from传入该workspace，history不扩，正文/期限/独立见证检查保持。前端strict schema同批更新。验证为生产配置/JWT/两H2的COPY-only成功、双权/错scope/跨组织拒绝、原键恢复/409、严格raw query/媒体/正文/限额、安全链、默认off/COPY-only不要求旧许可、CREATE/SAVE旧schema兼容及READ二标量真实匹配。

### 8.28 COPY工作台与独立原键恢复窄D（2026-10-14，root审定）

PC-CON-PROMPT-COPY-UI-01依赖§8.26组件及本§8.27固定HTTP合同，源码可并行，出口须合并HTTP实证。缺口是用户无法从已读确切版发起复制及恢复；复用正式PKCE/Frame、既有表单样式和有限回执。独立审查采用Astra High，只读确认以下边界，无新增业务决定。

目的地为`{view:'prompt-copy',sourceWorkspaceId,sourcePromptId,sourceRevisionId,sourceContentSha}`，对应query的sourceWorkspace/sourcePrompt/sourceRevision/sourceContentSha四元；全部规范UUID/64hex，严格重复/混搭/部分坐标拒绝并绑定PKCE。另允许严格无四元`{view:'prompt-copy'}`，只展示原提交恢复，不能显示新POST或自动READ，保证源正文到期/没有旧SHA仍可凭源工作区、目标工作区、原key查回。URL不含正文/default。确切READ页仅在当前有效revision可操作时提供复制按钮，从真实READ DTO取得四元，以同token内部导航，不全页跳转丢会话；切版、失权、到期后清旧入口。

新复制页固定源四元，只输入targetWorkspaceId/sourceId；不接正文编辑、改名或默认副本。提交冻结token、全部源四元、target/sourceId、序列化原bytes和key；同步busy、epoch及Abort隔离迟到，UNKNOWN只允许原keyGET或显式原bytes重试，不自动新key/POST。未决POST期间独立手填恢复不能覆盖或清除其冻结key/坐标，只恢复该提交。首次400/413/415可修输入；任何曾UNKNOWN后的拒绝保持原提交上下文。KEY409进入仅查回并禁该冲突命令POST重试；它不证明原命令与当前源SHA相同，因此后续GET不得按当前冻结SHA拒绝有效原回执。

手填恢复有独立sourceWorkspace/targetWorkspace/key输入，按token、这三个坐标及epoch冻结查询；改表单后旧200不显示为新坐标结果，不从当前复制选择补值覆盖。正常本次提交/UNKNOWN查回核目标workspace、generation='1'、contentSha及有效期限；手填或KEY409后查回只核真实目标和合法回执，不把相同contentSha当相同源版证明，显示“已查回原复制提交”。所有成功清冻结原命令，只显有限回执及显式目标确切READ链接，无自动target READ，也不称正文一直可用。401/403、token/来源路由变化及退出清内存，回执audit到期关闭，不写浏览器提交缓存。

出口验证含正式PKCE深链/无坐标恢复、真实READ入口、UNKNOWN原bytes/同key、KEY409后不同SHA旧回执、手填恢复与当前来源不同、不允许未决覆盖、重复点击/跨坐标迟到/401403/到期、成功不READ和现CREATE/SAVE/READ回归；1280/390真实浏览器合成HTTP组件观察，不冒运行实证。分工：copy_http独占HTTP/config/Core/READ后端及测试；copy_ui独占prompt目录UI/API/fixtures/专测；root独占api.ts、ResourcePage/ProductPage、导航/资源专测、计划与所有构建/验证。两代理Sol High，复杂设计审查Astra High已收；不得共享写fixture或构建输出。

#### 8.28.1 共享导航页面结构窄修

COPY同token导航使ConsultationProductPage达到224行，copy-entry-structure.log证明超过220硬限。FIX-CON-PROMPT-COPY-NAV-STRUCTURE-01仅把现有已保存选择读取表单搬入现SavedSelection.tsx的有限SavedSelectionAccess纯组件，传递原值、禁用条件及原回调；权限/原键/状态仍在原父组件，无新API或行为。root负责，出口为原全页回归和结构检查通过，立即回COPY UI；不得压缩代码掩盖硬限。

### 8.29 确切版本变量预览完整入口窄D（2026-10-14，root审定）

PC-CON-PROMPT-PREVIEW-01沿CON-10 R、已确认内部五权及仅内存不入模决定，依赖已完成领域内核、真实授权、READ和COPY入口。差额是用户无法对获准确切草稿进行变量预览；CREATE/SAVE/COPY已闭合，现应补此最后已确认草稿动作。复用TemplateRenderer、确切Store/独立READY、受管文件、现JWT/TLS与READ页面，不新增持久化/模型/发布。出口是实际双H2/身份/受管原件的应用及生产HTTP、真实浏览器合成输入的UI组件，成功/诊断零业务写入；实际人员、Grant和确切TEST来源许可在启用前核。本批前检为COPY完整后检05:56:18自然exit1，47/41一致，2244/2242过/2原skip、30/242/179全过，只有原TESTS_FAILING/TEST_EVIDENCE_STALE，所有后继已执行；原件copy-post-verify-result.json。Astra High及后端/UI只读审查已完成，以下为Codex派生工程合同，不冒原始需求逐字业务规定。

**请求与有限值。** POST `/v2/consultation/prompt-workspaces/{workspaceId}/prompts/{promptId}/revisions/{revisionId}/preview`，无query/原键/持久副作用；仅JSON UTF8，1MiB严格输入，重复/未知字段拒绝。仅`contentSha,sourceId,values`，values最多64个唯一`{name,type,value}`，name匹配`[A-Za-z][A-Za-z0-9_]{0,127}`。不允许自报模板、schema、sourceRef/evidence、模式或身份。INTEGER/DECIMAL必须JSON字符串，完整受限词法分别转BigInteger/BigDecimal；允许有界十进制指数，不经JS Number/double；字符串词法最多1024字符、精度最多1024、scale在-4096至4096（显式比较防abs溢出），默认数值也核。STRING/BOOLEAN/STRING_LIST保持本类型，单字符串64KiB UTF8、列表最多256项且仍服从总请求限额；合法Unicode。null或未提供按原内核缺值/default规则，空值不冒缺项。type须与真实schema相同；schema本身最多64变量及同名限额，超过操作资源预算泛化413。

**独立TEST原件。** ProductPromptPreviewPermission使用独立默认off的preview-enabled及preview-test-permissions-ref/sha256，目录格式`PROMPT_VARIABLE_TEST_BINDINGS/v1`，严格format/bindings，每项sourceId/permissionRef/permissionSha，1至128项，ID及ref/SHA对不重复。原件格式`PROMPT_VARIABLE_TEST_PERMISSION/v1`：permissionId/source/decisionVersion/responsibleParty/decisionDate/status/validFrom/validUntil；issuer/subject/workspaceId/organization/scopeVersion/registrySha/policyRef/policySha；promptId/revisionId/revisionNumber/recordSha/contentSha/schemaSha；operation=PREVIEW、mode=TEST、inputMode=MANUAL_TEST_INPUT、outputCoverage=REQUEST_MEMORY_ONLY；variables每项name/sourceRef/evidenceId/source/decisionVersion/responsibleParty/decisionDate/status/validFrom/validUntil/purpose=TEST_SAMPLE。所有字段严格齐全、重复未知/错型/非法日期/无期限/错SHA关闭，逻辑permissionId/evidenceId限定不含路径的`[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`。

schemaSha用固定`PROMPT_PREVIEW_SCHEMA/v1`编码有序完整变量定义及default/empty规则，数值默认转无损字符串；不改变现content或prepared字节。逐变量名称集合和sourceRef与确切schema严格相同；零变量也需要确切TEST原件。服务器由真实定义sourceRef及原件evidenceId构造Value，客户端sourceId只选已受管许可，不能授权自己。逻辑permissionId作默认值证据，TEST许可原件SHA是响应policySha；工作区保存policySha仅用于内部绑定。许可覆盖的是经授权的人工TEST输入范围，无需固定候选表；工程生成原件不能自获业务效力。

Witness私有构造，固定同Lease/action PREVIEW、workspace/组织/scope/registry、issuer/subject、exact revision/record/content/schema，并在policy/evidence取用时核Lease绑定和现时证据。保留Lease.testAdmitted()恒false，不扩大身份接口。每次首末重读并认证目录/原件及逐来源ACTIVE/有效期，旧CREATE/COPY许可不得替代；到期或漂移503。

**应用与时限。** 在execute(PREVIEW)回调内先READ+PREVIEW当前双权，registry现时，head加锁核workspace，选版标量存在/PRESENT/有效及command SHA后才readRevision；保持当前head与确切Header、独立READY和闭包，允许有效旧版，不暗换新版。TEST Witness认证后render(Mode.TEST)。不用嵌套ReadApplication，不向外暴露可伪造TEST boolean或Policy端口。成功与允许公开的变量诊断均在同Lease内完成有限DTO、有界序列化，再重核同head/header/READY/closure、TEST原件、registry及Lease；不重读正文。

捕获仅MISSING_VARIABLE/UNDEFINED_VARIABLE/VARIABLE_TYPE_MISMATCH/VARIABLE_EMPTY_NOT_ALLOWED为有限诊断。INVALID_TEMPLATE/VARIABLE_POLICY_UNAVAILABLE等持久模板或政策故障为503。变量诊断不得逃逸到controller后再带schema名返回；失权/到期/漂移覆盖原诊断。返回accessUntil=min(header.bodyUntil,header.auditUntil,lease.cutoff)、policyUntil=min(TEST及全部变量规则有效截止)，expiresAt为二者最小值。事务beforeCommit仅以固定两截止与Clock复核，覆盖lambda后授权复核跨期限窗口，不能在失活Lease上调用方法；访问到期403，TEST政策到期503。controller发完整byte[]之前再按固定截止核，成功和含schema诊断一视同仁，不续期。

**响应和放大防护。** 200仅status=OK/mode=TEST/workspaceId/promptId/revisionId/revisionNumber（字符串）/contentSha/schemaSha/policySha/expiresAt/segments；LITERAL仅kind/text，DATA仅kind/name/type/sourceEvidenceId/value，数值value为字符串。默认值证据为获准逻辑permissionId，无受管路径。400诊断仅code=PROMPT_PREVIEW_INVALID_VALUES/expiresAt/issues[{code,variable}]，不含segments、模板片段或样例值；其他语法400只有泛化码。401身份、403授权/对象/正文期限、413预算、415媒体、503不可认证状态；全部no-store，无正文/诊断日志或事件/回执写入。

TemplateRenderer新增仅用于本操作的有界render重载，旧render及validateSyntax合同不变；tokenize每次增加token前检查最多1024segments。输出DTO共享不可变值，不提前逐重复占位符展开为JSON字符串，不递归渲染样例。JSON向同时限制单字节/块写的1MiB OutputStream写入，写前核剩余量，超限丢弃整个结果且不向HTTP发送部分字节；无需重复一套JSON成本算法。最终200和400均已完整序列化后才交controller，输出限额泛化413。

**装配/UI/验证。** preview默认off，显式prompt.enabled及identity.enabled，准确独立两DS/主manager、专用有界Jdbc和私有decoder；仅原READ五主/三独立表探针，不依赖V008～V010请求表或旧写许可。现安全chain覆盖路径；不新增迁移、模型或共享身份写。READ现DTO足够，直接在有效revision区挂PromptPreviewWorkspace，以token/workspace/prompt/revision/SHA为key；独立TEST sourceId输入，按schema提供有无值选择，数值始终字符串、列表明确JSON数组输入。Literal/Data作为文本与类型化数据块显示，不注HTML。编辑清结果、busy/epoch/Abort隔离迟到；换版/重读/退出/401403/截止清样例及结果，401403回父清READ正文历史，401沿既有session失效；失败显式重试，无自动POST或浏览器缓存。诊断亦按服务端expiresAt关闭。

必要反例：双权缺一拒绝；同SHA错revision/主体/workspace、错schema/default/source、目录或政策首末漂移/撤回/恰到；旧有效版成功、后继PENDING关闭；整数超过JS安全范围/高精度小数无损；缺值/default/null/空值/未知/错型无部分segments；样例占位符不递归；重复大值/JSON转义/default列表超限；成功与诊断跨最终截止均失败关闭；前后业务/身份表无写入且无模型；生产装配开关/错DS/无旧许可/HTTP严格输入；UI编辑/切版/失权/到期/迟到及1280/390浏览器。root唯一运行全部专项/构建及连续批次完整后检；许可、应用HTTP、UI分文件并行，root独占Command/Values/Output、内核有限重载及计划。不得改固定人工4ecb包、安装SQL或解除暂停。

### 8.30 首次工作区登记与原键对账应用窄D（2026-10-14，root审定）

PC-CON-PROMPT-REGISTER-APPLICATION-01关联CON-10，依赖PREVIEW及五权认证组件出口，实际编码等PREVIEW完整后检自然终态。当前差额是五动作虽有源码/C，真实初次workspace登记仍只有存储原语；启用不能依测试建行或手工绕过。复用ProductPromptWorkspaceEvidence.current、ProductPromptStore.insertWorkspace、ProductPromptAuthorityStore.insertWorkspacePending/completeWorkspace、现exact DataSourceTransactionManager/受管文件/规范JSON，不新建池、通用维护框架或另一套五权。结束观察为实际mTLS维护身份与双H2下新UUID一次登记、主真实commit后READY及同原键只对账；标准维护HTTP接线随后继续。源码/C不等于DM8安装、实际登记、部署或B。

**有限保存的派生分类。** §8.5/8.7已批准项目草稿必要元数据、每事件180日的最小非正文追溯/原键回执；§8.9/8.15.2已规定确切workspace登记及其操作者/原键证据。本次只将该登记的一条请求事件与主结果回执归入上述必要元数据，这是Codex技术分类，不冒用户原话批准所有维护审计。禁止证书DER/私钥、正文/schema/default、JWT、通用id2_maintenance_audit扩用或永久安装墓碑。证书SHA仅是公开身份绑定摘要，不保存凭据。首次独立reserve时的preparedAt固定一次；receiptUntil=min(preparedAt+180日、原动作批准receiptUntil/validUntil、workspaceValidUntil及业务批准/政策更短界)。重试/重启/READY不得重新起算；主结果沿原请求期限，不另起180日。到期先读截止标量即关闭，不以缺回执重建；有限清理writer及7日备份真实准入仍是启用前置，本步不执行物理清理。

**确切登记批准。** 新ProductPromptRegisterApproval仅以部署固定的workspaceId→原件ref/SHA读取受管文件，绑定表最多128项，拒重复UUID或多义ref/SHA；不接受客户端组织、政策、DB坐标、操作者或批准JSON。严格原件PROMPT_WORKSPACE_REGISTER_APPROVAL/v1：format、decisionId/source/decisionVersion/responsibleParty/decisionDate、status/validFrom/validUntil、operation=PROMPT_WORKSPACE_REGISTER、operatorSubject/operatorOrganization/certificateSha/maintainerVersion、primaryDatabaseIdentitySha/authorityDatabaseIdentitySha、workspaceId/organization/scopeVersion/policyRef/policySha/registrySha/workspaceValidUntil/receiptUntil、traceCoverage=MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY。原件严格字段/类型、无重复/尾随/强转、规范UUID/SHA/毫秒UTC，非空有限来源责任字段；ACTIVE且当前有效。目标Workspace必须ACTIVE且当前有效；operatorOrganization=workspace组织；registrySha=既有业务Binding.approvalSha。构造目标纯值调用现WorkspaceEvidence.current(target,now)，核两份既有业务原件与90/180/7，不新增REGISTER五权枚举。文件一致性不自授权；实际workspace/维护主体/具体操作授权来源及部署DB身份在启用前核，工程负责生成格式材料，不让用户重写已确认保存决定。

原件认证产生私有构造Witness，持有固定target、原件pin、业务Witness、预期维护主体/version/证书SHA、两库identitySHA及更短截止；requireCurrent复读同原件/政策并逐值一致。维护lease认证真实证书与真实id2_maintainer后再次匹配此Witness；不可由调用方传入“已批准”布尔值。DB身份SHA以PROMPT_REGISTER_DATABASE/v1及真实metadata URL/schema/user的规范对象求SHA，原文仅请求内认证，不输出或持久保存连接参数。两DataSource及实际身份不得相同。

**专用维护lease和有限失败。** ProductPromptRegistrationLease使用原exact已启动HikariDataSource，入口拒任何调用方actual/synchronization/绑定资源；Semaphore(1).tryAcquire零排队。authority至少2/main至少1连接，allowPoolSuspension=false；connectionTimeout>0且<=30000ms、validationTimeout有限且不大于connectionTimeout，禁止运行中改变共享池。每次借用前核剩余预算足以覆盖connectionTimeout+validationTimeout及清理裕量。独立M连接来自同authority池，不绑定Spring；READ_COMMITTED/nonautocommit，真实id2_maintainer singleton=1 FOR UPDATE NOWAIT。核HTTPS证书attribute、当前有效期/SHA、ACTIVE主体/组织/GRANTS/version及确切动作Witness；普通require(GRANTS)或HUMAN_RETENTION均不能替代。M只持维护身份锁，不写业务或通用审计；普通五权不取M，短authority事务不回取M。

技术总预算120秒用单调时间约束，同时取证书/动作/工作区/政策实际截止；SQL最多5秒并向下取剩余整秒，不足1秒不开始；networkTimeout最多5000ms并取剩余预算，事务timeout取剩余且最多30秒。预算不延业务TTL，不用Future.cancel冒数据库取消。操作顺序为短authority reserve实际commit→主实际commit→短authority complete实际commit，各阶段均使用现exact Spring管理器；主阶段不得用authority JdbcTemplate隐式借reader，当前维护身份仅由M读取，主阶段所需独立快照在短authority事务中取得并认证。主提交后独立结算可在主观察事务自然退出后执行，因为M串行所有REGISTER及后续CLOSE，普通五权不改workspace或登记回执；不跨主commit持idp_workspace写锁，不引挂起资源桥。可能出现有限池等待，必须上界失败退出并保原PENDING，不能声称无等待环或自动重试。

M的lease绑定线程/连接/活动期，首末重核身份/Witness/固定截止，跨主实际commit持续持锁。应用主及短authority回调中设置有限networkTimeout和beforeCommit固定截止守卫；Spring在回调前的事务准备、设置恢复及驱动commit/rollback等I/O仍须固定DM8驱动有限socket/取消实证，源码H2不能替代。finally先使lease失效，再一次有界rollback；成功后才恢复isolation/readOnly/networkTimeout/autoCommit并close。禁止先autoCommit=true隐式提交；rollback/恢复/close异常用原Hikari.evictConnection淘汰确切连接，不能正常归还污染连接；淘汰不证明服务端锁已释放，释放未知则本实例后续REGISTER关闭，原失败阶段保留且不泄漏驱动连接详情。有限Executor仅用于JDBC networkTimeout，无新连接池/后台调度。默认无Bean/无HTTP，本步骤不能启用；后继装配还须现容量、NOWAIT及所有上述I/O有限退出的实际证据。

**固定原键与最小持久增量。** 新db/identity-authority/V011__product_prompt_workspace_register.sql和db/dm8/V114__product_prompt_workspace_register.sql只写源码，不改V007/V112或安装。专用RegisterRequest/Receipt不扩CREATE/SAVE/COPY枚举与表：authority idp_workspace_register_request保存requestId/receiptId、workspaceId UNIQUE、目标8标量、固定REGISTER、真实subject/organization/version/证书SHA、两DB identitySHA、动作批准ref/SHA、原key/request/workspace/prepared/result SHA、preparedAt/intentUntil/receiptUntil及PENDING/READY；主conp_workspace_register_receipt保存同request/receipt ID、workspace UNIQUE/FK、动作/主体/组织/version/证书SHA、key/request/workspace/result SHA、preparedAt/receiptUntil。无正文或可逆副本。重复workspace任何键不能第二次安装；原键唯一坐标(workspaceId,subject,certificateSha,REGISTER,keySha)。原key仅规范ASCII 1～64字符，持久保存SHA，不保存原字节。

摘要使用现规范JSON：workspaceSha域PROMPT_WORKSPACE_REGISTER_TARGET/v1+目标8标量；requestSha域PROMPT_WORKSPACE_REGISTER_COMMAND/v1+目标8标量/真实身份及version/证书SHA/两DB SHA/动作批准SHA/批准receiptUntil，排除Clock和新ID；keySha=原ASCII字节SHA。首次服务器分配requestId/receiptId各UUID，preparedAt一次毫秒UTC，intentUntil=min(preparedAt+5分钟、原动作/工作区/政策及receipt截止)，不绑本次120秒工程预算而允许另一次同键有限对账。resultSha域PROMPT_WORKSPACE_REGISTER_RESULT/v1+ID/原键坐标/requestSha/workspaceSha/preparedAt/receiptUntil；preparedSha域PROMPT_WORKSPACE_REGISTER_PREPARED/v1+全部不可变目标/身份/依据/ID/摘要/时间，排除state。恢复重算全部摘要，跨JVM确定顺序，不遍历无序Set。审批原件SHA变更、主体/version/证书轮换不能静默取得原键权。

**事务与三态。** register/lookup只收HttpServletRequest、workspaceId及原key。当前动作批准+mTLS lease核准后，首次先在主只读事务按workspace/organization核ACTIVE组织及workspace/receipt均不存在，在独立短事务核request/idp_workspace均不存在并原子插两PENDING；随后主新事务按同序复核组织/两主行缺席，insertWorkspace与原receipt同事务，首末核lease/原件/期限并真实commit。若主提交抛错即未知，保已提交独立PENDING，不换key或自动重发。后续主事务核workspace与原receipt全部ID/组织/标量/摘要/期限，拒缺一/重复/漂移/CLOSED；观察全后成立且仍当前lease/原intent有效后，独立短事务锁同request/idp_workspace，CAS workspace READY和request READY同事务。复用completeWorkspace的原intent_until>now，不扩过期结算。

同键POST或lookup首先按receiptUntil标量门禁后读完整原请求；任何已存在但过期行不是可创建的“缺失”。同key异requestSha冲突；异key同workspace冲突。PENDING+主workspace/receipt均无=ALL_BEFORE，503并保PENDING，禁止调用insertWorkspace或重建原prepared正文；PENDING+主两行全量匹配=EXACT_AFTER，只补原READY；READY仍核独立/主ACTIVE一致及原回执，不重复插入、不续期；其余UNKNOWN均关闭。缺独立请求却有任何两库workspace/主receipt、READY缺主、CLOSED或原intent过期不重建/复活。不从现五权Grant推登记权，不读取prompt正文、不签发Grant。完成后复核当前lease/原件和两库确切结果；末检/关闭失败不抹去主已提交事实。失败响应不得冒全部回滚，记录及返回仅有限标量，HTTP映射后继另审。

**分工和退出反例。** root独占本ADR/主计划/迁移编号/共享验证；lease及批准、有限记录/存储、应用编排三路分文件实现，字段及API先对齐，不运行代理测试。真实Hikari+双H2、真实mTLS证书/维护行与受管原件验证成功一次登记、当前身份/批准/更短期限恰到、调用方事务/错池/同池/悬挂池拒绝、确切原key和跨JVM摘要、PENDING全前拒重建/全后只结算、主孤立行/缺回执/READY旧主/CLOSED、各主与独立commit故障无盲重放、竞争NOWAIT/池耗尽有限退出、lease跨真实主commit锁/撤回阻塞后生效、清理失败淘汰且本实例关闭。故障Connection代理只注入外部故障，不替代生产协调器。实际DM8容量/取消、人员/具体REGISTER批准/政策与所有运行材料在启用前核；有限清理、维护HTTP及部署未完成保持原门禁。首轮原失败留存，root统一有意义组件及规定后检；出口立即回父维护入口，不因内部组件通过停止父交付。

#### 8.30.1 实施前独立反查收紧与统一接口（2026-10-14，root）

PREVIEW完整后检06:46:57自然终态作为REGISTER本批前检。Astra/后端只读反查提出的下列P1在编码前收口：reserve、main、complete各自beforeCommit必须执行当前lease.requireCurrent、动作Witness.requireCurrent及固定截止守卫，不限只查时钟，特别禁止READY先提交后才发现动作批准已撤回。EXACT_AFTER只能由Reconciliation拥有的主观察TransactionTemplate正常返回后私构；回调返回、异常或主commit未知均不得生成证明。证明绑定Store实例、同M lease对象/线程、完整原Request/preparedSHA、固定截止及一次消费；主资源必须已解绑，complete仅在exact authority事务中消费。禁止接受调用方boolean、普通Workspace或公开record冒提交证据。

Evidence新增approvedReceiptUntil独立标量，用于重算requestSha；实际receiptUntil继续取各更短界，两者不得混用。原intentUntil只约束reserve/主新写及PENDING→READY，READY同原键查回按固定receiptUntil、当前维护批准和工作区有效期，不因原五分钟窗口过期而拒绝。READY也走本次fresh ExactAfter后的complete纯读分支：逐项认证request与idp_workspace ACTIVE/READY及全部target，无UPDATE、不核旧intent、不续期；不另造requireReady旁路。lookup找不到原request始终拒绝，不调用prepare/reserve。

统一包内接口：RegisterApproval.admit(workspaceId)产生私构Witness，提供target、evidence、approvedReceiptUntil、expiresAt、requireCurrent；expiresAt仅业务动作/工作区/政策/批准receipt截止，不含证书或120秒预算。RegisterRecords.Evidence保存operatorSubject/operatorOrganization/maintainerVersion/certificateSha/两DB identitySHA/approvalRef/approvalSha/approvedReceiptUntil；Key保存workspaceId/subject/certificateSha/keySha，operation恒REGISTER。Request包含target/Evidence/Receipt/intentUntil/preparedSha/state，Receipt包含requestId/receiptId/Key/operatorOrganization/maintainerVersion/requestSha/workspaceSha/resultSha/preparedAt/receiptUntil；摘要包含两个ID，policyRef限现表200字符、subject限200、新approvalRef限500，不依SQL截断校验。

RegistrationLease构造接两个原exact已启动HikariDataSource、各精确DataSourceTransactionManager及Clock；withLease(request,Witness,Function)内提供main(cutoff,Supplier)/authority(cutoff,Supplier)，均独占实际事务/网络/预算及beforeCommit守卫，禁止嵌套/外部资源，READ_COMMITTED，返回才是实际commit结束。cutoff只缩短lease截止；证书与单调预算单独认证，不写入业务保存期限。Store构造复用现主Store/AuthorityStore、mapper、clock；key/requestSha/prepare从当前Lease固定生成；find仅在authority中先期限再全行；requireMainAbsent/insert仅main；reserve/complete仅authority，全部写方法显式接当前Lease与Request并匹配私构Witness。Reconciliation.observe(Lease,Request)拥有lease.main观察，正常退出后才返回私构Observation和ExactAfter。reserve/main/complete不隐式借额外authority reader，不新增事务桥。root批准认证；copy_entry_review维护lease/事务及专测；copy_http Records/Store/Reconciliation/V011/V114及专测；copy_ui薄应用及空workspace真实Hikari双H2/真实DER组件夹具；root统一验证。真实DER置于容器请求attribute仅证凭据组件，不能冒实际TLS握手；实际握手留标准维护入口。

### 8.31 REGISTER标准维护HTTP与显式装配窄D（2026-10-14，root审定）

本节仍属ARC-CON-PROMPT-OPERATION-D-01；拟实施项PC-CON-PROMPT-REGISTER-HTTP-01的硬依赖为PC-CON-PROMPT-REGISTER-APPLICATION-01退出，不在其组件与规定后检完成前编码。当前差额是登记应用没有可调用的正式维护入口；现在需将首次工作区登记接到真实HTTPS维护身份。复用§8.30应用、现identityMaintenance安全链及ProductIdentityTransportFilter、原两Hikari池、受管原件与有限JdbcTemplate。出口是生产Bean和真实HTTPS双H2下POST登记及GET原键对账、无证书/错误证书/失权/漂移/到期与请求边界反例；不扩CLOSE、清理或普通用户工作台登记权限。

**唯一输入和路由。** POST `/v2/identity/prompt-workspaces/{workspaceId}/register` 调用原application.register；GET `/v2/identity/prompt-workspaces/{workspaceId}/requests/REGISTER` 调用原application.lookup。两个端点显式核实际method仅为各自POST/GET，尤其拒绝Spring隐式匹配的HEAD（405且不进入应用或写库）；只收规范UUID及单个`Idempotency-Key`（ASCII可见非空1～64字节），拒任意query和正文，包括空JSON；不接收批准、组织、主体、DB坐标、回执期限或布尔权限。复用现CREATE坐标检查，但不调用JWT token/json检查。正文只读一个字节判空，不解析无界表单或JSON；不把GET缺失改成prepare。成功统一200，最小输出`requestId/receiptId/workspaceId/state=READY/resultSha/preparedAt/receiptUntil`；state只是已验证的登记控制结果，不是业务或正式状态。成功及Controller错误均no-store，不回显原键/证书/原件/连接信息。

**权限和拒绝。** 现Order4 identityMaintenance覆盖`/v2/identity/**`，保留permitAll到应用进行专用Lease认证；不得将permitAll当无权限，也不改现Order3提示词JWT链。现全局identity transport先核HTTPS、原始路径及当前Grant水位，之后应用仍核mTLS叶证书、真实维护行和确切REGISTER批准；普通JWT、浏览器PKCE、GRANTS角色本身或通用维护Proof均不构成登记权。不接受客户端header声称的证书/主体。坐标/key/query/body错误400；无有效维护证书/维护权限或未受管workspace403；确切RegisterConflict为409；原件失效/更短期限、缺行/漂移/全前/UNKNOWN、池借用/提交/清理失败503。公开错误仅固定code，不暴露cause或声称全部回滚；服务端仍保留原异常阶段与cause。继承全局transport自身403/503，不为统一错误体改共享过滤器。

**装配和固定pin。** 新`product.consultation.prompt.register-enabled`默认关闭；开启要求既有`product.identity.enabled`及`product.consultation.prompt.enabled`为true。复用`evidence-root`、`bindings-ref`及`bindings-sha256`；新增`register-approval-pins-json`只作为部署固定的数组配置，格式`[{"workspaceId":"<uuid>","approvalRef":"register.json","approvalSha":"<sha256>"}]`，最多65536 UTF-8字节、1～128项，拒重复/未知字段、非文本、重复UUID/ref/SHA、尾随内容；每项仍构造§8.30 Pin，原件逐请求认证。配置不是用户接口，不自动生成或批准REGISTER原件。

主DataSource必须是现JdbcTemplate与现DataSourceTransactionManager及existingWatermark.mainDataSource共同指向的同一个已启动exact HikariDataSource；authority必须是existingWatermark.independentDatabase的另一已启动exact Hikari池。新authority manager只绑定该原池；不新建池、不改共享池配置、不借用CREATE WriteComponents或追加普通业务权限。仅创建专用有限JdbcTemplate、现Store/AuthorityStore、Approval、Lease、RegisterStore、Reconciliation及Application；Lease作为有close销毁回调的Bean持有。提交结果STATUS_UNKNOWN或连接清理未知关闭该实例；数据对账UNKNOWN/ALL_BEFORE仅拒本请求，不能扩大成新增全实例停机。前述提交或清理故障关闭实例后，后续请求仍503；恢复依经准入重启后的新实例持原键显式GET/POST，禁止内部自动重启、清PENDING或新建workspace。

任何schema探测或Store构造之前，先核开关、原DataSource/manager身份，再构造Lease以验证exact已启动Hikari、禁止pool suspension、容量及有限borrow配置；不得先借连接后补校验，使挂起池绕过有限退出。随后仅以有界SELECT WHERE 1=0核REGISTER所用组织/主workspace/主receipt、maintainer/独立workspace/request的必要列，读取受管binding/pin配置并校验构造不写库；不因配置启用执行DDL、登记、迁移、授权、备份或删除。构造Lease前后的异常须关闭已创建的专用Executor，不能关闭或污染原两池。未开关时无Controller/应用/Lease Bean；开启但身份、原池、配置或必要schema缺项须启动失败关闭。

**验证与运行边界。** 组件覆盖默认关闭、缺开关/错manager/同池/非Hikari/未启动池/容量/缺列/严格pin与无写启动；正式Controller+原安全链+transport+真实应用+空workspace双H2验证允许路径及上述错误，GET从不新登记，READY无新写/不续期，POST提交未知后保原事实。至少一个实际嵌入式HTTPS服务使用工程自有CA/服务端/维护端证书完成握手与POST/GET，并证明无客户端证书及同CA签发但不匹配的另一有效clientAuth证书不能登记，后者须握手成功后被当前维护身份绑定拒绝；真实ProductGrantWatermark使用已迁移的工程空历史与READY行，不mock transport或水位。MockMvc请求attribute仅补输入反例，不能代替握手。工程证书、组织和原件只为C夹具，不冒实际人员授权。root唯一共享测试/完整后检owner；原失败及输入SHA留存。真实启用仍需V011/V114及必要schema安装、确切workspace/人员/REGISTER原件、两库metadata pin、现池容量/驱动NOWAIT/socket/prepare/reset/commit/rollback有限退出、有限清理与7日备份准入。原部署/物理操作暂停及人工固定候选边界全部继承。
### 8.32 SAVE旧容器截止修正窄D（2026-10-14，root审定）

`FIX-CON-PROMPT-CONTAINER-EXPIRY-01`是父步骤有限清理前的必要修复，关联CON-10，依赖REGISTER维护入口及SAVE应用已有出口。依据为§8.5“仅随仍有效修订存在；最后修订到期关闭该对象”和§8.11；不新增保存期限或权限。§8.23允许旧正文到期后提交完整新稿，前提仍是目标容器有有效修订，不能把尚有效的180日追溯当作恢复已关闭容器的依据。源码反查确认SAVE仅核controlUntil而未核旧containerUntil；原单版到期仍可SAVE的测试实际证明了此缺口，不是合法新产品合同。

只在fresh SAVE及PENDING+ALL_BEFORE的实际新写分支，锁定原head并核准确CAS后，要求now严格早于该原head.containerUntil。原截止在本次主事务内固定，不能取prepared.after中已经被新稿抬高的containerUntil；不更改原revision/receipt/request的期限、摘要或prepared字节。独立reserve执行截止取原containerUntil、原intentUntil及本次许可body/audit更短值，并沿现AuthorityTransactions提交围栏检查。主事务注册固定同截止beforeCommit守卫；由真实主commit前检查捕获回调末核后跨点，失败主回滚、已独立提交的PENDING保留，禁止续期或自动换键。复用COPY现有私有beforeCommit模式，不新增通用事务框架。

EXACT_AFTER及READY只认证原已提交事实、返回原有效追溯回执，不能加旧container/body门禁；到期后的标量对账继续沿现授权及audit/control截止。某旧版body到期而同对象仍有有效修订时，允许有准确当前CAS和新输入许可的完整SAVE，且不读旧JSON、不延旧版期限。无任何有效修订时不能借完整新稿复活同prompt；用户以独立CREATE创建新对象仍走其原合同。

root唯一共享验证与计划owner；先保留旧实现的有意义失败反例，再定点修源码。覆盖fresh/ALL_BEFORE在旧container恰到及过后拒绝、authority reserve beforeCommit跨旧截止真实回滚、主beforeCommit跨旧截止真实回滚且保原PENDING、旧版过期但后继仍有效的成功及原期限不变，并复验原EXACT_AFTER/READY到期标量查回及SAVE/CREATE/COPY受影响组件。无新API/迁移/部署/实际删除；前检沿08:11:26 REGISTER-HTTP完整后检，修后规定后检自然收口再返回父正文清理D。该修复不冒BODY_CLEAR、TRACE_PURGE或完整生命周期已实现。

### 8.33 正文清理的事务及兼容边界（2026-10-14，派生技术设计）

父步骤仍为ARC-CON-PROMPT-OPERATION-D-01，业务依据为§8.5/8.7及docs/02 Q-CON-PROMPT-WORKSPACE-01；已确认五权/90-180-7不重问。§8.32修后146项及08:57:37完整后检已自然收口。以下明确后继BODY_CLEAR的最短完整边界，当前只准入§8.34维护批准/锁接线；清理存储、writer及消费者兼容须补确切字段/方法/迁移和测试D后另登记，不凭本段写生产清理代码。

只清确切已到body截止且原audit仍有效的单修订：revision_json置原生SQL NULL（覆盖正文/schema/default），主Header及独立witness均改CLEARED。原content/record/result SHA等有限标量、业务generation/head、container/control期限、原prepared/业务request/event/receipt全部保持原值。READ/历史/预览/新COPY仍只接受有效PRESENT正文；清后原有限回执和SAVE创建来源依确切清理证明认证，不读或重建旧正文。A阶段只覆盖所有原追溯尚有效区间；任一原audit退出后的存续集合、非连续历史、来源退出及全部表清除另过TRACE_PURGE D。

候选独立cleanup_request和主cleanup_receipt保存固定有限转换；加入后生命周期范围由现11表变为13表。新表不纳入PROMPT_SCALAR_CLOSURE_V1业务摘要，以免afterSHA自引用；使用独立版本化严格非正文manifest及preparedSHA，禁止PromptRevision/schema/default/开放Object扩展。索引/期限/key/目标/before-afterSHA列须逐项核等manifest；数据库负责确切原键/request/receipt/单revision转换唯一约束。先查期限与长度再读manifest、严格字段/数值/时间/enum/摘要、读后再核期限；state及完成时刻不改原manifest。实际主NULL/Header/独立witness观察不能由manifest替代。

候选control新增cleanup_request_id及CLEANUP_PENDING，后者业务intent字段全部NULL。所有普通READY/PENDING解析、认证及CAS要求cleanup_request_id为NULL，防止误入业务恢复。流程在真实M锁内顺序执行：主prepare退出；authority按原READY head/generation/SHA/期限CAS reserve；主重核全前态后精确clear/更新唯一closure/写receipt并真实提交；新主观察自然COMMITTED后生成绑当前lease/线程/请求的一次性私有ExactAfter；authority原子更新witness CLEARED、control新SHA/READY并清cleanup ID、request READY。复用顺序事务，不复用绑定挂起主holder的业务ExactAfter。较早已获有效源快照的在途COPY可在主清理前提交，不声称reserve瞬间取消跨对象在途读。

reserve和真正主clear（含ALL_BEFORE续写）均以原intent/原audit/当前确切批准/lease更短固定截止注册真实beforeCommit围栏。未知提交立即停止本调用并保原PENDING，禁止换键、清gate或续期。显式同键POST只在准确ALL_BEFORE且原mutation截止有效时续原prepared；GET不续正文写。EXACT_AFTER可以在原intent到期后，仅以原audit及当前同pin维护批准/lease补结算，不再清正文；READY只认证原回执。mixed/分叉/单边/过期均关闭。firstAttempt在独立首次reserve固定，不因失败/重试重新起算；部署前仍须接24小时首次尝试及7日备份流程，当前不宣称已有调度或运行清理。

历史清理证明必须覆盖原target.auditUntil，固定receiptUntil=原target.auditUntil且不超过firstAttempt+180日；确切批准必须覆盖该保留窗口，不足则拒清理准入。当前动作批准validUntil只限新清理和同键维护恢复，不把历史完成证明寿命缩短到操作窗口，也不要求原维护者以后仍ACTIVE才认可合法历史。普通回执认证独立当前witness、固定原清理request/receipt与历史批准摘要；新的维护操作仍核当前同pin批准。所有证据也有限退出，无永久墓碑。

同代原键需独立原afterSHA至当前control SHA完整无分叉链及主标量逆投影核原afterSHA，支持同代其它修订被清及连续多次清理；每节核相同workspace/prompt/generation/head/期限、原Header及主独立全等，只在内存将CLEARED投影为历史PRESENT，绝不回写或当作正文存在。后继READY代次沿已有规则并认证确切旧目标清理证明，不跨代重演全部历史。Origin保原event/receipt与当前witness全标量认证并核确切CLEARED证明；不能裸放宽PRESENT谓词或借Origin复活旧容器。

### 8.34 BODY_CLEAR专用批准与真实维护锁窄D（2026-10-14，root审定）

任务PC-CON-PROMPT-BODY-CLEAR-ADMISSION-01关联CON-10，硬依赖FIX-CON-PROMPT-CONTAINER-EXPIRY-01及REGISTER维护入口退出。缺口=现Maintenance Lease仅接受REGISTER Witness/GRANTS，无法认证确切正文清理权；必要=任何清理writer须先取得真实维护身份与专用批准。复用RegistrationLease/Transactions、现两exact Hikari/manager、ProductSourceEvidenceFiles及WorkspaceEvidence；出口=专用批准、真实TLS证书输入/两H2 M锁及期限/漂移/错分支反例C，随后立即回父清理存储/writer详细D。前检沿08:57:37完整后检，不增业务权/期限，不准入SQL安装/实际清理/部署。

新包私有ProductPromptBodyClearApproval：Pin(workspaceId,promptId,revisionId,approvalRef,approvalSha)，确切三个规范UUID；1～128项且拒重复坐标/ref/SHA、路径逃逸。admit(workspaceId,promptId,revisionId)仅匹配服务端固定pin，未绑定拒绝。Witness私有构造，提供workspace()、target()、evidence()、expiresAt()和requireCurrent()。Target只含promptId/revisionId/revisionNumber/recordSha/bodyUntil/auditUntil；Evidence为专用类型，含operatorSubject/operatorOrganization/certificateSha/maintainerVersion/primaryDatabaseIdentitySha/authorityDatabaseIdentitySha/approvalRef/approvalSha/approvedReceiptUntil，不接受REGISTER Evidence冒充授权。

批准原件严格JSON/duplicate detection/无尾随/确切字段：format=PROMPT_BODY_CLEAR_APPROVAL/v1、operation=BODY_CLEAR、traceCoverage=MINIMAL_BODY_CLEAR_SCALARS_AND_ORIGINAL_KEY；沿REGISTER的来源/版本/责任/日期/status ACTIVE/validFrom-validUntil、维护六坐标、workspace组织/scopeVersion/policyRef-SHA/registrySHA/workspaceValidUntil/receiptUntil，增加promptId/revisionId/revisionNumber/recordSha/bodyUntil/auditUntil。只允许文本/确切正long/规范毫秒UTC；错目标、错组织、同DB SHA、错误原SHA/政策/registry、未知/重复/缺字段均拒。原件没有正文/凭据/调用者自造组织。WorkspaceEvidence认证原工作区批准与政策；scope/policy/组织及批准一致，workspace和业务approval/policy有效期限覆盖原target.auditUntil。bodyUntil<=now<auditUntil且bodyUntil<auditUntil；receiptUntil>=原auditUntil是覆盖许可，未来实际清理追溯不延原audit。expiresAt=min(当前清理批准validUntil,workspaceValidUntil,原业务approval.validUntil,policy.validUntil,target.auditUntil)，不能把已经到期的bodyUntil加进截止。requireCurrent重新认证原字节/登记/政策和相同Admission，恰到即拒，不按当前时间重新准备期限。

RegistrationLease仅新增封闭withLease(request,BodyClearApproval.Witness,Function<Lease,T>)重载，内部共享方法只收两个确切Witness并要求严格XOR；Lease保持两个私有typed字段。approval()只允许REGISTER，bodyClearApproval()只允许BODY_CLEAR，错分支/失效/跨线程拒绝。两分支共同核真实Servlet HTTPS叶证书、有效证书/精确指纹、id2_maintainer FOR UPDATE NOWAIT及完整固定快照、ACTIVE/version>0、准确subject/org/cert/version和两metadata SHA。REGISTER仍要求GRANTS；BODY_CLEAR由确切专用批准授权，不把GRANTS/人工retention或任何其它角色当清理权，也不创建新身份角色。禁止Object/predicate/任意授权Supplier或调用方伪造Witness；Transactions、连接池和身份发行不改。

共用现120秒monotonic总预算、5秒network/query/cleanup、main至少1及authority至少2容量、M跨顺序真实commit、无ambient和精确phase、beforeCommit当前复核、UNKNOWN/清理失败poison及异常cause/suppressed诊断。组件反例覆盖body恰到准入/audit恰到拒绝、覆盖不足、批准/政策原件漂移、未绑定/重pin错误动作/严格JSON、无GRANTS但确切BODY_CLEAR批准成功且REGISTER仍拒、互斥accessor、错维护主体/组织/证书/version/status/两DB、锁竞争/跨线程/ambient、批准截止跨真实主beforeCommit回滚、M持锁跨实际commit阻撤回。工程自有证书及原件只证明C，不冒真实人员或实际删除授权。root唯一测试/计划owner；代理按文件实施后共同冻结，跑受影响REGISTER及专用C，再规定完整后检。无新Bean/HTTP/数据库写入口，实际BODY_CLEAR批准/人员/目标/原件和DM8驱动事实仅在启用前核。

### 8.35 BODY_CLEAR有限记录、存储及历史兼容详细D（2026-10-14，root技术审定）

任务PC-CON-PROMPT-BODY-CLEAR-STORAGE-01，需求CON-10，父步骤ARC-CON-PROMPT-OPERATION-D-01。硬依赖PC-CON-PROMPT-BODY-CLEAR-ADMISSION-01完成规定后检；当前只审设计、登记后继，依赖退出前不写该任务代码。差额=已具真实维护准入却无确切NULL转换、双库恢复和清后原业务回执认证；必要=90/180/7日已批合同要求正文到期关闭且保有限追溯。复用§8.33/8.34、ProductPromptStore的现标量算法、RegistrationTransactions及业务原键恢复。出口=下面实际存储/历史兼容及故障C，不包含应用编排、HTTP/Bean、运行DDL、物理删除、调度、备份或TRACE_PURGE；源码中的清理原语不授实际清理许可。已批组织/五权/期限不重问，启用的具体主体/原件/目标和操作许可仍独立核实。

**有限材料。** 新ProductPromptBodyClearRecords使用私有固定类型Key、Request、Receipt及Manifest；不得增加业务Operation枚举或复用业务Pending。Key固定workspaceId/promptId/revisionId/subject/certificateSha/operation=BODY_CLEAR/keySha，原key沿REGISTER可见ASCII 1～64字符后hash。Manifest仅含原Workspace8标量、typed批准Evidence、原Head8标量、目标原PRESENT Header9标量、原业务Receipt14标量、独立cleanup request/receipt UUID、原键与request/result/prepared摘要、afterSha、firstAttempt、intentUntil、receiptUntil及closureScopeUntil。不含正文/schema/default/PromptRevision或开放Object。after Head由before仅替scalarSha，after Header由原Header仅替bodyState为CLEARED；其余全等。closureScopeUntil由准备时全部revision/event/receipt原audit、Head.controlUntil及Workspace.validUntil取最小值并在每次主闭包认证重核，不延原期限。

命令摘要固定版本PROMPT_BODY_CLEAR_COMMAND/v1，绑定确切批准、工作区、目标及原业务坐标；结果摘要PROMPT_BODY_CLEAR_RESULT/v1绑定确切转换、两个UUID、原键、before/afterSHA及固定期限；prepared摘要PROMPT_BODY_CLEAR_PREPARED/v1覆盖完整固定Manifest，排除自己的preparedSha、可变state及完成时刻。禁止用普通record序列化替代现PROMPT_SCALAR_CLOSURE_V1。receiptUntil固定原目标auditUntil，须<=firstAttempt+180日且获准覆盖；intentUntil=min(firstAttempt+300秒、receiptUntil、closureScopeUntil、准备时当前批准expiresAt)。首次真正reserve的准备时刻随首次独立PENDING提交固定，失败恢复不重置；只读扫描不计首次尝试。24小时首次尝试与备份的运行接入后置，不以此存储实现声称已满足调度。

**物理delta。** 向前新增db/dm8/V115__product_prompt_body_clear.sql及db/identity-authority/V012__product_prompt_body_clear.sql，不改已发行V112/V007。主新增conp_prompt_cleanup_receipt，独立新增idp_prompt_cleanup_request，两者固定列request_id/receipt_id、operation、workspace_id/prompt_id/revision_id、subject_id/certificate_sha/key_sha/request_sha、generation/head_revision_id、before_sha/after_sha/result_sha/prepared_sha、first_attempt/intent_until/receipt_until/closure_scope_until、manifest_json；独立表另state=PENDING/READY，主表只记录已提交转换。请求、回执UUID分别唯一；完整Key唯一；目标revision唯一；(prompt_id,generation,before_sha)和对应after_sha分别唯一防同代分叉。加准确现workspace/prompt/revision/control外键及操作、有效期限、正代次、摘要长度、state约束。manifest存CLOB，读取先查固定receiptUntil及LENGTH<=16384，再严格字段/重复键/无尾随、UTF8<=65536字节、有界字符串/确切整数/规范毫秒UTC、跨字段合同和摘要；所有查询/CAS索引列逐项核等Manifest，不只核摘要。原生SQL NULL与字符串null不得互代。两新增表不进入业务闭包摘要，生命周期范围由11表变13表。

control新增cleanup_request_id，扩现CHECK：READY和普通PENDING必须cleanup ID原生NULL；CLEANUP_PENDING须generation>0、现head非NULL、cleanup ID非NULL，全部业务intent/operation/key/request/before/expectedAfter/targetGeneration/targetRevision/intentUntil原生NULL，原generation/head/scalarSHA/container/control期限不变。ProductPromptRecords.Control及AuthorityStore.control精确读取marker。所有业务reserve/complete/requireReady、CreateRequest.matchesPending/requireReady、Reconciliation、Origin控制判断及实际CAS均拒非NULL marker，不能只用state防混合行，也不得以BODY_CLEAR扩业务Operation。历史fixture仅安装新schema供C，无新产品入口/开发身份。

**存储及证明。** ProductPromptBodyClearStore沿现exact StatementJdbcTemplate，分别requireMainTransaction/requireAuthorityTransaction。prepare(Lease,Key)在真实main顺序事务锁workspace、head、目标，核当前工作区/原闭包、PRESENT且原生非NULL、原event/receipt及目标批准；预测仅目标bodyState转换的afterSHA，捕获上述固定材料。find(Lease,Key)只在authority phase，先期限/长度再读固定材料，已有其它key的同目标转换冲突，缺request却有cleanup gate/witness后态保持关闭。reserve(Lease,Request)独立事务核完整READY control、目标原witness全标量、确切same-pin批准及原键/目标唯一性，插PENDING request并CAS原control为CLEANUP_PENDING；任一步失败整事务回滚。reserve与clear真实beforeCommit均使用min(原intentUntil、receiptUntil、closureScopeUntil、当前批准/lease截止)。不依赖M锁阻止普通用户写；prepare与reserve间普通SAVE竞争由完整control CAS排除。

clear(Lease,Request)只在main phase，锁内重核完整ALL_BEFORE、原Head/目标Header/业务event/receipt/原生正文存在及cleanup request对应的保留材料；条件UPDATE revision_json=NULL/body_state=CLEARED，CAS head.scalarSha=固定afterSHA，插唯一主cleanup receipt，再核完整EXACT_AFTER及闭包。仅上述三处写，不改业务generation/head、container/control期限、任何业务request/event/receipt/prepared、content/record/result SHA。中途失败同事务回滚；真实commit UNKNOWN立即结束调用并保原PENDING，不在同调用继续authority结算，不造替代key或清gate。此前获合法快照的在途COPY可先自然提交；main清理仍按现workspace锁等它结束。

ProductPromptBodyClearReconciliation.observe拥有一轮lease.main自然结束的观察，只返回ALL_BEFORE/EXACT_AFTER/UNKNOWN。回调内不能造proof；main自然返回且真实STATUS_COMMITTED、无phase.failure、无ambient/资源、当前same-pin批准和截止复核通过后，EXACT_AFTER才私构一次性ExactAfter。proof绑定reconciliation/store实例、同Lease对象、线程、完整原Request和固定观察截止；只能在准确authority phase消费，不接受反射外的公开工厂、任意Supplier或业务挂起holder证明。观察及EXACT_AFTER/READY结算截止=min(receiptUntil、closureScopeUntil、当前批准/lease截止)，不含旧intentUntil；旧intent只限真正正文mutation。ALL_BEFORE只能由以后显式POST在原mutation期限内续原prepared，GET不续正文写。complete(Lease,Request,ExactAfter)独立事务重核原request/manifest/精确CLEANUP_PENDING/witness，原子将witness.body_state改CLEARED、control.scalarSha改afterSHA及READY并清cleanup ID、request READY。READY路径仅认证既有确切证明/回执，不再clear、改witness或续期限。authority事务失败或UNKNOWN后只允许后续显式原键重新观察，不复用已消费proof。

任一主clear或主观察的lease.main抛出异常（包括实际提交后当前批准/截止复核、网络恢复失败），本调用立即结束，保原独立PENDING和已知主事实；不只处理STATUS_UNKNOWN，也不在catch/finally造proof或自动再观察。complete实际authority事务使用固定settlementCutoff=min(receiptUntil、closureScopeUntil、当前批准/lease截止)，consume及真实beforeCommit均复核。仅PENDING到READY要求CLEANUP_PENDING；READY路径认证已有request/receipt及当前READY head/witness，合法后继代不得被旧head全等要求误拒，沿下面历史认证合同核原目标及已认证当前上下文。

**历史业务兼容。** 当前授权与历史证明分离：历史requireHistoricalProof不要求旧维护者现在ACTIVE或旧动作窗口仍有效，但须在原receiptUntil内核两库固定Manifest/preparedSHA、原批准摘要及所有目标/业务标量、主原生NULL/CLEARED Header与当前CLEARED witness、准确已认证Head/control上下文；禁止未来代次证明。新的维护动作仍核当前确切批准与真实维护Lease。历史proof的原保留寿命不被较短动作expiresAt收短，不复制正文或延原audit。

CreateRequestStore.find中的authority-only requireReadyHistory只读独立request/control/witness，先核固定Manifest/preparedSHA及同代原afterSHA到当前control.scalarSHA的唯一完整READY清理链，保原head/generation/期限全等；链逐节同workspace/prompt/kind/generation/head/期限，逐目标核当前CLEARED witness全非状态标量，无缺口/额外链接/分叉/环/重复revision。主阶段requireHistoricalProof再核主cleanup receipt与上述独立材料全等、实际Header/native NULL及当前closure；禁止authority phase调用主库读取或把authority先验当双库完成证明。主Reconciliation READY分支先核当前完整closure，再按相反顺序逐节仅在内存把该链目标Header CLEARED投为PRESENT，严格复用Store现排序、scalars字符串规范和PROMPT_SCALAR_CLOSURE_V1；每步当前SHA=节afterSHA、逆投影SHA=节beforeSHA，最终=原业务request.afterSHA。只逆该业务写之后的同代清理，保留更早generation已CLEARED目标；原业务PENDING分支、prepared构造的PRESENT和摘要完全不变。后继代READY中，原目标仍PRESENT且原生正文非NULL时沿原Header/witness/event/receipt认证，不要求清理记录；仅原目标CLEARED时追加确切历史清理证明，不跨代重演全部旧链。

同代无清理记录时，零节链仅在原afterSHA=当前control.scalarSHA且原head/期限全等时合法，不因不存在cleanup记录拒绝原有READY回执。PRESENT和CLEARED原回执认证均不新增bodyUntil当前门禁，仍按原audit及当前业务权限认证。

实施前独立反查补充（09:44:32规定后检已收口，作为本批前检）：所有FK只指本库对象；主receipt指conp_workspace/conp_prompt/conp_revision，独立request指idp_workspace/idp_prompt_control/idp_revision_witness，control.cleanup_request_id只指本库idp_prompt_cleanup_request。独立先插request再CAS既有control，同事务，不设跨DataSource FK。prepare/reserve在任何持久写前核生成manifest字符<=16384、UTF8<=65536；读取先单独取得receiptUntil及LENGTH，已存在但过期/NULL/超长不可伪作不存在。

RegistrationTransactions的exact phase坚持一个Spring资源。维护prepare/clear/observe只通过main.jdbc读取主标量及原生NULL状态；文件批准及Lease原生M连接身份复核照常。authority workspace/control/witness/request只在此前自然提交的独立find/reserve及随后complete事务认证；主phase不调用业务History混合reader方法、requireNoManagedTransaction或authority JdbcTemplate/DataSourceUtils。历史业务消费者仍沿原业务Transactions，不为复用而放宽维护单资源守卫。

共用接口冻结为封闭ProductPromptScalarSnapshot，由Store.scalarSnapshot(Head expected)在准确主事务、当前Head及closure全等后取得。保唯一现排序/scalars字符串/PROMPT_SCALAR_CLOSURE_V1算法，深不可变、私有构造，只暴露sha()、closureScopeUntil(Workspace)、clear(原PRESENT Header)、restorePresent(原PRESENT Header)；清理预测及历史逐节逆投影只改该精确目标bodyState，不开放Map、任意row mutation或回调。原业务append预测复用同算法，摘要不变。该机械接口不授正文读取、清理或复活权，正文状态原生NULL观察及当前批准仍由准确调用路径认证。

维护READY旧key允许后续同代清理及合法后继业务代次。主观察回调改为私有MainObservation(State、实际ObservedHead、原主cleanup Receipt固定Manifest、同代后续已提交主转换候选Manifest列表、currentClosureScopeUntil)，不是裸State；主表无state，不能把候选提前称独立READY。只有主观察自然COMMITTED后私构proof并绑定这份不可变材料；authority READY完成准确比较当前Control与实际ObservedHead全坐标，逐项核候选独立READY request/witness。后继代只认证原目标和当前上下文，不重演跨代历史。相间普通SAVE推进或reserve为PENDING，准确比较失败，后续显式原key重观，不追随新head修改proof。

同代未新增集合时当前scope等于原manifest固定scope；合法后继SAVE可能缩短audit，后继代只取更短当前scope，不强制相等，也不续原期。实际观察和settlementCutoff=min(原receiptUntil、原closureScopeUntil、currentClosureScopeUntil、当前批准/lease截止)。正式main观察前以独立main只读预读scope自然结束，再按该固定更短budget正式观察并核scope未漂移；自然返回后再核该截止才造proof，authority真实事务及beforeCommit同样使用它。不在回调后才收紧已执行JDBC预算，不改变原Manifest或历史证据保留寿命。

Origin继续核原event/receipt/witness全部现比对：PRESENT沿原路；CLEARED追加上述准确历史证明。外层先沿Origin.control认证READY或现有严格own SAVE PENDING，再对该before.scalarSHA/原first Header及Receipt核证明；历史helper不能无条件要求READY而误拒合法ALL_BEFORE续写，也不把ownPending白名单扩给普通原键find/settle。当前control为CLEANUP_PENDING、混合marker、旧备份主PRESENT对独立CLEARED、单边记录、坏manifest、过期或不等均关闭。READ/历史/预览/新COPY继续只读有效PRESENT正文；已清正文不能恢复、重新读取或借SAVE复活到期容器。

**组件出口。** 同源冻结后root统一跑记录严格解析/边界/摘要跨独立JVM、两H2真实转换/单代两次链和更早代次CLEARED保留、主和authority逐写回滚/commit前后UNKNOWN、真实M锁/当前批准和closure最早audit跨beforeCommit、intent到期后的EXACT_AFTER结算/READY查回、GET ALL_BEFORE零mutation、原键冲突/旧备份/分叉/单边/witness混合/原生NULL、CREATE/SAVE/COPY旧回执及Origin精确ownPending、READ/历史/preview权限与到期和REGISTER回归。XML/日志/退出码及稳定输入留存，先有意义反例及受影响回归再规定完整后检。C不替DM8驱动/DDL安装、部署、真实流程或业务验收；本D只准入本行文件与必要现消费者兼容，不准入未审应用/维护HTTP/TRACE_PURGE或实际物理删除。

### 8.36 BODY_CLEAR正式应用编排窄D（2026-10-14，root技术审定）

PC-CON-PROMPT-BODY-CLEAR-APPLICATION-01归属CON-10和父ARC-CON-PROMPT-OPERATION-D-01，硬依赖§8.35存储项完成规定后检；依赖未退出前只准备本设计，不实施。交付差额=独立存储原语尚无一次完整维护调用及显式原键恢复；必要=后续标准维护入口不能靠调用者手排事务。复用现专用Approval、RegistrationLease、BodyClear Store/Reconciliation及现实际Hikari/H2故障夹具。出口是本应用的真实组件调用及失败恢复，不含HTTP/Bean、运行DDL、TRACE_PURGE、调度、备份、部署或实际清理授权。已批业务范围/期限无新增决定缺口。

包内ProductPromptBodyClearApplication构造仅接受ProductPromptBodyClearApproval、ProductPromptRegistrationLease、ProductPromptBodyClearStore、ProductPromptBodyClearReconciliation。clear(HttpServletRequest request,String workspaceId,String promptId,String revisionId,String rawKey)及lookup同签名均返回包内ProductPromptBodyClearRecords.Receipt；不接受调用者Manifest、prepared、批准、摘要或自造主体。两方法均先approval.admit确切三坐标，再BODY_CLEAR withLease及store.key。初find和fresh prepare分别使用min(lease.expiresAt、witness.expiresAt、target.auditUntil)固定预算；find在authority自然事务，prepare在main自然事务。find返回旧材料后requireApproved再核原receipt/scope及same-pin，覆盖读取提交期间跨期；不依最新head重新计算原requestSHA而误拒合法后继代。

新clear：find不存在→main prepare→authority reserve→main clear→reconciliation.observe→authority complete。reserve及实际clear一律按store.mutationUntil的原intent/receipt/scope/lease/当前批准最小截止，不续期。已有记录首先observe：PENDING ALL_BEFORE只允许新的显式clear调用在原mutation截止内main.clear原Request，然后自然返回后重新observe；不prepare/reserve、不换UUID/firstAttempt/preparedSHA。lookup不存在、ALL_BEFORE或UNKNOWN均关闭，且不prepare/reserve/clear。EXACT_AFTER及READY均通过本次自然主观察的proof，在lease.authority(proof.cutoff(),complete)内认证/结算；GET允许既有确切主提交的独立库结算，不清正文。READY不得仅凭旧独立行返回，也不带旧intent或固定旧head预算；复用实际ObservedHead及当前更短scope。所有正常成功仅来自complete自然返回的原有限回执。

任何main/authority/observe异常立即结束本次调用，包括实际提交后截止复核、UNKNOWN、网络恢复/清理失败。无catch/finally自动重观、结算、清gate、造回执或替代key；后续只有新的显式原键调用查明事实。proof只能本次准确phase消费，失败后不复用。应用不重复事务框架或写存储标量。新增封闭ProductPromptBodyClearConflict，仅用于该清理分支已知同目标不同Key及原READY control竞争CAS，定点替换原通用conflict；坏/单边材料、批准漂移、UNKNOWN、期限及不可认证状态仍沿原失败原因关闭，禁止按异常消息文本分类。该类型只为后续HTTP明确409准备，不改变普通业务冲突或原状态判据。

后续HTTP另审有界DTO，禁止直接序列化本包Receipt/Manifest；候选输出只含requestId、receiptId、workspaceId、promptId、revisionId、READY、resultSha、firstAttempt、receiptUntil，不暴露批准原件、证书或两DB身份。本节不准入Controller/配置或维护UI。

root唯一共享编译/验证及本设计审定者。应用专测复用ProductPromptBodyClearStoreFixture和现TLS/故障源，只增加真实Approval/application构造所需入口。覆盖fresh全链、重复/重建相同回执；reserve后main回滚的GET零持久写与POST原prepared续写、intent恰到拒写；主已提交后旧intent过期GET结算；reserve/main/complete提交前后UNKNOWN与主提交后复核失败均本调用不自动再观；后续原键恢复PENDING/READY；两同代清理/后继SAVE的旧键及相间推进拒绝；同目标换key/错pin/单边/坏备份关闭；未知key GET/错坐标/非法key在首次写前拒绝；原receipt/scope/批准跨实际beforeCommit关闭。低层原子性、严格Codec和历史链反例沿当前有效§8.35证据，不复制测试。冻结后受影响C与规定后检收口，立即回父标准维护HTTP详细D。

§8.36实施前独立反查补充：find取得既有Request后须在同准确authority事务内认证真实idp_workspace与原Workspace完整一致，再认证control/witness及分类不同Key。仅受管文件/current M批准不代实际独立workspace；尤其PENDING ALL_BEFORE重试不能在独立workspace已关闭或漂移时先commit正文清除、到complete才拒绝。两项应用反例分别用原key及不同key核坏独立workspace在任何清理SQL前关闭，保持PRESENT/原Manifest/主清理回执零，不冒typed409。

### 8.37 BODY_CLEAR标准维护HTTP、私有装配及真实TLS窄D（2026-10-14，root技术审定）

PC-CON-PROMPT-BODY-CLEAR-HTTP-01归属CON-10及父ARC-CON-PROMPT-OPERATION-D-01，硬依赖§8.36应用项完成规定后检，依赖退出前仅准备设计。差额=完整应用无标准维护入口与真实HTTP身份接线；必要=准确操作者须能调用并显式核原结果，不能手排存储事务。复用RegisterController输入/错误模式、现identityMaintenance/TransportFilter/Watermark、两exact Hikari及现HTTP/TLS夹具。出口是生产Configuration/Controller及真实双H2、JSSE/Tomcat组件；不含运行DDL、实际清理许可、发布、调度、备份、TRACE_PURGE或维护UI，不新增五权及90/180/7日业务决定。

**路由和输入。** base=`/v2/identity/prompt-workspaces/{workspaceId}/prompts/{promptId}/revisions/{revisionId}`；POST `/body-clear`调用application.clear，GET `/requests/BODY_CLEAR`调用lookup，均传request和三坐标/rawKey。处理器首先核实际method为POST/GET；Spring隐式HEAD映射须405且不进入应用、M Lease、查询或结算，其他动词不进入应用。三个UUID严格规范小写形式；复用ProductPromptCreateController.coordinates验证workspace/query/key，再补另两坐标。query原生非NULL即400，空query亦拒；Idempotency-Key恰一header、可见ASCII字符33～126、长度1～64，不trim。inputStream首字节非EOF即400，不解析JSON/form，缺Content-Type可合法。全部校验先于应用；不接收Manifest、批准、摘要、主体、组织、state或期限。Controller不重试、不异常后lookup；GET沿§8.36只允许确切已提交结果的独立结算，不清正文。

**有限输出及故障。** 成功200、JSON、no-store，显式九字段ReceiptView：requestId、receiptId、workspaceId、promptId、revisionId、state固定READY、resultSha、firstAttempt、receiptUntil。只从application正常返回的有限Receipt投影，保原持久时间，不序列化Receipt/Manifest，不输出原键、正文、subject、证书/DB身份、批准ref/SHA或prepared/before/afterSHA。typed BodyClearConflict→409/PROMPT_BODY_CLEAR_CONFLICT；AccessDenied→403/PROMPT_NOT_AVAILABLE；输入拒绝→400/INVALID_PROMPT_REQUEST；HEAD→405/PROMPT_METHOD_NOT_ALLOWED。IllegalStateException/IllegalArgumentException/DataAccessException/IOException等不可认证、期限、坏材料、UNKNOWN及工程故障→503/PROMPT_UNAVAILABLE，服务端LOG.warn固定阶段文字并传原Throwable，保cause/suppressed；不得按消息文本分类或把失败称全部回滚。Controller成功/错误均no-store；全局TransportFilter及TLS握手拒绝保持现边界，不改共享过滤器。

**安全及生命周期。** 现identityMaintenance的`/v2/identity/**`继续经TransportFilter核HTTPS、路径和Watermark；permitAll仅把专用认证交应用，不能用普通JWT、GRANTS角色或伪证书header代替BODY_CLEAR批准。TLS身份只取容器X509Certificate attribute，维护ACTIVE/version/subject/org/cert/两DB及真实M行锁照常。新增`product.consultation.prompt.body-clear-enabled`默认false，true时要求现prompt.enabled/identity.enabled及准确池/manager/Watermark。新BodyClearConfiguration factory私有构造专属RegistrationLease，不注册第二个同类型Bean、不改REGISTER开关/Lease Bean。应用支持close，仅关闭所注入专属Lease executor；工厂失败亦只清自身executor，不关共享池。两个owner的closed/poison状态分离，共享M NOWAIT锁仍串行维护操作。exact已启动两Hikari、准确manager/Watermark身份及容量准入先于metadata/schema；沿原120秒总预算和5秒phase/锁/网络守卫，不新建池或改池配置。

**固定pins及schema。** 复用evidence-root/bindings-ref/bindings-sha256；新增`body-clear-approval-pins-json`严格数组1～128，字符及UTF8均<=65536；每项恰workspaceId/promptId/revisionId/approvalRef/approvalSha五文本字段，无重复字段/未知字段/尾随值。(workspaceId,promptId,revisionId)元组唯一，ref和SHA各自唯一，允许同workspace/prompt不同revision。配置仅固定准确Pin，不自动生成批准或启动admit/清理。复用ReadConfiguration的完整6主/4独立列probe，再加id2_maintainer七列，范围主workspace/head/revision/event/receipt/cleanup receipt及独立maintainer/workspace/control（含cleanup marker）/witness/cleanup request，准确全列WHERE1=0只读，不安装或修表/写行。

**分工和验证。** root唯一ADR/计划/共享target/证据owner；Controller/HttpTest与现HTTP构造入口、Configuration/配置专测、真实TlsTest及必要时间夹具三路精确分文件实施。复用现RegisterHttpFixture实际WebContext/Node及RegisterTlsTest JSSE，不stub应用/Store/proof。现BodyClearStoreFixture推进工程Clock不能直接用于systemUTC生产配置；仅新增一致initialInstant构造入口，在任何原件/批准/提交生成前固定过去提交时刻和未来audit。原构造继续原语义；HTTP fixture以实际UTC准入，不sleep等待或替换生产Clock。互有依赖的共享fixture由一个代理持有并先冻结，不并行修改同一文件。

覆盖POST/GET/repeat恰九字段、固定时间及无泄露；三UUID/重复header/ASCII/query/body400先于应用SQL；PENDING ALL_BEFORE GET零正文mutation、POST原prepared恢复、主commit后UNKNOWN本次不结算及后来旧intent GET恢复；HEAD两个路径在主已提交独立PENDING下405且Lease/查询/mutation零；普通JWT无cert、缺/伪/同CA其他证书403，准确BODY_CLEAR无GRANTS正例；实际Tomcat TLS完整生产接线；503固定公开体及原cause/suppressed留存；关闭零依赖、单开无需REGISTER、双开无Bean歧义、错pin/池/缺表列拒绝、构造失败仅executor清理、REGISTER poison不污染BODY_CLEAR owner及原REGISTER回归。低层原子性和历史链不复制；root同源冻结后统一受影响C与规定后检，不以组件证据冒DM8/部署/真实人员清理或业务验收。
