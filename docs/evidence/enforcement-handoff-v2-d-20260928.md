# ARC-ENF-HANDOFF-03 D复核证据

- 日期/复核者：2026-09-28，Codex（项目技术语义复核，非现实执法业务签收）。
- 输入来源：执法需求总览第5～6节、`docs/01`第7节、`docs/02`第27～28、44～45节、`docs/57`第4、6.24～6.25节、ADR-ENF-HANDOFF-01；`IMP-ENF-MATERIAL-01`与`IMP-ENF-EVIDENCE-02`均在主计划已完成且冻结。没有修改两者代码/合同。
- 产品验收输入：项目有权`PROJECT_DOCUMENT_REVIEW`案件快照、已发布Evidence规则、经MATERIAL实际核验的A/B/C原件与Evidence组织确切v1～v4；两个不同具名测试人员经同一产品Issuer获取精确领取/意见Grant。它们是将来同一产品的验收输入，不是现实案件、岗位或接收记录。本D只核设计及预期，HANDOFF API/DB/浏览器C/I均`NOT_RUN`。

| D反例 | 输入/预期 | 禁止副作用及实现后证据 |
|---|---|---|
| H01 包与请求 | 引用确切Evidence v4及三原件Ref/SHA形成包v1；保存请求后仍`PENDING`，不能显示已接收 | 不复制原件、不改Evidence修订或官方状态；C/I核包、请求、事件/回执同事务，HTTP/页面一致 |
| H02 旧版迟到 | 包v1请求后补料成v2，旧v1领取/意见仅归v1；v2保持待领取/复核 | 旧请求不能推进v2，旧回执不能返回v2成功；版本/事件/文件逐项对账 |
| H03 并发与撤权 | 两具名人员对同一包版本竞争领取仅一方成功；撤Grant后同键重放/读取失败 | 失败方无受理事件/回执；旧权限不能恢复；双实例DM8+独立现时水位验证 |
| H04 意见与回流 | 领取者具名提交意见/理由/引用确切包版，回流只生成内部新版本；退回留原因 | 模型、未领取者和旧版提交拒绝；不写线索正式处置/立案/处罚状态 |
| H05 故障与恢复 | 断连、提交中断、取消后迟到分析、重启与旧DB/文件回灌；先核确切引用和备份外现时水位 | 不把通知当接收、不产生半回执；缺权威/原件失败关闭，旧备份不复活失权受理 |
| H06 工作面 | 待领/已领/已回流与包版、缺口、争议、未处理可见；403清屏、冲突重载 | 不显示无权正文或伪官方完成；登录V2 HTTPS/浏览器E2E核直接API一致 |

设计复判：上述输入、状态、权限、版本、事务、失败恢复、人工/AI边界和C/I/HTTP/E2E预期在`docs/57`第6.25节闭合；H01～H06全为预注册，绝不标已执行。项目技术复核结论为`ARC-ENF-HANDOFF-03`产品D通过，准许开始唯一`IMP-ENF-HANDOFF-03`；真实接收岗位、正式外部系统协议、真实案件/原件许可、专业接受及生产恢复批准继续`WAITING_EXTERNAL_INPUT`于现实业务启用/B。若这些输入揭示最终合同缺陷，走Change Request，不造第二套HANDOFF。

## IMP-ENF-HANDOFF-03 H01 C/隔离I增量（2026-09-28）

- 执行者：Codex。V054/U054在HANDOFF自己的`enf5_*`关系上建立包修订、请求、意见、事件、回执及事务锁；领域只通过确切Evidence组织读取和当前授权端口引用上游，不改MATERIAL/EVIDENCE表或实现。H01输入由MATERIAL现有产品服务逐A/B/C生成实际原件、收集确认、异人核验`VERIFIED v3`，再由Evidence现有规则发布/组织服务生成真实组织v1；原创三原件与项目方法用途保持原许可边界。
- 预期/实际：`PACKAGE-001 v1`固定`HANDOFF-ORG v1`、EvidenceSet摘要、`EVENT_TIME:CONFLICT`和未处理清单；`REQUEST-001`保存后仅`PENDING`，无领取者和意见；具名`named-receiver`领取后才`ACCEPTED`，另一有权具名领取者旧期望版冲突；该领取者提交带理由意见后生成内部回流结果，仍不写官方状态。三命令及初建包各同键同载荷原回执，失权后旧请求键与意见读取拒绝。H2 C及专用5257随机DM8 I各1/1，零失败/错误/跳过；DM8正逆迁移、第二独立JDBC读取相同包/请求/意见。
- DB/文件/禁止副作用：`enf5_work_package_revision/human_request/human_result_revision=1/1/1`，HANDOFF回执/事件各4；失败领取、旧版提交和失权旧键不增。上游Evidence组织回执仍1、MATERIAL行3，三份原件实际字节不变，没有HANDOFF blob，也没有正式线索/案件状态写入。`ENF_HANDOFF_H01_PRODUCT package=1 request=PENDING claim=ACCEPTED result=1 receipts=4 events=4 evidence=1 originals=3 revoked=DENIED`在C/I两次输出。代码增量后`verify-all`退出0（47需求基线、41台账、619任务）。
- H01的登录HTTPS/浏览器尚`NOT_RUN`；H02～H06双实例、旧版迟到、完整恢复、页面和最终技术复核均`NOT_RUN`。仅是IMP实现增量，不宣称HANDOFF最终验收或现实受理。

## H02旧版迟到与新包隔离（2026-09-28）

- 执行者：Codex。以上同一真实MATERIAL原件与Evidence组织v1后，具名人工纠正A的`EVENT_TIME line 4`形成Evidence v2；HANDOFF `PACKAGE-001`按期望包v1追加v2，`workId`不变，内容摘要变而v1仍可读。v1的第二请求`REQUEST-OLD`与v2的`REQUEST-NEW`各自绑定确切版；v2创建后才领取并提交v1意见，旧意见明确保存`packageVersion=1`，v2请求仍`PENDING`且无意见。旧期望版重修订冲突，既有版本/回执不覆盖。
- H2 C和专用5257随机DM8 I各1/1零失败/错误/跳过，随机schema正逆迁移和第二JDBC读取；实际`enf5_work_package_revision/human_request/human_result_revision=2/3/2`，回执/事件9/9，拒绝的旧期望与撤权重放不增。上游Evidence组织因真实人工纠正为v1/v2两版，MATERIAL仍三行、三原件字节不变，未产生HANDOFF文件、官方处置或案件状态。输出`ENF_HANDOFF_H01_H02_PRODUCT packages=2 requests=3 results=2 new_request=PENDING receipts=9 events=9 evidence=2 originals=3 revoked=DENIED`在C/I两次一致。此处只闭H02版本隔离的C/I，登录HTTP/双实例及其余H场景仍另测。

## H03双人并发领取与撤权C/I（2026-09-28）

- 执行者：Codex。在上述新包v2请求上，两名具名且均有领取动作的测试人员用不同键、两连接同栅点并发`CLAIM(expectedVersion=1)`。H2 C及专用5257随机DM8 I各1/1零失败/错误/跳过，实际一人`ACCEPTED`、一人版本冲突；第二JDBC读当前领取者，`enf5_handoff_receipt/human_event=10/10`而非11/11。撤现时权限后胜者同键重放拒绝，回执不增；上游Evidence两版、MATERIAL三行及三原件字节不变，没有官方状态副作用。输出`concurrent=ONE_ACCEPTED receipts=10 events=10 ... revoked=DENIED`两环境一致。此处是单服务双连接I，不冒称双独立Spring部署HTTP时序；后者仍待执行。

## H04具名退回、撤回及迟到拒绝C/隔离I（2026-09-28）

- 执行者：Codex。冻结状态机的`RETURN_HUMAN`和`WITHDRAW_HUMAN`在同一HANDOFF事务追加不可变事件、具名原因及回执，分别将已领请求退回`PENDING`、将请求置`WITHDRAWN`；待办按当前包/上游/现时领取Grant过滤。原因列由HANDOFF自身V055/U055增量迁移持有，不重写已落地V054，不改上游表或服务。
- 预期/实际：非领取者退回、服务身份意见、退回后的旧领取意见、撤回后的迟到领取全部拒绝且不写成功回执。具名胜者退回后请求v3为`PENDING`、领取者清空，事件原因逐字读回；原请求者撤回后请求v4为`WITHDRAWN`、原因逐字读回。H2 C与执法专用5257随机DM8 I各1/1零失败/错误/跳过，专用schema执行V055/U055正逆迁移。实际包/请求/意见2/3/2，HANDOFF回执/事件12/12，拒绝路径均不增；上游Evidence组织两版、MATERIAL三行及三份原件字节不变，无HANDOFF blob或官方状态写入。两环境均输出`returned=PENDING withdrawn=WITHDRAWN receipts=12 events=12 evidence=2 originals=3 revoked=DENIED`。
- 此项只闭H04的C/隔离I子项；登录V2 HTTPS、独立双部署HTTP、浏览器H06、物理恢复H05及最终具名技术复核仍`NOT_RUN`，`IMP-ENF-HANDOFF-03`保持进行中。

## 登录V2 HTTPS产品链（2026-09-28）

- 执行者：Codex。`HandoffHttpsTest`在独立TLS、Issuer、备份外授权水位与PKCE登录上启动当前V2 Controller；项目自备有用途的A/B/C原件由MATERIAL现有服务实际注册、收集/异人核验为VERIFIED v3，Evidence现有服务实际发布规则并组织v1。两名具名产品主体获取确切粒度的当前Grant，服务没有测试身份旁路；此HTTP测试的产品DB为独立H2，故不冒称双部署DM8/物理恢复。
- 预期/实际：匿名包创建401，登录后包创建及同键重放200；请求保存后仍PENDING，具名待办列表1；创建者无领取/提交Grant返回403，领取者领取后提交意见200，读取意见仍引用包v1。撤提交Grant后原意见同键重放403，成功回执不复活。H2 HTTP测试1/1零失败/错误/跳过，`enf5`回执/事件4/4、意见1、Evidence组织1、MATERIAL原件3；逐个读取三原件文件并与验收输入字节相等，无HANDOFF blob/官方状态写入。执行输出`ENF_HANDOFF_V2_HTTPS login=PKCE package=1 request=PENDING claim=ACCEPTED result=RETURNED receipts=4 events=4 originals=3 revoked=403`。
- H03双独立部署DM8 HTTP、H05共同物理恢复、H06真实浏览器及最终具名技术复核仍`NOT_RUN`；页面仅完成源码和前端构建，不算浏览器实测。

## H06浏览器已回流与失权子场景（2026-09-28）

- 执行者：Codex。以`-Pbundle-frontend`编译当前React工作面，在同一独立TLS/Issuer和PKCE产品服务上由Chromium实际登录具名领取者；读取`HANDOFF-HTTP-PACK v1`确切Evidence输入、`EVENT_TIME:CONFLICT`缺口、请求v3 `RETURNED`与具名意见/理由。浏览器带Bearer的`/v2/enforcement`直接API返回同一请求v3/包v1；请求监听未见`/api/v0`，桌面及390x844移动截图为`frontend/target/enforcement-handoff-v2-{desktop,mobile}.png`，移动无横向溢出，截图已目检。
- 撤当前`WORK_PACKAGE/READ`精确Grant后再点击页面读取，实际403并清空包、意见和会话，登录按钮重现；业务回执/事件仍4/4，MATERIAL三份文件逐字节不变，无官方状态写入。`ENF_HANDOFF_BROWSER_WITHDRAWAL_CLEARED`，登录HTTP/浏览器测试1/1零失败/错误/跳过。此前浏览器定位按钮的首次失败只涉及E2E选择器，改为稳定工作面控件后重跑通过；未作为业务成功证据。
- H06的待领/已领浏览器状态尚未实际执行，因此H06整体仍未完成；H03双独立部署、H05物理恢复及最终技术复核亦未完成。当前工作树并行项目使全局`verify-all`出现`TEST_EVIDENCE_STALE:project`，不修改无关代码来消除该失败；执法专项C/I/HTTPS/E2E结果独立记录。

## H06待领→已领→回流浏览器补验（2026-09-28）

- 执行者：Codex。先用登录V2 HTTP实际创建第二请求`HANDOFF-HTTP-UI`，页面初读v1 `PENDING`、未领取；Chromium以具名领取者点击领取后v2 `ACCEPTED`，填写意见/理由并提交后v3 `RETURNED`及确切包v1意见可见。页面对首条已回流请求仍可独立读取；同一浏览器直调API一致，撤`WORK_PACKAGE/READ`后页面403清屏。缺结果的PENDING不再预读意见并误判失权，该冻结范围内缺陷已修复。
- 预期/实际：登录HTTP/浏览器测试1/1零失败/错误/跳过；`enf5_handoff_receipt/human_event=7/7`、两条意见，失败和撤权后不增；Evidence组织1、MATERIAL三原件文件逐字节一致，无HANDOFF blob或官方状态副作用。桌面/移动截图覆盖最终工作面且移动无横向溢出。H06当前冻结的待领、已领、已回流、确切版本/缺口、失权清屏与直调一致均有实际执行证据；H03双独立部署、H05物理恢复及最终具名技术复核仍未完成。

## H03双独立部署DM8 HTTPS时序（2026-09-28）

- 执行者：Codex。执法专用5257业务DM8与5258独立现时授权DM8使用同一随机隔离schema；MATERIAL真实三原件经收集/异人核验、Evidence组织v1/v2及HANDOFF包v1/v2由现有产品服务形成，随后两个独立Spring/JVM HTTPS部署共享业务DB/文件与授权权威。两名具名产品主体各以PKCE取得令牌，在不同部署上同栅点、不同键领取同一确切包v2请求，真实HTTP得到200/409；第二JDBC读胜者，失败方无回执或事件。撤胜者当前`HUMAN_TASK/CLAIM` Grant后，以原键重放为403，旧回执不被重新接受。
- 随机schema正逆迁移及专用双部署测试1/1零失败/错误/跳过。初始HANDOFF回执/事件12/12，加新请求和唯一领取后14/14而非15/15；Evidence组织仍两版，MATERIAL仍三条；三份原件按确切案件输入逐个重新算SHA-256，均与原件Ref一致，无HANDOFF blob或官方状态写入。输出`ENF_HANDOFF_DUAL_DM8_HTTP outcomes=200/409 receipts=14 events=14 revoked=403 evidence=2 originals=3`。H03冻结的双实例/撤权竞争已实际执行；H05共同物理恢复和最终具名技术复核仍未完成。

## H05真实产品DB+原件共同物理恢复（2026-09-28）

- 执行者：Codex。第一次静止窗口过长，生产者短期access token已过期，撤权后HTTP得401；恢复端虽以新PKCE会话读回并失败关闭，此轮**不计H05通过**。保留隔离的首轮恢复卷5294和失败报告，不以过期令牌冒充撤权。第二轮重新使用随机执法业务/授权DM8 schema、V057当前身份基础迁移、MATERIAL三份VERIFIED v3原件、Evidence组织v1/v2、HANDOFF包v1/v2及具名请求/领取回执。领取提交后静止，短暂停止仅5257业务库，冷复制真实DB卷到全新5295恢复卷，并同点复制私有三原件和案件输入；立即重启5257。独立5258授权/来源/删除水位从未停止或回灌。
- 恢复端在同一最终产品Spring/TLS入口重新PKCE登录快照内具名胜者，先查`enf3_material=3`、`enf4_organization=2`、`enf5_work_package_revision/human_request/human_result_revision=2/4/2`，逐个核文件字节SHA，`enf5_handoff_receipt/human_event=14/14`。当前Grant仍有效时GET确切请求与POST原领取键均200，旧回执只重放不新增。暂移恢复根一份真实原件，GET/POST均非200且回执/事件不增；归还原件后GET恢复200。原部署在5258撤领取及包读取精确Grant，以新PKCE令牌核旧键403，随后恢复端仍持撤权前旧业务快照，以同一会话GET/POST均非200，回执/事件仍14/14，三原件SHA仍匹配。两项专项测试各1/1零失败/错误/跳过，输出`ENF_HANDOFF_OLD_BACKUP_CURRENT_AUTHORITY before=200/200 missingFile=DENIED after=DENIED/DENIED receipts=14 events=14 files=3`。这不是生产加密备份批准；现实B独立。
- H05物理回灌/旧水位/缺文件子场景实际通过。冻结的提交中断事务回滚及断连同键重试仍需专项实测；最终具名技术复核未执行，IMP保持进行中。

## H05提交中断与断连回执补验（2026-09-28）

- 执行者：Codex。`HandoffProductTest`的同一产品服务在意见写入后、请求版本更新前注入提交异常；H2及执法专用5257随机DM8 `HandoffDm8Test`各1/1、零失败/错误/跳过。预期/实际：请求仍`ACCEPTED`、意见不存在、`enf5_handoff_receipt/human_event`仍10/10，未产生半回执；后续具名退回/撤回及拒绝路径结束为12/12、意见2、上游Evidence组织2、MATERIAL三原件字节不变，无HANDOFF文件或官方状态写入。
- 独立TLS/PKCE当前V2产品入口`HandoffHttpsTest`以具名领取者发送完整领取POST后断开客户端连接，先等DB确认首领取已提交（回执/事件从请求前4/4增至6/6），再以相同键、载荷、令牌HTTP重试得200；请求为`ACCEPTED`且回执/事件仍6/6，不产生第二领取。浏览器分支再经`PENDING → ACCEPTED → RETURNED`及撤权403清屏，最终回执/事件9/9、意见2，三原件逐字节不变，Evidence组织1、MATERIAL材料3，无HANDOFF blob/官方状态副作用。`ENF_BROWSER_E2E=1`专项1/1、零失败/错误/跳过，输出`disconnectedReplay=ONE_RECEIPT receipts=9 originals=3 revoked=403`；HTTP只使用`/v2/enforcement`。第一次带前端打包命令因并行构建占用`tsbuildinfo`在测试前退出1，不计业务验证；改用已构建的相同工作面后专项通过。

## IMP-ENF-HANDOFF-03具名最终技术复核（2026-09-28）

- 复核人：Codex；依据冻结的`docs/57`第6.25节和本文件预注册H01～H06，不追加条件、不重开D。H01包/请求/具名领取/意见由C、5257 DM8 I和登录HTTPS实测，保存请求不等于受理；H02旧包迟到只归旧版，新版待领且无旧回执污染；H03两个独立JVM HTTPS对5257/5258竞争实际200/409、仅一新领取回执，撤权旧键403；H04具名退回/撤回原因不可变，非领取者/服务身份/迟到命令和提交中断均无成功副作用；H05实际产品DB与三原件同点物理恢复至5295，回执/事件14/14、缺文件拒绝、备份外水位撤权后旧快照读/重放拒绝，断连后已提交同键重试仅一回执；H06 Chromium具名登录工作面`PENDING → ACCEPTED → RETURNED`、包版/缺口与直调API一致，失权403清屏。首轮5294过期令牌401不计通过，第二轮以新PKCE令牌取得撤权403。
- 核查结果：H01～H06冻结的正反例均有上述实际结果；H2 C、随机独立5257 DM8 I、双独立部署HTTPS、物理恢复和浏览器专项各执行报告零失败/错误/跳过（恢复生产者/恢复端第二轮各1/1）；DB中的`enf5`包/请求/意见、事件与回执闭包、三原件SHA/字节及拒绝路径不增逐场景核对。无HANDOFF blob、上游MATERIAL/EVIDENCE写入改动、线索官方状态或现实接收回执。技术开发验收通过，`IMP-ENF-HANDOFF-03`完成并冻结；真实岗位、正式外部接收、第三方权利和生产业务B独立，未被测试授予。
- 治理检查：编辑前`verify-all`通过47份基线/41需求，但结构硬限报HANDOFF两份测试与并行身份`ProductIssuerHttpTest`；仅将HANDOFF测试辅助代码等价提取至各自测试包，复跑登录HTTPS/浏览器及双实例DM8专项均1/1零失败/跳过。状态/证据编辑后`verify-all`需求基线仍通过，项目健康门被既有Java报告`TESTS_FAILING:project`（0失败、7错误）截断：Evidence两项匿名类加载错误、旧`EnforcementHttpApiTest`五项上下文错误；不把这些旧报告当HANDOFF失败，也不修改冻结Evidence、V0或并行身份模块。全仓校验未通过，专项技术结论只限HANDOFF冻结验收。
