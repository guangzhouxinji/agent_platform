# ADR-DB-SINGLE-RECOVERY-01：单业务数据库＋强制恢复核验部署评估

日期：2026-10-07（Asia/Shanghai）。性质：根据用户本会话“先按一个业务数据库＋强制恢复核验评估部署方案”形成的技术评估，不是迁移执行批准、业务权限批准或新恢复合同验收。任务状态唯一见[主计划](../08-总开发实现计划.md)的ARC-AGENT-SINGLE-DB-ASSESS-01。原双实例准备保留为候选历史，不在本次继续实施。

## 1. 评估结论与批准边界

建议有条件采用一个DM8业务实例、三域逻辑隔离、受控恢复期间全部业务关闭的目标部署。取消的是第二个校验数据库实例，不是授权、来源、审计或恢复核验机制。当前源码不能只改URL直接采用此方案。

本次R范围仅为部署技术评估；未放行单库业务API、迁移、现时授权合同替换或实际数据恢复。docs/02第39节及[docs/69](../69-最终产品五项基础协议ADR.md)的备份外现时水位合同继续有效，未来须以新设计增量明确替代范围并通过D，不能改写原冻结记录。未提供新业务许可也不妨碍本次只读评估。

**保障区别**：单库候选保障“受控恢复后未经核验不开放业务”；不保障“任意时间管理员绕过流程原地回滚整库后，系统自动发现且维持全部最新授权”。仅依赖同库版本、摘要、恢复开关或签名旧快照，均不能证明其最新性。若后者仍是必需条件，应继续使用独立可信状态；外部状态不一定必须是DM8，但不能假装不存在这一依赖。

## 2. 最小部署与隔离

- 日常测试目标：1个DM8实例/容器及持久数据卷，不另建在线校验数据库服务。备份副本、文件对象、密钥及部署控制记录是必要资产，不计为第二个业务数据库。
- 保留三个独立AgentDefinition、应用入口、Skill/Tool、策略、评测与生命周期；同库不允许跨域内部Service调用或跨域万能DB账号。
- 第一阶段只合并实例，不同时合并同名表、Issuer、身份记录或重命名所有schema。建议保留3个业务schema＋3个安全/校验schema及各域最小权限账号（沿用现有名称或显式迁移映射）；这6个schema都在同一个DM8里，不是6套数据库服务。V001维护者singleton=1使得直接共用一张维护者表有身份串域风险。
- 同实例不具备物理故障隔离；三个领域同时受数据库不可用影响。容量、连接池和恢复耗时必须实测，不能因为容器数减少就承诺性能或RTO/RPO。
- 业务资料/来源原件在受管文件卷或对象存储，需与DB快照引用、SHA及保留规则配套。禁止仅恢复DB、忽略文件/模型回执/模板版本/密钥。
- 安全维护者的可信引导仍由库外部署凭据及具名责任确认支撑；恢复旧维护者记录不能恢复被撤销证书的管理权。密钥和控制文件不得与数据库一起盲目回滚。

## 3. 威胁边界与恢复门禁

### 3.1 运行控制面，不再复制一套实时授权库

复用部署脚本/服务管理和受保护运维记录，保存环境身份、恢复批次、允许启动的目标数据卷/实例、构建/配置摘要及核验报告引用；不在这里逐请求查询授权，也不在这里复制全部Grant历史。记录须处于业务备份之外，普通应用账号不可写。文件签名只能证明完整性，不能单独证明新旧；运维流程须禁止重用旧放行记录。

候选恢复状态（属于运维，不是案件/任务/审核状态）：

RUNNING → QUIESCED → RESTORED_ISOLATED → RECONCILING → VERIFIED → RELEASED

- 初始状态未知、控制记录缺失、核验失败、恢复进程崩溃、重启或更换目标卷：保持隔离，不自动转RELEASED。
- 正常服务重启与恢复备份区分处理；已放行环境的普通重启可按相同环境/部署身份核验，不每次全量重批权限。目标卷或恢复批次改变必须进入恢复流程。
- 不能靠数据库内maintenance=true、健康检查为绿、仅代理下线或环境变量RECOVERY_OK=true放行。
- 同卷内被特权人员绕过流程偷偷回灌无法靠卷ID发现，这是明确不支持的恢复路径；不得称单库实现与旧独立水位保障完全等价。

### 3.2 关闭业务必须覆盖全部执行通道

恢复前停止三个Agent/API/Worker/定时任务及其他写入者，断开旧连接池，隔离业务端口和外发网络；停止原有流式响应/下载，撤销旧短期访问凭据。恢复目标不接入业务网络、不带可用的外发服务凭据，禁用自动重启/调度拉起旧服务。不能仅停前端或仅关闭一个API容器。

恢复工具使用独立受限身份；普通业务/Agent身份没有恢复或放行权。未来恢复模式下仅启动最小离线核验/受控维护入口，不在JWT之外新增匿名产品API。运行时仍须在读取受限正文、文件输出、模型外发和写入前校验当前权限及环境执行代次；关闭门禁后的在途请求和旧Worker提交必须被拒绝。部署隔离和应用检查双层实现，不能靠其中一个替代全部保护。

## 4. 强制恢复流程与禁止自动恢复的内容

1. **封锁并静止**：登记新的恢复批次，关闭全部入口/写入者/外发；证明无在途写入与老Worker，保留源实例只读快照和原证据。不得在现行库上试跑恢复演练。
2. **隔离恢复**：将选定备份恢复到隔离目标，核DB迁移版本、三域映射、文件引用/SHA、必要加密密钥和备份完整性。备份完整不等于现时授权有效。
3. **先恢复管理信任**：由可信部署控制确认当前维护责任人与证书；不从旧id2_maintainer自行取得批准权。核验工具不可自动注册自身为管理员。
4. **失效旧执行身份**：旧会话、刷新凭据、授权码、用户/服务委托、Worker租约及放行令失效。新环境执行代次绑定Token/委托/Run恢复动作；只换JWT签名密钥不能取代Grant/来源恢复核验。具体字段/API待D实现，不宣称当前已有该全局代次。
5. **重建当前许可**：备份内Grant、SourceUse、删除状态先视为历史记录，不直接用于放行。有完整现时权威记录时逐项对账；没有时由具备当前资格的责任人重新批准精确主体/对象版本/动作/用途/期限，不能批量重批历史全部权限。原记录保留，新增恢复/批准事件，不擦除撤权历史。
6. **来源撤回与删除优先**：无法确认是否仍可保留/使用的材料继续隔离，不得把重新授予READ当作撤销删除义务。取得现时删除/保留依据后执行对应处置，再决定是否放行；来源原文、附件、检索索引、导出/派生文件和缓存一并核查。缺少最新删除记录不能标“全部恢复成功”。
7. **任务与外部副作用对账**：保留Task/Goal/Run/Checkpoint及已知回执，新授权不自动续跑历史任务。对模型外呼、正式提交、已生成文件或其他外部系统动作逐项核回执；结果未知则继续停在该任务，不自动重发。恢复旧预算/调用账本不能获得重复额度；待现时计费/回执对账。任务恢复继续使用原版本、SHA、fence、幂等和人工审核规则。
8. **验证后放流量**：核验报告绑定目标环境/批次/卷/构建、迁移版本、文件清单、权限/来源批准和对账结果，由当前有效责任身份放行；放行后才启动持有新代次的API/Worker。未核验的任务/来源仍不可用，不能用总体“环境已开放”覆盖局部禁止。

任一步失败保持隔离；恢复操作可重入，但同批次重跑不得重复发Grant、重新外呼或覆盖历史证据。中途崩溃后不得从备份中的READY自动开放。

## 5. 源码影响矩阵

| 现有实现/证据 | 可复用内容 | 必须变化与风险 |
|---|---|---|
| [ProductGrantWatermarkConfiguration](../../backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product/ProductGrantWatermarkConfiguration.java) | 正式身份启用约束、最小权限连接、启动失败关闭 | 当前拒绝相同URL；不得只删检查。引入明确恢复策略装配，独立实例模式保持兼容，新模式需环境门禁/许可证据 |
| [ProductGrantWatermark](../../backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product/ProductGrantWatermark.java) | 授权版本、并发CAS、返回前复验 | history_sha可继续检测不一致，不能再宣称防整库回滚；业务/授权事件和投影需评估单事务。不能将两数据源指向同实例就认为已同事务 |
| [ProductMaintenanceProof](../../backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product/ProductMaintenanceProof.java)及V001 | 维护者mTLS、角色、审计 | 旧维护者注册/撤销也会回滚，恢复引导需外部当前责任确认，不能漏迁这三张基础表 |
| [ProductSourceWatermark](../../backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product/ProductSourceWatermark.java)与SourceEngine装配 | 来源版本、PENDING、来源历史摘要/用途 | 来源未核验默认不可用；现时撤回和删除不能由同库旧epoch自证 |
| 执法ProductEnforcementAccess及来源/删除水位 | 证据、程序、处罚确定性规则、现时准入 | 多个Controller直接取independentDatabase；以小范围适配端口逐步收口，不能只改咨询后称三域完成 |
| [CaseWorkConfiguration](../../backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/adjudication/casework/CaseWorkConfiguration.java)的CaseSourceAuthority | 案件版本、材料SHA、用途授权 | 来源authority需单实例适配；保持争点/报告人工边界与源撤回后拒绝读取 |
| [ProductTaskRecoveryCheck](../../backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product/ProductTaskRecoveryCheck.java)及各RecoveryVerifier | Task/Checkpoint/fence关系、文件/检索/模型结果核验 | 当前强制独立URL并读取authority；拆分“恢复数据完整性”和“当前恢复放行依据”，不是删除原检查 |
| [咨询启动](../../scripts/start-consultation-container.ps1)、[备份](../../scripts/backup-consultation-container.ps1)、[裁决启动](../../scripts/start-adjudication-product-runtime.ps1) | 镜像/挂载/配置核验、备份加密、正式身份参数 | 当前含双库配置/假设，需单实例部署和全写入者静止；原咨询备份不能直接作为三域一致性备份 |
| [执法物理恢复脚本](../../scripts/verify-enforcement-penalty-physical-recovery.ps1)及现有HTTP/DB测试 | 真实恢复字节核验、业务反例 | 使用新隔离资源验证单库模式，旧独立authority证据不作新模式通过证明；不运行这些脚本修改现有资源 |

影响判断：中等偏大的安全基础设施改造，不是三Agent业务重写，也不是几项配置修改。先解决部署门禁和恢复信任，再适配身份/来源/任务恢复，最后做数据迁移；不能同时合并三个Issuer/表结构。没有实测依据不承诺工期、性能或恢复时间。

同库事务优化是明确后续设计点：若继续使用不同连接/事务管理器，PENDING与部分提交风险仍存在；要消除须使用同一受限事务连接原子写入所属域授权事件/投影/校验状态，证明跨schema最小权限与锁序。可保留旧PENDING协议作迁移兼容，但不得声称完成该可用性简化。

## 6. 发布、迁移与回退边界

日常目标仍是1个DM8。安全迁移/恢复演练可以临时同时保留旧实例和1个新目标，不是日常必须部署多个库；未经数据核对和验收不清理旧资源。本次未执行任何这些操作。

后继实际迁移须先冷静止三域全部写入者、读取各域现时业务及authority状态、检查未决PENDING、导出所有所属表/来源文件与密钥引用、生成确切SHA及映射，再导入目标。不能复制一个旧业务库就重算history_sha把它宣布现时有效。三域独立维护者及来源状态分别核验，不合并身份、Grant或issuer。

切换前原库保持可恢复；新库一旦发生写入，退回旧库也是一次恢复，必须重新封锁和对账，不能直接切URL或把旧备份的旧授权当现时状态。不得以“回滚部署”为名绕过单库恢复门禁。

## 7. 必须实际通过的验收反例（本次未运行）

| 反例 | 必须观察到的结果 |
|---|---|
| 先备份、后撤权、恢复旧备份 | 旧Token/Grant拒绝；新登录也不能凭旧Grant放行；仅新批准的精确权限可用 |
| 先备份、后撤回来源/删除、恢复旧备份 | 正文、附件、下载、索引和模型均不可使用；删除处置不被重新READ批准覆盖 |
| 恢复旧维护者/旧刷新Token/旧服务委托 | 不能取得维护权、签发可用新会话或启动旧Run |
| API直连、流式下载、Worker/定时任务、模型外发 | 代理已关仍不能绕行；恢复核验期间没有业务副作用 |
| 任一恢复步骤崩溃、重启、报告缺失或被篡改 | 环境维持隔离；旧READY/旧放行记录不能自动恢复运行 |
| 旧Worker晚到提交与撤权/放行竞争 | 旧fence/环境代次拒绝；新请求仅使用当前许可 |
| 已发生外部动作，但旧库缺少回执 | 不自动重发；缺现时回执的任务单独隔离，不假装成功或退款预算 |
| 三域schema隔离 | 咨询账号不能读写执法/裁决；各域维护者不能跨域授予 |
| 新库写入后回退旧库 | 重新进入恢复流程，不丢失已撤权/已提交事实后直接开放 |
| 同卷特权绕过流程回滚 | 记录为本模式不提供自动检测保障的威胁边界，不伪造“通过” |

每项要穿透真实HTTPS身份、业务服务、DM8持久化和必要文件/外发边界；单元/Controller测试只补反例，不能替代上述实证。产品测试和生产采用同一安全模式，不提供TEST_SKIP_AUTH或忽略来源核验开关。需测实际恢复时长、全体连接池峰值与失败恢复，不预设已达RTO/RPO。

## 8. 对主计划的影响及尚需批准的范围

本评估属于共享基础设施，不重开已冻结Agent业务任务，不改变咨询优先级、不扩大业务授权。不将评估等同实现。后继单库实现须新增任务、依赖和D证据；原双库准备仍待确认而非删除其历史。

实施前需要核定的核心取舍：接受“受控恢复后先隔离、逐项核验/批准再开放”，以及不再承诺无外部现时权威时自动识别所有绕流程整库回滚。技术设计可先继续完善，不要求用户代填表名/端口等工程细节；实际维护责任人、来源删除/保留依据和生产许可不能由工程团队虚构。

本次只完成源码与合同评估、设计文档和主计划登记，没有运行新单库部署、业务任务或恢复演练。前置verify-all退出1，已有1个测试证据stage失败；47份需求/41项台账基线一致。最终验证以命令原始输出为准，不把结构检查当安全语义或业务验收。


评估文档验证证据：[前置原始输出](../evidence/database-recovery-assessment-20261007/verify-all-pre.log)、[后置原始输出](../evidence/database-recovery-assessment-20261007/verify-all-post.log)。两次verify-all均退出1，唯一延期错误为TESTS_FAILING:project；治理/基线及独立检查完成，结构检查有既有软告警。初次文档12处源码/合同链接逐项存在性检查通过。该结果仅支持评估交付，不支持新模式运行或恢复安全验收；全项目测试健康仍非绿。


## 9. 单业务实例精简工程增量R/D（2026-10-07）

依据本轮用户明确工程授权（主计划ARC-AGENT-SINGLE-DB-ENGINEERING-D-01、IMP-AGENT-SINGLE-DB-AUTHORITY-ADAPTER-01）。以上评估与冻结证据保留；其第二实例必选、复杂强制恢复控制面/候选状态机不是当前最终部署准入。目标为一个DM8业务实例、三独立Agent、现有schema/账号/Issuer边界及原需求范围备份恢复；当前真实连接/数据迁移与旧库删除未获准。

第一切片只适配配置与专用authority池。product.identity.authority.topology缺省/independent保留原同URL拒绝；显式shared-instance允许authority URL缺省继承业务URL，显式值必须等于业务URL，业务/authority schema与账号均须有效且不同，未知模式或缺项建池前拒绝。保留各域原schema、账号与Issuer，不共用万能身份。连接池拥有者只关闭自己创建的池，创建/启动失败不泄漏池或凭据。

主会话接线ProductGrantWatermarkConfiguration；子智能体只写persistence/authority设置/工厂及其两测试。现有generation/history_sha/READY/PENDING/来源epoch及默认拒绝全部继续执行；专用连接和跨连接未决围栏保留，本切片不声称同库原子事务已经实现。ProductTaskRecoveryCheck和备份恢复脚本后继按相同拓扑适配，不能靠删同URL检查冒完整单库恢复。

不增加RECOVERY_OK/TEST_SKIP_AUTH等旁路，不建立另一套恢复核验平台。沿既有部署停写/备份/隔离恢复/核对原件和当前权限/缺证关闭过程，实际恢复放流量需现时责任依据，不自动复活历史授权、来源和未知外呼。单库内水位不保证识别特权人员绕流程同时回滚全部schema，这是技术能力边界而非授权检查关闭。实际单库发布/恢复未通过前不变更现行部署。

专项覆盖拓扑/标识/不同schema账号/池生命周期/失败关闭；主会话随后验证实际接线和必要回归。H2与组件只记工程证据；隔离DM8需新自有实例/凭据、明确内存CPU磁盘上限、原环境保持。新架构不能继承旧双库验证结论，真实隔离/并发/撤权/来源/恢复逐项另取证。

### 9.1 本批单实例DM8隔离工程验证细化

VER-AGENT-SINGLE-DB-ISOLATED-01依赖已完成适配专项，主会话唯一负责人。新增自有容器agent-platform-single-db-engineering-20261007-01和同名隔离网络；镜像固定现有本地DM8 SHA6409a1d…，不pull替换。仅127.0.0.1:5491发布5236，新生成受管凭据不进代码/证据；2CPU、4GiB内存（含swap上限4GiB），只读根，2GiB tmpfs数据、64MiB日志、128MiB临时目录，进程1000:1000。仅新实例创建CONPROD/CONAUTH、ENFPROD/ENFAUTH、ADJPROD/ADJAUTH及专用应用账号；三Issuer分开，输入为显式工程夹具，实际运行原授权算法不改行为。先核端口/唯一归属，失败不连接其他实例。

测试直接验证新Settings/Factory/Configuration对实际DM8同URL、不同schema/账号的连接及当前generation/history/READY/PENDING拒绝；跨域和业务/authority账号权限互拒，销毁专池不关闭业务连接。测试数据与凭据只属于隔离实例，无真实Task/来源/模型或专业决定；不计正常业务分母。只读恢复配置复用同一resolve，保留原全部恢复校验，不增加绕行。tmpfs隔离不能证明跨容器重启持久恢复，原需求备份恢复和数据/文件一致性另接续；不得把该专项外推为单库正式部署、三域完整迁移或生产恢复验收。现行两库、运行配置、固定A发行包及其冻结证据均不变。
### 9.2 持久隔离接续设计（2026-10-07）

VER-AGENT-SINGLE-DB-PERSISTENT-01只新增自有受限持久DM8工程资源，唯一端口/容器标签、固定本地镜像、2CPU/4GiB上限及新独立密钥；持久数据与临时旧环境不共用。首先实际DM8单容器停止/重启对比三域授权水位与schema/账号/Issuer、拒绝路径；然后用实际加密原生导出与新隔离恢复核对数据摘要。保留现行库/容器/卷，不回灌现行数据。输入仅已批准工程夹具或许可材料，不用当前过期正式Task。独立IT和证据只由子智能体B修改，主会话协调脚本/资源，不宣称身份重启等于完整三域业务/来源撤回验收。来源链及业务代表性在已批准合同下逐项接续，不新增强制恢复平台。

### 9.3 已完成持久身份子出口之后的来源与事务审查

VER-AGENT-SINGLE-DB-PERSISTENT-ID-01只继承实际硬限、真实DM8进程停启和独立认证恢复的36表身份原件，父任务保持PARTIAL。VER-AGENT-SINGLE-DB-SOURCE-01复用受限持久5492/5493归属环境及既有Source许可/准入/字段用途/撤回实现，以明确自有工程资料和专有工程身份验证；可以新增隔离工程Source字段/账号初始化，不能创建正式业务对象或给第三方资料代签许可。验证记录必须区分许可材料、实际DB变更、水位/摘要、撤源拒绝、跨schema拒绝及恢复后不复活。若仅DDL或直接seed通过，不称完整产品Source链通过。专业Golden缺失不阻止该机械工程出口。主会话负责部署/备份脚本；子智能体仅独立证据harness及自有工程资源，不能共用回归库或当前A。

ARC-AGENT-SINGLE-DB-TRANSACTION-REVIEW-01按真实DataSource和账号审查ProductGrantWatermark.change、ProductSourceWatermark.change/recover及主要调用者；同物理实例不等于同连接或同数据库事务。权威schema账号隔离无跨schema写权限时，不可仅删PENDING或用主事务管理器包另一连接来冒原子事务。有收益的域内结果提交仍复用同一主库事务；需要拓展账号权利或替换恢复语义的方案另作D，不以当前工程授权扩大权限。

#### 9.3.1 逐项事务审查结论（2026-10-07，本轮源码）

1. ProductGrantWatermark.change：authority先CAS写PENDING，业务操作由各领域事务提交，再按业务history_sha封口authority。shared-instance仍是两个DataSource、两个schema/账号，独占账号没有跨schema写权；给外层业务TransactionTemplate加包装不会让authority连接加入同一事务。保留PENDING、generation和摘要协调是当前隔离合同下必需，不合并万能账号，也不宣布防任意整库特权回滚。
2. ProductSourceWatermark.change / ProductSourceAdmission.admit、withdraw：许可/current/event/history_sha在主库TransactionTemplate中原子提交，authority状态先后在专池独立提交。源事件绑定intent、beforeHistorySha、exact epoch，失败仅在主历史未变时释放PENDING；同URL不构成删除这些证据的理由。域内提交已原子，无需重复重构。
3. ProductSourceWatermark.recover：S1释放与S2推进按确切intent、原epoch、主历史及已提交事件取证，authority事务使用其实际DataSource；保留各自专池。未知历史保持PENDING而不是猜状态。工程持久恢复不得更改旧决定或把WITHDRAWN复活。
4. ProductComparisonMatrixStore.commit / rejectBeforeDispatch：矩阵、行、引用、回执、invocation与candidate/fence都在同一主库事务中闭合，租约失败回调加入该事务；不为它引入跨域authority写。用户命令持久预留与mTLS外呼刻意分离，外呼不能纳入数据库事务。不将该必要分离误当需要删除的跨连接PENDING。

本轮结论为保留必要协调、复用已有域内原子事务；没有证据支持通过拓展运行账号跨schema写权获得收益，因此不做无依据事务改造。核对对象：ProductGrantWatermark、ProductSourceWatermark、ProductSourceAdmission、ProductComparisonMatrixStore、ProductComparisonStartCommands及其接线，以上只说明实现机制，不替代真实DM8来源/业务/恢复验收。

### 9.4 单实例部署及备份静止前检技术细化

IMP-AGENT-SINGLE-DB-DEPLOYMENT-PREFLIGHT-01是现有脚本的小范围只读前检，不是新的恢复平台。输入固定SINGLE_DM8_DEPLOYMENT/v1配置：唯一DM8容器完整ID、其唯一持久数据卷、上限CPU/内存、同一确切JDBC URL，三域各自Issuer和business/authority owner/runtime账号（六schema十二账号，全部唯一）。输出每域shared-instance环境映射但不含密码；不读业务原件、不改连接、不启动服务、不开权、不执行DDL。现行A配置不改。资源检查以docker inspect实际完整ID/标签/卷/上限为准，不仅凭端口/名称；备份或恢复前调用静止检查，必须列全三域写入者完整ID，三域全部停止，不能只停咨询就宣称三域静止。不自动停止/重启任何服务，不把前检成功当许可、恢复验收或整体单库交付。缺domain/重复Issuer/schema/账号/不一致URL/错容器/临时存储/超限/运行写入者均拒绝。凭据依然由原部署脚本受管引用；该前检不构造万能账号或绕开PENDING。

部署前检进一步严格绑定JDBC端点到inspect的确切容器：loopback映射必须为本容器5236/tcp的127.0.0.1端口；受管网络别名必须来自该容器且端口5236。只验证配置枚举的写入者静止，不宣称扫描出全部潜在写入者，也不由卷存在推定磁盘硬限。输出明确unlistedWritersProvenAbsent=false、diskCapacityVerified=false；正式一致性备份仍须责任方确认完整写入者范围和已有资源硬限原件，不能用此工具代替实际备份/恢复验证。该收敛避免新增另一套恢复核验平台。

### 9.5 执法既有业务合同的单实例持久恢复切片

VER-AGENT-SINGLE-DB-PERSISTENT-ENF-01不重开已冻结模板开发任务，仅验证单实例新架构不继承旧双库结论。当前DocumentTemplateService/Dm8DocumentTemplateStore/LocalDocumentTemplateFiles保持不改，输入仅project-functional-template.docx精确SHA5656160c…及manifest的FUNCTIONAL_TEST_INPUT许可；不作为正式模板/案件。5493真实持久容器a2df7cece…独占进程窗口，先核新ENFPERS20261007_BIZ/AUTH与各自owner/runtime账号不存在，应用确切必要身份/authority及V074DDL，维护工程身份/Grant按既有合同建立且不空权限回调。经历CREATE/PREVIEW/PUBLISH/DISABLE保存四原回执/事件、原件和预览。只静止本新切片写入者，源停启前后核DB/文件SHA；独立随机密钥认证加密原生导出本新schema和必要关联文件，5491新同名独占schema恢复并用当前产品实现读取。缺件/损坏只在新负例副本验证，不改正例或原证据；原5493身份/Source和5491原工程schema摘要前后不变。5491 tmpfs目标只证明独立导入/读取，不证明目标持久停启。文件上限256MiB、保护包512MiB；新AppData single-db-enforcement-persistent-20261007-01目录分source-files/restored-files/negative-copies、凭据只vault。不得碰5492、现行库、Worker/模型，不能对旧水位/Grant或Source续期。新资源不增、进程顺序与B互斥，工程原件明确专业/B仍待验收。

初始化前尚无业务写入者时，配置必须显式列出writerContainerIds空数组；只允许离线和资源只读前检，RequireStoppedWriters入口拒绝任一域空数组，不以“没有枚举”证明“已全部停写”。该细化允许对已归属隔离DM8实际资源检查，不为备份或发布提供替代批准。

资源前检支持已停止的自有DM8：只核HostConfig中既有loopback端口绑定，运行中则核实际NetworkSettings绑定；输出databaseRunning与databaseConnectivityVerified=false，容器配置匹配不代表端口在线、JDBC或业务就绪。不因前检擅自启动停写数据库或应用。

### 9.6 裁决原合同的单实例机械报告恢复

VER-ADJ-SINGLE-DB-REPORT-RECOVERY-01依据已有IMP-ADJ-V2-TASK-01B/01D及原创task-first-loop-input-v1 manifest，仅MECHANICAL_DOMAIN_TEST：一案两材料一主张，无法律源，不产生真实案件结论/专业金标/生产来源或外部模型使用。复用当前CaseWorkCommands、JdbcCaseWorkRepository、CaseWorkCodec、CaseSourceAuthority，严格工程Access port绑定自有scope/source/action/current generation，不能用空许可回调或业务seed代替命令。5492仅新ADJENGMAIN/ADJENGREST/ADJENGAUTH与专用运行账号（先核无人占用），MAIN/REST原V086六表，AUTH既有来源权限三表；正常业务权限不由备份复活。原生业务导出与指定原创输入原件认证封包、真实DM8停启、恢复REST并核版本/审计/回执与字节SHA。撤回保持AUTH当前权威，恢复旧业务/文件不能复活读取；密文篡改恢复写入前拒绝。仅验证此次纳入工程原件，不冒完整生产附件服务、第三方版权或专业准确性。证据harness只在evidence/single-database-persistent-20261007/adjudication-report-recovery-01，凭据只vault，沿用2CPU/硬限loop与顺序总4GiB，5493运行则不启5492，不扩容；不触SRC/START/原三域、5491/5493/5497及共享源码/主回归。主计划状态唯一，不增恢复平台或并列台账。

### 9.7 单库前向初始化清单的限定技术细化

依据IMP-AGENT-SINGLE-DB-LIFECYCLE-SCRIPTS-01现有工程R/D；只生成前向执行清单，不执行DDL、不接触现行数据库。受限输入包含三个独立域的六schema、已确认非空/空表清单、精确目标ID，以及逐项migration ID、所属schema、SQL原件SHA与显式依赖。SQL内容只能从工作区db/dm8下确切原件取摘要；同ID跨schema允许，按schema+ID唯一。每个已应用记录必须有匹配的原件SHA与实际对象核验依据；缺迁移日志不能推定未应用，存在未知对象/未知迁移/已应用摘要冲突时拒绝输出可执行清单。依赖必须在同清单内、无环，已应用项的依赖也必须已应用；输出仅尚未应用的拓扑排序项和已应用不重放项。非空schema的待应用项必须绑定明确前置对象检查、前向非破坏性审查证据SHA及证据引用，缺失时拒绝；这些机械引用不自动证明专业或运维批准。资料、保护有效性、实时对象检查和实际执行批准仍由现有发布/保护合同另行核验。不得由此开放initialize-admission、保护或恢复出口；该工具只消除盲重放，不把离线清单当实际迁移完成。

### 9.8 单库整体生命周期限定实现D

依据用户本轮工程授权，接续IMP-AGENT-SINGLE-DB-LIFECYCLE-SCRIPTS-01。实际目标只允许已明确归属、受限资源的隔离环境，不复用/覆盖现行数据。先通过既有部署前检精确容器/卷/CPU/内存/三Issuer/六schema十二账号配置。完整库存必须实际读取六schema的表/列/约束含FK/索引/sequence/view/trigger及账号授予，未知对象/依赖不得自动重放迁移；分开空库初始化与已有对象升级路径，并持久记录原键、逐项脚本SHA、前置检查和成功/失败现场。DDL非事务时不能假装全回滚，重复执行先实际核验对象与日志再决定，不删非空数据。

三域保护必须由完整写入者列表与责任依据证明静止，不自动停现行实例。只在实际拥有环境中进行，所有三域数据库schema及必要文件同一静止区间内导出，核导出前/后完整对象/行与文件摘要。原生DM8导出或备份和必要文件认证加密封包不向外披露；审计保留与实际恢复使用有效性分开，按实际数据合同更短期限。恢复至独立自有目标/新schema，不覆盖源，核业务回执/引用/文件SHA/迁移与最小权限及当前权限水位；旧备份不得复活被撤身份/来源。完整报告有任何缺项即保留PARTIAL，不将已有独立域回执拼成同点快照。不新增恢复平台，不切连接或删旧资源。

执行者负责scripts单库生命周期新工具/测试与evidence自有Java/JDBC工程harness；不修改咨询/身份/来源业务实现、主计划、已固定包或共享构建输出，不跑全Maven。资源启停仅在已批准工程持久5492/5493确切ID/卷和顺序4GiB限制内，先核对，二者不同时运行；缺归属或保留依据不得新建目标或打开旧包。首个可交付子出口为真实六schema库存、初始化/升级执行准入与必要动作的实现验证；整体物理保护/恢复如未完成必须明确欠项，不能用元数据前检冒充。


### 9.9 保护操作途中现时准入重检
FIX-SINGLE-DB-LIFECYCLE-DEADLINE-01属于9.8既有工程合同的故障关闭修正。入口批准时间不能冻结导出、原件读取、封包与解密全过程；在每个异步边界后、读取原件前及成功返回前重检动作批准、数据库资料及必要文件的实际使用期限。采用不小于入口时间和当前实际时钟的检查，不能回拨或扩大期限。到期时拒绝继续及成功回执、释放写入围栏并清除本次内存明文；保留真实失败，不删原数据。若已进入文件写入后才到期，不能冒成功或续期，已写加密原件保留其真实到期头，后续打开仍拒绝。读取加密包先核有界公共头，解密交付前再次核实际使用期限；审计保留不代替恢复使用有效性。仅自有工程包验证，不打开旧过期保护包，不据单测声称DM8原生恢复完成。

同一修正涵盖已有初始化/升级执行器：DDL前后、受管库存返回后和总成功回执前核实际动作批准，existing保护准入也重检。若期限已失效，不启动下一DDL；若正在执行的DDL在到期后返回，保留真实START/FAILURE和非原子失败现场，不写SUCCESS，不声称逆迁移或全回滚。该机制不擅自取消已提交数据库操作，不授权现行DDL。

### 9.10 原生DM8导出与有界数据库围栏适配（2026-10-08）

`IMP-SINGLE-DB-NATIVE-FENCE-01`是9.8的工程细化，依赖既有目标/期限/库存工具。适配只接受批准绑定的六schema、十二账号、确切Docker目标及完整写入者责任清单；三域应用写入者必须按部署清单实际停止，维护操作持独占本地文件锁。使用独立JDBC连接在同一事务内持有全部六schema基表SHARE锁，前后核基表集合并用该连接重检存活；获取失败/丢连接/写入者复活不导出或返回成功。SHARE锁不是账号权限或所有DDL/sequence/文件写入的全局围栏，仍须具名责任依据证明完整DDL、权限、sequence及文件写入者静止；缺该依据只失败关闭，不由空清单、会话数0或Docker暂停推断完整围栏。

原生导出固定使用已安装DM8工具的SCHEMAS模式、GRANTS=Y、DROP=N、WITH_UR=N、单线程及有界单文件，不用FULL纳入其它schema。秘密只经环境/标准输入进入受管进程，不进入公开配置/日志；输出由DM8原生加密并进一步沿既有认证封包，必须保留恢复所需受管密钥引用。原生退出非零、警告、错误、分卷、过大或空文件均拒绝成功；导出前后核现时批准、原件合同、目标及围栏。新随机输出不覆盖原件；超时后保留原生文件和真实失败，不声称撤回已经执行的数据库读取。该适配不签发资料许可、维护权限或DDL批准，不证明当前持久工程数据均有有效使用合同。实际整包导出/恢复尚须核各纳入数据与文件的现时用途、完整写入者责任证据，以及独立恢复的权限和引用一致性。不得打开旧过期包，不切现行连接，不停止原咨询服务。SQL语法和锁语义先在已归属隔离DM8验证，不能只凭端口替身计原生通过。

文件责任：主会话独占scripts/lib/single-database-lifecycle-native.mjs及测试、scripts/java/SingleDatabaseFence.java、snapshot CLI接线和本ADR；子智能体不得改这些共享文件。退出：适配及失权/超时/集合漂移反例工程验证；实际DM8锁/原生导出/独立恢复分别取证，缺现时资料合同保持PARTIAL。

实际锁语义原件：2026-10-08在既有自有5492顺序资源中运行生产SingleDatabaseFence，同连接持六schema/36基表SHARE锁，竞争连接EXCLUSIVE实际被DM8 -608拒绝；HELD重检一致，RELEASE后EXCLUSIVE可获取。无DML/DDL/原件读取/原生导出，5492/5493结束均停止；不是完整写入者围栏或三域备份恢复通过。原件见evidence/single-database-native-adapter-20261008/actual-lock-result.json。原生密钥引用纳入外层认证头，避免恢复包只有密文而无可追踪受管密钥引用；不保存密钥正文。


### 9.11 原生封包独立恢复消费及命令行（2026-10-08）

`IMP-SINGLE-DB-NATIVE-RESTORE-01`沿9.8—9.10限定恢复D，只实现恢复消费，不新增数据/DDL/运维批准。源封包targetId与独立恢复targetId分别绑定；restore动作批准必须精确绑定包SHA、源目标、配置SHA、受管外层密钥引用和原生密钥引用、六schema/十二账号、数据库及文件现时使用依据。预置账号的独立目标必须对象/行/sequence等全部为空、账户权限与批准的空目标库存一致。不得用空会话推断完整写入者：三域非空注册清单实际停止，具名维护排他责任配合本地独占锁；导入不持SHARE锁阻塞自己的DDL。

恢复入口先保留新attempt日志，再打开认证封包；解密/导入/文件恢复/库存返回/成功返回各边界重检动作、源包、数据库与文件使用期限及目标/围栏。原生工具固定SCHEMAS、IGNORE=N、GRANTS=Y、单线程、有限超时，不使用FULL、APPEND、TRUNCATE或REPLACE。原生密钥仅环境/标准输入；日志只保留摘要与安全错误码，不保存工具正文或凭据。原生启动前持久记录IMPORT_STARTED，非零/超时/部分DDL、未知结果不自动重发；同attempt或同目标恢复标记已存在即拒绝，须按真实现场另行处置。

数据库与必要文件不能声称同事务回滚。文件只写批准的新独立目录、固定相对路径、wx不覆盖，确切原件映射及SHA逐项核验；任一步失败保留数据库、文件和attempt现场，禁止自动DROP/回灌/删除。导入后的六schema对象/行/权限库存与源包（仅规范化targetId）必须一致，文件SHA一致，末尾现时准入失败不返回成功。恢复成功只是隔离恢复消费证据，currentAuthorityOutsideBackup必须为true且仍不开放业务写入，不激活旧身份/来源，现时水位/撤回及三域业务恢复另有验收。端口反例明确不是实际DM8导入；实际dimp仅在确切目标/数据/文件/DDL批准匹配且完整责任原件到位后执行。

唯一写入者：主会话负责恢复协调器、原生适配、CLI、verify-all、ADR和主计划；执行子智能体仅新恢复专项测试文件，不动共享文件、不操作Docker/数据库/模型或全量构建。退出：CLI与受管适配接线、失败关闭专项及治理通过；真实DM8消费单列待验证，不关闭父任务。

9.11现时决定采用已登记责任方原始restore决定文件的受管路径和确切SHA引用，CLI配置只派生此引用，不签发决定。每次围栏重检重新读取原决定，字段与批准完全一致且SHA不变才允许；撤回/替换/缺失即拒绝。源卷由同决定明确，必须与恢复卷不同；原生DDL许可及文件输出根目录单独绑定。该文件不是新状态台账，也不由工具自行生成批准。


9.11命令行是 `node scripts/restore-single-database-lifecycle.mjs CONFIG.json NEW_RECEIPT.json NEW_JOURNAL.jsonl`。配置延用既有deployment/inventoryJdbc/schemas/accounts与adapterPath；restore特有项为sourceTargetId/packagePath/packageSha256/keyEnv/keyReference/currentDecisionPath/fileRoot/files及nativeRestore（user/passwordEnv/keyEnv/keyReference/timeoutSeconds/maintenanceLock/attemptMarker）。批准绑定configurationSha256、restoreConfigurationSha256、adapterSha256、emptyInventorySha256、sourceVolume、nativeImportAllowed、nativeToolEvidence、fileRoot、来源/文件现时期限及完整writer责任。currentDecisionPath原始决定不含派生的currentDecisionEvidenceSha256，配置approval增加该SHA但其它字段须与原件完全一致。秘密正文不写配置。三域writer清单不能为空；无完整责任不调用原生工具。恢复attempt标记含目标/包摘要及原生文件名、阶段和日志SHA，跨进程保留防盲重发；恢复journal的RESTORE_CHECKS_PASSED仅表示当时检查，通过后仍要现时复核和独立成功回执，不把中间事件当最终成功。

9.11集成审查发现认证包内必要原件列表原先仅逐项some匹配，未证明一一对应；本切片在既有v2 reader收紧数量、唯一原件键、SHA/许可引用及规范base64、总容量校验，导入前拒绝认证列表缺项/重复，不等到部分文件写入才失败。解密拒绝也清零已分配native缓冲；不改变旧原件字节，不打开旧包、不倒填回执。

9.11原生执行前核实际安装dimp HELP的批准SHA，不以工具名推定版本；每次导入前（含原生文件暂存后）要求源包和目标动作/数据/文件剩余使用窗口均超过受管timeout加15秒安全余量。未达到不启动dimp；途中失效仍在异步边界拒绝成功，已启动非原子操作保留真实现场。

### 咨询既有Assumption物理执行限定细化（VER-CON-DM8-ASSUMPTION-EXECUTION-01，2026-10-08）
继承第9.2/9.3已批准受限5492资源、纯自有工程夹具及身份/Task D，不新增数据库拓扑/业务授权。RBC11六类实际跳过原因来自保留XML；Identity/TaskPhysical只缺DM8环境，ModelFence只缺专用DM8变量且测试明确provider/authority为夹具，不需要真实模型批准但绝不能冒真实安全整链或模型能力。双实例Grant/Source/TaskService要求不同URL与同时在线，本批5492/5493顺序总4GiB合同不能满足；不改Assumption、不为换绿同URL伪装独立库、不同时启动。
使用现有5492确切容器e5e5000c0d55a3da109f7963067416bd12e0014800391856cb74c2647318d1b2，先核镜像SHA、标签/UID、绑定端口/卷/辅助PID、capless及3840MiB+两个128MiB辅助总4GiB/2CPU，5493必须停止。当前工程批准没有另设执行截止；不套A运维窗口或备份使用期限，不访问历史保护包。仅测试生成新ID/CT/CMF随机schema及临时工程账号，沿原测试DDL/异常/reverse/清理，不对既有六schema逆迁移或DROP；原断言及跳过条件保持。凭据从已有DPAPI引用只入子进程环境，不进日志/代码/回执。原始执行日志/XML留独立私有构建目录；公共必要结果若含凭据仅脱敏凭据并记录原件SHA/位置，不能删除失败内容。测试子进程有界，失败保留现场且停止本自有DM8进程；无重启现行服务/新资源/模型/Task授权。当前源码与独立副本逐字节核对，不从旧HEAD遗漏成果。执行/失败修复和物理重验单独取证，129旧跳过不改成旧通过。

### 单实例协调故障物理细化（VER-CON-SHARED-DM8-COORDINATION-FAULT-01）
第9/9.3/9.3.1已有PENDING及Source精确S1/S2恢复合同不变。旧SOURCE withdraw通过的是最终CAS处Java异常，不据此声明真实进程死亡。新增显式shared-instance工程测试：5492现有确切ID/硬限/受管管理员引用，只新随机两个owner schema及两专用runtime账号。owner仅安装原V032和Source必要DDL；runtime只取自身指定表的SELECT及必要INSERT/UPDATE，Source authority按已存在S1_DELETE/恢复事件合同取得自身表DELETE/INSERT，Grant authority仅自身水位SELECT/UPDATE；无跨schema业务读写。实际受管AuthorityDataSourceFactory/Settings与独立Hikari业务池，单URL不是伪装双实例；原独立URL测试及Assumption保持。
新测试子JVM只继承本次自有runtime凭据（环境不落盘），用文件屏障证明产品change已持久化PENDING，暂停于未提交DML或业务提交后最终authority封口前。父测试终止且等待该确切子JVM，随后关闭旧池、以新专有池重连读取实际DM8intent/history/epoch/generation/业务行，不能根据抛异常猜提交状态。Source未提交DML进程死亡必须回滚原交易并允许精确S1恢复；提交后进程死亡须有真实Source事件/intent及已封历史才能S2，错误intent/未知history不能释放PENDING。原件记录SHA及阶段，不上传专业输出/业务许可。Grant现有代码只有change及assertCurrent，未有获准S1/S2恢复入口，故只证明中断保持PENDING及重连拒绝；不发明自动恢复或维护者决定。
故障仅本自有子进程/新schema，不停数据库制造故障、不改原服务/现行数据/旧SOURCE样本/授权/角色。成功后仅清理本次新对象；测试失败保留新随机现场和原始诊断，不自动删除或重复旧attempt。新测试入后续当前源码回归，RBC04—11不重放；必要全量须新的合法有界资源。无模型外呼、正式Task、native导入、旧保护包或新发布授权。

上述物理测试第01/02轮实际暴露DM8 runtime外键写入需REFERENCES父表，单独SELECT/INSERT/UPDATE不足；限定补充仅本次业务schema的id2_subject、con4_source_contract、con4_source_admission三个确切父表REFERENCES，未授authority或其它schema权限，不改变生产账号/角色。这是现有业务DML合同在DM8上的最小工程权限细化，不用RESOURCE/DBA授给runtime，不删外键。子JVM控制台明确UTF-8并保留私有日志，不能让编码错误覆盖实际SQL诊断。第01轮默认TempDir清理导致独立child日志未保留，父Maven/XML及失败schema仍保留；后续显式NEVER保留，不能倒填首轮child原件。仅孤立contract不是封入当前Source/history的历史变更，未知history反例收紧为写入无合法历史seal的current/event，要求实际history变化且拒绝恢复；不修改Source生产摘要范围。

### 9.12 Grant未决恢复的只读准入与职责分界（机器观察记录2026-10-08）

依据docs/69第1.3/1.4身份D、9.8既有离线只读恢复校验，以及当前用户“核现有D、明确合同编码、未决权限责任集中提出”的指令。FIX-IDENTITY-GRANT-RECOVERY-ADMISSION-01的技术D仅细化原恢复账号已允许读取的issuer精确水位及ProductGrantHistoryBatch既有摘要；不批准新的维护读权限、公开接口、业务恢复或schema变更。

在ProductTaskRecoveryCheck既有入口及返回前加入ProductGrantRecoveryEvidence：复用ProductWatermarkJdbc与现有同一history算法/表发现，不另造摘要。读取authority前/中/后状态及两次业务摘要，观察不一致拒绝OBSERVATION_CHANGED；相同观察不证明跨连接原子性或不存在未观测竞态。READY须非负generation、无intent、合法64位SHA且与业务摘要相同才可继续原全部检查；缺失/重复/非法水位、缺表/读权限或历史不可读均拒绝。PENDING必须保留，无论业务摘要与原摘要相同还是变化，均非恢复许可：分别输出PENDING_HISTORY_UNCHANGED/PENDING_HISTORY_CHANGED；后者不等于S2，不证明哪条业务命令提交。

诊断仅输出format、机械code、配置issuer、generation、intentId、recordedHistorySha、observedHistorySha、observationsStable、repairAuthorized=false、mutationPerformed=false。不输出账号口令、SQL、主体或grant正文，不保存或签发批准。主命令拒绝时输出有界JSON并仍以异常/非零退出；不能把诊断捕获改成恢复成功。既有恢复校验仍全部执行，最终再检查，运行产品水位构造/请求授权路径不改。只读类不含INSERT/UPDATE/DELETE、事务提交、状态改变或恢复开关。

必要验证：READY确切摘要、PENDING两种历史、错误/缺失/重复authority、不可读history及观察竞态、无写调用、原记录SHA/水位不变；同URL双schema受限账号真实DM8关闭旧池重连仍得相同机械码。复用已批准5492/5493顺序资源约束，仅新自有工程schema，不重做已完成6进程死亡/11物理场景。源/业务字节许可和真实运行不由工程数据签发。主会话独占生产类/离线接线/护栏/主计划，测试执行者独占新测试；全量由主会话源码固定后统一执行。

Q-ID-GRANT-RECOVERY-01是独立未决写入D：现有维护GRANTS职责是否包含显式修复，谁对确切issuer/intent负责、如何认证/职责分离；S1释放的原意图/原代次/原摘要和当前不变证据；S2需原业务回执及不可变intent/目标摘要绑定。Grant现有事件未绑定intent，不得仅以最新摘要推进generation或仿Source恢复。建议先授权严格S1，S2待新增可靠绑定D；此为建议而非安全批准。责任方原决定到位后再登记实现写入任务，当前不新增恢复端点或修改PENDING。
9.12职责反查补充：ProductGrantWatermark.change同时围栏grant授予/撤回、SUBJECTS停用、workload及委托变更，不是所有intent都属于GRANTS职责。仅issuer/intent/generation/摘要无法确定原操作类型或责任人；即使摘要未变也不能据此选择修复角色。构造函数assertCurrent会使PENDING时业务应用启动拒绝，恢复入口不能以关闭该检查来启动：受控离线签名决定或独立维护mTLS入口的认证/职责分离须由责任方明确，不新增匿名/万能管理员绕行。原操作职责与恢复审批职责取交集，无法证明原操作/责任或回执关联的旧intent仍保留。当前只读诊断不返回S1/S2可执行结论。

### 9.13 最终单库工程基线与空实例边界（2026-10-08）

依据用户本轮《单库最终收口与测试发布执行指令》，沿ARC-AGENT-SINGLE-DB-ENGINEERING-D-01及9.7—9.12，不改历史原件。单库是唯一后续工程目标；independent原默认仅历史兼容，不是新环境隐式回退。物理独立恢复目标是临时验收/恢复资源，不是产品必选第二库。旧多库保留当前运行维护，切换批准前不改其连接或数据。

完整源码覆盖核对读取db/dm8、db/identity-authority全部V原件及当前Java/配置引用，输出SHA、对象和未覆盖引用；只提取技术事实，不从文件名猜测领域或放行业务。开发专用历史SQL不得自动纳入产品对象；完整安装清单必须由已过D的产品依赖明确选择及证明排除，少量工程表不等于完整产品库。catalog输出不执行SQL、不签发R/D、不另建状态台账。子智能体唯一允许写scripts/lib/single-database-source-catalog.mjs、对应.test.mjs及scripts/catalog-single-database-source.mjs；禁止共享源码、迁移编号、主计划、verify-all、数据库或构建目录。

空实例管理bootstrap是现有owner迁移工具的前置，不是现行数据升级：绑定确切受管新目标、六schema/十二账号、配置及管理员动作清单SHA与现时原始决定；需要完整writer维护围栏。账户schema创建/有限系统授权只经独立管理员JDBC端口；应用及owner迁移仍沿既有受限端口，严禁应用账号DBA/RESOURCE或跨域授权。管理员动作由批准清单显式列出，口令仅环境引用，日志不能写SQL或秘密。对缺账号、部分DDL、错误授权、重入及摘要漂移保留现场、拒绝盲重发；每步实际inventory与显式后置核对，不把退出0当结构成功。非原子DDL不承诺回滚，不自动DROP/逆迁移/删除。

测试发布与现行迁移是不同出口。新环境默认无现行历史结果依赖，不将三份隔离处置、模型预算或专业金标作为单库基础部署前置。咨询仍需确切来源用途/CLAIMS映射及HTTPS/mTLS接收准入才能验证真实交接；无模型准入严格零模型/零计算attempt/零矩阵。执法/裁决不超已有D。实际A/B必须完整writer、必要文件同点与独立原生恢复及三应用使用/撤权/重启证据；离线工具和端口测试不证明A/B。当前Docker连接失败是执行现场限制，不是新增业务决定，也不自动批准启动旧资源或现行连接切换。

9.13结构核验细化：沿既有executeLifecycle保持旧object前置兼容，新增显式对象类型、列类型/可空性、约束类型以及真实table-rows范围前置。所有声明在首条DDL前校验，不以缺少迁移日志猜测执行，不通过删除行满足空表条件；列/约束查实际inventory，新增列失败不能用原表仍存在冒称升级成功。原库存字段算法由原file-H2证据适配提取到独立DM8-only受管JDBC类，DM8 owner执行不再走H2限定分支。空实例bootstrap围栏不锁尚未创建的业务表：新目标实际归属/静止writer检查加原责任方完整registry与维护排他证据、跨进程维护锁；应用尚未创建必须显式applicationsNeverStarted，不能从空列表推断。该协作锁不声称阻止未登记外部DBA，责任原件缺失拒绝。批准配置/source/class/fence摘要绑定，原决定途中重读，不新建恢复平台。

9.13既有裁决来源原件接入：实际源码依赖的三张ADJ2_SOURCE表已有db/dm8/adjudication_source_authority.sql及裁决API持久化D，不是缺需求，也不新造迁移DDL。保留原件全部字节/原编号，新增纯技术受控辅助迁移注册A001（deployment/single-database/auxiliary-migrations.json），绑定原件SHA、既有裁决authority范围及原设计引用；安装实例键仍schema:id。该原件唯一INSERT是既有singleton_key=1/generation=0/READY初始化，不授予主体或资料用途；仅空authority三表均缺且原审批新环境允许时执行。existing路径未记录完成则拒绝自动初始化，已成功只验证结构不重发。CLI强制A001落在deployment裁决authority schema；不允许其它未版本化SQL或任意DML，不复制单库/多库业务SQL，不改现行源记录。


### 9.14 最终安装profile与真实接线技术细化（2026-10-09）

沿既有身份D（docs/69 1.3/1.4）、三域最终产品D及9.13。最终清单显式固定正式应用装配与全部当前依赖，不以历史V0/DEV原件自动成为产品，不为缩表静默关闭已交付能力。111 V与A001逐份选择/排除理由、设计/实现引用及SHA，共享identity/Task/Runtime原件按真实app导入/DAO依赖复制安装实例而不复制业务源码/SQL。两V074仅各自域schema同号实例，所有对象级跨schema依赖和最小DML/REFERENCES授权显式记录，应用不得拥有DDL/ANY/RESOURCE。

正式ProductIdentityBootstrap仍硬拒同URL，是实际初始化阻断而非新业务决定。仅新增显式shared-instance初始化路径，复用AuthorityDataSourceSettings，actual JDBC URL/schema/owner/runtime均核对、owner不与runtime或backup重用；原independent入口/测试保持历史兼容，无新环境回退。所有证书/CA/职责/空域/原watermark/维护审计、同authority事务、PENDING与摘要检查原样保留，不新增主体、Grant或Source授权。原合同要求独立只读backup账号，最终部署须包含三authority backup账号，不把12工程账号库存当最终账号完整；部署v2允许准确三域backup账号，各仅CREATE SESSION及本域authority现有SELECT，六业务/身份schema不变。旧v1/12账户原件仍按原范围有效，当前新安装用v2/15账户，不能同构假装旧v1覆盖v2。

固定profile生成工具消费的bootstrap/owner-migration/grants/初始化引用及正式应用配置，不生成运行许可。物理目标ID只能从新获准命名资源实际创建/inspect绑定；缺空库存/确切决定/角色证书/managed-key字段必须在DDL前拒绝，不能以模板空值运行。现行升级沿保护/非空前置/日志和部分DDL现场合同，新空环境不得读取旧数据或打开旧包。3条未覆盖引用都是当前代码的系统目录适配，实际DM8库存权限及catalog查询须真运行；动态SQL只核本次装配影响，不扩展SQL平台。

写入分工：主会话维护共享配置v2/installation编译/资源/profile/生命周期JDBC及计划。执行者A仅scripts/lib/single-database-object-grants.mjs、.test.mjs、scripts/grant-single-database-objects.mjs、deployment/single-database/application-capabilities.json及对应独占新测试，不动共享JDBC/计划/SQL/数据库/全量构建。执行者B仅ProductIdentityBootstrap.java及独占新shared初始化测试，原测试及语义不重写，独立构建目录，不操作DB/全量。后端集成源码固定后主会话统一完整回归与构建；端口/H2/编译均不冒真实DM8初始化。

9.14原件前向执行补充：实际最终依赖V042/044/046/056/066/088/092/106等包含替换既有CHECK约束，执法/裁决原件包含回执锁分桶seed，V088包含已有来源声明复制。它们是原件内前向升级动作，不是失败逆迁移。仅在固定安装选择、原件SHA及逐语句SHA共同匹配时允许这些确切语句；DROP TABLE/SCHEMA/USER、任意DML、未固定的DROP CONSTRAINT始终拒绝。原件列出的lock seed不授予身份/用途；前置核验来源表与非空要求，失败保留现场。既有保守splitForwardSql默认行为不扩权；最终安装专用例外注册仅原件SHA匹配时消费，升级不能以缺日志或清表重播。

9.14正式启动入口细化：咨询/执法既有正式SpringBootConfiguration没有main；补标准SpringApplication.run本类入口，与裁决既有入口同式，发行PropertiesLauncher明确loader.main三独立产品类。不是新业务路径或演示启动。V070/V077既有行前向复制与V082分桶初始化同样采用原件及逐语句固定摘要许可；实际非空升级仍需明确非破坏审查与保护，不能从新空库结论推导升级通过。

9.14装配隔离补充：咨询原ComponentScan覆盖security.product同包，会发现执法SpringBootConfiguration并递归展开其ComponentScan。正式咨询只排除包扫描发现的其它SpringBootConfiguration（当前root仍显式注册），保留本包既有业务配置与条件。执法/裁决保持显式装配。该修复恢复三独立应用原架构边界，不关闭咨询业务能力、不增加万能Agent。


### 9.15 V10.41后继确定工程缺口：回归启动前检与same-network native围栏

不改变最终业务/R/D合同，不重做既有目录或平台。2266项当前全量的20错误需对照RBC24和原20修复实际XML逐项对账；新运行器只复用既有safe-full-environment-model及受管wrapper，禁止直接缺环境Maven起跑、generic SYSDBA、改变测试为skip、读取过期数据包或重用消费RBC。启动前需确认全部当前required字段在同一受管Action作用域中传给子进程，输入/源码SHA、现时许可、确切资源、RBC空目标/未消费标记及输出新目录均匹配。metadata前检不读取密码值；许可/目标不足则返回确切缺项，不执行Docker写/Java业务/Maven。旧5491/5311等仅为原对账事实，不能由历史runner引用推导本轮可启动或可用。新增FIX-SINGLE-DB-REGRESSION-LAUNCH-01从已有VER-AGENT-REGRESSION-ENV-01与原环境修复合同接续，退出是20错误对账和可重复同进程注入前检/拒绝证据，不把它当完整回归绿色。

same-network persistent native fence继承9.10/9.14：容器模式复用已批准工程的精确非root/只读rootfs/512MiB/0.25CPU工具归属与网络核验，固定原SingleDatabaseFence源码及实际执行class SHA，同一确切DM8 URL，不以本机DNS失败静默退回旧地址。维护锁、完整责任原件及实际停止所有应用/Worker/导入器仍是独立强制前置；同一连接持SHARE/反复CHECK/释放协议不变。v2的15账号/six schema必须从完整deployment派生核对，不能只改count；backup或runtime不得被当作跨六schema的维护围栏账号，不能取代停写责任。旧v1/12工程与原锁证据范围保留。无外部操作测试只证明传输/准入协议，不宣称真DM8持锁或整体恢复。

主会话负责FIX-SINGLE-DB-REGRESSION-LAUNCH-01、共享tool资源validator、15账号snapshot/restore/init兼容专项、材料集合和唯一主计划；单个执行者独占native adapter、专用container fence transport及其测试（scripts/lib/single-database-lifecycle-native.mjs/.test.mjs、single-database-native-fence-container.mjs/.test.mjs），不改Java fence/shared container validator/计划/SQL/数据库，也不运行全量。

9.15集成细化：六schema保护的维护身份与15个产品账号分开，不新增第16个产品账号或扩大应用权限。可沿原管理员维护能力使用SYSDBA，但v2必须在当前snapshot批准内显式绑定administratorFence（确切targetId、user、passwordEnv、purpose=SINGLE_DATABASE_PROTECTION_FENCE、具名owner/source）；没有此项仍拒绝generic系统身份。该身份仅执行已批准窗口的完整停写后SHARE/CHECK/RELEASE，不创建或合并产品角色，不替代停写责任。应用/backup/领域owner均不得作为跨域围栏身份。现行测试申请采用该既有管理员动作，不另造需要全库权限的新维护账号；管理员口令仍只经受管引用，初始化/原生工具与应用动作分开。

### 9.17 恢复目标文件树可实现性前检（FIX-SINGLE-DB-RESTORE-FILE-PREFLIGHT-01）

派生技术修复，沿已通过工程D及9.11的精确独立文件映射/新目录/non-overwrite合同，不增加业务或资料权利。现有validateRestoreConfiguration只以原始relativePath Set拒绝相同字符串，允许files/archive与files/archive/item.txt；restoreSnapshot执行importDatabase后才restoreOriginalFiles，前者作为文件、后者作为目录不可同时成立，错误会在已启动非原子导入之后暴露。Windows目标还存在大小写同一文件及已禁止受管Windows引用中的尾点/DOS设备名别名，原相对文件语法没有前检。

在现有validateRestoreConfiguration语法校验之后、任何围栏/包解密/库存/导入/目标文件创建之前检查文件树：所有相对路径互异且任一目标都不是另一目标的目录祖先；与输入顺序无关，按路径段而非字符串前缀（archive与archive2是不同目标）。Windows执行宿主使用大小写折叠比对并拒绝尾点及DOS设备名段（与当前受管Windows引用规则一致）；POSIX保留不同大小写合法文件名，不宣称识别其未知文件系统别名。保留已有相对路径语法/无跨域推断，不推导新增许可或改写原文件键。错误沿既有EXACT_RESTORE_FILE_MAPPING_REQUIRED/NEW_INDEPENDENT_FILE_MAPPING_REQUIRED，原输入/批准不重写。

测试先在独立当前源码副本复现原缺口，再修复并验证：祖先冲突两顺序、非祖先同字符串前缀、有效共同目录/嵌套文件；Windows大小写目标重复/祖先别名/尾点/设备名，POSIX不同大小写映射合法。配置拒绝必须fence/inventory/import/journal/fileRoot均0、调用key清零；有效自有文件解密写回及V2十五账号仍通过。只读原182指纹前后核验，写集仅scripts/lib/single-database-lifecycle-restore.mjs与其.test.mjs；无Java/SQL/活跃config/native class/共享Maven或前端输出写入，Node单进程顺序测试256MiB堆预算，自有临时目录原测试安全清理。模拟native payload明确不是DM8，不跑原生导入/不读旧包/不另起DB，不冒三域实际保护恢复或A/B退出。

### 9.18 恢复封包读取容量竞态窄修（2026-10-10）

FIX-SINGLE-DB-SNAPSHOT-CAPACITY-01继承9.8/9.9、已完成原生恢复工程及本轮明确源码修复授权。当前openSnapshot先stat检查容量，又stat取得新size分配Buffer；文件在异步边界增长时，第二个未校验大小可突破既有128MiB×2＋1MiB＋40容量上限。只修已存在有界恢复读取：同一文件句柄一次取得并严格检查安全整数大小，以该固定有界值读取，读取后再核大小变化但不以新大小分配；变化拒绝，不进入解密/恢复副作用。包格式、有效期、密钥、来源/目标/审批及attempt合同均不变。自有小文件与FileHandle大小竞态替身用于避免实际分配巨量内存，明确不是DM8或真实原件故障实证；保留原红/原源码并验证既有正常与负例。无新增工具/产品行为或运行批准。

### 9.19 生命周期JDBC子进程有界失败关闭（2026-10-10）

FIX-SINGLE-DB-JDBC-TERMINATION-01只修既有jdbcRequest执行缺陷：120秒超时/32MiB输出超限仅请求kill，没有失败记忆/有界拒绝；迟到close(0)仍可能返回超时或截断前JSON。依赖已验原生有限命令管理器，不重复实现或修改它，通过函数内部延迟import复用，避免额外模块初始化循环。仍按原固定command/无shell/env凭据/JSON stdin执行；同样120秒/32MiB，最多5秒终止宽限，超时、超限、流错误不可成为成功。保留JDBC_PROCESS_UNAVAILABLE/JDBC_REQUEST_FAILED及原结构化operation refusal映射，不披露stderr/driver credentials，解析后清零字节。未知真实服务器动作仍不推断已终止/未执行，不重试、释放未知锁或删除attempt。仅自有假子进程/假时钟测试，正常与异常JSON及原受影响库存/迁移专项；实际连接/DDL/导出恢复不由窄修放行。

### 9.20 当前最终库保护/单在线独立恢复执行准备（2026-10-10）

PREP-SINGLE-DB-FINAL-PROTECTION-01消费原resource-approval01与conditional-scope-approval02的完整停写原生保护/独立5497恢复授权，不把待产生封包当外部待供输入、不重复申请同范围。现时发现原件为mainline-protection-consultation45/current-resource-writer-discovery45、current-full-inventory45及current-protection-closure45；只读完整库存15账号/674对象授权、六schema，业务文件树当前空及相关材料/模板/Input/Source表0；客户端/维护/seed非空必须一起保护，不把最终库当空库。该审计是时点输入，不能替代停写后的再次库存和文件SHA/引用闭包。

**准入与责任**：主会话唯一操作维护锁/DM8/三应用/发布角色，不与并行页面源码任务争用。完整writer登记必须覆盖三正常应用9b/e0e5/4f、维护ff/JDBC/native与本会话其他写端口，原卷所有14使用者逐一核Running及RW、restart策略；679/1bf为透传候选，须复核无业务凭据/仅TCP后才排除。停止资源本身不是全部维护写入者已隔离证明。锁为single-database-maintenance-<实际source64>.lock，有名责任/原批准证据，未存在则wx创建、存在先对账不删。7GiB/4CPU/一次仅1在线DM8和10-10 18:00北京窗口每副作用前重读；模型/计算/矩阵0。

**恢复账号准备技术路线**：SCHEMAS/GRANTS=Y的HELP不证明15账号/系统权限都在dump中。独立新空目标先按原primary-bootstrap-config06的具名create-user/grant-system步骤准备15账号及准确系统权限/原nativeDefaultsSHA，密码只用原受管引用临时进入进程，禁止读取或复制原数据库密码hash、扩大PUBLIC/RESOURCE/ANY；绝不向源库重放此步骤。实际source库存：三business owner仅CREATE SESSION/TABLE/INDEX、三authority owner仅CREATE SESSION/TABLE，9 runtime/backup仅CREATE SESSION，均PUBLIC，674对象grant由原生GRANTS=Y恢复后逐项对账；不预放674对象授权或105迁移/维护bootstrap。新目标账号准备必须走既有可恢复bootstrap CLI及独占journal/逐步摘要，非空/未知结果保留，不借源目标ID或旧receipt。目标准备后的emptyInventorySHA由真实只读取得，空业务对象/行与账号权限完整分别核验。nativeSchemaObjects中的SCH是正常账户schema，不冒业务对象。dexp/dimp当前HELP原始输出与SHA保存于45；如果实际import不能恢复对象grant/结构/摘要，保留partial并记录确切缺项，不能吞warning/临时改GRANTS/重播来源迁移/自动重试。

**确切可逆步骤合同**（不是现已执行）：
1. 在停止服务前冻结现时发行/容器/卷/网络/所有应用文件路径与SHA、完整数据库及权利/账号/原始维护者、持久化与保留依据、wrapper/native两独立受管key引用，复核恢复5497名字/端口/新3GiB卷未被使用；当前咨询新身份/Task/Source/Input若产生，必须重新取完整闭包而非用45早期空表结论。native fence class/source及JDK网络引用精确pin，原管理员SYSDBA只供维护。
2. 登记实际停止与恢复影响/退出和完整责任后，在原窗口按三精确应用ID逐一可逆stop并确认PID0/Running false/无自动restart，关闭维护并发入口；未知stop结果先inspect不盲重试、不处理未知进程。透传只读可留运行但不得作为可写入口。对stopped writers及独占锁复核后，原SYSDBA同连接持SHARE围栏，assertHeld/现时决定逐次重检。
3. 既有snapshot CLI有界原生六schema导出，原文件清单及权利/许可/期限、导出前后完整库存一致、原生加密与AES-GCM认证封包、唯一新输出/实际SHA及receipt；包不得纳入TLS/master密钥或账号凭据，受管引用保存在包外。120s以内export/128MiB上限不放宽；实际输出超限/失权/断锁即失败关闭并留证。未成功独立可恢复不关闭A。
4. 围栏释放与源库实证保存后，按确切source ID可逆停止源DM8并证PID0，才允许独立5497空目标创建/启动（实际ID/原名/新卷/3GiB限额/镜像SHA/网络硬核验）。不同时运行2在线DM8，不改应用指向，不碰旧5237/其他环境。
5. 在独立目标以原SYSDBA和既有CLI实施上述最小账号准备、真实空库存SHA；绑定真实packageSHA/new target/source distinct volume、新文件根/currentDecisionSHA/DDL许可，永久attempt marker先写。一次native import IGNORE=N/GRANTS=Y/有限timeout，warning-free原日志SHA、全部对象/行摘要/native定义/15账号有效权限与文件引用完全匹配才退出。失败/超时/未知不重放dimp、不删marker/部分文件/目标或锁，不假称回滚。恢复仅工程验证，businessActivation=false；历史权限绝不覆盖当前权限或授权应用使用目标。
6. 成功或失败均先明确停止确切restore目标并实证PID0/保留现场；恢复目标结果未知则不启动source。确认唯一在线限制后恢复原source同ID、原三应用/原发行字节及原实际服务配置，严格TLS健康及完整库存/账号/文件与停止前一致，核原现时watermark。未知启动先对账，不能靠重包或改连接“恢复”。有新写后的失败处置必须保留原新写，不以旧包/历史权限回滚。10-10 18:00前预留退出与清理时间，截止不以长步骤/材料10-14期限延长。

**本节通过范围**：时点只读发现和上述准备技术路线已核；当前仍未完成全writer现时责任证明、全部可执行配置及真实空目标绑定，未停止任何服务/封包/import。实际操作D只有这些具体引用/责任/准入全部闭合并写入唯一主计划才通过；不据本文序列擅停库。账户/权限路线通过真实恢复验证而不是HELP或理论推断取证，当前父A/B/C继续PARTIAL。
9.20准备补证：08:11北京已在原批准CurrentUser DPAPI根生成独立wrapper/native新key，仅保存确切引用及密文字节SHA，不复制TLS/master、不向workspace保存秘密；managed-protection-key-references45.json为原件。protection-configuration-inputs45.json已实际绑定三应用完整ID、六schema/15账号、原ff/JDBC/class SHA与原native adapter/source SHA；纯组装测试实际exit0且因writerRegistryComplete=false得到COMPLETE_WRITER_AND_MAINTENANCE_EVIDENCE_REQUIRED，action保持PREPARATION_ONLY_NOT_ADMITTED，禁止拿此输入执行snapshot。初版import路径多一级造成ERR_MODULE_NOT_FOUND的脚本/log保留；02仅纠正离线路径后通过，无外部副作用/盲重试。mounted-fence45只读实际SHA与5bb9504e…相符；未取得SHARE锁/停写/导出。剩余writer责任/停止后复核、当前资料与文件闭包、实际空恢复ID/水位及currentDecision仍须具体闭合，不把已生成keys继续列为外部缺口。

9.20阶段准入澄清：不得把未来输出设置为现时循环前置。snapshot阶段需要当前source/发行/全部write端责任、必要数据文件权利、wrapper/native受管key、锁与停止/退出合同；不要求尚未创建的restore64 ID或尚未生成的package SHA/emptyInventory SHA。restore阶段依原批准确切name/5497/独立new volume创建后取实际ID，启动前必须先证source停止，实际空库存和现时restore决定/包SHA在import前闭合。仅创建而未启动的目标不计第二在线DM8；不得为提前获取空库存而同时在线两库。prep原配置仅准备记录，完整责任、现时闭包与phase绑定仍待主会话落实，但新目标ID/封包/空库存由实施产生，不再向人类索取或用其未产生阻断合法前一阶段。


### 9.21 当前source原生保护阶段操作D（2026-10-10 09:06北京）

沿9.20阶段准入澄清，仅细化既有`IMP-SINGLE-DB-NATIVE-FENCE-01`实际source snapshot，不新授恢复/业务/身份/C权限。责任Codex当前主会话，依据原resource-approval01管理员完整停写原生保护及原窗口、conditional-scope-approval02、45真实优先级原件。当前唯一source精确f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f；source-snapshot-config46.json已通过原native配置和现时准入validator，source-maintenance-responsibility46.json明确全部选定源部署/admin/文件维护的受托独占责任；source-writer-registry46.json登记三原应用及唯一确切维护锁。不以当前运行清单推断已停止，不以host连接数0推断全局围栏。

原件均在evidence/single-database-installation-20261009/approved-execution/mainline-execution46：09:02前当前资源/三文件根/maintenance进程及六schema真实库存exit0；与45库存逐字段0差异，15账号/674对象授权及三原应用+DB+两透传+工具七角色PID/StartedAt/image完全未变。两透传及ff均readonly、仅非秘密只读挂载、无DB秘密环境字段；两透传不成为直接库/文件写入者。source仅dmserver，ff仅sleep；host已建立连接只作时点旁证。完整职责由主会话主动承担并冻结本次所有其它维护/Grant/DDL/迁移/资料及并发入口，不靠旁证推断权利。当前业务Input/Source/普通身份仍空，仅安装锁种子/维护/已批准client登记数据；必要业务文件为空，无TLS/master/凭据进入封包。

实施合同：先原三ID/current labels/image/no-restart与source/窗口硬核验，逐一stop --time 30，并inspect确认Running=false/Restarting=false/PID0后才snapshot。任何未知结果先对账，不盲重发。保留逐步原始事件；原CLI取得同连接SHARE围栏、逐次assertHeld和现时期限、六schema完整前后库存相等；120秒/128MiB/单文件/native加密及AES-GCM原约束不放宽。wrapper/native密钥仅消费45原DPAPI引用，临时env使用后恢复，公开日志无值。只执行一次snapshot CLI，新唯一single-final-source-protection46.xjsnap和source-snapshot-receipt46.json不覆盖，失败保留原native/包/锁/原始结果，不自动重试或删除。

退出：source DM8本阶段始终不停止、不创建/启动restore，不会两库在线。snapshot自然终止后，确认新维护锁已不存在且ff无活动java维护进程，才依次启动本次已明确停下的原三ID，image/原运行配置不改，不重包、不bootstrap、不切连接；未知状态先inspect，不处理未知进程。严格咨询CA/SAN/server pin GET、原三应用实际Running/PID及最新完整库存与停止前对账，记录任何新审计变化，不以旧包覆盖。未能确认围栏释放/运行恢复则保留现场并报告，不假称回滚或整体通过。必须在原2026-10-10 18:00北京截止内退出。

限定D结论：本节及确切配置/职责/当前数据闭包使source snapshot阶段具备执行准入；真实停止/SHARE/native导出/封包/返回原应用仍分别待执行输出。独立restore的真实ID/empty库存/currentDecision/包SHA在其阶段闭合；raw LAST_CHANGE敏感性7项离线反例只证明比较器边界，不证明实际恢复等价或失败。已安装达梦手册原字节与页码/SHA留存；尚未获得LAST_CHANGE及SREF目录字段可忽略的权威语义，现比较器完全不改、未知差异严格拒绝。source snapshot不是独立可恢复、A/B/C或业务验收退出。


### 9.22 原维护暂停后精确网络及透传返回窄修D（2026-10-10）

`FIX-SINGLE-DB-MAINTENANCE-RETURN-01`依赖既有单库工程R/D，仍在原受托source保护操作中恢复原资源；不新增容器/网络/权限或改发行/透传配置。46真实第一次source snapshot在prepareContainerFence的网络名守卫拒绝(EXACT_CONTAINER_FENCE_ALIAS_REQUIRED)，发生在class读取/持SHARE/native导出前；源码边界与原safe CLI日志可定位，未产生package/receipt。维护锁已消失、ff无java，三应用原ID/image已返回，但Docker动态地址变为enforcement .4/adjudication .5/consultation .6，而原地址是consultation .4/透传679 .5/adjudication .6/透传1bf .7/enforcement .8。两原透传在原应用停止期间因UnknownHostException失败关闭(PID0/exit1)，不是运行成功；第一次正常TLS返回GET因透传已停拒绝连接，保留原始失败，不冒整体运行返回。证据source-protection-execution46-result/events、consultation-return-state46、application-return-network46及source原始资源发现。

同Task详细合同：主会话暂停其它业务/维护写入，核当前source/ff不变、原三应用确切ID/image/原网络、两透传确切exit1/PID0和原readonly两挂载SHA不变，核原窗口/预算。只按确切原ID可逆停止三当前应用并inspect PID0，无删除/重包/改网络/改配置。依据原网络布局依序启动原consultation9b→原consultation透传679→原adjudication4f→原adjudication透传1bf→原enforcemente0；每次立即对真实唯一Internal网络的原IP和原image核验，应用保持原no-restart/原存储及env，透传消费原目标ID/DNS/IP及原两SHA不变。IP分配是待验证输出，不推定顺序必然成功；任何未知或IP/字节/预算漂移即停止本分支、留现场，不盲重发或改targetIp绕守卫。应用需在本次StartedAt之后真实Started日志及当前Running证明确认后才启动其透传；有界只读就绪检查，不签业务请求或新身份。

退出：七原角色同64ID/同image/同文件及原IP、≤7GiB/4CPU、source未停且仅1在线DM8；两透传实际Running/PID和严格原咨询TLS/Issuer/login200及最新完整库存回读；任何未知/new-write不能回灌旧包。该窄修只恢复原允许运行角色，不修publisher产品守卫、扩大egress或改证书。全部前后失败保留；此后再单独修DNSNames守卫并重新核实际source保护，不能用本次返回当原生保护/独立恢复/A/B/C或业务验收通过。


### 9.23 Docker实际DNSNames精确围栏名守卫兼容窄修D（2026-10-10）

`FIX-SINGLE-DB-FENCE-DNS-NAMES-01`依赖既有单库工程R/D；技术责任Codex。46真实目标在确切Internal networkId 047a31…ad65下Aliases=null、DNSNames=[原source完整name,原短ID]；原JDBC库存通过其既有Name合同，但native围栏只读Aliases而在执行class/S HARE/native前拒绝。当前Docker原始网络结果及失败日志为具体输入，不把运行失败转成业务规则。本轮外部官方字段文档检索未返回可用内容；本D仅以当前实际Docker inspect字段与确切资源/源代码守卫为事实，不声称已验证通用daemon/API版本协议。

同Task详细实现：仅single-database-native-fence-container.mjs中的确切networkId下DNS名匹配与其专测。若该网络存在DNSNames字段，只接受非空纯字符串array中精确同jdbc alias；空/null/非法类型/错误名必须拒绝，不允许由Aliases兜底遮盖。只有DNSNames字段完全缺失的旧检查结果才沿既有Aliases精确数组合同。禁止接受db.Name全局匹配、跨networkId、子串/IP/模糊名。其余actual64ID/Running/owner/readonly/nonroot/CPU/memory/类与Java源SHA/同连接SHARE、现时批准/完整writer/维护锁保持完全不变；不改JDBC、部署、Docker网络、DB或恢复比较器。

退出：真实DNSNames/Aliases=null正例及DNSNames缺失旧Aliases正例；空/null/非法值/mixed/wrong name/其它network命中/当前DNS与旧Aliases冲突反例全拒；原专测回归。随后只读prepareContainerFence对原source/ff/network/class真实检查（不执行返回invocation，不取JDBC锁或native导出），必须通过才可在新明确操作回执下再次source snapshot。第一次明确在原生前失败不当未知dexp重放，原日志/锁/所有失败不覆盖。当前父A/B/C及独立restore仍不关闭；操作返回必须先完成9.22原精确IP/两透传原字节与严格TLS恢复，不因新修源改变既有咨询25/裁决39/执法44发行。


9.22/9.23限定退出补证：09:15已恢复七原角色同ID/image/原Internal IP和原两透传JAR/config SHA，预算6.75GiB/3.85CPU；严格咨询三GET200与完整六schema/15账号/674权限库存回读exit0、摘要43763a66…与暂停前完全相同。DNS守卫窄修136/136工程专项及原source/ff/class两条真实只读prepare通过，未执行返回invocation。只完成这两技术窄项，第一次原生前失败/TLS拒绝/IP轮换原件完整留证，真实source原生保护及独立恢复仍待分别实施。

### 9.24 明确前native失败后的新source保护安排（2026-10-10）

既有IMP-SINGLE-DB-NATIVE-FENCE-01接续9.21，原批准/window/目标/六schema/15账号/受管引用不变。第一次46已明确在native之前守卫拒绝，Java/JDBC围栏/native均未开始，package/receipt均不存在，锁释放与全原运行已按9.22实际核验，不能冒作未知native重放。9.23实际只读准备通过后，允许新确切一次CLI尝试，独立新config/output/receipt/events(46-02)，保持原失败原件不覆盖、无自动重试。

本次暂停先按确切ID停止原两透传并inspect PID0，再停止三原应用并inspect PID0，避免其在已知DNS临时缺失期继续解析；这只是既有原角色可逆运维不新设资源。source保持在线，ff只消费原native/同连接SHARE适配。snapshot自然结束后锁消失/ff无java才返回：按原consultation→consultation透传→adjudication→adjudication透传→enforcement顺序，逐项原IP/image/两非秘密SHA、实际当前Started及预算严格核验；未知或IP差异只留现场，不改targetIp或重包。完整最新库存与暂停前摘要、严格TLS与原账号/权限核验再退出。独立restore仍0，原dump/key期限10-10 18:00北京不延长；保护失败/未知只保留原始现场，实际native已开始时不再发dexp。新实际source成功与独立恢复通过/父A/B/C退出仍分别记录。


9.24实际执行补证（09:21北京）：两原透传及三原应用按精确ID停止并inspect PID0；新一次snapshot CLI退出1，原始safe code=NATIVE_PROCESS_REFUSED_OR_TIMED_OUT，未生成有效package/receipt。通用代码没有绑定失败命令阶段，不能从时间或/tmp无single-native文件推断唯一根因或保证native未开始。当前不再发dexp；完整事件及源现场保留，未启动restore/停source/触及旧环境。原维护锁已释放、ff无java，source仅原dmserver；七原角色已按原IP/image/两文件SHA顺序返回，预算7247757312bytes/3850000000NanoCPU。只读native前提实际证实timeout/dexp存在、/tmp属1000可写，不证明native完成；原阶段仍失败未退出。

当前下一安全工作为`ARC-SINGLE-DB-NATIVE-FAILURE-DIAGNOSTIC-01`：主会话先冻结精确source/ff/阶段及原生子进程证据D，分别标识target/resource/writer检查、JDBC围栏获取、围栏前后库存、native exec/read、释放；明确成功/非零/超时/未知的类型、不可重复副作用边界及有限退出。只允许稳定阶段enum、实际时间/exit或unknown、字节计数/安全SHA/确切非秘密目标引用，不记录argv/password/native key/环境值、native/JDBC原始正文或未经筛选exception。未知不能升级为成功或无副作用；原完整writer/资料许可/锁/现时决定/128MiB/120秒及恢复比较器不删不放宽。D退出须专项秘密反例、首次确切子进程自然终止/有限处置、不杀未知进程、防重复及原始失败保存的证据方案；未过此同Task详细D不改共享runtime/native/CLI。未来实现task由唯一主计划控制，D未齐不得写或执行新保护命令。

9.24末次退出实证：09:24完整六schema真实库存exit0，15账号/674对象权限、完整摘要43763a66…与两次暂停前完全一致；严格咨询原CA/name/server pin三GET200。未回灌历史包/权限、未重放迁移、未创建身份/Task/Grant/资料，模型/计算/矩阵0。仅证明当前原运行及数据返回一致，不改变failed source snapshot/native restore或专业/验收状态。原件链接见主计划46末次TLS/库存。


### 9.25 原生保护失败安全阶段诊断D（2026-10-10，ARC-SINGLE-DB-NATIVE-FAILURE-DIAGNOSTIC-01）

这是Codex在既有维护/技术修复委托内作出的派生技术决定，不是原需求或新增运行批准。依据9.24及46-02原始失败、47只读接管和前置verify-all：47文件/41契约一致；整体历史TESTS_FAILING/TEST_EVIDENCE_STALE仍红，不以结构检查代业务正确性。证据目录为`docs/evidence/single-database-installation-20261009/approved-execution/native-diagnostic47/`，takeover47保留原件SHA；47未执行导出或改运行角色。原锁不存在，ff仅sleep、source仅dmserver；旧WindowsTerminal子PowerShell命令不可读，保持不动，不将其认定维护worker或终止它。

**范围与决定**：仅修改受管原生命令适配器、容器围栏只读命令阶段、snapshot CLI诊断包装及既有专项；新纯诊断模块不持久化业务状态、不访问环境/凭据。主计划是唯一状态控制。既有错误码保持，追加机器可读的安全diagnostic；不采集argv、stdin、环境、SQL、JDBC/native正文、路径、未筛选异常或秘密摘要，不创建新业务页面/字段。阶段枚举固定为TARGET_INSPECT、WRITER_INSPECT、FENCE_METADATA_INSPECT、FENCE_CLASS_DIGEST、FENCE_OPEN、FENCE_ACQUIRE、FENCE_ASSERT、FENCE_RELEASE、INVENTORY_BEFORE、INVENTORY_AFTER、NATIVE_EXPORT、NATIVE_READ、SNAPSHOT_SEAL、UNCLASSIFIED。嵌套阶段保留最具体失败；异步上下文隔离，不使用可串台的全局currentStage。CLI的前后inventory按实际调用序号区分；同一次失败不得解释为已产生保护包。

**数据与出口合同**：失败仅可含受信模块生成的阶段、开始/结束ISO时刻、有界非负耗时、stdout/stderr字节计数、固定reason、整数exitCode或null、白名单signal或UNKNOWN、受管宿主子进程PID或null、closeObserved和termination（NOT_STARTED/CLOSE_OBSERVED/UNCONFIRMED）。固定reason区分SPAWN_ERROR、TIMEOUT、OUTPUT_LIMIT、STREAM_ERROR、NONZERO_EXIT、SIGNAL_EXIT、UNKNOWN_EXIT及STAGE_REFUSED；不从错误正文抽取诊断。只有真实close事件记录closeObserved=true；同步spawn抛错才可NOT_STARTED，异步error不能假定未启动。Docker客户进程close不是容器native终止证据，remoteTermination恒为NOT_PROVEN。超时或未知沿用原TERM/最多5秒KILL的本次子进程有限处置；不新增kill他进程、不延时放宽、不重试。计数不持久化正文，缓冲按既有合同清零。外部Error自带diagnostic/cause不可信，不序列化；通过模块私有WeakMap生成安全投影。

**资源/副作用/失败恢复**：不改120秒原生、128MiB、完整writer、锁、资料使用期限、权限、固定发行或恢复比较规则。诊断失败保持失败，现有先保护后恢复门禁不变。源端未知结果先只读观察精确容器进程和新文件元数据；无自动重复导出。正式新保护仍须新配置/adapter SHA及原完整停止、窗口/资源/数据闭包、返回流程；本D不批准新副作用、不把无/tmp文件等同导出从未启动。第一次46-02原失败无法事后补出精确阶段，永久记UNKNOWN；真实47只读命令可验证新诊断路径但不冒保护或恢复完成。释放错误按FENCE_RELEASE报告，不能据此推出此前保护成功；原始执行日志仍保留。

**验证和D退出**：已确定技术责任Codex、源版本9.24/46-02及原批准引用；没有待人类代定的身份/资料/专业事实。实现前本节详细D通过，仅准FIX-SINGLE-DB-NATIVE-FAILED-COMMAND-DIAGNOSTIC-01。专项须覆盖零退出、非零/信号/未知状态、spawn同步/异步失败、超时close/未close、缓冲超限/流错误、非法阶段拒绝、并发阶段隔离、外部伪造诊断/秘密正文不输出、真实受管Node自然非零退出；既有有限终止/围栏/恢复专项不得回退。真实容器只读命令诊断独立取证并标明非native导出；全部工程验证完成才记实现，真实保护阶段证据与独立恢复仍由后继实际执行取得。


### 9.26 当前原生工具参数兼容性核验（2026-10-10）

47真实调用已将失败限定为NATIVE_EXPORT、NONZERO_EXIT/exit1、3553ms、stdout112字节/无stderr、宿主close实际观察；这不是超时。内容仍不保存，不能从长度猜唯一根因。源真实进程/锁及原七角色已返回，完整六schema/15账号/674权限摘要与46不变，严格TLS47b通过。原失败、初TLS检查误端口5447及修正原5446的原件全保留；它是证据脚本错误而非服务/证书问题。此结论完成诊断工程子项，不完成保护或恢复。

当前工具自身45 HELP原件版本03134284458-20251113-301923-20178，列明ENCRYPT_PASSWORD、FILESIZE、FILENUM等但未穷尽值约束；原key45生成器把32随机字节转64字符hex，未校验工具的实际长度/字符约束。只把该差异列为待证候选，不引用无法取回的网上搜索作通过证据。

ARC-SINGLE-DB-NATIVE-EXPORT-PARAMETERS-01当前安全边界：主会话在精确原source中仅执行有界HELP/参数解析工程探针，全部输入为明确非业务测试常量，绝不注入实际密码/native key/受管引用；没有产品用户或业务替身。若需要USERID语法占位，仅使用不存在的工程探针名及127.0.0.1:1，且每条都有HELP、NOLOGFILE=Y、DROP=N、唯一探针输出名，不使用真实5236、SYSDBA或真实schema。无真实凭据加HELP是双重不导出边界，不启动/停止资源、不读DB、不生成业务保护包。每次timeout与输出上限固定，原输出只含公开帮助/测试常量可保存；如结果不是帮助或预期参数拒绝立即停，不能把探针成功当真实导出。核验前后精确进程对账、实际文件副作用若出现保留，不删现场。

退出前必须依据当前工具实际正反例冻结：native密钥允许格式/长度、单文件参数与实际输出命名、旧key与新key的独立保留及受管引用生成方式、强度不降于现有保护合同、在任何源调用前拒绝不合规输入的本地负例。不得先截断旧秘密、去掉加密/128MiB/超时/原grant/writer围栏、改成全库导出或吞警告；不凭类似工具推断%U等规则。真实保护的新执行沿9.21/9.25全准入，只允许单次新命令，未知不重发。当前本节只准只读参数核验，FIX仍未准入，现行旧环境/C和模型均不动。


9.26当前安装工具首个公开参数探针：`HELP`与额外参数组合并非单参数HELP的可靠短路；真实输出出现密码提示及“数据加密密钥长度和复杂度不符合要求/导出失败”，exit1，未提供任何真实凭据。该反例保留在native-parameters48，不把它称帮助成功或实际源调用根因已全部证明。后续探针必须显式首参为非业务占位USERID加127.0.0.1:1，禁止依赖HELP短路；单独HELP才作为帮助。固定不存在的工程schema、NOLOGFILE=Y、DROP=N、唯一/tmp探针名及两层10秒/15秒上限不变。任何进入连接阶段只能到明确非DM端口1；真实源5236及环境秘密不注入。若先通过参数后被连接拒绝，仅证明参数门禁未拒绝，不冒登录/导出或输出命名验证。


9.26第二组当前工具反例：HELP作为额外参数统一被“无法识别参数HELP”拒绝，7个长度探针均未验证到密钥门禁；原始stdout bytes已保留；初版终端显示乱码不能作语义结论。48b的raw hex和严格UTF-8解码证实工具实际输出UTF-8，乱码来自Python默认GBK输出再由PowerShell解码；此前GB18030推断撤回，错误原件保留。下一组只移除该额外HELP，仍强制非业务USERID/假密码及127.0.0.1:1、不存在的schema、无日志/DROP=N/唯一探针文件/10秒timeout；不读取任何真实凭据，不接真实5236。出口只允许明确参数拒绝或该非DM端点连接拒绝；成功、超时、未知输出即停止并对账，不声称帮助或导出成功。该对安装工具公开参数的只读工程核验不改变产品流程或数据/权限。


9.26当前原始字节已确认的首个硬约束：48b无额外HELP、无真实凭据且非DM端点时，工具在连接前直接输出“指定数据文件大小，必须使用%U对名称进行自动扩展”，exit1、stdout112字节；原命令FILE是普通.dmp而FILESIZE=128M，确有不兼容组合。它与47同阶段/同退出和字节数相符，但47正文未保存，不把长度相同当唯一归因。后续参数探针使用含%U的唯一工程模板，仅验证密钥门禁是否进一步通过，仍不连真实数据库、不生成实际包；不能因原文件名不兼容而删FILESIZE或放宽容量。


**9.26详细D退出合同（当前安装工具实证后）**：native-parameters48/installed-native-contract48.json及原始stdout证明无%U的FILESIZE组合在连接前拒绝；含%U的混合字母数字8/9/32/44/48字符参数通过后仅在明确非DM端点被-70028拒绝，49/64字符在参数层拒绝；44字符纯小写/大写/数字/小写hex均在参数层拒绝。这不是生产登录或导出证据；无探针dump文件，source仍只有原dmserver。采用本项目更严格的受管44～48位ASCII字母数字且三类别齐全输入合同，新密钥固定44位，每字符由密码学均匀62选1生成，类别不齐则整体重新生成；约262位随机性（类别条件后仍超过256位），不截短旧64位key、不复用wrapper/TLS/master key。仅在既有获准current-user DPAPI根下CreateNew新环境引用SINGLE_DB_FINAL_NATIVE48_KEY，旧45两文件SHA保持；受控读回验证只报告布尔，不打印/保存明文、密钥摘要或派生秘密。

源码修复限native adapter及专项：在acquireFence任何Docker/目标访问前（原配置/现时准入检查后）验证受管native key；export前再验证，错误只给固定码。文件入口仍是受控UUID基础名，native参数内部把FILE变成该唯一前缀加-%U.dmp，保留FILESIZE=128M/FILENUM=1/ENCRYPT=Y/DROP=N/GRANTS=Y/WITH_UR=N及120秒、原writer围栏。输出不猜6位/起始编号；以该精确UUID前缀只读发现一次，必须恰有一个普通非符号链接的数字后缀.dmp文件、正大小且≤128MiB，其他类型/多文件/路径/未知名/空/超限拒绝。目录发现输出有4096字节/10秒上限；实际cat仍128MiB/30秒，上下文为NATIVE_READ，读后长度须等于发现值，再原围栏/库存验证。未知结果留原文件，不删、不合并、不循环重发导出；成功导出仍需原warning-free摘要、认证封包、独立恢复才分别退出。后继import不改账号/权限/DDL/恢复比较器或历史密钥合同。

专项出口：有效44/48，过短/过长/单类别/Unicode/换行/空密钥前置拒绝；唯一受控数字后缀正例及不同宽度、错UUID/路径/软链/目录/多文件/空/超限/无效UTF-8负例；实接FILE模板/原限制不删/argv无秘密；旧native/fence/restore/termination回归。当前无业务/法律未决项阻塞这项派生技术修复；ARC参数D完成，只解除同Task FIX源码及受管新引用准备，不把HELP/socket负例当保护已验证。真实新尝试仍需重读全部准入、当前配置/SHA/writer/库存/返回边界并仅一次，实际失败保持独立证据。


### 9.27 源保护出口与恢复目录等价详细D接续（2026-10-10，尚未通过）

48已实际通过原完整writer暂停/现时准入/同连接围栏、受管新native key与%U单文件导出、加密认证封包及源前后完整库存一致；native244729字节，真实生成后缀00001（未猜6位），封包6253965字节/SHA9480f1d388930195d32a9157690be441c5e1c1401fe6834345af007f42254c18。原七角色/IP/image及预算返回，严格TLS三GET200，库存43763a66…不变。该子范围工程/实际保护通过；未启动独立目标、import、旧环境C或模型，父A/B/C未完成，包及操作截止仍2026-10-10 18:00北京。旧失败、旧key及native加密文件保留。48原件见主计划对应回执，不把这些说明当另一状态账本。

接续ARC-SINGLE-DB-RESTORE-CATALOG-EQUIVALENCE-01，仅技术恢复验证D。49字段事实来自48实际完整raw，原字节/SHA保留：1006条constraints均有LAST_CHANGE；986条indexes中954 GENERATED=Y、32=N；constraints.GENERATED均为空，不能靠该标记或名字前缀推定匿名约束；列2275、索引986条的LAST_ANALYZED目前均null，不预言恢复必变。当前indexColumns1173、constraintColumns1619、checkConstraints330已采集，FK已有有序两端列与规则；不是缺这三类数据。assertInventory未强制这三类，且无独立索引表达式类别，完整性/适用判定要先明确。当前索引类型CLUSTER/NORMAL/VIRTUAL，不能仅据类型认定表达式不适用。

当前verifyRestoredInventory只改targetId后调用共享inventoryDigest，后者仍必须用于源时点漂移/封包绑定，不能为了恢复去全局删字段。下一详细D须给每个待处理字段当前版本的可靠意义依据，划为严格比较、经证实的恢复变换或未知即拒绝；原raw、转换说明及全部差异保留。若准许生成名等价，必须以表/约束/有序列及方向、唯一性/表达式/条件、FK两端与更新删除规则、约束有效状态建立一对一映射，同步验证所有引用及对象数量。手工名字、未知生成来源、重复对象、关联缺失/歧义不自动规约。时间/存储/统计字段不可整体扔掉，尚未批准LAST_CHANGE或SREF目录例外，也没有真实恢复差异可用来宣称误拒已证。

必需反例包括复合列交换、方向/唯一性/表达式/CHECK/FK规则变化、仅改一处生成名引用、少一个重复对象、手工名变更、同行数但内容SHA变化、授权增减/ADMIN_OPTION/PUBLIC及继承角色/账号状态变化。正例只能对应证据支持的单项转换，不能把源模拟副本当实际DM8恢复。详细D未退出之前FIX保持未开始，原比较器仍严格拒绝；只继续元数据依据/关联和最小采集范围的核验，不请求人类代做已委托的技术设计，不擅启独立恢复或新业务分支。


#### 9.27.1 当前安装目录语义与完整性收口依据（2026-10-10，D仍未退出）

来源、版本、责任与范围：Codex受托技术核查；当前主库只读JDBC返回DM DBMS **8.1.4.170**，2026-10-10 11:11北京附近采集。固定SELECT、15秒单查询/120秒进程/8MiB输出/10000行查询上限，七个指定SYS兼容视图定义、六schema原生目录及表达式目录；不接收调用方SQL，不查询业务记录，不执行DDL/DML/DCL。使用原受管admin引用仅环境内传递，原只读非root工具/目标/网络/类SHA经核验。依据为[49实际目录原输出](../evidence/single-database-installation-20261009/approved-execution/restore-equivalence49/installed-catalog-probe49.raw.json)及[逐schema关联与来源SHA](../evidence/single-database-installation-20261009/approved-execution/restore-equivalence49/installed-catalog-semantics49.json)。当前安装自带《DM8系统管理员手册》PDF元数据日期2025-11-14，物理597/603/604/606页（印刷581/587/588/590）用于交叉解释CRTDATE、SYSINDEXES和SYSCONS；603/604已渲染目视核对。手册SHA及安装内路径已记，不交付整份手册，不把网页空检索当依据。探针最初模块相对路径错误、随后Buffer返回类型误用均为本地工具失败，日志和源码原件保留；后者只读查询完成但输出未保存，重读后才取得完整原件，不谎称首轮成功。

**已证事实，不等于批准恢复变换：**

1. 当前DBA_CONSTRAINTS的LAST_CHANGE直接取O_CONS.CRTDATE；1006条与原生对象创建时间逐项一致。约束GENERATED是SQL字面量NULL；DBA_INDEXES的FUNCIDX_STATUS同样是字面量NULL。当前实际DBA_IND_EXPRESSIONS查询成功且0行，986个原生索引的XTYPE函数位也全0，二者共同支持本次时点无函数索引；不能以空字段或列名巧合代替这项证明，也不能推及未来目标。
2. GENERATED=Y实际由SYSINDEXES.FLAG最低位推出；986个索引逐个对应，954个Y全部可分解为676个不同约束各自INDEXID所指索引（没有共享/歧义）及278个无约束所属的零键聚集索引。原先仅按兼容constraints.INDEX_NAME关联得到395/559不是缺失归属证明：外键该字段引用父端索引，不能用来寻找外键自己的索引。原生INDEXID/FINDEXID必须区分。
3. 原生KEYNUM与现有indexColumns逐索引全一致；278个零列索引KEYNUM实际为0，分别266个普通表聚集索引和12个内部临时表聚集索引。其余索引列位置连续且都能关联现有表列。不能用“每索引至少一列”作为通用完整性门禁；零键例外须由当前版本原生类型/FLAG/KEYNUM与表归属共同证实，不凭空列表放行。
4. 原生对象2282条，原兼容对象清单2258条，额外12个内部临时表和12个系统触发器。当前DBA_OBJECTS定义明确过滤特定位表/系统触发器，因此不是采集SQL报错；现有raw保留其索引但未含这24个对象本体的完整定义。它们与4个确切自引用外键在ID/命名/表归属上对应，每组3表3触发器，但**对应关系观察不是删除对象或完整语义等价的充分依据**。禁止按SREF前缀删除、只比数量或用未采集触发器正文冒完整恢复验证。
5. 292个约束名字恰好等于CONS加本库ID，714个不符合此模式；这只是名字统计，不能证明前292条原DDL未显式命名。手工名、原DDL未命名声明与生成名必须分开取证，不以“看起来生成”批准改名。

**比较合同的已冻结安全边界（尚未批准生产实现）：**

| 类别 | 本次选择 | 解除条件/必拒反例 |
|---|---|---|
| 源时点漂移/封包绑定 | 共享inventoryDigest及全部raw保持原样 | 不全局删字段、重排数组或回写48包；不把49旁证追认为48内容 |
| LAST_CHANGE | 已知为创建时间，生产仍严格；候选仅允许在完整对象一对一映射后另记时点差异 | 缺值/非法时间/对象少一项/约束定义变化不能因时间例外通过；真实源目标差异尚未观察 |
| GENERATED索引名 | 仅认FLAG证明，不能仅认字符串；未来映射需676约束所属或278零键聚集的完整锚点 | 列次序、DESCEND、唯一性、FLAG、类型、表达式、存储配置、引用或数量不同均拒；无锚点/重复映射拒 |
| 约束名/手工名 | 严格；未知是否原DDL显式命名时拒绝变换 | 手工改名、CONS前缀伪装、一个引用没改、跨schema错指、两对象合一均拒 |
| 内部表/触发器 | 保留，不删除；最小原生补充采集合同待定 | 24对象正文/列/父外键/引用闭合未证明前不称完整等价；名字或数量相同不够 |
| 列与约束 | 类型/空值/default、索引/约束有序列、CHECK原文、FK两端及更新/删除规则、有效状态全部严格 | 交换复合列、ASC/DESC、CHECK表达式、FK规则及仅改一处引用必须拒绝 |
| 统计/存储/其余未知字段 | 全部严格，包括LAST_ANALYZED与物理/统计配置；暂无整体豁免 | 新字段、版本变化、不支持类型/表达式、缺类别/关联歧义不能当空集合通过 |
| 行与权限 | 原全表count、typed-content SHA及直接/有效权限、PUBLIC/继承role、ADMIN_OPTION/账号状态严格 | 同行数换内容、少表、多/少授权、继承/Public边变化必须拒绝；不把历史包权限当当前授权 |

正例合同仅限：完整原件自比；经证原生KEYNUM=0且关联完整的零键索引；表达式目录成功为空且原生函数位全0。时间/生成名正例须在下一D冻结后另立，不在此声称已通过测试。负例沿9.27完整清单，并增加缺native/表达式类别、FUNCIDX_STATUS空但XTYPE函数位非0、KEYNUM不等索引列数、少一个隐藏对象、只保留SREF名字/数量而改正文、版本/view-definition不匹配及用49旁证替换48认证内容。

**未退出原因及安全接续：**本次解决字段语义/完整关联的关键证据，不再把LAST_CHANGE意义或索引列存在性当未核事实。剩余由Codex技术责任继续完成：最小原生/表达式/隐藏对象采集与认证封包绑定（必要时新源保护，不篡改旧包）；292个候选约束对原批准105迁移声明的来源核对；4组自引用内部对象完整图及正文比较；限定转换和可执行反例矩阵。上述未齐前ARC保持进行中、FIX未开始。源库不停、独立目标不启动、import/C/模型均0，不要求人类代做元数据技术判断；原18:00窗口不延长。


#### 9.27.2 原批准DDL的未命名声明定位（2026-10-10，离线证据，不放行改名）

继续安全工作已逐项核对105个原批准migration的文件字节SHA，全部与primary-migration-config07中原manifest一致，未重放SQL。对292个CONS+ID候选：283个P/U约束在同schema/table/类型/有序列下，各自仅匹配一处原CREATE TABLE的未命名声明；另9个在三域原V096中逐条定位到6处CHECK同文和3处FK两端/有序列同文。来源路径/原SHA/行/原声明及实际名字均保留：[283个原声明定位](../evidence/single-database-installation-20261009/approved-execution/restore-equivalence49/approved-original-unnamed-keys49.json)、[9个CHECK/FK定位](../evidence/single-database-installation-20261009/approved-execution/restore-equivalence49/approved-original-unnamed-nonkeys49.json)。前者为只读词法提取脚本，不是通用SQL语义解析器；后者未以原DDL缺省语法自行推导FK动作规则。

因此“是否能找到未命名原声明”已有292/292限定源证，不再说仅名字猜测或完全无来源；但完整迁移drop/alter/recreate链与现存对象同一性尚未重建，不能单凭词法匹配准许重命名。9.27.1所列最终变换/原生补采认证绑定/4组内部对象正文与引用闭合仍待D；本次没有修改生产比较器或补写旧包。49探针后重采全部raw库存与48源返回库存逐schema/账号一致，SHA仍43763a66bdc2bb75fef435a52250f6f125b3985dd7185f89b501dfd1872f2f5e，见[完整源未漂移对账](../evidence/single-database-installation-20261009/approved-execution/restore-equivalence49/post-probe-inventory-equality49.json)。


#### 9.27.3 原生完整图、迁移同一性及JDBC外键覆盖缺口（2026-10-10，D仍未退出）

本轮先核对49最终verify-all回执，47/41一致、工具1620/0失败/2既有skip、质量179通过；整体仍仅历史TESTS_FAILING/TEST_EVIDENCE_STALE红，不把它说成项目全绿。50继续使用原只读非root JDBC工具、现时窗口和六schema；固定SELECT、15秒查询/120秒进程/8MiB输出；10001行探测以拒绝超过10000，不截断后冒完整，CLOB/二进制各2MiB上限。元数据原件：[50当前完整原生目录](../evidence/single-database-installation-20261009/approved-execution/restore-catalog-completeness50/installed-catalog-probe50.raw.json)，实际DM版本8.1.4.170/版本行保留，18内部表列、12系统触发器完整SYSTEXTS正文（每项SEQNO=0）、hidden dependencies与object infos查询成功为空。查询没有读取业务记录或执行DDL/DML/DCL。手册与当前视图定义共同支撑字段意义，官方FAQ也说明自引用约束会生成临时表和系统触发器；统计和完整性结论以本机实际原件而非FAQ概述为准。

**原批准迁移的完整受控来源核验。** [105原件和真实有序执行链](../evidence/single-database-installation-20261009/approved-execution/restore-catalog-completeness50/approved-migration-history50.json)逐条调用既有original()，核对原SQL字节SHA及受控statement pins；实际journal为每migration恰好START/SUCCESS且顺序与manifest完全一致，210事件链逐哈希有效。离线重建266个CREATE TABLE、110条已控制ALTER的声明生存链；全部现存1006约束可分为714最终手工名、292未命名生成候选，32非系统索引全部对应原CREATE INDEX。292个候选的当前原生ID/NAME=CONS+ID、当前CRTDATE/LAST_CHANGE、原未命名声明及相应真实START/SUCCESS时间区间逐项一致；受控ALTER没有对这292名字改名/删除，也没有修改其使用的列，源证从词法定位提升为受控执行链内的同一性。当前源只读SYSDATE/CURRENT_TIMESTAMP实际+00:00与工具UTC首尾对应，使用明确时区而非将DM裸时间误作北京时间。见[源同一性证明](../evidence/single-database-installation-20261009/approved-execution/restore-catalog-completeness50/controlled-origin-proof50.json)及独立clock原件。这不声称能证明所有未登记外部管理员历史不存在，未来源变化/原件变化/身份或关联歧义仍拒绝；也没有执行SQL重放。

**真实采集缺口，不能由complete=true吞掉。** 首版内部图核查用现有JDBC foreignKeys查自引用外键时失败，失败脚本和机器日志已保留，未修改原库存。扩大逐项核对后证实：当前原生SYSCONS有281个F外键，共447对有序两端列；现有JDBC清单仅219个不同外键，缺62个，其中咨询49、执法13、其余0。62个缺项全部引用U约束，219个已返回项全部引用P约束；现有4个自引用外键也仅返回2个。这是当前安装driver/catalog的实测覆盖事实，不称所有DM版本通用bug。原raw仍有constraints、indexColumns、constraintColumns和父端引用，不能误说它们不存在；但缺失外键的完整有序两端与update rule不能仅依靠当前JDBC数组或把未知当空。原生INDEXID是自己的索引、FINDEXID是父端索引，借二者和完整indexColumns得到281/447全集，与compat R_OWNER/R_CONSTRAINT_NAME/父INDEX_NAME/DELETE_RULE逐项一致，已返回JDBC记录逐项与原生全集一致。全部原生FACTION实际是两个空格，compat DELETE_RULE为NO ACTION、已返回JDBC更新/删除均3；本轮仅冻结当前已证无动作范围，未来非空编码不得猜。见[完整外键覆盖与62缺项](../evidence/single-database-installation-20261009/approved-execution/restore-catalog-completeness50/native-foreign-key-coverage50.json)。

**自引用内部图已完整取得并交叉验证。** 在原生外键全集而非不完整JDBC数组上，4个确切自引用外键各对应3个内部临时表、3个系统触发器；24个隐藏对象、18列、12完整正文恰好各覆盖一次，不以数量替代逐对象关系。每个表的原生类型、内部/临时/事务级标志、状态及一个FLAG=9/KEYNUM=0聚集索引均核对；REFING/REFED列按FK/父有序列核对类型、长度与刻度；触发器原生系统标志/有效状态、ON父表、完整正文与组内实际引用均核对。词法核验只对已证明的组内生成标识建立角色映射，字符串常量保持原字节；不做全局正则替换，不丢任何正文。源码body SHA和限定标识映射后的token SHA分别保留；dependencies成功0行不被冒作完整引用证明，实际引用来自完整正文。见[4组完整图](../evidence/single-database-installation-20261009/approved-execution/restore-catalog-completeness50/self-reference-graph50.json)。没有恢复目标，以上不是源目标等价验证。

**最小补采范围及认证边界选择（派生技术设计，尚待最终字段/反例合同收口）：**

- 同一次真实JDBC库存必须直接采集DBA_TABLES（本机266条及当前view定义已只读取得）、DBA_IND_EXPRESSIONS，以及最小原生schema/table/constraint/index身份、类型、状态、KEYNUM/XTYPE/FLAG、INDEXID/FINDEXID/FACTION和隐藏对象/列/完整正文。不能只增加字段名或给缺查询默认空数组。原有所有raw类别仍保留；失败、超限、未知版本、断关联、数量或有序列不完整整体拒绝。
- 完整外键由原生及兼容目录交叉构成；JDBC记录保留并逐项核对，明确其覆盖范围，不再充当全集唯一来源。源/目标都必须281同范围语义地比（实际数量从完整目录推导，不能把本机281/447硬编码为未来库的放行条件）；复合顺序、两端列、动作、状态、父约束都不得吞掉。
- 纯恢复比较器独立于inventoryDigest。原raw及source漂移/封包绑定不规约；候选只在同版本、完整一对一对象映射后处理已证创建时刻/allocated IDs/生成名。所有其他字段默认严格，包括table/index统计与配置、表达式、CHECK、业务行内容SHA和直接/有效权限。目录行仅在private comparison中按身份/有序字段保留重复地排序，不写回原件或把复合顺序丢掉。
- 最小profile必须在writer完整暂停及原同连接围栏内，与native export同一保护操作的before/after库存一起采集并认证。50旁证不能注入或追认48包内容；若选择该最终恢复合同，需要新唯一封包，不改/覆盖48旧包和失败原件。源起源证明作为派生技术元数据，拟在认证payload中保存原件对象，在AAD header中绑定其canonical SHA及原文件SHA/来源；不增加第三份业务资料、不改变原两材料权利或18:00有效期。
- 候选生成名源必须由受控原件/执行链/当前身份证明；目标必须是原获准空目标、真实native import、完整围栏后的独立库存，不能用测试副本。32手工索引与714手工约束名严格；954系统索引需明确归属；292生成约束逐一映射，4组内部对象仅在原生标志和完整图/正文引用同时闭合后映射。任意多对一、重复、缺项、额外对象、只改一处引用、unknown flag或源证明失配均失败。
- 恢复CLI须保存独立唯一空目标raw、import后raw及比较差异证据，不只返回成功计数；失败保留原native、目标与输出，不自动重试或删除。metadata proof不能授予恢复目标的当前业务授权，businessActivation仍false。实际仅一库在线的停启/返回安排、维护writer归属和资源预算仍按既有独立恢复准入另核，不能由这个目录D自动批准源停服、C或新运行身份。

**本轮D退出边界：** 50已取得原生完整图/来源生存链并发现、定位62外键采集缺口，故不再把这些事实留给用户确认；但补采profile的最终字段白名单、payload/CLI原件出口接线合同和逐项正反例矩阵仍须完成，ARC保持进行中、FIX未开始。现有比较器未修改，48真实导出/封包工程事实保留，但不以它或本次只读检查宣称恢复目录完整验证/独立恢复/父A/B/C通过。安全下一步为冻结上述最小profile/源证明及比较/失败出口D，而不是贸然import或默默删除未比字段。

#### 9.27.4 最小恢复profile、认证及比较出口冻结（2026-10-10，派生技术D）

责任方Codex；范围仅本任务的DM8 8.1.4.170原生恢复工具，继承已通过的工程R/D及原资源/窗口/数据库和两原件许可，不新增业务授权。依据是49/50固定目录SELECT、105原迁移SHA/210链事件/110 ALTER、281外键/447有序列及4组完整内部图；原件路径和SHA逐项固化在[最小字段profile](../evidence/single-database-installation-20261009/approved-execution/restore-profile51/minimal-profile51.json)。50原回执引用的10件原件SHA均核一致；48原包SHA仍9480f1d388930195d32a9157690be441c5e1c1401fe6834345af007f42254c18，不修改旧包。

**采集合同。** profileId=`DM8_8_1_4_170_RESTORE_MINIMAL_01`。六schema限定、固定SELECT，不接受调用者SQL。同源保护围栏中的同一次inventory调用、同一JDBC连接附加`restoreCatalog`；版本必须精确相同，既有所有raw/JDBC外键及数据摘要/账号直接和有效权限原样保留。固定类别是DBA_TABLES、DBA_IND_EXPRESSIONS、SYSOBJECTS（完整INFO1..8/VALID/VERSION）、SYSCONS（含CHECKINFO/COLID）、SYSINDEXES、内部SYSCOLUMNS/全部SYSTEXTS片段及固定10个系统view正文（DBA_CONSTRAINTS/DBA_CONS_COLUMNS/DBA_INDEXES/DBA_IND_COLUMNS/DBA_IND_EXPRESSIONS/DBA_OBJECTS/DBA_TAB_COLUMNS/DBA_TRIGGERS/DBA_DEPENDENCIES/DBA_TABLES）。每类字段白名单见profile，查询元数据必须记录列标签、JDBC类型整数和类型名，重复/缺/额外标签及未知类型拒绝。SYSDEPENDENCIES/SYSOBJINFOS仅当前已证为空分支：固定COUNT查询成功且为0才准入；非0整体拒绝，不只丢行或默认空。内部表另采hiddenTableCounts（SCHEMA_NAME/TABLE_NAME/ROW_COUNT），由已类型核验的原生UTAB隐藏标志及六schema/安全标识符生成只读固定COUNT查询，逐表计数必须为0；完整空表内容由COUNT0证明，不读取未知内部非空正文。表身份/数量必须与完整nativeObjects隐藏表集合一对一；非空、缺表或多表整体拒绝。字段清单初版漏此已设计类别已于实施前补齐，不能把已有源旁证当实际COUNT0证据。

所有目录数值（含ID、INFO位图和DECIMAL）在Java读取后直接转换为十进制字符串，整数不经过double或JS Number；DECIMAL使用toPlainString；null、空字符串、布尔和二进制（独立binaryBase64标签）严格区分。时间保留JDBC原始精度，不转换本机时区。历史49/50数值JSON不先经过JS Number再作位运算或来源证明；原件字节保留，使用无损解析/原Java重新采集。每查询15秒/10000行并用第10001行检测截断，单值2MiB、补采总16MiB；整体仍受原JDBC32MiB/120秒和保护容量限制。SQL失败/超限不返回complete成功或默认空。

**外键与完整性。** 不覆盖旧foreignKeys。新完整外键从SYSCONS.TYPE$=F的INDEXID/FINDEXID→原生两个索引→有序DBA_IND_COLUMNS→父P/U约束闭合，列位置必须1..KEYNUM、两端等长、TABLEID/SCHEMA/引用所有者和兼容目录R_CONSTRAINT_NAME/R_OWNER/INDEX_NAME一致。当前FACTION仅允许精确两个空格并与DELETE_RULE=NO ACTION核对；其他规则值拒绝未知，不借相似库猜含义。所有已返回JDBC外键逐列/方向/规则必须匹配原生，所有额外/重复JDBC行拒绝；缺JDBC项只允许完整原生所证的U引用，不能据219条声称全集。281/447为本次源实例事实，不作为未来固定候选数；新源必须实际核全集。KEYNUM=0仅当前证得的原生零键聚集关系合法；表达式查询成功0并且XTYPE函数位全0才通过当前无函数索引分支；任何不完整列/隐藏对象/正文/引用整体拒绝。

**认证接线。** 新snapshot配置显式钉住profileId/配置SHA及起源原件路径/SHA。起源50原件对象与原始字节（base64）、原文件SHA进入新认证payload；AAD新增profile SHA、catalog canonical SHA、origin raw SHA和origin canonical SHA。读取时同时验证原字节SHA/解析对象一致/起源target及版本/源每个候选与当前同名同ID同table/type/创建时间、714手工约束及32手工索引来源闭合；不把sourceOriginProof当当前业务授权，也不声称历史无未知维护。源before/after原inventoryDigest继续严格原定义，另比较补采完整摘要（包括原生权限）；不改函数，不改旧source digest。50旁证不回填48。新包采用CreateNew，仍AES-256-GCM/原密钥隔离/最短使用期限/完整停写和同连接围栏；咨询随后新增数据必须在后继保护的真实before/after内。

**独立恢复比较。** 原source/target库存及补采全部raw先CreateNew输出，再生成差异报告，控制台只输出路径/固定码/计数和SHA，不输出秘密、业务正文或DPAPI内容。配置、profile/版本/view正文、认证来源缺失在IMPORT_STARTED之前拒绝。旧v2包仍可按原严格合同读取，但不能充当新profile恢复完整性通过；本次真实独立恢复必须新认证profile包。结果包含raw两端SHA、原inventoryDigest两端值、逐项路径差异、限定转换及一对一映射证据；失败也必须保留报告和原件，不吞原生权限差异。

语义比较只允许：目标容器ID替换；被当前view证得的约束LAST_CHANGE及对应CRTDATE作为重建创建时刻（原始值仍报告）；来自认证292个未命名声明且语义键唯一的生成约束名，语义键为schema/table/type/完整有序列/CHECK或外键两端+规则；原生FLAG与完整归属证得的系统索引按父约束角色或table/零键聚集角色的一对一身份；4组完整自引用图按父外键及LEVEL/REFING/REFED、BS/AR/AS角色映射。名字不是正则前缀白名单，714手工约束/32手工索引必须原名一致，重复同语义对象拒绝歧义。ID/SCHID/PID/TABLEID/INDEXID/FINDEXID/TRIGID仅通过已闭合的对象映射转换，-1哨兵保持，未解析引用拒绝。内部触发器全文保留；只替换已证图中的独立SQL标识符token，不改字符串/注释内容，不做全局数字或子串替换。列/有序位置/方向/类型/唯一性/表达式/CHECK/规则/对象数量/INFO位图/状态及其他未明确字段严格比较；创建时间以外统计/物理字段未知差异默认失败，不能泛删。表行数和内容SHA、直接及PUBLIC/递归角色有效权限/ADMIN_OPTION/GRANTABLE/账号状态严格逐项相等；权限来源及原nativePrivileges也比较，不能只比较inventoryDigest未含的根外字段。

**可执行正反例合同（不是实际恢复证据）。** 正例：完整原件自比；顺序变化保持同一多重集合；仅目标ID变化；已证创建时刻变化；认证生成约束/系统索引/4组内部图全部一对一引用同时转换。各转换单独验证，未实现的转换不得被成功路径接受。负例：缺/额外类别字段、数字舍入/不安全Number、版本/view变化、查询失败/截断、少一个外键/只保留JDBC219、U引用缺列、复合列换序/方向变化、KEYNUM不符、规则/唯一性/CHECK/表达式变化、函数位与空表达式矛盾、少一个重复对象、手工名改动、生成来源失配/多解/单处引用未改、内部列或完整正文/字符串改变、未知INFO位变动、同行数但内容SHA变化、直接或有效权限/PUBLIC/ADMIN_OPTION/GRANTABLE/角色循环或账号变化、起源raw/canonical/AAD不一致、50旁证冒48认证包、源围栏内原或补采变化、失败无raw出口。FIX须逐项覆盖本合同并作现有native/fence/restore回归，单元通过不得冒真实dimp。

**D审结及实施边界。** 最终字段/无损类型、完整性守卫、认证接线、raw/差异CLI及限定转换/正反例合同已经明确，现有Q的技术未知不再留给用户作决定；本D解除同Task FIX源码实施，不批准提前导入或增加第二在线目标，也不关闭父A/B/C。FIX先完成无损补采和完整外键守卫，再实接认证及比较；尚未实施的环节保持fail closed。真实恢复仍需新保护包、原批准确切空独立目标/源停库/全写暂停、预算、确切步骤/失败返回源核齐及当前18:00窗口准入。不重放105/674/bootstrap，不改旧5237/资源/ACL/秘密，不代本人激活或选区，不进行模型/计算/矩阵。

9.27.4实施中已核的接线细节：50 manualIndexes原证据保存了schema/table/原SQL路径、SHA及statementOrdinal，但没有序列化字典的index-name键；不改50原件，认证同时保存受控起源proof与原105迁移history两件原字节/解析对象，从被钉住的原CREATE INDEX statement恢复确切名称再与当前32手工索引对账；每个原SQL实际字节SHA再核，不据table或数组位置猜名。51新Java只生成一个主class（不增加未被原class-SHA钉住的辅助class），真实同连接补采及起源守卫已经在当前主库只读执行成功，见actual-profile-read51-receipt；12内部表确实查询COUNT0不是unit假设。组件封包正反例及旧回归是工程证据，新围栏包/真实独立恢复仍须实际执行，不能据unit native buffer报恢复完成。

9.27.4的52实施接线补充（不是新运行授权）：当前10个SYS view已从51真实批准原件冻结逐件正文SHA，源/目标即使同时同版改正文亦拒绝；profile字段/ID不变，AAD已有catalogSHA覆盖捕获的完整正文，原51/48原件不追改。纯比较器在全对象映射后严格比较所有非明确转换字段与多重集合：CHECK列成员由实际DBA_CONS_COLUMNS的null POSITION证得无序成员，P/U/F仍按完整位置；null不改成字符串。inventorySha256、restoreCatalogSha256、foreignKeyDependencies、fullNativeForeignKeys是inventory CLI生成的可选回执字段，仅逐个重新计算核真后不作第二份独立业务值比较，原raw及两端旧inventoryDigest全留证；任何未知root/schema字段拒绝。恢复配置同时绑定既有recoveryCatalog完整SHA与独占recoveryComparisonDirectory，新包配置/profile/AAD/两来源原字节、源自比完整图及目标空前库版本/view均先于IMPORT_STARTED；缺失raw读取/写出port不能落入旧恢复路径。JDBC增加只读captureRaw端口保存原stdout，不改inventoryDigest；源raw是已认证包/ inventory节点的无损JSON序列化（仍可从原加密包复核），目标raw是该次JDBC返回的原字节，必须与返回对象完全匹配。预/后阶段失败的可得原件通过CreateNew输出；没有post结果时只登记明确失败与PREIMPORT-only/JSON-invalid原件，绝不把空或pre库存冒post成功，原native/包/部分现场及失败journal保留不重试。独立离线CLI只认证/比对/写证据，没有导入、业务授权或可用恢复声明；报告只含路径/码/计数/两端SHA/一对一物理目录ID及变换哈希，不含SQL正文/值/秘密。时间/角色循环/权限有效闭包/数据内容/未知统计物理字段及真实独立dimp仍须各自验；组件native buffer不冒恢复。


### 9.27.5 独立逻辑恢复空目标准入（2026-10-10，派生技术D）

稳定任务 `ARC-SINGLE-DB-EMPTY-RESTORE-ADMISSION-01`，依赖9.27.4及原确切resource01批准。来源版本、SHA、责任方Codex、日期、限定范围及结论完整记录于 `../evidence/single-database-installation-20261009/approved-execution/independent-restore54/empty-target-technical-d54.json`。当前adapter使用逻辑dimp，不是物理文件恢复；必须有在线空目标。既有primary entry不改；新专属restore entry只允许确切新restore owner marker/loop491041/3GiB、原物理参数、stdin受管管理员/审计员密码，既存dm.ini/其他内容拒绝，失败现场不重试。15受管账号及源直接最小系统权限属于原空环境管理员批准，不运行105迁移/674对象授权/三域维护初始化；PUBLIC只按原已实际执行273项收紧manifest及固定PUBLIC/SOI/SVI policy核验，新目标不能继承宽默认。domain对象/行/授权仅由一次认证原生导入产生。

主执行登记完整写入责任，先停原五角色及source并核PID0再启动target，新restore工具与原维护工具不并行运行。实际空库profile/version/十view/权限前置校验后才能导入。创建/初始化/账号DCL/原生导入任何失败都保留、不得无判据重试；target和restore工具先停，再返回原source/tools/五角色，核原image/IP、TLS和完整source库存。17:10后不启动副作用、17:35强制开始返回，18:00截止不延长。安全时间不足则只保留已验证工程，不冒险停库。退出不是业务/父A/B/C验收；完整比较未知或差异仍拒绝，不修改白名单吞差异。


9.27.5预算接线校正：54首次执行在任何创建/停源/解密前因实际七角色3.85CPU+原助手0.25CPU>4而拒绝，失败原件保留。新54b只把串行storage/device助手各限制0.10CPU，峰值3.95CPU，内存及target/source权限不变；确切事实和责任方见independent-restore54/budget-reconciliation54b.json。不是部分初始化或导入重试；source/tool/五角色完全未动、target/卷未增，后续仍逐步当前准入。


9.27.5存储实际失败补充D（54c）：原source02/03采用b7:491040 rwm；54b遗漏对应restore规则，实际9bf2在losetup EPERM退出。完整原件和只读proof证明本次3GiB image（UUID a36112b7-8e05-468e-a42a-1404e3d09364/inode154723/device2096/0600）根仅lost+found、loop491041尺寸0未绑定，无DB。限定恢复D见independent-restore54/storage-partial-recovery-d54c.json：只允许b7:491041 rwm、绑定此现有本次空image，不mkfs/不新增或detach loop/不删失败原件；首次ENOSPC和marker按原存储脚本后段执行一次。source不动，实际目标启动仍须原source PID0及安全窗口。派生技术修复不增加业务许可、专业/模型/C权限。


9.27.5 / Q-SINGLE-DB-EMPTY-LOGICAL-RESTORE-01 54d限定补充：54c实际ENOSPC、stable device/3GiB volume和target789636初始dminit通过；source及五角色/工具PID0串行已证。准备current()在任何JDBC/DCL前以SIX_SCHEMAS_ONE_ENDPOINT_REQUIRED拒绝：六绑定仍原source URL（本次编排错误，不是外部阻塞）；原target/tool已停止，source/五角色原image/IP返回、原件全留。54d详细D只校正六绑定到确切restore endpoint，并凭已完成初始化/原dm.ini SHA/ready log/无DCL或导入原件，为同一target开启显式START_EXISTING_APPROVED_RESTORE入口；不dminit、不资源重建、不隐式重试、不删原件。来源/版本/责任Codex/日期/范围/结论见independent-restore54/completed-init-proof54d.json。原fresh-init17:10截止不变；仅已初始化目标再次停源/启动最晚17:30，17:40开始返回（原53已有20分钟安全余量，实际54c原角色返回约49秒；新目标/工具退出及原source完整读/TLS另留20分钟，18:00绝不延长）。PUBLIC每项保持实时sourcePID0+既有JDBC target/tool真实身份/SHA准入，完整writer/资源初末核验及wx维护锁不变，避免每项重复六份无业务状态变化的Docker枚举；不是放宽权限或共享维护。


9.27.5 / Q-SINGLE-DB-EMPTY-LOGICAL-RESTORE-01 启动观察54e技术结论：54d existing-only入口已实际输出NO_DMINIT，proof/ini SHA通过；但首次异步就绪观察把旧Stopped/StartedAt当本次退出，回收随即对新启动发送SIGTERM。失败事件与实际DM log时序/源码见startup-observation-d54e.json，不能说DM无原因坏了或已恢复。没有target JDBC/DCL/15账号/导入；原target/tool已停，source及五角色再返回原image/IP，原件保持。当前剩余安全预算与已冻结17:30 existing-start准入不足，禁止再停源；物理接续阻塞于新确切维护窗口（原审批人/管理员负责，18:00不延长），同一FIX继续安全离线就绪状态判断修复和原source完整读/TLS退出。明确区分旧捕获pending/本次running/本次exit，ready只读本次StartedAt之后日志，不得用历史READY；ID/状态/超时未知拒绝。新批准应覆盖确切789636 target、既有空库/15账号/native源包及source停/返回步骤；不是要求用户重述任务或重复初始化。 来源版本、责任方Codex、2026-10-10、范围与结论见independent-restore54/startup-observation-d54e.json。

### 9.27.6 57已就绪但目标准备复用撤回分支的纠正D（2026-10-10）

责任方Codex；来源为57真实events/result/public-tightening START0、源码prepare-target54.mjs及原最终部署ADR第5节、primary-native-public-hardening-failure01.json与native-failed-branch-withdrawal03.json。57已实际无dminit就绪，失败发生在PUBLIC第0步后，不能再报初始化proof拒绝。primary同SQL已真实28000/-5571失败并撤回，现合同固定该确切DM8版本PUBLIC/SVI自身对象/系统目录基线，禁止额外ANY/业务域PUBLIC/陌生角色；不是“把PUBLIC清空”。57实际vendor输出因runNativeCommand非0清零未保留，不能由primary历史冒补。

D未通过。先完成VER-SINGLE-DB-RESTORE-TARGET-STATE-01只读状态：现时native-policy/PUBLIC与57前态、15账号/对象存在、当前源安全返回及原包绑定。读取不得执行旧harden-public-step、revoke、create、bootstrap/import，不重dminit。若现态与当前固定native定义不符或账号已部分存在，保持场景并拒绝自动重入，另做确切恢复设计，不默认为空。

候选修复仅用当前已批准nativePrivileges读取和hasNativePrivileges/compileInstallation bootstrap；不再引用失败旧runtime02 hardening class/manifest。原nativePrivilege母任务仍进行中，本切片不能冒其跨域业务否例/生产验收。D出口需以目标真实读取闭合，并在docs02记录来源/版本/责任/日期/范围的技术结论后才实施FIX；任务状态只在docs08。


#### 9.27.6 58现时只读证据闭合与纠正D冻结

2026-10-10，责任Codex。58实际串行停源、已有target no-dminit就绪、当前JDBC唯一bootstrap-inventory成功。PUBLIC system/role与57前态逐项一致，PUBLIC/SOI/SVI符合当前固定native定义；15账号及对象全部不存在。目标停、七原角色原image/IP返回；完整源数据/结构/账号及restoreCatalog逐字节相同，严格CA/SAN/server pin三GET200，预算6.75GiB/3.85CPU及maintenance无Java/锁已实际核证。原件及版本/SHA见 `../evidence/single-database-installation-20261009/approved-execution/independent-restore58/native-preparation-technical-d58.json`（含raw/返回/源码来源）。这仅证明当前场景，不还原57丢失vendor结果。

此技术D通过，只准FIX-SINGLE-DB-RESTORE-NATIVE-PREPARATION-01最小修复：新独占prepare器彻底不引用runtime02/harden-public-step/旧清空PUBLIC manifest；用当前被钉住的native JDBC、six endpoint及hasNativePrivileges（必须true）核实际空状态，compileInstallation(stage bootstrap)与现行typed bootstrap接线。最终部署ADR第5节**后续实际默认值更正**优先于早期SOI拟议：新账号默认PUBLIC(N)、CREATE SESSION(NO)、自身SCH，无SOI；不生成重复CREATE SESSION或SOI撤权。15账号及最小owner grants按原安装profile，任何角色/系统/对象偏差均由前后库存拒绝；不借此重放源105迁移/674授权/三域初始化。

运行仍需新当前准入、原包/源完整库存绑定、source及writer PID0、目标独占锁/固定类及decision SHA。typed bootstrap逐步实际inventory和持久journal是账号DCL出口，部分或未知DDL立即保留停目标/返回源，不自动重入、不推定回滚；不得先import再解释差异。完整empty profile/源直接权限及restore-profile相等后才另准入一次native import；import失败及比较差异仍保留场景。58读成功不算bootstrap、恢复、跨域业务授权或父A/B/C验收，母nativePrivilege任务仍进行中。后续专业/身份/来源/业务D不受此D放行。


### 9.27.7 59首次真实导入严格失败及60现态差异事实（D未通过）

责任Codex，日期2026-10-10；来源版本/SHA是independent-restore59当前准入、native-preparation/24步bootstrap/preimport及restore原journal/raw/comparison、source-safe-return59，及60b原始库存digest固定的只读诊断。59已有目标无dminit就绪、15账号及原最小24步typed bootstrap真实完成；preimport完整空结构/直接系统与角色权限/版本十view等前置通过；一次native import实际返回并通过工具输出检查，但生产严格语义比较以RESTORE_SEMANTIC_DIFFERENCE_CAPACITY拒绝，不能称恢复成功。原目标/tool已停，原七角色image/IP、全源数据/结构/账号/恢复目录、严格三GET200及资源/maintenance返回全部已实际证。旧“native import0”是59前历史，不再描述当前目标；已导入target禁止再作为空目标/neverImported分支使用。

60b仅对原raw（两SHA绑定comparison失败报告）及当前认证origin/受控semantic-map作离线诊断，未修改生产比较器/10,000容量/profile/白名单，没有新的恢复回执。2288语义对象映射与266个库存表count/content SHA一致；稳定诊断键完整无缺/多身份，无未定位集合。实际仍有GRANTOR 1348叶差异（674直接授权加有效授权投影重复，源owner→SYSDBA，其余逐项授权字段与重数一致）；nativeObjects还有CRTDATE1276、VERSION246、INFO5.binaryBase64 66、INFO4 15差异。每类字段hash样本及raw来源见independent-restore60b/restore-difference-diagnosis60.json。不能凭数据一致或相同权限集合推GRANTOR/撤销权等价，也不能把隐藏binary/INFO4默认成非业务噪音。

新ARC-SINGLE-DB-RESTORE-ACTUAL-DELTA-D-01依赖60诊断出口，当前仅离线设计。须核对应版本官方安装手册、原迁移来源、每种object类别/字段位置及实际字节关系；明确权限来源/撤销效果、失败恢复和每项被允许变换的精确谓词/反例。没有充分依据则保持拒绝，不扩生产容量、不直接重放源674、不清target或盲重import、不自行把未知字段归入生成值。目标已导入场景的再次启动/只读核证也须新确切现态准入，不复用55外层空/neverImported证明。父A/B/C、母原生权限、业务/本人验收均未退出；主计划继续为唯一状态记录。


### 9.27.8 61有界离线判据与撤销责任复核（技术D不通过）

日期2026-10-10 Asia/Shanghai；责任Codex技术执行者；对应当前任务ARC-SINGLE-DB-RESTORE-ACTUAL-DELTA-D-01。这是技术推导，不是原始业务需求或恢复验收。断流指纹、已落盘原件SHA、限定进程/锁检查、未知范围与不重放结论见 `../evidence/single-database-installation-20261009/approved-execution/independent-restore61/bounded-continuation61.json`。前置沿60b已结束完整治理（退出1，仅既有project健康红），不复测充进展。源维护工具仅sleep，target/tool PID0；本机一个命令行未知，指定目录无锁不代表全部worker/锁退出，不构成运行准入。

**固定来源及范围**：只用59原始source/target inventory、comparison及native import journal，60b已SHA绑定诊断/安装手册提取，61静态参数证据。安装版本DM DBMS 8.1.4.170；手册来源及PDF SHA见60b `installed-permission-manual-source60.json`、`installed-manual-delta-source60.json` 与46 `installed-manual-search46.json`。SQL PDF SHA为2939780f6ece716720c8446b017272ec36c58d32f075e13fdecdc2a07bfe9938。全部链接与raw保留，不新盘点数据库。以下是未来最小规则的必要条件，不是已经获准的比较白名单。

| 差异 | 精确候选谓词与保留项 | 必须拒绝的安全反例（设计用例，未执行） |
|---|---|---|
| GRANTOR：674直接/674有效投影重复 | 仅认证映射中普通本域表；源grantor=owner，目标grantor=本次journal确证执行人SYSDBA。owner/object/grantee/privilege/grantable/hierarchy/重数及原系统/角色全部相等；不新增PUBLIC、ANY、角色/列授权或转授权链。直接与有效投影关联复核，不当1348独立授权。授权历史raw不能擦除 | 源grantor非owner、目标非执行人、owner/grantee改变、GRANTABLE改变、新增角色/ANY/PUBLIC/列授权、重数或有效权限改变，任一拒绝；即使权限集合相同，撤销责任未证也拒绝 |
| CRTDATE：1276叶 | 仅认证映射TABLE266、SELF_GRAPH内部UTAB/TRIG24、MANUAL/SYSTEM_INDEX986的目录创建时间；父图/自引用图/索引逻辑定义一致。约束1006原有归一规则不扩大 | 业务日期、触发器执行时间、其他对象类别、未映射对象、父图/触发定义变化拒绝 |
| VERSION：246叶 | 仅普通TABLE/UTAB的原生字典对象版本，安装管理手册PDF页0597定义INTEGER；以规范十进制字符串和BigInt校验非负及INTEGER范围，绝不用JS Number类型误报；所有逻辑定义/完整数据仍严格相等 | 业务行版本、其他对象VERSION、非规范数值、越界、定义/数据变化拒绝 |
| INFO4：15叶 | 管理手册PDF页0599/0600：仅普通表低32位表字典变化版本候选可重建，高32位大字段数据版本逐项严等。规范十进制字符串经BigInt解位，不全字段删除。本次15处只低位变，高位均0 | 高32位变化、其他对象同名字段、非法/越界值、业务计数或数据变化拒绝 |
| INFO5：66表 | 手册定义普通表前10字节为BLOB段头；本次raw固定22字节，仅零基4/5改变。候选刻意限当前4/5，其他20字节（含后12字节）严等；必须已识别LOB列、完整typed row/table digest相等。Java reader完整读取Blob/Clob，单LOB>8MiB或累计>128MiB抛错而非截断 | 非LOB/非普通表、非22字节、其他偏移/尾部变化、未知LOB类别、reader截断/超限/读取失败、任何行count/content SHA变化拒绝；不得泛化整10字节/整binary抹除 |

对应安装手册文本：60b `installed-dm8-admin46-object-priv-mode-page-0182.txt`（印刷166页：OBJECT_PRIV_MODE=0静态DM规则）；60b `installed-dm8-admin46-delta-page-0597.txt`、0599/0600/0608（VERSION/CRTDATE/INFO4/INFO5/GRANTOR）；60b `installed-dm8-security60-object-authority-page-0036.txt`、0056/0060/0061/permission-page-0062/0063（授权者、回收、级联、多路径）。Java读取边界位于 `D:/daima/xinji/agent_platform/scripts/java/SingleDatabaseLifecycleJdbc.java` 第44—61行。raw字段类型及字节位置证据见60b `native-field-kind-diagnosis60.json`、`native-header-byte-position60.json`。266表count/content SHA一致仅是数据出口，不证明目录/撤销责任出口。

**撤销责任已证与未证分开**：61 `static-object-priv-mode61.json`证明两份已有ini全文SHA相同、OBJECT_PRIV_MODE均0、target ini自preimport未变；启动时刻晚于文件mtime。它不是新JDBC运行参数查询，也不是实际REVOKE试验。安全手册印刷45页说明用户对自身模式表隐含所有对象权限；印刷49页说明回收者须具有相应对象权限及转授权，并由OBJECT_PRIV_MODE控制回收策略；印刷50页说明8.1.4.109以后GRANT ANY OBJECT PRIVILEGE可回收任意对象及CASCADE/多路径效果。SYSDBA能力不能推给普通runtime/backup；owner隐含权限也不能直接推导每一条SYSDBA记账授权可由owner按原语句回收。

**最小未决问题**：在固定8.1.4.170、mode0、无转授权/列授权且对象owner不变的本次674集合中，owner是否能按源同一REVOKE语句回收目标SYSDBA所记授权，并保持失败条件、回收影响范围及多路径行为一致？当前所取手册没有明确回答该跨grantor匹配问题，原raw只是结果状态，没有该操作语义实证。必须取得该版本官方明确规则/实现依据，或另行详细D与确切已导入目标准入批准下的受控实验，才可闭合；本次不运行实验、不以其他版本/Oracle规则代答。

**D结论**：五类差异已有具体字段语义、候选谓词与反例；最小阻塞是跨grantor撤销语义证据不足。整体D仍不通过，Q保持未确认，任务保持进行中。不得实现比较修复、adapter/权限变化、增加capacity、启动已导入target、再bootstrap/import或复用targetNeverImported=true。设计反例没有冒充组件测试；59实际严格失败原件不覆盖，代码实现/离线重验/当前目标实际验证/本人验收均未因此完成。下一合法动作仅补上述明确版本规则证据；若需实测，先登记独立稳定任务及已导入验证D/准入，绝不由本段自动放行。资源、源返回、原维护批准与咨询恢复离线边界全部继承。


### 9.27.9 62固定版本回收规则有界原文复核

2026-10-10，责任Codex；只复用60b安装安全手册印刷49—52页（object-authority-page-0060/0061、permission-page-0062/0063）及既有语法提取，不新搜索/盘点。例1/2仅SYSDBA回收其自身授出的权限，例3的owner回收/级联/多路径链起点也都是owner自授，未提供owner回收SYSDBA授出权限的例证或跨grantor匹配规则。因此8.1.4.170/mode0/本次674无转授权集合的同语句REVOKE影响范围仍不能由原文闭合。主计划将该D证据化阻塞，owner=Codex技术执行者，出口仅当前版本官方明确规则或另行详细D、最小精确写集/可逆退出及确切已导入writer锁准入的受控实测。本轮不登记或执行DB实验，不借未知进程放行，不重import/改权限/扩大比较规则。替代路线执行已有咨询恢复D二遍复核，不关闭单库父A/B/C。


### 9.27.10 69跨grantor最小隔离验证详细设计（未放行执行）

2026-10-11，owner=Codex。承接60/60b/61/62；对应ARC-SINGLE-DB-GRANTOR-VALIDATION-D-01→VER-SINGLE-DB-GRANTOR-REVOKE-01→既有ACTUAL-DELTA-D。主计划先登记后设计，全部状态只见docs08。固定SQL及原raw摘要见[精确合同](../evidence/single-database-installation-20261009/approved-execution/minimum-recovery-design69/grantor-probe-contract69.json)，[现场有限观测](../evidence/single-database-installation-20261009/approved-execution/minimum-recovery-design69/observation69.json)不是完整writer围栏。

**原文出口**：61/62已穷尽的REVOKE例仍不足以证明8.1.4.170/mode0下owner回收SYSDBA授出权限的匹配规则，本轮不重扫或把自授例洗成答案。另从同SHA安装SQL手册目录定位PDF134—138页，新增范围只证明CREATE SCHEMA AUTHORIZATION可指定模式所属用户；PDF137说明默认SYSDBA及该选项的创建者限制。该原文不是跨grantor规则，不关闭Q。

**不能混用的主体**：ACTR66_EMPTY/RECEIPT/EVENT均SYSDBA拥有，owner与SYSDBA相同，不能证明所问的跨主体语义。原674实授权按owner/grantee/privilege/grantable/hierarchy冻结29层（不是1348独立授权），共六owner、九owner-grantee对，五种权限SELECT/INSERT/UPDATE/DELETE/REFERENCES；GRANTABLE/HIERARCHY均NO。既有主体不新建或重授权。隔离候选为CGR69_ADJAUTH、CGR69_ADJPROD、CGR69_CONAUTH、CGR69_CONPROD、CGR69_ENFAUTH、CGR69_ENFPROD，分别AUTHORIZATION同名既有owner；每模式仅GP_CONTROL/GP_RESTORED/GP_PATH三张明列两列、固定PK的工程探针表及一行(1,11)。三张表区分控制、恢复记账和双授出路径，18表/6模式是上限，不改六原模式。新增模式、探针DDL/DML和探针对象GRANT/REVOKE是独立scope差异，不从ACTR66三模式或原管理员能力推导已获准。不得为了省模式让SYSDBA充当普通owner。

**精确顺序与结论边界**：
1. 现时版本/mode=0、target789636已导入、演员登录账号、模式所属主体、零初始探针授权及无非预期有效访问先查真。管理员仅创建明列模式，owner创建明列表/工程行；任何命名已存在或所属不符拒绝，不IF EXISTS跳过。owner不能在自有新模式建表即停止，不补CREATE ANY等权限。须保留模式主体与DBA OWNER（模式名）的映射，不能混淆grantor用户名与非默认模式名。
2. 逐29层：owner向GP_CONTROL授出单项权限，由同owner执行固定REVOKE；SYSDBA向GP_RESTORED授出同项权限，由同owner执行同模板REVOKE。仅对象名映射不同，其他actor/grantee/privilege、无转授权和默认回收语句完全一致。 授出后必须先查真：控制记录GRANTOR为该owner，恢复对照记录GRANTOR为SYSDBA，grantee/privilege/GRANTABLE/HIERARCHY及精确对象均符合固定合同；如果该版本普通GRANT没有重现native-import59的SYSDBA记账状态，立即标构造未证并停止，不执行比较REVOKE，不用actor登录名代grantor事实，不写系统字典制造记录。每步保存完整相关授权/有效路径、错误码/SQLSTATE、其余权限不变及固定fixture字节，DCL按可能独立提交记录PREPARED/COMPLETED/UNKNOWN；不能用事务rollback假装回退DCL。SELECT及DML正/负访问由真实grantee连接，DML在显式事务中回滚，不改变原产品数据。连接/语法/唯一约束错误不冒权限拒绝。
3. REFERENCES不以SELECT替代：先证明回收调用与授权/有效权限记录变化；依赖外键效力必须与原认证完整FK/依赖证据逐项对照。简单探针无相关依赖不能证明有依赖表的CASCADE/拒绝影响；未覆盖条目保留阻断，不自行追加child/schema/角色/列权限探针。
4. 九个owner-grantee对仅追加GP_PATH的SELECT owner授出→SYSDBA授出→owner回收观察，记录该版本真实合并或剩余路径，不预设两行，不新增角色或转授权链。其结果是路径语义证据，不自动证明所有多路径工作流。
5. 任一owner控制/恢复对照的结果或剩余权限不一致即停止该实验，不用SYSDBA代回收再冒owner等价。若基本对照通过，也仅形成已覆盖29层的操作证据；继续核原674的对象类别/依赖/多路径适用条件，未覆盖不以采样通过宣称整体D通过。

**受限调用与现场准入**：当前67/68只封闭ACTR66/V107-U107且禁止GRANT，不能扩它们、透传SQL或借旧owner==schema工具绕过。后继源工程须先有本限定D及单一执行任务：只允许合同的目标ID、actor集合、六namespace、18表、有限GRANT/REVOKE/工程DML模板、SHA绑定合同和只读核对；不接任意SQL/账号/模式。当前没有已安装该入口，设计不冒实现。未来凭据只从既有授权受管端口进入，禁止新账号、日志或环境泄露，本轮不读秘密。

每次副作用之前现时重核批准原件、≤7GiB/4CPU、一在线DM8、当时窗口与至少20分钟安全返回余量；所有真实writer/发布器/job/数据库会话/主机进程及其锁须可归属。var无锁不是全围栏，未知writer只读对账，不杀进程/删锁。先鲜活全源保护（包含51当前持久身份，不复用过期旧包）→原writer/source/tool全PID0→启动原已导入target/tool，neverImported=false。每步journal持久后才调用；超时/提交未知立即停，不重发/回收/清理探针。

异常或完成均停止target/tool并核PID0，保留所有新探针及原失败现场；恢复原source/tools/writer前核当次保护的full6/15、目录/授权、严格TLS及PID/StartedAt/资源。源返回未证保持writer门闭，锁仅按既有own-marker规则处理；不能拿历史59观察当当前返回。原六域目录/数据和原674原授权逐项不变，额外探针授权仅容许合同明列对象；不改生产比较器忽略它们。实验后的target只是保留实验现场，不冒原59未变运行库或新包正式恢复目标。

**本轮结论**：已产出最小有限SQL写集及失败退出，不运行实验、不关闭Q、不提高capacity、不改比较白名单、不重import、不标整体或隔离D通过。缺确切scope、封闭调用实现/安装与现时全writer围栏仍分别保留；技术责任Codex，scope回复不是实验通过。scope之外的新SQL/账号/角色/附加依赖探针必须另审，不自动扩展。
