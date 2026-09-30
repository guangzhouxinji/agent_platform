# ADR-V2-DEV-GATE-01：执法与裁决合成开发期入口、撤回及恢复边界

日期：2026-09-25。关联任务：`ARC-V2-DEV-SHARED-GATE-DECISION-01`。性质：项目技术决定，不是原始需求、甲方授权、安全验收或生产运维批准。依据为`docs/54`的领域数据所有权、`docs/02`第26/27/30/32节的开发期决定、`docs/57`第6.4～6.5节、`docs/58`第5.3～5.11节和现有V2咨询边界代码。适用范围仅为已签入的项目原创合成输入、显式隔离的开发配置；旧manifest和历史回执不扩权。

## 1. 决定

1. `platform-api`持有唯一最高优先级`/v2/`入口及启动路由清单。咨询、执法、裁决域提供自己的路由声明和用途判定，平台按独立开关求精确并集，并与实际Controller方法/路径双向核验。未知、关闭域、大小写/编码/额外方法变体不得落入V0或第二个后置Filter。四种咨询/执法开关组合及裁决独立开关组合均须测试；禁用时不装配Controller，启用但缺完整凭据、manifest或持久授权状态时启动失败。
2. 凭据签发、撤销和域绑定由平台的开发期安全适配器负责，领域服务仍逐命令核资源归属及当前用途。各域独立凭据空间、组织和动作授权；客户端自报`X-V0-Actor`、组织、角色、owner或caseId均不构成授权。执法首切片只有签入的`dev-enf-a`具名H同步注册/投影/读取/取消；A无业务命令，J只有无正文到期清理职责。裁决若进入CASE-001切片，只允许服务端具名H且对CASE-001逐动作grant，不从manifest中的principal引用推断真实案权；双人复核和正式流程不在此决定内。
3. 对本项目控制的合成来源，当前凭据/grant和dataset/record许可状态必须在所有服务实例可核的持久状态中拥有单调epoch。开发期选择同一DM8事务连接上的固定锁序：凭据与动作grant、dataset与受影响记录（稳定键顺序）、领域工作行；授权/来源维护操作采用兼容锁序并提交审计。命令进入、提交前以及返回受限正文前重核当前状态与签入原始字节、字段用途、期限；同键回执重放也先核当前许可。撤回先提交则新读/写零正文零业务写，业务先提交则历史保留、撤回后不再展示受限正文。状态不可达、部署实例不共享当前状态、摘要漂移或来源过期，一律失败关闭；仅内存`revoke()`和manifest中的旧撤回版本不满足该合同。外部来源撤回通知到达之前的时延另由来源责任方定界，不能声称零窗口。
4. 业务事务、幂等回执、事件和版本由各领域数据模块拥有，Agent不能直写。开发期仅用当前签入包准许的字段用途；执法v1的`QUERY/SAVE/DISPLAY`不能继承v2的`INDEX/CANDIDATE_COMPARE_INPUT`，其首切片也不消费后两项。裁决v2的`CLAIM_SIDE_BY_SIDE_INPUT`只表示未确认双方主张并列输入，不是专业对照、证据采信或报告草稿。不得跨域复用咨询`con2_*`关系或V0自报身份路由。
5. 恢复采用默认隔离：任何DM8旧备份回灌后，三域V2路由和后台工作均保持关闭，直至从**不随该备份回退**的可信责任源取得完整当前凭据、逐资源grant、dataset/record许可及撤回高水位，校验签入摘要、DB时钟/更早来源期限，并完成到期和撤回对象的失败可重试清理。没有完整现时状态或其真实性/版本不可核时不得自动开放；同库epoch不能自证。若开发环境经运维核证无包含该切片的备份，才可在相应物理D中排除回灌I，在线拒读和清理仍须实测。此ADR不决定生产备份介质、轮换或恢复SLO。

## 2. 独立窄D应用与未决项

| 范围 | 本决定可供D审查的技术输入 | 仍需独立冻结/证明 |
|---|---|---|
| 执法观察到材料提及 | 单H同步、旧v1字段用途、共享入口、严格撤回栅栏、材料无字节不得升级证据 | `enf2`列型/摘要/CLOB上限、FK/唯一键、回执和取消事务、PURGING及逆向迁移的具体物理D；共享安全适配器设计适用性；咨询先行复盘。D后另立实现/C/I任务 |
| 裁决CASE-001双方材料 | 精确逐动作grant、跨案默认拒绝、v2限定输入、当前来源状态与恢复隔离 | 选定首切片是只读还是持久工作；若持久化须审`adj2`逐表约束/锁序、取消的无正文例外及错误优先级；备份范围和高水位实际提供者。D后另立实现/C/I任务 |
| 真实或生产范围 | 无自动继承 | `docs/02`第25节D25-AUTH/DATA/ENF/ADJ/HUMAN/OPS的来源许可、真实案权、专业规则、岗位及环境证据；完整D、真实I/B分别判定 |

本ADR只选技术路径，不伪造职责方已配置持久状态、运维已提供高水位或独立窄D已通过。若实现前无法取得同事务共享状态，则缩到明确的单实例且禁止宣称跨实例即时撤回；任何需要真实跨实例保证的业务入口继续关闭，不能改用“接受风险”隐式放行。

## 3. 预注册反例

- 路由C：咨询/执法开关00、10、01、11和裁决独立开关，检查实际映射与清单双向一致；多出/遗漏方法启动失败，关闭域404，V0头、编码路径、后置Filter不能绕开；咨询原有正反例保持通过。
- 身份/用途C：无凭据、跨域H、A/J调用H命令、同组织无CASE-001动作grant、CASE-002及旧包扩权均拒且零正文/零领域写；已提交同键回执在撤权、来源漂移或到期后不返回受限结果。
- 事务C/I：两连接同版本竞争恰一提交；撤回先/业务先两序按同一锁序；断连无幽灵回执、重启同键对账不重算；取消与投影或附料竞争无半版本。逐次核领域新表与旧`enf_*`、`adj_*`、`con2_*`禁止增量。
- 恢复I：旧备份含ACTIVE grant/source而恢复前已撤销时，未取得独立现时高水位始终不开放；高水位缺键/签名错/版本回退、到期边界、清理中途失败均无正文或旧回执复活，重试后可核计数。没有可用隔离备份环境时记`notRun`，不能记通过。

实际C/I须在各域独立D通过、实现Task登记后运行；上述反例只是设计输入。结构校验也不证明法律、专业或真实授权正确。

## 4. 共享可信状态物理窄D准备（未准入）

`ARC-V2-DEV-TRUST-STATE-D-01`只评审开发期技术基础设施，不给本ADR原任务追加实现许可。现有`DevelopmentSubjectPolicy`的凭据撤销与`SyntheticUsePolicy`的来源撤回只在进程内；`V2ConsultationBoundaryFilter`独占`/v2/`且只识别咨询路径。它们的C/咨询DB开发I可供接口和失败关闭反查，却不是多实例共享grant、来源epoch或旧备份恢复的证据。咨询当前正文DISPLAY守卫仍须保留其已签入字节核对，不因接入新状态而把旧SAVE包扩成DISPLAY。

| 候选持久关系/端口 | 最小键、状态及写入边界 | D前反例与未决证据 |
|---|---|---|
| Credential | 平台可信签发的`issuer + domain + keyId`唯一，哈希秘密不回显；绑定具名principal、org、kind、有效期、ACTIVE/REVOKED及单调epoch。请求头只携不透明凭据，不采信客户端自报域或组织。 | 同keyId跨域碰撞启动拒绝，旧版本/过期/撤销和A/J冒用H拒绝；实际签发、维护者、密钥轮换和审计配置未定。 |
| ResourceGrant | `issuer + domain + principal + org + resourceType + resourceId + action`精确唯一，ACTIVE/REVOKED、较短有效期及单调epoch；裁决CASE-001的`READ_DEV_CLAIMS`必须有独立行，不从manifest的主体引用推断案权。 | 无grant、CASE-002、跨组织及动作替换零正文；开发维护入口的具名操作者、授予来源及撤权生效点未定，真实甲方案权不在此范围。 |
| SourceState | 项目原创签入包的dataset键及`dataset + recordKind + recordId + version`记录键，分别持ACTIVE/REVOKED、当前摘要/签入版本、期限和单调epoch；字段用途仍从不可变签入manifest逐次核，不在状态表给旧包增加用途。 | 旧v1用途不能继承v2、错记录摘要/跨域/撤回先提交均拒；状态行缺失或DB断连失败关闭，来源维护者及每条记录初始化证据未定。 |
| MaintenanceAudit | 只有受控维护端可按`expectedEpoch`增加版本、撤回或缩短期限；写具名维护主体、原因、对象键、前后epoch与DB时间，不存正文或秘密。普通H/A/J及Agent不能调用维护操作，也不能把撤回同版改回ACTIVE。 | 双维护者竞争只一提交，响应丢失按当前状态对账；谁有权初始授予/重发新版本、审计期限及技术账号最小权限仍需固定。 |

最小端口为`Authenticate`、`AuthorizeResource`、`CheckSourceUse`及仅维护侧的`AdvanceState`；领域服务只收服务端可信subject和显式资源/动作，不直接依赖DM8。受限正文的读取/提交事务使用同一连接和DB时钟，按credential、grant、dataset、稳定排序的record、领域工作行固定锁序；维护事务锁同一状态行，提交为撤回线性化点。返回前再核epoch、签入原始摘要和逐字段用途，状态变化或不可达整响应无正文；已经送出的字节不能追回，外部撤回通知到达之前的窗口也不能声称消失。平台路由声明与实际Spring映射双向核对，关闭域先404，开启但缺完整持久状态启动失败，不以V0头或后置过滤器兜底。

恢复模式默认关闭全部V2业务流量和后台任务。旧DM8备份中的epoch不构成现时高水位；开放必须核对不随该备份回退的责任源提供完整credential/grant/source状态，或由开发运维核证该隔离切片无备份且不走回灌。两种分支均要记录具体实例、维护账号、配置版本和日期，重算期限并完成撤回/到期清理及摘要对账；失败保持关闭。当前仅有共享技术方向和咨询随机schema的DB机制验证，没有上述实际维护者、独立高水位或可核无备份结论。D前还须固定新关系的列型/唯一键/FK、单调epoch更新与回滚不可复活机制、最小权限、正逆迁移顺序和错误优先级，并把两实例撤回-读/写双序、断连、旧备份回灌及咨询回归写成可运行C/I输入。未经本Task独立D通过并登记后继实现任务，不建共享表、不开放执法/裁决GET、不读CASE-001、不写`enf2_*`/`adj2_*`或改`con2_*`业务关系。

### 4.1 物理与事务合同反查（仍未准入）

对照V023/V025已用的`VARCHAR`键、`BIGINT`版本、`CHAR(64)`摘要、`TIMESTAMP`和`FOR UPDATE`，候选共享表仅持技术状态，不存领域正文：

| 关系 | 候选键和约束 | 不可推断的行为 |
|---|---|---|
| `v2dev_credential` | `issuer,key_id`全局唯一；域、具名主体、组织、H/A/J类型、秘密摘要、状态、期限、`epoch >= 1`。轮换创建新keyId并撤销旧行，不能改旧REVOKED为ACTIVE | manifest主体不是凭据；客户端自报域/组织不选凭据行；跨域同keyId拒绝 |
| `v2dev_grant` | `issuer,domain,principal_ref,org_ref,resource_type,resource_id,action`唯一；状态、期限和epoch。grant不外键指某keyId，轮换凭据不能暗改资源授权 | 同组织或开发全权限假设不替代精确资源动作grant；A/J不继承H grant |
| `v2dev_source` | dataset以`domain,dataset_id`唯一；record以`domain,dataset_id,record_kind,record_id,source_version`唯一且引用dataset；各持状态、摘要、期限和epoch | record缺行、dataset撤回均拒；旧版不能继承新版字段用途，表状态不能替代逐请求签入原始字节核验 |
| `v2dev_maintenance_event` | 独立事件ID、对象键、具名维护主体、动作、前后epoch、请求摘要、原因引用和DB时间；状态CAS成功才同事务追加，事件不可覆写 | 历史ACTIVE事件和恢复出的旧表不是当前授权 |

状态维护要求`WHERE epoch = expectedEpoch`且新epoch只加一，受影响行数恰为1；撤销单调，重新授予只经新键/版本的具名维护操作。初始化需逐对象签入摘要和用途核对，缺行失败关闭。请求按`issuer,keyId`锁credential、精确grant、dataset、稳定排序的record、领域工作行；维护端同序，禁止持领域锁再回头锁共享状态。业务提交前在同连接以DB时间重核当前状态，受限正文返回前再次核验且响应缓冲未开始写出；已经送出的字节不能追回，撤回通知尚未到达的窗口不称零时延。

错误优先级候选：关闭/未登记路由404且无业务Controller；启用却缺可信配置或状态表启动失败；无效凭据先拒绝且不探测资源；有效凭据无精确grant拒绝且不泄对象是否存在；来源状态/签入字节/用途失效整请求无正文`SOURCE_UNAVAILABLE`；领域修订冲突只在当前许可成立后报`VERSION_CONFLICT`；DB不可达503且无幽灵回执。C须以不同组织和CASE-002反例验证错误不泄资源存在性。

隔离DM8 I预注册为两独立进程、不同连接、同一临时schema的撤回先/业务先双序、两维护者同epoch仅一成功、断连及旧备份ACTIVE与现时REVOKED冲突；清理失败保持关闭并可重试。备份现时高水位必须来自不随该备份回退的责任源，或由开发部署证据明确排除该切片回灌。当前仍缺维护者/最小权限配置、独立高水位或无备份证据、迁移正逆序和反例的确切配置输入，故本节不判D通过，不建迁移或实现Task。两进程实测结果属后继开发I，不是本D的先决结果。

### 4.2 现有咨询事务接入点与迁移反演（仍未准入）

代码反查：`V2ConsultationBoundaryFilter`目前独占全部`/v2/`，按方法/路径和H/A/J种类拒绝，但`DevelopmentSubjectPolicy`的凭据版本、`SyntheticUsePolicy`的撤回集合均只在本进程；过滤器的`permits(subject, kind, subjectId)`也不是资源grant。`Dm8DevTaskCoreSupport.task(..., true)`在领域事务中先锁`con2_task`。因此不能只在现有Store的锁后追加共享credential/grant/source查询，否则与第4.1节的共享先锁顺序相反，撤回维护与业务请求可能死锁；也不能在Filter另开连接查一次状态就称提交前同事务重核。后续实现必须让领域用例在**同一事务连接**上先取得共享状态锁，再进入现有任务锁，并在提交与受限正文返回前重新核验；现有owner/包级条件仍由咨询域判断。启动路由声明要与实际Spring映射双向核对，旧Filter不得与新共享Filter各自放行同一路径。先用双实例C/I证明锁序和撤回双序，才可替换当前单实例声明。

最小迁移候选的顺序是credential、grant、dataset、record、maintenance_event，record只引用dataset的复合键；审计事件不外键指会清理的业务行或可撤销状态行，保留期与清理由独立批准界定。每表的状态、epoch非负/正值、摘要长度及唯一键必须在目标DM8临时schema以正向和**空表逆向**脚本验证；回滚顺序为event、record、dataset、grant、credential。不得在非空状态或已签入历史上执行破坏性逆迁移，也不得复用V023～V030的`con2_*`表；当前没有共享V脚本或可运行迁移结果，这只是待检的依赖顺序。

任务创建前不存在taskId授权行：共享动作grant若要求精确taskId，必须先有可信签发/初始化协议，不能由普通H、Agent或客户端自报owner给自己造grant；若选择域级`CREATE_DEV_TASK`入口grant，创建后owner及包级权限仍由咨询领域事务授予/判断，不得自动继承其它任务读取权。此选择及新任务grant的事务时点尚未冻结，属于D的阻断点，不由现有“两个H可操作”测试代替。来源dataset/record行初始化也须由受控维护者逐签入摘要，缺任一行默认拒绝，不以manifest中的ACTIVE状态自动开权。

下一轮D反例输入需具体到：两进程共享同一临时schema，撤回先提交/读取或写入先提交各两序；现有task锁已持有再索取共享锁的路径必须被静态/运行检查拒绝；同epoch双维护CAS仅一成功；缺credential、grant、dataset、record及摘要漂移分别整请求失败且零业务回执；新任务无精确grant时不越权自授权；关闭域404、开启但状态表/维护配置缺失启动失败；旧备份ACTIVE与独立现时REVOKED相冲突时持续关闭。最后一项仍缺不随DB备份回退的可信高水位或经核证无该切片备份的开发部署证据，不能拿本机同库epoch自证。实际迁移/接口、受控维护身份与I均待本D通过后登记实现Task，不在本节执行。

### 4.3 签入包的状态粒度反查（仍未准入）

当前咨询包结构不能统一假定每条记录都有独立签名摘要。`DEV-CON-BOUNDARY-01`的P1/P2有`recordId/sourceVersion/contentSha256`，Q1无正文且只获QUERY；`DEV-CON-INPUT-01`的GOAL/SUPPLEMENT各有kind及原文字节摘要；`DEV-CON-FEATURE-CANDIDATES-01`的F1～F3有`featureId`和逐项摘要。这些可候选为有稳定键的record状态，但仍须把manifest版本、dataset、字段用途与逐条摘要同时核对，Q1不得从元数据QUERY推导SAVE或DISPLAY。`DEV-CON-COMPARE-INPUT-01`、`DEV-CON-CORRECTION-INPUT-01`和`DEV-CON-HUMAN-GAP-01`主要签整个payload摘要及字段用途；其中P1/P2片段、纠正三个字段、REQUEST_GAP/OPINION_GAP不能凭JSON路径或文本相同造出独立已批准的逐记录撤回版本。

因此候选`SourceState`需显式区分`RECORD_SCOPED`与`DATASET_SCOPED`，并将粒度、manifest ID/版本及原始摘要绑定在初始化证据中。前者缺确切record状态行整请求拒绝；后者只在dataset行完整、整包摘要/期限/用途和原签入字节现时一致时使用，不查询虚构record行，撤回只能作用整包。由dataset粒度切换到record粒度、增加字段用途或单条撤回都必须经新签入版本及独立D复判，不能在状态表上改标志扩权。仍须核同一内容跨旧/新包的许可交集，旧SAVE、候选DISPLAY和新包COMPARE_INPUT不互相继承。此处仅列可逆技术映射候选，尚未批准表列型、初始化者或实际状态行；第4.2节“缺record行拒绝”只适用于已冻结的record粒度，测试矩阵应同时覆盖dataset粒度缺行/漂移/撤回。

### 4.4 执法首切片的创建授权及恢复收敛（2026-09-27；共享D未通过）

本节按`ARC-ENF-SYNTH-MATERIAL-D-01`的优先级先收窄共享基础设施，不扩大其业务范围，也不为裁决CASE-001预授权。平台安全模块是路由、凭据和共享状态的唯一代码所有者；执法域只消费可信subject、签入用途及显式授权结果。项目负责人允许开发具名H具有全部**已定义的人工操作能力**，但没有批准无凭据、跨域或任意资源通配。下列是项目技术选择，部署身份/权限与恢复证据未落实前只作设计输入。

| 阶段 | 可信创建/维护者与最小权限 | 失败关闭及审计 |
|---|---|---|
| 初始登记 | 隔离开发维护者通过仅本机受控维护入口，凭既有已签入manifest的原始字节摘要登记`dev-enf-a` H凭据、`dev-enf-isolated`组织、旧包dataset及观察`DEV-ENF-OBS-001@V1/V2`两条record状态；逐条比对签入摘要/期限/字段用途后才建行。维护入口使用独立维护凭据，不能用H/A/J token；业务进程无状态INSERT/UPDATE/DELETE权限。 | 缺签入包、用途或摘要漂移则事务零行；逐对象保存维护者、配置版本、时间、请求摘要和动作审计，审计不保存密钥/正文。初始ACTIVE只针对确切旧包，不从v2 manifest补INDEX/COMPARE。 |
| 业务创建 | H用服务端已认证的域/机构/主体，查询对`SIGNED_OBSERVATION:DEV-ENF-MATERIAL-INPUT-01:DEV-ENF-OBS-001@V1`或`@V2`的精确`CREATE_DEV_MATERIAL_JOB` grant；该grant由维护者预置，不由普通H、Agent或HTTP参数生成。创建后执法域从不可变job的owner/org/input键检查`READ/PROJECT/CANCEL`，共享层仍对同一精确观察核现时grant及来源；不写新jobId通配grant。 | 无精确CREATE grant不得建job；同组织别的观察、旧包新用途、伪owner/自报组织均无效。跨job访问还须job owner及输入版本一致，不能仅靠观察grant读取另一H的job。 |
| 撤回/缩期 | 维护者按`expectedEpoch`在DM8事务内锁credential、grant、dataset、record的相关行，单调推进状态并记事件；撤回不能同键改回ACTIVE。业务事务按同一顺序先锁共享行再锁本域job，提交前重核。 | 撤回先提交则后续写/读及旧key重放拒绝；业务先提交只留历史，撤回后无正文。双维护者同epoch只有一成功，未能读取现时状态一律拒绝。 |

**备份与恢复的硬边界。** 当前`agent-platform-dm8`和`agent-platform-con-physical-lab`均不提供共享可信状态；后者已有空白库的加密备份/隔离读回试验，不能据此宣称前者无备份、可用现时高水位或已隔离权限。开发期推荐让维护状态的现时高水位由**不包含在待回灌DM8备份包内**的受控责任源持有，至少覆盖credential、精确grant、dataset/record撤回epoch与签入摘要以及清理水位。维护者每次状态变更须先形成可核的意图/版本证据，再提交DB状态；如果意图已记录而DB未提交或外部水位更新失败，恢复或启动必须保持关闭并由维护者人工对账，不能据旧ACTIVE自行选择放行。这里不指定尚不存在的文件、密钥库或远端服务为已部署权威源。

恢复只在无V2业务路由/worker的隔离目标执行；若现时高水位缺失、来源不可信、版本倒退、与DM8不一致或无法证明不随该备份回退，保持全部V2路由关闭。开发期可以由受控维护者以**新凭据/新grant/新来源签入版本**重新初始化隔离环境，但旧job、旧回执及旧受限正文必须先清理且不得重新开放；这不是旧任务可继续的恢复，也不能宣称生产RPO。若选择不备份的全新隔离实例，须先实际核证该实例/卷未被任何主机或容器快照、备份和恢复链覆盖，并禁止回灌；当前两个已知卷不满足此证明。共享D需要选定实际分支、责任账号/最小权限和配置证据，不能靠本段候选通过。

**D前可执行的设计反例清单。** 两独立服务实例共用隔离临时schema：域开关00/10/01/11与Spring映射双向一致，未知/关闭域404；无H凭据、无确切CREATE grant、record/dataset缺行、v1/v2旧用途交叉、不同owner读取均零正文/零`enf2`写；两维护者同epoch仅一变更；业务先/撤回先共用行锁得到相反且确定的提交顺序；响应丢失后重放先核现时状态；旧备份中ACTIVE但外部REVOKED/缺外部水位时全部不开；中途清理失败保持PURGING。设计须为每个栅点固定请求、事务、预期行数/摘要、错误码和咨询既有路由回归。实际C/I留给共享D通过后的实现Task，不能倒填本D。

仍缺：真实受控维护账号/数据库权限和来源、外部现时高水位的实际实现与备份范围证据、精确迁移列型/FK和逆向脚本设计、目标DM8配置及逐栅点C/I输入。缺项由平台安全/开发运维和数据服务负责人在项目内落实；未落实时共享D保持进行中，执法窄D不越级通过。

### 4.5 本轮物理探针准入复核（2026-09-27；notRun）

现有`Dm8DevTaskCoreIntegrationTest`仅在`DM8_JDBC_URL/USER/PASSWORD`三项齐备时，以随机临时用户运行咨询V023～V030并在`finally`逆迁移、删除该临时用户；它不创建或验证本节的共享状态表。本轮进程未配置这三项环境变量，故没有可复用的隔离连接前提。已知两个DM8容器存在也不能据此取其管理密钥、在旧业务schema试DDL，或把咨询测试结果改称共享状态I。共享表的迁移/列约束、维护者CAS、双实例锁序和旧备份现时高水位探针均记`notRun`，不是失败也不是通过。

下一次物理试验的最小入口证据为：指定隔离实例及临时schema创建/删除权限、受控维护主体与业务主体各自的最小DB授权、正逆迁移脚本和不随待回灌备份回退的现时状态来源（或可核证的无备份实例）。在这些输入未就绪前，继续审阅已有SQL/端口及失败关闭反例可以推进设计，但不能判共享D通过或登记共享实现Task；裁决CASE-001只读D仍依此阻塞。

### 4.6 共享状态候选迁移和恢复对账输入（D前设计，未部署）

仅对当前签入的项目原创开发输入，拟按`credential -> grant -> dataset -> record -> maintenance_event`建表，全部在独立`v2dev_*`命名空间，不引用`con2_*`、`enf2_*`或`adj2_*`。`credential`以`(issuer,key_id)`为主键且另对`key_id`设全局UNIQUE，否则复合主键不能阻止不同issuer跨域重用，保存`domain_code/principal_ref/org_ref/kind/secret_sha/status/valid_until/epoch`；`grant`以`(issuer,domain_code,principal_ref,org_ref,resource_type,resource_id,action)`为主键，保存状态、期限与epoch，不以credential为FK，避免凭据轮换改变资源授权。`dataset`以`(domain_code,dataset_id,manifest_id,manifest_version)`为主键，保存`scope_kind`、签入manifest及整包摘要、期限、状态与epoch；`record`以该dataset键加`(record_kind,record_id,source_version)`为主键，复合FK指确切dataset版，保存签入记录摘要、期限、状态与epoch。`DATASET_SCOPED`包不得凭JSON路径生成record行；`RECORD_SCOPED`包缺确切record行时整请求拒绝。所有键字段非空，摘要固定64位十六进制、epoch从1起，状态仅ACTIVE/REVOKED；字段用途仍只读签入manifest，不在共享表追加许可。`domain`在当前DM8作列名报语法错误，候选列名须用`domain_code`；其余精确长度、索引、正式迁移与空表逆向仍待D复核，不能由临时探针代签。

维护事件以独立不可变ID保存对象全键、动作、维护凭据引用、`before_epoch/after_epoch`、请求摘要、配置版本和DB时刻，不存正文或明文秘密；初始化对象记`0 -> 1`，随后只允许`after_epoch=before_epoch+1`，撤回同键不可回到ACTIVE，新增许可只能用新签入版本/新资源键及独立准入。每次维护在同一连接先按credential、grant、dataset、record稳定键顺序锁定受影响状态，`UPDATE ... WHERE epoch=?`要求恰一行，再写事件；失败全部回滚。业务事务按相同顺序先锁共享行再锁领域行，提交前重核DB时间、摘要、用途和epoch；不能在已锁领域行后补共享锁。单表直接写权限、维护入口身份及具体DM8授权尚未配置，故CAS合同不等于实际不可绕过。

旧DM8备份之外的现时责任源须为每个状态全键提供当前epoch/status/摘要/期限及维护事件连续水位，另覆盖已清理任务的不可复活水位；只有最大epoch不足以证明缺失的grant、record或撤回。维护者在DB变更前写可核意图，DB成功后对账标提交；任一方失败或有未决意图，启动/回灌保持所有V2业务路由与worker关闭，不能以旧备份中的ACTIVE补齐。恢复先验责任源身份、完整性、版本与备份范围独立性，再对每个对象核签入原始字节、现时状态、DB时间及更早期限，清理旧任务/回执并重置租约；缺键、外部版本倒退、备份含未知键、摘要不符、清理中断均维持隔离。现时责任源的具体介质/维护主体及其备份排除配置尚不存在，不能用本机同盘DPAPI密钥或同库事件自证。

后继C/隔离DM8 I须分别记录：同keyId跨域、缺grant/record/dataset、旧包新用途；两维护者同epoch一胜一冲突；撤回先/业务先各自提交顺序和两进程读取；启动缺表/缺责任源、旧备份ACTIVE对现时REVOKED、意图已写DB未提交、清理失败重试。每例记录确切请求、外部与DB前后epoch、领域表/回执行数、响应状态和零正文断言。D前可在隔离临时schema用非业务探针核候选列型、复合FK和清表顺序；正式`v2dev_*`迁移的正向、空表逆向和非空拒绝只在D通过并登记后继实现Task后执行。本D仍待实际维护者、最小权限、完整独立高水位和目标DM8可运行列型证据，执法业务路由仍关闭。

### 4.7 隔离DM8来源键与epoch候选探针（2026-09-27；D未通过）

`SharedTrustStateDm8DesignProbeTest`在项目原创空白`agent-platform-con-physical-lab`（127.0.0.1:5247）以随机`ST*`临时用户创建五张`st_*`非业务候选表。第一轮发现`domain`作为列名报DM8 `[-2007]`，改`domain_code`后运行1项零失败/错误/跳过：四段复合dataset主键及record复合FK可建，record指错manifest版本、重复record键和先删父表均被拒；dataset的`epoch=1`条件更新恰一行、旧epoch再更新零行。第二轮在credential设置`UNIQUE(key_id)`后不同issuer的跨域同键被拒，grant复合键重复被拒，维护事件`after_epoch=before_epoch+1`的跳号被拒。定向finally按事件/record/dataset/grant/credential删表，`USER_TABLES`无`ST_`残留，随后删除随机用户。定向专项、仓库全量后端测试与verify-all通过。

这只证明上述候选列型/FK、唯一/CHECK和单连接顺序CAS在该隔离DM8可行，不是正式`v2dev_*`迁移、业务/维护权限或双连接撤回线性化验证。完整列/期限/索引及维护审计合同、受控维护者和最小权限、独立现时责任源/备份范围、两进程交错及恢复失败关闭仍未取证；本D和执法材料D均不提升。

同一D前探针另创建随机`SV*`只读与`MT*`维护技术账号，分别只对临时`st_dataset`授SELECT及SELECT/UPDATE：SV能读epoch但UPDATE被DM8拒绝，MT的限定UPDATE成功；三名临时账号均在finally移除。此项只证明表级授权可区分，**不**证明实际服务/JOB/维护身份已经部署，也不证明维护者不会绕过事件审计；正式合同须让业务/JOB无状态写权限，受控维护命令在同一事务内执行CAS和事件，凭据来源、授予/撤销、审计及恢复水位还需独立配置证据。
