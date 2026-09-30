# 执法材料开发输入执行证据（2026-09-27）

仅针对项目原创`ENF-MATERIAL-DEV-PRODUCT-CONTRACT-V1`。执行者为本会话Codex执法开发代理；隔离证书和成员是可追溯的开发验证身份，不代表真实执法岗位或正式审批。此文只存运行事实，状态以`docs/08-总开发实现计划.md`为准。

## 开发规则发布与执行

- `discovery-rule-dev-release.json`为`ENF-RULE-V1-DEV-MARKING-01@1`的开发配置发布，SHA-256为`cc1699aae9a1ffd7d41ba8d0045aaf6de4891bc09f351ee2c1d1192bf7d63795`，绑定未改写草稿SHA-256 `abc965701346bf2138448795058502b3f367a68f8ee426deb6394978270c8517`及第40节项目授权；发布者为`CODEX_ENFORCEMENT_DEVELOPMENT_SESSION`，不是具名执法专业或法律审核人。
- `node scripts/verify-enforcement-dev-rule-release.mjs --self-test`实际为1正4负。独立执行该脚本输出：`OBS-001@1`为`CANDIDATE`，字面起点UTF-8第33字节；`OBS-001@2`为`NO_MATCH_WITHIN_SCOPE`；缺字段`UNKNOWN`、来源不可达`FAILED_NOT_NO_MATCH`、跨来源`NOT_APPLICABLE`，业务写入0。仅形成待核材料候选，正式效力`NONE`。
- 发布目前为签入的不可变开发配置和离线执行，不是ENF-02产品发布服务、授权HTTP试跑或撤回事件；规则任务不因此结案。

## 具名开发Grant与独立水位

- 新建`agent-platform-enf-dev-db`（`127.0.0.1:5257`）和`agent-platform-enf-dev-authority`（`127.0.0.1:5258`），各有独立命名数据卷；未使用`agent-platform-con-physical-lab`或旧`agent-platform-dm8`。执法业务实例另挂`agent-platform-enf-dev-files`和`agent-platform-enf-dev-backup`。
- `ProductGrantWatermarkDm8Test`在上述两个实例实际运行1项零失败/跳过，验证缺水位与PENDING拒绝。`EnforcementDevGrantDm8Test`在同一双实例的唯一随机schema安装现有Issuer身份/独立水位迁移，以隔离CA和客户端证书登记`enf-dev-maintainer`、登记并激活`enf-dev-material-operator`，按`grant-intent.json`签发35条确切H资源-动作-用途Grant。一次实际运行的成员subject为`216ad833-2b9e-4c29-9088-1a9fad4bf0b6`，独立水位generation为36；获准PREVIEW守卫后核对317字节原件。跨组织/跨批次、撤权后、独立水位PENDING及旧业务Grant投影均拒绝；PREVIEW撤权epoch为2。隔离测试完成后临时schema、开发成员、证书文件已清理，不能称为长期已部署授权。
- 该I执行的是现有Issuer类和实际DM8事务/水位，不经过尚未编码的执法V2产品HTTP/文件端口；服务/JOB委托、真实TLS入口、双实例正文返回交错仍未验证。

## DB/文件共同快照与恢复

- 在专用业务库建立`ENF_DEV_RECOVERY_PROBE`，记录批次、原件SHA-256 `1b3042f1ecb965b302c992a7ed5bf2e00b4157259dbf56c77dd061dec2e77deb`、317字节及checkpoint epoch 1；同一执法文件卷实际保存原件并核SHA/长度。独立库`ENF_DEV_AUTHORITY_PROBE`起始epoch 1、sourceWithdrawn=N、deleteEpoch 1。
- `BACKUP DATABASE FULL`联机尝试实际报`[-8003]`缺本地或远程归档，**没有**产生有效DM8联机备份。随后关闭仅执法业务容器，在静止窗口把其`DAMENG`数据卷（约1.7G）及文件卷复制到专用备份卷；文件快照SHA与DB记录一致。独立水位库没有进入该快照。该备份是未加密的开发冷快照，不是加密/轮换的产品备份。
- 从该组快照复制到全新`agent-platform-enf-dev-restore-data/files`卷，以`--network none`启动`agent-platform-enf-dev-restore`，无宿主端口。恢复库实际SELECT读回batch、SHA、317字节和epoch 1，恢复文件实际SHA/长度相同；第一次启动中途连接失败，待DM8完成启动后重试读回成功。
- 备份后只在独立库推进epoch 2、sourceWithdrawn=Y、deleteEpoch 2。`EnforcementDevRecoveryDm8Test`连接实际执法业务/独立库读回`1 -> 2/Y/2`，核旧快照、独立水位缺失和回退拒绝，1项零失败/跳过；旧库未从备份重写独立库。拒绝逻辑是隔离恢复探针，不是产品路由响应。恢复容器仍保持无网络隔离。
- `LOST_RESPONSE`、`ORPHAN_CLEANUP`和双实例撤权交错未运行；共同产品事务回执、文件绑定/清理、来源及删除水位正式提供方、服务最小权限未就绪。冷快照I只证明静止开发探针的DB/文件可读与旧水位拒开，不能代替`VER-ENF-DEV-RECOVERY-01`完整退出条件。原生加密备份的后续执行见下节。

## 原生加密备份增量

- 只在执法业务实例启用本地归档。第一次`ADD ARCHIVELOG`因先于`ARCHIVELOG`报`[-810]`；更正挂载态操作顺序后`V$DATABASE.ARCH_MODE=Y`。首次备份又因专用备份卷归属不允许DM8进程写而报`[-136]`；将该执法卷授给容器内`dmdba`后，普通全量备份实际成功。这些失败均未作为成功证据。
- 另以本机用户DPAPI保存的随机开发口令生成`ENF_DEV_ENCRYPTED_01`，`dmrman SHOW BACKUPSET`报告`encrypt type: 2`、`AES256_CFB`，数据片约124407 KB、日志片65 KB。口令不在仓库或本记录；DPAPI同机同用户限制不等生产密钥恢复方案。
- 独立新卷中`USE_AP=2`以错误口令尝试还原报`DM[-8229]`；正确口令`RESTORE DATABASE TO '/mnt/restore'`、从同一包`RECOVER DATABASE`、`RECOVER DATABASE ... UPDATE DB_MAGIC`均成功。镜像默认入口指向另一目录，首次启动未读回；改由包内`dm.ini`在`/mnt/restore`直启的无网络`agent-platform-enf-dev-encrypted-readback`显示`SYSTEM IS READY`，实际SELECT读回同一批次、摘要、317字节和epoch 1；独立文件恢复卷实际SHA一致。
- 原生加密包只含DB，文件快照另从静止窗口复制；尚无产品业务事务水位签发的单一加密DB+文件清单、轮换与最晚删除、独立备份身份和两库同时回灌校验。旧独立水位epoch 2仍不随该包回退，因此这个增量提升DB恢复工具证据，不关闭完整恢复任务。

## D复判

`ARC-ENF-MATERIAL-DEV-D`未通过：授权是已清理的一次性隔离开发I，规则是离线配置发布而非授权产品发布/撤回，恢复只覆盖静止冷快照与技术探针，M01～M06产品C/I和内部任务评估均`NOT_RUN`。`IMP-ENF-MATERIAL-01`禁止编码。`ARC-ENF-MATERIAL-01`正式业务D的真实来源/岗位/规则/现案原件/收集链/生产恢复六项仍另行待证，不能被本开发包关闭。

## 2026-09-27 剩余场景专项增量与复判

- **ENF-02产品发布/停用：NOT_RUN。** 只读代码核查：`EnforcementDiscoveryRuleController`的发布和停用仅在`/api/v0/enforcement/discovery-rules`，由`X-V0-Actor`驱动；`EnforcementDiscoveryRuleService`及DM8 store属于旧实现。没有`/v2/enforcement`产品规则服务/持久状态可执行。新包`discovery-rule-dev-release.json`的离线`PUBLISHED_DEVELOPMENT`不能冒充产品`PUBLISHED -> DISABLED`事件或获授权HTTP请求。按本轮禁止V0及D前禁止业务编码的边界，没有执行该项。
- **备份密钥轮换：实际执行。** 在专用业务库以不同随机口令生成`ENF_DEV_ENCRYPTED_02`，`SHOW BACKUPSET`为`encrypt type 2 / AES256_CFB`。旧包的DPAPI口令尝试还原新包报`DM[-8229]`；第二把DPAPI口令对新包完成`RESTORE`、`RECOVER`、`UPDATE DB_MAGIC`。无网络直接启动的隔离读回实例实际SELECT恢复批次、原件SHA、317字节和epoch 1。首次启动因恢复卷属主不匹配报`Permission denied`，仅更正新恢复卷属主后读回成功。第一把口令仍可恢复旧包，未声称旧包已到期或密钥已销毁；两把DPAPI文件仅属本机开发身份。
- **双实例授权水位时序：实际技术I。** `EnforcementDevGrantDm8Test`在专用双DM8上以两套独立JDBC连接构造实例A/B。B先校验第35条中PREVIEW grant及generation 35；A撤回后B对旧generation的返回前校验抛`IllegalStateException`，重取当前水位后该grant仍拒绝。与`EnforcementDevRecoveryDm8Test`一起定向运行各1项、零失败/错误/跳过。该证据没有执法产品HTTP正文返回、文件下载或提交事务交错，不标完整产品双实例I。
- **产品回执与孤儿清理：NOT_RUN。** 仓库内没有通过材料开发D的V2命令/回执表、文件绑定及受控清理产品实现，不能通过旧`/api/v0`、新建假业务表或静态`recovery-protocol.json`代替。`LOST_RESPONSE`、`ORPHAN_CLEANUP`和产品两实例提交/返回均保留`NOT_RUN`。
- **本轮D结论：不通过。** 开发D的规则生命周期、产品回执/文件清理及产品两实例失败关闭仍缺实际可执行对象与证据；正式`ARC-ENF-MATERIAL-01`另缺六项真实业务证据及P5/共享D。用户同时禁止D前业务编码，因此不能在本轮通过编造V2服务跨过该门。`IMP-ENF-MATERIAL-01`继续禁码。

## 2026-09-27 开发D门槛调整后的证据归类

项目负责人明确将ENF-02 V2产品发布/停用、产品回执和孤儿清理实测移到实现C/I。上述历史复判保留原时点结论，不将`NOT_RUN`改成通过。三项均为`DEFERRED_TO_CI`：发布active/disabled的授权生命周期；断连同键同载荷回执及异载荷冲突；暂存文件绑定失败/孤儿清理故障重试和不删除已引用原件。实际V2服务、命令、文件端口尚未实现，三项的执行结果仍为`NOT_RUN`。

开发D所用实际前置证据仅为本文件前述新开发包、专用双DM8一次性具名Grant及双JDBC撤权/独立水位、离线不可变开发规则配置发布/正反试跑、独立DB/文件冷恢复与原生加密备份两钥轮换隔离读回。一次性成员清理、非产品规则发布、无产品事务共同水位及旧密钥未销毁是实现与正式启用风险，不伪称服务已验证。按docs/57第6.20节独立评审，开发D限定通过，M01～M06和正式业务D仍未通过；后继C/I必须首先实际运行三项并保留输出和失败关闭证据。

## 2026-09-27 IMP首批C/I增量（开发范围，非正式业务验收）

- 执法V2新增独立`enf3`存储、私有文件根、`/v2/enforcement`产品服务与工作面；入口由产品JWT和现时具名Grant、独立来源水位保护。输入使用本项目新开发包的两版观察与317字节原创文件，未调用`/api/v0`、Synthetic投影或历史案例。测试期间临时DM8 schema与文件根均隔离，服务测试结束清理临时用户。
- 定向命令：在工作区根执行`mvn -q -f backend/pom.xml -pl apps/platform-api -am '-Dtest=MaterialProductDm8Test,MaterialProductServiceTest,ProductEnforcementAccessTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，向`127.0.0.1:5257/5258`的专用执法业务/授权DM8传递各容器实际`SYSDBA_PWD`（未写入仓库/输出）。3项测试零失败、零错误、零跳过；I输出`source=1 rule=ACTIVE_DISABLED clue=1 receipt=CROSS_INSTANCE_REPLAY_CONFLICT orphan=REMOVED referenced=RETAINED bytes=317 secondInstance=DENIED sourceWithdrawal=DENIED`。聚合根之外运行时SQL相对路径不可用，模块单跑缺新领域依赖；这些错误调用不计业务测试结果。
- ENF-02产品服务C/I：具名开发成员获精确`EDIT_RULE/PUBLISH_RULE/DISABLE_RULE` Grant，草稿不产生线索；发布后新观察的未命中拒建、旧观察命中只生成一条待核线索；第二连接停用后第一连接新发现拒绝，原线索及规则引用保留。`enf3_event`与`enf3_receipt`随命令同事务提交。此为开发规则的产品生命周期执行，不是正式专业规则效力或法律判断。发布前正反例、字段/依据审查及多规则合并尚未成为产品强制合同。
- 回执C/I：材料注册同键同载荷重复返回同一结果，异载荷冲突；第二独立业务/授权连接在第一响应视为丢失后重取同一回执，DB核`enf3_receipt`、`enf3_event`、`enf3_material`均恰一条。绑定故障时无材料/回执；撤回独立来源水位后两连接均拒绝预览。网络真实断连、DB+文件恢复后的回执对账、HTTP认证与并发交错尚未实测。
- 孤儿清理C/I：材料绑定故障遗留的私有卷暂存文件经具名`CLEANUP/RETENTION_CLEANUP`工作Grant清理；无Grant拒绝；H2故障注入先记录`FAILED`、重试转`SUCCEEDED`，已引用317字节原件仍可预览。隔离DM8核清理事件成功3条、已引用文件保留；清理部署身份、DB/文件共同恢复后对账及并发绑定竞态仍待验证。
- 前端`npm run build`通过；`npm test -- --run`为10文件/33测试通过。这只证明编译和现有单元回归；登录浏览器到真实HTTP产品路径与M01～M06内部评估未运行，不能将`IMP-ENF-MATERIAL-01`、`ARC-ENF-MATERIAL-01`或正式B标为完成。
- 全量`mvn -q -f backend/pom.xml test`退出0；计划/证据更新前的`verify-all`因并行文件测试时效报`TEST_EVIDENCE_STALE`，全量Java重跑后`node scripts/verify-all.mjs`退出0（47基线、41需求契约、615任务、0 READY）。结构软警告不作为语义/法律正确性的证明。
- 来源撤回失败关闭补测：材料注册先核来源才暂存文件，命令提交后及人工修订返回前再核来源；独立水位改`WITHDRAWN`后第二连接以原键/原载荷重放材料命令拒绝，既有回执仍恰一条，不能借旧成功回执绕过现时来源。定向三项C/I再次零失败/错误/跳过；这一断言没有替代真实网络响应交错测试。

## 2026-09-27 MATERIAL后续C/I与HTTPS执行增量

- ENF-02发布前核验：V2发布请求须绑定两条不同、同组织同来源的已保存观察，正例必须命中、反例必须未命中，原文SHA须与保存摘要一致。`enf3_rule_review`与规则active、回执、事件同事务写入，记录观察ID/摘要/具名发布者；反转正反例被拒且审查行仍为0。停用后原审查与旧线索规则引用保留。H2 C与专用双DM8 I均实际通过；这是项目开发规则的文本候选核验，不是正式专业适用性或法律效力签发。
- M02多命中：两条经核验发布的开发规则命中同一`OBS-001@1`，`enf3_clue`仍恰1条，`enf3_clue_rule`恰2条不可变规则版本引用；第二规则不创建第二待核线索。`OBS-001@2`未命中不建线索。缺字段、跨来源、失败非空页的完整产品试跑尚未闭合，M02不标全通过。
- M03观察结构：两版实际项目原创文本各登记`OPERATOR_CANDIDATE`和`SUPPLIER_CANDIDATE`，逐字唯一定位保存4条角色候选，`business_code IS NOT NULL`计数0。HTTPS授权读取返回两角色；材料提及没有生成文件，后续317字节原件仍为`PENDING_VERIFICATION`，未生成EvidenceVersion或默认优先级。收集/核验链与内部逐字段签收未完成，M03不标全通过。
- HTTPS E2E：隔离Spring产品Issuer与真实TLS随机端口，测试证书/授权库/文件根均独立；HTTP客户端实际提交登录表单、PKCE授权码及令牌请求，再持产品JWT走`/v2/enforcement`来源、两版观察、规则发布、待核线索、人工修订、材料登记/预览。原件317字节读回；来源水位撤回后相同材料请求与预览均HTTP 403，旧`material-http`回执仍恰1。没有调用`/api/v0`。这属于网络C/E2E开发输入，不等正式B或浏览器自动化。
- M05断连/并发：TLS原始请求完整发送材料命令后主动断开响应，DB实际出现一条提交回执，带当前JWT同键重试取得原材料ID；专用双DM8两连接同键同时提交由16个持久锁桶串行化，返回同一ID且回执/事件均恰1。异载荷冲突、撤回后旧回执禁止成功也有专项I。恢复后的回执对账及产品浏览器交互仍待M06，不把M05内部评估标完成。
- 定向命令`mvn -q -f backend/pom.xml -pl apps/platform-api -am '-Dtest=MaterialProductDm8Test,MaterialProductServiceTest,EnforcementMaterialHttpsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`在执法专用5257/5258双DM8提供真实密码环境后退出0、零跳过；输出`ENF_V2_HTTPS ... receipt=DISCONNECT_REPLAY withdrawal=DENIED`及`ENF_V2_MATERIAL_DM8 ... receipt=CROSS_INSTANCE_REPLAY_CONFLICT`。前端构建通过。并行全量Java运行期间出现咨询`DevelopmentBoundaryProperties$Status`类缺失和前端咨询两例5秒超时；未修改咨询代码或把该全量失败当执法专项失败。`verify-all`后续独立一次通过619任务，但仅为结构/基线门，不代替M01～M06语义执行。

## 2026-09-27 M04授权拒绝与工作面清屏增量

- `EnforcementMaterialHttpsTest`以产品登录表单和PKCE JWT走真实TLS `/v2/enforcement`：无token登记来源401且来源表0；同一具名主体仅有错组织CONFIGURE grant时403且来源表0；仅有QUERY、无SAVE grant时观察登记403且观察表0。错组织grant通过独立水位事务签发；先前测试直接写主库导致503失败关闭，修正测试设置后重跑通过，未改生产授权逻辑。
- 317字节原件预览成功后撤回精确PREVIEW grant，原URL 403且响应不含原文；独立来源水位撤回后，旧`material-http`同键同载荷重试403、预览403，已有成功回执保持1条，材料/事件行及私有文件数量保持撤回前快照。旧回执不得跨撤回成为成功响应，是固定HTTPS回归断言。此为本机TLS/H2现时拒绝与副作用证据，专用双DM8撤回与并发另见上节，不冒称正式岗位或两实例HTTP。
- 前端材料预览401/403现在清除当前会话、规则、观察、主体、线索、修订、材料、上传选择、成功提示及待重试键；一般产品请求和退出也清同一状态。`EnforcementMaterialFlowTabs.test.tsx`的预览403组件用例1/1通过，`npm run build`通过。尚无真实浏览器端到端清屏操作和正式身份B；M04只闭合当前开发HTTP/组件断言，不标完整M04内部评估。
- 定向三项`MaterialProductDm8Test,MaterialProductServiceTest,EnforcementMaterialHttpsTest`在专用5257/5258双DM8配置下3项零失败/错误/跳过；HTTPS测试自身用隔离H2双库和真实TLS。M01实际分页/重启游标、M02缺项与源故障产品试跑、M03收集核验签收、M05恢复回执、M06产品DB+文件共同回灌/独立水位/清理对账及六项逐例内部评估仍未闭合。`IMP-ENF-MATERIAL-01`继续进行中；正式业务D与`ARC-ENF-EVIDENCE-02`不放行。

## 2026-09-27 M02只读规则试跑增量

- V2产品新增`GET /v2/enforcement/discovery-rules/{id}/trial/{observationId}`，按现时规则编辑Grant、观察读取Grant及两端来源用途/独立水位两次复核，仅返回规则/观察版本、原文摘要、`MATCH`/`NO_MATCH`/`NOT_APPLICABLE`/`UNKNOWN`和限定字面解释，不写线索、回执、事件或法定判断。现有规则工作面显示结果与观察版本/摘要；401/403仍用既有清屏路径。
- 输入为新开发包同来源`OBS-001@1`命中与`OBS-001@2`不命中。`MaterialProductServiceTest`的H2、`MaterialProductDm8Test`在专用5257/5258双库、`EnforcementMaterialHttpsTest`经登录/PKCE的真实TLS产品路由均实际执行正反试跑；试跑前后回执数不变、线索数为0，H2事件数不变。DM8撤回独立来源水位后试跑抛`SecurityException`，未变成`NO_MATCH`。三项定向测试零失败/错误/跳过；`EnforcementRuleTab.test.tsx`和既有预览403组件测试2/2通过，前端构建通过。
- 尚缺跨来源观察、缺字段和来源不可达的完整产品解释试跑、浏览器操作及具名项目内部评估；不能把接口枚举或离线反例充作这些执行。M02仍部分完成，M01、M03～M06状态不因本项提升。

## 2026-09-27 M01产品分页/重启游标执行增量

- V2新增服务器配置的HTTPS源页端口和`POST /v2/enforcement/sources/{id}/syncs`；请求不能提交任意目标地址。`enf3_sync_cursor`以`START`开始，逐记录在同一业务事务内保存观察、幂等回执/事件及下一个连续游标；源页读取在事务外。源限流/不可达是503，不是空页；页内错误返回`PARTIAL_FAILED`与最后连续位置。新增工作面“同步一页”并显示起止游标、记录数和失败码，不复用`/api/v0`。
- 专用执法业务/授权DM8（5257/5258）的`MaterialProductDm8Test`从项目新开发包两条原创观察字节通过本机真实TLS源服务读取两页：首位置`START→p1-1`，第二页先保存`OBS-001@2`、重放`OBS-001@1`后第三位置失败，游标只到`p2-2`；重新构造业务/授权JDBC连接、仓储和服务实例后，源HTTP 429拒绝且游标保持`p2-2`，再试成功到`p2-3`。最终DB观察2、OBSERVE回执2、事件2、线索0；同源版本重复没有新增观察或事件。输出`ENF_V2_M01_HTTPS ... partial=SOURCE_RECORD_FAILURE rateLimit=429 restartCursor=p2-2 finalCursor=p2-3 observations=2 receipts=2 events=2 clues=0`。该测试无跳过。
- `EnforcementMaterialHttpsTest`在产品表单登录/PKCE JWT后实际调用V2同步路由，外部源仍经独立TLS socket；首响应`CONTINUE`、第二`PARTIAL_FAILED`、429为503、重试`SUCCEEDED`，DB游标`p2-3`和观察2，后续规则/线索/修订/原件链继续通过。三项执法定向测试零失败/错误/跳过；前端构建和2项执法组件测试通过。开发输入仅替换原文，产品同步协议/状态路径相同；这不是第三方源的正式许可或真实来源I。
- 仍缺持久`SyncAttempt`及逐条失败审计、源连接凭据Ref/配置版本、停用在途策略、真实源适配和具名内部漏项评估；通用部署端点配置与正式来源许可也未验。因此M01分页/重启游标C/隔离I/HTTP子项有证，M01完整内部评估及最终开发验收仍未通过。M02～M06剩余项不因本次自动提升。
- 追加真实空页：源在`p2-3`返回HTTP 200、空entries和complete，V2结果`SUCCEEDED`/processed 0，DB游标仍`p2-3`且观察/回执/事件数维持2；与前述429=503区分。连接器只接受部署配置的HTTPS源，禁止客户端提交目标URL；响应流最多2MB、1000条，超限拒绝。
- 并行共享授权收紧后，`ProductGrants.issue`禁止通用`SOURCE`；执法领域调用仍为`SOURCE`动作，但适配器只查精确`ENFORCEMENT_SOURCE`资源类型，定向测试Grant也按该类型发行，不改咨询授权服务。第一次汇总受并行编译产物缺失`MaterialProductServiceTest$1`影响失败；单项重跑后四项`MaterialProductDm8Test,MaterialProductServiceTest,EnforcementMaterialHttpsTest,ProductEnforcementAccessTest`在专用双DM8配置下零失败/错误/跳过。此映射须在未来正式Grant配置与任务审查时保持一致，旧开发grant意图不能自动充新资源。

## 2026-09-27 M03收集与核验C/隔离I增量

- V2材料登记在原件事务写`UPLOAD`动作，仍为`PENDING_VERIFICATION`。另设具名收集者`COLLECT_ATTEST`（原件来源定位、方式、摘要、版本）和另一具名主体`VERIFY`（再次读取实际文件并比摘要），动作追加不可覆盖；工作面及`/v2/enforcement/materials/{id}/collection|verification|actions`均使用当前产品入口，不生成EvidenceVersion或法定采信。每个命令核当前材料用途、精确Grant、来源独立水位、实际文件字节，回执/事件与动作同事务。
- H2 C使用新开发包317字节原件：无收集链核验、非申报收集者确认、同人核验及旧版本均拒绝，禁止请求回执0；有效收集/独立核验动作依次为`UPLOAD,COLLECT_ATTEST,VERIFY`，最终材料`VERIFIED`，同键收集重放同一回执。专用5257/5258双DM8 I以项目Issuer两具名主体、精确版本Grant及真实文件再跑收集/核验，输出`ENF_V2_M03_DM8 upload=UNVERIFIED collection=ATTESTED verifier=INDEPENDENT actions=3 forbiddenReceipt=0`；授权旧v1的预览在材料升v3后拒绝，清理仍保留被引用原件字节。组合`MaterialProductDm8Test,MaterialProductServiceTest,EnforcementMaterialHttpsTest`零失败/错误/跳过，前端构建和材料组件1项通过。
- 补登录/PKCE后真实TLS V2 HTTP：`/materials/{id}/collection`首次及同键重放均200，`/actions`读出UPLOAD与COLLECT_ATTEST两条；上传/收集者本人`/verification`为409，`self-verify-http`回执0。随后撤回来源，旧材料回执重放和预览仍403、材料/事件/文件数不增；`EnforcementMaterialHttpsTest`单项退出0。异人核验HTTP、撤回时序中的新动作、文件摘要漂移和具名逐字段内部评估仍未通过，M03部分完成。`verify-all`曾遇DM8测试416行硬限（已抽测试辅助类）及并行计划`TEST_EVIDENCE_STALE:project`，最新复核退出0；不改咨询证据求绿。

## 2026-09-27 M01持久同步尝试与故障码增量

- V2 `enf3_sync_attempt`在源页请求前落`RUNNING`，完成/局部失败/异常后分别持久结束水位、处理数、失败码和失败位置；来源429经结构化`RATE_LIMITED`，HTTP其它失败、IO、坏页有不同代码，异常仍由产品入口返回503。逐条成功仍由观察+OBSERVE回执/事件和游标同事务留痕；该表不使失败页变成空页或推进游标。
- `MaterialProductDm8Test`专用5257/5258双DM8加断言：五次实测调用有五行尝试，局部失败行`p2-2/SOURCE_RECORD_FAILURE`，429行`p2-2/RATE_LIMITED`；重建连接后最终游标`p2-3`，观察/回执/事件各2、线索0。登录PKCE后TLS原产品同步与材料链回归通过；两项定向测试退出0、零跳过。仍缺连接凭据Ref/配置版本、停用在途策略、逐记录故障详情与具名业务漏项评估，M01不能判全过。修改前`verify-all`因并行`TEST_EVIDENCE_STALE:project`截断，专项独立留证。

## 2026-09-27 M02跨源/缺字段/来源故障C增量

- H2 `MaterialProductServiceTest`在项目新观察文本外另登记第二具名来源与同文字面观察，规则试跑返回`NOT_APPLICABLE`；空原文字段的观察注册抛`IllegalArgumentException`、无回执；来源权威端不可用时试跑抛`SecurityException`，不把不可用解释成未命中。试跑前后线索为0、DISCOVER回执0，只有第二来源和其有效观察增加各一条回执/事件。单项退出0，变更前`verify-all`退出0。专用DM8/登录HTTP的这组三反例和具名专业误漏报审阅尚缺，M02仍部分完成。

- M03再补失败关闭C：对已登记原件的私有`.blob`实际改写字节后，收集确认因SHA漂移抛`IllegalStateException`，动作数不变；恢复原字节后确认成功。随后模拟独立来源不可用，异人核验抛`SecurityException`、材料版本停在2，恢复现时水位后才核验为v3。`tampered-collect`与`withdrawn-verify`均零回执，单项H2测试及变更前`verify-all`退出0。此为开发C，不替代专用DM8/HTTP撤回交错或业务人工评估。

- M01源故障码边界：在专用双DM8产品同步入口再注入不符合`[A-Z][A-Z0-9_]{0,79}`的来源故障码，页内处理前拒绝，尝试表记`FAILED/PAGE_MALFORMED`，游标仍`p2-3`且观察/OBSERVE回执维持各2。`MaterialProductDm8Test`单项零失败/跳过、变更前`verify-all`退出0；不冒称连接配置或停用在途已完成。

## M01～M06逐例内部技术评估（未作业务签收）

| 场景 | 已执行事实与禁止副作用 | 尚缺，不能判最终通过 |
|---|---|---|
| M01 | 独立TLS两页、局部失败、429、重建连接、真空页及畸形故障码；专用DM8游标`p2-3`，有效观察/回执/事件各2、线索0，六次尝试中畸形码拒绝不推进 | 来源连接凭据Ref/配置版本、停用在途处理、逐记录失败详情和具名漏项复核 |
| M02 | 开发包两版草稿试跑MATCH/NO_MATCH零业务写；H2跨源NOT_APPLICABLE、缺文本零回执、来源不可用拒绝而非未命中；发布前正反审查及双规则同观察单线索已在专用DM8/TLS实测 | 跨源/缺项/故障的专用DM8及HTTP组合、具名专业误报漏报复核 |
| M03 | 项目原创317字节原件先UPLOAD未核验；H2和专用DM8具名收集/异人核验三动作，H2冒名/同人/旧版/摘要篡改/撤回禁止回执0；登录TLS收集重放与同人核验409、回执0 | 异人登录HTTP、撤回交错与文件漂移的隔离I、具名逐字段材料和收集链签收 |
| M04 | 复用既有登录TLS无凭据/错组织/错用途/撤Grant/来源撤回拒绝，旧成功回执不得重放为成功且DB/文件零新增；前端预览403清屏组件通过 | 实际浏览器操作和两独立产品HTTP实例时序 |
| M05 | 原始TLS断连后同键取回、异载荷冲突、双DM8连接并发单回执/单事件；2026-09-28真实产品DB+原件冷恢复后同键仅原ID、1回执/1事件，独立来源撤回后旧回执拒绝且无新增 | 双HTTP实例交错、具名内部评估 |
| M06 | 2026-09-28实际`enf3_*`产品库2.3G冷快照及317字节原件同静止窗口复制到新卷，隔离恢复库`VERIFIED` v3、三动作、SHA一致；恢复后孤儿清除/引用文件保留、缺独立权威拒启动、旧来源撤回拒重放 | 冷副本未加密、独立删除水位/保留合同及双实例启动交错未证；不能判最终通过 |

这是开发代理的逐例技术复核，不是具名执法业务/法律人员的专业B，也不是`IMP-ENF-MATERIAL-01`最终验收。正式业务D仍独立阻塞；下一步保持本任务CURRENT，先完成M05/M06产品共同恢复，再补M04双实例浏览器与M01/M02/M03余例。

## 2026-09-28 M05/M06真实产品共同物理恢复

- 备份对象为`MaterialProductDm8Test`通过V2服务实际提交的随机`EMF0B576151AE3B3CD` schema：材料`4ac65570-32f5-46d0-922b-80785bb6c4ea`处于`VERIFIED` v3，动作`UPLOAD/COLLECT_ATTEST/VERIFY`，`material-1`回执与事件各1；原件键`027d8085-14d3-4b8c-8530-f3d2f3aa419a`，317字节、SHA-256 `1b3042f1ecb965b302c992a7ed5bf2e00b4157259dbf56c77dd061dec2e77deb`。不是早期`ENF_DEV_RECOVERY_PROBE`。测试事务在提交后暂停，未执行后继规则停用或来源撤回；独立5258授权库不在业务快照内。
- 静止窗口仅停止`agent-platform-enf-dev-db`，将其实际数据卷拷到`agent-platform-enf-v2-product-backup-20260928`（约2.3G），随后立即重启原业务容器；备份再复制到全新`agent-platform-enf-v2-product-restore-db-20260928`。同一暂停窗口从产品私有文件根取精确`.blob`，复制到`agent-platform-enf-v2-product-restore-files-20260928`，在卷内重新核长度317与上述SHA。恢复容器`agent-platform-enf-v2-product-restore-20260928`独立端口`127.0.0.1:5267`，原业务5257、独立权威5258，未使用咨询容器/卷/端口。该冷快照未加密，不能冒称已完成加密备份与保留治理。
- `MaterialProductRecoveryDm8Test.restoredProductReceiptFileAndIndependentSourceReconcile`实际连接恢复库和未恢复的独立权威：读回`VERIFIED` v3与三动作、实际文件SHA；V2服务以原键/原载荷重放返回原材料ID且回执/事件仍各1、材料行1。恢复卷中新暂存孤儿过期后清理并留`SUCCEEDED`，被引用原件字节不变；独立源水位撤回后旧回执重放及动作读取均拒绝，回执/事件/文件数均无新增。输出`ENF_V2_PHYSICAL_RESTORE ... orphan=REMOVED referenced=RETAINED withdrawnReplay=DENIED receipts=1 events=1`，该单项`mvn ... surefire:test`退出0。
- `MaterialProductRecoveryDm8Test.restoredProductRejectsUnavailableIndependentAuthorityWithoutEffects`将独立权威指向不可达端口；`ProductGrantWatermark`在产品入口初始化时即失败关闭，恢复库回执/事件仍各1、文件数2，定向`mvn ... test`退出0。初次把失败断言放在服务命令阶段而实际在构造阶段被拒，已修正并重跑。
- 编排生产者第一次默认回归通过；暂停快照后恢复者推进独立来源撤回，生产者释放时后续规则停用因现时水位拒绝、退出非零，其`finally`清除随机schema。这是恢复交错预期，测试专属暂停分支已收窄为释放后直接清理；无暂停的普通`MaterialProductDm8Test`随后再次零失败/跳过。验证编译曾被并行咨询测试的错误截断，直接运行已编译执法测试并重装执法依赖后恢复用例通过；未修改咨询代码。
- 本次关闭M05恢复后同键回执/状态对账以及M06开发冷快照产品DB+文件共同恢复、孤儿/引用文件和缺权威/旧来源水位反例；仍未证明加密、删除水位/保留合同、双HTTP实例及业务B。因此`IMP-ENF-MATERIAL-01`继续进行中，不能标最终开发验收。

## 2026-09-28 M04双实例V2 HTTPS增量

- 预期：同一具名登录/PKCE产品JWT和独立授权水位下，两个真实TLS产品实例读取同一317字节原件；撤回现时精确PREVIEW Grant后，第二实例原URL 403且不含原文字面，第一实例也403；回执、材料、事件、文件不新增。旧v1授权不应跨材料收集后的v2版本生效。
- 实际：`EnforcementMaterialHttpsTest`在第一个Spring TLS实例完成V2来源、观察、规则、线索、修订、原件和收集事务，撤v1 PREVIEW后按授权epoch签发v2 PREVIEW；`MaterialSecondHttpsInstance`以独立Spring/Tomcat/Hikari连接同一隔离H2业务/授权库及私有文件根，持原产品JWT GET预览返回200且字节与开发包317字节一致。第一个实例撤v2 Grant，第二实例相同HTTP请求403/空正文；第一个实例同URL随后403。独立来源水位撤回后旧`material-http`同键命令仍403，原成功回执1，材料/事件/文件数维持撤前快照。无`/api/v0`、Synthetic投影或历史材料。
- 定向`mvn -q -f backend/pom.xml -pl apps/platform-api -am '-Dtest=EnforcementMaterialHttpsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`退出0，1测试、0失败/错误/跳过；失败的首次编排分别揭示第二实例默认测试profile混用和旧材料版本Grant拒绝，修正测试隔离配置及授权epoch后重跑通过，未改产品授权逻辑。此为双HTTP实例同进程、隔离H2的网络C，不是两独立部署进程/DM8交错，也不是实际浏览器操作；M04完整内部评估仍未通过。记录前`verify-all`先通过一次，后一次被共享计划`TESTS_FAILING:project`截断，不修改咨询/共享项目以求绿灯。
- 当前顺序复判：M05/M06真实`enf3_*`共同物理恢复与回执/状态对账已有上一节执行证据，但冷副本未加密且删除水位/保留批准未证；M04真实浏览器及两独立部署进程仍未证；M01～M03余反例与具名内部评估未执行。`IMP-ENF-MATERIAL-01`保持进行中，MATERIAL最终开发验收不通过，不进入`ARC-ENF-EVIDENCE-02`。

## 2026-09-28 M04真实Chromium产品入口E2E

- 预期：浏览器经产品`/app/enforcement/material`与Issuer登录/PKCE取得会话，V2 GET读取现时v2材料并预览实际317字节原件；另一个真实HTTPS实例在同一Grant下读原件。撤v2 PREVIEW后第二实例403且无正文，浏览器原按钮再次预览收到403，立即清空材料和会话；既有回执不得重放成功，DB材料/事件及文件数不增。
- 实际：补齐已有V2页面的SPA直接访问/OAuth回跳路由；以`-Pbundle-frontend`构建带`enf-http-client`的真实前端，Playwright Chromium实际打开随机TLS产品端口，填写项目Issuer登录表单并完成浏览器PKCE，进入材料Tab，以材料ID实际读取并点击预览，网络响应200。测试在浏览器待机时让独立Spring/Tomcat/Hikari的第二TLS实例先200读回相同317字节，再由第一实例撤回精确v2 PREVIEW Grant；第二实例同URL403空正文。恢复浏览器后再次点击原预览按钮，页面显示授权不可用、已无材料ID正文并回到登录状态。主实例随后403，来源撤回后旧`material-http`同键请求403，回执仍1、材料/事件/文件计数及文件数不增。没有调用`/api/v0`，也未使用旧SPA/Synthetic或历史材料。
- 命令设置`VITE_ENFORCEMENT_PRODUCT_CLIENT_ID=enf-http-client`及`ENF_BROWSER_E2E=1`，运行`mvn -q -f backend/pom.xml -pl apps/platform-api -am -Pbundle-frontend '-Dtest=EnforcementMaterialHttpsTest,EnforcementMaterialFrontendRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`退出0，两项测试零失败/错误/跳过，浏览器输出`ENF_BROWSER_WITHDRAWAL_CLEARED`。浏览器已实测但双实例在一个JVM、共享隔离H2；专用DM8两个独立部署进程的撤回中响应时序仍未证，不作完整M04签收。全局`verify-all`在更新前一次被文件401行结构门截断，缩至400行后又被并行`TEST_EVIDENCE_STALE:project`截断，未改咨询或其他项目。

## 2026-09-28 M01～M03顺序复核及最终开发验收复判

- M01预期/实际：专用执法业务5257、独立授权5258及真实TLS源页两页，在页内失败后只提交连续位置，429不冒充空页，重建连接后从`p2-2`到`p2-3`；本轮`MaterialProductDm8Test`实际再次输出`observations=2 receipts=2 events=2 clues=0`，故障未增线索、重复源版未复制观察/回执/事件。连接凭据Ref/配置版本、停用在途和逐记录故障详情尚无完整产品执行，不能称M01全部通过。
- M02预期/实际：草稿跨源`NOT_APPLICABLE`、缺文本拒绝且`missing-text`回执0、来源权威不可达拒绝而非`NO_MATCH`；本轮`MaterialProductServiceTest`的H2服务C重跑退出0。专用DM8和登录HTTP的跨源/缺字段/来源故障组合，以及具名专业误报漏报复核未执行；只承认既有发布正反核验和产品试跑证据。
- M03预期/实际：实际317字节项目原件先`UPLOAD`未核验，具名收集和异人核验动作依次追加、冒名/同人/旧版/摘要漂移/来源不可达无禁止回执；本轮专用DM8实际输出`upload=UNVERIFIED collection=ATTESTED verifier=INDEPENDENT actions=3 forbiddenReceipt=0`，H2负例同时重跑退出0。异人登录HTTP、撤回交错/字节漂移的隔离DM8组合和具名人员逐字段签收仍缺。
- 命令从两个**执法专用**DM8容器的非输出环境取测试口令，设置`ENF_DEV_DB_URL=jdbc:dm://127.0.0.1:5257`、`ENF_DEV_AUTHORITY_URL=jdbc:dm://127.0.0.1:5258`，运行`mvn -q -f backend/pom.xml -pl apps/platform-api -am '-Dtest=MaterialProductDm8Test,MaterialProductServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`退出0，两项测试零失败/错误/跳过；随机schema在finally清除，口令未写入仓库或证据。更新前`verify-all`退出0。
- **复判：不通过MATERIAL最终开发验收。** M05/M06已有产品DB+原件共同物理恢复与回执/状态对账，但未加密冷副本、独立删除水位/保留批准和双部署启动交错未证；M04浏览器与双Spring HTTP已证但非双独立DM8部署；M01～M03上述剩余反例与具名内部评估未闭合。项目不能由开发代理冒签专业或业务评估，正式业务D仍独立未过。`IMP-ENF-MATERIAL-01`保持进行中，不进入`ARC-ENF-EVIDENCE-02`；用户当前禁止新增功能，故不能为填这些实现缺口擅自扩编码。

## 2026-09-28 M05/M06 V2产品共同加密归档及隔离读回

- 预期：已有静止窗口实际`enf3_*`产品DB与确切原件必须进入**同一**加密备份包；错误密钥不可解密，正确密钥到全新卷可读回材料状态/版本、动作、回执/事件与原件SHA；独立现时权威不随包恢复，缺失时产品入口拒绝且DB/文件无副作用。
- 实际：以原产品冷备份卷`agent-platform-enf-v2-product-backup-20260928`及同一共同恢复来源的私有原件卷只读输入，`cpio`把DB与文件装入单一流，`gzip`后用OpenSSL AES-256-CBC/PBKDF2(200000轮、随机salt、进程内随机口令)输出到新执法卷`agent-platform-enf-v2-product-encrypted-20260928`。密文83 MB，SHA-256 `1cf62c554f054fbf7287f07b01c79a37e9828522c5bb8c71814501659d85e5e8`；错误口令退出非零，未提取文件；正确口令解到另两新卷，DB 2.3 GB，确切317字节`.blob`摘要`1b3042f1ecb965b302c992a7ed5bf2e00b4157259dbf56c77dd061dec2e77deb`。密钥不持久化，所以这是一次性可验证加密演练，不是可长期恢复的受控密钥托管或认证加密完整性方案。
- 新容器`agent-platform-enf-v2-product-decrypted-20260928`独立`127.0.0.1:5268`从解密DB卷正常启动。对该恢复库/文件重新实际执行`MaterialProductRecoveryDm8Test.restoredProductRejectsUnavailableIndependentAuthorityWithoutEffects`，断言真实材料`VERIFIED` v3、`UPLOAD/COLLECT_ATTEST/VERIFY`三动作、文件SHA一致、`material-1`回执/事件各1；故意指向不可达独立授权库时`ProductGrantWatermark`拒产品入口，回执/事件/文件仍各1/1/1。该测试退出0、零跳过，输出`ENF_V2_PHYSICAL_RESTORE authority=UNAVAILABLE replay=DENIED receipts=1 events=1 fileCount=1`。此前同一冷点在未加密物理恢复的正向产品服务对账另见上一节；此次解密实例因原一次性独立权威schema已清理，**未**重跑正向授权回执，不把直接数据读回冒充当前授权成功。
- 开发保留基线暂按已有产品合同：项目原创包仅隔离开发用途，材料`expiresAt`由实际登记命令提供且过期不得读取/核验，暂存孤儿按现有10分钟清理逻辑；备份卷只供本次开发演练，不获得正式保留/删除批准。正式业务对象期限、法律保全/审计例外、备份销毁与密钥托管仍由正式业务D确定。现有产品没有可执行的独立**材料删除**水位或其受权删除命令；仅直接改测试库制造水位是技术探针，不能算M06。故加密子项有真实产品包证据，但删除水位/保留执行及M05/M06最终通过仍为未完成。

## 2026-09-28 M06独立删除水位失败关闭增量（非删除验收）

- 预期：专用执法业务/权威双DM8中来源仍`READY`而独立删除水位为`DELETED`时，旧`material-1`成功回执不得重放为成功；回执、事件、材料、私有文件均无新增。实际：`MaterialProductDm8Test`在真实V2服务事务后由测试直接切换独立`enf3_delete_watermark`，第二业务连接同键提交抛`SecurityException`；原有回执15、事件15、材料2、文件5保持不变，随后来源撤回回归仍拒绝。`MaterialProductDm8Test,ProductEnforcementAccessTest`在5257/5258专用DM8定向运行退出0、零跳过，输出`ENF_V2_DELETE_DM8 source=READY delete=DELETED oldReceipt=DENIED receipts=15 events=15 materials=2 files=5`。
- 此次切换由测试直接更新独立表，**仅证明产品拒旧回执的水位守卫**，不是受权删除命令、旧业务备份回灌后独立水位不回退、文件删除或保留策略执行。开发基线只限定项目原创开发输入随来源/材料`expiresAt`拒用和暂存孤儿满10分钟清理；加密归档是一次性演练，未形成可长期恢复的密钥托管/认证完整性或备份到期销毁。正式保留期限及例外批准继续留正式业务D。M05/M06整体不通过，IMP仍进行中。
- 开发期限预期/实际：在同一专用DM8已登记原件上以产品服务的前进时钟越过登记的`expiresAt`，原件预览抛`SecurityException/Material use unavailable`；回执15、事件15、私有文件5不变。`MaterialProductDm8Test`单项再次退出0、零跳过，输出`ENF_V2_RETENTION_DM8 expired=DENIED receipts=15 events=15 files=5`。这是开发用途到期拒读，不证明正式业务期限、到期物理删除或备份销毁。

## 2026-09-28 M06冻结旧备份回灌I

- 预期：真实V2产品事务的旧业务DB/全部文件在同一静止点恢复到隔离实例，独立现时权威不随备份回退；当前授权下原同键回执及收集动作可对账。之后来源仍`READY`而独立删除水位前进为`DELETED`时，恢复实例的旧回执及受限动作均拒绝，两个连接无新增DB/文件；权威不可达时入口失败关闭。
- 实际：`MaterialProductDm8Test`在专用5257/5258生成随机schema中的`VERIFIED` v3原件、三动作和`material-1`回执后暂停；仅停止5257，冷拷实际DB卷至`agent-platform-enf-v2-m06-old-backup-03-20260928`，同时拷完整私有文件根5份317字节文件；5257立即重启。备份卷再拷到全新`agent-platform-enf-v2-m06-old-restore-03-20260928`，独立DM8监听`127.0.0.1:5271`，5258权威未拷。恢复测试逐文件比对检查点字节，原件SHA `1b3042f1ecb965b302c992a7ed5bf2e00b4157259dbf56c77dd061dec2e77deb`；原同键回执返回原材料ID，`UPLOAD/COLLECT_ATTEST/VERIFY`仍三动作、回执/事件各1。正向回执暂存多一孤儿后取副作用基线为文件6。测试在独立5258将该来源删除水位从既有epoch加1并置`DELETED`，来源仍`READY`；两个重建连接对旧同键同载荷均抛`SecurityException`，具名核验者动作读取也拒绝；回执1、事件1、材料1、文件6及引用原件字节不变。输出`ENF_V2_OLD_BACKUP_DM8 source=READY deletion=DELETED oldReceipt=DENIED instances=2 receipts=1 events=1 materials=1 files=6`。另指向不可达权威的入口构造失败关闭，回执1/事件1/文件6不变；两项各零失败、零跳过，生产者释放后退出0。
- 前两次演练没有计为通过：首次测试把已有epoch 1错写成期望1而实际推进为2；第二次只拷引用原件而非完整文件根，且将正向回执的暂存文件计入错误基线。测试改为比较原epoch+1、逐文件对账完整根并在正向对账后取禁止副作用基线，第三次新schema/新卷全程退出0。检查点含临时凭据，仅在本机临时目录，未签入或输出。独立水位由测试直接推进以制造冻结的`OLD_BACKUP_EPOCH`反例，不宣称受权删除命令、实际文件销毁或正式保留批准。这里两个连接不是M04要求的两个独立部署HTTP进程；M06开发I该子场景通过，M01～M06整体验收未复判。

## 2026-09-28 M01～M04冻结反例专项复跑

- M04预期/实际：真实Chromium以产品HTTPS登录/PKCE，V2材料原件预览200，撤现时Grant后第二Spring TLS实例403空正文，浏览器再试403清会话/受限内容，旧同键回执与DB/文件不增。`-Pbundle-frontend`的`EnforcementMaterialHttpsTest,EnforcementMaterialFrontendRouteTest`本轮退出0、零跳过，输出`ENF_BROWSER_WITHDRAWAL_CLEARED`、`ENF_V2_DELETE_HTTP ... read=403 receipts=9 events=9 materials=1`。两实例仍同JVM/H2，不冒充独立部署DM8 HTTP时序。
- M01预期/实际：项目实际观察`OBS-001@1`相同记录键/版本而正文漂移经专用5257/5258 DM8产品同步返回`PARTIAL_FAILED/INVALID_SOURCE_RECORD`，游标仍`p2-3`，观察2、回执3、事件3均不增；尝试表单独记录该失败与末连续游标，缺码/429/空页和两页重启原路径同跑。输出`ENF_V2_M01_DRIFT_DM8 cursor=p2-3 failure=INVALID_SOURCE_RECORD observations=2 receipts=3 events=3`。不是第三方源正式许可。
- M02预期/实际：第二项目开发来源具名Grant登记后，缺文本观察拒绝且`missing-text`回执0；同字面跨源观察对原规则只读试跑为`NOT_APPLICABLE`，撤该来源现时水位后试跑抛`SecurityException`，而非`NO_MATCH`；试跑前后回执16、事件16、线索1不增。输出`ENF_V2_M02_DM8 cross=NOT_APPLICABLE missing=REJECTED withdrawn=DENIED receipts=16 events=16 clues=1`。本组是隔离DM8产品服务I，跨源HTTP组合尚未单独取证。
- M03预期/实际：专用DM8私有卷上将实际已登记317字节原件首字节改写，具名收集确认抛`IllegalStateException`，动作仍只`UPLOAD`、`tampered-collect-dm8`回执0；在`finally`恢复原字节后同键收集重放与异人核验完成`UPLOAD/COLLECT_ATTEST/VERIFY`三动作、最终`VERIFIED`。输出`ENF_V2_M03_DM8 ... tampered=DENIED ... actions=3 forbiddenReceipt=0`。异人登录HTTP仍未单独运行。
- M01～M03定向`MaterialProductDm8Test,MaterialProductServiceTest`在专用5257/5258双DM8退出0、零跳过；随机schema清理。`verify-all`在证据更新前退出0（47基线、41需求、619任务），只作结构/基线检查。M04独立部署、剩余HTTP组合及具名内部评估未结，IMP仍进行中。

## 2026-09-28 M02/M03登录后V2 HTTPS组合反例

- M02预期/实际：同一具名产品Issuer/PKCE token在`/v2/enforcement`登记第二开发来源，缺文本观察POST 409且禁止回执0；同字面跨源观察的草稿试跑HTTP 200/`NOT_APPLICABLE`；独立来源水位撤回后原试跑URL 403、响应无原文。试跑前后回执6、事件6、线索0不增，输出`ENF_V2_M02_HTTP cross=NOT_APPLICABLE missing=409 withdrawn=403 receipts=6 events=6 clues=0`。没有使用`/api/v0`。
- M03预期/实际：先由操作员登录确认原件收集，同人核验409且`self-verify-http`回执0；另登记、激活具名`enf-http-independent-verifier`并授材料v2精确VERIFY和v3 READ Grant，以独立cookie/PKCE登录后POST核验HTTP 200，GET动作HTTP 200共`UPLOAD/COLLECT_ATTEST/VERIFY`三条，产品材料`VERIFIED` v3且私有原件317字节不变。输出`ENF_V2_M03_HTTP login=PKCE verifier=INDEPENDENT state=VERIFIED actions=3 selfReceipt=0 original=UNCHANGED`。旧操作员v2 READ撤回、精确v3 READ授予后浏览器读材料/预览与撤PREVIEW清屏继续通过；旧回执/材料/文件零新增、最终`ENF_V2_DELETE_HTTP ... receipts=12 events=12 materials=1`。
- 初次重跑被并行咨询Task Goal新增必填配置阻断执法隔离Spring上下文；只移除执法测试装配中无关`ProductConsultationTaskConfiguration`导入，不改咨询。首次异人核验后的浏览器因操作员只有v2 READ未到达v3材料；第二次试图直接授v3 READ被现时epoch拒绝；按产品合同先撤旧v2 READ再授精确v3 READ，最终两项带真实前端打包/Chromium定向测试退出0、零跳过，输出`ENF_BROWSER_WITHDRAWAL_CLEARED`。这些失败尝试不计通过证据。`verify-all`本次证据更新前通过。M04独立部署DM8 HTTP与具名内部评估仍缺，IMP不判最终通过。

## 2026-09-28 M04双独立部署DM8与具名技术复核

- `MaterialIndependentDm8HttpTest`在执法专用5257业务/5258独立授权DM8中创建随机schema和私有文件根，启动测试JVM及由当前产品jar启动的第二独立JVM。产品Issuer登录/PKCE取得token，V2来源→观察→规则发布→线索→人工修订→317字节原件提交后，两HTTP实例预览均200且字节相同。撤确切PREVIEW Grant后第二及第一实例依次403、响应无原文；独立来源撤回后第二实例重放旧材料命令403。禁止副作用基线与实际一致：回执8、事件8、材料1、文件1。输出`ENF_V2_M04_INDEPENDENT_DM8 jvms=2 business=5257 authority=5258 before=200/200 after=403/403 oldReceipt=403 receipts=8 events=8 materials=1 files=1`。随机schema及第二进程由测试清理；这不是V0或Synthetic。此前Chromium/PKCE撤权清屏证据仍有效。
- 首次启动缺随机授权schema具名维护者，第二次真实DM8授权码BLOB比较返回500，均不计通过。测试夹具补维护者；产品`ProductAuthorizationService`只在实际DM8 JDBC上将授权码BLOB转换查询后交回原Spring授权服务按ID读回，H2维持原路径。当前jar重建后`MaterialIndependentDm8HttpTest,ProductIdentityDm8SchemaTest`在专用DM8两项退出0、各1测试/零跳过；`EnforcementMaterialHttpsTest,MaterialProductServiceTest` H2两项退出0、零跳过。旧版H2直接CAST失败不计通过。未修改咨询产品。
- 具名内部技术评估者：**Codex（本会话执法开发技术复核，2026-09-28）**。依据本文件实际输出与`docs/57`第6.12节逐例比对：M01的两页/局部失败/429/空页/同源正文漂移/重启游标均有专用DM8结果，故障不越游标且观察/回执/事件零禁止新增；M02草稿零线索、发布版同源多规则仅一线索、跨源/缺文本/源撤回在DM8及登录HTTPS拒绝且零禁止回执；M03双角色原文定位、仅提及无原件不成证、字节漂移/同人拒绝与异人登录核验三动作已实测；M04本节双独立JVM及既有浏览器清屏通过；M05同键异载荷、断连重试、双DM8并发及恢复回执单提交已有执行证据；M06实际产品DB+全文件旧备份、加密归档、孤儿/引用文件、独立删除epoch不倒退和缺权威失败关闭已有执行证据。此为证据核对而非执法人员、数据责任人或法律专家的B签收。
- **复判：`IMP-ENF-MATERIAL-01`仍进行中，最终开发验收未通过。** 冻结产品来源合同`docs/57`第6.1/6.6节已明确受控连接凭据Ref、配置版本、停用在途策略和逐记录失败细节；当前M01只有分页/尝试汇总与故障码执行证据，不能将缺口判通过。M06正式保留批准和M01～M03专业/专家签收留给正式业务D/B，不把开发基线冒充正式通过。受权删除命令不在当前合同，不新增或用测试水位推进冒称其执行。不得以本技术复核放行`ARC-ENF-EVIDENCE-02`；需按冻结范围补M01实现/反例后再作唯一最终验收复判，新增需求另立任务。

## 2026-09-28 M01冻结凭据Ref执行增量

- V2来源登记现要求`credentialRef`，业务表只存Ref；部署受控HTTPS适配器按Ref解析密钥并向实际TLS源发送Bearer，不接受客户端目的URL，也不将密钥写入来源响应。缺Ref或部署未配置时拒绝同步。专用TLS源测试端实际要求正确Bearer，否则401。项目原创两页观察在5257/5258执法双DM8经该连接器同步，最终游标`p2-3`、观察/OBSERVE回执/事件各2、线索0；登录产品Issuer/PKCE的`/v2/enforcement/sources/{id}/syncs`同样运行。`MaterialProductDm8Test,MaterialProductServiceTest,EnforcementMaterialHttpsTest`三项退出0、零跳过。首次H2重跑的第二来源负例因未传新必需Ref得409，不计通过；补齐同产品输入后重跑通过。配置版本、停用在途及逐记录失败细节尚未执行，IMP保持进行中。
- 配置版本增量：V2来源当前行保存`configVersion`，`enf3_source_config_revision`保留v1/v2不可变Ref、enabled、操作者与时间；同步尝试固定配置版本。专用5257/5258 DM8经产品`configureSource` v1→v2，旧expectedVersion拒绝且回执/事件不增，重建连接后的实际TLS空页同步留下v2尝试、游标`p2-3`且观察仍2。登录Issuer/PKCE后的`GET/POST /v2/enforcement/sources/{id}[/configuration]`实际v1→v2、旧版409、禁止回执0，下一次HTTP同步记录v2；响应只含Ref不含密钥。输出`ENF_V2_M01_CONFIG_DM8 revision=1->2 stale=CONFLICT attemptVersion=2 cursor=p2-3 observations=2 forbiddenReceipt=0`和`ENF_V2_M01_CONFIG_HTTP login=PKCE revision=1->2 stale=409 attemptVersion=2 forbiddenReceipt=0`。上述三项定向退出0、零跳过。停用在途与逐记录失败细节仍未验，IMP保持进行中。
- 停用在途增量：真实TLS源在`p2-3`响应前暂停，专用DM8同步已持久开始并冻结v2；另一产品命令将配置v2→v3停用，放行源响应后同步失败关闭为`FAILED/SOURCE_DISABLED`，游标仍`p2-3`、观察不增，下一同步在建尝试前拒绝。登录/PKCE的V2 HTTPS同样在异步请求挂起时调用配置端点，旧请求503、后续503，原件文件和观察不增；再通过具名配置命令v3→v4恢复，原材料链继续。输出`ENF_V2_M01_STOP_DM8 inFlight=SOURCE_DISABLED cursor=p2-3 attemptVersion=2 next=DENIED observations=UNCHANGED forbiddenReceipt=0`和`ENF_V2_M01_STOP_HTTP login=PKCE inFlight=503 next=503 attemptVersion=2 cursor=p2-3 observations=UNCHANGED files=UNCHANGED`。HTTP定向零失败/跳过；合跑DM8时并行构建导致原有辅助类瞬时`NoClassDefFoundError`，不计通过，独立重跑`MaterialProductDm8Test`退出0、零跳过。逐记录失败细节尚未收口，IMP仍进行中。
- 逐记录结果增量：成功条目在观察/游标同事务写`enf3_sync_record`，失败条目保存尝试ID、顺位、记录键/源版本、前后游标与标准错误码，不复制原文；同步响应含尝试ID，具名来源配置权限可用V2路由查询该尝试记录。项目原创TLS页将`OBS-001@1`文本置空形成反例，登录/PKCE的`POST /v2/enforcement/sources/{id}/syncs`返回`PARTIAL_FAILED/INVALID_SOURCE_RECORD`；`GET .../sync-attempts/{attemptId}/records`实读单条`FAILED`、前游标`p2-3`，正文不在响应，游标/观察/回执/事件不增。专用DM8同源同版正文漂移也实读记录键/版本/失败码、回执/事件/观察不增。输出`ENF_V2_M01_RECORD_HTTP login=PKCE record=OBS-001@1 failure=INVALID_SOURCE_RECORD cursor=p2-3 forbiddenReceipt=0`和原`ENF_V2_M01_DRIFT_DM8`；`MaterialProductDm8Test,EnforcementMaterialHttpsTest`两项退出0、零跳过。四项M01冻结技术证据已产生，但工作面/受影响回归及MATERIAL最终复判尚未完成，不提前放行Evidence。

## 2026-09-28 MATERIAL冻结开发验收最终复判

- 执行者：Codex（执法开发技术内部评估）。范围只含冻结M01～M06的开发C/I/HTTP/E2E，不是具名业务、法律、数据权利人或生产B签收。V035当前结构的真实产品`enf3_*`事务、五份317字节原件在两个独立静止检查点物理复制，业务5257在复制后恢复，独立权威5258未回灌；恢复实例5272、5274分别承担回执对账和旧备份反例，未碰咨询设施。
- M01预期/实际：受控TLS凭据Ref不泄密；v1→v2配置修订、旧版409/零回执；真实源响应暂停期间停用v2→v3，旧在途及下一同步503，游标`p2-3`、观察/文件不增；逐条坏记录HTTP查`OBS-001@1`、`INVALID_SOURCE_RECORD`、原游标且无正文/禁止回执。专用双DM8及登录V2 HTTPS定向复跑输出`ENF_V2_M01_CONFIG_DM8/HTTP`、`ENF_V2_M01_STOP_DM8/HTTP`、`ENF_V2_M01_DRIFT_DM8`、`ENF_V2_M01_RECORD_HTTP`，五项后端专项退出0、零跳过。旧配置与故障均未产生额外观察、线索、事件或材料。
- M02/M03预期/实际：发布前正反例、同源多规则去重及草稿零线索维持；跨源`NOT_APPLICABLE`、缺文本409、撤回403，无新线索/回执；317字节原件字节漂移拒收集，同人核验拒绝，异人登录后`UPLOAD/COLLECT_ATTEST/VERIFY`三动作、`VERIFIED` v3，禁止回执0。双DM8与登录HTTPS定向复跑均通过，原件摘要未变。
- M04预期/实际：两个独立JVM共享执法专用5257/5258及实际文件根，授权前200/200、撤精确Grant后403/403、撤来源后旧回执403，回执8/事件8/材料1/文件1不增；本轮独立部署HTTP定向退出0。打包前端以部署`VITE_ENFORCEMENT_PRODUCT_CLIENT_ID=enf-http-client`运行真实Chromium/PKCE，预览后撤权清屏，输出`ENF_BROWSER_WITHDRAWAL_CLEARED`，两后端测试零失败/跳过；未配置clientId的一次试跑停在“产品身份入口不可用”，不计通过，E2E新增失败诊断而未放宽断言。
- M05/M06预期/实际：当前V035同点物理恢复至5272后，原回执`material-1`各1、`VERIFIED` v3、三动作、317字节SHA `1b3042f1ecb965b302c992a7ed5bf2e00b4157259dbf56c77dd061dec2e77deb`一致；孤儿清理`REMOVED`、引用文件`RETAINED`，撤来源旧回执拒绝；缺权威入口拒绝且回执/事件各1、文件5不增。另一全新事务与五文件恢复至5274，在来源仍READY、独立删除epoch前进后两连接旧回执/动作均拒绝，回执/事件/材料各1、文件6不增，输出`ENF_V2_OLD_BACKUP_DM8`；三项恢复定向最终各零失败/跳过。首次错误顺序先撤来源导致旧备份起点不READY、另一次恢复实例尚未就绪出现连接EOF，均不计通过；新随机检查点重做后才取证。既有加密归档开发I见上文，正式密钥托管、保留批准与受权删除不在本次验收结论内。
- 前端组件`EnforcementJourneyPage`一项通过；`verify-all`在本次文档更新前退出0（47基线、41需求、619任务），结构校验与语义评估分开。逐例评估结论：冻结M01～M06开发C/I及内部技术验收条件均通过，`IMP-ENF-MATERIAL-01`开发实现完成；不继承正式`ARC-ENF-MATERIAL-01`六项真实业务证据、P5/共享D或正式B。`ARC-ENF-EVIDENCE-02`虽为下一执法设计任务，其现有依赖仍未满足，不开始该任务的业务设计或编码。

## 2026-09-28 正式MATERIAL D证据受理与冻结回归

- 按主计划`ARC-ENF-MATERIAL-01`及docs/57第6.14节逐项只读盘点当前工作区：`data/enforcement/source-review/cnipa-official-v1/manifest.json`仍标`REVIEW_REQUIRED`且用途仅`INTERNAL_DEVELOPMENT_REVIEW_UNTIL_PILOT_END`；`data/enforcement/authorized/cnipa-official-v1/manifest.json`仅`INTERNAL_DEVELOPMENT_ENFORCEMENT_ASSISTANCE_UNTIL_PILOT_END`；项目原创`material-dev-product-contract-v1`材料及具名开发grant/规则/恢复日志只覆盖开发C/I。没有发现具来源、版本、责任方、日期、正式适用范围与批准结论的产品来源许可、执法岗位现时授权、专业发布规则、现案原件、实际收集核验链或目标环境恢复批准。六项仍各由其主计划任务保持`WAITING_EXTERNAL_INPUT`，不将此盘点当作新的状态台账或正式B。
- 开发侧已完成的可复用准备是docs/57第6.7～6.12、6.14节逐字段许可/版本/撤回、精确grant、水位、规则正反例、原件/派生与收集链、DB/文件回灌的证据格式和允许/拒绝预期；新交付物到位时按确切来源ID、版本、摘要、用途和责任方逐项反查。共享`NEXT-V2-D-DATA-01`及P5仍按主计划独立阻塞，不由本域改状态。
- 冻结产品防回归：5257/5258执法专用双DM8运行`MaterialProductDm8Test,MaterialProductServiceTest,EnforcementMaterialHttpsTest,MaterialIndependentDm8HttpTest`，四项零失败/错误/跳过。输出M01旧配置冲突/停用在途失败关闭/逐条错误码、M02跨源与撤回、M03字节漂移及异人核验、M04双独立JVM 200/200→403/403、旧回执403且回执8/事件8/材料1/文件1不增；MATERIAL活跃规则停用、回执同键冲突、孤儿移除和引用原件保留仍通过。此为开发环境回归，不给正式六项补证。`verify-all`在证据变更前通过47基线、41需求和618计划任务；变更后需求基线仍通过，但并行项目测试报告时效`TEST_EVIDENCE_STALE:project`截断统一校验，不计全局通过，未改无关模块。正式D复判仍**不通过**，不启动`ARC-ENF-EVIDENCE-02`。

## 2026-09-28 最终产品验收输入核验与Evidence非实现预注册

- 项目原创验收清单`data/enforcement/material-v2-acceptance-v1/manifest.json`引用既有观察包和317字节原件，不复制或改写原件；原件SHA-256为`1b3042f1ecb965b302c992a7ed5bf2e00b4157259dbf56c77dd061dec2e77deb`，限定QUERY/SAVE/INDEX/DISPLAY观察用途及SAVE/PREVIEW/DOWNLOAD原件用途，禁止MODEL/SHARE/官方证据/决定/生产来源。`node scripts/verify-enforcement-material-acceptance-input.mjs --self-test`退出0：一项有效，冒称真实案件、扩大原包范围、越用途和篡改字节四项拒绝。预期与实际一致；此只核清单/字节/权利声明，不产生产品回执、线索、证据或DB/文件副作用，也不证明第三方权利、现案或正式D。
- 冻结MATERIAL窄回归`MaterialProductServiceTest,EnforcementMaterialHttpsTest`退出0，零失败/错误/跳过；登录PKCE的V2 HTTPS输出M01旧配置409/禁止回执0、停用在途503且观察与文件不变、逐记录`INVALID_SOURCE_RECORD`/禁止回执0、M02跨源不适用/缺项409/撤回403且线索0、M03异人核验三动作/同人回执0，以及撤权旧回执拒绝。此次H2与HTTPS回归不替代既有5257/5258双DM8和物理恢复证据，也不增加正式输入范围。
- `PREP-ENF-EVIDENCE-02`按原始ZF-06/07在docs/57第6.22节登记E01～E05的输入、预期、C/I/HTTP/E2E与禁止副作用及B责任；仅纸面预注册，五例`NOT_RUN`，没有Evidence服务/API/迁移/页面。已发布ENF-02发现规则不等于已发布ENF-06待证规则；真实案件类型/事项/依据效力/岗位/现案材料及第三方权利均保持`WAITING_EXTERNAL_INPUT`。
- 正式MATERIAL D复判：新增清单仅关闭项目原创原件**输入完整性**一部分，六项主计划任务的同一产品实际验收、收集链及公共`NEXT-V2-D-DATA-01`/P5仍未全部满足，`ARC-ENF-MATERIAL-01`继续阻塞；`IMP-ENF-MATERIAL-01`保持已完成冻结，`ARC-ENF-EVIDENCE-02`不启动。前置统一校验通过需求基线47/41，随后被并行`TEST_EVIDENCE_STALE:project`截断；计划/证据更新后的`node scripts/verify-all.mjs`退出0（47基线、41需求、612任务），未触碰无关模块。结构通过不代替正式语义审查。

## 2026-09-28 MATERIAL产品D与现实启用条件独立复判

- 项目负责人当前决定见docs/02第44节。上一节“正式D缺真实输入/公共D而阻塞”是变更前时点快照，不再是当前产品D定义。未改冻结实现、接口、规则语义或M01～M06验收条件；只将六项项目有权输入与既有产品执行证据逐行复用，结果见主计划对应六行及docs/57第6.23节。
- 来源：项目原创两版观察和实际字节摘要/用途，受控TLS分页/429/空页/重启游标、凭据Ref/配置版本、停用在途、逐记录失败由V2双DM8与登录HTTPS执行，故障不推进禁止游标或写入。授权：产品Issuer/PKCE测试成员精确grant，错组织/缺用途拒绝及双独立JVM撤权200/200到403/403、旧回执拒绝，回执8/事件8/材料1/文件1不增。规则：ENF-02发布前正反例、active/disabled、旧引用保留和新发现停止，跨源/撤回不误命中，限项目候选发现而无法定效力。
- 原件和收集：项目原创317字节原件SHA及保存/预览用途核验；V2上传、具名收集、异人登录核验三动作到VERIFIED v3，同人409/回执0、字节漂移/撤回拒绝、旧成功回执不越权。恢复：实际`enf3_*`事务和全文件根同点物理回灌，回执/状态/原件SHA、孤儿清理/引用保留对账；独立epoch前进时两连接旧回执与动作拒绝，回执1/事件1/材料1/文件6不增，缺权威失败关闭。相关原始预期/实际、失败尝试及专项退出码保留在本文件M01～M06各节，未用V0、Synthetic投影或技术探针代替。
- 具名复核：Codex（执法开发技术内部评估，2026-09-28）对冻结六行及M01～M06逐项核预期/实际/禁止副作用，结论是`ARC-ENF-MATERIAL-01`在项目声明的最终产品验收范围**通过**。这不是第三方许可、真实执法岗位/现案、法定专业效力、生产保留/密钥/运维批准或具名业务B；这些仅阻其相应现实启用，不退回产品D。`IMP-ENF-MATERIAL-01`仍已完成且冻结。公共`NEXT-V2-D-DATA-01`未改状态，`ARC-ENF-EVIDENCE-02`只进入自身D审查准备，不因本结论自动获准业务编码。
- 本次计划状态变更前`node scripts/verify-all.mjs`退出0（47需求基线、41契约）；六行及D状态变更后，需求基线仍通过，但全局在并行`TEST_EVIDENCE_STALE:project`截断，未修咨询或无关代码。结构或时效检查均不能替代以上语义评估。
- 后续定向`MaterialProductServiceTest,EnforcementMaterialHttpsTest`零失败/错误/跳过，验收输入专项`--self-test`一正四拒退出0；全量`mvn -q -f backend/pom.xml test`退出0后，`node scripts/verify-all.mjs`再次退出0（47基线、41需求、619任务），中间时效错误已消除。仅更新执法计划/设计/证据及相应执法追踪行，没有修改MATERIAL代码或咨询业务文件。
