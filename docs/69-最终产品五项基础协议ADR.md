# 最终产品五项基础协议 ADR

版本：设计基线 V1，2026-09-27。性质：开发团队技术/产品设计决定，依据项目负责人在docs/02第38节的委托；不是原需求原文、外部数据许可、已过D的证明或验收记录。任务及状态只在docs/08维护。适用首条咨询任务，公共机制可经各域独立D复用。

## 1. 身份与 DataScope

来源：CON-12共同任务约束、docs/01第5～8节、docs/55第2、4、6节。认证入口仅接受部署时注册且可验证签名、issuer/audience/期限/nonce的用户凭证，或双向认证且在服务注册表中有效的工作负载身份；请求头自报用户、组织、角色或API Key不产生用户身份。适配器输出`SubjectRef(issuer,stableId,kind)`和认证版本，原始凭证不入任务记录。用户类别为任务所有者、具名受理人、被授权协作者、数据/权限维护者；类别是职责模板而非全域通行证。工作负载分Agent、检索/文件JOB、备份、恢复和维护身份，不能充任人工受理/复核人。实际用户和岗位由可信身份源及组织授权登记，未完成该接入时受保护路由默认拒绝。

授权判定的完整键是`subject + domain + action + resource(type,id,version) + purpose + organization/assignment + grantVersion + sourceUseVersion`；仅角色不授任何具体任务或来源权限。建立任务须有当前组织/创建动作grant，创建事务把当前可信主体绑定owner及组织；任务级协作/领取/撤销由受控授权命令生成不可变事件及单调授权版本，不从历史任务创建事件推断现时权力。默认拒绝空、未知、跨域/机构/任务、过期/撤销、用途不匹配和授权服务不可用；存在性敏感资源对无范围主体不暴露正文/对象是否存在，已认证且可见对象的动作不足返回403，失效/未知来源返回明确受限错误及traceId。数据范围是受控集合交集而非客户端自由字符串；列表在召回前过滤，详情、原文、结果引用和下载逐对象重验。

Agent只能携带短时、绑定任务/动作/用途/输入修订的委托，执行权取服务身份本身允许动作、委托人现时grant及来源许可三者交集；委托不能转授、扩大范围或执行人工决定。JOB仅可在显式任务租约和资源引用下处理，不可扫描全部租户；备份身份仅读备份所需快照，恢复身份不得直接开放产品路由。写入锁内按统一顺序读取现时授权和来源epoch并在提交前复核；返回受限内容、流式分片、文件下载和模型调用前再验。撤权事件更新独立现时高水位，缓存只作有界提示，不能用历史grant或回灌库自证当前有效。身份/授权端口不可用时，不降级到测试token、匿名或旧缓存。

技术选型：用户登录由部署中受信的OIDC发行方完成，服务验证固定配置的issuer/audience、签名算法与JWKS、有效期/生效时刻及唯一subject；不从未验证JWT、角色或组织claim直接生成DataScope。受保护调用还必须由发行方现时撤销/会话有效性接口或等价签名状态水位确认，无法取得现时身份则拒绝；不承诺仅靠短期JWT解决即时撤权。服务/JOB经双向TLS工作负载注册，证书主体绑定服务类型及允许目的；前端不能提交服务委托。凭据、签发私钥与维护口令不入DB业务表或浏览器。认证失败401；已认证但无精确grant/用途403；可信状态不可达503；现时版本冲突409；无对象可见权时不泄露对象存在。错误体沿docs/55第4节，敏感字段按当前范围裁剪。

DM8本域保存不可变授权变更事件和现时精确grant投影，至少包含issuer/subject、domain/organization、resourceType/id/version、action/purpose、有效期限、status/epoch、维护事件引用及审计时刻；任务归属另存且不替代grant。授权维护端用与普通业务服务分离的mTLS身份，按`expectedEpoch`和DB时间执行授予/撤销/缩期，事件与投影同事务；不能由Agent、服务JOB、任务owner字段或业务Controller自行增权。初始维护身份由部署可信注册流程提供，未注册则不提供授予入口。在线业务按共享现时授权→来源状态→领域任务行的固定锁序核验；备份外责任源须保存完整授权、来源和删除水位及未决意图，恢复/启动先对账再放流量。具体OIDC发行方、维护者证书、DB账号、备份外介质与双实例配置是部署事实，不从本ADR自动产生；缺任何必需事实时产品服务保持关闭。

D反例：跨组织同ID、owner失权、受理人撤权、服务只有读权限却持用户写委托、同键回放后的撤权、列表页授权而详情前撤权、两实例交错撤权/发布、旧备份恢复后重放grant、不可用身份源，均须无越权正文/写入。项目自有发行的授权决定见docs/02第39节；具体准入与验证划分见第1.3节。真实外部组织授权和生产恢复仍不可代签。

### 1.1 独立纯策略切片D反查

`ARC-CON-PRODUCT-AUTH-POLICY-D-01`仅冻结不依赖任何HTTP/DB/凭据解析的最终产品授权判定函数。输入由可信端口提供，全部不可空：`Actor(issuer,id,kind,organization)`、`Resource(domain,type,id,organization,version)`、`Action`、`Purpose`、`now`、`CurrentState(authorizationEpoch,sourceEpoch,available)`、显式任务`Grant(actor,resource,action,purpose,validUntil,epoch)`、可选`Delegation(user,service,resource,action,purpose,validUntil,epoch)`及服务自身grant；访问来源内容时另有`Source(id,version)`及`SourceUse(source,purpose,validUntil,epoch)`。任何空/无效/过期、组织或精确资源不匹配、grant/source epoch落后、服务无自身grant、委托缺失或范围扩大都拒绝；用户不以角色或任务owner字段自动放行。主体`kind`只有HUMAN/SERVICE，服务不得申请HUMAN_REVIEW/ACCEPT/GRANT/RESTORE等人/维护动作；未知action与purpose不解析为默认许可。grant/委托/sourceUse是否真实及当前epoch的原子性由未实现的可信适配器负责，函数绝不签发它们。只在内存返回ALLOW/DENY，不持久化、不发布API、不宣称跨实例撤权I。`CurrentState`必须按本次主体/资源与来源范围从同一现时可信水位解析；不能传全局常量或客户端版本。代码未接端口，不能将这一限定视为已验证。

本切片的正反测试使用明确输入值验证算法而不作为产品数据或身份替身；接口不包含testIdentity、fixture模式或开发开关。正例为同组织现时具名H精确任务READ/DISPLAY，以及服务在自身grant与H短期委托、当前来源用途交集内QUERY。反例为跨组织/任务、用途不符、过期边界、旧epoch、源撤回、服务越人事动作、无服务grant/委托、授权状态不可用。原需求的默认拒绝和Agent不可授权边界及docs/02第38节已充分确定这一**无业务API**算法；实际issuer、维护grant、来源权利及路由D仍属`ARC-CON-PRODUCT-IDENTITY-D-01`，不得借本D放行。

实施反查：`ProductAccessPolicy`只计算输入事实，不持有凭据、缓存、持久化或产品Controller；`ProductAccessPolicyTest`四项覆盖同组织确切资源/用途、跨组织/任务、来源和授权epoch变化、服务自身grant与用户委托交集、越权人事动作、过期和空事实。2026-09-27定向4/4、后端全量`mvn -q test`及`node scripts/verify-all.mjs`通过，只有组件C，没有实际身份/来源I或业务B。`RESTORE`在本任务策略内不向Agent委托，专用恢复身份按第5节独立维护通道设计，不能从任务服务身份获得恢复权。

### 1.2 完整身份D证据核查（2026-09-27）

以下是`ARC-CON-PRODUCT-IDENTITY-D-01`的当前取证结果，不是另一个状态台账。设计合同已由第1节固定；若将来换发行或维护机制，须重新核其撤权时序和拒绝反例。

| 所需证据 | 仓库及运行环境实查 | D所需最小可核输入 |
|---|---|---|
| 身份发行 | `application.yml`只有旧API Key和`consultation-v2.development`；V2 filter仅`test-synthetic`装配；当前两个容器均为DM8，无OIDC发行/JWKS/现时会话服务 | 实际受信issuer、audience、JWKS/验证算法和现时撤权接口/等价水位，来源版本、配置责任人及正反签发记录 |
| 授权配置 | `ProductAccessPolicy`为纯函数；`docs/66`的`v2dev_*`仅D前临时schema探针，无产品grant表、受控维护身份或真实任务授权 | 组织/主体/任务/动作/用途的现时授权来源、授予者和撤销者、维护证书及DB最小权限、初始授予与并发CAS记录 |
| 撤权配置 | 旧开发凭据/来源撤回仅进程内，DB候选epoch无备份外现时权威；未见双实例或恢复后水位配置 | 不随业务备份回退的可信状态提供方及完整键/水位、撤回通知和提交/读/返回前竞争两序记录 |
| 正向授权 | 旧具名开发主体仅限合成任务测试；四项纯策略测试的主体和值是算法输入 | 实际签发主体对确切任务/动作/用途的grant、当前DataScope和成功请求/DB审计关联，敏感令牌不入仓库 |
| 拒绝样例 | 旧测试和纯策略C有拒绝反例，但不证明产品issuer及现时grant拒绝 | 同一实际身份体系下无凭据、跨组织/任务、用途错、撤权先于读/提交、身份服务故障的零正文零写入记录 |
| DataScope | `DataScope(reference)`与开发fixture不证明组织成员关系；客户端组织字段不可采信 | 可信成员归属及至少两个隔离组织/资源的精确grant、可见/不可见列表和详情记录及版本来源 |

设计反例输入如`issuer-A/H1/org-A/task-T1/READ/TASK_WORK`允许、`H1/org-B/task-T1`拒绝，只定义预期，不表示这些主体、机构或grant已经签发。未取得上述实际证据前，完整D不通过，不能以构造token、自己填组织字段或临时同库grant作替代；`IMP-CON-PRODUCT-IDENTITY-01`及正式路由继续禁码。若实际授权由项目部署自行管理，仍须先由具名维护责任方完成可信引导、逐资源授权与备份外水位配置，并留其真实记录。

### 1.3 项目自有Issuer的最终产品D合同

docs/02第39节改由本项目建设可信发行方；第1.2节描述的是该决定之前的缺证快照，不再要求外部Issuer材料。采用Spring Authorization Server承担标准OIDC发现、授权码+PKCE、令牌/JWKS/撤销端点；不开放密码授权模式、匿名注册、自动创建测试账号或静态个人token。人由具名安全维护者登记、设置初始凭据并完成一次性激活后才能登录；服务客户端独立登记并用双向TLS认证，不能用人的授权码冒充。账号只有稳定subject和认证属性，组织/角色claim均不直接授DataScope。无安全维护者可信引导、证书或DB配置时正式发行和产品路由不启用。维护者本人不能通过业务请求给自己授任务权限；首次维护身份由部署独立密钥/证书登记并审计，丢失时走隔离恢复而不是后门。

Issuer URI和audience是部署必填且固定的HTTPS标识，不使用localhost或请求Host反推；签名使用RSA-3072/RS256和独立随机`kid`，私钥只以部署外主密钥加密后存储，明文不落DB/日志/仓库。JWKS仅返回非撤销的当前与尚需验证历史token的公钥；轮换先写新签名密钥再原子切活动`kid`，旧公钥至少覆盖尚有效令牌和时钟容差，但旧私钥立即停止签发；密钥泄露执行紧急吊销，相关token和会话停止。token只含签发与主体识别、aud/iss/iat/nbf/exp/jti/sid，至多含不作为权限依据的显示字段；不能携带永久grant或资料正文。每次受保护请求验证标准JWT、当前会话/jti及主体状态、grant/来源当前epoch；令牌过期、非法签名、未知kid、撤权、身份/状态服务不可用均失败关闭。令牌和会话期限为版本化安全配置，未配置不得启动，不在文档臆定数值。

DM8正式身份域至少持不可变主体登记/停用事件、客户端登记、加密签名密钥版本、当前会话/jti状态、grant事件及现时投影。Grant字段固定为`subject, organization, taskScope, dataScope, resource(type,id,version), permissions(action,purpose), issuedAt, expiresAt, status, grantVersion, issuer, maintenanceEvent`，所有范围和权限必须由服务端可信登记，不采信客户端值；一次授予/缩期/撤销按`expectedGrantVersion`锁定现时行、追加事件、单调加版且写审计，同一事务提交，REVOKED不可原键激活，新授予用新授权版本/ID。撤权还推进独立于JWT的当前epoch，旧token即使签名有效也不能越过；撤权记录不得物理删除充撤权。列表先按当前DataScope限制，任务/来源详情及正文返回前重验精确grant；跨组织、跨任务、超期、错误用途、未知资源、缺失或不可达全部拒绝。

业务服务只通过端口取得可信主体、当前授权快照及来源用途；`ProductAccessPolicy`以本次资源、动作、用途和现时epoch做交集。旧`test-synthetic`过滤器和Controller不复用或装配进正式请求链；正式`/v2/consultation/**`由唯一产品安全链处理，路径双重解码、未知子路由和无当前许可拒绝，不存在测试profile开关使产品权限降级。正式发行/JWKS/维护端点与业务端点分开限权，公开JWKS只含公钥；无业务D的任务Controller继续关闭，不能为了验证身份而放行合成任务。正式身份的可操作产品接口包括查看本人现时会话及获准范围、显式退出和受控维护grant；不能添加只供验证的业务页面。

D设计反演须逐一闭合：合法签发与确切grant、同主体跨任务/组织、同令牌先用后撤、过期边界、旧kid/非法签名、来源用途不符、双维护者CAS、恢复旧grant、两实例撤权与受限响应交错。实现后正式HTTP+DM8 I记录签发/请求/拒绝/审计/回灌，实际项目内登记主体不能称甲方人员。生产账号登记和异地备份/水位配置是部署I/B事实，不可用自造外部身份代替；但项目自有发行的设计D不再因没有外部SSO而阻塞。

### 1.4 身份D二遍：物理与请求合同

身份服务与业务服务共享DM8事务事实但使用独立的`id2_`表组和最小权限DB账号；不能复用`con2_`开发表或旧`v2dev_*`探针。标准OAuth2授权、注册客户端及会话的持久表由Spring Authorization Server JDBC服务拥有，迁移显式适配目标DM8列型、长值和索引并做空库/升级/逆向反例；不可用内存`RegisteredClientRepository`或每次启动生成临时JWK。项目拥有的表至少如下，确切约束是本D的实现门禁：

| 表 | 主键及硬约束 | 事务所有权 |
|---|---|---|
| `id2_subject` | `(issuer,subject_id)`；账号标识独立不可变，凭据摘要、激活/停用、凭据版本及有效期限，停用不可直接重启旧会话 | 维护者登记/激活/停用，令牌签发读现时行 |
| `id2_signing_key` | `kid`唯一；公钥、主密钥AES-GCM加密私钥、算法、ACTIVE/VERIFY_ONLY/REVOKED、启用/停用时刻及单调版本；同时最多一把ACTIVE | 维护者轮换先插后CAS切换，旧公钥按最晚有效token截止清理 |
| `id2_session` | `sid`唯一，`jti`唯一；主体/客户端、签发/过期、ACTIVE/REVOKED、撤销时刻与原因、令牌对应`kid` | 签发原子保存，退出/停用/密钥紧急吊销按版本更新，不物理删作撤权 |
| `id2_grant` | `grant_id`唯一，业务资源/动作/用途键与独立`grant_generation`组成不可复用唯一键；`issued_at < expires_at`、ACTIVE/REVOKED、`grant_version >= 1`及授权epoch | 维护者按expectedVersion锁行；重授权新grantId/代，不从旧REVOKED恢复 |
| `id2_grant_event` | `event_id`唯一；grant完整键、授予/缩期/撤销、前后版本、维护主体、DB时刻、请求摘要；只追加 | 与现时grant/epoch同事务，审计不可经业务服务改写 |
| `id2_current_epoch` | `(issuer,subject,organization,resourceType,resourceId,action,purpose)`唯一，授权和来源各自单调epoch及唯一现时`grant_id`指针；缺键即拒 | 维护者与来源责任端更新，业务端只读；恢复与备份外高水位核对 |

`taskScope`为具体taskId或经维护者明确登记的组织任务创建作用域，不接受`*`作为读取/人工/来源权限；`dataScope`为确切dataset/record/version与逐字段用途引用，若来源许可缺失则QUERY/DISPLAY/DOWNLOAD/MODEL拒绝。组织关系只由维护授权记录表达，不能从JWT中的字符串认定。人员登记与授权独立：`POST /v2/identity/subjects`创建未激活主体、`.../{id}/activate`由一次性凭据完成激活、`.../{id}/disable`停用并撤会话；`POST /v2/identity/grants`与`.../{id}/revoke`仅维护身份并要求幂等键/expectedVersion，不能普通用户自领授权；`POST /v2/identity/keys/rotate`仅密钥维护身份；`GET /v2/identity/me`、`POST /v2/identity/logout`为本人会话。Issuer标准端点由框架提供；前端不接受静态API Key作为正式用户登录。维护端必须是独立双向TLS认证且精确维护动作授权；未配置操作者、主密钥、Issuer URI或DB角色时启动失败，绝不以默认admin/secret放行。

原子顺序：签发先核账号/客户端状态及凭据，再用活动kid签名，在同事务持久化授权码/会话及jti；无提交回执不返回成功token。受控grant命令先认证维护身份→按scope固定键锁epoch/现时grant→幂等查旧回执→核expectedVersion/期限/职责分离→追加事件、CAS更新grant和epoch/回执→提交；撤权先提交后任何旧JWT读/写拒绝，业务先提交后历史保留但后续正文不可见。同键同载荷重放先核当前权限再返回原回执，同键异载荷409。正式业务请求先验证JWT签名和iss/aud/nbf/exp/kid→现时subject/session/jti→锁或线性化读取精确grant和scope epoch→`ProductAccessPolicy`→域操作；提交及返回受限正文前重核同一epoch，状态不可用503且不回退缓存。备份外高水位未配置时只可在隔离实例建立新身份域，不得开放恢复的旧grant或业务任务；同DB水位不是恢复证据。文件和来源当前性另由其对应D保证。

二遍反向检查点：完整正向的前提是部署具名维护者先登记真实项目内部成员及确切组织/任务授权，测试不得预置虚构账号；缺配置启动拒绝。初始grant/密钥轮换、重授权、双维护者竞争、旧token撤权、篡改签名、旧kid、跨组织/任务、失效DataScope、源失效、过期与时钟边界、断连/提交丢响应和旧库回灌均有唯一拒绝或成功结果，无重复会话/事件/任务写。正式业务任务持久化本身仍须其最终产品D，不因身份D允许接上历史合成Controller。

### 1.5 身份实现证据边界（2026-09-27）

`ProductSigningKeys`在DB存储加密私钥及活动/历史公钥，轮换和紧急吊销均产生版本事件；`ProductGrants`提供含domain的精确键授予/撤权CAS和事件，旧代次不复活；`ProductSubjects`登记待激活主体、一次性凭据激活与停用撤会话，均追加主体事件。`ProductAuthorizationService`将标准授权记录与`id2_session`同事务保存，并在撤销时更新会话。`ProductIssuerConfiguration`使用Spring Authorization Server标准端点与JDBC持久存储，注册客户端读取仅接受授权码+PKCE与HTTPS回调，未注册客户端不签发；JWT解码要求固定issuer/audience、签名及现时主体/会话/密钥状态。`ProductIdentityController`的维护命令只接受TLS容器提供的有效客户端证书且指纹匹配配置的精确职责，身份端点拒绝明文；本人路由用现时JWT，普通请求头不能充当身份。产品发行与旧`test-synthetic` profile互斥；旧V2咨询Controller没有接入正式路径。

V9.207历史验证快照：签名、Grant、主体与JWT现时性的H2用例为组件C；非合成profile的`ProductIssuerHttpTest`在正式Spring路由用隔离登记值验证授权码+PKCE签发→现时JWT→本人查询→退出后拒绝，同时覆盖空客户端拒签、模拟安全请求JWKS公钥/无私钥、真实HTTP明文拒绝及无证书维护拒绝；仍不是运营身份I。V032/U032、V033/U033在随机临时真实DM8 schema正逆迁移、约束及Spring JDBC客户端和授权码BLOB保存/读回为物理I，probe客户端不是运营登记。后端`mvn -q -f backend/pom.xml test`、定向测试与`node scripts/verify-all.mjs`通过。当时仍无实际维护证书、项目具名成员正向签发/授权HTTP、服务mTLS身份、备份外现时水位、来源许可DataScope端口与正式咨询资源链；本人授权记录查询不能自证业务数据可访问，不能将上述C/局部I称作完整身份I或验收，也不开放来源检索。

2026-09-27增量实现：`db/identity-authority/V001__grant_watermark.sql`必须安装于不随主DM8一起回灌的独立数据库；部署责任方用受控方式登记唯一`id2_maintainer`具名主体、实际证书SHA-256指纹、精确职责与版本，不能由应用自动创建。应用缺登记、独立连接或授权水位时启动失败；每个维护请求从TLS容器已验证的证书取得指纹、读独立登记的现时状态/职责，并向独立库追加ALLOW/DENY审计。具备KEYS职责的该维护身份可在正式`POST /v2/identity/maintenance/revoke`以预期版本自撤权；遗失/被盗证书的紧急停用必须由隔离的独立库管理身份完成，不通过普通业务角色。`ProductGrantWatermark`在独立库以CAS置PENDING，然后主库事务写Grant事件与投影，提交后记录全量规范化授权历史摘要及递增代次；任一步结果不确定保留PENDING，不自动从旧主库补写。启动、身份HTTP和JWT读取均核当前摘要；缺表/缺登记、未决、双实例并发写、旧投影/事件回灌、独立库不可用一律关闭。初始化READY零代只允许在独立核查主库授权历史为空后由受控部署登记；恢复非空历史须按隔离恢复程序核对，不能重新置零。返回本人Grant前后比较独立代次。此水位仅覆盖Grant事件/投影，不能替代尚未实现的主体/会话、来源及删除水位和完整任务资源锁序；生产必须证明两库备份边界、最小DB账号及恢复演练，不能仅以不同JDBC URL推断隔离。

对应C：两独立H2数据源的签发/撤权摘要、旧Grant投影、未决意图、拒绝命令和双实例并发拒绝，具名登记/证书职责/审计/自撤权的隔离测试，以及正式身份HTTP水位未决503均通过；两套隔离真实DM8实例的临时schema安装、缺水位与未决拒绝通过，均非运营身份或生产备份I。仓库无可核证的实际维护证书、受托成员、组织/任务DataScope授权和生产权限/备份配置；隔离登记值只作机制C，不得宣称真实正向身份链或来源解锁。

部署初始化窄D补充（V9.215）：首次部署是离线受控命令，不提供匿名HTTP注册或默认管理员。操作人用独立的数据库部署角色、固定issuer、具名维护subject/organization、明确职责以及经部署信任链核验的实际客户端证书输入；主库身份/Grant历史与独立库维护主体/水位均须为空。命令先验证证书期限、客户端认证用途、指纹、信任CA及配置的独立主库/授权库端点，独立库同一事务插入唯一维护主体、证书指纹/组织/职责、零代READY水位和初始化审计；失败不留下半个维护身份。重复初始化一律拒绝，不能覆盖恢复状态。应用服务账号只读维护登记、追加审计及受控维护撤权/水位CAS，不能执行首次初始化；备份账号只读一致快照，恢复账号只能隔离回灌且不能直接放行路由。服务启动要求独立库已初始化和主库历史摘要匹配。成员经该维护主体的正式证书路由登记，由本人一次性激活，再由维护者以确切组织/Task/DataScope和动作/用途通过正式Grant路由授权；初始化命令不能自造成员、替其设置密码或签发任务权限。真实登记和授权证据只能在操作人提交实际证书、主体、组织和任务资料并执行后取得，隔离代码测试不升级为真实I。独立授权库的备份、介质/密钥及恢复验证仍属恢复D，不因部署初始化命令自动通过。

部署命令为正式应用包`java -jar agent-platform-api-0.0.1-SNAPSHOT.jar --identity-bootstrap <manifest.json>`。manifest只含`issuer,subject,organization,certificatePath,trustCertificatePath,certificateSubjectDn,certificateSha256,roles`，不含DB密码或成员凭据；部署环境独立提供`PRODUCT_IDENTITY_DB_URL/DB_DEPLOY_USER/DB_DEPLOY_PASSWORD`、`PRODUCT_IDENTITY_AUTHORITY_DB_URL/AUTHORITY_DEPLOY_USER/AUTHORITY_DEPLOY_PASSWORD`及`PRODUCT_IDENTITY_TRUST_SHA256`。两库和部署角色相同、空库条件不符、信任锚/证书用途/指纹不符或重复执行均拒绝。产品正常启动另使用`spring.datasource.*`及`product.identity.authority.jdbc-url/username/password`的非部署凭据。部署者仍须实际配置mTLS容器信任链、DM8最小权限账号及独立备份作业；仅填写配置不证明作业存在或可恢复，未核前不得升级物理恢复D。成员创建/激活/Grant是正式路由连续操作，不由部署命令生成一个不可归责的默认成员。

身份请求适配进展（V9.223）：`ProductConsultationAccess`只接受资源域从持久事实给出的组织、Task、DataScope及资源版本，入口必须先由正式JWT解码器验签/核当前会话；它再读当前主体/会话状态、唯一现时Grant及独立授权水位，精确匹配任务/数据范围/动作/用途，调用`ProductAccessPolicy`并在响应前复核水位。隔离双库C用正式签名和解码器覆盖允许、越Task/DataScope、动作不符、停用、会话撤销、Grant撤权和旧Grant投影拒绝；不是正式咨询资源HTTP或实际成员I。当前`--identity-bootstrap`是产品部署命令而非开发旁路，但其已过窄D只准创建具名维护主体及空授权域。用户要求部署同时建成员、组织、Task和Grant属于该窄D的变更：成员须本人激活、Task须咨询领域最终D和持久事实、DataScope须绑定获准来源，不得用manifest自报Task或测试主体代替。待对应Task/来源D通过后再定义可回滚的正式初始化编排和真实请求证据；未来操作人证书是部署输入，不作为编码的外部前置。

客户端部署补全（2026-09-28）：现有JDBC `RegisteredClientRepository`只负责持久读写与PKCE合同校验，正式部署尚缺受控注册，不能用测试插表或静态token代替成员登录。由持SUBJECTS职责的现时维护证书经`POST /v2/identity/clients`提交稳定clientId、名称、唯一确切HTTPS回调和幂等键；服务只创建无客户端密钥的授权码+PKCE、`openid`公共客户端，拒空值、userinfo/fragment、非HTTPS、同键异载荷与同clientId异登记。主库同事务保存注册、当前ACTIVE指针与含actor/请求摘要的事件回执；同键同载荷可重放。`POST /v2/identity/clients/{id}/revoke`以当前版本CAS转REVOKED并追加事件，注册仓库对已撤销客户端返回不可用，不删除历史；成员会话另按现时授权与撤权检查。维护认证独立审计，主体自身不能注册客户端。部署人员与HTTPS回调属于部署输入，但当前环境可按此正式入口自行初始化，测试主体或临时客户端不能冒充产品请求。

### 1.6 首个正式组织/任务/DataScope窄D候选（V9.224）

`ARC-CON-PRODUCT-TASK-SCOPE-D-01`仅覆盖产品部署时首个真实咨询任务资源和其只读元数据，不替代`ARC-CON-TASK-01`的消息、执行、取消、纠正、成果或完整保留恢复。来源为CON-12任务跨轮可恢复和默认拒绝、docs/55第2/4/5/6节、docs/56第1/2节及第1.3～1.5节的正式Issuer/Grant。任务没有预置用户问题、候选文献或模型输出：初始`AWAITING_GOAL`，用户以后明确输入才改变目标。`Organization`为部署者确切输入的稳定ID、名称、ACTIVE状态、版本和审计，不能从JWT自报组织生成。`Task`由服务端生成UUID，绑定该组织、首个已激活成员、状态/版本、创建者维护身份、创建时刻、明确到期时刻与保留依据ID/版本。`DataScope`由服务端生成UUID，绑定单一任务和组织，初版只覆盖任务自身元数据；文献来源另由Source Contract逐字段/用途授权，不允许此范围自动覆盖任何文献、文件或模型输入。三者在主DM8同事务写当前行及不可变创建审计；失败无孤儿Task/Scope。所有版本从1开始，后续修改需`expectedVersion`单调CAS和新审计，不原地改历史事件。

部署流程是同一最终产品的受控连续步骤，而不是匿名种子或一个不可归责的默认用户：`--identity-bootstrap`导入受信CA及具名维护证书并登记空授权域；维护者用该证书正式登记首个具名成员，成员自己激活；维护者随后提交确切组织/保留配置创建初始空目标Task/DataScope并通过现有正式Grant CAS发该成员对`Resource(CONSULTATION,TASK,taskId,1)`的`READ/TASK_WORK`最小权。独立水位先PENDING、主库组织/Task/Scope/Grant同事务提交、再推进水位；提交结果未知保持PENDING且路由关闭，不自动重置零代。任务/范围生成值由回执返回而非manifest预造，创建请求有幂等键和请求摘要；重复同键返回同一回执前重验当前性，异载荷409，不允许反复建立“首个”任务。成员停用、Grant撤回、会话失效及旧授权库/主库回灌均失败关闭；Task owner字段不是访问许可。已有身份维护审计与新增领域创建事件分别保存操作者、时刻、版本、请求摘要和当前授权代次。

保留边界：部署必须配置有责任来源的`retentionPolicyRef/version/evidenceSha256/maxAge`，无任何默认值，摘要固定所批准政策文件版本；首建命令所提交的ref/version必须精确匹配，`expiresAt`必须在服务时刻之后且不超过配置的最大期限，任务行保存依据摘要。缺配置、摘要不合法或超期拒绝，不从开发期六个月推导。配置的责任/真实性是部署I审查，代码不能单凭非空字符串宣称批准。到期时包括owner在内的读写立即拒绝；清理、备份轮换、法定例外、文件/第三方正文依第5节恢复D另判，不能声称此窄D完成物理删除。初始任务仅保存非正文元数据，未批准的目标内容/来源正文不落库。当前来源许可为空时只能查看自己的任务空壳及缺口，不能检索。`GET /v2/consultation/tasks/{id}`的领域仓储先按ID取确切组织、任务、范围和版本，核状态/到期，调用正式JWT及`ProductConsultationAccess(READ,TASK_WORK)`，返回前重查版本和独立水位；无范围不泄露对象存在，状态不可达503。资源版本变化使旧Grant失效且需维护者重新授权，不从owner字段自动续权。初始创建仅维护部署命令，不提供无组织创建权限的普通POST；完整用户创建/补充仍在`ARC-CON-TASK-01`。

D反例：无组织/重复组织、待激活成员、同键异载荷、并行首建、跨组织或Task、篡改DataScope、旧任务版本、到期边界、停用/撤权与旧Grant、主库回灌、独立水位PENDING/断连、读取中失权、DB提交后水位推进失败、重启后回执与Task/Scope不一致。C为正式代码加独立双库隔离输入，DM8 I须安装新迁移后实测事务/CAS与重启，实际部署证书/成员和批准保留输入的HTTP记录另证；合成值仅测试机制不冒充运营身份。二遍D须检查跨库意图与Task事务一致、部署首建权限和到期不误删、请求时领域事实不可从客户端取值，未闭合不得判通过或编码。

窄D物理/命令反查：新增独立`con3_organization(organization_id,name,status,version,created_at)`、`con3_task(task_id,organization_id,owner_issuer,owner_subject,state,version,expires_at,retention_ref,retention_version,created_at)`、`con3_data_scope(scope_id,task_id,organization_id,scope_version,kind)`、`con3_initial_task(organization_id,task_id)`、`con3_initial_receipt(request_id,request_sha,organization_id,task_id,scope_id,grant_id,created_at)`和`con3_event(event_id,task_id,operation,actor_ref,version,request_sha,occurred_at)`；FK与唯一键保证组织、任务、范围和首建回执一致，scope.kind仅`TASK_METADATA`，不存文献ID或外部来源许可。安装/回退迁移只对空新表操作，已有表与Grant历史不重置。部署初始命令输入`organizationId,name,memberSubject,retentionRef,retentionVersion,expiresAt,requestId`，来自具名维护证书且须是当前`SUBJECTS`及`GRANTS`职责，subject必须已ACTIVE；不接收taskId、scopeId、owner角色或客户端权限。命令先核独立水位，再锁组织首建键/回执；同键同摘要仅在任务/范围、成员现时状态及Grant当前指针都一致时返回原回执，异摘要409。首次创建时在`watermark.change`内用同一主库事务插入组织、task、scope、首建指针和审计，调用`ProductGrants.issue`建立精确`READ/TASK_WORK`授权，最后写含真实grantId的回执；任何失败回滚主库并按独立水位协议拒继续。Grant期限不得晚于任务到期。组织已存在或另一请求抢先首建409，不采用最后写入覆盖。具体证书路由/离线部署命令可共享同一应用服务，但无维护证书不开放该命令。

只读资源HTTP为`GET /v2/consultation/tasks/{taskId}`，入参仅路径ID，不接收organization、scope或actor头；服务按taskId查任务、当前组织、范围、到期和状态，组装`ResourceFacts`，经正式OAuth资源链解码JWT，调用`ProductConsultationAccess`后再校验行版本/水位才返回`taskId,organizationId,ownerSubject,state,version,dataScopeId,scopeVersion,expiresAt`，不返回证书、Grant、来源正文或其他成员资料。无对象、到期或无可见范围统一404，认证失败401，可信身份/水位不可用503；Grant期限不晚于任务到期，不承诺不可到达的“已授权到期410”。跨范围在查询中不使用客户端提供的scope；带错误scope的Grant不能匹配持久事实。当前scope字符串绑定`scopeId`与`scopeVersion`（版本化编码由服务生成，不对客户端作授权输入）；Task版本变化需要新精确Grant。只读响应无需任务写事务，但读取前后版本一致性、独立授权水位及现时会话必须重验；流式/文件下载不在此窄切片。

V9.229实现与证据：`V034/U034`建立/逆转六张新表，`ProductTaskDirectory`由咨询域持有，JDBC适配持久Task/Scope，`ProductInitialTaskService`在独立授权PENDING意图内用主库事务写组织/Task/Scope/审计/Grant/回执；`POST /v2/identity/organizations/initial-task`需具名维护证书的SUBJECTS及GRANTS职责，`GET /v2/consultation/tasks/{id}`经过正式JWT链、领域事实、ProductConsultationAccess及再次查询。旧V2开发filter只放行这个精确GET，其余旧路径仍关闭。隔离H2测试覆盖动态ID、同键回执、冲突、跨Task/Scope、停用/撤权、旧Grant、水位不可用与无证书；`ProductIssuerHttpTest`用隔离实际X.509证书、标准PKCE令牌走正式POST/GET。`ProductIdentityDm8SchemaTest`在临时真实DM8用户/schema执行正逆迁移、首建、重放、读取、冲突和撤权（独立水位该次使用隔离H2库）；后端全量Maven通过。测试成员/证书/期限仅为机制C/局部物理I，未证明实际部署人员、批准保留值、双DM8及生产备份恢复；只读空目标资源不等于完整任务闭环。

V9.231保留输入修正：首版首建只检查非空ref/version，不能证明部署配置获准。现`ProductRetentionPolicy`在产品身份装配时强制读取无默认值的ref/version/evidenceSha256/maxAge，缺值拒启动；首建精确匹配ref/version且到期不超过maxAge，任务行存依据摘要，幂等请求摘要也绑定政策配置。隔离C覆盖空依据、超期和摘要持久化，临时真实DM8事务重新通过。部署证据仍须核政策文件的来源、责任人、版本、哈希和适用组织；任意配置值不能凭测试自动成为法律保留依据。

部署权限增量：部署环境还必须提供预创建、彼此及部署者分离的`PRODUCT_IDENTITY_AUTHORITY_SERVICE_USER`、`PRODUCT_IDENTITY_AUTHORITY_BACKUP_USER`；一次性命令仅给服务账号维护主体/水位SELECT+UPDATE和审计INSERT，给备份账号三表SELECT，不授INSERT维护主体/水位或备份写权。正式服务配置另强制`product.identity.authority.schema`，每连接切入该schema以使用真正受限的账号；缺schema启动失败。H2正反C验证服务账号不能重建维护人、备份账号不能改水位；隔离双DM8临时账号验证GRANT语法、备份拒写与服务账号`Connection.setSchema`后的未限定表访问。没有实际生产账号、加密备份作业、独立介质权限及隔离恢复演练，这些物理I仍不可称为部署完成或恢复D通过。

### 1.7 首个确认目标与检索输入版（2026-09-28，ARC-CON-TASK-01设计复核）

本节是开发团队依据CON-04/06/12及docs/56第2～4节形成的最终产品首目标设计，不把V034空目标Task或用户本轮搜索字符串当已确认目标。`GoalRevision(goalId,revision,taskId,organizationId,purpose,queryObject,scope,requiredFeatures,sourceMessageRef,confirmedBy,confirmedAt,status,contentSha)`不可变；`GoalCurrent(taskId,goalId,revision,rowVersion)`是唯一现时指针；`SearchInputRevision(inputId,revision,goalId,goalRevision,taskVersion,queryText,requestedFields,sourceScope,scopeSha,confirmedAt,validUntil,contentSha)`仅从同事务确认的Goal派生。原用户消息及提取建议保留各自引用，模型只能提出草稿，不得写确认人或改当前指针。Task状态、Goal确认状态和SearchAttempt状态分列，不合并为一个“完成”。

输入最小合同：当前具名成员的Task `READ/TASK_WORK`及组织/DataScope、`expectedTaskVersion`、幂等键、用户原消息引用、目标用途（资料查询/检索/逐项对照）、查询对象（非空技术方案文本或明确专利文献键；号码歧义先返回候选）、明确检索范围（获准sourceId集合及可用的地域/日期/文献类型条件）、按需的关键技术特征及其原文引用。简单查询不要求特征或人工；逐项技术对照必须有明确目的、确切候选文献版及用户确认的必要特征，未齐时只可检索/补问，不能伪造对照输入。用户在提交已展示的对象、范围和用途时作显式确认；未明确范围、相关日期依据或特征是否会改变请求含义时返回必要补问，不能默认全域或今天。来源可用性在检索执行时另核，确认目标不授来源权限。

`POST /v2/consultation/tasks/{taskId}/goals/confirm`先核当前成员/Task与期限，按幂等键和规范化请求摘要查回执，再锁Task当前版；成功时同一主库事务插不可变Goal/Input、确认事件、唯一当前指针、Task版+1与回执。相同键同载荷仅在当前成员仍有效、目标仍现时且未到期时返回原版；异载荷409。断连未知提交按回执读回，不重建目标；并行旧`expectedTaskVersion`为409且无部分写。目标文本保留只用部署时批准的ref/version/evidenceSha/maxAge，期限为Task、批准政策和输入允许范围的最早值，无配置拒创建，不借Task元数据保留。成员停用/撤权、Task取消/到期或水位不可用分别403/410/503，均不改确认状态。

纠正对象、用途、范围或关键特征时追加Goal/Input新修订并原子推进Task版/当前指针；旧版与其检索/焦点/对照仍保留审计引用，但旧Input不得续页、选择为当前依据或发起新执行。仅展示别名的变动不无故重检。`SearchContextPort.load(taskId,inputId)`只从任务领域当前指针及确认事件重建，返回第2.4节约定的确切组织、Task/Goal/Input版、确认人/时间、query/字段/来源范围/期限；`AWAITING_GOAL`、旧版、未确认、取消、过期或授权不可用在触达来源前分别拒绝，不返回空结果。结果集合固定Goal/Input摘要及所用文献版，后续逐项对照和人工包只能引用该快照，不能引用“最新”浮动目标。

D反例须逐一过表/命令复核：合法技术查询无特征确认；专利号歧义先选；缺必要对照特征只允许查询；跨组织/Task、owner已撤权、缺原消息/范围、过期、同键异载荷、双确认CAS、断连回执、纠正后旧游标、取消与迟到执行、源撤回后旧输入均不能写入或发布错误当前结果。迁移需为Task/组织及Goal/Input版设FK和唯一当前键、事件/回执同事务；V034仅允许`AWAITING_GOAL`，新增可检索状态须正逆迁移并在真实DM8验证，不能绕其CHECK直接改状态。本节是最初的确认目标切片复核；本轮新的完整Task责任边界与D结论以紧随其后的第1.8节及主计划V9.278起的记录为准。

### 1.8 Task产品D责任边界与跨轮恢复（2026-09-28）

项目负责人本轮明确把Task产品能力与具体第三方来源准入解耦；本节覆盖`ARC-CON-TASK-01`的完整目标、消息、执行、取消及恢复设计，继承第1.7节输入版、docs/56第2～3节和docs/55第5～7节的事务反例，但不继承其“仅开发期”身份/期限假设。外部文献的QUERY/正文权利只决定来源是否ACTIVE及执行能否读取，不决定Task是否可创建、确认或恢复。没有ACTIVE来源时Task保持已确认目标并明确`SOURCE_UNAVAILABLE`，不制造空命中或完成结论。Task自身原文保存和期限另使用部署批准的目标正文保留政策；缺政策不能写正文，不能借初始空任务的元数据政策扩大用途。

Task从现有`Organization`和`DataScope(TASK_METADATA)`起步，成员的组织创建权限与确切Task `READ/WRITE TASK_WORK`分别由现时Grant决定；owner字段不授予访问。`taskVersion`是内容/命令CAS序号，`authorizationResourceVersion`是独立的权限资源版本，只有组织/范围/归属变更使后者前进且必须先完成新授权；普通目标确认、消息和执行提交不得使同一个Grant因taskVersion增加而自行失效。现有V034以taskVersion同时作为授权资源版，仅适用于空任务，实施时必须显式正逆迁移、逐路由切换和旧Grant不越权反例。Task、Goal、Session、Message、Attempt/Checkpoint、Receipt/Event各有独立状态/版本及确切FK；消息原文、确认目标与机器建议分存，不覆盖历史。当前指针与不可变修订同事务，按需补问不成为新确认目标。

产品Task状态采用docs/02第20节九个已确认状态；`用户取消`终止当前任务，`已完成`只能在主要目标与必要证据、限制均满足后成立。会话独立为进行中/结束/过期，断线或会话关闭不取消任务；新会话显式关联同一未到期、未取消Task，重新验证主体、组织、Grant和当前Input版后从已提交快照继续，不继承旧会话令牌。取消后的原任务不能恢复执行，用户要继续则新建有关联、重新授权的新Task，旧历史在批准期限内可受控读取；外部动作取消结果未知标待核实，不能宣称撤销成功。普通消息同Task只有一个活动执行槽，纠正/取消为带expectedVersion的控制命令；冲突409不抢占普通消息。

所有写命令先验现时身份、Task/DataScope及用途，再按`(主体,Task,操作,commandId)`和规范化请求摘要取回执，重放同载荷在重新鉴权后返原版，异载荷409；新写锁当前taskVersion、inputRevision及cancelEpoch，追加事件/检查点/回执/outbox并提交。Agent领取有单调fencingToken与服务端租约，提交再核token、taskVersion、inputRevision、cancelEpoch及当前权限/来源；长模型/外部调用在事务外。服务重启只扫描租约过期的非终止尝试，重核当前授权与来源、读取最后已提交检查点、对账未知外部结果后创建关联新尝试；旧worker、旧Goal、旧来源版和迟到输出只能留历史/失败审计，不能发布当前成果。取消先提交使旧输出拒绝，成果先提交保留既有版本且取消停止后续动作。DB/文件/独立授权与来源水位恢复时先校验Task/Goal/Input/Receipt/Checkpoint/原件引用一致性，旧备份不得回退现时撤权；不一致、不可达或期限缺失保持不可用，不自动重做外部副作用。

Worker领取窄入口只用于正式Task执行围栏，不提交检索/成果：`POST /v2/consultation/tasks/{taskId}/executions`接受确切`inputId,delegationId`及`Idempotency-Key`，租约时长由服务端受控配置固定，不从请求扩大；TLS验证项目CA签发的工作负载客户端证书，服务身份只能由证书指纹查当前工作负载，不接收主体ID自报。先后两次重核服务/人用精确Task WRITE Grant、委托、现时来源QUERY Scope/Grant、Task/Input版本与双独立水位；同主库事务领取单槽租约并存`(taskId,serviceSubject,requestId)`唯一回执、规范化请求SHA、attemptId/token/到期。相同键同载荷在重新鉴权后，仅当原租约仍为同一ACTIVE当前槽且未过期返回原回执；不同载荷、取消/续轮、到期、撤权、来源变化或新围栏后旧回执不恢复权限，明确冲突/不可用。无证书、跨Task/Scope、无委托、伪造来源或失联一律拒绝且零租约写；失败不能回退到用户JWT或维护证书。领取回执不是检索结果或成果，checkpoint/发布只能接后续已设计的结果提交事务，不能由这个入口凭空生成业务输出。

过期单槽的正式受控对账入口限定为`POST /v2/consultation/tasks/{taskId}/executions/reconciliation`，请求仅有确切`inputId,delegationId`与`Idempotency-Key`，响应复用`attemptId,fencingToken,leaseUntil`租约回执；不得由调用者指定旧token、新token或宣称外部结果。与领取同一mTLS工作负载和当前授权/Task/Input/来源双水位守卫、同一主库事务和请求键唯一表；请求摘要额外绑定`RECONCILE`动作，故与领取键冲突。仅过期槽已由领取路径标为`RECONCILE_REQUIRED`、任务/输入/cancelEpoch未变且没有任何外部dispatch意图时，读取最后已提交检查点，写不可变对账关系并CAS发行递增围栏。相同键同载荷只在重新鉴权后新租约仍为同一ACTIVE当前槽且未过期时返原回执；异载荷、旧来源/Grant、外部结果未知、旧token及对账重试越界均拒绝，不能自动推断外部调用成功或重复执行。此入口只解除无外部dispatch的死槽，不生成检索结果、证据或成果；实际结果提交须另有同库业务写事务与已通过的对应业务D，不能以空`Runnable`或租约记录冒充。

正常/异常反演：合法自然语言任务先建立待澄清目标，确认简单查询不要求技术特征；跨轮重登从同一taskVersion/goalRevision继续；并发同键只一条事件、异载荷409；会话过期只禁旧会话输入；取消与迟到结果不产生完成；恢复旧租约不得重复外部动作；撤权后旧回执/事件不泄露正文；缺来源为不可用而非零结果；来源撤回后已保存历史不作当前依据；Task目标正文保留到期即拒读/恢复，清理与备份回灌遵循独立水位。实现C用正式身份/路由与项目自有内容及真实采集字节验证技术合同，真实第三方内容只在具体来源ACTIVE后通过正式检索使用；物理双库/文件恢复I及业务验收分别取证，不是本D设计通过的前置。

Task续建窄D补全（2026-09-28）：首建`con3_initial_task`每组织唯一，不能用它重建到期或取消后的普通Task。组织维护者经现时Grant签发给具名成员`Resource(CONSULTATION,ORGANIZATION,organizationId,organizationVersion)`的`CREATE/TASK_WORK`，其`taskScope=organizationId,dataScope=organizationId:organizationVersion`且期限不得超过组织有效保留政策。正式`POST /v2/consultation/tasks`仅取当前JWT主体，在DB读取组织状态/版本、主体ACTIVE、精确CREATE Grant及独立Grant水位；客户端不得自报owner/组织权限。提交`organizationId,expiresAt,retentionRef/version`和幂等键，创建时锁组织版本并在授权水位PENDING下同主库事务写新的AWAITING_GOAL Task、TASK_METADATA Scope、对该主体的新Task READ Grant、`TASK_CREATED`事件及请求回执。回执重放须重新鉴权且任务/Grant/期限仍当前，同键异载荷409；跨组织、撤权、停用、过期、版本漂移及事务中途失败不留部分Task。首建路由及历史Task不变；此补全属于已通过的`ARC-CON-TASK-01`组织创建责任，不以第三方来源ACTIVE为前置，也不把创建权限扩大为来源读取权。

## 2. 数据来源与检索

来源：CON-02专利库第2～4、6、9节，CON-04第2～4、6、9节，docs/55第8节。每数据集注册不可变`SourceContract`：所有者/许可证据、采集日期及期限、允许字段和QUERY/SAVE/INDEX/DISPLAY/DOWNLOAD/MODEL/SHARE逐用途、主体范围、撤回通道、API能力（覆盖、分页/游标、排序、全文/权项、版本、原文定位、限流）、更新与错误语义。许可空值即禁止；公开可访问不推出可索引、全文复制或入模。项目自行采集或脱敏的真实资料必须保留原始出处、采集/脱敏链、摘要、逐字段许可和可复核定位，脱敏不能突破原数据权利。旧公开试点和合成包不自动进入产品注册表。

领域端口为`resolveNumber(number,type?) -> exact version or disambiguation`、`search(query,scope,cursor,requestedCapabilities) -> hits + actualScope + actualMethod + sourceErrors + coverage`、`readContent(sourceId,recordId,documentVersion,section,locator) -> bytes/text + digest + parseVersion + quality + originalRef`、`currentUse(ref,field,purpose)`。适配器不得把空返回包装成源失败、把本地摘要哈希当权威版本，或把供应商字段直接提升为系统业务事实。申请号/公开号/授权号分栏；文献键为源+稳定记录ID+实际文献版本，缺稳定ID/版本时按可核来源观察版隔离，不能合并同题不同版本。原件、解析/OCR和片段定位分别版本化；`claim:number`、原段/页/坐标优先，系统偏移明确标派生并回链原文。无原文或质量不足仅显示元数据/缺口，不从摘要造权项。

每轮搜索保存用户原文、确认范围、实际查询改写、方法/版本、各源实际覆盖、失败与时间；授权在召回前和结果返回前生效。仅对实际获准索引/接口执行其声明能力：纯关键词就是关键词，语义/图像能力缺失不得借字面命中冒充。排序限定在可比较的候选集合内，默认可解释相关度降序、同级以`sourceId,recordId,documentVersion`稳定排序；无标定分数用标签/证据而不造百分数。同族仅依据已验证关系聚合且可展开。结果集固定成员、版本、排序方法及范围，焦点引用精确集合成员而非当前第N项；重新检索新建集合。源超时/限流/撤回、部分成功、真实零结果、能力缺失各有不同结果/错误，不称检索穷尽。扩大时间、地域、源或权限先经用户确认和再授权；同范围改写可继续，资源预算无批准值不填无限或固定业务轮数。

D反例：同申请两公开版、号码歧义、S1第2项在S2重排后不重绑、源A成功B超时、源撤回、分页丢项、无权项/原文、解析漂移、同分排序、查询能力仅字面、无保存/展示/入模许可。待实证：首个真正可供该任务使用的数据集许可与具体查询/分页/版本/原文能力；采集资料可作为数据内容，不能代替检索算法或来源行为。

首任务来源D初审（V9.223）：`data/patents/public-pilot-v1`、`epo-cn-pilot-v1`、`cn-topic-public-pilot-v1`三份manifest的许可状态均为`REVIEW_REQUIRED`且用途仅`INTERNAL_DEVELOPMENT_PILOT_ONLY`。EPO包记录权项摘录不可用且需另有OPS授权；CN主题包声明不存PDF、说明书全文、摘要、权项及现时法律状态。这些资料不能产生产品逐字段QUERY/DISPLAY/DOWNLOAD/MODEL许可，也不能支撑权项原文定位。`ARC-CON-PRODUCT-SOURCE-D-01`已启动但未通过；首个产品来源须提供权利/用途凭据、真实采集/版本/定位样本及查询分页能力记录，逐字段核准后再判D。不得因身份D通过而激活旧固定候选。

### 2.1 首个正式Source Contract的D反查（V9.228候选）

产品登记键为`sourceId + contractVersion`，一次批准的不可变合同必须含权利人/来源、可核许可凭据及期限、`allowedUse`的字段×QUERY/SAVE/INDEX/DISPLAY/DOWNLOAD/MODEL矩阵、撤回通道和现时sourceEpoch、覆盖范围/采集时刻、可查询字段、分页/排序能力、全文/权项可用标记与原文定位能力。每篇文献的`documentId + documentVersion + sourceVersion + collectedAt + rawDigest`分开存；上游无权威版本时用内容摘要与采集时刻形成明确标记的观察版，不能称官方新版本或与另一观察版合并。`sourceLocator`须绑定确切原件摘要、权项号或页/段和解析版；派生字节偏移须回链原始响应/文件，重取不一致就拒该段原文。原始字节、解析文本和权项各有可用状态，只有元数据时不声称全文可读。

正式`search(query,scope,cursor)`只对现时许可允许QUERY/INDEX的已采集文献或源接口实际声明的字段、范围执行动态查询；召回前及返回候选前按成员DataScope、合同字段和用途过滤。返回`sourceId,documentId,documentVersion,sourceVersion,actualQuery,actualScope,method,coverage,orderedHits,nextCursor,sourceErrors`，成员及排序固定为本次结果版；同级用上述稳定键排序，不能从固定列表按序返回。选择只能绑定本次结果成员的确切版，不能从当前位置或最新源自动换绑。`readContent`按当前DISPLAY许可读取原文/权项，验证原始摘要、版本和locator，返回原文片段及限定定位；DOWNLOAD/MODEL各自另核许可。零结果、源超时/撤回、游标失效、无权项、解析漂移、无DISPLAY、真实部分成功分别表达，不把缺来源包装成零结果。许可或现时来源水位不可取得即拒绝，不退回旧缓存。

实际包核查：`data/patents/public-pilot-v1`有44条真实采集详细记录、47份响应，部分`claimResponses`含权项原文；但SureChEMBL的`licenseReviewStatus=REVIEW_REQUIRED`、`usageDecision=INTERNAL_DEVELOPMENT_PILOT_ONLY`，且示例上游`doc_version`为空。已有字节可作为许可审查与解析定位设计输入，不能被登记为正式`allowedUse`或产品索引/原文服务。EPO/CN主题包亦有同一用途限制或权项缺口。当前未取得可核的字段×用途产品许可、撤回能力与正式检索覆盖记录，D维持进行中；`IMP-CON-PRODUCT-SOURCE-01`不能编码。项目自行新采的真实专利同样先核权利/用途、再保留原始字节与版本定位，不因公开可读自动准SAVE/INDEX/MODEL。D放行最小样本为至少一份带权项原文定位、摘要与采集链的获准真实文献及一条许可拒绝反例，另须有该源的查询/分页能力与失败语义；无样本不臆造产品候选。

### 2.2 真实采集样本与用途复核（2026-09-27；D证据，非产品数据登记）

来源分类补充（2026-09-27）：项目自行采集的可核专利资料是**真实来源数据**，可作为最终产品当前数据源，不以另一个外部专利数据库是否存在判真实性。manifest中的`PILOT`/`INTERNAL_DEVELOPMENT_PILOT_ONLY`记录原采集用途边界，不把数据降为Synthetic/Fixture/Mock/Demo，也不自动批准新产品用途。正式合同对每篇须保留sourceId、原始来源及请求URL/接口、documentId、上游版或标明OBSERVED的documentVersion、采集时刻、sourceVersion、原始字节摘要、权项/正文原件及解析定位、现时撤回/失效状态；缺字段明确不可用，不捏造原文或官方版本。上述真实性与逐字段用途、保留/索引/展示、撤回水位和Task来源授权是独立门禁。真实采集字节可用于合同/解析核证，只有准入通过才进入正式用户检索和对照。

本轮逐文件复核：`public-pilot-v1`的records与47份raw、`cn-topic-public-pilot-v1`的records与16份raw均与manifest的SHA-256一致；`epo-cn-pilot-v1`的records摘要一致，但`raw/list-page-0.json`、`-1.json`、`-2.json`当前文件SHA-256依次为`7182d017eea0acba8176747ae24322e0602730b087293b34589f77c45e7aebfa`、`198ccd41b47781630d96d89a5d1cabff0735c3744b41db996ea6573e9a355a14`、`2c1a307634930bf029cc3abe715c089542a18e102d`，均不等于manifest登记值，LF归一或去尾空白也不能解释。EPO包的采集事实不因此变成合成，但这三份原件暂不能通过摘要链准入；须查明差异并由可核原始响应重建或更正带审计版本的manifest，不能静默覆盖旧摘要。

字段映射尚未等于最终Source Contract：`public-pilot-v1`的44条记录没有逐条`sourceId`，虽有`sourceRecordId/sourceVersion/capturedAt/detailRawFile/detailResponseSha256`及原始请求URL清单；`cn-topic-public-pilot-v1`的400条记录有`sourceId`，manifest的16份raw是查询页，另按逐记录`detailRawFile/detailResponseSha256`核了400份详情文件，全部摘要一致；EPO 30条无逐条`sourceId`且无权项。三包均无可核现时撤回状态，旧`sourceVersion`记录格式也不能代替来源合同版本或上游`documentVersion`。正式导入须从原始采集链映射并验证这些字段，不把缺值补成猜测常量。

采集包进入最终引擎的逐项映射判据（设计，不是现有包准入结论）：

| Source Contract字段 | 可核原始输入 | 不满足时的行为 |
|---|---|---|
| `sourceId`、原始来源与接口 | 经准入登记的来源合同ID、manifest来源名称/原始`requestUrl`及其请求响应摘要；逐记录关联详情请求 | 记录缺`sourceId`时只能经同批原始请求与合同的确定性关联后赋值，关联不唯一则隔离，不按目录名直接授权 |
| `documentId/documentVersion/sourceVersion/collectedAt` | 原始`doc_id`、上游`doc_version`、采集响应时间和合同版本；空上游版仅按原始摘要+采集时刻标`OBSERVED` | 缺确切文献键/时间/来源合同版本时拒导入；公开日或旧记录的`sourceVersion`不能冒充官方正文版本 |
| 原始摘要、权项/正文、定位 | manifest/逐记录原始文件SHA-256、原始字节、版本化解析片段、JSON Pointer及回链；权项号须有解析与原文复核 | 摘要不符隔离原件；只有列表/摘要的记录仅保留确实获准的元数据，不生成权项或全文能力 |
| `allowedUse`与撤回/失效 | 独立可核逐字段许可、期限及现时撤回通道/独立sourceEpoch，而非旧manifest用途声明或文件存在 | 未核用途、过期、撤回、PENDING/水位不可用均不得进入ACTIVE或用户搜索/展示；历史原件只按已获准审计保留 |

上述映射由真实来源适配器对实际数据逐条执行，动态查询只在ACTIVE且字段×用途、Task来源范围和结果保留条件同时满足时运行；不能将已采集的44/400/30条作为固定候选直接返回。允许完整元数据而无权项的来源按能力降级，不得为首条需要权项的技术对照声称可用。

开发团队以`data/patents/public-pilot-v1/manifest.json`的原始响应清单对`US-20130157136-A1`逐字节复核：请求为`https://surechembl.org/api/document/US-20130157136-A1/contents`，采集响应时间`2026-09-18 10:54:55 +0000`，`raw/detail-US-20130157136-A1.json`的SHA-256与manifest同为`8091762ebc546b6b8ee7c355c5a9b0ad684117073c507eb03bff9eca784aa8b2`。原始`doc_id=US-20130157136-A1`、`published=20130620`，但`doc_version`为空，故产品只能在获准后以原始摘要+采集时刻形成标明`OBSERVED`的`documentVersion`，`sourceVersion`另绑定实际采集批次/来源合同，不能把公开日伪装为官方修订版本。

同一原始响应的`#/data/contents/patentDocument/claimResponses/0/section/content`长4319字符，片段ID`PCLM52688162`，文本SHA-256为`9a4496801b1fbd97e14e967e72ae618c84147449e1dc4efa3ecc65e23a56039d`；`#/data/contents/patentDocument/descriptions/0/section/content`长41040字符，片段ID`PDES59744415`，文本SHA-256为`b181abe50997d3ab5071f65dd34b38ccc4b444a089e79c179a978ab26e044aba`。两者语言标记为EN。这里的JSON Pointer加原始摘要可验证精确**来源片段**；权项文本包含非规范`</claim-text>`标记，权项号/段落偏移必须经版本化解析和原文回链验证后才能作为产品`claim:number`或段落定位，不能按标记猜拆。44份详细响应都有非空`claimResponses`，此计数仅证字段存在，不证权项质量、许可或全量覆盖。

用途复核：`data/enforcement/source-review/surechembl-v1/raw/surechembl-bulk-readme.txt`称SureChEMBL **bulk data content**为CC BY 4.0，同审查manifest仅核了bulk目录并把API数据许可适用范围标待核。本轮另于2026-09-27直接读取`https://chembl.gitbook.io/surechembl/faq.md`（HTTP 200，2292字节，SHA-256 `12758eb41bb52bc1bafed14ecd8a99fe884dbfedb70faa037dbce44ebd7cbfd7`），FAQ泛称SureChEMBL data为CC BY 4.0；`patent-view.md`（HTTP 200，2538字节，SHA-256 `9f79751285fbcfdb7dbdad2e3fc483ba074af7d44f4a0546cb0200c068a85140`）描述权项/说明书在视图展示。这比先前只定位bulk目录的公开声明更宽，但仍未逐项保证API所返第三方专利原文的再利用权。同期`https://www.ebi.ac.uk/about/terms-of-use`（HTTP 200，30152字节，SHA-256 `f3c148e6b91501af2a516e24edf0be61c21e2ce1b4e9f40bd834c606f1f23ae9`，Last-Modified `2026-09-27 11:24:09 GMT`）明确原始数据可能受第三方专利/版权等权利约束，使用者自行确保不侵权，特定资源许可优先。不能由FAQ一句总括代签原文逐字段再利用判断。

`data/enforcement/source-review/surechembl-v1/approval.json`仅批准另一执法隔离目录的内部开发存储/索引，明确禁止生产、再分发及执法工作流，且排除权项/摘要；不能跨数据集、字段或领域移植到本咨询API正文。当前咨询pilot自身仅`REVIEW_REQUIRED / INTERNAL_DEVELOPMENT_PILOT_ONLY`。因此本批次的产品`allowedUse`对标题、摘要、权项、说明书的QUERY/SAVE/INDEX/DISPLAY/DOWNLOAD/MODEL/SHARE均保持**未核准即拒绝**；技术上可定位的样本只用作D前来源审查，不进正式检索、用户候选或模型。当前用户允许“自行采集且允许项目内部使用的真实资料”作为**输入**，不代替第三方API/专利正文的权利凭据，也不改变旧批次的已记用途。撤回通道、现时sourceEpoch、许可期限与动态查询/分页失败能力尚无该批次的可核产品记录。首个D放行可采用另一个确有逐字段用途及权项原文许可的采集源；无需等待业务方编写Source Contract，但在权利证据未闭合前不得编码`IMP-CON-PRODUCT-SOURCE-01`。

### 2.3 首个来源候选逐项准入复核（2026-09-27）

| 候选 | 产品用途及权项/正文处理 | 查询/分页/失败 | 撤回/停用 | 原文定位与版本 | 判定 |
|---|---|---|---|---|---|
| `public-pilot-v1`（SureChEMBL API） | manifest仅`REVIEW_REQUIRED / INTERNAL_DEVELOPMENT_PILOT_ONLY`；FAQ总许可与EMBL-EBI第三方权利声明并存，未形成该批次逐字段产品许可 | 原manifest存3个真实查询响应，但只采第一页；本轮以其Q1 URL分别请求`page=1/2&itemsPerPage=2`，两次均HTTP 500、`status=INTERNAL_SERVER_ERROR`且`error_message`为空，响应SHA-256分别为`018dd092714b02153209ecfc9e47d9bb772d0099298b66591969f8374f8d3d6f`、`6a495eb936a658b0a4ab260a705fe58d61fc16e2e0ae18165f97c589f18f9e35`；不证明当前可分页或零结果语义 | 只有项目拟定的撤回规则，未有该源可核现时撤回通道/水位 | 第2.2节有原件摘要、权项/说明书片段指针和观察版方案；权项号解析未验 | 候选，不登记正式来源 |
| `cn-topic-public-pilot-v1`（SureChEMBL API） | 同一待审用途，400条不自动扩权 | manifest有主题查询页，但无本切片现时故障/撤回闭环 | 同上 | manifest明确不保存PDF、说明书全文、摘要或权项 | 不满足原文最低样本 |
| `epo-cn-pilot-v1`（EPO LOD） | manifest仍待许可审查、仅内部开发试点 | 留存CN列表0～2页；OPS需凭据，不能用公开LOD的分页冒充全文查询 | 未核现时撤回 | 30条出版元数据，manifest说明权项/摘要等不在此公开端点 | 不满足权项/正文最低样本 |

上述核查只对保存在仓库的manifest/原始响应和当次只读网络探测负责。HTTP 500探测未保存为获准数据集，也不把提供方瞬时故障推断为永久不可用；它证明**现时产品检索/分页能力未获证**。允许继续独立准备来源许可映射、解析定位方法及撤回反例，但没有逐字段用途、原文和现时能力闭合前不通过`ARC-CON-PRODUCT-SOURCE-D-01`，不编写`IMP-CON-PRODUCT-SOURCE-01`。

### 2.4 Source Engine源中立窄D设计候选（V9.239）

本切片只拆产品内核与具体来源准入，不更改第2.1节的用户查询、文献与权项结果合同。`ARC-CON-PRODUCT-SOURCE-ENGINE-D-01`可以审默认关闭的注册、检索、选定和读取机制；`ARC-CON-PRODUCT-SOURCE-D-01`仍持有每个具体来源的正向许可及能力门。引擎的测试可使用真实采集字节作解析/查询输入，但不得把待审旧包登记ACTIVE，也不得在正式路由返回它们。引擎完成不等于有任何可用来源或完整首条任务。

注册合同由`sourceId,contractVersion,sourceVersion,provenance,owner,licenseEvidenceRef/licenseEvidenceSha256,allowedFields × allowedUse,coverage,searchableFields,claim/fullTextAvailability,query/pagination/locator/withdrawalCapabilities,collectedAt,validUntil,status,sourceEpoch`组成；版本不可变，`CANDIDATE`只可查看审查元数据。ACTIVE准入必须校验具名有责批准证据与范围、可用期限、字段×用途、真实来源能力/原文样本/版本及独立现时水位；服务不从公开URL、许可总述、上传者自报或有文件自动推许可。登记/激活/撤回为分离的受控操作与不可变审计，激活不代替第三方权利；缺许可、权项处理权、撤回通道或水位时一律不ACTIVE。WITHDRAWN单调不可回到旧版本ACTIVE，重新批准必须新合同版本和更高epoch，旧结果只留历史不可当前读取。具体外部适配器仅为注册的声明能力实际提供查询/读取；原文与派生文本摘要及解析版本分别绑定，不可把URL或JSON Pointer当最终可核权项号。

源权限与任务权限须分别满足：正式JWT当前成员→确切持久Task及其`TASK_METADATA` READ Grant→另一个指向确切Task、sourceId、合同/范围版本及`SOURCE_QUERY`或`SOURCE_DISPLAY`的来源DataScope/Grant→来源字段×用途与独立sourceEpoch。`TASK_METADATA` Grant本身不授任何文献内容。来源DataScope需领域持久化与精确任务/组织绑定、单调版本/CAS及事件，不能从请求头自报或由来源注册自动生成通用权限；两段当前性任一不可达即拒绝，不退回缓存。维护责任身份可以维护登记流程，但只有权利证据/批准链才使来源可激活；不能把现有GRANTS角色视为数据许可签发人。

接口候选：受控来源登记/状态端点只接收可核合同与证据引用，不直接接收`ACTIVE=true`；`POST /v2/consultation/tasks/{taskId}/patent-searches`以用户原查询/确认范围执行实际可用字段的查询，返回实际查询、覆盖/排序方法、固定结果集ID、成员确切`sourceId+documentId+documentVersion+sourceVersion`、源失败和游标；`POST .../results/{resultId}/selection`仅从固定成员选择，不按序号或最新文献重绑；`GET .../documents/{sourceId}/{documentId}/versions/{version}/content?kind=CLAIMS|FULL_TEXT&locator=`重核DISPLAY及原始摘要/解析版后回片段和来源定位。字段、查询能力、页大小与结果冻结以实际注册合同为准，服务端参数校验、幂等/版本/事务和审计必须明细设计后才可判D；不能提前借旧V0路径。未注册/撤回/无字段许可/水位失联拒绝；真零结果、源超时/限流、部分成功、游标失效、无权项、解析漂移分别表达，不把不可用源记零命中。结果生成前后及原文返回前复核身份、Grant、Task版本、源版本/epoch；源撤回后旧固定结果不能继续用于选文献或当前成果。

本轮二遍审查待闭合：具体来源范围Grant的受控签发主体与DataScope事务；来源独立水位库的角色/跨DB PENDING恢复、撤回线性化及旧包回灌；DB或文件原件保留/恢复与查询游标稳定性；注册证据的批准来源及负向伪许可样本。它们是引擎代码前的D问题，不以“默认禁用”掩盖不完整的正式写/读合同。若仅来源适配尚无许可，可在D通过后实现引擎并保持注册表空；此时正向真实业务I/B仍NOT_RUN。

实现级候选（第二遍前须逐条反演）：(1) 来源管理员采用现有正式维护证书另授`SOURCES`精确职责；其操作是核验并登记由具名数据责任人签署或来源公开许可可核的证据，不因拥有维护证书自行产生第三方权利。注册表以主DM8保存`con4_source_contract`（不可变合同/证据摘要/有效期/能力及状态）、逐字段用途行、不可变事件；登记只得CANDIDATE。激活请求携原证据Ref、原始字节SHA-256、责任主体/日期/确切字段用途/许可期限和实际查询/原文能力证明，服务校验摘要、范围与合同一致，并审计维护者；证据不足拒绝，具体证据的权利效力仍按具体来源D判定。历史合同不能原地修改或从WITHDRAWN恢复。

(2) 独立授权库增`id4_source_watermark(sourceId,epoch,state,intentId,historySha)`；维护操作先CAS为PENDING、主库同事务提交来源状态/事件、再推进独立epoch及事件摘要为READY/WITHDRAWN，异常未知保持PENDING。启动和每次请求核主库历史摘要与独立水位；失联、PENDING、旧包回灌或摘要漂移503并停后续字节输出。撤回提交前仍可能有在途响应，读取原文及结果发布前复核同一epoch；已送出的字节不能物理追回。服务/备份账号仅最小读写，恢复账号只能在隔离恢复中核对，不允许主库备份覆盖独立现时水位。撤回后新合同须新版本并更高epoch，不重开旧指针。

(3) 领域表`con4_task_source_scope(taskId,organizationId,sourceId,contractVersion,scopeId,scopeVersion,status)`每行精确绑定当前Task版本和一个ACTIVE来源，唯一当前指针及不可变事件；维护者须同时持`SOURCES`/`GRANTS`职责，在`ProductGrantWatermark.change`的主库事务中建范围及两个精确Grant `QUERY/SOURCE_QUERY`、`DISPLAY/SOURCE_DISPLAY`，期限取Task/合同/证据最早到期。两Grant的`taskScope=taskId`、`dataScope=scopeId:scopeVersion`、`resourceType=SOURCE`、`resourceId=sourceId`、`resourceVersion=contractVersion`，不能由任意`POST /grants`绕过范围存在/现时性检查。Task自身`READ/TASK_WORK`还须独立通过；来源范围不包含其他sourceId、DOWNLOAD/MODEL或任意文献正文。源状态、Task版本或范围版本改变使旧Grant不匹配；撤销/停用现时校验使用已有独立Grant水位。

(4) 已批准的本地获准资料可由受控来源服务经`SourceDocumentPort`登记`sourceId+documentId+documentVersion+sourceVersion+rawSha256+collectedAt`、原件字节和可用片段；若用DM8长文本，必须保存原始UTF-8字节/长度/摘要与解析片段的独立版本/摘要，禁止只存经过模型改写的正文。观察版由原始摘要+采集时刻标`OBSERVED`；上游权威版原样保存。原件只在合同许可SAVE时持久化，INDEX另核；具体保留/清理/文件备份在物理D未通过时不得启用需要文件对象的来源。相同文献键、不同原始摘要拒冲突，不覆盖；欠缺原文仍可只返回有许可的元数据，不生成权项文本。

(5) 首条范围限定文本关键词，实际字段来自合同交集；受控解析查询为非空词的AND，参数化匹配，不承诺语义/向量或穷尽。响应包含方法`LEXICAL_AND/v1`及实际字段/范围、每篇命中字段与定位，排序以命中字段数降序、同级`sourceId,documentId,documentVersion`稳定排序，绝不输出伪百分分。每次检索在同一主库事务保存不可变`SearchAttempt/ResultSet/Member`及确切源epoch；页游标绑定结果集ID、下一ordinal、Task/范围/合同版本和源epoch，任一漂移拒失效，不在后页重新召回。选择事务只按结果集成员键写新焦点版本，不能按当前第N篇重绑。完整任务目标/消息与继续仍依`ARC-CON-TASK-01`，引擎不代其创建目标或宣布任务完成。

二遍反演仍必须核：(a) 现有`ProductGrants.issue`通用端点可签任意`SOURCE`字符串，须加来源范围存在校验才不绕门；(b) 两Grant创建与范围、跨库intent的提交未知和同键回执如何保持原子/可恢复；(c) 保留依据只覆盖Task元数据，不自动覆盖原文/结果，需对引擎实际保存内容选择已批准政策或限制为无正文持久化；(d) 真实来源未批准时，自动化正向测试不能用旧pilot冒充正式许可。上述任一未闭合则窄D继续进行中，设计稿不授权迁移或API。

二遍技术决议（2026-09-27，仍非完整D）：通用`POST /v2/identity/grants`及`ProductGrants.issue`一律拒绝`resourceType=SOURCE`，即使维护者有GRANTS职责；来源授权只能由专用`SourceScopeGrantService`签发，且不得对外暴露独立Grant输入。专用命令先核维护证书同时具SOURCES和GRANTS、成员/组织/Task及版本、ACTIVE合同和独立sourceEpoch、精确字段×QUERY/DISPLAY与保留期限；在`ProductGrantWatermark.change`内用**一个主库事务**CAS新增来源范围及两条精确Grant/事件/同键回执。内部Grant创建必须复核已落库范围主键/版本及合同版本，不接受请求自报scopeId、sourceEpoch或resourceVersion。任一Grant失败回滚范围、两Grant、事件和回执；外部Grant水位若未完成保持PENDING，不因HTTP重试而重放半套授权。来源撤回无需等待逐成员Grant撤销才禁止读取：先CAS独立来源水位为PENDING，主库提交WITHDRAWN与不可变事件，再推进独立水位为WITHDRAWN；源读在召回前、发布结果前和返回原文字节前核状态/epoch/摘要，PENDING或源水位失联503，旧结果/Grant虽留历史但不具访问效力。未知提交由受控恢复命令对比主库事件摘要与独立意图，只能证明未提交才释放旧READY，证明已提交才推进新高水位，其他情形继续PENDING并人工隔离核查；回灌主库旧历史与较高独立epoch不一致时拒启动/请求。候选CANDIDATE撤回不具ACTIVE访问，仅用第2.5节主库CAS，不假装它已有独立源水位。

结果持久化另用部署时具责任来源/版本/摘要/最大期限的`product.source.results.retention`，不能借Task元数据保留依据；无批准值禁止创建SearchAttempt/ResultSet。原件字节另需逐字段SAVE、具体来源许可和原件保留/恢复配置，任何一个缺失不得持久化原文，也不得声称FullText/Claims能力；仅远程读取也需DISPLAY和可核重取定位。分页只翻同一固定结果集，游标是主库生成的不可猜测随机键并绑定成员、Task版本/来源范围版本/合同版本/epoch及到期时刻，后页不得重新查询或扩大范围，漂移返回失效而不是空页。此决议仍缺来源激活责任/证据核验的可执行合同、结果表与任务目标版本的逐字段迁移设计，以及受控恢复的物理双库反例；引擎D继续进行中，不能写引擎API/迁移。

Task前置反查：现有正式`con3_task`仅有`AWAITING_GOAL`空目标元数据与`TASK_METADATA`范围，docs/56第2/4/8节要求搜索输入引用同一目标的确认版、消息、范围和版本。`POST .../patent-searches`不得在空目标Task上直接创建结果集，亦不得把query临时写作目标而不保留消息/事实版本。首个可检索的Task目标与`SearchInputRevision`必须由`ARC-CON-TASK-01`的最终D（或该任务按治理批准的确切首目标切片）先固定及实现，并有到期/纠正/取消时的版本失效规则；引擎读其可信领域端口，不从请求体假造Task状态。此为正式搜索路由的真实前置，不冻结第2.5节候选登记，也不要求具体第三方来源许可才能完成Task目标设计。

准入决议候选（仍须二遍核验）：现有正式维护证书的精确`SOURCES`职责可以提交并审计来源准入请求，但此职责仅表示内部操作权限，不产生第三方内容权利。准入包固定独立来源权利原件/授权证明的不可变存放引用、原始字节SHA-256、权利主体/责任方与可核签署或公开来源、日期和期限、逐字段×QUERY/SAVE/INDEX/DISPLAY/DOWNLOAD/MODEL/SHARE用途、地域/时间/文献范围、撤回渠道与停止使用语义；维护者逐项记录证据与候选声明的对应/限制/日期，不能以“网页可见”、自填布尔值或自签字符串代替来源许可。能力包需实际请求/响应摘要、查询/分页/限流/超时与失败记录、至少一篇真实文献的稳定标识/版本和权项/正文可核原文定位；无权项许可的源可只批准元数据能力，不伪称权项可用。服务从受控不可变证据存放处重新取字节并核摘要、期限/字段/用途/能力交集及维护身份现时性，任一不可得保持CANDIDATE；法定权利实质不由字符串格式自动证明。实际具名维护证书与逐源权利资料是准入I输入，现有pilot没有这些资料，不能据本设计激活。

状态/水位写合同候选：`con4_source_contract`历史声明不可改；另有唯一当前指针`con4_source_current(sourceId,contractVersion,rowVersion,status,sourceEpoch,decisionId)`与不可变`con4_source_admission`（决议主体、授权epoch、逐字段许可/期限、证据引用/原始摘要/能力包摘要、签署时刻）和`con4_source_event`。只准`CANDIDATE→ACTIVE`且精确CAS版本，或者`CANDIDATE|ACTIVE→WITHDRAWN`，不允许同合同重新激活；后续新版本必须更高源epoch。激活与ACTIVE撤回均先在独立授权库CAS置`id4_source_watermark`为PENDING，主库在同事务写指针/决议/事件并锁来源键，提交后独立库推进READY或WITHDRAWN及历史摘要。独立水位初次建于正式来源部署流程，不从候选自动启用；启动、召回前、结果发布前、原文返回前核独立epoch/摘要和主库当前指针；未知提交保持PENDING直至受控比较事件链恢复。旧主库快照不能降低独立epoch，旧合同/结果永不因数据库恢复而重活。

查询与版本存储候选：`con4_search_attempt`保存taskId、可信目标/inputRevision、taskVersion、用户原query、确认范围、实际字段/源/方法版本、发起主体、状态及每源失败；`con4_result_set`保存不可变attemptId、生成时间、截断/可靠总数状态、各源合同版本与sourceEpoch快照、排序方法和结果摘要；`con4_result_member`按(resultSetId,ordinal)固定sourceId/documentId/documentVersion/sourceVersion、命中字段/证据定位及原片段摘要，不存未经SAVE授权的原文；`con4_result_cursor`存随机不可猜键、结果集/nextOrdinal、主体、Task/Input/Scope版本、合同/epoch摘要及有效期。当前Task目标或任何来源版本/epoch变化返回CURSOR_STALE，绝不静默重查或按最新第N篇选择。选择命令只引用确切(resultSetId,memberId,expectedFocusVersion)，创建新焦点版本并保留旧集合。每次检索按已获准字段和实际能力执行参数化Unicode字面词AND（特殊通配符按字面转义），排序为命中字段数降序及确切文献键稳定同级；实际范围/方法`LEXICAL_AND/v1`和截断/各源错误随响应保存，零结果仅在已执行的获准源均成功且真零命中时表达。只返回当前可用合同与授权交集中的成员；用户搜索与候选元数据管理权限互不继承。

原文读取候选：`SourceDocumentPort`输出确切来源/文献/上游版或标明OBSERVED的采集版、原始字节长度/SHA-256、采集时刻、解析版和质量、权项号或原页/段/坐标及回链原件定位；持久化仅在SAVE用途与独立原件保留配置齐全时进行，索引另需INDEX，远程现时读取另需DISPLAY及真实适配器。片段索引含原件摘要、解析摘要/版本和字节区间/权项号，重取后摘要漂移、定位不能回链或权项实际不可用时返回明确不可用，不用摘要补权项。读前与响应发送前再次核JWT/任务+来源Grant、独立Grant及源水位、字段DISPLAY、文献版、保留到期；撤回后旧证据只能按获准审计边界保存，不再作为当前正文输出。

引擎实施接口复核（源中立，不批准具体源）：

| 命令/端口 | 可信输入、原子效果与失败关闭 |
|---|---|
| `POST /v2/consultation/source-contracts/candidates/{sourceId}/{contractVersion}/admission` | TLS客户端证书、当前精确SOURCES职责、expectedVersion、逐字段决议及独立来源权利证据对象引用/原始摘要/签署时间/有效期/能力探针摘要、Idempotency-Key。服务重取受控证据字节验摘要及不可变存放、用途/字段/覆盖交集、期限与候选声明，逐条件留审核轨迹；全满足才CAS激活，缺任一项保持CANDIDATE并返回明确缺项。现时维护身份/证据库/独立源水位不可用503，不以注册声明、URL公开或维护者自签文字代替许可。 |
| `POST .../withdraw` | 当前维护证书SOURCES职责接收可核权利方撤回渠道事件并记录来源/时间/摘要，或以受控手工操作附确切合同/版本/原因/Idempotency-Key；ACTIVE撤回先PENDING源水位、主库CAS和事件、再完成独立水位。失联时拒绝后续结果/字节，重复同键复核当前WITHDRAWN，异键/旧版409。候选撤回继续走第2.5节维护端。 |
| `POST /v2/consultation/tasks/{taskId}/patent-searches` | 正式JWT、`Idempotency-Key`、expectedTaskVersion、可信`SearchInputRevision`引用和用户确认的query/范围；领域`SearchContextPort.load(taskId,revision)`只返回已持久化当前目标/组织/输入版/范围/有效期/可搜索状态，当前空目标`AWAITING_GOAL`必须拒绝。先独立核Task READ和逐来源SOURCE_QUERY Grant、ACTIVE/epoch及字段QUERY/INDEX；仅调用登记的真实适配器。主库同事务固定Attempt/Set/Members/源失败与回执；实际结果没有SAVE许可时只保留允许的最小元数据/摘要，未批准结果保留配置拒创建。响应返回确切attempt/resultSet、methodVersion、实际源/字段/覆盖/截断、成员与失败分类；零可用源为SOURCE_UNAVAILABLE，不记零命中。 |
| `GET .../patent-searches/{searchId}/results?cursor=` | 游标只推进固定成员，不调用适配器；重核当前成员/Task输入/来源范围/合同及双水位，任何版本漂移CURSOR_STALE且不泄漏下一页。页面大小受部署资源上限约束，记录实际截断，不补固定候选或伪总数。 |
| `POST .../results/{resultSetId}/selection` | 输入memberId与expectedFocusVersion；成员必须属于该不可变集合且来源仍可用，领域焦点CAS增版，不能从ordinal或新检索结果代选。 |
| `GET .../documents/{sourceId}/{documentId}/versions/{version}/content` | 输入kind=CLAIMS/FULL_TEXT及受控locator；当前DISPLAY Grant、字段许可、真实能力、文献/原件/解析版及原始摘要均须吻合。返回确切片段/原件摘要/解析版/权项号或页段定位和受限缺口；源撤回、漂移、超期或无原文不得返回旧缓存字节。 |

所有HTTP失败均按可核事实分层：401缺正式登录，403确切权限/用途拒绝，404不暴露跨范围资源，409同键异载荷/版本并发，410已撤回或过期且允许暴露该事实，422能力或定位不具备，429上游限流并标源失败，503授权/水位/证据存放不可达或查询上游不可用；多源仅实际部分成功时返回PARTIAL及逐源错误，不能把全部失败标零结果。主库搜索事务不得持有外部网络调用期间的锁：先取可信快照/执行适配器，再重核Task/Grant/源epoch，在短事务中写固定集合及回执，提交后返回前再次重核；中途源撤回则丢弃或标失效未发布结果，不能让旧快照通过。幂等键绑定actor、task、输入版、query/范围与动作摘要，同键同载荷只有仍现时才回原结果，异载荷409；网络调用成功但落库失败不返回候选，重试可重新执行并记录独立attempt。查询、结果和原文的保存/删除/备份分别按已批准部署保留依据执行，缺配置启动或命令失败关闭，不填默认天数。

引擎D第二遍必须实测的反例矩阵：无证书登记/无登录搜索、仅维护人自填或自签许可而无独立来源权利原件、仅宽泛许可但无字段权项用途、宣称能力与探针不符、通用Grant签SOURCE、跨Task/跨来源范围、旧Task/Input/Grant或源epoch、源水位PENDING/丢失/旧包回灌、注册或准入双提交未知、搜索中撤回、两源一超时一成功、全源失败与真零区分、下一页漂移、重新排序后旧成员选择、相同文献键异原件摘要、权项缺失/解析漂移/无DISPLAY、原件或结果保留缺值。自动化可使用隔离结构输入核算法与故障语义，但不将它们声明为真实来源正向I/B；ACTIVE与真实权项正向只在具体来源D及实际证据通过后验。

持久化/跨库二遍候选（待物理反例核验）：主库`con4_source_contract`沿用第2.5节的不可变声明与当前状态/行版本；`con4_source_admission`以decisionId主键、(sourceId,contractVersion)唯一批准关系存精确逐字段用途/覆盖/期限、独立权利与能力证据Ref/SHA-256、证据责任方/采集时间、维护证书指纹/事件和批准范围摘要。字段用途以子表`con4_allowed_field_use(decisionId,field,useCode,validUntil)`唯一键持久，不从声明JSON直接读作许可；撤回不删除此历史。`con4_source_event`继续追加ADMIT/WITHDRAW事件，包含来源epoch及前后行版本。每个来源只允许一个ACTIVE当前指针；历史版本不可重开，新合同版本用新decision和高epoch。候选登记/候选撤回不进入源水位摘要，因为从未可供使用；独立摘要只覆盖该sourceId的准入决议、ACTIVE/WITHDRAWN指针及关联事件，按固定字段顺序/长度前缀编码SHA-256，杜绝主库旧快照与现时水位不一致时重用旧合同。

独立授权库`id4_source_watermark(sourceId,epoch,state,intentId,historySha)`键为sourceId，状态仅READY/PENDING/WITHDRAWN，epoch非负、PENDING必须有intentId，其余必须为空。首次来源激活的epoch从0→1，不能仅因登记候选创建READY可用状态；先CAS独立行到PENDING，主库短事务CAS状态并提交准入/字段用途/事件，后CAS独立epoch和摘要到READY。失败时，若主库原摘要未变才可释放PENDING回原状态；证明主库已提交且新摘要等于预期才可受控推进，其他未知情形保持PENDING，所有读取503。ACTIVE撤回PENDING→主库WITHDRAWN→独立WITHDRAWN且epoch+1；旧主库恢复不会降低独立epoch。状态比较必须在两个独立连接/实例上进行，服务与备份角色分别最小权限，独立库不得包含在主库回灌卷内；缺表、权限或水位失败，产品启动与请求都失败关闭。一个来源的PENDING不虚构另一个来源的零结果；多源实际成功/失败分别留痕，依任务授权与目标范围决定是否可返回PARTIAL。

领域`con4_task_source_scope`以(scopeId,scopeVersion)为版键，外键绑定(taskId,organizationId)及(sourceId,contractVersion,decisionId)，存Task版本、sourceEpoch和ACTIVE/REVOKED；唯一当前指针(taskId,sourceId)和不可变范围事件。专用来源授权命令要求确切task/org成员、当前ACTIVE合同/准入、Task有效期/来源许可期限/部署保留上界交集，主库一个事务中创建范围与QUERY/SOURCE_QUERY及DISPLAY/SOURCE_DISPLAY两条最小Grant及回执；查询请求仍分别核Task自身READ/TASK_WORK和来源Grant，不能用Task元数据DataScope替代来源scope。`ProductGrants.issue`及其通用HTTP端点拒绝`resourceType=SOURCE`，专用内部发行校验现时scope和决议后才签两Grant；父事务外由`ProductGrantWatermark.change`保护，未知提交保持独立Grant水位PENDING。任务/范围版变更须撤销或重新签精确授权，老Grant历史不删；跨任务、跨源及下载/入模不由这两Grant隐含授予。

结果表键与保留：`con4_search_attempt(attemptId,taskId,inputRevision,taskVersion,actor,requestSha,originalQuery,actualQuery,scopeDigest,methodVersion,status,createdAt,expiresAt,retentionRef/version/evidenceSha)`；`con4_search_source(attemptId,sourceId,contractVersion,sourceEpoch,coverage,status,errorCode)`；`con4_result_set(resultSetId,attemptId,orderingVersion,truncated,totalReliability,digest)`；`con4_result_member(resultSetId,memberId,ordinal,sourceId,documentId,documentVersion,sourceVersion,rawSha,locatorRef,matchFields,matchDigest)`有唯一(resultSetId,ordinal)和文献精确版索引；`con4_result_cursor(cursorId,resultSetId,nextOrdinal,subjectId,taskVersion,inputRevision,scopeDigest,sourceEpochDigest,expiresAt)`。游标无外部载荷解释，仅服务端随机标识；重启后在主库读取，期限取Task/Grant/许可/结果保留最早到期，过期/撤回不可续页。保存query与命中元数据需独立结果保留政策，第三方片段若无SAVE仅存摘要/定位不存文本；`con4_document`原始字节与解析版/定位仅在SAVE及原件保留/恢复配置齐全时创建，是否建索引另核INDEX，不把上游URL当本地原件。上述表与唯一/FK/期限约束、原子回执和正逆迁移仍要在实现前形成可执行迁移复审，不因概念字典就判D完成。

第三遍接口/事务反查（2026-09-27；当前工作树V036/V034及正式权限代码）：V036的`con4_source_event.operation`仅允许REGISTER/WITHDRAW，故ACTIVE审批及撤回不得伪装为旧候选撤回事件；后续正向迁移须追加单独的准入决议/事件和唯一当前指针，旧候选历史与回执保持不变。V034的`con3_task.state`仅允许`AWAITING_GOAL`且无目标正文或输入版；`SearchContextPort`必须只接受领域未来已持久化的确认目标/输入版，缺口时返回`GOAL_NOT_READY`而不产生Attempt、游标或零结果。当前`ProductConsultationAccess`仅核`TASK_WORK`，`ProductGrants.issue`仍能接收任意`resourceType`；引擎实现须先拒绝通用SOURCE签发，再由内部专用服务在Grant水位PENDING内同一主库事务创建来源scope和QUERY/DISPLAY两Grant，检索/阅读分别以`ProductAccessPolicy`的`SOURCE_QUERY/SOURCE_DISPLAY`及有epoch的`SourceUse`判定，不把Task READ授权当来源授权。这三个既有代码事实不因设计文稿自动变成已实现。

最终来源使用政策候选：`requestedUses`只是登记申请；`allowedUse`只来自有责任来源、版本、原始摘要、期限与逐字段范围的准入决议，取具体资料权利证据、实际技术能力、组织数据处理政策及成员Task/Source Grant的交集，缺一项默认拒绝。项目团队定义QUERY/SAVE/INDEX/DISPLAY/DOWNLOAD/MODEL/SHARE分别的最小动作和失败码，不把维护者证书或用户本轮指令当第三方API原文权利。采集批次可做原字节/解析/定位和本地动态查询的合同核验；正式用户请求只读ACTIVE且用途交集满足的文献。撤回能力由产品控制：具名SOURCES维护者对确切版本显式撤回、权利方撤回事件经受控入口核验、到期定时转不可用；独立sourceEpoch在任一停用开始前PENDING，产品请求即时失败关闭。若外部源不能提供机器撤回接口，登记`withdrawalCapability=LOCAL_STOP`及实际核验/到期时点，不能宣称上游实时撤回；无法验证上游继续许可时保持候选或停止使用。EPO原件摘要漂移的批次隔离，不影响其他sourceId的独立水位和合法准入，但不作为查询零命中。

结果与双库实施复审所需不变量：独立sourceEpoch覆盖准入/撤回事件摘要而非原文索引；主库锁同一sourceId当前指针，外库先PENDING、主库事务提交决议与事件、外库后推进epoch和摘要。任一步未知提交只可对比前后事件摘要与intent做受控恢复，不确定保持PENDING；旧主库快照、旧候选状态、旧Grant、旧结果均不能覆盖较新独立水位。结果保留配置只准具责任方ref/version/evidenceSha256/maxAge且按任务/成员Grant/字段许可最早期限缩短；query与命中元数据无保存依据时拒绝产生持久集合，原文字节另受SAVE/原件保留/文件备份合同控制。清理先停止当前读取，再按引用和到期清理结果、游标、原件，审计留存与备份轮换逐用途证明；恢复隔离实例须比对Task/Input/Source/Grant/结果成员/原件摘要及两个独立水位，不得用主库备份降低水位。物理故障反例至少包含主库提交前/后断电、外库推进失败、旧主库回灌、原件缺失、清理后旧游标及同时撤回和分页；代码/DM8隔离I完成前这些是设计验例，不是已经运行的恢复证据。

本次D复判仍有两个实施前缺口：(1) 确认目标/`SearchInputRevision`的最小持久任务切片尚未过自己的D，当前V034空任务不能支持正式查询；(2) 上述新增表的逐约束迁移、索引、回退以及独立库部署/权限与双库恢复反例尚未完成二遍可执行复审。因此`ARC-CON-PRODUCT-SOURCE-ENGINE-D-01`暂不通过；不得先写正式检索迁移/API。具体SureChEMBL/CN内容用途的证据仍由`ARC-CON-PRODUCT-SOURCE-D-01`逐字段判定，与源中立引擎D分开。

迁移实施边界（第三遍设计输入，尚非迁移文件）：V036既有候选三表及V034任务六表保持不改历史；后续主库迁移新增`source_current`、`source_admission`、`allowed_field_use`、`source_authority_event/request`、`task_source_scope/current/event`、`search_attempt/source/result_set/member/cursor/request`和在SAVE许可下的`document/raw_object/parsed_span`。主键分别为来源ID当前指针、决议ID、(决议ID,字段,用途)、事件/请求ID、(scopeId,version)及(taskId,sourceId)当前指针、各结果UUID和(resultSetId,memberId)；所有文献成员以来源ID/合同版/文献ID/观察或上游文献版/原始摘要绑定，不能只以文献号去重。FK锁定候选合同、准入决议、任务与组织、scope、attempt/set及文献原件；枚举CHECK限定CANDIDATE/ACTIVE/WITHDRAWN、QUERY/DISPLAY等动作、非负epoch、正版本和有效期，唯一索引阻双ACTIVE、同幂等键异载荷、同集合重复ordinal。DM8不支持的部分唯一谓词以单行`source_current`/`task_source_current`主键CAS实现，不能依赖应用进程内锁。逆迁移只允许隔离空表/无ACTIVE及无结果引用时执行；有业务行或外库高水位即拒回滚，部署恢复不通过DROP降低epoch。

独立授权库另新增`source_watermark(sourceId PK,epoch,state,intentId,historySha)`及不可变intent审计；CHECK为READY/WITHDRAWN时intent空、PENDING时intent非空，epoch单调，主库不能直接更新它。首次准入必须在独立库占PENDING后对主库以sourceId当前指针锁定，提交决议/用途/事件/请求回执，再以同intent和旧epoch CAS完成新摘要；撤回同序。`source_current`读当前合同/决议/epoch，任何主库事件摘要与独立库不一致即503。单源PENDING只隔离该源，仍须按实际成功源/授权范围决定PARTIAL；若整个授权范围无可用源，返回SOURCE_UNAVAILABLE。主库事务之外调用真实来源端口；结果落库短事务再次检查Task/Input、两类Grant、来源epoch/字段用途；提交后发送前复核。断连同键重放必须先核现时性，不能返已撤回成员；异载荷409，未知提交保持PENDING或明确可核回执，不能重放半套范围/Grant。

可信输入端口的最小合同为`taskId,organizationId,taskVersion,goalId,goalRevision,inputRevision,confirmedBy,confirmedAt,queryText,requestedFields,sourceScope,validUntil,state`，由任务领域读取其不可变确认事件及当前指针并核用户确认、撤销/纠正、到期和当前权限；来源引擎只消费快照，不自行写Goal/Message或把搜索query当确认目标。`AWAITING_GOAL`、旧revision、未确认或取消一律在调用真实来源之前拒绝。结果集合只记录该快照摘要，Task更版并非无条件删除历史，但旧集合的续页/选择/当前成果必须重核受影响目标/范围版本；历史审计可按批准保留读取而不能重用为当前依据。

双库恢复设计反例序列：S0两外库水位READY且与主库摘要一致；S1独立源PENDING而主库未提交，隔离恢复只在证明旧摘要和旧指针均一致后释放；S2主库提交但外库仍PENDING，核新事件及intent后仅前进epoch；S3外库READY后旧主库回灌，历史摘要/epoch不符拒启动；S4 Grant水位PENDING或旧包回灌，同样拒请求；S5原件缺失/摘要漂移时对应正文与结果成员不可用，不用缓存拼补；S6撤回与分页并发时返回前源epoch变化拒整个受影响页。恢复演练必须用实际独立主库/授权库和文件副本记录前后摘要、请求副作用及恢复操作者，单库H2不能计物理I。上述是D设计验例，执行证据须由后续I提供。

可执行迁移前的约束复核：当前V036不允许ADMIT事件，后续迁移不改写旧REGISTER/WITHDRAW历史，而以新`con4_source_authority_event`保存ADMIT/ACTIVE_WITHDRAW/EXPIRE及前后epoch、决议ID、intent和请求摘要；`con4_source_admission`用决议ID主键、(sourceId,contractVersion)唯一、合同复合FK，证据Ref/SHA、字段用途摘要、责任/维护身份及有效期均非空；用途子表以(决议ID,字段,用途)为键并FK决议。`con4_source_current`以sourceId唯一、复合FK合同及决议，持rowVersion、status、epoch和不可变事件摘要；不能用仅对ACTIVE行的部分唯一索引，因为DM8兼容性未证。sourceId单行CAS防止两个版本并行激活；CHECK限定状态、版本、epoch和到期，跨表一致性必须在短事务锁当前行并用服务重核。无旧CURRENT但已有候选时先按sourceId锁候选合同；多版本竞争以新CURRENT主键冲突和同事务回滚收敛，绝不最后写胜出。独立库`id4_source_watermark`仅由服务/恢复最小权限账号更新，PK sourceId、CHECK状态READY/PENDING/WITHDRAWN及intent空值关系、epoch非负；独立数据库不能用主库迁移连接误建此表。

来源范围及结果迁移约束：`con4_task_source_scope`用(scopeId,version)主键并有(taskId,organizationId)复合FK、(sourceId,contractVersion)复合FK和decisionId FK，保存Task版本、sourceEpoch、status/expiry；`con4_task_source_current(taskId,sourceId)`唯一指向当前scope版，范围事件不可变。旧版Grant仍在历史，但因current指针/版本不匹配不再可用。`con4_search_attempt` FK确切Task，保存任务领域签出的不可变inputRevision及摘要；目前V034无输入表，不能伪造一个不存在的FK，写入与读取均由`SearchContextPort`核当前版，未来任务迁移的物理FK须单独复核。`con4_search_source` FK attempt/准入、`con4_result_set` FK attempt、`con4_result_member` FK set并唯一(setId,ordinal)及(setId,sourceId,documentId,documentVersion)，`con4_result_cursor` FK set并唯一随机键、`con4_search_request`唯一幂等键/请求摘要/结果引用；结果摘要在成员固定后同事务计算，不允许游标指向未提交集合。若原件持久化，`con4_document`以(sourceId,documentId,documentVersion)唯一并存rawSha，同文献版异摘要冲突，原件BLOB/对象Ref、字节长度/SHA和解析片段版本/摘要独立，片段的byteStart/byteEnd在原件长度内并可重取校验。现有主库数据库不支持跨独立水位FK，靠启动/请求双向摘要检查而非假外键。正逆迁移须先在隔离DM8以空库和有V034/V036行两种情况验证；逆迁移只在无ACTIVE、无Grant/结果/文件引用且独立epoch未前进时允许，恢复命令不是逆迁移。

引擎窄D与其他D的边界复判：`SearchContextPort`的字段、版本/失效和GOAL_NOT_READY已在本节固定，**其领域写入实现**归`ARC-CON-TASK-01`，未交付时正式搜索不可执行，但不迫使来源引擎虚造目标。具体SureChEMBL/CN资料的权项产品用途归`ARC-CON-PRODUCT-SOURCE-D-01`，D未通过时不得登记ACTIVE；源中立引擎的规则、默认拒绝与真实采集原件校验可独立实施。双库故障场景在本节已定义为D验例，实际隔离DM8/备份/恢复证据在IMP/I采集，不把未来测试结果预记为已通过。若SQL兼容性、跨库CAS或上述版本引用在实现审查中反演失败，退回本D而非加临时旁路。

源中立窄D结论（2026-09-27，项目开发团队二遍反演）：本节的可信输入端口、V034/V036兼容的表键及迁移边界、来源范围/Grant专用原子签发、双独立水位、字段用途、实际查询/固定分页、确切选择、原文定位/摘要、失效与失败码、分离保留及S0～S6恢复反例构成`ARC-CON-PRODUCT-SOURCE-ENGINE-D-01`的可执行设计。**只通过源中立引擎D**，实现顺序先通用SOURCE Grant拒签与水位/准入、再Task来源范围、真实资料适配、结果/原文；每段在正式代码/路由及隔离DM8验证，发现合同无法实现退回D。未实现的Task确认目标必须返回GOAL_NOT_READY，未获准来源必须保持CANDIDATE且SOURCE_UNAVAILABLE，EPO摘要漂移包不可登记正文；不以默认拒绝称已完成产品检索。具体来源`ARC-CON-PRODUCT-SOURCE-D-01`、产品用途实际证据、物理恢复I、专业效果/B均**未通过**，不能凭此窄D输出正式候选或权项。

采集真实性/准入状态补充（2026-09-28，源中立D内实施反演）：`REAL`是有原始来源、采集时间和原始字节的事实分类，登记到`con4_source_contract`时仍是`CANDIDATE`，不代表内容摘要已核。`VERIFIED`由单独的不可变`con4_source_verification`记录表示：主键verificationId、候选(sourceId,contractVersion)唯一、sourceVersion、候选行版、受控manifest Ref/SHA、records SHA、原件清单SHA/数量、核验者、核验时刻；必须读取受控原件并逐文件校验，且核版本/文献键/定位，不从客户端布尔值推出。候选撤回、版本漂移或原件失效时此历史记录不再具有当前性。只有具体来源权利/能力证据及有责审批独立通过，才能依据同一verificationId、精确字段×用途和当前候选版本写V037准入决议并推进独立sourceEpoch至`ACTIVE`。V036的CANDIDATE/ACTIVE/WITHDRAWN仍是使用状态，VERIFIED是准入前证明关系而非可检索状态；查询只认ACTIVE+双水位+现时Grant。验例至少有未核原件、原件漂移、重放/异载荷、候选撤回、核验完成但无用途许可，均不得产生ACTIVE或查询成员。此为开发团队技术设计，不对第三方原文许可作结论；具体来源D状态不变。

双库恢复实施反演（2026-09-28）：V037来源权威事件须同时保存独立库PENDING的确切`intentId`和变更前来源历史摘要`beforeHistorySha`，不可只记前后epoch；独立库intent与主库最后事件、旧摘要、递增epoch及当前完整摘要必须逐项一致才准将S2推进READY/WITHDRAWN。S1仅在主库旧指针/摘要完全未变时释放旧READY/WITHDRAWN（首次准入未落库则删除零epoch占位）；任一证据不一致保持PENDING，不能通过重试、旧主库回灌或DROP迁移降epoch。此绑定是既有S0～S6恢复合同的可执行字段补足，不增来源许可或业务状态。

实施反演修正（2026-09-27，覆盖上文第(3)项的`resourceVersion=contractVersion`写法）：正式`ProductGrants.Scope.resourceVersion`和`ProductAccessPolicy.Resource.version`均为`long`，而来源`contractVersion`为字符串，不能互赋。来源Grant的数值`resourceVersion`固定为`con4_source_current.row_version`，来源scope/准入决议继续精确保存字符串`contractVersion`、sourceEpoch和decisionId；`SourceUse(Source(id,version))`中的version为字符串合同版。专用签发在同一事务读取现时source current及scope，并将数值行版放入两Grant；请求判定必须同时比对Grant数值行版、scope字符串合同版/decisionId、独立sourceEpoch和SourceUse，不从客户端传任一版本。撤回或新合同使source current行版递增，旧Grant即时不匹配，即使旧字符串合同仍留历史。此修正是类型合同的必要闭合，不扩大既定权限范围；专用签发实现前须用C验证错行版/错合同版/错epoch均拒绝。

跨Task基数修正（2026-09-27，进一步覆盖本节旧`resourceType=SOURCE/resourceId=sourceId`和上一段的Grant数值版映射）：现有`id2_current_epoch`唯一键不含taskScope，直接用sourceId会使同一成员同组织的两个Task来源授权相互挤占。正式授权资源固定`resourceType=SOURCE_SCOPE,resourceId=scopeId,resourceVersion=scopeVersion`，`taskScope=taskId,dataScope=scopeId:scopeVersion`；来源scope当前指针以(taskId,sourceId)唯一，行内保存sourceId、字符串contractVersion、decisionId、数值sourceRowVersion、独立sourceEpoch和Task版本。`ProductAccessPolicy.Request.resource`按确切SOURCE_SCOPE构造，`Request.source/SourceUse`另绑定sourceId及字符串合同版和sourceEpoch；两段同时匹配才允许QUERY/DISPLAY。通用Grant入口同时拒签SOURCE和SOURCE_SCOPE，专用签发只能用持久scope/current/来源水位生成两Grant。来源current行版改变即使scope未换版，也因scope保存的sourceRowVersion与现时不符而拒绝；新范围版才可重授。不同Task有不同scopeId，当前Grant键不碰撞。一个Task来源scope可给多个具名成员分别签最小Grant，增加成员不替换已有scope；首建scope与首成员两Grant仍同一主库事务。跨Task同源授权及撤回一方不影响另一方的C为接路由前必验。此段是本D最新现行版本，前述旧资源映射只保留反演历史，不用于实施。

真实采集输入的只读查询探针（非Source Engine产品请求，亦非ACTIVE准入）：直接解析已核`records.json`，在每条标题/摘要/权项摘录上以大小写无关的Unicode字面包含实施全部词AND，再按文献ID稳定排序和5项切页。`public-pilot-v1` 44条中`binder AND coating`命中20条，前两页分别从`CN-108276937-A`和`CN-113178547-A`起；`cn-topic-public-pilot-v1` 400条中`人工智能`命中56条，前两页分别从`CN-107516134-A`和`CN-109409532-B`起。输入、命中与分页均来自实际文件而非固定候选；此探针未执行正式Task/Source Grant、结果事务、许可、撤回或原文定位，不得标为正式检索C/I或产品零结果语义。它验证本地采集语料确有动态查询/分页输入，不替代正式引擎。

来源Scope签发窄修正（2026-09-28）：第2.4节“两Grant同事务”仅适用于同一现时决议同时允许QUERY和DISPLAY的来源。若决议只允许QUERY，首建Scope和成员QUERY Grant须同事务，DISPLAY Grant不签发且回执中为空；正文读取仍须独立DISPLAY用途与Grant，不能从QUERY推出。两种回执重放均核已签Grant的现时epoch、成员、期限和来源水位，旧成对回执保持原语义。此修正落实最小授权及逐字段用途，不扩张任何已生效决议。

### 2.5 Source Registry候选登记窄D（V9.240）

这是最终注册表的可独立交付部分，不生成可检索来源。`ARC-CON-PRODUCT-SOURCE-REGISTRY-D-01`只准维护证书的`SOURCES`职责登记不可变候选及撤回候选，任何候选均不可变ACTIVE，查询引擎只能取ACTIVE并且必须另核独立来源现时水位/用途和任务来源范围。缺本切片不影响既有Task元数据GET；有本切片也不能对用户返回专利候选。当前单一维护主体证书可在部署manifest选择授`SOURCES`职责，源码不得默认补角色，历史已部署主体不自动扩权；新增职责仅准登记/撤回，不是来源权利的签署者。

`POST /v2/consultation/source-contracts/candidates`需TLS客户端证书、精确`SOURCES`职责及`Idempotency-Key`，输入`sourceId,contractVersion,sourceVersion,provenance,owner,licenseEvidenceRef,licenseEvidenceSha256,requestedFields,requestedUses,searchableFields,claimsAvailability,fullTextAvailability,queryCapability,paginationCapability,withdrawalCapability,collectedAt,validUntil`；这些是**待核声明**，无ACTIVE/approved字段。`requestedUses`为结构化字段×用途矩阵，值只能从第2节枚举选择，不允许通配所有字段/用途。正文、个人数据、URL下载和模型请求均不进入此命令。服务只验证结构、摘要、日期、声明范围和重放一致性，不能由格式验证得出合法许可。稳定`sourceId+contractVersion`唯一，同键同摘要返回同一候选回执，异摘要409；同源版本冲突409，不覆盖历史。维护主体失效/证书不匹配403、缺配置/DB不可用503、非法格式400。`GET /v2/consultation/source-contracts/candidates/{sourceId}/{contractVersion}`限同职责，返回待核范围与证据Ref/摘要，不返回原件；`POST .../withdraw`附expectedVersion与幂等键，仅CANDIDATE→WITHDRAWN，重复同键返回历史回执，错版409；不能从WITHDRAWN反激活或重新登记同一版本。

主DM8 `con4_source_contract`按`(source_id,contract_version)`主键保存声明JSON的规范序列化摘要、证据Ref/摘要、时间、状态`CANDIDATE|ACTIVE|WITHDRAWN`、行版本；`con4_source_event`追加`REGISTER|WITHDRAW`及操作者证书指纹、请求摘要、前后版本、时间；`con4_source_request`唯一幂等键及响应引用。当前服务不提供任何ACTIVE转换，后续引擎D必须在同表上增加带批准证据/来源水位的激活事务，不另建并行注册表。写入及审计/回执一主库事务；回执重放先核当前维护身份及候选版本，撤回后旧登记回执不得看作当前可用。索引仅注册键和状态，不采文献正文，不推保留周期；候选注册元数据使用部署时明确批准的`product.source.registry.retention`依据ref/version/evidenceSha256/maxAge，缺配置拒启动，命令期限不得超上限。历史审计到期与备份轮换仍属恢复D，当前窄切片不自动清理或声称物理恢复通过。

反例：无证书/缺SOURCES、无可信维护人、空用途或字段、通配、证据摘要格式错、过去期限、同键异载荷、同源版本不同载荷、并行登记、撤回后登记重放、撤回错CAS、数据库事务失败无孤儿事件/回执、重启后查询一致。隔离H2 C验证控制逻辑，临时真实DM8 schema做正逆迁移/事务与重启I；实际部署有责证书及批准保留参数另证。本窄D不含具体来源权利、ACTIVE批准、动态查询、Task来源范围或原文读，不可用完成状态掩盖第2.4节引擎剩余D。

二遍实施约束（CON-02/04/12）：本切片只持久化来源声明的**待核元数据**，不持久化许可原件、专利正文、查询结果或个人资料，因此Task元数据保留依据不被挪用；候选独立保留配置的ref/version/原始证据SHA-256/maxAge必须是部署输入且无默认值，`validUntil`不得越过登记时刻加maxAge，过期候选不能重放为可用状态。`sourceId+contractVersion`与`sourceId+sourceVersion`各自唯一，版本均为非空稳定标识；正文能力是声明，不是已验能力。字段与用途枚举、覆盖和可查询字段的交集由结构校验固定，许可未知依旧是UNKNOWN，不产生`allowedUse`。同一主库事务插入候选、事件、幂等回执；幂等键全局唯一，请求摘要包含操作者、动作、路径键和规范化载荷，同键异摘要冲突，撤回后旧登记重放冲突。撤回仅CAS `CANDIDATE`行版本1→`WITHDRAWN`行版本2并追加事件/回执，错版本或重复异键拒绝；同键重放只有当前行仍为WITHDRAWN且维护身份当前有效时返回撤回回执。异常回滚不留孤儿回执或审计事件，DB失联503。维护审计是独立证书审计和同事务来源事件两层，未认证请求不能提交来源表。状态`ACTIVE`保留给后续引擎D和独立水位流程，本切片没有任何到ACTIVE的代码路径；候选与撤回在用户查询/原文读取中始终不可见。上述约束闭合本窄D的身份、版本、事务、保留及失败反例，不解除第2.4节未闭合的来源Grant、源水位、原文保留、结果游标和具体源权利。

注册命令的`coverage`为必填结构化范围声明（如地域、时间、文献类型及其不确定项），与字段×用途一样仅供后续有责准入复核；它不表示系统已核准该覆盖，也不能取`*`或空范围。第2.5节前述输入列表遗漏此字段，以本句为准。

核验现时性实施补充（2026-09-28）：来源独立历史摘要还覆盖当前合同的不可变VERIFIED记录ID、来源版、候选版、manifest/records/原件清单摘要、数量、核验者与核验时间；否则主库核验引用漂移不会触发独立水位拒绝。正式ACTIVE读取需同时核当前主库指针/独立epoch、合同ACTIVE且候选版已由1推进2、同来源版VERIFIED、决议与确切字段×用途未过期，并在返回快照前再次核epoch。该读取仅证明数据库证据一致，真实原文字节仍须在读取时逐摘要复核；没有第三方用途决议不得因VERIFIED而返回可用来源。

词面查询内核实施补充（2026-09-28）：`LEXICAL_AND/v1`只在调用方交付的获准字段集合中做Unicode规范化、大小写折叠与字面词AND，通配符不是SQL语义；按命中字段数降序、来源/文献/文献版稳定排序，调用方显式提供结果上限并返回截断。匹配项必须携带来源批次版、文献版、原件SHA-256和定位引用，不把没有manifest绑定原件摘要的行提升为候选。纯算法本身既不决定字段用途也不允许绕开Task目标、独立水位、来源准入和正式结果事务。

### 2.7 首批采集来源的产品准入决议合同（2026-09-28）

项目负责人本轮明确授权开发团队确定当前产品范围的逐字段用途，且ACTIVE不代表已经取得所有第三方法律权利。此后`REAL`仍只指原始采集，`VERIFIED`只指字节/文献版/定位完整性，`ACTIVE`只指**本项目现时产品使用决议**。第三方权利依据、适用条款/主体、争议与到期另外记录为`VERIFIED|UNRESOLVED|WITHDRAWN`及原件Ref/SHA；不得以产品维护证书、项目负责人指令或自签文字填作“第三方授权证明”。界面/API不得把ACTIVE翻译成法律许可结论；权利方撤回或证据相抵触时立即PENDING→WITHDRAWN并禁止受限内容继续输出。

首批候选仅限`data/patents/public-pilot-v1`现有44条记录与manifest逐项列出的47份原始响应；登记来源身份为`SURECHEMBL_PUBLIC_SEARCH`、原始API `https://surechembl.org/api`、批次版`datasetId:generatedAt`，每篇绑定`sourceRecordId`、`sourceVersion`（上游doc_version为空则标观察版）、`detailRawFile`的manifest SHA及原文JSON Pointer。旧manifest的`INTERNAL_DEVELOPMENT_PILOT_ONLY/REVIEW_REQUIRED`是采集时决定，保持不可变；本轮新的产品决议须另存不可变版本、责任团队/具名维护证书、日期、确切组织/Task用途范围、有效期限、逐字段矩阵和法律事实状态，不能篡改旧字节或借旧候选登记的`rights-review`占位引用。第二批400条虽是真实采集，其详情文件缺与manifest逐项绑定的原件SHA，不能进入这一ACTIVE批；EPO三份漂移列表也继续禁用。

团队提出的首批最小产品用途矩阵为：`TITLE/ABSTRACT`准QUERY、SAVE、INDEX、DISPLAY；`CLAIMS`准QUERY、SAVE、INDEX、DISPLAY，仅当对应文献实际原件有完整权项和精确定位；`FULL_TEXT`准SAVE、DISPLAY，仅当实际说明书片段及定位可重取。无原件或有摘要漂移的具体文献只可在其有权字段内保留元数据，不伪装权项/全文。DOWNLOAD、MODEL、SHARE一律不从本决议推出，跨组织/跨Task不得继承。能力的字段可用性逐文献记录而非把44条都标“全文完整”；实际索引如只做受控字面扫描，也须以INDEX用途核定保存的可搜索副本。产品决议的期限取受控记录、来源有效期、原件保留政策与撤回监测期限最早值；缺批准保留配置或无法证明当前有效时保持CANDIDATE。

准入命令须分别读取受控原件和产品决议原件、核SHA/签署责任、候选版本、manifest/records/逐原件SHA、原始链接/文献观察版、字段与能力交集、逐用途期限、撤回渠道`LOCAL_STOP`与定期核查责任，保存`verificationId`、`productDecisionRef/Sha`、`rightsStatus/rightsEvidenceRef/Sha`、字段用途行、审批事件和幂等回执。现有V037的`rights_ref/rights_sha`原意是独立第三方权利证据，不能塞入产品决议以制造假许可；落库前需可执行迁移分开两种证据并复核`ProductSourceWatermark`摘要、正逆迁移与旧ACTIVE回灌反例。准入只在持SOURCES职责的具名维护身份、有效产品决议、所需外部使用条件已按决议范围核验后CAS `CANDIDATE→ACTIVE`；未决第三方事实仍在审计与对外适用范围披露，不能自动改为VERIFIED。若适用合同明确禁止拟定用途，即使团队同意也拒ACTIVE。

二遍D反例：旧manifest内部用途被直接改写或作为产品决议；仅维护人自填第三方许可；原件SHA正确但权项定位缺失；第二批详情不在manifest；EPO漂移；同源旧版或重复文献异SHA；用途宽于候选声明；已过期/撤回/无保留；同键异载荷、双维护者CAS、主库提交后独立水位PENDING、撤回与结果/原文并发、旧主库或旧产品决议回灌。每项须拒绝当前用途或保持PENDING，不能返旧缓存；可用文献的查询零命中与全源不可用分开。开发期结构C可验证拒绝路径，真实首批ACTIVE的I必须从具名产品决议和上述受控证据经正式维护命令产生，不能用测试直接插V037。

### 2.8 PRODUCT_ENABLED技术验证准入修订（2026-09-28）

第2.7节的字段矩阵和字节/定位反例不变。本轮项目负责人将当前项目的产品用途决议与第三方权利事实彻底分列：原采集证据为REAL，`con4_source_verification`为VERIFIED；对外可见的`PRODUCT_ENABLED`仅表示经正式维护命令批准的确切组织/Task/DataScope、字段和用途的当前产品技术验证。旧V037内部`ACTIVE`只作使用轴存储状态，不在API中译成第三方授权。独立`rightsEvidenceStatus=UNRESOLVED|VERIFIED|WITHDRAWN`及其原件引用/摘要不得由产品决议或维护者签名自动变成VERIFIED；权利冲突/撤回使来源停止输出，未来生产接入另审。

准入命令先核真实候选、同版VERIFIED原件、受控不可变产品决议字节/SHA、版本与期限、逐字段矩阵不超过候选及实际原文能力、批准保留依据、当前SOURCES维护证书和精确组织/Task范围；在独立来源水位PENDING围栏内同事务写产品决议、权利状态、字段用途、审计事件、幂等回执和唯一当前指针，成功后才释放水位。主库成功/独立库未确认保持PENDING；同键异载荷、旧版、无证书、无原件、无范围、过期、越字段、权利撤回、跨Task及旧水位一律拒绝，不能回退旧准入。原V037的`rights_ref/rights_sha`只保留真实权利材料引用，不用产品决议冒充；新增独立决议存储及水位摘要应经正逆迁移和旧副本拒绝测试。当前环境的实际身份和保留配置属I，未来生产岗位及正式业务验收另证。

同一sourceId续期只接受另一个已核验、未曾准入的合同版本：先用正式撤回命令使旧当前指针成为WITHDRAWN且epoch/行版各进一，再用新合同、独立产品用途决议及新Task/DataScope准入；独立水位先进入PENDING，主库CAS替换唯一当前指针的合同版/决议ID并将epoch/行版各再进一，追加ADMIT事件且保留旧合同、决议和撤回历史。旧版不得重新启用，ACTIVE当前指针不能直接换版；任一事件、摘要或外库水位不符即失败关闭。新准入后旧来源scope/Grant、重放回执和旧备份快照不得成为现时权限，第三方权利状态仍独立记录。

2026-10-02 UTC限定实现纠正：用途合同版本不是采集内容版本。对CAPTURE_SHA256:64位真实清单摘要，旧合同已经WITHDRAWN且所有同采集版声明的原来源、所有权署名、许可引用/摘要、覆盖、字段/能力及采集时间完全一致时，允许新合同复用该不可变采集版；合同ID/未来有效期可改变；用途申请按以下2026-10-03限定修正另核。不得改collectedAt/manifest制造内容变化，不得重启旧合同、复活旧决议或绕过新VERIFY/产品决议/水位/Task范围重授权。非摘要绑定旧格式、任一同版合同未撤回、同版声明漂移均409拒绝。V088将(source_id,source_version)内容唯一性转移至不可变con4_source_capture_identity锚表，迁入既有唯一声明并去掉合同层错误唯一约束，原(source_id,contract_version)主键和全部历史保留；新内容先插入/锁定唯一锚，随后核所有同版合同，跨实例首建与续版都串行，不依赖进程锁。锚不拥有权限、不提供查询来源，必须仍走新VERIFY/产品决议/现时水位。反向U088先恢复旧唯一约束，有合法重复内容版时应拒绝，不删历史以强行回滚。本修正不改变来源内容、用途范围或第三方权利。

2026-10-03真实模型检索限定纠正D见docs71 §3/§4及docs02 §83：requestedUses是新合同的用途申请，不作为采集原件内容锚；原文/manifest/字段/来源/许可及能力不变，旧合同必须撤回，新VERIFY与真实维护决议继续必需。ProductAdmission的用途申请白名单补MODEL，但它不自动产生任何授权；SOURCE现时逐字段MODEL批准后，本切片仅向配置的真实模型发送公开目标和已提交结果最小元数据，不外送原始权项/正文、私有身份、内部文件路径。DOWNLOAD/SHARE仍未开放；rightsEvidenceStatus仍UNRESOLVED，不冒第三方完整许可。

## 3. 专业技术对照

来源：CON-06第1～4、6、9节，docs/56 CON-06。协议按目的区分技术相似、权项对照、新颖性辅助和外观设计；首条文本任务不继承外观方法。`ComparisonInput`绑定任务修订、目的、目标原文/版本、用户确认的FeatureRevision、候选文献及权项版本、必要的日期/地域和方法版本。AI可提出含原文跨度/上下文的特征候选，用户确认或修订后生成不可变版；从属权项递归展开依赖，环/缺前项拒完整权项分析，说明书支持不自行变权项限定。缺权项、原文质量不达标或目的不明时为缺项，不借摘要/相似分补齐。

每一候选文献、每一目标特征都有独立行：`targetSpan, sourceDocumentVersion, sourceClaim/paragraph/page/offset, sourceDigest, relation, sameAspects, differences, reasoning, uncertainty, methodVersion, actor/version`。关系只允许对应、部分对应、已核范围内未找到、无法判断、未分析；“未找到”须记录已检范围和方法，不等于不存在。每行引用的原文按当前权限重取并验摘要/定位；定位失效则该行不得用于当前结论。单篇新颖性辅助不得拼接多篇为单篇覆盖；技术关系不得推出侵权、有效性、授权概率。没有标定分母/权重与专家质量证据不出数值评分。机器候选、人工修改与复核分别追加不可变版本和关联原稿，不能覆盖。

方法候选为“原文特征矩阵 + 逐行可核对应与差异 + 缺项显式保留”，不依赖模型自评分；AI只建议跨度、对应候选及理由，最终可用意见依具名专业人员审阅其需要复核的版本。对简单查询不强迫对照。纠正仅使依赖的特征/候选行及派生回答失效，旧版留存，重新定位/复核后才恢复当前引用。D反例：F2纠正、候选B模型故障、关键权项缺失、从属项前项缺失、多篇拼接、相似摘要但原文不支持、来源撤回、人工版与机器版冲突。带定位真实正反样本和专家允许差异用于质量验证，不伪造金标；没有样本时可核协议结构，不能称专业效果通过。

第3节首条文本对照的具体输入、权项择一分支、矩阵、API/权限、事务/恢复、局部纠正与验证合同现由[首任务D1](adr/consultation-product-comparison-20261002.md)细化（2026-10-02，ARC-CON-PRODUCT-COMPARE-D-01）；不改变五协议，不扩大来源用途或宣布专业效果通过。唯一任务状态仍见docs/08。

## 4. 人工受理、回流与成果

来源：CON-06第3、6、9节，咨询总目录任务闭环，docs/55第2、6节及docs/56第3节。触发为用户显式请求、正式使用/高风险判断、重要证据冲突或关键证据缺失；技术查询可直接交付有证据的参考及限制，不自动进人工。系统只提出必要性，具名有权人确认任务包的目的、问题、输入修订、证据/来源版本、当前缺口和允许动作。任务owner可请求；有对应职责及精确包/组织grant的受理人可领取、退回、追加意见/修订；复核或正式采用需另有授权，不因“受理人”自动拥有。用户自领或同人复核是否允许须遵守相应领域职责分离，不能从测试身份推断。

请求保存为待领取，不是已受理；领取以服务端受控CAS把确切包版本分给具名主体，真正提交才记受理。受理版与修订、来源/权限现时性均绑定；补料/纠正后旧包意见只能写回历史并待新版本复核，不能覆盖当前成果。退回、撤回、拒绝、超时或外部回执未知各自保留事件；没有可信外部回执不伪造接收号。人工原稿、机器原稿与人工修改各保存不可变版、差异、提交者/时间/依据引用。回流在原任务形成`AnswerRevision`：目标、已做事项、具体证据/版本、逐项结论或未知、限制、待补/继续动作、人工意见的准确归属；“已完成”只在目标与必要证据满足且限制披露后给出，部分完成、证据不足、技术失败分开。任何官方审核/签发/处罚/裁决状态都不由此改变。

D反例：普通查询直接成果、双人抢领、未领意见、旧包迟到、领后撤权、来源撤回、用户纠正使部分行失效、重启恢复、同键重放及人工/机器版本冲突。岗位类别和系统内领取方式属本协议；实际人员任命、正式责任与组织接收规则须以可信登记和真实动作验证。

## 5. 持久化、备份与恢复

来源：咨询总目录第3.7节DM-01～07、CON-12任务型闭环、docs/55第5～8节。DM8保存任务、消息/修订、结果集、证据/原文引用、授权版本的历史证据、人工事件、成果和命令回执；文件存储保存获准的原件/派生件，DB manifest保存不可变对象ID、内容摘要/长度、来源/用途、文件版本和引用状态。先受控暂存并验摘要，再同一业务事务绑定manifest及任务/证据版本；未绑定文件不成为依据，可对账清理。索引是可重建派生物，不是唯一原件。到期/删除按数据对象许可与正式保留依据分别执行；无保留依据的真实资料不能默认套旧六个月。

备份周期采用静止一致水位：暂停新写/清理及文件绑定，记录DB事务检查点、全部已绑定文件manifest与实字节摘要、引用闭包和授权/来源/删除现时高水位，再制作加密DM8备份和文件包；包清单签名或认证加密并独立校验。备份、恢复、线上服务、清理JOB分身份最小权限；密钥独立托管、访问审计及轮换，旧密钥仅在获准保留窗口可解旧包，失密钥明确不可恢复。轮换取对象最早许可到期与批准的备份保留期限，不能从旧包复活删除/撤回记录。备份可同盘用于逻辑误操作恢复，但不抗整盘故障，恢复目标/RPO/RTO与介质配置须独立批准和演练。

恢复只进入网络隔离实例，验证解密/校验、DB升级版本、manifest到字节、任务→输入修订→结果/焦点→证据原文版本→人工包→成果及幂等回执关系，核文件无缺失/不一致；索引重建后逐引用核对。将恢复包高水位与备份外不可回退的现时授权、来源撤回和删除高水位比较；缺失、落后或服务不可用时保持产品路由关闭，逐对象重新鉴权/核来源后才开放允许部分，不能重放过期任务或旧grant。恢复工具不直接签发生产身份。DB成功而文件缺失、文件多余、证据定位漂移、孤儿人工版、旧包复活撤权、错误密钥、清理与快照交错均为拒开放或隔离待核场景。

候选参数（备份频率、保留期限、RPO/RTO、对象大小、密钥轮换期、查询上限/页大小、模型预算）不在本ADR给无依据的固定值。由运维/数据治理依据资料期限、数据量/容量、目标环境、故障演练和业务恢复目标配置且版本化；缺必需配置时相关能力拒绝启动，不能无限期保存或无备份声称可恢复。开发同盘空库备份与旧PH探针只是工具证据，不通过本产品D。

首任务当前环境的离线备份合同（2026-09-28，开发团队依据本节及项目负责人当前环境产品机制授权确定）：正式命令只在部署维护窗口运行。部署控制器先停止全部产品API/Worker/清理JOB写者，冻结其数据库写入账号，并以数据库会话检查和部署实例清单证明无残留写者；任一步不能核证即拒绝备份，单靠应用内布尔标记不算停写。独立授权/来源水位库保持在线且不纳入可回退的业务快照。备份身份只有主库导出读取、受控文件根读取及备份根写入权限；恢复身份仅能写全新隔离目标，不可改动在线独立水位。服务/JOB、备份、恢复和密钥托管分岗，当前部署必须提供身份/权限配置与审计，不提供默认账号或口令。

命令输入为确切主库schema、独立水位库只读连接、获准文件根、受控加密暂存根、目标包根、版本化保留依据及密钥标识/托管引用；所有路径取真实路径后核不相交，空值/错权限/到期拒绝。停写后先以只读事务核Task/Goal/Input/回执/来源/文件引用闭包和原件实字节SHA，记录DB结构版本、当前Grant与Source epoch/摘要、文件清单及对象最早到期。DM8原生schema导出只在受控加密暂存卷生成，数据库凭据走受保护的标准输入/密钥源，不写进进程参数、包、日志或决议文件；随后将导出字节与确切文件字节封入同一认证加密包，清单绑定批次ID、每项SHA/长度、密钥ID、保留到期和前述水位。封包前后重读DB闭包、文件摘要和外置现时水位，任一变化/失败删除未发布包并保持关闭；仅原子发布完整包。包密钥由独立托管提供，缺钥/错钥/篡改认证失败，不以回退旧钥或明文包继续。轮换及销毁不晚于批准包期限与所含对象最早可用期限，不能从旧包恢复已撤销/已删除内容。

正式恢复命令只能面向空的新隔离DB schema与文件根，先验证包认证、清单、权限/密钥及全部字节，恢复主库和文件，不回灌在线独立水位；原始服务/JOB保持停止、产品路由保持关闭。运行现有只读Task/Grant/Source/文件闭包核验，并逐项比较包水位与未回灌的当前Grant/Source/删除权威；旧包、高水位缺失、权限失效、原件缺失/多余/漂移、孤儿回执/版本或未核外部派发均拒开放。只有当前身份及DataScope重新鉴权、当前来源逐用途仍可用、正常请求与跨范围/撤权/停用拒绝请求均在隔离产品路由复核后，受权恢复岗位才可签确切批次/目标/时间的开放回执；命令本身不代签业务验收。备份中断、错误密钥、错权限、停写中新增写者、文件/DB交错、同时回灌旧水位和恢复后旧回执重放是必须自动化覆盖的拒绝反例。当前环境的实际配置、同批包和新实例请求属于I证据；未来生产拓扑/岗位/RPO/RTO与业务签收另取证。

备份期限纠偏（2026-09-28，`IMP-CON-PRODUCT-RECOVERY-01`窄D）：Task/Source/Grant的`expiresAt/validUntil`只结束现时业务使用，不等于已入库历史、撤权事件和不可变回执的物理审计保留期限。当前仍需保留的主库全部行、原件与文件引用随原生整库导出进入同批包，不能在封包程序中假装过滤已过期行。部署须提供经摘要校验的不可变备份保留决议文件，固定责任主体、版本、组织范围、包最大寿命、历史审计保留截止、所覆盖的全部业务对象保留依据`ref→SHA-256`及Grant/Source事件历史覆盖；缺一项或数据库引用不匹配拒封包。包有效期必须在当前时刻之后，且不超过`now + packageMaxAge`和历史审计保留截止；当前仍有效的Task/Source也在包中，不用其使用期限缩短历史审计包寿命。历史已允许物理清理的内容必须先由正式清理事务实际删除并留下不可回退删除水位，再取原生快照；只声明可清理而仍存在的行仍受上述审计决议覆盖。恢复时到期或撤权的业务状态和独立Grant/Source高水位仍拒绝重新开放，不因包有效而恢复业务使用权。旧包按其原签发期限处理，不追认延长；本窄D不新增清理命令或声称第三方内容权利。

### 5.1 认证恢复包操作途中到期失败关闭（2026-10-10）

FIX-CON-PRODUCT-RECOVERY-EXPIRY-01归属已准入IMP-CON-PRODUCT-RECOVERY-01，沿CON-12及本节源中立恢复D；仅技术细化既存Snapshot.expiresAt，不设新期限或当前业务/资源授权。当前ProductTaskBackupBundle.seal/open仅在入口校验期限，长时间文件提取后直接原子发布并返回；ProductTaskBackupCommand.open没有额外末尾期限核验，不能用缺当前成员/Source/模型许可阻止这一独立产品机制修复，也不能借此运行真实保护恢复。

复用现有validate(key,snapshot)在每个封入/提取文件前、完整暂存完成后及原子移动前后重核，已到期不返回成功Manifest。失败在移动前发生时沿原实现清理本次新临时文件/提取树，不创建最终目标；在移动后末尾失效时只拒绝成功、保留本次已生成加密包或网络隔离提取目录，不声称回滚或删除任何既有资料。该目录不等于数据库已恢复或产品放行，现时水位/权限/来源及后续恢复闭包仍须原独立步骤。原业务数据/包格式/密钥/期限/SHA/目标与权限合同均不变，无新产品路由/开关/状态、无部分恢复豁免。

使用自编导出替身与文件字节、原实现实际认证加密/解密及受控Clock覆盖入口/途中/发布前/移动后到期和合法对照；不读取旧保护包/过期专利原件，不调用模型/真实数据库。纯文件组件证据与真实新包/DM8恢复/部署/人工接受分别记录，原失败/source bytes/SHA保留。

## 6. D与验证分层

每项在docs/08对应`ARC-CON-PRODUCT-*-D-01`单独审：原需求与已确认决定的正反映射，产品API/数据/角色/版本/失败合同，现时授权和逐用途来源，可运行的正常/竞争/故障/撤回/恢复反例及实际适配能力。D未过仅继续设计与只读核证；D通过后由对应IMP实现和C，再用获准采集/脱敏真实资料及真实身份/源/DB/文件进行I，专业质量与业务B由独立责任方确认。结构检查不证明语义正确；单项D不能提升13项完整D或首任务完成状态。
