# 单业务 DM8 的受管工具入口

技术合同见 `docs/adr/single-database-recovery-assessment-20261007.md` §9.7—9.13；任务、准入、状态和实际出口只见 `docs/08-总开发实现计划.md`。本文不是另一份实施计划或运维批准。

## 架构与环境边界

后续工程目标只有一个持久 DM8 业务实例、三个独立应用、六个业务/身份 schema、十五个独立账号（六 owner、六 runtime、三 authority backup）和三个独立 HTTPS Issuer。恢复验收临时使用另一实例/卷，不是产品正常运行的第二数据库。

`deployment/database-pair/config.json` 和原 independent 适配是旧环境的历史/过渡兼容输入，不是新测试环境模板，也不是 shared-instance 的隐式回退。保留至获准现行迁移、连接切换、旧环境禁止写入及保留/退出合同满足；不能仅换旧 JAR 安全恢复新写入。当前工具不更改旧连接或启动旧容器。

最终新环境必须用 `SINGLE_DM8_DEPLOYMENT/v2`（v1/十二账号仅历史兼容） 明确三域绑定，业务及 authority 使用相同确切 DM8 URL、不同账号/schema。沿既有 `prepare-single-database-lifecycle.mjs` / `validate-single-database-deployment.ps1` 生成并核对受管绑定。不得填工程 `.test` Issuer、disabled 工程主体或旧资料决定当作正式身份/数据准入。Issuer、维护证书和服务凭据引用使用批准的真实配置；密码和私钥不进入配置模板、清单、源码或回执。

## 全部原件核对

```powershell
node scripts/catalog-single-database-source.mjs --output docs/evidence/NEW_DIRECTORY/source-catalog.json
```

输出必须为新文件，父目录先明确创建。工具读取当前源码/SQL摘要和技术引用；完整审核清单逐个选择或排除原件，依据及 schema 必须显式。历史开发 SQL 不因存在而进入最终产品；重复版本按确切 schema 处理，不覆盖编号或原字节。动态/间接 SQL 的诊断须人工对照实际产品装配及最终配置；词法名称匹配不证明全部运行依赖。未版本化原件也不等于缺失需求或可直接盲执行。既有裁决来源原件按完整SHA登记为A001（辅助迁移注册JSON），不复制或改写原SQL。v2审核清单支持每一selected原件显式bindings数组，将共享同一原件安装到多个schema；迁移实例键为schema:id。A001固定裁决authority范围，运行CLI还要核对确切deployment schema。

## 空实例：管理员账号初始化与 owner 迁移分开

新的受管 JDBC 源码为 `scripts/java/SingleDatabaseLifecycleJdbc.java`。以项目受管 JDK21 和现有 Jackson/DM8 驱动 classpath 编译到明确的新工具目录；固定 source/class/依赖/config 的 SHA。与 Spring 发行物分开，不向产品账号提供 SYSDBA 凭据。该适配器拒绝 H2 与其它 JDBC 产品，错误只输出机械码，不输出 SQL、口令或驱动正文。

1. 先实际核对新的目标实例/卷/资源限制，不根据旧库存推断当前归属。管理员读取 `bootstrap-inventory` 取得十五目标账号的真实初始 SHA、对象和有效 PUBLIC 权限。
2. `SINGLE_DATABASE_BOOTSTRAP/v1` 清单显式列十五独立 `create-user` 与最小 `grant-system` 动作；schema owner 必须与 schema 同名。运行账号最多 CREATE SESSION，无 DBA/RESOURCE、ANY 权限、额外角色扩权或跨域授权。固有PUBLIC/SVI仅按确切镜像实测与固定native-privileges.json逐定义核验；自身原生SCH单列，无SOI不生成SOI撤权，不全豁免角色。秘密只用独立 `passwordEnv`。
3. 原责任方决定使用 `SINGLE_DATABASE_ACTION_APPROVAL/v1`，action 为 `bootstrap`，绑定 targetId、manifestSha256、configurationSha256、jdbcSourceSha256、jdbcClassSha256、executionFenceAdapterSha256、newEnvironmentOnly 和现时批准/期限。保留 currentDecisionPath 原字节并记录其 SHA；工具不生成批准。
4. 必须有实际完整 writerRegistryEvidence 与 maintenanceExclusivityEvidence。应用尚未创建也需原责任方明确 applicationsNeverStarted，不能从空列表、无会话推断停写。maintenanceLock 是绝对路径，文件名严格为 `single-database-maintenance-<targetId>.lock`。使用本仓库固定 `single-database-bootstrap-fence.mjs`；协作锁不声称阻止未登记外部 DBA。
5. 管理配置的 adminJdbc 使用 SYSDBA；inventoryJdbc、ownerJdbc、schemas/accounts/deployment 仍满足既有目标守卫。adminJdbc.mainClass 为 SingleDatabaseLifecycleJdbc，jdbcClassPath 必须指向真实受管主 class，所在目录须在 adminJdbc.classpath。配置 SHA 对去掉 approval 后的完整配置使用既有 canonical/hash 算法计算。

```powershell
node scripts/bootstrap-single-database-lifecycle.mjs CONFIG.json NEW_RECEIPT.json
node scripts/inventory-single-database-lifecycle.mjs CONFIG.json NEW_INVENTORY.json
node scripts/execute-single-database-lifecycle.mjs CONFIG.json NEW_RECEIPT.json
```

bootstrap 回执 `productInitialized:false` 是有意保留的边界：它仅初始化管理账号，不冒充所有业务对象、对象授权、水位、身份和配置已安装。之后按已审核原迁移清单由各 schema owner 执行，应用服务账号不执行 DDL。对象授权、原合同必要初始水位/配置必须在完整清单中另有明确依赖与实际后置；未完成不得启动对应业务功能。

ownerJdbc 使用同一 DM8-only 适配器的 `execute` 动作；user/schema 必须相同，拒绝系统账号。SQL 来自既有原件 SHA，受前向 parser 限制，不接收任意 SQL/自动逆迁移。唯一受控A001例外只接受原件既有的空来源水位初始化INSERT（1,0,READY）；三表均缺前置与首安装实际seed行SHA/来源及事件空行后置必须通过，existing未记完成拒绝执行，成功重入不重播seed。库存动作沿完整表/列/约束/索引/序列/视图/依赖/行 SHA/账号与有效授权算法，不以脚本退出0证明结构。

## 既有环境升级与失败重入

空环境和 existing 模式不混用。existing 必须有实际认证保护、精确当前库存/迁移日志与非破坏审查；缺日志不重放已有对象，非空表数据前置不通过删行解决。迁移键为 schema:id，源码 SHA 漂移拒绝。

结构检查支持 object（可声明类型）、column（表/列/类型/可空性）、constraint（表/名称/类型）和仅前置 table-rows（真实 min/max）。新列失败时原表仍存在不是成功；全清单检查声明在第一条 DDL 前校验。该结构检查不证明业务或法律正确。

bootstrap 与迁移均持久留存 START/SUCCESS/FAILURE 和真实失败库存。异常、部分DDL、途中到期、后置失败或未决 START 均禁止盲重发；不声称 DM8 DDL 整体回滚，不自动 DROP、逆迁移或删卷。全成功重入只在摘要和实际结构/授权不变时返回 skipped，不再提交已确认动作。未知现场必须按确切原责任方决定处置。

## 整体保护、恢复和发布

继续使用既有 `snapshot-single-database-lifecycle.mjs` 与 `restore-single-database-lifecycle.mjs`，不得打开过期包或重放已消费 RBC。必须纳入全部实际应用、Worker、导入和维护写入者，以及必要文件；明文、文件 SHA、授权、撤回和三应用恢复使用均需验证。少量工程表、file-H2、原生工具退出0或包解密不能替代三域整体生命周期。

只有唯一主计划 A 的实际出口成立后，才固定最终源码、三应用/前端、迁移与配置 SHA 并进行 B 的获准部署；新环境不默认导入现行三历史结果。无模型运行准入保持零模型/零计算 attempt/零矩阵，不阻塞基础库部署，也不冒充咨询计算或专业验收。

## 固定最终安装清单（V10.41）

本批入口不是再次扫描846条词法诊断。`installation-sources.json`逐原件登记111份V与A001的选择、排除、SHA和显式schema安装实例；历史U只保留，不执行。`installation-profile.json`消费该选择，固定105实例、266张计划表、298个显式表/索引对象及3274条最终结构声明。计数不是实际DM8安装结论。共享原件多schema安装不复制SQL；两V074分别为CONPROD与ENFPROD。A001仅ADJAUTH。身份/OAuth在本域business schema，authority仅本域现时授权与来源责任水位，不复制无消费者的身份表。

`SINGLE_DM8_DEPLOYMENT/v2`固定CONPROD/CONAUTH、ENFPROD/ENFAUTH、ADJPROD/ADJAUTH；owner与schema同名，runtime为`_SVC`，三个authority backup为`_BAK`。15账号全部独立；backup仅SESSION及本域authority逐表SELECT。v1/12保留旧工程合同，不冒充最终v2。owner仅取得该schema实际DDL所需系统权限；runtime不能执行DDL/ANY/RESOURCE。对象DML及REFERENCES来自正式装配和真实SQL使用，不从管理员bootstrap或历史脚本推导runtime权限。

空环境顺序：确切新目标/空账号库存 → compile bootstrap → 原bootstrap CLI → 普通完整inventory → compile migrate → 原owner执行CLI → 完整结构inventory → compile grants → typed grant CLI → 完整权限inventory → compile application-settings → 正常证书/职责身份bootstrap → 获准正式部署。每一步重新捕获库存；实际执行CLI仍需现时原决定、摘要、围栏和受管凭据，不从compiler生成批准。

```text
node scripts/compile-single-database-installation.mjs REQUEST.json NEW_COMPILED.json
node scripts/grant-single-database-objects.mjs PROTECTED_CONFIG.json NEW_RECEIPT.json
```

REQUEST字段为stage（bootstrap/migrate/grants/application-settings）、deployment、inventory、mode，以及existing所需逐实例nonDestructiveReviews。生成结果的manifest直接交给对应既有受管CLI；Java/class/fence/原决定与journal配置仍走既有私有配置机制。application-settings输出三份正式PropertiesLauncher main、同一确切JDBC地址、schema/runtime账号与Spring propertiesContent；密码、密钥和文件只引用环境变量。未知配置必须由批准原件绑定，不能把模板空值当运行批准。正式咨询扫描排除其它Boot启动配置，不递归装配执法。

既有环境升级单独使用mode=existing，必须先认证保护、原迁移成功链、实际结构/非空前置与逐原件非破坏审查。不得重建空库获得“升级成功”。同一已编译清单成功重入用既有journal，不重新编译出“空起点”；未决START/部分DDL/DCL仍需明确现场处理，不自动补做或重播。

原件中的CHECK替换、列MODIFY、锁分桶seed及V070/V077/V088前向复制，只在`forward-originals.json`原件SHA与确切语句SHA匹配时放行。该注册25份原件、134条确切例外，并在Java端独立固定相同摘要；默认parser仍拒绝，任意DROP TABLE/SCHEMA/USER或DML不开放。已由后继原件明确删除的旧约束，仅在对应后继SUCCESS及最终结构检查匹配时允许历史postcheck被替代。结构后置含实际JDBC列长度、约束类型和最终对象/列声明，不仅脚本退出0。

三应用与管理工具目标是owned Docker网络上同一个确切JDBC URL。`dockerJdbc`可选端口只读复核精确工具/数据库ID、同一networkId、归属、非root/只读rootfs、512MiB/0.25CPU上限及容器中实际class SHA；密码只按环境名传入exec，不出现在参数值或报告。没有目标/准入时不启动工具容器，不回落旧库或本地Java地址。整体native保护围栏的同网络运行、实际文件挂载/配置绑定及正式应用部署仍须在确切目标下验证，不能由该单请求适配通过外推。

资源申请原件`docs/evidence/single-database-installation-20261009/resource-delta-request01.json`的approvalGranted=false是历史提案，不能覆盖后续`approved-execution/resource-approval01.json`的真实人类批准。执行同时核对不可变批准/原申请SHA、确切范围、资源上限与北京时间2026-10-09 03:00至2026-10-10 18:00窗口；本说明不续期、不授予新权利。后续同类修复/正式最终运行与C条件式授权见同目录conditional-scope-approval02.json，C安全条件未有实证不能切换，退出不含删除。现时资源/实际访问/阶段出口只查主计划和现场，不把“已申请”或历史“尚未创建”当现在的事实。没有模型预算保持零外呼，新库不默认导入历史结果。旧前端可在源码/产物SHA仍适用时继承；后端全量必须使用现时受管环境，同进程注入，禁止裸Maven重现已知20项环境错误后又称产品回归。

## 中断接续与避免重复执行

会话断流不代表node/docker/java子进程终止。先只读核对进程父子链、原config/journal、维护锁、确切container/volume/UUID、日志增长与真实库存。活跃原迁移器继续观察，绝不并发第二个；锁为空也不删除。进程确已退出后，按原SUCCESS/FAILURE/未决START与对象实况对账，不重放CREATE USER/dminit或已完成DDL，不清库换目标。原失败及局部DDL保留；结果未知只进入既有受管显式恢复合同。

本次授权接续准备入口`approved-execution/prepare-primary-grants08.mjs`只消费原config07及完整receipt07/journal07；原锁存在即拒绝，不加载凭据、不连JDBC、不生成新动作文件。锁自然释放后仍要105实例日志链闭合、原批准现时有效、完整新鲜库存与最终结构匹配，才生成typed对象授权配置；准备成功不是DCL执行成功。实际DCL由原`grant-single-database-objects.mjs`在同一维护互斥下执行，之后再捕获完整有效权限库存。该入口不得冒充部分DDL恢复。

稳定结论/触发复验：日期只按当前客户端日期与明确绝对时刻换算，不延长窗口；原生默认权限只在目标镜像/定义/观察SHA改变时重新实测；回归只在源码/资料权利/受管环境改变时重新取证；已完成账号/迁移只核对原链与现时库存，不从零创建。前后verify-all和需求摘要检查仍每片执行，结构通过与业务/法律正确性分开。

### 现时授权耗时与正式启动前置

2026-10-09实际授权日志显示每项约81秒：既有执行器对每项DCL做前、后两次完整库存及结构/行/权限差异检查，同时独立启动JDBC和复核Docker归属。这是674项原固定合同的检查成本，不是重复初始化。日志连续增长且原进程命令/创建时间、锁归属、固定SHA、批准窗口和资源均不变时，继续原执行器；不要为加速改源码、删锁、减少库存或启动第二个。进程退出/FAILURE/未决START、日志长时间不增长、目标/资源/权限/固定SHA变化、批准到期，均要求重新核对现场与既有恢复条件，不按预计耗时推定成功。只有完整SUCCESS链、真实最终回执及新鲜权限库存闭合才进入应用启动。

当前正式启动器精确核对主库的两个数据volume subpath（db/logs）与唯一只读/entry.py代码挂载，并固定owned-entry原SHA/入口命令；Docker的实际Mounts.Source显示卷根目录不等于缺少subpath，必须联合HostConfig.Mounts.VolumeOptions.Subpath检查。未知bind、额外挂载、可写代码、代码SHA变化及跨卷实际库存都失败关闭，不能重建正确主库来迎合旧计数检查。三应用创建时只注册本域批准Issuer hostname为内部network-alias，并核对真实NetworkID/Aliases；内部HTTPS为8443，外部仍仅127.0.0.1:5446/5447/5448。宿主机浏览器DNS、真实TLS与当前身份权限另须实证，不忽略证书错误或换Issuer。

application-settings模板显式打开既有comparison.start.enabled的HANDOFF_ONLY入口，calculation保持关闭；真实组件mTLS、subject和确切Task授权仍必需，签发客户端证书不等于已注册Worker或完成业务。正式启动命令为pwsh -NoProfile -File scripts/start-single-database-applications.ps1 -Configuration <本批受管部署配置>；-ValidateOnly仅做无Docker/无凭据解密预检，不证明实际启动。该配置必须来自授权闭合后的新鲜完整库存与现时compiler、真实JAR/引用SHA、域内DPAPI和原批准，不能复用旧compiled清单或填假模型资产/凭据。

### 授权优化的稳定结论与应用边界

一次日志测量见approved-execution/grant-throughput-and-continuation14.json：2026-10-09 10:15:34北京时间101项SUCCESS/102 START（不是最后游标），整体SUCCESS间均76.30秒，最近20项均70.79秒、p90 106.84秒；前库存/围栏阶段均33.94秒，DCL加后库存均42.47秒，日志不能进一步分离纯SQL/JVM时间。按当时剩573项/最近均值估计10-09 21:31完成，按最近p90估计10-10凌晨；原截止10-10 18:00不延长。最近均值下授权后约20.47小时、耗时翻倍仍约9.21小时，均不是承诺足够完成全部后续验收。

用户已明确要求优化。现已实现grant-single-database-objects-session.mjs冷入口及持久NDJSON JVM，委托未改的executeObjectGrants和SingleDatabaseLifecycleJdbc.perform，全部完整库存/权限/角色/PUBLIC/对象/行与日志核验保留；每请求仍打开/关闭JDBC连接重新认证，不跨DCL持事务，不缓存SQL结果。原674最多1350全库存+674 DCL/2024次JVM启动改为当前九owner段加库存、最多10次启动，同时最多保留库存+当前owner两会话，连接和SQL次数不减。小6步协议微基准实测20请求JVM20→7、约2.7倍协议阶段，明确没有访问DM8，不能据此宣称约81秒整项变成三分之一。79专项包含原/新完整库存同次数、最终权限相同、负向与断线未知/冷接续不重复。新入口每请求复核原Docker端口/网络/类SHA、唯一只读wrapper类集合和当前批准，原Java以及额外服务器UTC期限均不放宽。

新入口可在上一项SUCCESS持久化后、下一项START前，用新鲜完整库存和旧manifest/目标/原日志链确认安全边界，进入原finally自然释放锁，关闭并确认会话退出后才写暂停回执；冷接续成功项只核不授，未决START/FAILURE仍拒绝。当前旧PID17600没有读取pause请求/信号/确定退出钩子，写新pause文件或改源码不会让它变安全；不能kill、删锁、并发DCL或篡改旧日志以追求立即收益。本批新入口尚未部署/应用。首次合法使用必须旧进程实际结束、锁自然释放、回执/链/实际库存闭合，并注册已审核只读wrapper类和新配置/决定SHA；不复用原已消费配置去冒新执行批准。新实现可用于以后相同typed授权维护，非通用任务恢复框架。

继承、不重做及复验触发：170部署专项、TLS/身份manifest与105迁移成果继续继承，不再旁支重建；只有对应源码/目标/配置/许可/密钥或证书SHA、窗口、日志/锁边界发生变化才复验相应结论。已知全量红与20环境错误不隐藏，受管回归入口仍要求新未消费RBC及现时资料权利；四替代材料不能给旧RBC续期。未有该合同，不裸跑Maven/不启动旧工程库/不编假全量报告。原674过程正常结束触发的权限→身份→三应用→整体保护恢复路线不变。

资源与退出补充：最大两个持久进程，不让七身份同时驻留512MiB工具；owner段切换确认旧进程退出再开新进程。heap128/metaspace48/code32/direct16MiB及ActiveProcessorCount1只是预算，必须真实native RSS小样本验证后才准入。readonly类目录同时检查HostConfig和实际Mounts，原/新SHA合并查验；exit非零/signal/关闭超时均不能给安全暂停回执。最新86专项和实际wrapper三类已落地，源码已集成但本批优化应用仍0，父任务仍进行中。

### 2026-10-09 当前候选构建前置（非最终发行）

旧current/lease及finally的一次有界核查没有可登记的受控停止机制；不热改、失权诱停或强杀旧授权器。原工具cgroup实测历史峰值151851008字节（144.82MiB）仅旧基线，不证明新双会话512MiB或原生加速；新wrapper挂载/配置/决定绑定和native小样本仍待原工具自然释放后准入。

复用既有同源码副本对齐2011文件，本地offline package -DskipTests -Pbundle-consultation成功（32.265秒）。候选JAR及咨询专用前端、三域loader main和bootstrap class/真实DM8驱动存在且SHA核对；三域properties由原compiler生成，环境引用仍需原获准实际绑定。候选位于docs/evidence/single-database-installation-20261009/approved-execution/formal-candidate16，收据formal-build16-artifact-receipt.json及formal-build16-properties-preparation.json。未执行测试、启动应用或连DB；不拿候选代替final-release和受管全量。

宿主hosts已完整备份为formal-dns16-hosts-original.bin；写入三内部域名映射被OS UnauthorizedAccess拒绝，复核原字节未改。有权限管理员需按原批准把consultation/enforcement/adjudication.agent-platform.internal映射127.0.0.1；不改Docker既有映射，不导入CA，不将DNS解析或配置准备声称HTTPS/浏览器已验证。

### V10.53：已绑定实际三域启动字段，等待原授权自然完成

formal-binding17/application-bindings17.json已绑定87环境项（21受管密钥引用，无秘密正文），现有launcher子检查通过67/49/38设置。candidate16原JAR及properties已由有限无网络128MiB辅助容器实际复制到原发行根三个本域路径，逐SHA通过、自然exit0，未挂DB/业务files/密钥。无重打包、无新JVM微基准。正式application-settings编译/完整launcher配置仍须真实完整授权后库存及正常身份/存储回执，不能用准备片段直接冒已运行；准确原入口输入见formal-binding17-resume-inputs.json。优化原生验证不是正式启动前置。

formal-binding17-recovery-mapping.json记录真实本域files子路径/运行根/秘密原引用和已存在Fence类指纹；三应用尚未创建，actualContainerId明确null，不能把预期名称变成实际停写证据。保护前必须实际取得全部三写入者ID/停写、原逻辑文件键/引用/SHA/当前权利/保留与完整维护围栏。

hosts不再尝试，不改ACL、不旁路提权；管理员按formal-dns16-admin-apply.ps1一次执行原三域127.0.0.1追加，前检旧SHA并保留原bytes。未完成仅门禁宿主浏览器DNS；内部网络别名+真实SAN/证书信任/原Issuer的正常TLS/API验证独立，不允许关闭验证或localhost替代。

### V10.54：管理员hosts步骤已实际完成，不再执行脚本

formal-dns16-admin-execution-20261009T042631777Z.json为管理员实际原件，formal-dns18-current-sync.json只读确认当前hosts SHA与其afterSha256一致，三内部域名各自唯一127.0.0.1；宿主DNS待办已解除。上文写权限失败/待管理员执行仅历史记录，不再作为当前门禁，勿重跑原SHA guard脚本或重复申请。DNS成功不等于服务器运行、CA/浏览器信任或业务验收；原674授权、自然锁释放及正常身份初始化/三应用实际启动仍按原接续输入执行，不重建已有候选和配置。
