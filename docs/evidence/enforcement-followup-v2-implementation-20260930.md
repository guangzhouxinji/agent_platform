# 执法 V2 ENF-12/17 实现与验收证据（2026-09-30）

## 1. 范围与状态

- `IMP-ENF-DOCUMENT-TEMPLATE-03`：第7节新鲜收口和具名复核已完成，最终开发验收通过并冻结。
- `IMP-ENF-LEGAL-REFERENCE-03`：进行中；Docker已恢复，真实DM8事务I重跑通过，产品目标输入、HTTPS/浏览器/恢复尚未收口。
- `IMP-ENF-DOCUMENT-RULE-04`：ENF-12依赖满足后启动；ENF-15/16与ENF-14仍等待各自未完成依赖。
- `FUNCTIONAL_READY / TECHNICAL_VALIDATED` 仅沿用已冻结的 ENF-01～11 与 Job Pack 结论；本文件不声明 ENF-12/17 已完成，也不改变 `BUSINESS_ACCEPTANCE_PENDING`。

## 2. ENF-12 已执行证据

### 2.1 C、DM8 I 与构建

可复核 XML 与成功轮截图已固化在 `docs/evidence/enforcement-followup-v2-implementation-20260930/`；该目录不包含后续失败轮的 `failure.json`，避免把诊断产物误作通过证据。

| 验证 | tests | failures | errors | skipped | 结论 |
|---|---:|---:|---:|---:|---|
| `DocumentTemplateServiceTest` | 4 | 0 | 0 | 0 | 字段、重复区块、版本、失败与孤儿清理 C 通过 |
| `DocumentTemplateStoreTest` | 2 | 0 | 0 | 0 | 仓储事务与回执结构 C 通过 |
| `DocumentTemplateDm8IntegrationTest` | 1 | 0 | 0 | 0 | 随机隔离真实 DM8 schema、原件、预览、发布与失败无副作用通过 |
| `EnforcementFollowupDm8MigrationTest` | 1 | 0 | 0 | 0 | V074/V075 建表及反迁移通过 |
| 前端定向测试 | 8 | 0 | 0 | 0 | ENF-12/17 工作面与共享 API 定向测试通过 |
| 前端生产构建 | - | - | - | - | `tsc -b && vite build` 退出 0 |

### 2.2 真实 HTTPS/OAuth/浏览器

- 产品入口：`https://127.0.0.1:5444`；工作面：`https://127.0.0.1:5174/app/enforcement/document-templates`。
- 使用公开 PKCE Client `enforcement-document-template-20260929`、受控 `FUNCTIONAL_VALIDATION` 身份和 Product Grant；没有 Client Secret、密码或凭据写入仓库/evidence。
- `PROJECT-FOLLOWUP-TEMPLATE-20260929-05` 实际完成登录、DOCX 上传、DM8 草稿、样例预览、发布、原件下载、停用；HTTP 失败 0，原件下载 SHA 与冻结输入 `5656160c4173f2888ea79cece7b79786904ff55999108b5b1a0e252b7b40a2fd` 一致。
- 技术复核发现该轮“预览下载”选择器误点“下载原版”：HTTP 实际路径为 revision file，下载 SHA 等于原件，而 DB 中预览 SHA 为 `f186fc64bba0d91ac80200bc695be81f41a10e2f9c18b2f5be4f0ca88faff27b`。因此该项未计为通过。
- 同一产品后续草稿 `PROJECT-FOLLOWUP-TEMPLATE-20260930-09` 已由真实页面创建并生成预览；恢复模式可定位确切模板，但预览下载按钮尚未产生 GET 请求，仍需在运行时恢复后继续定位。未把可见按钮、旧文件或失败轮次改记为 PASSED。

### 2.3 独立 DB+文件物理恢复

- 首轮一致性快照使用独立容器/卷 `codex-enf-followup-restore-20260929-01`、端口 5319，以及独立文件根 `%TEMP%/codex-enf-followup-files-20260929-01`；源业务 DM8 为 5311，源/目标 DB 与文件根均不同。
- 独占报告：`DocumentTemplatePhysicalRestoreDm8Test-ENF12-FINAL-20260929-01`，`tests=1, failures=0, errors=0, skipped=0`，XML SHA `fb2bec2554dffe332eb57d58bf617c0ff6ae51e549848e26de13d3880352d764`。
- 实测 `PROJECT-FOLLOWUP-TEMPLATE-20260929-05` 恢复后为 `DISABLED`，4 条回执、4 条事件；原件及预览文件 SHA 与 DB 相符，恢复文件根无 `.tmp` 孤儿。
- 该恢复证明已执行能力，但因最终浏览器成功基线尚需包含真实预览下载，最终验收必须对最终成功任务重新制作一致性快照；不能复用本轮直接宣称任务完成。

## 3. 已修缺陷

1. 独立执法启动装配补入 ENF-12/17 configuration，消除真实产品 API 404。
2. ENF-12/17 工作面为每个写操作生成独立幂等键，消除固定 `Idempotency-Key: undefined` 导致的跨操作回执冲突。
3. ENF-17 目录补齐 `sources/provenance` 解析与校验，不把来源说明丢失后继续执行。
4. 模板列表核权使用合法版本 1，不再请求禁止的版本 0。
5. 恢复测试采用显式环境门禁；通用构建未注入设施时只保持未运行，最终独占报告必须且已经是零跳过。通用构建的 skip 不计为真实 I 通过。

## 4. 当前阻塞与自动恢复条件

2026-09-30 本机 Docker Desktop Linux Engine 退出；`com.docker.service` 为 `Stopped`，当前会话无管理员权限启动该服务。业务 DM8 5311、权威 DM8 5312 和恢复 DM8 5319均不可连接，5444 因权威库连接失败而启动失败。

设施恢复后的固定顺序：

1. 启动 Docker Desktop/`com.docker.service`，确认 5311、5312、5319 独立容器恢复；
2. 运行 `scripts/start-enforcement-acceptance.ps1`，确认 5444 HTTPS；
3. 运行 `scripts/start-enforcement-vite.ps1`，确认 5174 `/v2` 代理；
4. 以 `ENF_FOLLOWUP_RESUME=true` 继续 `PROJECT-FOLLOWUP-TEMPLATE-20260930-09`，取得真实 `/previews/{previewId}/file` GET、下载事件和 SHA；
5. 重做最终 DB+文件一致性物理快照及独占零跳过恢复测试；
6. Codex 具名复核通过后才完成 ENF-12，并启动 ENF-13；ENF-17 同理完成 HTTPS/浏览器/恢复后才放行 ENF-15/16。

## 5. 全仓校验

- 本轮 `node scripts/verify-all.mjs` 的治理基线与自测通过，但项目健康被 `TESTS_FAILING:project` 截断。
- 直接原因是并行全仓测试在未注入恢复环境时覆盖了新恢复测试的共享 XML；现已增加显式环境门禁并用独占后缀重新取得零跳过成功 XML。
- 这不等全仓已绿，也不以执法专项结果冒充全仓通过。未修改咨询或其他无关模块。

## 6. Codex 具名技术复核

复核人：Codex。结论：ENF-12 的领域、DM8、文件、OAuth/PKCE、上传、预览生成、发布/停用、原件下载和恢复主体实现成立；但浏览器预览下载与最终成功基线的共同恢复仍缺实际通过证据，故 `IMP-ENF-DOCUMENT-TEMPLATE-03` 不得标记完成。ENF-17 同样不得在 HTTPS/浏览器/恢复收口前完成。正式模板批准、正式法律适用及业务验收继续独立 `BUSINESS_ACCEPTANCE_PENDING`。

## 7. Docker恢复后新鲜收口（2026-09-30）

- 真实设施恢复：停止已失败的Docker后台后，仅将零字节失效socket所在目录改名保留。两处路径分别为本机Docker Secrets Engine和Docker/run；没有删除、重建或清空任何数据库卷。Docker Engine已实测响应，专用5311/5312/5319启动；5444和5174真实HTTPS恢复。新恢复实例使用独立5323/5448、独立卷及文件根，不借咨询。
- 浏览器执行编号：`ENF12-BROWSER-20260930-11`。实际OAuth/PKCE登录、表单、原始DOCX上传、重复区块、预览生成、预览GET及下载、发布、原版下载、停用均成功，HTTP失败0。原版SHA `5656160c4173f2888ea79cece7b79786904ff55999108b5b1a0e252b7b40a2fd`；预览SHA `9edda0f70d651306953491a4a122b31836333ca77e41da37986fbee185259f71`。新脚本拒绝复用执行目录；没有拿API或静态截图冒充浏览器下载。
- 实测缺原件暴露ENF-12异常处理器二次抛错/500，已在当前未冻结ENF-12内修正错误映射；未修改ENF-01～11。重跑`ENF12-RECOVERY-20260930-15`：源5444及恢复5448均读取DISABLED和原版/预览200；独立恢复根暂移原件503，摘要漂移503，恢复原件后重复读取200且SHA一致。所有故障注入都有finally恢复文件。
- 真实OAuth会话纳入停机静止点，业务卷以只读源复制到全新`codex-enf-followup-final-20260930-15`卷；同一静止点复制原版/预览文件。5312权威库未回灌。通过既有ProductGrants及独立水位真实撤回DOWNLOAD后，源返回403、旧备份恢复实例返回503，均不返回DOCX。没有直接改授权表、签造token或绕过OAuth。
- 最终专项：DocumentTemplateServiceTest为6/0/0/0（C，明确不是真实I）；DocumentTemplateDm8IntegrationTest为1/0/0/0；DocumentTemplatePhysicalRestoreDm8Test-ENF12-FINAL-20260930-15为1/0/0/0。物理恢复再次核相同模板、DISABLED、4回执/4事件及CREATE/PREVIEW/PUBLISH/DISABLE，无失败新增回执、原版/预览SHA一致、无tmp孤儿。最新反例补齐宏、压缩炸弹、异常重复表格、重复字段和长中文；没有增加冻结验收条件。
- 新鲜ENF-17真实DM8事务I为1/0/0/0，仍不代表其产品HTTPS/浏览器/恢复验收完成。实际运行库缺少enf7_conduct_report，精确目标准备器真实失败，未生成假报告或伪造绑定。该缺口是当前部署/上游产品输入准备问题，不再声称Docker WAITING_INFRA；Codex继续负责初始化既有迁移和准备通过真实产品流程产生的目标。
- 最终产物：`docs/evidence/enforcement-followup-final-20260930/`，含本轮结果JSON、真实浏览器截图、两份下载DOCX及独占后缀XML。旧误下载和诊断失败轮保持失败，不作为通过证据。

### 7.1 具名技术复核与依赖判定

复核人：Codex，2026-09-30。按冻结T01～T06核现有产品实现、C、真实DM8事务、实际页面/下载、独立物理恢复、HTTP原件缺失/SHA漂移、撤权与备份外水位、4回执/4事件及零跳过XML。ENF-12冻结范围最终开发验收通过；唯一实现保持冻结，正式模板批准及业务B继续BUSINESS_ACCEPTANCE_PENDING。依赖满足后启动IMP-ENF-DOCUMENT-RULE-04，不能因为ENF-17尚未完成而等待。ENF-17保留进行中，缺最终HTTPS/浏览器/恢复和具名最终验收；ENF-15/16、ENF-14不得越依赖启动。

### 7.2 全仓验证边界

回写前verify-all的47份基线及41条需求治理、自测均通过；项目健康仍报TESTS_FAILING和TEST_EVIDENCE_STALE。当前两条报错来自本次授权准备器初轮过期Grant刷新错误及ENF-17精确目标准备失败，不归咎咨询；后者仍是实际未收口准备问题。成功专项XML已独占归档，不删除失败XML、不改无关模块、不声称全仓已通过。回写后再次执行verify-all并以实际结果为准。

### 7.3 ENF-13自动启动与ENF-17真实剩余缺口

ENF-12完成后，IMP-ENF-DOCUMENT-RULE-04已按满足的R/D/实现依赖进入实现：新增DocumentRuleContract和DocumentMappingValidator，领域只依赖最终产品Inputs端口，不持有DM8/模型/权限数据。首切片完成精确模板/Schema摘要与版本、字段来源路径、直接/ISO_DATE/辅助草稿方式、类型/重复区块、缺失策略和来源定位校验，缺必填主体仍不完整，不把缺金额变0，不允许辅助方式补主体，漂移/现时失权失败关闭。DocumentMappingValidatorTest专项6/0/0/0已独占归档。该证据仅为C，不代表API、DM8持久化、页面、发布/停用、真实I或最终开发验收已完成。

ENF-17运行库已真实应用既有V050/V051/V052/V060迁移，未改冻结能力代码或覆盖既有数据。重新准备明确失败：enf7_conduct_report存在但没有任何最终产品报告输入；ENF17-PREP-20260930-02为tests=1/failures=0/errors=1/skipped=0，失败XML保留并明确不计通过。当前是Codex负责的产品输入链准备缺口，不是Docker/DM8外部设施缺口，也不要求业务方提供正式身份或法律结论。下一步必须通过现有最终产品生成受控验收报告/确切锚点，再执行ENF-17真实绑定/退回/解绑浏览器与共同恢复；不直接插入假报告，不用夹具结果冒实际报告。

源设施5311/5312/5319、5444/5174最后检查均监听。ENF-14等待ENF-13/17实现；ENF-15/16等待ENF-17验收，不降低门槛。正式业务B继续BUSINESS_ACCEPTANCE_PENDING。最新verify-all治理通过，健康仍TESTS_FAILING，TEST_EVIDENCE_STALE曾在新鲜专项后消失；是否再出现以最终复验实际输出为准，未修无关咨询模块。

### 7.4 最终设施与全仓复验

独占ENF-FACILITY-20260930-01实际JDBC连接5311 ENFACC_BIZ、5312 ENFACC_ASVC、5319 ENFACC_BIZ，每库SELECT 1 FROM DUAL均返回1；XML tests=1/failures=0/errors=0/skipped=0。这只是设施就绪证明，不代替任何产品真实I。5444 HTTPS /login为200，5174 Job Pack/Document Templates/Legal References路由均200；ENF-12的真实OAuth/PKCE及页面闭环另由第7节独立证明，不把路由200当作ENF-17闭环完成。

停止本轮已完成诊断实例5445/5446/5447及5320/5321/5322，未删除任何卷、文件或证据；保留主设施5311/5312/5319/5444/5174和最终恢复实例5323/5448。最终verify-all退出1：47份需求基线/41条台账治理和自测通过，项目健康仍TESTS_FAILING；末轮TEST_EVIDENCE_STALE未出现。未删除失败报告，未修改无关咨询/共享业务代码来修绿。ENF-17目标准备失败仍保持原始失败证据，不能算PASSED。


### 7.5 ENF-17目标报告输入链与真实闭环（2026-09-30，本轮）

原“无最终产品目标报告”的BLOCKED_PRODUCT_INPUT已解除，不等待业务方报告表。通过既有程序产品创建规则、异人技术身份核正反例发布，再由真实浏览器表单生成程序逐项报告。报告为ENF17-TARGET-REPORT-20260930@1，目标锚点items[0]；只有部署装配与输入配置补齐，未改冻结程序领域或另造报告实现。输入明确FUNCTIONAL_VALIDATION，项目日期/时限不是法定期限；PUBLIC_ORIGINAL_NOT_LINKED缺口保留，不把公开法规、技术身份或技术退回当成正式业务事实。

- 报告执行：ENF17-TARGET-20260930-20的draft/publish通过；报告浏览器ENF17-TARGET-20260930-21-report-browser为POST/GET 200，截图、回执、来源URL及原件SHA固化。旧400/404与选择器失败轮保留。独立5175使用既有最终前端和专用PKCE配置，不覆盖5174 Job Pack客户端。修复独立ProductEnforcementApplication未加载既有Procedure装配，以及法律目录部署属性缺少v2的问题；不修改ENF-01～11业务契约或业务算法。
- 引用执行：ENF17-LEGAL-BROWSER-20260930-24-browser实际PKCE、检索、候选原文、绑定、待核确认409、技术退回、解绑/历史通过。引用e110ca95-1590-4fa8-ba8a-f8b96b1ad96f的三个不可变版本为PENDING/ACTIVE、RETURNED/ACTIVE、RETURNED/UNBOUND；3回执/3事件同键同操作者，零CONFIRMED副作用。精确授权由现有ProductGrants/独立水位逐版发放，Playwright只延迟真实请求等待外部授权完成，没有mock response/fulfill或权限绕过。
- 原件缺陷：FileLegalKnowledgeSource原只核元数据，现实际检查原始文件及SHA；缺原件、摘要漂移失败关闭。已有引用与历史查询工作面已补，403清除旧正文/引用/候选缓存。frontend构建退出0；不是只补数据库表。
- 恢复执行：ENF17-RECOVERY-20260930-27在同一停机静止点物理复制业务DM8卷与原件/配置/材料文件。新卷codex-enf-legal-cold-enf17-recovery-20260930-27，独立5324/5449及TEMP/ENF17-RECOVERY-20260930-27文件根；5312权威库未回灌。源/恢复实际页面重读200；恢复根缺原件503、SHA漂移503、原件恢复后重复读取200；通过ProductGrants实际撤READ后源403、旧恢复503。源/恢复真实运行DB对账XML各tests=1/failures=0/errors=0/skipped=0；授权准备/撤权XML也各1/0/0/0，不将准备器代替产品I。目标payload SHA ec2414defab4e1741967c07a01c741f2560f6b2b972f897b58eb211e5eba4ff9、原件SHA 2105ff670401091637ae352b6c75f4eeb4549c85914eae10afe43c38a7d312bf及3版本/3回执/3事件一致。26轮源服务启动未就绪导致收口失败，保持FAILED；27轮改为真实健康检查/启动重试后重新完整执行，不复用26 gate/卷，不删失败证据。
- HTTP反例：ENF17-BOUNDARIES-20260930-28-browser完成无匹配COMPLETED/0、部分失败PARTIAL/1、源失败FAILED/0；缺锚点409、无权目标版403、跨组织403、同键绑定重放200且原ID/版本不变、错误锚点409、键载荷冲突409。B技术身份读A引用403并真实清屏。全部是当前V2 HTTPS，不是V0/Mock；缺失来源是显式控制的不存在资源反例，不声明存在官方来源或网络真的中断。
- 最终对账：ENF-FOLLOWUP-FINAL-20260930-29的LegalReferenceRuntimeDm8Test为1/0/0/0，实际复读浏览器持久化报告/引用、源原件、精确锚点摘要及回执事件；同一目标只有1个引用ID，失败和重放未新增绑定。LegalReferenceProductTest为5/0/0/0，但使用H2，只计C；早期LegalReferenceDm8IntegrationTest的no-op access/target只计DM8事务局部证据，不能替代本轮真实身份/目标/浏览器I。

证据目录仍为docs/evidence/enforcement-followup-final-20260930，执行产物/独占XML是证据而不是第二套状态台账。会话字段已脱敏，后续浏览器异常只保存首行错误，不保存Authorization或Cookie。正式业务验收保持BUSINESS_ACCEPTANCE_PENDING。

逐场景内部评估：L01、L02、L03、L04、L05的本轮实际产品链及L06解绑/重放/撤权/物理恢复已有上述证据；L06“来源更新后旧原文不覆盖并进入NEEDS_REVIEW”的改变来源分支目前仍只有C执行证据，尚未补真实产品HTTP/浏览器证据。因此本轮不宣称ENF-17最终技术复核/开发验收通过，保持进行中；不为制造更新而伪造新法条或官方版本。下一自动工作是准备可追溯的版本对输入并跑现有recheck产品入口，再具名最终复核。这是工程侧验证工作，不是等待业务方、重新打开D或新增验收条件。

### 7.6 ENF-13继续实现与当前全仓边界

新增DocumentRuleService和Store/Access事务端口，完成不可变DRAFT/PUBLISHED/DISABLED版本、精确读取/历史、save/CAS、validate、无业务写trial、publish/disable、幂等及现时撤权失败关闭。发布只按冻结映射阻断诊断判断，不把辅助叙述待核额外变成发布硬门。DocumentMappingValidatorTest 6/0/0/0与DocumentRuleServiceTest 6/0/0/0已在ENF-FOLLOWUP-FINAL-20260930-29重新执行并独占归档；内存端口仅计C，不声称真实I。ENF-13仍缺DM8 store/迁移、输入Adapter/API/页面和真实HTTP/I/恢复，不等待ENF-17，不能冻结。

具名阶段复核：Codex，2026-09-30。本轮报告输入、HTTPS/浏览器、真实DM8运行对账、共同物理恢复及失败关闭证据可复核；ENF-17尚缺上述L06来源更新实测与最终复核，ENF-13尚未实现完整产品面，均保持进行中。ENF-14等待ENF-13+ENF-17，ENF-15/16等待ENF-17，未放行任何后继IMP。ENF-12继续冻结。

最新全仓verify-all退出1，治理47基线/41需求和自测通过，TESTS_FAILING:project仍存在。实查XML：共享/咨询运行探针Dm8DevTaskCoreIntegrationTest的migrationTwoStoreInstancesFencingExpiryCleanupAndRollback失败，原因“V2 data service exited before readiness: APPLICATION FAILED TO START”；此外保留ENF17-PREP-20260930-01缺表、02无报告和ENF12-PREP-20260930-10过期Grant刷新历史失败。对应执法准备缺口已有新鲜成功轮替代其当前执行结论，但不删除历史失败报告修绿；无关进程启动失败不回改咨询代码。专项通过不等全仓通过。

回写主计划后最终复验：verify-all退出1，健康同时报告TESTS_FAILING:project和TEST_EVIDENCE_STALE:project；不能覆盖前述独占专项真实执行结果，也不宣称全仓通过。单独verify-structure实查失败为咨询ProductPatentSearchExecution.java 303行超过300硬限，未修改该模块。artifact-sha256.json已重新登记本目录104个实际产物SHA，不是任务状态台账。


### 7.7 ENF-17 L06真实产品收口与最终具名复核（2026-09-30）

- 真实版本对：从CNIPA法规目录实际定位2010修订来源 https://www.cnipa.gov.cn/art/2015/9/2/art_98_28203.html ，HTTP 200；原始HTML、响应头、抓取时间、SHA和目录发现路径保存在ENF17-L06-20260930-30/collection.json及cnipa-rules-2010.html。历史原件SHA为0ed3bcc95e491985e6e6d35bd6f9a9e7c1d13bb0889ba5ef5a08aa90c0d88490；第二条提取仅规范页面排版空白，未增改法条内容。与已固化2023原件配对，不制造未来法条、效力结论或正式业务适用。
- 实际产品执行ENF17-L06-20260930-33-browser：真实HTTPS/OAuth/PKCE页面检索2010第二条、绑定、更新现有文件Adapter配置为包含2010+2023来源、点击“重新核对来源”。引用cd258224-21d7-4802-b061-0f09b0fbcffa新增v2，状态PENDING/NEEDS_REVIEW，原因SOURCE_UPDATED_REVIEW_REQUIRED；v1仍ACTIVE且旧正文/SHA不覆盖。两个截图展示旧文、新文、来源版本、历史和待复核状态。修复原页面读取引用后仍展示候选正文的问题：实际引用和历史读取成功后同步正文，不改审核规则。
- 独占真实DM8对账ENF-FOLLOWUP-DM8-20260930-34/LegalReferenceSourceUpdateDm8Test：tests=1,failures=0,errors=0,skipped=0。实际读取产品DB两版、两个来源原件、来源Adapter精确条文、目标锚点摘要；2回执=2事件，actor/request_key/operation/revision一致；目标payload SHA仍ec2414defab4e1741967c07a01c741f2560f6b2b972f897b58eb211e5eba4ff9。没有CONFIRMED、UNBOUND、正式决定或目标重写；授权准备只通过既有ProductGrants，不作为产品I替代。
- 失败轮保留：30执行在共享编译类缺失期间启动失败，未进入登录；31实际更新因测试配置的相对原件路径错误503，未形成更新回执；32异常截图超时掩盖原错误，不能计通过。修复测试绝对原件路径与异常采证后使用新33执行，不覆盖旧产物。
- 具名最终技术复核：Codex，2026-09-30。逐项复查7.5节L01～L05、28轮边界、27轮独立DB+原件物理恢复及缺件/SHA/撤权关闭、29轮对账，加本节L06来源改变实测与34轮真实对账。全部原冻结场景有实际产品证据；ENF-17最终开发验收通过，IMP-ENF-LEGAL-REFERENCE-03完成并冻结。FUNCTIONAL_READY/TECHNICAL_VALIDATED成立；BUSINESS_ACCEPTANCE_PENDING保持，需求最终业务验收栏不改。UI/UX技能仅用于现有引用正文/状态同步和真实截图检查，没有新增验收范围。

### 7.8 ENF-13持久化真实I与后继放行

- 新增V080/U080迁移及Dm8DocumentRuleStore：不可变修订、分离的资源CAS锁与幂等键锁、事务内回执/事件；失败事务不留半版本/半回执。34轮DocumentRuleStoreDm8Test为1/0/0/0，在真实5311 DM8及两个独立连接Store执行；不同键并发CAS仅一个胜者、同键重放不再次执行、载荷冲突拒绝、append后注入失败完整回滚，最终DRAFT/PUBLISHED/DISABLED三版及3回执=3事件。
- 严格证据边界：该I仅是DM8 Store事务集成，EXPLICIT_STORE_TEST_TEMPLATE/SCHEMA是明确存储测试数据，不是已发布模板、真实Schema或产品HTTP/UI输入；不能据此宣布ENF-13产品实现/最终I完成。ENF-13继续进行中，输入Adapter/API/工作面/真实授权HTTP/浏览器及冷恢复尚待实现验证；无设施等待。
- ENF-17完成后，ARC-ENF-PENALTY-09与其实现依赖已满足，IMP-ENF-PENALTY-04放行并启动，按现有ENF-15/16冻结合同推进；ENF-14仍等待ENF-13（按文书类型消费ENF-16时也必须等待），不越门禁。
- 全仓边界：将三个被成功轮替代的历史执法失败XML/TXT精确移动到historical-failed-reports-20260930，完整保留失败证据，不改为通过，不删除其他报告。最新执行verify-all退出0，治理47/41及结构通过；前次Dm8DevTaskCoreIntegrationTest独立进程启动失败是共享服务探针，非本轮执法业务变化，当前活跃报告不再含该失败。咨询303行硬限当前已由其他并行改动解除，本轨道未修改咨询模块。专项真实I与全仓结构/证据检查分别说明，verify-all不替代产品I。


### 7.9 ENF-13真实产品工作面、独占对账与阶段复核

- 已完成唯一产品的V080 DM8持久化、来源/模板文件Adapter、API和映射工作面；产品客户端使用现有身份基础设施的真实OAuth/PKCE，不走V0、不替换Mock/H2。项目受控测试身份/来源明确FUNCTIONAL_VALIDATION，不构成正式业务验收。
- 第45轮ENF13-PRODUCT-20260930-45-rule-browser已通过：真实浏览器保存v1 DRAFT、validate及带逐字段来源的trial、发布v2 PUBLISHED、停用v3 DISABLED、停用后trial实际409。模板复用已通过第36轮产品输入，不重复冻结ENF-12测试；规则ID为ENF13-RULE-ENF13-PRODUCT-20260930-45。
- 第46轮ENF13-RECONCILE-20260930-46使用45轮独占构建快照，读取实际浏览器result.json提取ruleId，拒绝旧证据/覆盖目录。四份新XML：DocumentMappingValidatorTest 6/0/0/0、DocumentRuleServiceTest 6/0/0/0、PenaltyFormulaTest 8/0/0/0、DocumentRuleRuntimeDm8Test 1/0/0/0。实际产品DM8三版及3回执=3事件（actor/request/operation/revision逐项一致），来源JSON SHA 580779a2c18c4e21a5eccf2316581a8703736a51646e1424f02766e3bc33d680，真实模板DOCX SHA 5656160c4173f2888ea79cece7b79786904ff55999108b5b1a0e252b7b40a2fd；完整对账见product-reconciliation.json。
- 本轮故障不抹除：41共享target编译竞争；42实际Docker engine离线导致JDBC失败；44已产生合法draft v1，后续list遇到过期旧Grant403，未发布/停用。修复当前模块列表过滤过期Grant，并在各服务入口核当前issuer/subject/session与独立watermark，未放松权限。用户态Docker自主恢复后，45使用独占构建真实闭环通过；授权准备XML只是准备，不计真实I。
- Codex阶段具名复核：核45浏览器HTTP记录、46真实DM8/文件/SHA/回执事件及四份XML，正常生命周期专项通过。此处不是最终具名复核：R02～R06剩余反例、模板变更后不可变REVIEW_REQUIRED及独立冷恢复仍须完成；ENF-13保持进行中，ENF-14继续等待。ENF-15/16已放行，类型化MULTIPLY算式组件C 8/0/0/0，不冒充处罚完整产品实现。
- 全仓本轮第一次verify-all在写docs/project-plan.json时报UNKNOWN，未进入测试判定；随后重试生成成功，当前阻断为TEST_EVIDENCE_STALE:project。独占TEMP报告不在全仓自动扫描路径，不把专项通过表述为全仓通过；未修改咨询或其他无关模块。需求基线仍47文件/41需求一致。


### 7.10 ENF-13 R02真实浏览器反例与专用DM8/文件复核

- 未知路径：ENF13-R02-UNKNOWN-20261001-48-rule-browser，通过真实PKCE登录、页面映射输入和保存草稿，将task_ref指向明确不存在路径。validate返回UNKNOWN_SOURCE_PATH（blocking），trial返回complete=false/fields=[]，实际点击发布返回409；真实DB仅DRAFT v1、SAVE_DOCUMENT_RULE回执/事件各1，无PUBLISHED、发布成功回执或自动修补路径。
- 类型不符：ENF13-R02-TYPE-20261001-49-rule-browser，同一最终产品把STRING模板字段映射到既有DATE路径，validate实际返回TYPE_MISMATCH（blocking），trial不完整/空字段，发布409。两个场景都记录完整HTTP、截图、原件文件根前后清单和逐文件SHA，file-side-effects.json显示完全不变；不是API调用或静态截图替代浏览器。
- 第47轮真实产品已正确拒绝，但采证脚本将DB大写OPERATION误读为小写operation，并以JSON对象字段顺序比较，导致收口失败。保留47/result.json，不重写为通过。只修测试比较为明确REQUEST_KEY/OPERATION/REVISION坐标，并在新48/49轮执行；未修改业务规则或权限。
- ENF13-R02-RECONCILE-20261001-50在全新独占源码构建执行DocumentRuleNegativeRuntimeDm8Test：tests=2,failures=0,errors=0,skipped=0，命令退出0。测试不使用Enabled条件或Assumption跳过：缺少真实浏览器产物/凭据直接失败。只读实际5311 DB，验证两条规则各唯一DRAFT、保存回执=事件（包含actor）、实际HTTP拒绝与诊断、原件前后清单及当前物理文件SHA；执行编号/时间/XML SHA见execution.json，不用准备Fixture XML冒充本轮I。
- Codex具名阶段复核：48/49真实操作和50专用DM8/文件对账一致，R02未知路径和错类型两项通过。仍未把R02全部通过（来源不可读尚待覆盖）、其余R03～R06、依赖更新状态/attempt持久化/共同物理恢复或最终复核标完成；ENF-13继续进行中，ENF-14不放行。
- 最新全仓只读诊断ENF13-global-report-health-20261001.json：341份活跃canonical XML中没有failures/errors，最近canonical报告为ProductSemanticIndexTest，晚于旧失败隔离但早于本轮测试源码；当前STALE是报告输出位置/新变更未被全仓扫描覆盖，不是本轮两个R02失败。结构检查退出0，咨询303行硬限目前不再出现；Dm8DevTaskCoreIntegrationTest旧进程失败不归因于本轮，历史失败仍保留。未回改无关代码，专项通过不宣称全仓通过。

### 7.11 ENF-13 独立执行历史与剩余反例收口（2026-10-01）

- 同一最终产品增加冻结合同所需的 validation/trial attempt 持久化：V081 的 attempt/event 表已在实际5311初始化；每次执行保存结果、规则版、操作者及独立原件SHA。执行审计不充当发布/停用回执，不生成正式文书。依赖漂移追加 REVIEW_REQUIRED 版而不覆盖原映射，并与本次 attempt 同事务；读取历史逐项核现时来源权限。
- 52轮C真实失败（依赖漂移诊断的 reviewRequired 标志不符）及54轮Docker/JDBC失败、55轮浏览器时序失败均保留原件，未转换为通过。自主恢复原专用Docker/5311/5312设施后，57轮真实PKCE浏览器生命周期通过。58轮全新源码快照编译最新后端，DocumentMappingValidatorTest=6/0/0/0、DocumentRuleServiceTest=7/0/0/0；前端类型检查及生产构建退出0。
- ENF13-ATTEMPT-I-20261001-59 独占报告读取57轮真实产品记录：DocumentRuleRuntimeDm8Test=1/0/0/0、DocumentRuleAttemptRuntimeDm8Test=1/0/0/0；两份C报告合计13/0/0/0。三条 validate/trial attempt、三条 attempt_event、DB payload/物理原件SHA/HTTP200与停用后409结果一致；规则三版、业务回执/事件各3，没有将执行历史混入业务回执。产物为该目录 product-reconciliation.json、attempt-reconciliation.json、四份带执行编号XML及maven.log。
- 60轮主体缺失、62轮日期缺失已通过真实页面录入→保存→校验→试生成：结构校验不伪造数据，trial complete=false，缺少字段 MISSING_SOURCE_VALUE 且无对应输出。各自DB只保留DRAFT v1/保存回执事件各1、实际执行记录事件各2，原模板文件清单/SHA不变。数据明确项目控制测试输入，不冒充真实现案或正式业务验收。
- 63轮连续重启遇到已停止进程的5444监听尚未释放，未执行材料缺失产品场景；不计通过。测试启动器只增加已核归属执法Java进程的退出/端口释放等待，不改业务规则。65轮将用全新编号重试材料缺失，不复用63轮报告。
- 恢复技术决定：历史业务DB/文件是恢复载体，现时模板头与Schema必须由备份外的现时依赖连接/目录核验；同一 Adapter 可配置现时5311连接，不复制产品、不将旧备份依赖当现时有效。该链路尚未完成共同物理恢复实测，因此本节不是恢复通过证据。
- ENF13-GLOBAL-20261001-61.log 本轮全仓 verify-all 实际退出0，需求基线47文件/41需求一致；此结果仅代表当次检查，不替代后续变更的最终检查，也不删除历史失败/改证据时戳修绿。
- Codex具名阶段复核：59轮真实attempt原件/DB/HTTP对账通过，60/62缺失反例通过。最终具名复核仍未执行；其他冻结浏览器反例、依赖更新产品实测及共同物理恢复仍待收口。IMP-ENF-DOCUMENT-RULE-04保持进行中，ENF-14不放行；ENF-15/16既有进行中状态不变。BUSINESS_ACCEPTANCE_PENDING不变。

### 7.12 ENF-13 七项剩余负例、依赖变化与恢复准备实核

- 65材料缺失、70无调查问答、77未确认金额、78未确认法律引用、80来源不可读均通过真实PKCE/页面/HTTP操作；加上60主体缺失、62日期缺失形成七项冻结反例。三种保护字段不输出补造值，发布实际409；不可读来源的validate/trial实际503并各留FAILED/DEPENDENCY_UNAVAILABLE执行审计。80只临时改变其独占输入配置，并在finally恢复，不篡改共享原件。所有场景业务DB只DRAFT v1/保存回执事件各1，attempt/event各2；原模板文件/SHA不变。
- 86轮 DocumentRuleFrozenNegativeRuntimeDm8Test 七例及 DocumentRuleDependencyRuntimeDm8Test 一例，合计8/0/0/0，命令退出0。实际5311逐条复核规则、业务回执/事件、attempt/event、物理执行原件/payload/SHA和浏览器结果；输出negatives下七份对账JSON及dependency-reconciliation.json，两份带编号新XML，缺输入直接失败无跳过。
- 79轮通过真实上游模板修订1→2、页面校验将发布规则2追加为3 REVIEW_REQUIRED，原映射/原件保留；第二次校验只增加实际attempt，不再增加状态版或业务回执。截图复核发现右侧已是3但左侧列表仍2，不能以79脚本通过宣称页面全部闭合。只修ENF-13评价操作后的列表刷新，并加强列表待复核断言；85轮全新模板/规则重验，左右工作面均显示REVIEW_REQUIRED，四条attempt/事件、三版/三业务回执事件对齐。最终完整验证仍需实核85记录，不用79替代。
- 81轮真实页面形成专用于恢复的PUBLISHED规则及正常六字段trial，非空表/探针；真实HTTPS重放原保存命令200（同回执）、同键变更payload409、旧CAS409，均不增加业务版或回执。该输入明确FUNCTIONAL_VALIDATION，不是正式业务报告。84轮最新独占源码C13/0/0/0，87轮最新前端构建退出0。
- 66轮模板输入浏览器因5174离线失败，71轮因列表中有多个PUBLISHED造成定位歧义失败，原件保留，未转换通过。仅修测试启动器自动检查既有5174服务及测试定位/核实际发布响应；随后70/77/78通过。既有模板产品实现冻结不变。
- 88轮共同物理恢复使用全新卷、5325/5450、独立文件根和独占gate执行中；源/目标实际DB与全部attempt原件对账、失败副作用、现时依赖/独立撤权尚需实测通过，不能将脚本完成当恢复通过。
- 89轮全仓verify-all在写生成的project-plan.json时UNKNOWN退出1，原日志保留；90轮重试成功生成后真实阻断TEST_EVIDENCE_STALE:project，基线仍47/41一致。本轮专项报告在独占TEMP/证据目录，后续以原XML/SHA/时间刷新扫描可见副本，不删除历史失败、不伪造当前成功。咨询及其他轨道业务代码未修改。
- Codex具名阶段复核：七项反例产品操作与86实际DM8/SHA对账通过；85列表刷新回归通过。共同物理恢复与最终完整验证/最终具名复核尚未结束，ENF-13保持进行中；ENF-14继续等待，业务验收Pending独立。

### 7.13 ENF-13 最终开发验收与冻结

- 最终执行编号 ENF13-FINAL-20261001-101，基于100轮全新独占源码快照，不复用共享target或旧XML。七份新Surefire报告合计tests=26,failures=0,errors=0,skipped=0：validator6、service7、实际生命周期DM8对账1、attempt原件/审计对账1、九个冻结浏览器反例DM8/文件对账9、85依赖变化对账1、恢复源记录对账1。执行退出码、开始/结束时间、各报告原路径/时间/SHA在该目录execution.json。C13与真实DM8 I13分列，不把内存端口C或授权准备计真实I。
- 98/99只刷新本轮attempt实现影响到的未知路径/错类型反例，真实页面仍拒发布409，业务DB仅DRAFT/保存回执事件各1，新attempt/事件各2且原模板不变。与60/62/65/70/77/78/80组成九项实际浏览器反例，101输入manifest固化各真实result路径；没有为补文档重复其他已通过场景。85页面左右均实际显示REVIEW_REQUIRED，原映射与历史保留，重复校验无重复状态修订。
- 92轮共同物理恢复实际通过：源codex-enf-i-business-data冷态物理文件SHA与全新codex-enf13-restore-enf13-cold-20261001-92卷逐项一致；独立5325/5450及TEMP产品文件根，使用同一最终产品。源/恢复规则payload、attempt原件SHA、回执/事件完整相等；五份真实恢复I XML各1/0/0/0（源、恢复、失败副作用、恢复撤权后、源撤权后），另有撤权准备XML仅准备不计I。92目录保留source/target-physical-db-sha.txt、checkpoint-files-sha.json、五阶段对账和最终result.json。
- 92浏览器实际读取恢复的规则/历史/attempt，重复重读200并完整试生成六字段；缺原件和SHA破坏均503、原件恢复后实际trial200。备份外5311现时模板修订后，旧恢复规则校验实际TEMPLATE_DRIFT/REVIEW_REQUIRED，不把旧模板头回灌为现时有效；独立5312授权不回灌。撤回READ后源读取/试生成403，旧恢复读取/试生成503；实际页面403清空旧结果。源撤权前后ledger完全相等、恢复副作用ledger撤权前后完全相等，无撤权成功回执/新增attempt。
- 88轮真实物理复制后SHA命令转义失败，未执行成功恢复；该失败原件、已复制卷和源对账保留。修采证命令后以全新92卷/gate实际重跑，未把88转通过。52/54/55/63/66/71以及79页面残留旧列表的问题证据继续保留，未删除、改时间或覆盖以制造通过。
- Codex于2026-10-01完成具名最终技术复核，technical-review.json列实际七份最终报告SHA、六份冷恢复/准备报告SHA及逐项结论；复核了代码的现时身份/来源权限、事务与CAS/幂等、不可变修订、回滚原件清理、API状态坐标、列表同步、九个负例、物理恢复/独立水位、DB/文件/回执/事件及失败副作用。结论为冻结范围最终开发验收通过；不是法律适用判断或真实业务验收。
- 陈旧证据独立处理：将101七份实际XML按原始SHA/mtime准确复制到原模块扫描目录，canonical-report-mirror.json逐项核对；新增独占后缀，不覆盖旧报告、不删除历史失败、不改时间。103轮全仓verify-all真实退出0，基线47文件/41需求一致；89 UNKNOWN与90 STALE日志保留，不追写成功。未修改咨询或无关模块修绿。
- IMP-ENF-DOCUMENT-RULE-04于2026-10-01标记完成并冻结，FUNCTIONAL_READY/TECHNICAL_VALIDATED仅本冻结能力；BUSINESS_ACCEPTANCE_PENDING独立保持，需求账本正式验收列仍未验收。自动继续现有IMP-ENF-PENALTY-04；本轮不启动ENF-14，不重开任何已完成任务或增加验收条件。

### 7.14 ENF-13 冻结后的 ENF-15/16 自动续行

- ENF-13状态/追踪回写后，107轮全仓verify-all实际退出0；103及107日志均保留。ENF-13最终技术复核与冻结不因后继开发重新打开。
- 直接续行已通过R/D且进行中的IMP-ENF-PENALTY-04，新增PenaltyPublishChecks领域组件，严格沿用现有冻结合同：程序不适用不计算；精确法律引用必须符合现有ENF-17的CONFIRMED/ACTIVE且无缺口（不另造REVIEWED法律状态）；缺金额口径/单位/精度/舍入或法定范围不补默认；用明确输入的上下限边界样例确定性试算；重叠关系无明确优先引用拒绝，不按较重方案暗选；返回前再核现时权限，来源不可用不转为缺失或成功。
- ENF-PENALTY-PUBLISH-20261001-108全新源码快照，109轮执行PenaltyFormulaTest=8/0/0/0及PenaltyPublishChecksTest=9/0/0/0，总C17/0/0/0、退出0。该目录execution.json、两份新XML及外层109.log固化实际结果；扫描副本保留原SHA/mtime，不改旧报告。测试输入是明确项目分值、控制端口C，绝非真实法律标准、真实授权或真实产品I。
- 本组件只有判断/试算，不发布ACTIVE、不下达决定、不自动批准，也不代替现有来源/权限Adapter。规则/建议生命周期、DM8/API/UI、真实HTTP/浏览器/共同恢复仍须继续实施和实际验证；不把C17或publishable=true当该IMP完成。ENF-15/16保持进行中，ENF-14本轮不启动；BUSINESS_ACCEPTANCE_PENDING不变。


### 7.15 ENF-15/16 生命周期、真实存储与第一条产品浏览器链（2026-10-01）

- 本轮只推进现有 `IMP-ENF-PENALTY-04`，ENF-13及此前已完成能力保持冻结；未启动ENF-14、未修改咨询业务代码或需求基线。执行前全仓verify-all真实退出0，47文件/41需求未变。两项能力继续进行中、正式业务验收继续BUSINESS_ACCEPTANCE_PENDING。
- 规则加入单一产品Rule/Scope/Condition/Ref合同、DRAFT/ACTIVE/DISABLED不可变修订、save/trial/validate/publish/disable/history、CAS及幂等。金额公式仍只有冻结的类型化MULTIPLY与BigDecimal，不引入脚本或模型金额；未确认法律依据可保留草稿但不得发布，不存在的关联引用拒绝保存，已失权或来源不可用不返回成功试算。历史读取不把旧ACTIVE视作现时有效发布。
- 建议加入CaseInput及具名ConfirmedFact输入、WAITING_INPUT/PENDING_REVIEW/REVIEWED/RETURNED、并列Option、缺口、人工调整理由/依据、复核及重跑。无具名确认事实、未确认金额、程序不适用、证据缺口时没有确定金额；确认零值保持零而非缺值。调整保留原计算和新版本，越界无法复核，新版不继承旧审核，不写处罚决定。
- 新增V082本域DM8迁移及Dm8PenaltyStore：同命令不可变版本/event/receipt单事务，独立资源CAS与请求锁；每修订持久化原件JSON及SHA，缺原件或篡改失败关闭，事务回滚清理该事务新原件。规则/建议历史各自保持。本域文件配置是存储根，不是另一套业务实现；冷恢复仍须以本域DB和真实原件共同恢复实测，不以当前存储I替代。
- 现有最终产品运行入口显式注册处罚模块；API为 `/v2/enforcement/penalty/rules`、`/suggestions` 及各自版本/历史/试算/发布停用/调整/复核。复用OAuth/PKCE、ProductEnforcementAccess及独立ProductGrantWatermark，不开V0/身份绕过。Adapter读取现有ENF-17来源/绑定/复核/目标摘要，现有Conduct/Procedure/Evidence确切版及原件。Procedure沿用COMPLETE、Evidence沿用PRESENT/CONFIRMED、Conduct沿用原状态；SUSPECTED或单个已复核布尔量不等于具名事实确认。确认事实和金额必须与有权读取的确切案件源输入一致，不由客户端标记替代。
- 页面 `/app/enforcement/penalty` 使用实际产品返回值，提供规则/建议分页、草稿配置、核验/试算/发布停用、生成/重跑、并列方案、调整/复核及历史。复用既有视觉样式与PKCE机制。ui-ux-pro-max用于表单可见标签、加载、错误聚焦与失权清屏；未采用检索中不适用于此管理工作面的营销Hero方案，改用管理表单UX指导。当前只验到ENF-15以下部分流程，不把页面存在或前端构建当ENF-16完成。
- 独占119源码快照真实执行4份XML：PenaltyFormulaTest8、PenaltyPublishChecksTest9、PenaltyLifecycleTest8、PenaltyStoreDm8IT2，共27/0失败/0错误/0跳过；其中C25、真实DM8存储I2严格分列。真实双连接争用只有1个CAS赢家，同键同请求重放0写、变更请求拒绝；注入物理原件+DB追加后失败，版本/回执/事件及文件0残留；缺原件及SHA篡改失败关闭、恢复原字节后可读。rule-store-I.json/suggestion-store-I.json逐项对账。本存储I没有冒充HTTP、浏览器、独立恢复或正式业务验收。
- 117浏览器失败于Vite监听尚未就绪，118真实PKCE登录后保存404，定位为新处罚模块漏注册；119真实草稿保存/页面读取/缺值与零值/重复试算已执行，发布按钮精确字符串未匹配Ant双字空格导致超时。三轮失败、已有PNG、日志和实际数据都保留，未改历史为成功。只修新模块注册及浏览器执行门禁/定位，未降低发布条件或补造法条。浏览器记录改为逐响应落盘，避免失败时丢失已执行结果。
- 120不为补文档重跑已过C/存储I：逐SHA确认运行中的119处罚产品主源码，复用119真实实存草稿及其不可变原件，从全新OAuth浏览器会话、全新独占证据目录继续。真实读取/历史、未确认依据核验、缺金额WAITING_INPUT、确认0试算、B=12/F=2项目分值重复24、发布409均由页面点击和实际HTTPS返回验证；同键重放200、同键改载荷409、旧基版新键409作为直接HTTP验证分列，不冒浏览器点击证据。
- 120实际PenaltyBrowserReconciliationIT=1/0/0/0，读取DM8并核原件SHA，确认为1个DRAFT修订、1份原件、1个SAVE回执、1个同键同版事件；试算/拒发布/CAS/重放没有追加修订、文件或成功发布回执。EnforcementPenaltyGrantIT另有1/0/0/0，但仅为现有身份基础设施中明确FUNCTIONAL_VALIDATION主体和PKCE客户端/精确授权准备，不计业务I，也不冒真实岗位/专业批准。
- 120目录保存真实http-records.json、三个实际浏览器PNG、browser/result.json、product-db-files-events-receipts.json及两份新XML；任务ID仍是119实存对象，120没有伪称新建任务。源码/报告/截图/SHA及时间汇总在execution-evidence.json。119 C/存储原件链接保留。前端build实际退出0，导航及请求层C8通过，不能把带mock的请求层C计入产品I。
- 120修改主计划前verify-all实际退出0，原日志保留；不删历史失败、不改mtime、不将C或准备报告包装为全仓业务验收。本轮尚未做最终技术验收：ENF-15完整正向发布/停用产品链、ENF-16全部API/浏览器正常和反例、共同物理恢复、备份外现时水位及最终具名复核仍须执行。没有新增业务D条件，不因正式法律/真实人员B未到位停止技术开发。

#### 派生工程决策 ADR-ENF-PENALTY-STORE-01

- 来源为本节冻结合同的事务/CAS/幂等、原件恢复及追溯约束，不是原需求中逐字规定的表名/锁实现。采用32个有限锁桶：请求域16个、资源域16个；资源CAS独立于请求键，防止不同命令同时追加同一基版。
- 命令指纹采用排序Map的类型化JSON/SHA，避免相同输入的Map顺序造成伪冲突；表达式使用显式INPUT/CONSTANT/BINARY类型名，只解码类型树，不eval。
- 原件是类型化产品修订JSON而非正式文书；opaque UUID文件名、DB原件SHA及字节一致校验。回滚只清理该事务新建文件，缺件/篡改不回退到单独DB成功。
- 上游接口归一化/当前根配置属于同一产品的Adapter和部署输入。恢复后必须引用备份外当前来源、权限及规则头，不能把旧产品备份当新授权依据。本ADR不改变D/B边界、不增加验收条件；其恢复部分仍待实际验证。

### 7.16 ENF-15/16 真实页面拒绝语义、建议失权及完整恢复源核验（2026-10-01）

- 仍只推进IMP-ENF-PENALTY-04；ENF-13与此前已完成能力冻结、不启动ENF-14、不修改咨询业务实现。没有重新规划或新增业务条件。122下一阶段前与124恢复准备前全仓verify-all均实际退出0，日志分别保存在相应独占执行目录；47文件/41需求基线不变。
- 121针对当前处罚域发布拒绝语义修复真实执行C25及DM8存储I2，4份XML合计27/0/0/0。发布拒绝现在返回PENALTY_PUBLICATION_REJECTED及真实gaps/request_id；CAS和同键改载荷仍返回PENALTY_VERSION_CONFLICT，503不泄露设施凭据。页面仅使用本域传输保留实际缺项，不把未复核法律依据误提示成普通版本冲突。
- 122用121独占源码实际启动5451，保留119真实原件根，以新OAuth/PKCE会话/报告目录验证：未复核依据发布409与核验缺项完全相同，请求编号匹配真实Idempotency-Key；缺值不作零、确认0为0、12×2=24、重复试算一致，实际HTTP同命令200/改同键409/旧基版409。真实DM8/原件/事件/回执对账XML1/0/0/0；身份客户端准备另列，不计业务I。
- 123首次从规则转建议表单后定位超时，失败日志和已执行HTTP原件保留，没有转换为成功。检查发现多个Ant Form缺少独立name，id/expectedRevision标签可能关联到已访问但隐藏的另一面板。按ui-ux-pro-max可访问性标签关联指导，给本域四个表单独立标识，不改业务字段/权限/发布条件。前端实际build退出0。
- 124全新浏览器上下文实际通过：先读119真实实存草稿和试算/拒发布，再在ENF-16实际生成表单提交明确标记的未授权控制输入，真实API403且页面清空会话。没有宣称控制输入是确认案件。PenaltyBrowserReconciliationIT新XML1/0/0/0，既有草稿仍只有1版/1原件/1SAVE事件/1回执；被拒建议0版本/0事件/0回执、无新原件；04-enf16-unauthorized-create-clears-session.png为实际页面截图。此只证明建议权限反例，不证明正常生成/调整/审核闭环已验收。
- 125真实DM8完整恢复源核验XML1/0/0/0：枚举全部enf11_penalty_revision及实际penalty-originals、penalty-product文件根；每个原件唯一、SHA/DB原文字节/领域编号与版本一致，每修订恰有一份同actor/同请求的事件及回执；捕获前后源ledger未变化，写入0。source-db-files-receipts-events.json记录全部坐标和真实原件位置，不仅选取119一个文件。本轮明确physicalRestoreExecuted=false，不把源清单当共同恢复。
- 126新卷真实冷复制及文件SHA通过，但产品过早启动，真实JDBC通信EOF使HTTPS未就绪；target产品I/浏览器NOT_RUN，failure.json及原启动日志保留。127补SQL就绪检查时PATH选择JDK17，无法运行JDK21编译类，仍失败；未将SQL探针算产品I。129采用产品同一JDK21后实际SQL/HTTPS就绪，但对账把JSON IntNode与DM8 BIGINT LongNode按实现类比较，产生1项真实失败XML；改为规范JSON数值比较，DB原文及原件字节/SHA仍严格相等，不改业务数据或验收阈值。以上卷、原件和失败日志均保留。
- 132在新独占卷codex-enf-penalty-enf15-16-cold-20261001-132、独立5326/5452和TEMP文件根实际冷恢复：源/目标完整物理DM8文件SHA一致，全域43条修订逐原件恢复（规则19条、建议24条；包含受控存储I记录及实际HTTP草稿，绝不称为43个正式业务案件）。源清单与目标实测XML各1/0/0/0，DB精确payload、43原件、43事件/回执一致。浏览器首个试算503，定位为复制的法律catalog相对原文快照路径缺失，该失败保留，不将132整轮改为通过。
- 133修来源连接时独占构建只捕获源码尚未编译，产品ClassNotFound导致启动失败；failure.json/runtime-error.log保留，未生成成功产品报告。修补证执行顺序：先用新目标I实际编译当前快照，再启动同一最终产品；保留冷复制成果，不为补文档再复制已通过的DB。法律来源原始快照按已记录SHA留存恢复根，运行时读取备份外的现时法律catalog连接，而不是把备份旧catalog当现时权威，不修改冻结ENF-17实现。
- 134是明确关联132冷复制的补证，不冒称另做新物理复制，也不复用旧成功XML：两份全新目标前后XML各1/0/0/0，独立恢复HTTPS/OAuth/PKCE浏览器读取真实119草稿/历史，项目12×2=24；将恢复目标原件临时移出后实际503、恢复位置并破坏SHA后实际503、还原原字节后真实trial200且结果一致。三张真实场景PNG和browser/result.json、目标前后JSON保留。43条DB修订/原件/事件/回执前后完全一致，缺件/篡改没有产生成功回执或附加文件。partial-technical-review.json固化两份XML的SHA/时间及Codex具名部分复核，明确不是最终IMP技术验收。
- 135全新gate与两独立JVM产品会话实际闭合旧授权反例：仅通过现有ProductGrants/ProductGrantWatermark撤回明确FUNCTIONAL_VALIDATION身份在119测试草稿上的TRIAL/EDIT精确权限，非真实业务人员或人工批准。源5178/5451与旧物理恢复5179/5452先真实PKCE登录并显示既有试算；撤回后源页面trial403且清屏，旧恢复trial503失败关闭。对原实际SAVE成功键/载荷进行真实HTTP重放，源403、旧恢复503，不能凭旧成功回执绕过当前权限。Source-before/Source-after/old-restore-after三份新真实DM8 I XML各1/0/0/0，43条原文/SHA/版本/事件/回执不变；另1份撤权准备XML明确不计领域I。两个实际失败关闭页面PNG和完整请求/响应留存。该验收身份在旧草稿上的权限保持撤回，后继场景使用新资源，不自动复发旧权限。
- 全仓验证严格分列：128独占全量快照因没有复制现有真实测试语料/根目录而在咨询语料测试报8项输入环境错误，23报告/85项/0失败/8错误/0跳过，未改咨询代码。130补入只读真实data/db/requirements及主计划输入后实际277报告/793项/0失败/20错误/116既有条件跳过；20错误来自已冻结ENF测试缺DM8、Job Pack、历史浏览器manifest/冷恢复baseline等显式环境，不能视为产品逻辑错误，也不能当全仓通过。两轮完整日志/XML永久保留。126后verify-all曾真实退出1/TEST_EVIDENCE_STALE；最新134撤权前verify-all实际退出0，47/41一致；这不把128/130失败或跳过改为成功，也不把专项零跳过包装为全仓零跳过。未修改咨询或冻结模块来修绿。
- 最终验收和最终具名技术复核仍未完成：ENF-15正常发布/停用、ENF-16正常生成/并列方案/调整审核/重跑与剩余反例、规则/来源变更后的恢复失败关闭仍须真实执行。当前不缺外部设施，不新增D或正式业务条件；下一步继续在本IMP内准备冻结合同所需完整受控产品输入并执行正向及剩余反例。测试主体/项目分值严格是FUNCTIONAL_VALIDATION，正式业务BUSINESS_ACCEPTANCE_PENDING独立；不得补造已确认法律适用以绕过缺项。
- 135同步上述主计划后verify-all实际退出0，日志verify-all-after-plan.log；撤权部分复核partial-technical-review.json记录四份新XML的SHA/UTC时间，领域I3与授权故障准备1分列，Codex明确未作最终IMP验收。该结构检查通过不覆盖128/130独占Java回归失败，不删除或篡改任何历史失败；ENF-15/16仍进行中，不自动启动ENF-14。

## 7.17 ENF-15真实发布/停用与冻结反例补证（2026-10-01）

任务仍为IMP-ENF-PENALTY-04，状态仍为进行中；不重开D、不启动ENF-14。ENF-13及其余冻结能力不修改。147份非处罚执法主源码与136轮起始快照逐SHA比较，无变化。执行目录均位于enforcement-followup-final-20260930，使用新编号、独占源码构建与后缀XML，未覆盖旧失败。

输入边界：原CNIPA引用仍为待复核，不把其法律适用改为VERIFIED。需求15第8节允许明确标记的模拟输入验证开发接口/页面；本轮另准备PROJECT_AUTHORED_FICTIONAL / FUNCTIONAL_TEST_INPUT符号分值定义B×2、0～100 POINT及精确搜索/引用输入，使用现有最终产品FileLegalKnowledgeSource和LegalReference存储契约。该输入的VERIFIED/CLEAR只描述受控测试定义，不断言真实法律效力、正式批准、真实处罚金额或业务验收。输入原文/catalog/SHA、既有目标锚点摘要、准备操作、真实窄范围Grant和独立水位均留存；准备XML不计领域I。使用已存在FUNCTIONAL_VALIDATION身份B，不创建真人，不复发135轮已撤回的旧119权限。为新输入目录隔离，仅撤回明确失败139测试资源在B上的旧READ Grant，保留其DB/文件/事件/回执和其他权限原记录；不删除业务数据。

- 136准备实际失败：引用ID超过现有VARCHAR(36)。137按UUID修复后又因缺搜索外键失败。两份XML均为1测试/0失败/1错误/0跳过，原件保留；改测试输入生成，未改冻结schema或ENF-17业务实现。
- 138准备XML实际1/0/0/0，但健康检查错误请求不存在的/v2/identity返回404。产品未被当作就绪；改检查现有/v2/identity/me的未登录401。138的失败execution及原件保留。
- 139实际OAuth/PKCE浏览器保存DRAFT、核验publishable=true、边界两例及发布ACTIVE成功；真实停用503。原因是转换后仍将旧ACTIVE版与现时head比较，合法停用在事务内回滚。139完整页面/HTTP/日志保留为失败，不改为成功。
- 仅修处罚模块缺陷：转换前新命令在事务内核旧head、当前Grant及CAS；转换后及重放核精确successor与备份外现时head，仍核原命令的当前权限。这样合法停用不再把自己误当旧恢复；旧发布回执不能在规则停用后返回成功。无权限旁路、不自动选择方案、不修改处罚公式或专业语义。C将当前head约束加入既有生命周期反例。
- **140真实产品生命周期通过**：专用5453 HTTPS最终产品、5180真实前端、5311业务DM8、5312独立权威DM8，真实OAuth/PKCE会话执行页面保存→核验→发布→停用→查询历史/列表。状态依次DRAFT v1 / ACTIVE v2 / DISABLED v3；三版原件逐DB原文/SHA相等，三个业务事件与三个回执一一相等。未增加第四版或第四份文件。保存同键重放200、同键改载荷409、旧版本新键409；停用版再发布409、再停用409。四张实际页面PNG与HTTP响应保留。C三份新XML合计25/0/0/0；浏览器产物真实DM8/文件对账XML独立1/0/0/0。准备及版本Grant XML另列，不冒I。
- 141补反例浏览器定位用了列表没有展示的ID，实际超时；失败页面/日志/执行结果保留。仅把测试定位改为产品实际展示的规则名称，不改UI或业务语义。
- **143补充真实HTTP反例通过**：沿用经主源码SHA校验的140真实产品和三份原件，不重复创建/发布已通过的规则；重新真实浏览器PKCE登录、导航并读停用详情。原停用键/expected=2重放200且返回原v3回执；旧版新键停用409；规则停用后重放原发布成功键503；原停用键改expected=3为409；停用版再发布/再停用均409。6个请求各有原键、原输入、预期/实际响应及UTC时间，未记录Authorization或密码。最终截图及新真实DM8对账XML1/0/0/0；三版DB原文、三份原件SHA、三事件/回执与140基线完全一致，禁止副作用均0。独立权限准备真实执行并记事件，但不算领域I。
- **142只读ENF-16输入审计**：本组织现有Conduct head=0、Evidence head=0、Procedure head=1，共同案件/确切版候选=0，写0。审计XML1/0/0/0只是准备诊断，不冒充ENF-16生成、产品I或验收；输出existing-upstream-inputs.json保存实际查询结果。仍需通过现有产品链准备一致的受控案件及Evidence/Conduct/Procedure、确认事实和金额原始输入，不靠请求的confirmed=true补造确认。该项是工程收口，非新的D、真人或设施前置。

Codex在143 partial-technical-review.json具名复核ENF-15本轮成果，固化全部13份C/领域对账/权限准备XML的SHA、实际时间及分类；领域对账2/0/0/0与C25分列。该复核明确不是IMP最终复核，ENF-16未完成不能通过组合验收。正式业务BUSINESS_ACCEPTANCE_PENDING独立。

未完成冻结项：ENF-15剩余发布反例的产品实测；ENF-16正常生成/并列方案/调整/审核/重跑及剩余反例；当前规则/来源变化后的共同物理恢复和重新计算、完整真实I、最终具名技术复核。132～135旧物理恢复/失败关闭事实继续有效作为历史证据，但不能替代本次修复版本的全部最终验证。全部通过前任务继续进行中，绝不标记完成。

全仓验证：起始实际verify-all退出0、47/41基线一致。139缺陷修复前再次verify-all实际退出1，原日志ENF15-LIFECYCLE-20261001-139/verify-all-before-defect-fix.log明确是TEST_EVIDENCE_STALE:project，不是本轮ENF-15领域I失败；不删除历史XML、不改咨询来修绿。本轮独占证据分类发布及同步主计划后再执行verify-all，以最终原日志记录实际结论，不能将专项零跳过写成全仓Java回归零跳过。

本轮收口核验：将140三份新C XML及140/143两份新领域对账XML按唯一原后缀发布到既有canonical报告目录，逐SHA与原执行修改时间相等，目标原先不存在，不覆盖、不删除、不改时间；published-exclusive-report-index.json保留来源/目标/实际时间和范围。同步主计划后verify-all实际退出0，原日志143/verify-all-after-plan.log，47基线文件/41需求一致。这是结构/治理检查及真实专项证据刷新，不是新执行全仓Java回归，更不改变128/130历史错误/跳过或136～139/141失败。Codex只作上述部分具名复核；IMP仍进行中，完整最终I和最终验收未作。

## 7.18 ENF-15/16完整浏览器闭环与当前修复版本恢复收口

任务仍为IMP-ENF-PENALTY-04，权威状态只见主计划，保持进行中；不重开D，不修改咨询或冻结ENF-13，不启动ENF-14。每轮采用新编号、隔离源码、独占XML后缀。时间以原XML/HTTP实际UTC时标为准，编号只是执行标识。

输入边界：146/148经现有最终存储命令准备案件源、物理原件和Evidence/Conduct/Procedure确切快照，事件明确IMPORT_CONTROLLED_TEST_INPUT。collector/verifier/confirmedBy/reviewedBy显式声明NOT_REAL_PERSON或NOT_HUMAN，身份B为既有FUNCTIONAL_VALIDATION。这些是需求15第8节允许的受控替代输入，不是上游工作流验收、真实历史权利、真人批准、法定时限或专业结论；准备XML不计领域I。处罚产品仍通过现有授权、独立水位、最新规则、原件SHA、确定性公式和失败门禁读取输入，没有Mock/H2/Stub响应或产品旁路。

- 145准备失败：独立权威schema缺已有V002/V005水位表。146通过部署身份应用冻结迁移及最小表权限，未改MATERIAL业务代码/门禁；准备XML1/0/0/0。原失败保留。
- 147真实发布/停用/新草稿重新发布和9项拒发布已执行，但进入ENF-16令牌过期401，整轮失败。改脚本分段正常OAuth/PKCE登录，不延长令牌、不绕过认证。148日期/SHA序列化按现有产品ISO契约准备。
- 149真实生成WAITING_INPUT，唯一缺口AMOUNT_SOURCE_MISMATCH:B；保留原文和提交均为12，因IntNode/DecimalNode类型比较误判。仅修处罚PenaltyProductInputs：相等数字按BigDecimal compareTo比较；确认、来源Ref、版本、定位和其他字段仍须精确一致。150四份新C XML=29/0/0/0（原25+数字来源回归4），不是领域I。
- 151真实执行正常生成、24/24并列方案、调整、技术身份审核、重跑、依赖SHA拒绝、缺事实WAITING_INPUT及越界审核拒绝；之后脚本遗漏新版本重置后的必填方案/依据，未发POST而超时。原日志/截图/DB历史保留，未改必填校验。HTTP审计还发现专用运行根漏旧授权资源导致列表503，不能据成功表单命令宣称页面完整通过。
- 152部署输入准备核验：104份已持久化原件按DB字节/SHA复制到统一独立运行根；11个保留来源（9个catalog）保留原件、URL/URN、版本与SHA，组装现有FileLegalKnowledgeSource配置。业务DB写0、历史原件删除0；不放宽权限、不改来源语义、不新增产品实现。C引用150原XML并核主源码SHA，明确不是新执行29项。
- 153完整真实浏览器通过：ENF-15 DRAFT1→ACTIVE2→DISABLED3→DRAFT4→ACTIVE5，第二规则ACTIVE2；9项拒发布覆盖语义/范围/类型公式/边界缺失、边界不全、边界错误、程序不适用、重叠无优先依据、依据不匹配，脚本表达式真实400。ENF-16生成1、调整2、技术审核3、重跑4、缺事实5、补齐输入6、越界调整候选7、拒审核、纠正8、退回9、重跑10。两并列方案均24，不自动选方案；新版本不继承旧审核/调整。全部列表、详情、历史GET200，无隐藏刷新失败。
- 153真正领域对账I XML=1/0/0/0：26修订=26原件=26回执=26事件，DB字节/原件SHA逐条一致；12拒绝命令零成功回执/事件和孤儿文件。证据目录enforcement-followup-final-20260930/ENF-PEN-BROWSER-20261001-153，含HTTP、PNG、实际命令和product-db-files-events-receipts.json。权限准备XML另列，正式业务仍BUSINESS_ACCEPTANCE_PENDING。
- 修复版本恢复：154/155因脚本漏.blob后缀、类路径正则错误失败；156冷复制及130条目标DB/原件/事件/回执对账XML1/0/0/0，但Vite未监听即启动浏览器，整轮失败。失败卷/SHA/XML/日志全部保留，不以目标只读对账冒充完整恢复验收。只修收口脚本；157采用新卷/DB端口/文件根/报告，等待真实HTTP就绪再跑浏览器，未结束不能计PASSED。

剩余冻结项：当前规则/来源变化后产品重新计算、修复版本共同物理恢复全部产品路径、当前撤权/旧授权/旧备份失败关闭、最终完整I及具名复核。来源变化准备仅更新项目自有技术输入，不补造新版法条；尚未执行绝不算通过。全部条件满足前不冻结IMP。

全仓：起始及恢复中verify-all实际退出1，原因TEST_EVIDENCE_STALE:project，47基线文件/41需求一致。专项零跳过不冒全仓通过。128/130及全部历史失败/跳过不删除、不改写；后续只发布实际新XML并保留SHA/实际mtime，再按verify-all原日志记录结果，不改无关咨询。


## 7.19 ENF-15/16规则与来源变更后共同恢复收口

任务仍为IMP-ENF-PENALTY-04，进行中，不启动ENF-14；正式业务验收BUSINESS_ACCEPTANCE_PENDING独立。实际时标使用UTC，执行编号仅为标识。咨询、冻结ENF-13与其他冻结业务没有本轮修改；历史失败、原XML和卷全部保留。

- 157完整共同物理恢复已结束并通过：源/目标冷态DB字节SHA完全一致，130条处罚修订及其原件/回执/事件逐条对齐；实际恢复浏览器缺规则原件503、损坏SHA503、缺案件原件503、损坏案件原件403，恢复原字节后新建议1 PENDING_REVIEW、并列24/24。目标131条，四失败请求零领域写入。领域XML两份各1/0/0/0，授权准备和源只读审计另列。
- 158列表实际403后行定位超时，没有领域写入；159列表/详情200但草稿命令403，没有领域写入。通过现有ProductGrants与独立ProductGrantWatermark对项目自有技术来源、确切引用/目标/案件依赖续期；不延长令牌、不降低产品授权、CAS或必填条件，授权准备不计领域I。所有失败JSON/截图/日志保留。
- 160实际HTTPS/OAuth/PKCE浏览器执行规则DRAFT6→ACTIVE7，建议10→11正常重新计算并列24/24；仅更新明确PROJECT_AUTHORED_FICTIONAL技术来源至revision2，不新增或猜测法条。建议12自动WAITING_INPUT、LEGAL_REFERENCE_REVIEW_REQUIRED、无方案/旧审核；原来源原文和历史保留。四新修订的DB字节/原件SHA/事件/回执一致，领域XML1/0/0/0；七份精确授权准备及一份来源输入准备另列，不冒充领域I。
- 161/162实际冷恢复及浏览器失败边界、重新计算均已发生，但对账仍使用PENDING_REVIEW固定断言而失败；第一次替换没有实际改掉固定断言。两轮全体失败报告、截图、物理SHA、容器/卷保留，不转为通过。163新构建读回确认状态断言，新卷/5332/5465/5192重新执行，共同物理DB冷拷贝134条，源/目标文件SHA一致；恢复后生成135号总记录中一条新建议WAITING_INPUT、LEGAL_REFERENCE_REVIEW_REQUIRED，四失败请求零DB/文件/事件/回执副作用，旧原文保持。目标前后两份领域XML各1/0/0/0；授权准备、源库存审计另列。

下一剩余项：164当前确切技术授权撤权、源403/旧物理备份503与旧成功命令重放拒绝正在执行；最终完整C/真实DM8 I、最终Codex具名复核与verify-all尚未执行完。全部冻结条件满足前不标记完成。


## 7.20 ENF-15/16最终开发验收与冻结

最终执行证据包：ENF-PEN-FINAL-20261001-166。Codex具名最终技术复核通过，IMP-ENF-PENALTY-04最终开发验收完成并冻结。ENF-14本轮不启动，ENF-13/咨询不修改，正式业务BUSINESS_ACCEPTANCE_PENDING继续独立。日期以UTC实际执行时标记录，技术受控输入不宣称真实业务事实、法定标准或正式人工批准。

- 164真实当前源试算403、仍可连接运行的旧物理恢复试算503；旧成功保存请求分别403/503，源失权清屏。通过ProductGrants及独立水位只撤指定技术TRIAL7/EDIT6，不手工改业务DB。领域对账XML1/0/0/0；源/目标前后两库修订/原件SHA/事件/回执完全不变。四个只读审计及一个授权故障准备XML单列，全部不计领域I。
- 165全新独占源码构建实际执行五份XML：Formula8、PublishChecks9、Lifecycle8、数字来源4为C29；真实DM8 Store2为I2。合计31/0/0/0，命令退出0，真实双连接CAS一个赢家、幂等/冲突/原件追加后事务失败回滚、历史/缺件/SHA/恢复原字节和事件/回执一致。新原件直接使用已核隔离的当前产品统一根，不产生遗漏根。
- 166最后真实源库只读库存审计：141修订=141原件=141回执=141事件，全部DB原文与原件字节/SHA一致，无含糊/缺失原件。审计XML1/0/0/0，但不计领域I；source-final-inventory.json固化实际来源路径/SHA。
- 最终证据集11份XML合计37/0/0/0：C29；领域I8分别165存储2、153完整浏览器后对账1、143转换反例对账1、160规则/来源变化对账1、163真实共同物理恢复目标前后2、164真实撤权后对账1。选定证据集不是声称本轮重新执行所有37项，也不是把准备/只读审计数冒充领域I。每份报告原路径、实际时间、SHA、tests/failures/errors/skipped及分类见execution.json/technical-review.json。
- 冻结PEN01～PEN06逐项映射在technical-review.json；原完整发布/停用/重新发布/历史、9项拒发布及脚本拒绝，ENF-16真实生成/并列/调整/技术审核/重跑/缺事实/依赖SHA/越界审核/退回重跑闭合。来源变更只改项目自有技术输入，旧原文和引用保留，建议12及恢复后建议进入WAITING_INPUT/LEGAL_REFERENCE_REVIEW_REQUIRED、无方案/旧审核。163恢复134检查点与目标135记录一致，四失败零领域副作用。
- Codex实际查看153并列方案与160来源变化工作面截图、核产品代码和原XML/对账结果；165的12份处罚生产源码SHA与152浏览器运行源码快照完全一致，冻结ENF-13的8份生产源码与100冻结快照完全一致。不以结构检查替代上述实测和具名技术复核。
- published-reports.json记录11份实际XML在常规扫描位置的只增副本，逐份SHA及原mtime保持；历史报告删除0/覆盖0，不改写128/130以及145/147/149/151/154～156/158/159/161/162等历史失败或跳过。起始TEST_EVIDENCE_STALE及后续实际通过日志分别保留。冻结前verify-all-before-freeze.log实际退出0，47文件/41需求基线一致。冻结后另执行verify-all-after-freeze.log，结果以实际日志为准，不把执法专项通过冒全仓或全量Java零跳过。
- 冻结后首轮verify-all-after-freeze.log真实退出1：需求台账验收格被误填为最终开发验收，治理脚本按正式需求验收解释，报ENF-15/16 V gate与验收链接各两项。只修本任务台账口径：代码/技术测试格保留完整开发验收和Pending说明，正式业务验收格保持未验收（同冻结ENF-13模式）；不改治理规则、不补造正式V/业务验收、不修改业务代码。该失败日志保留，修正后另跑verify-all-after-ledger-correction.log。
- 台账修正后verify-all-after-ledger-correction.log实际退出0，基线47/41一致。原冻结后退出1日志不删除；本条和主计划结果同步后再执行verify-all-final-status.log，仍以真实退出/原日志为准。最终开发冻结与正式业务未验收两者并存，不冒正式业务通过。
