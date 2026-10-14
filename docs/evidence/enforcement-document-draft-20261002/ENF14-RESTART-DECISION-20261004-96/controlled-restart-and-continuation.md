# 执法 V2 受控重启与事实记录续行决策材料

日期：2026-10-04。归属：REC-ENF-JOB-PACK-79-01 / IMP-ENF-DOCUMENT-DRAFT-05。只整理已有证据和源码，不是重启许可、历史完整确认、专业法律确认或新增D。任务状态仅在docs/08。

## 1. 建议负责人批准的最小范围

建议只批准“接受未知更晚历史仍未证明这一运行边界，在下述停机只读预核通过后受控启动5444，正常OAuth/PKCE登录并读取现存事实记录不完整稿”。允许正常登录所必需的OAuth授权/会话写入，**不允许任何案件/JobPack/文书业务写入、重复领取、修改Grant/epoch、专业引用确认或文书审核/DOCX**。

目前没有任何重启批准。本轮不连接主库、不运行恢复/测试/搜索、不打开产品、不创建定时任务。此前automation已删除，RUN仅限定安全队列已收尾；IMP/REC仍进行中、BUSINESS_ACCEPTANCE_PENDING。

## 2. 三类已证明事实，不能相互替代

| 事实与原件 | 实际源码/接口/版本关系 | 对本案续行的含义及未覆盖内容 |
| --- | --- | --- |
| 87单事务INSERT16/UPDATE0/DELETE0，独立新连接199字段/29时间/12原件SHA一致；[提交原件](../ENF14-ENV-REGRESSION-20261003-79/recovery-admin-readonly-20261004-87/application/restore-execution-result.json)、[独立对账](../ENF14-ENV-REGRESSION-20261003-79/recovery-admin-readonly-20261004-87/independent-reconciliation.json) | 12 task、1 claim event、1 pack revision、1 receipt、1 event；目标task v2/CLAIMED、目标pack v1/WAITING_REAL_HUMAN_ACTION；当前Controller GET可以读取既有对象 | 已知16记录恢复通过；精确事故前截止及更晚历史NOT_PROVED。不能重造缺失包、把隔离I当最新权威历史，不能宣布REC全面完成 |
| 95真实隔离Importer重入12任务及全部原件不变；[原对账](../ENF14-CLAIM-RESTART-SAFETY-20261004-95/claim-reconciliation.json)、[原报告锚点](../ENF14-CLAIM-RESTART-SAFETY-20261004-95/report-integrity.json) | PublicCollectionTaskImporter.run/importCandidate：候选满足公开准入后先find(taskId)，已存在即返回；TaskStore.seed也先find并处理唯一冲突。两源SHA与90相同，见95 production-source-hashes.json | 当前已存在的任务不会因该Importer重入而被重置。**若运行库缺任何候选task，非空清单会创建新任务/原件**；“已有12保持”不证明任何未来启动必然零写 |
| 95同actor/org新request_key实际新增CLAIM事件，但task version2、claimedAt/updatedAt/owner/publicInput不变；同key重放不增，异actor/org及fingerprint冲突拒绝 | Dm8RealBusinessCollectionTaskStore.claim；POST /v2/enforcement/job-packs/collection-tasks/{taskId}/claim。Controller先核当前CREATE/COLLECT/READ及原件SHA，再调用claim；包缺失时另createPublic | 明确已有技术行为，不擅称业务缺陷或批准重复领取。续行应直接GET已领取task/既有pack/文书，**不再点领取，也不以新key“找回历史”**。缺包时POST可能新建v1，当前未知历史下禁止 |

当前工作区源SHA、Git HEAD及原证据SHA统一保存在[source-and-evidence-anchors.json](source-and-evidence-anchors.json)。HEAD不证明已发行二进制；源码审阅/隔离I不证明现时运行库、用途/权限、来源时效已就绪。95为独立5386机械I，不能冒本案真人/产品I。

## 3. 启动和入口可能执行什么

1. 正式分派：AgentPlatformApplication首参数必须是 --product-enforcement，随后运行ProductEnforcementApplication。不得走默认根应用（带EnableScheduling且扫描范围更广），不得换咨询产品或test-synthetic等被正式issuer拒绝的profile。
2. **直接照旧运行start-enforcement-acceptance.ps1不安全**：它同时设置ENFORCEMENT_PUBLIC_JOB_CANDIDATES和显式 --enforcement.v2.job-pack.public-candidates-path=<12候选清单>；还可能在retention-v1.json缺失时创建文件。启动前必须确认已有保留文件/原参数存在，不能靠自动生成补配置。
3. Importer已有正常配置支持空public-candidates-path，run即返回，非开发旁路。最小启动应让**有效配置** enforcement.v2.job-pack.public-candidates-path 为空，包括移除非空环境值和最终命令行的强制清单。仅设置空环境变量不足以覆盖旧脚本的非空CLI。不能为此改冻结Importer，也不能手改任务状态。现脚本没有这个开关参数，不能声称原脚本原样即可安全启动；审批后如需调整启动工具，只改既有属性的传递方式并留证，不新增业务状态/接口。
4. EnforcementMaterialCleanupJob仅在enforcement.v2.enabled和enforcement.v2.cleanup.enabled都true时装配；使用当前CLEANUP授权并可清理原件。明确采用已有 enforcement.v2.cleanup.enabled=false，核有效配置和装配日志；不能只凭默认值或“没有人调用”认为不存在后台写者。审阅正式入口没有EnableScheduling；这不等于证明任意已发行artifact没有调度处理器，故仍以关闭bean条件为准。
5. EnforcementSourceScheduleRunner是config包内test-synthetic profile+source-admin.scheduling-enabled=true的开发调度器，正式Product入口不扫描该包，且issuer拒绝该profile。不得切profile/默认根应用启用它。源码未发现正式扫描/导入范围内其他ApplicationRunner、PostConstruct或Scheduled写者；未覆盖未知插件/部署DDL触发器/外部进程，不能宣称全环境无写者。
6. ProductSigningKeys构造只核参数，不自动rotate；ProductGrantWatermark构造读取独立权威和主投影并assertCurrent，差异时必须拒绝，不调用change或bootstrap补齐。现有OAuth登录实际保存OAuth授权及id2_session，**不是纯SELECT**；只有明确批准上面的登录范围后可发生，Grant/epoch/业务行仍不得改。
7. GET既有task/pack/draft/history是读取入口；claim、create、submit、verify、baseline-import、generate/revise/regenerate/adopt、review/export等是写入口。只用正常有权会话GET，不开放或自动调用任何写入口；读取不以重新领取为前置。

## 4. 最小重启前置与执行顺序（全部待批准后才执行）

### A. 保持停机，先只读定界

- 固定5444产品PID/启动入口、5311 ENFACC_BIZ、5312独立当前权威；不启动5319或旧恢复卷，不动5443/咨询/裁决。不是重搭环境。
- 固定既有发行build/classpath的具体版本与SHA，不用任意当前target目录/全模块classes拼装替代。当前源锚点只是审阅证据，不冒运行artifact；若已有发行物/启动参数不能确证，则不启动，不临时编译其它业务弥补。
- 对材料根、模板根、文书根、Case文件、映射catalog、法律catalog、NON_OFFICIAL profile、retention文件、HTTPS证书标识及issuer/既有PKCE客户端记录做只读配置核对，路径与原版本/SHA可定位。秘密继续仅由本地安全设施注入进程，证据不保存密码、私钥或token。
- 执行下节SELECT/读件快照，保护87原16、事故后文书@1及原件、**此刻**权威水位/当前Grant投影。87的581行是当时保全事实，不把它当永恒合法授权集合；合法后续授权变化只能由独立权威及原审计证明，不回灌旧581行或授予新权限。
- 核端口/访问入口确实能由指定操作人员受控使用。现有Vite脚本绑定127.0.0.1，但旧后端脚本没有固定server.address证据，不能推断5444只监听loopback。必须先核已有部署访问限制；若现有控制不能保证写请求不进入，此批准不自动补出全局只读模式，仍保持停机并提交最小部署控制D/配置审批。

### B. 受控启动，首先仍无业务写请求

- 仅在A通过且负责人明确接受未知更晚历史边界后启动固定执法发行物；应用既有Importer空值及cleanup=false配置，保持全部现行授权/用途/CAS门禁。
- 检查有效配置/条件装配、TLS及正常issuer发现/JWKS，不运行bootstrap、迁移脚本、seed、key rotate、Grant修复、claim或产品测试。/v2/identity根本身不是GET健康接口；现有身份读取为GET /v2/identity/me。
- 先做启动后只读数据库/文件对账，未发登录时相关业务/授权投影必须与A相同。若有新增task、包、领取事件、原件、授权变更或未知写者，停止本轮**精确PID**、保存证据，不“先启动再补历史”。
- 再由既有受控有权主体经正常OAuth/PKCE登录；只允许本次实际会话/OAuth授权产生的正常身份记录，不增Grant/epoch/新主体、客户端或签名键；不用过期token或测试账号冒业务真人。当前会话权限不足即401/403退出，不放宽。
- 仅GET现存task、pack、事实记录@1/history与来源/版本/缺项。相应页面若进入自动claim/写操作路径，不打开该路径；先正常文书查询入口读取，不能程序写状态。HTTP200不替代正文、原件SHA和manifest核对。
- 读取结束再做同一只读快照；业务数据、原件、当前Grant投影必须无变化。正常登录记录单独列明，不混进“零DB写入”或领域I数量。

### C. 失败停机/回退

- TLS/权威水位不一致、原件缺失/SHA错误、manifest版本漂移、未允许DB/文件写入、用途/Grant失效或后台配置未关闭均停止；只关闭本轮启动的已核PID，不停未知进程。
- “回退”首先是回到停机并保留新日志/快照/会话审计。**不重跑87工具、不整库恢复、不删除新行、不改Grant、不过期延用、不覆盖新文书**。如已出现未允许写入，另列具体差异和owner，数据回退另需批准。
- 如仅发行参数错误，可在无异常写入且证据保全后撤下本轮进程，保留原发行物；不把重启旧artifact当自动恢复权限。没有取得阶段A/B实测结果前，不记RUNTIME_READY或产品恢复通过。

## 5. 待执行只读对账口径（本轮没有执行）

对照87 independent-restored-records.json/independent-protected-after.json/independent-files-after.json，用相同列与类型化时间、原键排序/UTF8摘要读取；不SELECT身份密码、token或密钥明文。

- enf8_real_business_task：12已知原键逐字段及version/claimedBy/organization/claimedAt；目标v2/CLAIMED。也列出其它实际记录但不凭数量猜历史完整。
- enf8_real_business_task_event、enf8_job_pack_revision/receipt/event：原request_key、actor、operation、版本/状态/时间/原payload关系；目标已知包v1，trigger0/file注册0按原记录核。任何新增行先定位真实来源，不删除。
- enf12_document_draft_revision/receipt/event/review/candidate/export：目标文书现存head/全部历史、请求键和逐版manifest/内容SHA/原件SHA；87保护的新文书@1不可回退。候选/审核/导出不得凭隔离I补写。
- 5312权威水位及5311当前授权投影：使用现有ProductGrantWatermark读取/摘要口径，记录当下generation/state/intent及投影SHA；应READY且无未完成intent。对事故后现时投影只保全不回灌，不因主业务历史缺口直接改权威。
- 所有既有原件路径/大小/SHA及模板、规则、来源目录；对照同案精确清单。前置/启动后/正常登录后三个快照独立保存，不覆盖87证据。

## 6. ENF14精确续行对象及真实动作（不是本次启动批准包含的写许可）

已有80浏览器与81实际对账只证明prepare→generate→query的不完整FACT_RECORD，93/94只证明隔离revise/diff/adopt和失败机制。复用原件，不重复生成另一份“通过稿”。

- 组织：ORG-ENF-FUNCTIONAL。
- 案件：CNIPA-GUIDANCE-CASE-1-PUBLIC-ENF14-PUBLIC-CASE-20261003-45@1；材料1f3f5391-4f6f-479e-b550-017be5fa6323@3、d72ae68c-0663-4a1b-87ee-9a1ea81bbe30@3。
- 模板：ENF14-BUILTIN-FACT_RECORD@1；规则/映射：ENF14-FACT-MAPPING-ENF14-PUBLIC-MAPPING-20261003-48@2。精确SHA沿80 result.json的input，不自行提升为当前最新。
- 文书：ENF14-FACT-DOCUMENT-20261003-70@1，INCOMPLETE_DRAFT；正文SHA164d82f4f56f96cab747f13d8b680ae36610fa19c7d71c2dd5816f2bd6625a13、原件SHAd30cb68de511c5ccf75d357eab7f36740db6667cd67ac908d743e34e8da17e26。
- 输入manifest legalReferences=[]，conduct/penalty=null。已有56法律来源候选及59规则草稿的REVIEW_REQUIRED不等于CONFIRMED/ACTIVE法律引用；不存在已证实可直接填入的引用ID/版本。

仍缺12来源路径：case.id、task.ref、record.recordedDate、case.region、case.stage、materials[].ref、materials[].note、legal.references[].title/sourceVersion/locator/verbatim、review.comment。

- 其中案号/任务标识/记录日期/材料标识及四个引用路径为当前BLOCK项；地区、阶段、材料说明、review.comment可保留空值/警告。字段源路径与运行manifest坐标是不同合同，不能把“系统Case有ID”自动当案号、生成日期当真实记录日期，或把RBC待办ID猜成文书task.ref。
- 有权来源维护/材料核验主体沿正常源版本机制补可证字段及定位；查不到的继续缺项，不延长用途、不复制别案、不把专业结论填成事实。若既有源契约没有承载所需字段的入口，记录精确最小连接D/变更待批准，不造开发catalog旁路。
- 有权法律引用审阅主体在已有同案目标机制下验证来源版本/原文定位/当前时效/案件关系，通过正常search→bind→review形成真实CONFIRMED/ACTIVE精确版本。Case@1不能冒报告目标；未找到合法目标时明确缺目标type/id/version，不能临时编造报告。FACT_RECORD不额外引入ENF11/16/专业Conduct前置。
- 后续另获本案文书工程写许可、现时权限/用途有效时，才对该@1做真实页面revise→regenerate candidate/diff→显式adopt；模板实际可修订叙述字段只有summary、review_comment，受保护字段不可任意改。12缺项/旧稿/SHA/同版关系不因修订消失，采用不继承旧审核。实际同案写流程尚未执行，不借隔离结果宣称通过。
- 所有BLOCK消除、同案有效引用成立后，有权审阅人员才可实际提交/审核具体文书版本，并按合同导出同版DOCX及保存下载原件。模型不能代签，服务启动批准绝不包含该专业批准。正式业务验收仍由NEXT-V2-INT-ENF-01独立管理。

## 7. 必须另批的最小变化边界

- 新全局只读模式、写route中间件/代理ACL、schema只读切换、后台Importer独立开关或新claim幂等语义，都不是现有代码已证明能力；如现有部署控制不足，只提交限定D/变更，未经批准不实现、不放宽权限。
- 现有public-candidates-path空值、cleanup.enabled=false、正式启动分派及正常GET/PKCE属于已有机制；启动工具若要新增参数以传递既有空值，须先限定工具变更及非空CLI覆盖检查，不能借此修改冻结产品或增加开发状态。
- 本材料是决策输入，不通过结构检查宣布数据完整。20历史环境errors/119skip/全部旧失败保留；本轮只执行治理verify-all，前置实际1/TEST_EVIDENCE_STALE且47/41一致，后置verify-after.log实际退出0，属于本轮治理/证据门检查，不是重跑全仓Maven或恢复/业务验收；前置陈旧失败原日志不覆盖，不重跑Maven/浏览器/恢复或新搜索。

## 8. 唯一批准问题

**是否接受“已恢复16条可信原记录，但未知更晚历史仍未证明”作为当前运行边界，并授权在停机只读对账、既有后台写者关闭及入口控制落实后，仅受控启动5444、正常OAuth登录（允许必需的新会话记录）和读取现存同案不完整稿，同时继续禁止重复领取、本案业务写入、专业引用确认、文书审核/DOCX；任一前置不成立即保持停机？**

负责人此项回答只处置运行风险；不自动证明历史完整，不构成专业法律或文书审核批准，也不关闭IMP/REC/正式业务Pending。
