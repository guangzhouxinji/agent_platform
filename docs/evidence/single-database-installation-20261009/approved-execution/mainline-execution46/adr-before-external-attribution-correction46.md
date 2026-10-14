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

`FIX-SINGLE-DB-FENCE-DNS-NAMES-01`依赖既有单库工程R/D；技术责任Codex。46真实目标在确切Internal networkId 047a31…ad65下Aliases=null、DNSNames=[原source完整name,原短ID]；原JDBC库存通过其既有Name合同，但native围栏只读Aliases而在执行class/S HARE/native前拒绝。当前Docker原始网络结果及失败日志为具体输入，不把运行失败转成业务规则。官方Moby api/types/network/endpoint.go公开EndpointSettings定义亦包含DNSNames（本次只作为字段模型来源，不宣称运行daemon与master同版）。

同Task详细实现：仅single-database-native-fence-container.mjs中的确切networkId下DNS名匹配与其专测。若该网络存在DNSNames字段，只接受非空纯字符串array中精确同jdbc alias；空/null/非法类型/错误名必须拒绝，不允许由Aliases兜底遮盖。只有DNSNames字段完全缺失的旧检查结果才沿既有Aliases精确数组合同。禁止接受db.Name全局匹配、跨networkId、子串/IP/模糊名。其余actual64ID/Running/owner/readonly/nonroot/CPU/memory/类与Java源SHA/同连接SHARE、现时批准/完整writer/维护锁保持完全不变；不改JDBC、部署、Docker网络、DB或恢复比较器。

退出：真实DNSNames/Aliases=null正例及DNSNames缺失旧Aliases正例；空/null/非法值/mixed/wrong name/其它network命中/当前DNS与旧Aliases冲突反例全拒；原专测回归。随后只读prepareContainerFence对原source/ff/network/class真实检查（不执行返回invocation，不取JDBC锁或native导出），必须通过才可在新明确操作回执下再次source snapshot。第一次明确在原生前失败不当未知dexp重放，原日志/锁/所有失败不覆盖。当前父A/B/C及独立restore仍不关闭；操作返回必须先完成9.22原精确IP/两透传原字节与严格TLS恢复，不因新修源改变既有咨询25/裁决39/执法44发行。


9.22/9.23限定退出补证：09:15已恢复七原角色同ID/image/原Internal IP和原两透传JAR/config SHA，预算6.75GiB/3.85CPU；严格咨询三GET200与完整六schema/15账号/674权限库存回读exit0、摘要43763a66…与暂停前完全相同。DNS守卫窄修136/136工程专项及原source/ff/class两条真实只读prepare通过，未执行返回invocation。只完成这两技术窄项，第一次原生前失败/TLS拒绝/IP轮换原件完整留证，真实source原生保护及独立恢复仍待分别实施。

### 9.24 明确前native失败后的新source保护安排（2026-10-10）

既有IMP-SINGLE-DB-NATIVE-FENCE-01接续9.21，原批准/window/目标/六schema/15账号/受管引用不变。第一次46已明确在native之前守卫拒绝，Java/JDBC围栏/native均未开始，package/receipt均不存在，锁释放与全原运行已按9.22实际核验，不能冒作未知native重放。9.23实际只读准备通过后，允许新确切一次CLI尝试，独立新config/output/receipt/events(46-02)，保持原失败原件不覆盖、无自动重试。

本次暂停先按确切ID停止原两透传并inspect PID0，再停止三原应用并inspect PID0，避免其在已知DNS临时缺失期继续解析；这只是既有原角色可逆运维不新设资源。source保持在线，ff只消费原native/同连接SHARE适配。snapshot自然结束后锁消失/ff无java才返回：按原consultation→consultation透传→adjudication→adjudication透传→enforcement顺序，逐项原IP/image/两非秘密SHA、实际当前Started及预算严格核验；未知或IP差异只留现场，不改targetIp或重包。完整最新库存与暂停前摘要、严格TLS与原账号/权限核验再退出。独立restore仍0，原dump/key期限10-10 18:00北京不延长；保护失败/未知只保留原始现场，实际native已开始时不再发dexp。新实际source成功与独立恢复通过/父A/B/C退出仍分别记录。
