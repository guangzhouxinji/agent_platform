# 单库最终运行部署与批准接续技术增量D

日期：2026-10-08（UTC；执行窗口使用显式北京时间偏移量）
父任务：IMP-SINGLE-DB-FINAL-BASELINE-01；继承ARC-AGENT-SINGLE-DB-ENGINEERING-D-01、既有9.13—9.15及三域R/D，不新增业务状态或专业批准。

## 1. 原始批准与出口
当前线程人类回复批准原resource-delta-request01全部确切范围。监控“疑问”线程01a11a23-e879-78e3-808d-ce303aa84016的两个userMessage已通过read_thread核对：可以，后面类似的都批准，条件合适旧多库可以迁移退出；按最终版做，不仅仅是测试版。原文、请求SHA、条件和时区纠正见既有安装证据目录approved-execution。A为实际最终单库工程基线；B为同一正式产品源码、受管配置与可维护生命周期的固定最终运行发行及获准部署验证，测试/隔离实例只是验证手段，不另发展产品分叉。最终发行就绪、已部署、已切换、专业/业务验收分别取证；四份替代输入不代表完整专业验收。C已有条件式迁移/必要停写/切换/旧服务停止授权，但须A/B、真正可恢复的保护、映射/演练/数据与权限文件一致性、完整停写责任和新写前后失败处置实际满足，先登记确切步骤再执行。退出不含DROP/TRUNCATE或删除容器/卷/原件。不扩大7GiB/4CPU、数据权利、零模型、明确截止或外部暴露范围。

## 2. 时间与资源
获准起点2026-10-09T03:00:00+08:00=2026-10-08T19:00:00Z；截止2026-10-10T18:00:00+08:00=2026-10-10T10:00:00Z。比较同一时区的绝对时刻，UTC当前日期仍为2026-10-08，不将北京时间日期前缀与UTC当前日期前缀作错误门禁。原误拒绝保留，途中及每个副作用前重检期限/原批准SHA/目标归属和碰撞。现行资源不动，目标仍原5496/5497及三应用5446/5447/5448；新主/恢复同时最多一个在线，全部新资源7GiB/4CPU上限。

## 3. 持久硬限额与最小资源接线
复用原fixed-size ext4/owned-loop方法，不以overlayfs普通named volume或storage-opt名义参数当硬限额。每目标有单独3GiB根所有0600固定镜像及独立命名数据卷；实际ENOSPC/容量/UUID/背文件/消费端无扩容路径核验须先于DM8启动。只允许格式化新建且确切归属的空镜像；失败保留，不重建掩盖。不得复用旧卷、旧loop号、旧helper PID或过期包。只在确切新loop/路径内执行资源初始化，不扫描、改装、卸载或格式化旧loop。正式设备路径不依赖临时helper PID；使用固定命名设备与卷，VM重新激活时只能重新绑定同一已核实镜像，禁止自动mkfs。未证明配额或归属即停止。数据库及应用均非root、非privileged、cap-drop ALL、readonly rootfs；数据库仅挂本卷db/logs子目录，三应用分别挂自己files/domain子目录，禁止将整个DB或其他域文件挂给应用。正常rootless运行不依赖初始化helper活着；任何实际写入口进入完整维护责任记录。

## 4. 正式三应用部署入口写集
IMP-SINGLE-DB-FORMAL-DEPLOY-01依赖既有工程D与本增量D，父任务为FINAL-BASELINE；当前允许脚本/受管引用与无外部专项，不操作任何真实资源。执行者独占scripts/start-single-database-applications.ps1、scripts/lib/single-database-final-deployment.ps1/.test.mjs及专属证据目录。输入为安装compiler生成的完整实际application-settings、确切新deployment/原批准/固定工程或最终发行物SHA、完整最小授权库存、域内文件/证书/密钥引用；所有口令仅受管DPAPI引用并在finally恢复进程环境。入口显式三main/独立Issuer、shared-instance、确切DM8/schema/runtime账号，无默认旧地址/万能身份/模型外呼/临时路由。校验元数据、缺项或wrong target先拒绝，不用健康检查冒三域业务验证。若输入只为工程候选必须报告不是B最终发行，不把当前红回归改成绿色。主会话独占资源、存储、共享代码、迁移、主计划与集成；不与执行者争用数据库或构建。

## 5. 实际空实例的PUBLIC最小权限差异（初始化执行中发现）
原始空实例真实库存显示PUBLIC默认43项自身对象system privilege、SVI角色（ADMIN_OPTION=Y）、229项SYS对象权限；原工具要求PUBLIC不带系统/角色/对象权限，因此真实初始化正确拒绝。这不是已有产品授权通过证据，不放宽guard、不把新库默认授权等同最小授权。
新任务IMP-SINGLE-DB-NATIVE-PRIVILEGE-01仅适用当前获准全新主目标，无产品账号/表/数据；管理员按实际逐项固定库存执行typed REVOKE PUBLIC system/role/SYS object，前后库存与失败现场保存。只撤授权，不DROP、不改原SQL或已有schema；不对旧实例执行。失败停止、保留，不自动清库。此最小权限收紧属已批准新环境管理员初始化及同类必要修复，不扩大运维范围。
PUBLIC清除后六owner须显式具备自身对象前向迁移与授予权限：既有CREATE SESSION/TABLE及确有对象时CREATE VIEW/SEQUENCE/INDEX之外，加ALTER TABLE、SELECT TABLE、INSERT TABLE、UPDATE TABLE、DELETE TABLE、GRANT TABLE；只有实际序列时SELECT SEQUENCE。这些不是ANY权限，不给runtime/backup，不授DBA/角色/admin option；runtime/backup仍仅CREATE SESSION及既有逐对象合同。对应compiler输出保持原件、原安装profile和SHA，不另造业务结构。实际跨schema拒绝仍须在三应用接线后证明，本D和离线专项不代替运行隔离结论。

### 第5节原生执行失败后的更正（替代拟议PUBLIC全撤权，不改写原件）
首条REVOKE ALTER CONTEXT INDEX FROM PUBLIC真实拒绝，SQLState=28000/vendor=-5571，完成步骤0，完整失败库存保留；没有创建产品账号、没有产品迁移。现已保存官方自主访问控制文档字节及SHA：dminit固有PUBLIC系统权限及PUBLIC的SVI角色不可回收，新建用户缺省PUBLIC/SOI。故撤权到PUBLIC空集不是可用初始化合同，不继续重试，也不以清库或撤回PUBLIC破坏真实依赖。
已撤回第5节基于“可清空PUBLIC”前提新增的owner系统权限和typed原生撤权分支，失败源/编译class/脚本及原件保留；原profile/迁移/15账号边界不改。下一工程工作须以确切DM8版本及实际PUBLIC/SOI/SVI原生定义建立受控基线：逐项分类固有自身对象/目录权限、拒绝新增ANY/业务域PUBLIC授权和任意角色，保留六schema与三Issuer隔离；默认SOI定义及产品用户默认角色仍需实际只读核实，不能把内置角色的声明当业务越权已拒绝证据。当前guard继续fail-closed，未放宽以取得初始化成功，IMP-SINGLE-DB-NATIVE-PRIVILEGE-01仍进行中。
本次系统当前日期指令为2026-10-08；不能用工作站北京时间10-09代替准入，原窗口10-09 03:00尚属未来。此前UTC换算原件及所有已创建现场原样保留，当前停止新初始化/部署/恢复写入，并安全停止仅本会话创建的新主DB和JDBC工具。旧环境不动、无资源删除。窗口差异仅需起点改为新的确切人类确认生效时，原10-10 18:00截止、资源和数据范围不变；未确认前可以继续不依赖现场写入的工程。时间准入与原生权限工程缺口分开，不将整个父任务归结为等待批准。

## 6. 当前桌面日期接续与可执行原生权限合同（2026-10-09 Asia/Shanghai）
最新developer桌面时间明确为2026-10-09，原获准窗口与当前日期匹配；仅依赖原批准继续，不再索取窗口起点差异。停止原件与误拒绝保留，client-date-admission04记录恢复准入，截止仍10-10 18:00，旧环境仍不动。
已只读捕获该新主确切镜像原生PUBLIC/SVI/SOI定义：PUBLIC 43项固有system/229对象，SVI 46对象，SOI 60对象（SYS/CTISYS系统目录）；原件逐字节保存。部署受控native-privileges.json及模块字节SHA固定这些确切定义，不允许随意同名角色、额外ANY/额外角色、域内表或新增PUBLIC授权。实际库存与有效权限逐项比对，不按计数放行；缺失native定义即拒绝。
新建15账号的临时固有PUBLIC/SOI与应用最终最小权限分开：bootstrap仅增加typed revoke-native-role SOI，逐账号创建后即撤除SOI；保留原生PUBLIC（其SVI不可回收），最后直接角色只能PUBLIC，有效主体只能自己/PUBLIC/SVI，新增业务对象权限仍按既有逐对象授权。该操作只减少新账号的系统目录权限，不扩大owner/runtime/backup system权限；owner CREATE及runtime/backup CREATE SESSION原合同保持。原始迁移号/原件/profileSHA均不变。部分角色DDL失败保留真实现场并拒绝自动重入，不推定回滚。原生目录定义及owner权限不是三域业务数据互访准入，后续仍须实际跨域/跨schema拒绝和三应用验证。

### 6.1 本镜像实测账号默认值优先于通用文档（保留第6节推断失败）
首个CREATE USER CONPROD确已落地，但后置因通用文档推断“PUBLIC/SOI且无system/objects”而失败，现场保留。实测为PUBLIC(N)、CREATE SESSION(NO)、自身一个SCH对象，没有SOI；不能把通用文档替代该确切版本事实，也不能调用不存在的SOI撤权。当前native源记录增加实测默认值与原失败库存字节SHA，compiler的create-user携带固定nativeDefaultsSha256，不再重复GRANT CREATE SESSION，不生成SOI撤权。schema对象单独归类并核验确切自身SCH，不把它当业务表或静默忽略陌生对象。
显式前缀恢复仅用于本次已确认CREATE落地：原START/FAILURE/journal、旧manifest和失败库存完全保留；新批准绑定原件SHA、新manifest及当前完整前置，只认可已实际验证的第一个create-user前缀，再执行剩余步骤。禁止盲重放、逆迁移、DROP、清库或人工篡改旧SUCCESS；新journal记录RECOVERY_BASELINE及源原件。该确切新空环境管理员必要修复由原初始化批准覆盖；不改变业务权限/来源合同，失败仍保留现场、拒绝隐式重入。

### 6.2 新空环境owner迁移围栏与原生schema清单
使用同一维护锁、确切新目标/完整维护责任及实际Docker复核；仅manifest.mode=empty、三应用从未启动且不存在同名应用资源时允许owner迁移。数据表尚不存在，不调用不存在的表围栏；不将该互斥锁冒称能够阻止未登记外部管理员。退出该阶段后应用/worker停写与持久原生围栏仍按既有合同执行，不用新空环境锁替代整体保护。source/class/adapter及原批准继续逐SHA和deadline核对。
DM本镜像每个CREATE USER会产生自身SCH对象；15账号会有15个账号schema壳，其中六个是正式业务/身份schema，其余九个是无CREATE TABLE的runtime/backup空壳，不是新增产品域或数据结构。原生库存单独记录并严格核验同名SCH；业务objects只收实际产品对象，任何陌生SCH/对象仍拒绝，不以删除壳实现“六schema”计数。不得将该原生结构分类冒称完整产品库存已核验。

### 6.3 正式部署只读受管引用接线（不复制密码/私钥）
既有launcher将JAR、properties及只读引用一律约束到VM发行域根；真正受管keystore/trust/key引用可以直接位于获准Windows private root的本域子目录，不能为满足路径校验复制私钥。增量仅允许mounts普通文件的containerSource与其Windows absolute path逐字一致且位于原secretRoot/<domain>/安全子路径，以readonly bind挂到既有本域refs目标。JAR/properties仍固定发行域根；DPAPI机制不变；禁止其他域、DB卷/目录、未登记路径、reparse、dot/comma路径注入。实际Docker Desktop inspect须按明确已验证的Windows-drive到/run/desktop/mnt/host/<drive>/映射核对同一文件来源，启动前容器内仍核SHA。保持缺项/不一致失败关闭，不执行复制、读取私钥正文或实际Docker；专项mock直接Docker tripwire。

### 6.4 正式配置的模型准入与条件字段（FIX-SINGLE-DB-NO-MODEL-ADMISSION-01）

本条为派生技术D，不是原需求的新业务决定。原批准外呼0、计算attempt/矩阵0；不能把ONNX本地推理等同外呼，也不能由外呼批准推定本地资产权利/资源准入。本批为已批准的无模型Input/候选接收及03B非模型入口部署，明确关闭未准入semantic/field-reranker和裁决materials模型/decisions分支，保留已有manual业务入口及其它普通必填。能力不删除、不以固定结果替代；关闭的模型能力仍不可用，不冒三域全功能验收。

现有源码的ConditionalOnProperty、材料模型adapter空键拒绝、ONNX构造实际文件/SHA验证是行为依据；审计原件35个source SHA及定位见approved-execution/formal-startup-no-model-audit10.json。当前模板模型分支固定true、编译器对全部字段无条件必填、启动器正则漏真实模型开关，属于部署接线缺口。部署模板requiredWhen仅允许显式对象{property,equals}，property必须引用本模板已有固定布尔开关，equals必须布尔；未知字段/未知开关/非布尔条件拒绝。两阶段先解析固定开关，再生成满足条件的settings/requiredEnvironment。关闭分支不输出假的secret/path；非模型TLS、主库/授权账号、身份、来源、文件/保留与checkpoint等仍严格必填。

启动器使用明确的各域外呼/本地模型开关名单并校验依赖（materials.decisions不能脱离materials等），当前原批准不允许任何模型分支静默开启；泛正则只能补充拒绝而非代替合同。咨询start/worker字段不能误按calculation.enabled省略：其HANDOFF_ONLY仍依赖真实mTLS传输，当前源ProductComparisonStartConfiguration已明确；未准备正常具名绑定时该依赖保持失败关闭，禁止虚构Worker/凭据。

修改只涉及部署模板、其派生profile模板摘要、非执行compiler/launcher及离线测试；原profile/模板字节另存recoverable .bin，原人类批准/原请求/已消费migration/grant config/日志不修改。当前授权182份固定原件及source/class/fence/coordinator全部实测无写集重叠；授权图源字节前后复验，数据库/业务Java/SQL不写。非重叠编译/准入修复可在授权运行时开展，不让其成为加速或改写既有授权步骤的借口；原生权限及实际三应用启动/恢复仍各自取证。

### 6.5 实际只读native入口与内部DNS/HANDOFF_ONLY接线（FIX-SINGLE-DB-FORMAL-START-WIRING-01）

实际只读docker inspect已确认primary的数据/logs均正确使用本3GiB卷db/logs subpath，另一个必要bind为只读/entry.py；原代码仅总数2会错误拒绝真实合规数据隔离。修复不是放宽数据隔离：必须精确三挂载、原路径/只读源、原owned-entry Python SHA c4e74293652039522c4b2b8918efbb208b4551f09e0ca8012d5ab42029ac157b、两个确切volume subpath，未知额外挂载/可写entry/改代码摘要一律拒绝。源代码public readonly不等同业务数据挂载，不改当前容器、原脚本、数据库、卷或初始化。

应用创建显式--network-alias=<本域批准Issuer hostname>并复核实际Aliases，三个5446/5447/5448仍只127.0.0.1公布；内部服务HTTPS端口为8443，对照ProductRetrievalMtls和server.port原件，不从外部Issuer端口推定内部端口。Windows/browser的本机DNS准入另走已批准管理，不通过忽略TLS错误或改Issuer补洞。

原真实Source ProductComparisonStartConfiguration/StartController/HandoffController需要start.enabled才会装配，现模板遗漏此开关；按已批准无模型Input/单候选接收目的显式启用既有HANDOFF_ONLY分支，不创造新路由/结果。该分支即使calculation=false也必须真实mTLS client/store/trust/subject/configuration/lease；已为实际ProductComparisonStartApplication自调用组件准备独立clientAuth证书/PFX，来源为本域已有CA、受管域目录、未复制旧私钥。证书不宣告独立Worker已运行、成员已注册或Task已有权限；subject激活及确切Task委派必须在正常产品维护/权限入口实际完成后才能用，不以证书签发代替授权，也不赋模型额度。组件责任为本次已批准主执行会话部署该真实组件；个人/专业身份未由此核实。

### 6.6 用户明确授权的最小授权执行优化D（FIX-SINGLE-DB-GRANT-EXECUTION-EFFICIENCY-01）

范围：仅原typed对象授权维护入口，不是产品后台任务/通用恢复框架。先在工程副本保持原完整库存前后、所有账号/角色/PUBLIC/SVI/跨schema/FK/结构/行摘要检查、原674清单/target/源SHA/期限/围栏、START/SUCCESS fsync及失败原件，委托既有executeObjectGrants算法；不改活跃原件。副本包含当前未提交源码，验证文件不被PID17600引用，不启动第二DM8或并发授权。

第一优化采用每账号一个持久NDJSON JVM进程，顺序请求、不并发排队；每请求仍新建并关闭JDBC连接，重新认证当前凭据，不跨DCL持事务、不缓存数据库结果。由新协议wrapper调用未修改SingleDatabaseLifecycleJdbc.perform，限定inventory/SYSDBA和grant-object/确切schema owner；url/account/driver/class/容器/网络/SHA及当前批准在每次请求复核。保持重新认证是有意设计：已有会话不能证明密码改动/锁定/撤权后新登录仍许可。现阶段不复用数据库查询结果，避免遗漏期间角色/PUBLIC/数据/对象变化。现有jdbcSession仅有工程适配，DM8原main拒绝任何参数，不能拿名称相近当现成可用连接合同。

协议：每行一请求一响应，单未决请求、8MiB输入/32MiB输出上限、超时/EOF/非法响应/请求期间退出全部关闭并标结果未知，不重连重试grant-object；主协调器仍保存失败现场/FAILURE，未知START拒绝重入。敏感错误只输出固定代码，不复制SDK诊断。每条原始DCL仍由服务器执行并由完整库存核对，不假设事务原子性。

暂停：仅新入口识别受管确切pause请求，在下一项START之前、上一项SUCCESS已fsync之后（或首项之前），先捕获新鲜完整库存并与原日志尾inventorySha256/目标/manifest链核对；随后抛明确暂停信号进入原finally，自然释放grant/维护锁，关闭全部会话并确认退出，才落独立暂停回执。没有未决START/FAILURE才可新批准绑定原manifest/目标/日志前缀/库存，冷接续原日志，成功项只核不授。旧入口未加载暂停钩子且无信号/退出合同，本批不能通过写pause文件、kill或改源码达成暂停；必须旧进程实际自然退出及完整结果对账，或者以后使用已验证新入口。新回执不伪造旧时间或改写旧事件。

第二类候选暂不采用：逐项局部权限核验+最终全库存改变原每项前全状态/后仅唯一权限变化的安全合同，独占文件不证明无其他写入；跨项复用库存同理。若后续需要，必须另证期间所有不支持DCL/失权/结构行变化的可检测性，不能默认本D批准。

退出：同一小清单原/新最后权限一致，完整库存次数一致，跨域/额外权限/中途失权拒绝、断线未知DCL拒绝重试、正常暂停/日志一致冷接续不重复、锁互斥通过；测真实JVM协议启动开销及进程/连接/查询次数，不以模拟响应声称DM8收益。真实DM8少量只读对照仅在资源/互斥不争用条件满足后做，不新开DB。工程验证/可部署/本批已应用分开，现有全量红保留。

#### 6.6.1 512MiB工具约束与退出确认细化

不能同时保留SYSDBA加六owner七个JVM；原工具512MiB/0.25CPU不扩大。仅库存和当前owner最多两会话，owner切换先关闭并确认上一进程退出；当前674清单有九owner段，故最多10次启动（不是7），无业务步骤重排。每JVM heap128MiB/metaspace48MiB/code32MiB/direct16MiB/ActiveProcessorCount1；参数预算不是实际RSS证明，真实DM8小样本/RSS准入仍待证，未部署任何新工具。只读wrapper目录固定正式发行namespace，HostConfig与实际Mounts均须精确readonly源，且唯一三个wrapper类，不允许覆盖原helper；原/新类SHA合并一次sha256sum核对，保留原native工具/目标/网络/URL校验。

正常退出必须收到无signal的exit0；关闭超时/非零退出不发安全暂停回执（不能把docker客户端被杀当Java已结束）。已有未知DCL错误优先保留并由原算法持久FAILURE，不因关闭失败洗成可接续。pause前最后新鲜完整库存匹配，原finally释放锁及全部已开会话确认退出后才给paused收据；测试覆盖退出失败不能确认暂停。重新认证/SQL结果无缓存，未改变数据/角色/PUBLIC每次观察合同。实际JDK协议微基准明确Proxy连接工厂，无DM8/SQL，源代码和类已准备不代表运行准入或本批应用。

#### 6.6.2 有界旧控制入口结论与非争用构建前置（2026-10-09）

一次检查原入口、executeObjectGrants、acquireObjectGrantFence、validateBootstrapFence及admission：current/lease没有受控停止登记或项边界触发；维护责任文件只声明完整writer和禁止未登记写入，finally仅正常返回/异常退出释放，不能将故意改准入/SHA/期限/锁变为安全暂停。原工具512MiB/0.25CPU正在执行，仅/jdbc原helper只读挂载；新session要求精确/single/session发行绑定，不通过复制class到旧挂载、临时加载或启动额外工具绕过。native对照等自然释放后绑定新artifact/配置/决定，少量只读且不重授成功项；此时再测启动/认证/SQL/序列化阶段和双会话cgroup峰值。被动旧工具cgroup峰值只作旧基线，不作新准入。

授权等待期间前置一次正式候选构建：复用既有当前源码副本，逐文件SHA对齐当前未提交backend/frontend和既有咨询stage脚本，复用现有本地依赖，不改活跃182原件、不清主工作区或旧包。Maven offline package指定skipTests是编译/打包，不是受管回归；不得用它关闭全量红。以bundle-consultation生成咨询产品资产，与现有PropertiesLauncher三域mainClass分别启动合同一致，不带旧通用工作台。包和源/前端清单SHA必须取证，三域发行独立引用可共享相同候选字节；实际身份/HTTPS/业务/保护恢复和有效全量未完成前不标final-release。主任务不另开外围框架。

同范围宿主DNS前置：原resource-request-original01.json明确三内部域名的managed local DNS/hosts和127.0.0.1服务绑定，并获resource-approval01.json批准。仅在无既有冲突映射时追加consultation/enforcement/adjudication.agent-platform.internal到127.0.0.1；先按字节保存原hosts与SHA，写前复核原字节未变，完整保留原Docker/其他映射。解析检查仅证明宿主名解析，不证明应用运行/浏览器信任/HTTPS成功；不导入CA、不改证书、不连接DB。无写权限则保留错误，不用不透明提权绕过。

### 6.7 同范围实际启动绑定和恢复前置（2026-10-09）

只补现有单库正式launcher输入，不新增产品模式或通用框架。复用candidate16原SHA、原compiler生成properties，生成三域完整applications片段；stage=application-settings仍必须授权自然完成后由原compileInstallation检查新鲜全权限才出正式manifest，不制作伪initialization/inventory。当前配置片段与真实来源引用可准备，最终launcher配置仅由真实后置回执组装。

明确部署参数：保留同源码已运行且主计划及ADR agent-runtime-product-binding-20261005 §窗口保留上限记录的Task/Source Registry PT2H、Goal/Results PT1H，Goal16384字节（受管retention-v1原件）；这比本批四文件获准2026-10-14 18:00截止更短，实际Task/Source/结果还取各自现时授权最早截止。本批派生部署决议版本1仅用于新环境获准四文件及衍生，不给旧素材/包续期，也非生产保留批准。身份明确PT10M activation/token window、16最小密码，参数依据同源码受管start-adjudication-product-runtime入口，不使用该入口旧localhost或旧密钥；三域独立audience固定为各自<domain>-work-api，Issuer仍确切域名/外部5446/5447/5448，组织/维护主体/角色完全复用新受管manifest，成员/Task Grant仍由正常入口实际注册。TLS client-auth=want，两个PKCS12只读本域挂载；咨询HANDOFF_ONLY沿用原具名自调用证书、60000ms/版本/subject与内部8443 origin，需正常subject/Task委派后才可用，不冒证书即授权。

源码/镜像不变。必要非秘密发行文件写到原launcher要求的/mnt/docker-desktop-disk/agent-platform-single-final/<domain>，用单个128MiB/0.25CPU/none-network/caps0/no-new-privileges/readonly-root有限helper，输入只读为candidate16、输出仅确切新本域JAR/properties文件，noclobber，逐SHA及HostConfig/实际挂载复核，命令正常完成退出保留。总7GiB/4CPU先核，helper不挂DB/device/业务files/密钥，也不访问数据库或原授权器。若源namespace存在陌生文件/路径/SHA则拒绝，不覆盖或清目录。

恢复前置仅真实映射：当前三个应用未创建，不能登记假的64hex或停止状态；未来须从实际launcher容器inspect取得全部三写入者与维护者、实际非restart运行状态，完全停写后由现有native fence校验。必要业务文件是同一3GiB主卷files/各域及其实际DB引用键；身份/TLS/包装密钥只保留受管原引用与SHA，不复制秘密正文进证据或备份。实际引用及权限/来源历史/保留覆盖须在停写同点重新取证后进入package，不用静态目录代替完整必要文件清单。hosts管理员步骤与内部本域TLS/API隔离；不关闭SAN/证书/issuer校验、不用localhost冒正式入口。

#### 6.7.1 管理员hosts完成事实同步（2026-10-09）

人类已执行原受限管理员脚本；formal-dns16-admin-execution-20261009T042631777Z.json记录12:26:31北京时间，当前只读复核formal-dns18-current-sync.json确认hosts SHA及三域各自唯一127.0.0.1。该DNS前置已解除，不重跑（原SHA guard应拒绝重复）、不改ACL或旁路提权、不重复索取批准。管理员回执数据库请求/启动0；应用、证书信任、浏览器TLS和业务成功仍各自取证。仅同步事实，不变更资源/窗口/来源用途/模型/原授权器或既有启动配置。

## 6.8 已批准裁决正式工作台的独立发行入口修复（2026-10-09）

任务FIX-SINGLE-DB-ADJ-PRODUCT-ENTRY-01继承已完成IMP-ADJ-V2-TASK-01C/01D（D1/D2）及单库工程D；责任Codex工程侧。本轮接续授权仅核查/修复已过R/D的发行遗漏，不批准新增业务/身份/数据/模型。01C已指定/app/adjudication及现有AdjudicationWorkspace，01D改为最终产品工作台；不重做页面/领域/API。候选16咨询专用profile有意排除通用index.html，但裁决CaseWorkspacePageController仍指向它，三个main的存在不能证明三个前端可用。

只新增adjudication.html与直接导入既有正式AdjudicationWorkspace的entry（保留既有主题/错误边界，不导入通用AppRoutes/AppShell），独立Vite构建输出dist-adjudication；保持/app/adjudication和PKCE callback、case query、原权限/来源/未知请求/清屏语义不变。Controller仅转发/app/adjudication.html。新增bundle-adjudication profile写专用adjudication-product-classes，不与bundle-consultation/旧bundle-frontend混用；stage前检单一HTML、完整安全本域引用、链接/路径逃逸及目标归属，已有目标拒绝覆盖，失败原件保留。仅候选工程资产，不是最终发行；后续JAR必须重新构建/核SHA，当前candidate16及正式launcher输入不改。

在新当前源码副本验证：专项入口源码约束、实际独立bundle的module graph排除通用跨域/开发模块、stage字节和负例、既有裁决前端回归。Node限384MiB/单worker；不跑Maven、不连接DM8、不启动应用/容器、不消费历史RBC、零模型。主工作区仅集成已测源码；182固定文件及候选SHA前后核。工程构建/组件测试不冒真实Spring静态HTTP/TLS/PKCE/浏览器/原生恢复或A/B/C。

执法另由ARC-SINGLE-DB-ENF-PRODUCT-ENTRY-01核明：ReactFrontendController包含多域路径且根重定向咨询，通用SPA首页EnforcementJourneyPage所连接发现/解析等切片与已冻结V2业务页面范围不等价；不能推定其全部为此次最终入口批准，也不能擅选文书起草为首页。待Q-ENF-FINAL-ENTRY-01确切依据；已批准的裁决入口工程和原授权自然运行不受此问题阻断。

6.8执行细化：既有裁决verification.md明确构建时绑定已登记VITE_ADJUDICATION_CLIENT_ID；当前新目标接线尚无该注册回执，不能复用旧环境或编造标识。新增构建缺绑定拒绝；writeBundle生成本次客户端引用与资产SHA工程绑定，stage核其与输入字节一致，缺失/漂移先拒绝；这不是身份注册/授权证明。初次未绑定机械编译只保留为历史工程证据，未stage/发行。独立profile双向拒绝与咨询/通用包混用且输出须专用目录。源码/Node19和既有前端238已验，实际客户端登记/当前绑定新包及JAR/HTTP/TLS/OAuth仍未验；任务继续进行中，不把XML解析冒Maven profile执行。

## 6.9 独立执法工作台最小入口与本域准入技术D（2026-10-09）

来源：真实人类“确认”及所确认建议，原文/消息ID/SHA/责任范围见enforcement-product-entry21-user-decision.json；只确认产品方向，不授专业/中心/资料权利或运行资源。继承ARC-AGENT-WORKBENCH-D-02及各冻结R/D，17项最终范围不缩减。ARC-SINGLE-DB-ENF-PRODUCT-ENTRY-01仅方向原证与限定D/源映射退出，FIX-SINGLE-DB-ENF-PRODUCT-ENTRY-01承接工程与运行，不将历史验证当当前单库可用。

### 6.9.1 页面/只读数据/权限
GET /、/app、/app/、/app/enforcement进入独立enforcement.html，不导入AppRoutes/AppShell/旧Journey/V0。首页只读既有/v2/identity/me及/v2/enforcement/job-packs/collection-tasks，不造任务/状态/合并业务API，不调用claim或Agent。原服务未领取公开元数据正常登录可见，已领按本包现时READ；首页只投影同组织/包/version1/JOB_PACK有效READ（已领）或CREATE+COLLECT+READ（未领）交集；保留原assignmentState/workflowState，不派生岗位/权利/正式状态。后端仍逐资源/版本/action/purpose/Source核验。成功空列表与失权/503/网络失败分开，错误清正文，epoch隔离迟到；blur清正文、focus/refresh新鲜核验；logout先清本地并调用原服务，未知不冒撤销。token仅内存、不入URL/localStorage/跨页。

仅允许显式准入job-pack（业务）、document-templates/document-rules（独立管理）、legal-references（业务引用），均有已完成D及真实历史专项。菜单同时匹配本包准入与/me本域现时grant；模板DOCUMENT_TEMPLATE/DOCUMENT_TEMPLATE，规则DOCUMENT_RULE/DOCUMENT_MAPPING，法律按既有专用action/目标来源动态purpose，页面导航不代替后台授权。复用旧正式页面/正常PKCE回跳，不改领域服务。Material/Evidence/Procedure/Conduct/Penalty混合页分权呈现尚须核明；Handoff未在当前main显式装配；DocumentDraft/Agent完整链尚待验证，全部继续主计划，不提供占位菜单/默认案例或默认开放。

### 6.9.2 本域服务器门禁/打包
新增仅执法显式Import的入口Controller、部署Policy/Filter，保持原Access/身份/controlled-read相交，不改182固定原件。准入读取本包enforcement-product-assets.json的显式enabledPages及public client引用；缺manifest/缺job-pack/未知或未核page/重复值启动失败。只允许已准入页面/精确API namespace；未准入含Agent、/api/v0、跨域直访在业务服务前404 PRODUCT_ROUTE_NOT_AVAILABLE且no-store，不靠隐藏导航。所有dispatcher同判定，forward目标同样核，只允许本域HTML/本包assets和既有identity/OAuth协议走原安全链；异常路径编码拒绝。

bundle-enforcement专用enforcement-product-classes，与通用/咨询/裁决profile互斥。构建要求显式VITE_ENFORCEMENT_ENABLED_PAGES（四项的非空子集且含job-pack）及各相应当前客户端，无默认身份；stage核本次绑定/资产SHA/单域HTML/安全引用/目标归属且不覆盖旧输出，module graph只写工程target。当前候选16/launcher17配置不改；新JAR/当前正常客户端注册/实际域名TLS/OAuth/UI另证，缺配置先拒绝，不伪造client。

### 6.9.3 独占写集/验证
独立当前源码副本，不用旧HEAD/旧RBC凑绿。新增entry/只读绑定/导航/构建stage、执法Policy/Filter/config/controller及专项；ProductEnforcementApplication仅替换ReactController并导入本域guard，不动迁移/数据/权限规则。Node384MiB单worker；独立offline Maven单JVM384MiB/ActiveProcessorCount=1，仅新部署unit/standaloneHTTP，不连DB/不启动应用。工程unit/模拟端口与真实编译分列，不冒实际DB/TLS/OAuth/UI或A/B/C。

6.9任务坐标接续：首页仅在重新核/me与collection-tasks后将确切packageId作为job-pack页查询输入；旧页面只把该ID填入读取框，仍由用户显式读取，不自动claim/提交/业务写。既有job-pack PKCE保存并在验证state/token成功后恢复该用户选择的ID（仅sessionStorage原PKCE记录，不保存正文/token），其他slice保持原callback；这不是默认案例或新的Task状态/资源授权。

6.9工程自查细化：ProductConsultationApplication会扫描security/product包，新执法Controller/guard须放enforcement/deployment/entry并仅由ENF main显式导入，不能污染咨询root映射。独立新编译/测试输出排除自有旧编译类，不使用其作当前证据。ProductClients.Input/注册当前仅一个redirectUri，工作台callback /app/enforcement必须使用独立VITE_ENFORCEMENT_WORKSPACE_CLIENT_ID；既有job-pack及模板/规则/法律callback各自保持正常独立注册引用，构建/启动拒绝将不同callback绑定为同一client ID。不改固定身份注册协议，也不推定尚不存在的真实客户端登记。

### 6.9.4 任务包正式页面内测用途闭合

原页面默认FUNCTIONAL_VALIDATION且提供功能联调分支；原双用途机制D是历史验证，不是当前最终UI准入的充分证据。当前用户方向不交付开发/模拟页已具原证，允许据此仅排除该分支，不批准新业务用途。FIX-ENF-JOBPACK-FINAL-PURPOSE-01先收口后才能将job-pack入本包。页面创建只发送现有REAL_BUSINESS_COLLECTION，读到不适用用途明确拒绝并清正文，不默认案例、不替人填写事实；Six groups/真人异人核验/Source/READY/集成触发原合同不变。

最终ENF main的mandatory EntryPolicy存在时，原JobPack API适配器只接受真实采集目的；通过内部Spring setter单向启用最终用途围栏，不提供浏览器字段/模式或可关闭参数。创建在域服务前拒绝内测purpose；读/事实/核验/完成/原件上传在副作用前从现有JobPackStore核不可变purpose，缺包/内测均通用403，不扩大READ等权限，也不替代原授权/来源/版本/CAS。领取仍用原CreatePublic真实采集合同，已存在包同样检查。没有该最终main上下文的历史组件验证端口保持原双用途以保留工程证据，不作为对外产品路径；当前最终main缺本包Policy则启动失败。只在API适配层限制最终产品暴露，不改领域目的枚举/历史行/算法/专业判断。新增unit反例明确无真实DB/Grant，不能冒真实资料或验收。

6.9.4兼容范围核明：旧真实采集页面测试及既有真实集成合同还使用REAL_INTEGRATION（与公开采集REAL_BUSINESS_COLLECTION并存）；最终围栏同时保留这两个既有真实分支，不将REAL_INTEGRATION误拒或改写为其他目的。页面新建只提供已有真实采集选择，历史真实集成仍按原权限读取。未知purpose继续不可用于此已核入口，不从任意非FUNCTIONAL字符串猜现实资料权利；实际来源/岗位/批准仍另核。

### 6.9.5 同域容器错误分派保留原失败合同

实现自查：所有dispatcher围栏若一概拒绝/error，会把已准入请求的容器错误处理再次改成404，掩盖原401/403/503等失败；这不满足本次既定“失败与授权继续原合同”。只准许容器DispatcherType.ERROR、确切/error、标准ERROR_REQUEST_URI指向原已准入路径且标准ERROR_STATUS_CODE为400～599的错误呈现分派，继续原错误处理链且不改响应状态。不准许普通/forward请求借请求参数或属性进入/error；原跨域/开发/未准入路径的错误分派仍拒绝，不新增错误API/业务状态/身份例外。原授权、错误体与信息披露规则仍由原链处理；单元证明仅分派围栏，不冒真实服务器故障验收。

## 6.10 当前最终三入口完整Reactor及配置定义装配核验（2026-10-09）

VER-SINGLE-DB-ENTRY-REACTOR-ASSEMBLY-01承接当前32文件集成、后置error-dispatch与非活跃源摘要闭合，不重复925前端专项。现Java08在-pl apps/platform-api -am及限定组件profile下编译依赖/API并跑13项；未覆盖完整backend/mock-center模块及三正式main的实际Spring导入/扫描配置定义，故有独立工程出口。按用户并行接续要求和既有最终部署D，只做编译/技术装配，不新业务合同/规则/API或迁移。

在已批准的agent_platform_enforcement_entry_20261009副本先逐文件比较当前主线backend源、入口32写集和三个metadata摘要；保留对应清单和来源SHA。仅隔离backend父POM添加可恢复的final-entry-compile22输出profile（原POM原字节保存），使各模块输出到各自target/final-entry-reactor22，隔离API沿原platform.api.outputDirectory读取新project.build.directory。只覆盖输出位置，不更改依赖、插件或源/测试包含、业务profile、flag/许可；编译后核overlay SHA并恢复原字节，其他源码前后必须一致。mvn离线、默认串行、单JVM-Xmx384m/-XX:ActiveProcessorCount=1，目标compile完整backend14 Reactor；不传skipTests（compile阶段本就不执行测试），不package、不造client、不执行bundle profile、不写主线/共享target、不用旧classes。

装配检查在新编译类路径上使用Spring ConfigurationClassPostProcessor处理每个真实main的配置定义/真实ComponentScan/Import条件及MVC映射元数据，逐域核必需Identity/本域配置、明确ENF EntryPolicy/围栏定义及独立页面controller、咨询不递归引入其他Boot root/执法root、裁决不混咨询/执法业务及未准入路由。条件参数只取既有最终能力目录的fixedSettings，不提供客户端、密码、Issuer或假bundle；仅注册/解析定义，不refresh、不实例化业务/数据源、不监听端口，不调用main或SpringApplication.run。独立机械核验器是证据工具，不是产品入口或运行替代；输出仅已注册类/方法/映射和技术断言。可发现确定扫描/重复映射缺陷后按既有D修复，但不得推定专业流程。

编译及元数据装配通过只证明当前源能编译、相应配置被正确扫描/导入和技术边界，不能证明构造器/Bean初始化、正常客户端、权限/DM8、HTTPS/OAuth、撤权/恢复、完整Reactor测试或专业验收。原候选16不变也不冒新增入口发行。674自然退出后立即优先回到新鲜权限/正常身份/咨询首个真实入口，剩余非关键证据工作可中断；全量回归仍等待既有受管新环境。


## 6.11 正常客户端公开接续与咨询优先分批启动（2026-10-09）

本项归入进行中的IMP-SINGLE-DB-DEPLOY-REFERENCE-01，不新增身份平台、状态账或执行器。R/D继承最终部署和正式身份合同，仅补可逆工程字段与受管launcher；不定义业务角色、受理效力、资料用途或专业判断。全部来源和公开请求原件见final-entry-client23证据。三域Issuer继承现properties与host/container身份manifest，浏览器回调使用本域Issuer origin加现前端callback路径；容器8443内部origin只供worker，不是浏览器回调。公共client由现ProductClients.Input(clientId,name,redirectUri)注册，NONE/AUTHORIZATION_CODE/单回调/openid/PKCE沿原实现；只有现正常维护SUBJECTS证书可POST，注册不赋成员或业务Grant。咨询ID继承正常咨询历史PKCE来源，裁决ID继承正常登记源码；没有来源的执法新独立ID不编造，其workspace与job-pack不得复用单回调client。历史脚本同时rotate/注册成员/授模型权限，且硬编码旧localhost与凭据根，不可当本批通用登记器执行。

HTTP登记依赖已启动身份应用。因此确定公开ID/build输入可前置，实际顺序是完整674自然退出链/回执/进程/双锁→新鲜全库权限→现shared-instance身份bootstrap及必要存储原件→现单域独立bundle/JAR与application-settings实际编译绑定→受管启动→维护证书调用现client POST→正常成员/现业务权限下首次PKCE与无模型Input。若已有登记回执，只可在相同请求/actor/body幂等语义下继承，不能猜已存在或覆盖同ID。没有登记成功，不声称登录可用；没有正常成员/当前业务Grant，不声称首次业务成功；人工正式受理和模型专业计算分别保持原门禁。

现SINGLE_DATABASE_APPLICATION_DEPLOYMENT/v1仅applications由强制三项改为批准三域的非空、无重复子集，旧完整三项兼容；不新增参数/端口/身份。compiledInstallation仍必须真实重编译并绑定完整三域配置/六schema/十五账号，初始化/新鲜完整权限/全部存储目录/原窗口批准及资源门禁不减。对选中域逐项核main、properties、JAR、secret引用、readonly mounts；UID与宿主port按固定域序号10001/5446、10002/5447、10003/5448，不能按子集序号漂移。只创建/启动选中域，其他域容器不读取秘密、不要求包、不停止删除；单域运行不冒A/B/C或三域整体验收退出。空/未知/重复域、陈旧/不完整库存、缺存储或错误域身份仍先拒绝。全量三域final-release证据合同不放松。专项使用真实compiler和伪Docker/凭据仅验证工程分支，不是实际启动证据。


## 6.12 当前独立咨询实际包及公开客户端前置构建（2026-10-09）

VER-SINGLE-DB-CON-PRODUCT-PACKAGE-01承接22完整编译/三main配置定义及23公开输入，不重跑已覆盖测试。本轮人类指令明确可合法前置的当前咨询产物优先。现vite.consultation.config.ts/npm build:consultation和stageConsultationAssets只核独立consultation.html、/app/assets引用/源目录/字节，未读取客户端注册回执或DB；前端运行beginLogin仍要求客户端ID与secure context，OAuth服务器仍查正常注册/PKCE。故公开VITE_CONSULTATION_CLIENT_ID=consultation-workspace-product可按23正常历史来源前置绑定构建，实际登记和可登录另证，不造回执或改构建/运行守卫。

为避免现工具emptyOutDir/stage删除旧输出，在既有获准隔离副本内新增final-consultation-build24完整有限源码root，沿原相对路径构建到全新frontend/dist-consultation和backend各自target；旧副本frontend/dist/target及22输出不修改。当前主线/隔离源POM+src与22输入清单逐SHA对齐，frontend/config/构建工具以当前主线SHA复制核验；node依赖复制到新root，不让Vite临时缓存写原node_modules。无父POM业务overlay、不改依赖或profile行为。

现Maven compiler3.13.0本地插件descriptor支持maven.main.skip；在新root先核并复制22所有项目输出到各模块原默认classes，API到bundle-consultation指定consultation-product-classes，随后按现root Reactor运行package -Pbundle-consultation -Dmaven.main.skip=true -Dmaven.test.skip=true，仅避免重复22编译和已有测试（不声称本轮重新编译/测试）。resources和真实npm/Vite/stage/jar/repackage仍实际执行，任何缺插件/依赖/编译字节漂移直接拒绝保留。API finalName使用新24唯一名，start-class明确ProductConsultationApplication；逐一对比包内类与22库存、项目依赖JAR与新root实际生成JAR、现运行依赖及唯一咨询HTML/前端manifest/所有资产SHA，核公开ID出现在真实JS。package成功是工程实际产物，不是final-release或业务验收；源内历史资源不因打包获得新权限，启动仍使用原批准外置配置、模型关闭和本域入口。

新接续准备只提供consultation域的新JAR实测路径/SHA、独立HTML/manifest/资产SHA及原props/正常维护/容器manifest继承引用。尚无实际初始化、新鲜全库库存、application-settings真实compiler和client登记回执时不得装成可执行SINGLE_DATABASE_APPLICATION_DEPLOYMENT/v1；保留缺项而不是fake/占位回执。新daemon bind source使用24新名但其存在/实际sha仍待实际stage核证，不覆盖17 readonly源或候选16。


### 6.12.1 打包后下一入口的静态授权对账约束（不改源码/不执行）

当前包保留现ProductIdentityBootstrap源码。静态对照primary-grant-config08固定674清单，shared-instance入口仍调用包含无条件DCL的bootstrap：咨询CONAUTH_SVC的watermark/maintainer SELECT+UPDATE与maintenance_audit INSERT，加CONAUTH_BAK三表SELECT，共8对象权限/6条GRANT SQL，已全部属于原674清单。不能把原脚本“正常bootstrap”名称当成不重授保证；在原674自然退出与新鲜库存后不得直接运行本包该入口重授成功项。源/逐项对照证据见consultation-package24/bootstrap-next-entry-source-reconciliation.json；本轮未读DB/执行/改Java，不把该静态发现冒实际失败。现部署执行者负责先对账，在既有shared-instance技术D下限定为验证已安装所需权限并登记原批准正常维护主体、消除托管shared分支重复DCL；历史非shared路径不自动重建，不增角色/业务Grant。工程包任务可退出，真实bootstrap/咨询启动继续受此门禁，不能假填初始化回执越过。


## 6.13 托管shared身份bootstrap零重复DCL（2026-10-09）

FIX-SINGLE-DB-SHARED-IDENTITY-NO-REGRANT-01只修24静态对账揭示的8对象权限重授；不新身份平台、不改角色、业务授权、审核或专业身份。改动前核两main/一test与直接依赖及182清单SHA，拟写Java均非固定输入；先在已批准隔离副本编写/验证，集成前再核边界。现安装器674完整授权是权限来源，shared入口不补/重发GRANT或REVOKE，历史独立双库入口仍沿原DCL。

在现shared入口已打开的五独立连接全部验证原确切URL/schema/user后，使用authority owner同连接的标准JDBC DatabaseMetaData.getTablePrivileges，只读核三个确切本域对象/本域authority runtime和backup的8项直接NO-grant-option权限：runtime watermark与maintainer SELECT/UPDATE、maintenance audit INSERT，backup三表SELECT。精确过滤TABLE_SCHEM/TABLE_NAME/GRANTEE，不能靠underscore模式或其他schema/账号/角色/public结果补足；缺项/可再授/读取失败关闭，不改权限。现已解析DM8 JDBC原件getTablePrivileges实际委托MetaSQL只读SELECT并返回标准列，有本地字节码来源证据；真实DM8结果仍须运行另核。该最小核验不替代新鲜完整全库权限库存（其他过权/继承关系仍由完整库存门禁）。

ProductIdentityBootstrap原common信任/证书/组织/职责/primary空表/authority未初始化校验、事务内maintainer/watermark/audit与拒重放不改；仅原DCL块限定shared==null，shared已核权限路径零DCL。同域水位generation0/READY/空历史、审计和事务失败回滚保持，账号不合并。专项使用真实H2预安装权限/正常证书/登记事务，metadata URL仅测试双，任何shared DCL均tripwire拒绝；逐项缺权限、错误实际身份、登记审计失败及旧双库覆盖，不冒DM8初始化。

只对两源和受影响测试javac到新独占25目录（优先于22 classpath），JUnit限定受影响两类；重包继承24实际前端/11新项目依赖/51已核runtime库，仅替换重编确切API类和当前source/POM元数据、保持新咨询Start-Class/公开ID。新25包使用新路径/SHA，不覆盖24，所有旧字节保留。原执行自然退出时先核674链/真实回执/进程/双锁和新鲜全权限，再准入修正后的真实bootstrap/咨询启动；不存在skip/假回执/新的permission bypass。


6.13重包细化：为真正只重包受影响API而不重建11项目依赖/前端，使用当前24实际plain JAR与Spring Boot 3.3.5 loader-tools Repackager（现Maven插件同SDK）生成新25包。JDK jar在新独占目录重新装入24已核API资源/前端/原POM元数据，只用本轮两源javac产出的确切class覆盖；依赖callback按24原classpath顺序提供已核11项目Jar和50外部runtime Jar，jarmode由同SDK同内嵌原件加入。逐包核除确切重编类外所有class/resource/前端/依赖字节及runtime classpath顺序，loader为同原件；这是工程包装证据工具，不是另一个bootstrap执行平台。

## 6.14 已有owned-image卷的精确只读存储证据绑定（2026-10-09）

FIX-SINGLE-DB-OWNED-IMAGE-RECEIPT-BINDING-01继承原安装R/D、正常部署与6.11单域启动，不增业务/身份/数据用途或资源批准。写集仅final-deployment.ps1机械准入、独立owned-image-admission.ps1/只读proof.py与专项；先在已批准隔离副本验证再集成。旧daemon-file背文件路径分支原样保留，不把任意路径并入allowlist。

新backingKind=owned-image-volume只准当前确切primary-data-20261009-image、primary.ext4、loop491040、固定稳定device，任务/卷角色必须与实际Docker inspect一致。128MiB/0.25CPU/pinned原DM8镜像只作Python只读工具，entrypoint显式python3，不启动DM8，无网络、rootfs readonly、cap-drop ALL/no-new-privileges、image与bounded卷readonly，设备只读(r)，无秘密/DB连接。Linux LOOP_GET_STATUS64只读ioctl取loop编号、背文件设备号与inode、offset/sizelimit/flags；与readonly镜像stat的st_dev/st_ino、kernel loop sysfs/backing_file/size/autoclear以及实际bounded根st_dev逐项相等。读取镜像ext4 superblock magic/UUID（offset1024，UUID104），核原UUID29678a4e-4538-4341-88cb-4718a7c1f5d8与当前挂载容量。镜像必须regular非symlink、root:root/0600、3GiB，offset/sizelimit0、autoclear false，实际ext4容量3094126592且不超过3GiB；marker及三域files目录分别10001/10002/10003、0700、非symlink。不是路径字符串匹配或模拟证据。

历史ENOSPC只继承原resume02 source/log（assert ENOSPC在失败marker写入之前实际已执行）与resume03 source/log/exit0原件及SHA；原probe所在相同image/loop/UUID/owner有后续实际核对。不再次向在线磁盘写quota probe。新binding proof以machine live输出和实际image-volume metadata、确切target/helper身份/安全配置、这些原件引用组成，proof整体SHA绑定，15分钟新鲜；checker核原件SHA/实际历史脚本断言与成功resume03说明、固定image/loop/UUID/容量/inode设备对应和原owner marker。旧无结构proof不能进入新分支。

launcher在全局installation/15账号/674库存和原窗口/资源门禁外，仅增加该机械证据入口；实际runtime再次inspect确切image卷Name/Driver/Mountpoint/task/role，无foreign options，数据库非root/readonly/caps ALL/no-new-privileges/无devices/capadd、现3挂载只含db/logs/owned-entry，不能访问image卷或扩大背文件；应用仍原非root本域files挂载，禁止背文件/设备授权。错卷/任务/角色/镜像/设备/offset/inode/UUID/所有者/容量/readonly/helper/历史证据/新鲜度一律失败关闭，缺证不猜。

初始化complete只表示全局六schema/15账号/105迁移/674授权与当前存储安装前置完成；identityInitialization独立记录initializedDomains=[consultation]及其真实bootstrap/postconditions引用，不宣称执法/裁决已初始化。只有选中域有真bootstrap后置证据才可准入该域（新owned分支）；未选中域的file UID与全库权限仍须完整。新compiler绑定实际新鲜inventory，不套旧manifest/request SHA。25咨询JAR/HTML/依赖均继承，不重编/重包或重放bootstrap。通过专项和真实只读绑定后立即组装真回执、ValidateOnly和实际consultation受管启动；TLS/正常client/OAuth/成员业务Grant/无模型及撤权重启各自另证，绝不以进程或fixture替代。
## 6.15 实际Created网络前置与同容器安全恢复（2026-10-09）
FIX-SINGLE-DB-CREATED-NETWORK-ADMISSION-01仅修原launcher fixture把NetworkID立即分配的假设。实际首个咨询Created容器ef041b814cf38d8bef01762214c3799c1c8297d171b0417771655e8251717da4的网络唯一、domain Alias正确、HostConfig.NetworkMode为原完整ID，但NetworkID/EndpointID/IP为空且Pid0/未启动。原guard已失败关闭，不能手工start绕过；失败副作用只为受管create，Java/JDBC写/HTTP均0。

Created阶段只有State.Status=created、Running=false、Pid0、对应EndpointID空、网络唯一/正常domain alias和HostConfig.NetworkMode确切ID，并原Assert-FinalOwnedRuntime真实network inspect Id/owner/Internal/DM target一致时，才接受尚空NetworkID。其他状态或非空错误ID拒绝。运行start后重新核相同完整原guard，此时NetworkID必须确切原ID，不能把Created例外带到运行态；原HostConfig/command/SHA/只读mounted references/资源/端口/域隔离和window/storage/current inventory都保持。

正常维护CLI增加显式ResumeCreatedContainer确切64hex ID，仅接受单域configuration、原唯一同名/同label/同manifest/release/User/resources/网络/挂载与State=created/Running=false/Pid0。恢复不create/rename/remove，不覆盖资源或重授/bootstrap；按同配置重新生成原launch command并完全比对，加载现受管秘密只用于内存逐key核原Config.Env与当前config/DPAPI解密值相等，未预期SINGLE_DB环境拒绝，环境内容不写证据/日志；finally恢复和清理。当前权限/证据/窗口/owner及实际挂载前置再次通过才调用现start并作运行态复核。不能恢复running/exited/foreign容器，若后续Java/TLS失败原件保留并先定位；新功能是最终维护机械失败恢复，不是开发专用产品路径或业务授权。
## 6.16 普通引用显式bytes失败关闭与真实挂载差异（2026-10-09）
FIX-SINGLE-DB-REFERENCE-BYTE-ADMISSION-01只修现生产launcher shell预检和原受管引用机械接线，不增业务/身份/来源/模型许可。原26/27咨询应用曾实际运行Java，但server.p12 TLS解析失败；与同一实际Source的只读探针28确认该mount是directory（4096），主机原server.p12是4510-byte正常PKCS12、原SHAaea7...2714和原JDK10验证不变。不能将主机SHA或inspect Source相等当容器已经读取到同一文件；也不能把新同源目录当新秘钥、改密码猜测或绕过TLS。

原checks为set-e之后的test-f && test-not-link && sha-equals；POSIX AND列表非末项失败并不必触发errexit，前面的test-f失败可继续Java。改为每个完整predicate用if ! ( ... ); then exit 78; fi；files/domain权限谓词同样显式保护。只有每一项regular/non-symlink/原SHA和正常UID/GID/0700通过才exec原Java。同一工厂用于create和Created恢复，原失败容器command不同/State exited不能因此自动恢复。专项以真实/bin/sh在非秘密小fixture目录验证缺失/目录/symlink/hash/UID/模式失败会退出、目标exec不执行；不把fixture当产品挂载/配额/业务证据。

真实private-root引用只允许原本域受管C路径与其已观测映射，保持Windows ACL和消费者readonly/最小权限，不允许复制私钥到仓库/发布daemon路径、改访问权限/跳过证书链或盲目生成替代keys。当前Docker同路径读为目录的主机共享问题须确切取证并由部署操作人恢复该限定根目录的可读共享；未取到逐字节真正文件就保持运行阻塞。对新输入源/资料/法律/专业流程无任何推论。验收说明只陈述实际固定SHA/期望地址和真实可访问状态、角色/来源/步骤/证据与边界，不是计划/状态账，CON全需求D/验证/验收不得被这些工程证据升级。
### 6.16.1 原件句柄物理路径接线纠正（2026-10-09）
用户明确先只读核路径，不新增资源/执行器、不改共享/ACL、不搬私钥；代码接线错误则继续原任务。实际desktop-linux/WSL2 C盘已共享，hosts/settings/D源码对照SHA一致。GetFinalPathNameByHandle对原四只读文件句柄返回C:/Users/60329/AppData/Local/Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/AgentPlatform/single-final-20261009/consultation对应原件，WSL直接stat/sha256sum均regular/原SHA一致；未重定向的原逻辑路径为目录。此前“请操作人恢复C共享”只是初步误判，现撤回；GetCurrentPackageFamilyName返回15700，不声称当前PowerShell拥有原生包身份，句柄实际路径证据才是依据。

原logical path/source/secretRoot/本域范围/逐SHA审批保持。新增只读句柄解析，将同一原件解析后的确切物理Windows路径用于Docker普通readonly mount；不移动/copy/chmod任何文件。物理根只有原root自身，或本次确切OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/AgentPlatform加同一原root后缀；不泛化任意包/环境变量/目录。file以原逻辑文件句柄证明实际物理原件，从同一原相对后缀确定并准入确切physical root，再用该physical root目录句柄复核；原logical root可能存在影子目录，不能以其句柄推断文件未重定向。文件物理路径必须恰好等于已准入physical root加原logical相对路径，逻辑/物理SHA相同；原Assert-FinalFile防reparse/非regular/错误SHA沿用到物理原件。句柄API失败、未知重定向root、跨域/后缀不一致、缺文件/目录/错误SHA均失败关闭，不回退猜路径。仅驱动器字母lower映射到已观察Docker /run/desktop/mnt/host/，actual/HostConfig mount都须匹配解析后物理路径。

纯合成launcher回归对新增物理解析边界使用显式tripwire/mock，不访问真实私钥或Docker；实际只读原四文件句柄/WSL/SHA核验与工程反例分别留证，不声称实际container mounted bytes/TLS已过。旧exited容器mount/command不可变，本补丁不create/rename/remove/restart任何资源；失败应用的可逆受管恢复仍待独立最小设计与确切执行准入。本项机械接线不创建成员/client/业务Grant，不升级产品D、HTTP或人工验收。
## 6.17 固定25失败实例最小可逆受管恢复（2026-10-09）
用户当前明确恢复固定25正常访问，并要求保留失败实例/log、刷新真实库存/物理证明、不重放初始化/复制私钥/旁路TLS；这是本轮恢复授权，不沿用上轮只读诊断的“不得新增资源”限制。仅一个替代应用消费原咨询768MiB/.5CPU槽，原failed实例必须exited/非运行/Pid0/nonzero退出，不能同时有本目标同域运行实例；不新建卷/网络/DB、不要扩容、不启动旧错误实例。工程任务FIX-SINGLE-DB-FAILED-INSTANCE-RECOVERY-01依赖已完成部署入口/Created网络/owned-image接线；物理引用修复证据作为本轮输入核验，不将未完成整体client/业务授权工作设为HTTPS恢复的虚假前置。

恢复输入绑定确切原ID/原canonical name、同一25 JAR/props/manifest/正常配置/DPAPI引用、本轮人类范围和原失败command工厂。工厂历史文件SHA固定273e6b036d107ec721281e8c86eb7107715f8172571877042f7898263a0e2dfb，只解析Get-FinalApplicationLaunchCommand的函数体作为旧实例bytes验证，绝不执行旧launcher/旧command。复用原容器guard核User/resources/完整command/labels/network/port/所有mount；只有retained非运行失败实例验证可按原logical readonly来源核旧挂载，绝不带到新create/start/运行态。旧Image须同原批准镜像；原Env与当前公开配置+DPAPI在内存逐key相等，额外SINGLE_DB键拒绝，不写secret值。

独占本目标本域维护mutex；现时完整库存/真实owned-image只读物理证明/原674和已成功bootstrap/窗口/源SHA须重新准入。原canonical name只有确切原失败ID；preserved name固定为原名加-failed-及原ID前12hex且不得已存在。再次确认状态/无本目标同域运行writer后，仅rename保留原ID/log释放原canonical name，随后调用原Invoke-FinalApplications按同固定配置创建并启动唯一替代，不另造launcher或绕任何准入。新引用必须原件句柄物理路径、显式if/exit78 SHA前置；新运行后原guard完整复核。没有新canonical实例时失败可逆rename原ID回原名；如果已create则保留两实例及错误原件，不rm/覆盖/盲重启、不自动将错误旧实例恢复写入。外部旧环境不改。

start出口只为命令成功，非HTTP。正常UID在真实替代容器中stat/non-symlink/sha256sum核四文件和Jar/props，正常DNS/CA/SAN/时效/Issuer，正常维护证书私钥/证书配对与challenge先核，使用严格TLS正常请求固定页面；不得SkipCertificateCheck/关client-auth/替换CA或用SYSDBA作业务身份。维护正常HTTPS与业务成员/Task/Source/Grant/OAuth、模型/专业、三域整包恢复/A/B/C各自分证，后者缺口集中列出，不等待完整CON-01～12才恢复正常固定入口。
### 6.17.1 必需空证据目录与当前命令同ID退出恢复
首个替代9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f已过显式文件/SHA前置并进入Java/TLS装配，因批准配置SINGLE_DB_CONSULTATION_PRODUCT_SOURCE_EVIDENCE_ROOT=/product/files/consultation/source-evidence缺少空目录而exit1。ProductSourceEvidenceFiles ctor的toRealPath要求部署先准备目录；不是来源许可、模型或新业务资料决定，不改25/props/SHA。首次失败日志原件保留。以现owned-image证明的同一filesystem device/本域root UID10001/0700为边界，仅准备这一确切空子目录，不能mkdir任意路径、chmod既有目录、扩大挂载、复制原件或导入来源内容。WSL由现有原volume挂载进入精确本域cwd，实际mkdir进程降为10001:10001/clear-groups/no-new-privs并移除全部capabilities，保持既有私有祖先权限不变；不授父目录访问，不触碰其他域/DB/log目录。新目录0700正常owner，缺件/错UID/GID/mode/device/符号链接拒绝；空目录不是Source/Grant准入。

9b实例的immutable mount/command已为当前显式if/原件物理引用，不需要再次替代。增加单域ResumeFailedContainer正式机械恢复：只接受确切canonical name的exited/nonzero/Runningfalse/Pid0当前实例，完整当代command/资源/镜像/标签/网络/mount/当前所有Env内存对账、原件/现时库存/物理证明/窗口均一致，单writer mutex下仅start同ID，运行后完整复核。不是通用blind restart，不接受原ef错误command/source、running/Created/成功exit0或额外Env；失权/缺证不恢复。旧错误实例和首次9b失败log不删，不新增第二替代。实际mkdir及same-ID start分别留证；随后正常UID mounted bytes与严格TLS/页面/维护client接口各证，不升级业务成员或CON整体验收。

## 6.18 固定25正式localhost TLS透传发布角色（2026-10-10）

来源：Q-SINGLE-DB-LOCAL-TLS-PUBLISH-01原始人类批准已核真实消息，见[独立批准](../evidence/single-database-installation-20261009/approved-execution/owned-image-binding26/local-tls-publish33/approval.json)。本节只作部署机械技术D，不扩大业务R/D，不改固定25及原props。当前ARC任务依赖正式部署与Created网络任务均已完成。

### 6.18.1 唯一构成与拓扑

正式传输角色固定名agent-platform-single-final-consultation-publish-20261010；继承现本地已批准JDK镜像sha256:14731f0ec76c841877c8858e1a29413d7acacda5c5ce567eaf6e5f19913b7cb1，运行单一可维护Java TCP byte relay，不提供HTTP页面、管理入口、授权/数据/模型功能，不安装或下载其他代理。发布是最终运行传输层，不是开发/临时产品路径。UID10010:10010、readonly root、cap-drop ALL、no-new-privileges、128MiB/0.05CPU、pids-limit64、restart=no，JVM heap48MiB；仅两个readonly非秘密mount（relay JAR与严格配置），无Docker socket、DB/files卷、证书/私钥、credential或业务环境变量。

角色只接现有默认bridge（确切网络ID3fc351592a63a334572db0a34af42b586da11586350ffc4e6450835bc956c5bb）以发布127.0.0.1:5446→8443，及原Internal网络（确切047a31…d65）以到达批准9b。不创建网络/卷、不将应用或DB接bridge，不改变Internal。容器net.ipv4.ip_forward=0且启动读回必须0；IPv6 forwarding同为0，cap全降，禁止IP包转发。程序只在自身bridge IPv4接口监听8443，不在Internal接口监听；无目标选择协议、CONNECT/SOCKS、DNS服务或host-network。它不是宿主代理。

唯一出站业务Socket目标为经受管入口核完整9b ID/标签/镜像/command/六文件SHA/网络/资源后得到的该实例8443。配置绑定短ID DNS名9b0037591eef（现Docker实际DNSName），以及当次实际内部IPv4；每次连接前在原Internal网络DNS重新解析，必须唯一IPv4且等于本次已验地址，缺/多/改变均拒绝，绝不仅信任旧IP。角色必须在开始监听前证明两个本机IPv4分别来自现bridge和原Internal子网；其它网络接口/双目标/未知配置拒绝。没有运行时任意目标参数或脚本执行入口。停掉/移除原target后DNS不能解析时新连接拒绝；新同名应用不是批准9b，受管重新发布需再次完整ID核验，不自动跟随。

### 6.18.2 受管准入与不可变输入

复用scripts/start-single-database-applications.ps1的单一入口，通过互斥模式PublishLocalTls/StopLocalTls和严格发布配置调用独立机械helper；不造第二套部署平台。实现子任务IMP-SINGLE-DB-LOCAL-TLS-PUBLISH-01先登记，依赖本ARC退出。发布前后使用同一manifest/domain维护mutex。批准JSON及确切原话/请求SHA、原资源批准、固定JAR/props/manifest均核；窗口用绝对UTC核from至2026-10-10T10:00:00Z，任何create/connect/start前重核，不延长。复用已有full compiler和实际现时库存/674权限检查，fresh receipt≤15min，历史105/674/bootstrap/失败原件只继承不重放。复用原application与owned runtime guard并在真实10001容器内核六readonly文件bytes/0700。读取目标Env仅在内存按原受管secret逐key对账，不写入原件输出；发布角色本身不获得secret。

原Internal network、target完整ID/Endpoint/唯一IP/DNSName、DB与应用原网络必须一致；现bridge必须确切ID且non-Internal，不配置任何网络/防火墙或外部地址。target仍running/PID非0。所有同时运行本目标资源逐ID读取实际Memory/NanoCpus，限值缺失或未知本目标角色拒绝，加新角色后合计≤7GiB/4CPU且在线DM8仅一。原ef保留exited，不触碰旧环境。

relay源码编译为独立不可变小JAR，源码/字节/严格配置SHA写入发布manifest；host可见文件按原Windows句柄物理路径解析且只读bind，前置在容器内显式if校SHA失败exit78。create前若确切名字已存在不创建替代/不删除；未知create结果先inspect确切name/ID及全部command/labels/env/resources/mounts/networks/ports，有回执才准接续。只接受本配置确定的created或已运行实例。create使用默认bridge，start前只network connect原Internal到发布角色；运行后核实际NetworkSettings.Ports确为127.0.0.1:5446，应用和DB网络完全未变，再做正常TLS/HTTP。

### 6.18.3 失败关闭、恢复与生命周期

relay无TLS解析/终止，原server CA/SAN/Issuer与mTLS由固定25执行；只能byte转发到唯一8443。连接数最多16、两向小buffer、有连接/空闲超时和EOF半关闭处理；拒绝配置错误、DNS变化、目标不可达、forwarding非0或接口漂移。进程退出不自动重启。网络/目标不确定或校验不符时受管入口不启动/不续接；运行验证失败只按原manifest与exact publisher ID可逆stop新增角色并保留容器/log/配置，绝不stop原咨询/DB、删资源或重启应用。Stop路径仅停exact本角色，不扩大到按name猜测实例；窗口到期禁止新受限部署副作用，现服务不擅停。

退出证据分离：源码/受影响专项→实际role资源/双网/forwarding0/实际端口→宿主严格CA/SAN/mTLS正常HTTP/咨询唯一HTML及Issuer→原维护client登记（如既有合同已覆盖）。普通成员、Task/Source/业务Grant、无模型首次操作、撤权/持久重启、真实模型/专业验收另取证。不将入口恢复升级为A/B/C、CON全项或父任务完成。

### 6.18.4 最小验证集合

独立Java纯逻辑/loopback字节测试验证严格配置、唯一解析、forwarding拒绝、两向字节不变、EOF/故障、并发/超时；PowerShell替身Docker专项验证批准/原文SHA、窗口、target/network/资源/bridge/未知字段/额外mount/env/实际ports漂移拒绝、互斥模式、未知创建结果不重建与exact-ID stop。替身/loopback只作工程证据；真实运行端口、peer/TLS/mTLS/HTTP必须另留原件，不用skip验证、开发页或伪业务用户。

6.18实现审查补充：可逆Stop使用原immutable发布配置/角色ID完整核验，不要求现时DB库存、target可达或解密target凭据，避免失败时被无关健康门禁阻止收回新增角色；仍受原窗口、exact-ID/command/mount/label/resource/双网绑定约束，只停止新增角色，原咨询与DB不动。新专项纳入原verify-all首阶段。

6.18实际Created原件与窄修复：首个正式发布角色6796847921a4c9542d8e2e2e7b203a72faae2a89d508ed6dd452ccb009baab2f已由受管入口创建但未start，实际Mounts.Source保留确切物理Windows源D:/…，尚未映射成/run/desktop/mnt/host/d/…；原运行态断言提前拒绝，见[publisher Created原件](../evidence/single-database-installation-20261009/approved-execution/owned-image-binding26/local-tls-publish33/publisher-created-failure-inspect.json)及[确切原因](../evidence/single-database-installation-20261009/approved-execution/owned-image-binding26/local-tls-publish33/publisher-created-failure-cause.json)。仅Status=created/Running=false/Pid0时可接受actual Source等于已核句柄/原SHA的确切HostConfig物理Windows源；所有mount数量/readonly/command/resources/labels保持原门禁，运行后仍强制实际映射源，启动内六引用SHA失败关闭。接续同一679 ID，不新建替代，不改变原咨询。

6.18运行态实际原件与收敛修复：同一679实际running，但Docker Desktop的D盘非秘密artifact Mounts.Source在运行后仍保留确切句柄物理Windows原源，非源文件变更。旧“运行后必映射路径”是错误的元数据假设，已用[运行态原件](../evidence/single-database-installation-20261009/approved-execution/owned-image-binding26/local-tls-publish33/publisher-continuation02-inspect.json)及[原拒绝](../evidence/single-database-installation-20261009/approved-execution/owned-image-binding26/local-tls-publish33/publisher-continuation02-cause.json)证明。仅接受exact已核句柄物理Windows源或其确切Docker映射源，且每次running准入必须实际10010核两regular/non-symlink文件SHA完全相同；readonly mount/数量/command/Env/资源等均不放宽。没有mounted bytes绝不因Windows字符串相同放行。停止已知角色不以运行bytes健康为前置，但仍核原不可变ID/标签/command/声明源/readonly/资源/网络边界，只可撤回本角色，不可启动或改其他资源。

发布配置的原immutable角色绑定与每轮fresh只读准入证据分离：optional originalPublication只引用原固定发布JSON及SHA，禁止链式引用；approval/source/build/JAR/runtime/root/target/bridge/name/schema全部与原件逐项相同，原deployment只有initializationReceipt/inventoryReceipt两纯准入引用可换成新的真实现时观察，其他字段逐项一致。原publisher label继续绑定原JSON SHA，fresh refs不迫使删建已批准角色、不延长freshness或窗口。每轮仍经原compiler/674/owned-image/target完整校验；recorded capture不倒填，过15分钟确需重新观察，不重放初始化/授权。此为该单一角色必需受管运维，不另建恢复平台或放宽业务身份。

6.18真实限定出口（2026-10-10 02:27:47北京）：同ID受管publication03已通过。宿主JDK21标准JSSE直接只读加载原物理maintainer.p12、受管密码仅内存，CA/SAN/原server pin及维护mTLS成立；原client23确切body/幂等键返回ACTIVE/version1，无证书维护403、错误CA/hostname拒绝。[真实TLS/HTTP/身份边界](../evidence/single-database-installation-20261009/approved-execution/owned-image-binding26/local-tls-publish33/java-normal-https-result.json)及[编译exit/原件SHA](../evidence/single-database-installation-20261009/approved-execution/owned-image-binding26/local-tls-publish33/java-https-preexecution02.json)。这是Schannel EphemeralKeySet兼容失败后的已有正常验证方法，不是新增产品身份/权限路径，不复制私钥、不持久写store。HTML与JS/CSS正常HTTPS字节均与固定25一致，原应用/DB PID/StartedAt/网络未变；错误证据目标探针/写证据失败均留原件，不冒产品故障。仅正常访问限定实现/恢复退出，不升级普通OAuth/业务、最终发行、模型/专业或A/B/C。
## 6.20 委托评估后的裁决首次正常启动顺序与本域透传限定D（2026-10-10）

来源不是人类逐字批准本节细项：已通过read_thread核验“指令”聊天用户原话“需要我决定的，你帮我根据需求评估决定”，消息01a1225a-3b8c-74f1-9229-4fd938eedc93、turn01a1225a-3b63-7f51-b7e3-5486a244c4c5及真实turn开始时刻，原话/SHA/版本/责任与未返回消息时刻见approved-execution/adjudication-first-start39。Codex依据三独立正式入口、项目最终身份/失败关闭需求及原三应用/≤7GiB/4CPU资源合同作派生技术决定；不把代理转述/本方案当人类原话，不代定组织关系、来源权利、专业方法或费用。

仅替代6.8首次启动“构建前必须已登记”的顺序缺口：原公开client adjudication-case-workspace先绑定全同正常产品行为的独立首次启动候选→新鲜目标/本域身份状态核查→仅原域确为空时按既有共享实例正式维护初始化→原获准裁决应用正常安装/启动→原Issuer严格CA/SAN/pin与原维护证书mTLS，POST原client23正文/幂等键→真实登记回执version/SHA绑定→登记后才进入已登记发行/访问验证。登记前正常OAuth/登录按既有服务失败关闭；不加临时身份/API/开关，不借咨询包/client/旧环境，不改旧6.8工程原件。候选构建仅公开标识绑定，不假称身份已经登记；真实Bean/DB/入口、登记、发行、业务及专业验收分别取证。

需要宿主访问时按该委托作唯一最小可逆技术接线选择：127.0.0.1:5448→本轮新鲜核验原批准裁决应用实际完整64hex ID:8443，新增唯一publisher≤128MiB/.05CPU，裁决应用原768MiB/.5CPU，总仍≤7GiB/4CPU、唯一Internal应用网络不变。仅TCP/TLS字节透传，不终止TLS/读取复制私钥/扩应用egress/授权或中心通道；不得将咨询679角色批准冒充裁决精确角色批准。本节决策+原资源+委托源证为裁决技术接线依据，精确实际目标/网络/端口/镜像/配置/源SHA须在实施配置内绑定并正反拒绝。原咨询fixed25/679/主DB、原失败实例日志均不动，105/674/咨询bootstrap不重放。

唯一主会话持有部署/DB/共享测试/主计划；对应FIX-SINGLE-DB-ADJ-PRODUCT-ENTRY-01与IMP-SINGLE-DB-ADJ-LOCAL-TLS-PUBLISH-01，无新资源恢复平台。当前UTC须在原2026-10-10 18:00北京截止前，窗口不延长。未知创建/启动/登记先查完整ID/状态/日志/幂等回执，再决定，不盲重试；失败保持正常OAuth拒绝，只对确切新增publisher作既定可逆处理并保留现场，不擅停原服务/DB或删卷/秘密/失败资源。

退出标准：当前独立bundle/JAR与配置SHA，资源实际限额/唯一网络/端口/隔离；未登记OAuth拒绝，无维护证书/错误CA/hostname/跨域client拒绝；本域Issuer/HTML/JS/CSS字节与路由一致；真实维护登记回执/version/SHA和登记后绑定，必要前后治理真实保留历史红。现时普通成员/案件资料/模型/专业及C不由本D放行。

## 6.21 裁决真实身份初始化回执的单域启动准入接线（2026-10-10）

依据6.20、原三域安装R/D及adjudication-first-start39真实首次bootstrap/只读逐字段后置/全六schema inventory，对现有owned-image launcher仅consultation的临时单域守卫作限定工程接线。不是放宽完整六schema/15账号/105迁移/674权限/存储和当前窗口；不使用咨询回执满足裁决身份条件。FIX-SINGLE-DB-ADJ-IDENTITY-RECEIPT-BINDING-01写集仅scripts/lib/single-database-owned-image-admission.ps1与独立拒绝专项；在原owned-image专项入口仅import注册新专项，并在verify-all的既有工程阶段显式注册该入口（本地主机Windows/pwsh环境；非Windows不冒这些Windows证据已验证），原断言和证据不改。

consultation原执行/对账format、身份标志及全部拒绝不变。adjudication另用本轮真实ACTUAL_ADJ_NORMAL_FIRST_BOOTSTRAP_ATTEMPT/v1及ACTUAL_ADJUDICATION_SHARED_BOOTSTRAP_RECONCILIATION/v1；初始化回执只声明所选一个域、globalIdentityInitialized=false。两个来源必须SHA有效，执行target/JAR一致、exit0、bootstrapInvoked=true、retryPerformed=false；对账domain=adjudication、actualJdbc=true、adjudicationIdentityInitialized=true、globalIdentityInitialized=false、memberOrBusinessGrantCreated=false、requiredObjectGrants=674、同target/JAR，postconditions为本域实际只读格式、passed=true且executedWrites/executedDcl=0，七业务身份表计数必须逐项0。缺失/错域/咨询证据借用/成员或业务Grant/旧包/目标不一致/失败或未执行/全域初始化声明一律拒绝。enforcement现无同类新鲜实际身份回执，不随本改动放行；身份守卫不是授权初始化或客户登记。

专项先对原守卫真实红（裁决完整真实证据仍拒绝）留原源码与输出，再对修后正例及上述负例运行；证据双只模拟文件输入/拒绝，不把专项当DM8/身份验收。既有consultation专项继承并复跑受影响身份/owned-image校验，不重跑无关全npm/Maven。应用须另具完整实时compiler/初始化/库存/存储/uid目录与当前新包SHA，通过ValidateOnly后才正常启动；新包不冒已登记发行，TLS与维护client登记另证。


6.21限定工程出口：当前新裁决真实回执原被拒绝（32项31过/1红）；修后新32及原owned-image/咨询70共102/102，正常launcher138/138，均0失败/0跳过。新32通过原专项import及verify-all Windows工程阶段显式入口注册，不修改旧断言。05:09后置仅原治理链通过，曾只有import而原入口不在verify-all；该接线遗漏在最终检查前发现并显式修正，不将05:09记录冒新32治理覆盖。真实本域证据消费者准入及当前完整compiler/安装配置ValidateOnly通过；只读helper复核当前device/inode/UUID/目录/3GiB固定长度，未重做在线ENOSPC或修改存储。模型/成员/业务Grant0。Linux新jar未stage，实际裁决应用/维护client登记/已登记发行仍未执行；主计划父任务不退出，后置治理另留原件。


## 6.22 实际裁决39应用与唯一5448透传的精确守卫（2026-10-10）

IMP-SINGLE-DB-ADJ-LOCAL-TLS-PUBLISH-01继承6.20和正常安装R/D。原39包已无覆盖stage，应用实际完整ID4f9633f496823838d058d8f0eb6bd8a9bc091e87e0be19f923bf28c3f13df2ff，HTTPS8443/UID10003、唯一Internal 047a31…cad65、172.25.0.6及short-ID DNS已取证，包8ce3…b1bc/原properties e4d0…4302/原3受管引用mounted SHA与目录700成立，仍非client登记或业务接受。

publisher-derived-decision40是依据真实用户委托与原资源合同的派生技术记录，不是用户逐字批准具体角色；SHA cd89400ecf650f4914872698002f329b15b7240b1517e736478399f8bcaaebcf。冻结真实委托3bc845…7c1c4、原话8691cf…143ce、原资源批准649f93…00fff7，原绝对窗口Oct8T19Z到Oct10T10Z不延长。仅新schema SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v1显式调新single-database-adjudication-tls-publish.ps1；现CLI原consultation路径与守卫不变。源、当前guard、继承实际relay构建/Java10测试及原件SHA、派生决定、当前真实部署/包/props、实际target/bridge/runtime/整体配置都须SHA绑定；拒绝咨询scope/旧包/错target/多字段/缺源/越窗/新网/未知运行角色及总额超界。

唯一新角色agent-platform-single-final-adjudication-publish-20261010，UID10012:10012（技术进程隔离，不是产品身份）、128MiB/.05CPU、pids64、read-only/cap-drop ALL/no-new-privileges、两个readonly非秘密mount、原JDK镜像、不加Env，127.0.0.1:5448:8443，既有bridge+唯一Internal两网，IPv4/IPv6 forwarding0。继承同原LocalTlsPublisher Java源码c45dc4…ce53、Jar a693bc…d2e2、只转TCP字节/精确targetIp+fullId+short DNS+子网+连接/超时上限，不重编此未变化relay。只允许当前裁决、原DB/ff/咨询9b/679及本新角色参与预算，≤7GiB/4CPU；原受保护三角色PID/StartedAt/网络必须与责任原件一致。不读/挂载私钥、不TLS终止、不扩应用网络/egress/权限。裁决target只核本域目录及实际5文件，不借咨询source-evidence或虚造裁决业务目录。

写集独立guard、新拒绝专项、现CLI仅schema分派与verify-all Windows工程入口注册。新专项只受控文件/命令双，不发真实DB/业务；实际发布须新鲜原安装库存/owned proof、实际目标Env仅内存比对与mounted字节/目录/网络、真实资源、创建/连接/运行各态完整ID与mounted bytes/forwarding0。未知创建或启动先精确名称/全ID/不可变profile核查，不blind retry；失败保留现场，只有确切本新增publisher可按原同window stop并保留，不删资源/不重启旧角色。

标准JSSE使用原物理maintainer.p12/CA、受管密码仅内存，CA/SAN/原server pin验证本域Issuer与独立HTML/JS/CSS SHA；原client23 body/幂等键真正维护mTLS POST，并保留ACTIVE/version回执、输入/SHA、登记后绑定。先证未登记OAuth拒绝，再登记；无维护/错CA/错hostname/跨域client均拒绝。跨域反例不是新身份或正式client输入。后置继承47/41基线和旧健康红，工程/运行/登记/业务/专业各自取证；普通成员/Task/资料/模型/旧环境/C不扩。

6.22反例方法细化：读取现有ProductClients.redirect仅要求合法HTTPS URI，并不把合法跨主机OAuth回调推定为禁止；不新增同host业务规则，不以裁决维护凭证POST外国client而意外创建未批准身份。“跨域client拒绝”实际检查在裁决Issuer对既已属于咨询域的consultation-workspace-product发正常PKCE授权GET并拒绝，另检查裁决client登记前同入口拒绝及无维护证书POST原确切输入403。只有源client23原正文/原键由原裁决维护证书真正登记。Java检查工具初次JSON引号编译失败及修正源码/exit保留，无HTTP执行/产品包变化；不是产品失败或假回执。

## 6.23 裁决固定目标relay专化与发布结束态纠正（2026-10-10）

真实否定6.22的relay直接复用假设：原Java readConfig第30行硬编码咨询9b完整ID，40目标4f使c824实际exit1/PUBLISH_CONFIG_REFUSED、尚未进入ServerSocket监听；工具返回0只是第一次Running后的竞争态，不是传输或HTTP。保留40原源/JAR/profile/容器及全部失败原件，不覆盖/删/rename/盲restart；裁决4f、咨询9b/679/主DB均不改。FIX-SINGLE-DB-ADJ-TLS-FIXED-TARGET-01仅继承6.20原身份/正常维护入口与资源D，专业/成员/材料/模型/C不扩。

新增scripts/java/AdjudicationLocalTlsPublisher.java只从原最终relay机械专化classname与TARGET_ID=4f9633f496823838d058d8f0eb6bd8a9bc091e87e0be19f923bf28c3f13df2ff两处，逐字节归一比较其余代码相同，不放宽为任意目标；11字段/short DNS/实际IP/既有子网、端口8443、max16、connect5000/idle30000、仅TCP/不读TLS/HTTP/私钥及原隔离不变。继承原算法设计与10专项结构，真正重新编译本新小relay与真实Socket受控组件测试，不重编未变化产品39或咨询relay。新source/JAR/build/classes/test/10exit及当前41 guard须精确SHA绑定。

40不可变失败资源继续停止；技术委托下选择不破坏原件的新精确角色agent-platform-single-final-adjudication-publish41-20261010，artifactRoot=adjudication-runtime40/publisher41、UID10012、128MiB/.05CPU/pids64、原127.0.0.1:5448、既有bridge+唯一Internal/forwarding0/two nonsecret readonly mounts/无Env同6.22。一域只能有一个active publisher，40完整IDc824必须仍Stopped/PID0；不得把保留失败角色算作可用资源或复活，运行预算仅原五角色+本41 ≤7GiB/4CPU。派生决定41引用原真实委托与原资源批准、40实际失败及当前target；不冒人类逐字新增批准或咨询679范围。

独立41 guard保持原40可恢复停止的源码/版本引用，现CLI只新schema SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v2显式分派，不改v1/咨询路径。创建/网络/运行强校验与各副作用前窗口/目标、未知完整ID先对账不盲重复。同原source/byte/target/env/权限/预算判定后，在耗时最终target校验和预算采样之后**再**Assert Publisher Running/完整ID/真实字节及端口，并记录最终时刻；budget不得用“角色已退出所以再预留128M”替实际存在。新增受控反例模拟在最后target检查退出，必须拒绝，且无restart/delete；实际严格HTTPS/原客户端登记回执仍为独立最终出口。

写集新Java/test、小relay独占build/new41 guard/test、现CLI与Windows工程阶段注册、主计划/本ADR及真实取证；旧40源/relay/错误证据保留并标误判不可使用。工程完成仅证组件/控制流；实际实例/JAR/配置、完整网络/资源/无未登记OAuth授权、Issuer/独立HTML/JS/CSS、原维护mTLS与client23确切请求回执和登记后绑定各自成立才可限定退出。

6.23限定工程实现证据：独立41 guard/new v2/CLI显式分派已实施，新73项实际PowerShell受控双全过，新增慢最终目标检查期间退出拒绝与原失败c824停止/完整ID漂移拒绝。Java小relay实际10/10、release17与精确SHA见publisher41/relay-build41.json；产品39不重编。原40 guard/JAR/profile/失败容器不改。上述仅工程，不冒实际监听/TLS/维护登记，后者按本D单独退出。

6.23第二次实际反例与同任务修正：41末态真实Running仍不能证明首连接类可加载。实际JAR仅main/Config而漏Pair，首HTTPS连接NoClassDefFoundError后exit1，无POST。保持41原件及完整IDfcc426f1196d6111384698ba11435a316da795ecc133e31049b2c2aaef9fcf5a停止/exit1/PID0，与40一并守卫。新publisher42目录/new独立42 guard/schema v3与精确agent-platform-single-final-adjudication-publish42-20261010继承同固定4f目标/窗口/资源/无密钥/双readonly挂载；不修改Java业务算法或产品39，仅从同已编译生产class完整封装main/Config/Pair三类、禁止测试类。独立实际JAR测试须先证41失败，再从42 JAR验证三类/codeSource/Pair完整双向半关闭/slot与socket生命周期，原10组件使用JAR优先类路径复证；真实HTTPS/原client登记另执行。所有失败保留，不重试同包/同失败容器或整个已有部分HTTP；若尚无POST，证实只读库存完整未变后可用纠正包从新专属证据目录进行原维护流程。

## 6.24 委托评估后的执法公开客户端配置与有界首次运行D（2026-10-10）

FIX-SINGLE-DB-ENF-PRODUCT-ENTRY-01沿原6.9/人类产品方向、当前真实“根据需求评估决定”委托与原三named应用资源。原enforcement-product-entry21-user-decision的answered已包含“菜单/路由/正常客户端/构建由开发按已有合同核明”；client23的clientId=null是当时未准备的输入，不是要求用户给公开字符串的业务未决项。源/消息ID/SHA/责任/技术派生标记及逐依赖判定见enforcement-first-start44/delegated-client-page-decision44.json与short-source-classification44.json；不冒人类逐字批准这些新字符串。

五入口保持原Issuer https://enforcement.agent-platform.internal:5447及原单redirectUri：workspace→/app/enforcement，job-pack→/app/enforcement/job-pack，document-templates→/app/enforcement/document-templates，document-rules→/app/enforcement/document-rules，legal-references→/app/enforcement/legal-references。从既有ENFORCEMENT域和页面key机械派生各独立正式公开clientId：enforcement-workspace-product、enforcement-job-pack-product、enforcement-document-templates-product、enforcement-document-rules-product、enforcement-legal-references-product。PUBLIC/NONE/authorization_code/S256/openid与原每入口幂等键保持，不改ProductClients.Input/单回调协议，不借咨询/裁决/旧注册来源；名称沿contract.ts/工作台现文字。公开client登记不登记普通subject或Grant，也不是岗位/资料/专业用途依据。

最终产品守卫复核不能以旧测试替代：当前模板页72/73行硬编码PROJECT-FUNCTIONAL-VALIDATION作发布/停用evidenceRef；法律页30行退出只清本地而未调用原endEnforcementLogin；模板依赖的规则入口及三个切片的最终身份/来源/操作清屏边界须按既有字段/源合同另核和修复，不能把开发旧页自动当最终D。故按6.9明定的nonempty subset（含job-pack）选择本次enabledPages=['job-pack']、工作台隐式存在，构建/首次登记只消费前两ID；后三ID仅可追溯技术准备，不登记、不启用页面或API，也不伪称业务未知要求用户命名。17项最终范围不缩减；FIX-ENF-ADMITTED-PAGES-FINAL-BOUNDARY-01另登记为最终边界工程待设计，未经该切片最终D不得启用。任务包沿已完成FIX-ENF-JOBPACK-FINAL-PURPOSE-01，仅原REAL_BUSINESS_COLLECTION/REAL_INTEGRATION、六组真实事实/异人核验/Source/READY合同，不默认案例/任务/身份/专业结论。

当前执法无正常runtime/客户端的首次顺序采用同三域维护合同下本域独立接续：独占当前源副本及输出、两公开ID/页面明确绑定，正常bundle-enforcement新独立JAR（不是已登记发行）→新鲜完整六schema/674/15账号/本域身份空状态→仅未初始化时用原ENF shared-instance维护manifest/账号/信任证书在既有ff工具真正首次维护bootstrap→只读精确本域后置和全库存变化对账→当前配置/owned-image/15分钟proof正常compiler和ValidateOnly→新增原获准ENF应用正常启动→新鲜实际完整64hex target/唯一Internal/原server/trust/policy SHA→本域精确5447透传→严格原CA/SAN/server pin及维护P12/private-key配对仅内存→登记前本域/跨域拒绝→仅两原API正文/原幂等键真实mTLS登记→ACTIVE/version1/登记后PKCE登录入口/独立资产绑定。没有临时身份/字段/API/注册绕过或test模式；未知结果先实际ID/日志/回执对账，已存在bootstrap不重放。

原维护输入为formal-binding17-resume-inputs的ENF条目与domain-references-enforcement10，subject=enforcement-acceptance-maintainer-20260929、organization=ORG-ENF-ACCEPTANCE、原SUBJECTS职责、证书74d07e92…72ba3、trust caf63a4e…a3f5及其受管引用；不拿CA store当maintainer或复制私钥。不推定普通成员或中心法定身份。独立包仅现API/最终source受管服务，无业务写/模型；实际Bean启动失败保留，不伪称工程装配就是运行成功。

原应用768MiB/.5CPU/UID10002/原Internal与受管文件目录不变。新本域transport在实际目标核明后才写确切固定常量及source/JAR/配置SHA，只TCP字节、127.0.0.1:5447→实际本域target:8443，UID10014、128MiB/.05CPU、pids64、readonly/capsALL/no-new-priv、原bridge+唯一Internal、forwarding0、两非秘密readonly mounts、无秘密Env、不读TLS/私钥、不扩大应用egress。只在委托同类最小可逆边界及新鲜原总≤7GiB/4CPU/一DM8确实覆盖时准入：本轮理论原6GiB/3.35CPU加ENF .75GiB/.5与relay .125GiB/.05=6.875GiB/3.9CPU，但理论不是实际预算证据。保护咨询9b/679、裁决4f/42、主DB及原ff工具PID/StartedAt/配置；40/41失败资源仍停止/保留。不得套用他们的exact-ID transport批准。必须实际JAR含main/Config/Pair三生产类且codesource/Pair双向/半关闭/释放测试通过；慢最终target/预算检查后再次完整Running/真实mounted bytes/端口复核，工程guard不冒HTTP。

原窗口到2026-10-10 18:00北京，不延长；105/674/咨询和裁决bootstrap不重放，原25/39/42不重包/重启，旧环境/C不动，模型0。限定退出仅所选两正常公开client/工作台与任务包静态发行和协议边界；普通成员/真实业务包/资料权利/专业/其余三切片/A/B/C另证。每个副作用前原批准/本D/冻结源码/源权利/完整资源/窗口与未知结果守卫成立才执行。

## 6.25 执法真实首次维护回执的单域启动准入限定D（2026-10-10）

FIX-SINGLE-DB-ENF-IDENTITY-RECEIPT-BINDING-01仅继承6.24/原维护合同与现owned-image消费者，待ENF本域真实首次bootstrap与只读后置成立后扩接其证据，不泛化跨域或全局初始化。原consultation/adjudication正反判定、105/674/15账号/完整库存/存储及当前窗口均不放松。ENF执行format=ACTUAL_ENF_NORMAL_FIRST_BOOTSTRAP_ATTEMPT/v1、对账ACTUAL_ENFORCEMENT_SHARED_BOOTSTRAP_RECONCILIATION/v1；两个SHA有效且实际target/JAR一致、exit0/bootstrapInvoked=true/retryPerformed=false。本域enforcementIdentityInitialized=true、globalIdentityInitialized=false、memberOrBusinessGrantCreated=false、requiredObjectGrants=674；后置为实际JDBC/通过/写0/DCL0、本域原maintainer ACTIVE/version1/原证书职责、READY generation0/原空历史/BOOTSTRAP审计1与七业务身份表0。仅选一个域，假成功/错域/咨询或裁决借用/全局/成员或Grant/旧包/目标不一致拒绝。工程证据双只检准入，不冒DM8/维护/TLS。修改前用本域真实完整输入证原消费者拒绝，窄修后独立正反专项与受影响原回归；实际launcher ValidateOnly/启动另证。源码只写现owned-image准入/独立专项/现Windows工程入口，主会话独占。不写业务API/迁移或重授账号，域维护事实未齐先拒绝不改回执。

6.24独立工程包落实：44副本及2533输入与主线逐文件一致，真实offline package十三Reactor成功，测试明确未执行且原ENF/Reactor22测试继承；最终显式job-pack及两个本域公开client。Maven通用Boot插件默认legacy AgentPlatformApplication不能作为独立产品标准入口，保留原Maven输出，44新产品副本仅标准Start-Class绑定现ProductEnforcementApplication，不改编译类/资产/其他ZIP entry data bytes；正常launcher loader.main与该标准入口同向。最终JAR2063bdb7b92986e5631ba79741f6577afb0bddcb46d38f6384a17b95444bbbeb，不是已登记运行发行。manifest标准续行的初检查误拒/正确展开后核验及所有原件保留。旧源/产物/私钥不复制修改。详见enforcement-first-start44/engineering-receipt44.json；下一步仍须当前完整六schema/本域维护首次空状态/原预算和窗口，否则不初始化或启动。

6.24真实目标与传输绑定落实（不是新的业务批准）：44正常CLI候选e0e5ddf6b3caac946c333ce8b581ebde6bbd8a6918b25a053e55b7b6c1a07449、UID10002/PID35857/StartedAt=2026-10-09T23:19:28.595412637Z已核明；该确切目标才派生publisher44决定，原五公开合同/两首发不变。新EnforcementLocalTlsPublisher仅替换现已验证传输类名/确切target常量，算法/失败关闭不变；独立JAR严格三生产类、包级codesource/双向/半关闭/释放3及纯组件10均实际通过，无TLS终结/私钥/HTTP权限。guard44独立新schema v1、UID10014/5447/完整SHA及额外保护裁决4f/42；其历史40/41停止保持检查不冒执法前任或批准。当前7角色预算6.75GiB/3.85CPU，增加relay为6.875GiB/3.9CPU；readonly .25CPU存储probe已在应用启动前刷新，不得在3.85或3.9CPU时重启它。只读库存刷新可在原ff中执行，不刷新存储timestamp；后续若15分钟proof到期，失败关闭并重评安全步骤，不能借旧日期或超CPU。实际publisher执行/HTTP/登记仍分别取证。证据enforcement-first-start44/application44-actual-metadata.json、publisher44/publisher-derived-decision44.json、relay-build44.json；原CONS/ADJ构建/应用/发布均不改。

## 6.25 三个未启用执法页面的最终输入及会话边界增量D（2026-10-10）

任务FIX-ENF-ADMITTED-PAGES-FINAL-BOUNDARY-01仅重建既有ENF-12/13/17最终产品接口的页面输入与会话边界；依赖及R沿主计划既有任务，不新增业务语义、来源用途、岗位、Grant、client或启用许可。依据：docs/57对应三能力、既有enforcement-followup-v2-d-20260929详细设计、当前DocumentTemplateContract/Service、endEnforcementLogin及页面源码；本节为派生技术决定，不是外部权利确认。2026-10-10只读核明者Pascal已报告三份来源摘要匹配；全仓基线以mainline-protection-consultation45/pre-mainline-governance45原件为准。

1. 模板发布/停用分别增加用户本次填写的必填evidenceRef，原expectedRevision、URL及幂等合同不变；空/纯空白不请求，无默认值/自动生成/FUNCTIONAL兜底，不把approvalRef混用。切换模板或修订、退出或失权清输入；展示已有transitionEvidenceRef为服务记录，不声称权威性已校验。规则、法律页不增加此字段。
2. 三页调用现有endEnforcementLogin(capturedToken)。注销发起同步使会话epoch失效并清token/组织/所有业务数据和用户表单，然后等待服务端请求；在途禁止重复注销、登录或业务操作。失败保持锁定、报实际错误、不宣称服务端撤销成功、不自动重试；完成后可正常登录。401按既有helper处理。卸载后不回填。
3. 当前identity或业务/upload/download的401/403立即清屏锁定，不发后继请求。模板清列表/current/preview/fileList、上传元数据/预览/转换证据表单；规则清rules/current/history/result/attempts/receipt及映射/读取表单；法律页清result/selected/reference/history及搜索/读取/人工复核表单；三页均清组织。身份上下文未成功时不得仅凭callback token解锁；不靠无关grants[0]补组织、不新增前端授权算法。
4. 每页内部使用存活标记、单调会话epoch和当前操作标识；每次await后、后继请求前、状态/表单/下载副作用前检查当前上下文。logout/失权/卸载使旧链无效。旧成功/普通错误/401/403/catch补读/finally均不得回填、清新会话或解除新busy。下载晚响应不得创建链接/click。此机制不取消已执行服务器事务，不能据此声称服务端零副作用。
5. 独占写集恰为frontend/src/features/workflows下EnforcementDocumentTemplateV2Page.tsx、EnforcementDocumentRuleV2Page.tsx、EnforcementLegalReferenceV2Page.tsx及各自同名.finalBoundary.test.tsx（三个新测试）。不改共享auth/API helpers/routes/启用配置/backend/DB。不跑Docker/DB/Maven/verify-all/全前端测试；只在frontend执行这三测试，maxWorkers=1/no-file-parallelism。主会话独占治理集成。
6. 专项出口：分别验证真实用户输入与空白拒绝/切版清理、logout在途立即清屏及失败锁定、全部当前失权清屏、登录/identity/业务晚成功/晚失败/晚失权与旧finally隔离、上传/读取/预览/下载和规则read/history/attempts/evaluate失败补读、法律search/bind/read/history每段后继停止；原用途/字段/版本合同不变。测试mock仅在测试，不当真实权利或验收。报告实际数/失败/skip/原输出。

D范围限制：当前服务只校验evidenceRef非空并保存，未证明来源解析/存在性/版本/权利验证。本增量仅接通有权用户已持有的真实可追踪引用；不能替代真实来源或权威resolver。三页仍未启用/未登记，真实证据、正常入口、服务端撤销效果和专业验收另证；若出口要求resolver另列任务/D，禁止这六文件伪造解析或新增请求字段。上述六文件输入/注销/清屏/晚响应工程D通过，可独立实现；业务启用D不扩展。
6.25限定实现/工程验证：Pascal六路径源码及专测已停写，run05实测150/150、0失败/0跳过；主会话复核tsc -p tsconfig.app.json --noEmit exit0。首轮五处ByRoleOptions的exact:true类型失败与专测旧选择器红原件保留，实际锚定选择器修正后全过，不放宽共享类型。原服务/helper/routes/DB/应用包无改动，三页未启用/登记；真实来源/普通岗位/入口及服务端撤销实证仍未完成，非产品整项验收。

6.25当前源补证：首次后置verify-all在verify-structure拒绝三个新测试237/242/227行超过220硬限，真实失败原件保存；只对三个专测排版收敛到各216行，不删断言或减少150用例、不改checker或限值。当前run06重新实际150/150、0失败/0skip，六SHA固定，三产品页面与run05相同。主会话按当前输入再次后置验证；不以旧run05摘要冒新测试字节。
