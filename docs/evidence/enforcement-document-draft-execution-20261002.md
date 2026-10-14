# ENF-14 初始实现执行证据（2026-10-02）

任务状态只以主计划 IMP-ENF-DOCUMENT-DRAFT-05 为准。本文件记录实际执行，不建立第二套状态台账。

## 范围与基线

本轮仅启动 ENF-14，不重开 D，不修改 ENF-01～13/15～17 业务实现或咨询。冻结合同沿用 enforcement-followup-v2-d-20260929.md 第6.1节及现有 document-draft-v2-d-input-v1；requirements 未修改。NEXT-V2-INT-ENF-01 的正式业务验收仍独立 Pending。

启动前 node scripts/verify-all.mjs 实际退出0，47需求文件/41契约基线通过；原输出保留在 ENF14-C-I-20261002-02/enf14-before-20261002.log。

## 既有运行环境恢复

- Docker实际启动既有 codex-enf-i-business:5311、codex-enf-i-authority:5312、codex-enf-followup-restore-20260929-01:5319；未新建实例/卷、未恢复出厂。
- 重用既有152轮编译快照、现有本地Secret、证书与保留输入恢复5444和5458，未重编冻结领域、未改变既有数据。
- 恢复5174→5444和5186→5458前端/代理。HTTPS身份匿名请求及5174代理身份请求实际401；Job Pack/处罚页面入口实际200。401只证明未登录边界正常，不能宣称已登录、OAuth端到端通过或上游授权可消费。
- 启动脚本写既有日志前，原server/vite日志及pid已原样复制到本地Secret目录下 runtime-history/ENF14-START-20261002-01。历史失败及原证据未删除或覆盖。
- 本轮未执行浏览器操作，HTTP 200不记为浏览器证据；未完成ENF-14运行验证。

## 运行对象只读核对

由 scripts/java/EnforcementDraftObjectAudit.java 使用本地安全Secret连接真实5311 DM8，Connection只读；结果保存于本轮 runtime-and-storage-reconciliation.json。

- 当前模板18个，其中5个最新版本PUBLISHED；映射21个，其中1个最新版本PUBLISHED；引用9个，其中6个CONFIRMED/ACTIVE。
- 唯一PUBLISHED映射 ENF13-RULE-ENF13-RECOVERY-SEED-20261001-81@2 所需模板@1，其现时最新头已为@2 DRAFT。因此不是当前可消费模板/规则组合，不据历史PUBLISHED记录放行。
- 当前有效授权分组仅Job Pack用途5组，文书/映射/法律来源用途未具备。只读库存不是现时主体/会话/资源授权核验，更不是产品输入READY证明。
- D包未拿来证明运行库已就绪，未创建新业务案件、身份或批准；确认引用数量不代表其目标与案件绑定关系已满足ENF-14。

## 本轮落地实现及验证边界

- DocumentDraftContract / DocumentDraftService：精确案件、证据、模板、映射、引用和按类型需要的上游坐标；prepare不写入；生成冻结manifest摘要、内容、字段定位、撰写人、辅助标记与修订；缺项保留INCOMPLETE_DRAFT/诊断，不补造；按需依赖与現时核权端口失败关闭；读历史保留确切输入。
- Dm8DocumentDraftStore / V083：独立ENF-14表，CAS、幂等、不可变JSON原件，DB/原件/回执/事件事务路径与失败清理。未改既有域表/源版本。
- 这些是初始领域与持久化实现，尚未接通产品API/UI；不标记FUNCTIONAL_READY或技术最终验收。

| 本轮实际验证 | 预期 | 实际 | 边界 |
|---|---|---|---|
| DocumentDraftServiceTest | prepare零写；版本/manifest校验；缺项显式；未发布拒绝；按类型依赖；同键重放及撤权拒绝 | tests=9 failures=0 errors=0 skipped=0 | C，使用明确的端口测试替身，不算真实I |
| DocumentDraftStoreDm8IT | 双连接读取/同键重放/冲突拒绝；异常事务无DB/文件/事件/回执增量；损坏原件失败关闭 | tests=2 failures=0 errors=0 skipped=0 | 真实DM8存储适配器I，不是产品生成、双JVM或共同恢复I |
| 存储只读对账 | 成功记录原件摘要相等，每份修订回执/事件各1；失败事务无新记录 | 2份保留技术存储记录原件SHA匹配、各1回执/1事件；对账退出0 | 使用既有D输入的DTO验证存储，不宣称上游可消费或正式业务 |

运行编号 ENF14-C-I-20261002-02，独占构建目录与Surefire后缀。两份XML原样复制并保存，没有旧报告/并行覆盖或skip转pass。第一次 ENF14-C-20261002-01 因新增测试Inputs导入冲突编译失败；已修复并在新快照重跑，原失败日志保留。它不是成功或被删除的历史失败。

本轮没有最终技术复核或任务验收；生成任务状态、API/UI、实际输入适用性/关联校验、业务列表分页、修订/候选/显式采用、同版审核/导出、完整D01～D09、双部署/共同物理恢复及浏览器闭环均未完成。

## 最终产品内置基线及泛化处理（03～06轮）

用户批准项目内置模板直接作为最终产品开发基线；唯一配置资源为 backend/enforcement/src/main/resources/enforcement/document-template-baseline-v1/profiles.json。三类文书分别14/18/21字段，分类NON_OFFICIAL，未含案件事实、真实人员或批准。规则defaultMappings只是导入建议，生成必须显式消费已发布规则快照；字段、来源、章节条件与配置生效窗口已由通用引擎处理，无当前模板字段硬编码。版本内容保存Profile、规则和来源快照；未来替换只影响新版本，不重写历史。ADR见 docs/adr/enforcement-document-template-baseline-20261002.md。

实际CNIPA资料保存在 ENF14-BASELINE-20261002-03/sources：2016表格页及真实链接ZIP、2020指南说明页；URL、HTTP、抓取时间、SHA和原件路径见唯一资源。2016表格ZIP可查看调查笔录、现场检查笔录、证据清单等，仅作为结构实践，未断言当前法定效力。SAMR403及错误CNIPA404均保留原始HTTP失败，不计成功，不绕过限制。

ENF14-BASELINE-C-I-20261002-06：DocumentDraftServiceTest=11、DocumentProfileEngineTest=11、DocumentDraftStoreDm8IT=2，全部failures=0/errors=0/skipped=0。独占XML及日志保存，不复用旧报告。真实5311只读对账两份技术存储修订原件SHA相等、事件/回执各1；runtime-and-storage-reconciliation.json已实际生成。该轮仅22C+2存储I，不是产品生成/双JVM/共同恢复I。verify-all-after-baseline.log实际退出0；修订前verify-all-before-revision.log亦退出0。

## 人工文本修订及真实存储历史（07轮）

DocumentContentEditor与唯一DocumentDraftService.revise已实现：仅允许确切模板快照声明可编辑、当前显示的非重复LONG_TEXT；受保护身份/金额/日期/来源不允许自由改写。修订保留原SourceDatum及逐条修改前后文本、操作者、理由与时间，生成新版本和REVISE回执/事件。手工文本不会把原来源缺项变为已核实；新稿不继承旧审核。现时EDIT/上游适用性检查、输入内容SHA、CAS和同键重放仍受控制；不自动执行人工批准。

ENF14-REVISION-C-I-20261002-07 独占执行实际退出0：

| 测试 | tests | failures | errors | skipped | 范围 |
|---|---:|---:|---:|---:|---|
| DocumentDraftServiceTest | 16 | 0 | 0 | 0 | C；修订来源/历史、幂等/撤权、受保护/未知字段、错误SHA/CAS、缺项保留 |
| DocumentProfileEngineTest | 11 | 0 | 0 | 0 | C；三类配置、独立规则、条件、范围、类型/缺项与历史 |
| DocumentDraftStoreDm8IT | 3 | 0 | 0 | 0 | 真实DM8存储I；双连接历史、两版保留、失败无额外DB/文件/事件/回执、重放/CAS和损坏原件关闭 |

07轮 runtime-and-storage-reconciliation.json 实际核对4份修订原件SHA全部相等、逐版事件/回执各1；新增REVISE技术存储记录两版保留，ROLLBACK及stale失败没有新增记录/文件。execution-summary.json保留XML SHA和实际计数。测试DTO仍来自明确的D合同技术输入，不是可消费产品对象或真实案件。

报告以带执行编号的原名和原mtime同步至治理扫描目录，历史文件未覆盖；复制不是第二次执行。全部旧失败/成功轮保留。同步记录后的首轮verify-all因docs/project-plan.json瞬时打开失败退出1，原verify-all-after-record-sync.log保留；未修改业务代码，重试verify-all-after-record-sync-retry.log实际退出0，47/41基线未变。当前仍未完成API/UI、实际案件输入链、候选差异/显式采用/审核/DOCX、浏览器、共同物理恢复与具名最终验收；不能用30项通过宣称产品完成。

## 后续执行顺序

继续仅本任务：通过既有身份权限机制准备现时技术用途授权并使用真实产品动作形成可消费既有模板/规则组合，不改库状态、不伪造批准；绑定确切案件/证据/已确认引用与原件，再接通事实记录prepare→generate→查询/持久化的API/UI。该资源准备不设为真实业务人员或正式B新前置。事实记录产品闭环实测后才扩展既有另外两类，再执行冻结修订、审核导出、D01～D09及共同恢复并最终具名复核。

## 本轮治理复验

首次后置verify-all实际退出1，唯一错误TEST_EVIDENCE_STALE:project；原失败输出保存于 verify-all-after-implementation.log。根因：治理actualEvidence只扫描仓库backend/**/target/surefire-reports，不包含TEMP独占构建报告。核对受测documentdraft源码摘要与002快照一致后，把两份原始XML同步到各自模块target/surefire-reports，以原后缀、原LastWriteTimeUtc和相同SHA保留；不覆盖任何旧报告，不篡改XML、不touch造新时间，也不计新的测试执行。再执行verify-all实际退出0，原成功输出保存于 verify-all-after-report-sync.log。

该退出0是治理/结构检查结果，不代表ENF-14已经完整实现或所有冻结产品D/I/E2E条件已通过。主计划保持进行中，最终验收尚未发生。主计划ENF-06～11/17实现追踪和ENF-14陈旧依赖只做有证据的记录修正，正式需求最后一栏仍未验收。

## 08～13轮运行库模板发布与工作流接线

08轮实际35项C＋5项真实DM8存储I零失败/错误/跳过，增加独立candidate原件、差异、显式采用、同版提交/审核、CAS、损坏关闭与事务失败无孤儿。09/12轮分别38项C零失败/错误/跳过；09轮前端构建/导航测试通过。09轮身份准备只注册真实PKCE产品客户端并为已有受控技术主体发行现时授权，不创建业务人员、不计领域I或正式B。

10轮浏览器定位未兼容按钮实际空格而失败；11轮真实OAuth登录后baseline-profiles返回404，原因是独立执法应用未装配ENF14配置。原failure.json/log均保留，修复仅涉及ENF14接线及浏览器定位，不改咨询或冻结业务实现。12轮独占构建部署5444后，13轮真实5174浏览器使用OAuth/PKCE和既有TemplateService.create/publish动作导入三类基线，全部PUBLISHED@1，并各重复同键页面动作一次，回执一致；不是直接改库发布。

13轮[result.json](enforcement-document-draft-20261002/ENF14-BASELINE-BROWSER-20261002-13/result.json)、三张页面截图及[只读对账](enforcement-document-draft-20261002/ENF14-BASELINE-BROWSER-20261002-13/db-file-event-reconciliation.json)固化：三模板原件SHA3/3相等，CREATE/PUBLISH事件/回执6组，重放无增量。08轮技术存储既有7份文书原件、1份candidate原件亦SHA一致，review2条、workflow事件/回执10组；这些技术DTO不冒可消费产品文书。三模板发布完成不等于映射/案件组合就绪，DATA任务继续进行中。

## 15～18轮映射消费修复与同版DOCX实现

本轮未改变模板架构、领域/修订模型或冻结D。修复ENF14原先正文直接取源值而未消费ENF13映射试算结果的问题：按field消费确切Mapped结果，保留未转换原始SourceDatum；逐项核sourceRef/version/locator/mode；缺映射结果不得回退原值。组件验证只使用既有ISO_DATE转换，不新增转换规则。15轮因旧配置测试没有传实际Trial出现8个错误，原XML和日志保留；改为接入真实DocumentMappingValidator试算后16轮41C及5真实存储I均零失败/错误/跳过。16轮启动命令有一次V082错误迁移路径预查警告，已有V083表使存储测试未进入初始化；不据此宣称V083迁移本轮重验。

新增同审核版DOCX命令、元数据/文件下载API和工作面下载：EXPORT独立核权；确切head/contentSHA及该版APPROVED审核；旧审核不继承；精确模板原件＋内容/模板/规则/字段来源快照渲染；导出原件和元数据与事件/回执同事务，失败清理原件；前端下载再核SHA。导出记录保存Input manifest、生成revision/contentSHA、rendererVersion、artifactSHA、actor/request/time。V087只新增ENF14导出表，U087禁止有历史数据时降级，U087尚未执行不宣称验证。

渲染器不硬编码三类字段；按配置处理条件章节、重复表格、跨run占位符、XML转义、换行与精确字节模板。来源文本只替换一次，不重新解释其中的模板语法。17轮发现DECIMAL来源快照默认反序列化成Double导致DOCX组件1个错误；原失败XML/log保留，修复ENF14修订与渲染读取为BigDecimal，不放宽禁止二进制浮点金额门禁。

18轮独占构建结果见[原始XML摘要与SHA](enforcement-document-draft-20261002/ENF14-EXPORT-20261002-18/execution-summary.json)：

| 验证 | tests | failures | errors | skipped | 真实范围 |
|---|---:|---:|---:|---:|---|
| DocumentDraftServiceTest | 16 | 0 | 0 | 0 | C |
| DocumentDraftWorkflowTest | 8 | 0 | 0 | 0 | C |
| DocumentProfileEngineTest | 14 | 0 | 0 | 0 | C；含真实映射算法消费及坐标篡改拒绝 |
| DocumentProfileTemplateAssetTest | 3 | 0 | 0 | 0 | C |
| DocumentDraftExportServiceTest | 5 | 0 | 0 | 0 | C；机械renderer替身明确不是产品DOCX |
| DocumentDraftDocxRendererTest | 4 | 0 | 0 | 0 | OOXML C；受控字段、模板端口替身，不冒产品I/视觉QA |
| DocumentDraftStoreDm8IT | 6 | 0 | 0 | 0 | 真实DM8存储适配器I；非真实上游/HTTP/浏览器领域I |

合计50C＋6真实存储I，测试进程退出0，前端build退出0。新增存储I实际覆盖未审核拒绝、同版已审核写入/重放、缺原件、SHA损坏、事务注入回滚、修订后旧版拒绝及历史原件保留；其导出测试原件明确是opaque template storage payload，不冒真实案件DOCX生成。17轮实际创建V087，18轮读取真实表复验；未直接改业务状态。

18轮[DB/文件/事件/回执对账](enforcement-document-draft-20261002/ENF14-EXPORT-20261002-18/db-file-event-reconciliation.json)实际：文书原件9/9、candidate1/1、技术导出payload1/1、既有三基线3/3 SHA相等；review4条、workflow事件/回执15组；三模板发布事件/回执仍6组。没有把权限准备或只读审计计入6项I。

## 19～21轮真实部署与浏览器运行对象复验

18轮源码已部署独立5444，保留前一运行server日志/pid于本机runtime-history/ENF14-EXPORT-20261002-18，未触碰5443咨询服务。5311业务/5312独立权威实际连接成功，HTTPS未登录身份入口401正常。5174代理公开OIDC metadata的issuer实际为5444。

19/20轮使用错误猜定login_name导致真实页面提示用户名或密码错误；20轮截图前清空密码，failure.json及原日志保留，不输出或保存密码。修正探测执行上下文为读取本地functional-a.identity.json.loginName及DPAPI Secret，不重设密码、不修改身份。21轮真实浏览器OAuth/PKCE登录、回调、三profile读取、当前输入查询200通过，见[result.json](enforcement-document-draft-20261002/ENF14-RUNTIME-BROWSER-20261002-21/result.json)及runtime-input-state.png。返回inputCount=0、productsGenerated=0，完整文书产品I明确NOT_RUN；不把登录或空列表当文书生成通过。

当前工程剩余：三基线对应映射规则尚未通过正式产品流程形成可消费组合；当前配置ENF13技术来源值原件与case148已纳入材料并非同原始字节，不得拼接成已验证案件事实。继续同源运行输入/映射准入后实测事实记录prepare→generate→query/revise→candidate/diff→adopt→review→DOCX，再复用既有另外两类。无需新增真人或正式B条件。共同物理恢复、完整D01～D09浏览器/领域I、DOCX实际页面下载/视觉检查、恢复浏览器读取及最终具名技术复核仍未完成，IMP保持进行中，ENF14未验收。

## 本轮历史证据归档与全仓边界

前置verify-all真实退出1：TESTS_FAILING，47/41需求基线一致。15/17失败原XML按原名/SHA/原mtime保存；18成功原XML同样固化。初次后置verify-all退出1，治理扫描把15轮8错误＋17轮1错误累计为9历史错误，不改写为当前成功。对有18轮真实零失败/错误/跳过对应XML的ENF14旧报告，仅Move-Item到同一surefire-reports/history子目录，保留原文件、SHA及mtime；执行证据目录仍保存原失败文件，未删除或覆盖。没有修改治理算法。当前canonical保留18轮对应XML；归档不是测试执行或业务验收。

归档后后置verify-all真实退出1：TEST_EVIDENCE_STALE；独立咨询ProductPatentsViewSourceInventory/Record/ArchiveProof等业务文件持续并行变更，mtime晚于本轮执法报告，不能把执法专项结果冒全仓绿色。本轮没有改这些咨询文件。原前置/后置/归档后日志分别保留，不追改无关代码。

记录同步后verify-all-after-record-sync.log实际退出0，47/41基线、计划/结构/数据边界检查通过。这是该次真实全仓治理调用的结果，不是全仓Java重新回归，也不是ENF14完整产品验收。历史报告归档清单和SHA见18轮historical-report-archive.json：32份旧报告原样保留，其中9个历史错误未改写；21份当前ENF14 backend源码均与18轮独占受测快照摘要一致。IMP继续进行中，正式业务验收仍BUSINESS_ACCEPTANCE_PENDING。

## 0 输入门禁审计与运行连接修正（执行38／浏览器39）

主任务仍为 IMP-ENF-DOCUMENT-DRAFT-05／进行中。本节仅记录实际执行，不建立第二任务状态账。没有新增正式业务验收、真人或许可前置条件，没有修改咨询或冻结 ENF 实现。

新增只读 input-diagnostics API，并让页面和原 inputs API共用同一可消费集合算法。页面展示条件、实际对象版本、诊断码、拒绝原因和SHA；未授权对象不披露坐标。事实记录的Conduct和Procedure按冻结D标NOT_APPLICABLE，不制造上游报告。读取案件被拒时明确材料尚未检查，不误说已证无材料；缺材料原件也不误报案件文件缺失。

实际浏览器通过现有安全注入的 FUNCTIONAL_VALIDATION 账号完成 HTTPS／OAuth／PKCE／回调／组织读取／三基线查询／诊断查询。最初67项诊断中发现当前读取授权不足；通过 ProductGrants 和独立 ProductGrantWatermark 的正常命令补充当前组织、确切版本、确切用途的技术读取授权。没有写业务状态或创建真实业务身份。31轮权限准备1项真实DM8命令执行通过，但不算领域I。其首轮30因错误选取另一purpose的epoch失败，已补全epoch键中的domain/purpose后实际重跑；原失败保留。

修复后的最终浏览器[原始结果](enforcement-document-draft-20261002/ENF14-INPUT-AUDIT-BROWSER-20261002-39/result.json)和[页面截图](enforcement-document-draft-20261002/ENF14-INPUT-AUDIT-BROWSER-20261002-39/runtime-input-state.png)：HTTP200；126项实际门禁诊断（51项满足、73项不满足、2项不适用）；修复前可消费组合0，修复后仍0，productsGenerated=0。诊断不满足数量不是JUnit测试失败数量，不把诊断成功当文书生成成功。

| 条件 | 实际证据／为何不进入集合 |
| --- | --- |
| 案件／组织 | 当前配置仍为 ENF-PEN-CASE-ENF-PEN-CASE-20261002-148@1，组织ORG-ENF-FUNCTIONAL；明确PROJECT_AUTHORED_FICTIONAL／FUNCTIONAL_TEST_INPUT，不能克隆或重标为公开真实案件 |
| Evidence | 703703f4-9059-4ec2-acc8-83fffe2c267d@3，DB为VERIFIED、同组织、ANALYZE，但截至2026-10-01T22:51:47.964201Z已经失效；未延长期限或改状态 |
| 文件根／原件 | 原先后端文件根未指向该材料目录；按保留的runtime-preparation.json恢复正确MaterialFileRoot后，案件引用、DB和实际原件SHA均为478a28c7…226a5ab。修正文件根不能消除真实用途过期 |
| 模板 | 三类 ENF14-BUILTIN 基线均PUBLISHED@1，精确profile绑定与生效时间通过；模板发布本身不能证明映射或案件就绪 |
| 映射／规则 | 实际21个最新规则头版本均未精确绑定事实记录基线；DRAFT／DISABLED／REVIEW_REQUIRED及绑定其它模板的拒绝原因逐项保留，不按名称猜兼容 |
| 来源／schema | PROJECT-ENF-DOCUMENT-SCHEMA-01@1／ENF13-CONTROLLED-SOURCE-20260930@1；JSON原件SHA580779a2…d680与案件材料478a28c7…a5ab不同。独立技术任务的值不能当该案件同源事实 |
| 法律引用 | 实际9个引用头版本中，真实CNIPA引用分别PENDING、NEEDS_REVIEW或RETURNED／UNBOUND；CONFIRMED／ACTIVE的符号评分定义属于旧技术任务，均不满足当前同案关系，不能冒充真实法律依据 |
| 当前授权 | PREPARE过期导致一次HTTP403，经现有服务续发明确技术用途授权后恢复。其它确切READ授权经正常服务补齐；原失败不改写为通过 |
| 可复用真实对象 | 只读完整库存实际仅2个旧处罚材料／来源，均用途过期且明确技术输入，没有已准入运行库的公开真实案件组合 |

可复用库存原始结果见[33轮只读库存](enforcement-document-draft-20261002/ENF14-INPUT-AUDIT-20261002-38/enf14-inventory-33.json)。该库存不是当前授权证明。

### 已自主准备的公开来源，尚不冒充运行准入

运行 scripts/prepare-enforcement-document-draft-public-input.mjs，复用此前正常采集的国家知识产权局指导案例1号理解与适用原始页面，没有再次下载、重写为新案件或扩展许可含义。原始URL／机构／发布日期／采集时间／已成功访问的采集依据随包保留；公开原始页面SHA为fd1c6ffb…1c562，原文标题和主体名称逐字定位核对，保留完整原件及独立派生JSON的SHA和转换链。

[公开输入准备结果](enforcement-document-draft-20261002/ENF14-PUBLIC-INPUT-20261002-40/public-input-preparation.json)含正式基线14字段的schema／mapping结构，只有2个能够从原文直接证明的来源字段，其余12个路径保持缺项。COLLECTION_ALLOWED继续按现有项目采集规则保留；COPYRIGHT_LICENSE／COMMERCIAL_LICENSE／LEGAL_RIGHTS均NOT_ASSERTED。资料包不创建案件、不上传材料、不确认法律引用、不发布映射、不生成文书，运行准入和这些产品动作均NOT_RUN；这不是空表或静态产品I。 40轮另保存原采集HTTP200、请求/最终URL、开始/结束时间及查询策略，明确REUSED_ORIGINAL_CAPTURE，不伪称本轮重新抓取。34轮早期准备产物原样保留，不作为最新准入结果。

下一工程顺序只在主计划控制：通过现有正常产品机制登记公开来源及原件／明确派生材料 → 建立实际材料版本与案件关联 → 发布精确事实记录模板映射 → 使用实际有效且经现有审核机制确认的同案法律引用 → 实际页面prepare／generate／query及DB／文件／SHA／事件／回执对账。不能把CNIPA原始文件、派生值及旧受控材料混为同一SHA。正式业务验收继续BUSINESS_ACCEPTANCE_PENDING，不反向阻塞这些工程动作。

### 本轮验证与未执行边界

38轮独占构建当前源码，显式 enf-document-draft-input-audit 测试编译范围：5项诊断／权限保护／缺原件C，tests=5、failures=0、errors=0、skipped=0；[原始XML](enforcement-document-draft-20261002/ENF14-INPUT-AUDIT-20261002-38/ENF14-INPUT-AUDIT-20261002-38/TEST-cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftInputAuditTest-ENF14-INPUT-AUDIT-20261002-38.xml)。C中端口mock明确不算真实领域I。31轮权限准备也是独立工程动作，不混入领域I。前端22轮真实build退出0，UI未随后改变；39轮真实浏览器读取与截图通过并已人工视觉检查，当前仍无文书生成。

22轮诊断C曾1 error（未绑定profile的空值比较），30轮权限准备曾1 error；修复版本均实际重跑，失败XML及日志原样保留。36轮尚未进入测试，独立裁决CaseWorkspaceBrowserIT.java:52将Map当ObjectNode调用set导致全测试编译失败；没有修改该模块。为不受其阻断，38轮只选择既定诊断测试源编译；不是全仓Java回归，也不是跳过领域I。全仓原始失败、编译失败和执行编号／原机器时间均保留于[38轮证据目录](enforcement-document-draft-20261002/ENF14-INPUT-AUDIT-20261002-38/)。

记录同步前verify-all实际退出1／TESTS_FAILING:project；当前canonical失败还涉及ProductIssuerHttpTest、ProductInitialTaskServiceTest、ProductPatentsViewArchiveProofTest、ProductSemanticIndexTest及V2ConsultationBoundary等共享／咨询轨道。没有删除、改写或归档这些无关失败来制造绿色。最终后置结果以本目录verify-after-record-sync.log为准，不用专项通过冒全仓通过。

事实记录页面生成、修订／候选／采用／同版审核／实际DOCX及共同物理恢复仍NOT_RUN；未满足D01～D09最终验收，未做最终产品技术复核，IMP不冻结。当前优先仍是实际公开输入准入和映射发布，未在0输入时继续扩展后续功能。

后置 verify-after-record-sync.log 实际退出1，47份基线与41项需求行一致、治理自测通过，最终阻断 TESTS_FAILING:project。该结果不改为通过；后续记录同步的再检查另存 verify-final.log。

最后 verify-final.log 本次实际退出0；此前退出1的原日志均未删除或改写。当前共享工作树／报告状态发生并行变化，本轮仅报告各次真实结果，不把无关报告变化归功于执法专项，也不推断全仓Java已在本轮重新执行。verify-all=0只表示该次治理检查通过，不代表0输入已解决、领域I/浏览器生成/恢复/最终技术复核已完成。

日期口径：本轮控制记录按2026-10-02处理。少数原执行编号含20261003，仅保留其原标签及原机器时间；不能据此宣称已在2026-10-03完成任何验收。真实网络采集时间、当前页面核验时间与日期判断分别以原始UTC时间戳为证，不改写原XML、日志或旧编号。


## 2026-10-03：公开材料实际准入、精确映射发布与产品读取复核（41～54轮）

本轮控制任务仍为主计划唯一记录的 IMP-ENF-DOCUMENT-DRAFT-05，状态“进行中”。只推进公开输入和事实记录 prepare→generate→query 的前置连接；未扩展审核、DOCX或恢复。日期以当前开发者提供的2026-10-03和各产物UTC时间为准；上一节原日期记录保留，不改写历史。

### 实际完成的正常产品命令

执行开始时发现原执法容器和5444/5174停止，恢复原 codex-enf-i-business:5311 / codex-enf-i-authority:5312 与原执法服务、HTTPS代理。咨询容器、代码和冻结域业务实现未修改。重启前将旧server/vite日志复制到本地Secret目录的runtime-history，未覆盖唯一历史原件；沿用38轮产品编译对象，未将新的测试代码冒充新产品部署。

[44轮真实准入记录](enforcement-document-draft-20261002/ENF14-PUBLIC-ADMISSION-20261003-44/public-admission.json)通过两次正常浏览器OAuth/PKCE取得两名已有 FUNCTIONAL_VALIDATION 受控主体的真实会话，令牌只在进程内传递。权限由既有 ProductGrants + 独立ProductGrantWatermark 服务按确切资源/版本/动作/用途发放；没有直接更新业务状态，没有伪造真人身份或专业批准。

正常产品链：POST /sources → /observations（官方标题正例与原页面“国家知识产权局”页头负例，不编造案件）→ /discovery-rules → 真实trial MATCH/NO_MATCH → publish → /discoveries → /materials → collection → 不同主体verification。所有写命令HTTP200；原件preview HTTP200且字节完全相同。原HTTP200、原捕获时间及采集策略仍由40轮 capture-evidence.json 保留；本轮是保留原件的准入，不伪称重新网络抓取。

| 对象 | 真实运行坐标 | 原件SHA / 状态 |
|---|---|---|
| 公开来源 | ENF14-CNIPA-PUBLIC-ENF14-PUBLIC-ADMISSION-20261003-44@1 | CNIPA_GUIDANCE_CASE_1_INTERPRETATION；COLLECTION_ALLOWED，仅本项目采集用途 |
| 官方HTML材料 | d72ae68c-0663-4a1b-87ee-9a1ea81bbe30@3 | fd1c6ffb91d32829a8393cccdf9197f7e2710d405a80baa932db10bdabf1c562；VERIFIED |
| 机械派生JSON材料 | 1f3f5391-4f6f-479e-b550-017be5fa6323@3 | e65dc11708c378cb1de2874fb599b49c457f62043f361fd1349c2f99c320e395；VERIFIED |
| 同源案例快照 | CNIPA-GUIDANCE-CASE-1-PUBLIC-ENF14-PUBLIC-CASE-20261003-45@1 | [case-linkage.json](enforcement-document-draft-20261002/ENF14-PUBLIC-CASE-20261003-45/case-linkage.json)记录原件SHA、实际材料版本、来源和正常VERIFY记录定位 |
| 事实记录模板 | ENF14-BUILTIN-FACT_RECORD@1 | 运行DB对象PUBLISHED，SHA 455ac37e2cc4cf686bf2b5e589478d8087a7516c28301d9998d05f4b16af4428 |
| 正式映射/规则 | ENF14-FACT-MAPPING-ENF14-PUBLIC-MAPPING-20261003-48@2 | PUBLISHED；精确模板@1、14字段映射、来源schema@1 |

组织均 ORG-ENF-FUNCTIONAL。来源/材料新准入用途有效至2026-10-04T04:52:26.343600Z（北京时间10月4日12:52:26）；这是该公开保留原件的独立技术准入期限，不修改、延长或复活旧处罚材料/来源。COPYRIGHT_LICENSE / COMMERCIAL_LICENSE / LEGAL_RIGHTS 不作断言。collection实际系统动作时间见记录；材料collectedAt保留原始快照capturedAt，不称受控主体在原捕获时间实施了真人采集。

45轮使用既有版本化案件来源文件端口，将两个实际准入材料关联到同一公开历史案例。该case.json只是可追溯输入登记/材料关联快照，不声称新增正式业务案件或数据库Case表；未复制旧虚构处罚案例内容。case.id、task.ref、recordedDate等不能从公开原文证明的生成字段仍没有补入派生值。catalog新文件保留旧schema快照并追加同源schema，旧来源原件及文书绑定未改写。

[48轮映射发布](enforcement-document-draft-20261002/ENF14-PUBLIC-MAPPING-20261003-48/fact-rule-publication.json)从实际发布模板读取版本/SHA，通过正常 /document-rules 保存@1、validate、trial、publish@2。validate.valid=true；trial.complete=false且保留来源缺项/复核诊断。只有已公开可证的party.name与analysis.summary存在值，其余12路径继续缺项；不将映射发布误称数据完整或文书生成通过。映射生效消费由当前PUBLISHED精确版本及已绑定模板profile有效期共同门控，未发明额外rule生效字段。

### 真实对账与验证数字

[54轮DB/文件/事件/回执对账](enforcement-document-draft-20261002/ENF14-PUBLIC-RECONCILE-20261003-54/public-input-reconciliation.json)直接读取真实DM8与产品LocalMaterialFiles：材料2/2原件SHA匹配，每份UPLOAD/COLLECT_ATTEST/VERIFY各一个回执和事件，共6回执/6事件，一一按request、操作、操作者匹配。映射历史DRAFT@1/PUBLISHED@2保留，保存/发布各一个回执/事件，共2/2；validate/trial两个持久attempt保留，48轮通过真实产品读取核对其原件。没有文书生成事件或回执。

| 执行 | 范围（不得混计） | tests | failures | errors | skipped |
|---|---|---:|---:|---:|---:|
| 44 | 双DM8/真实HTTPS公开材料准入产品命令 | 1 | 0 | 0 | 0 |
| 48 | 双DM8/真实HTTPS精确事实映射发布产品命令 | 1 | 0 | 0 | 0 |
| 54 | 已准入材料与规则DB/文件/事件/回执对账 | 1 | 0 | 0 | 0 |
| 50 | 身份客户端/当前权限准备（非领域I） | 1 | 0 | 0 | 0 |

每轮XML已按执行编号复制到各自证据目录；Maven报告后缀与运行编号一致，构建分别独占快照，没有并行覆盖。50轮仅权限准备，不计入上述产品命令两项I或文书生成I。执行器历史控制台通用文字“public admission completed”不能把50/54解释成新材料准入，以明确Test类及上述分域结果为准。

### 浏览器产品结果与尚未完成项

[51轮真实浏览器结果](enforcement-document-draft-20261002/ENF14-PUBLIC-BROWSER-20261003-51/result.json)由5174正常HTTPS/OAuth/PKCE登录、点击读取输入取得HTTP200。新案件快照、两份VERIFIED@3材料、各自SHA、当前组织授权和新事实记录映射PUBLISHED@2均已通过页面当前对象核验。

**修复前可消费组合0；本轮修复后仍0。** 当前剩余拒绝项 NO_CURRENT_CASE_REFERENCE：该新同源案例尚无 CONFIRMED/ACTIVE 的法律引用。实际 LegalReferenceTargetStore 只接受 CONDUCT_REPORT / PROCEDURE_REPORT 的持久报告及具体anchor，不能直接将公开材料/案例快照当报告目标。现有其他案件报告或符号评分引用不能搬用；现有公开法规候选保留 REVIEW_REQUIRED / LEGAL_APPLICABILITY_UNCONFIRMED，公开来源真实不等于效力/具体适用已确认。本轮没有制造报告、法律结论、CONFIRMED引用或专业批准，也未放宽该门禁。这是尚缺的同案产品输入连接/引用核验，不标成新增真人或正式业务验收前置条件。

事实记录当前阶段：页面输入选择 NOT_RUN；prepare NOT_RUN；generate NOT_RUN；query生成文书 NOT_RUN；productsGenerated=0。不能将51轮输入读取HTTP200或44/48/54局部验证称为用户要求的真实文书闭环。Conduct/Procedure不额外成为FACT_RECORD文书生成前置门禁，但现有法律绑定端口所需同案报告目标仍未建立；本轮未编造它们。

Codex具名阶段核对（2026-10-03）：原文/派生值/独立SHA、正常准入命令、异人技术核验、同源快照、精确模板映射和上述对账可复核；**ENF-14最终产品技术复核未通过/未执行**，IMP保持进行中，不冻结。下一步只继续该同案合法目标/引用链；组合实际≥1后才真实浏览器prepare→generate→query，不先扩审核/DOCX/恢复。正式业务验收持续 BUSINESS_ACCEPTANCE_PENDING。

### 历史失败与全仓结果

41轮Maven参数引号失败（尚未进入测试）；42轮错误业务用户名导致1 error（未执行业务准入命令）；43轮产品命令实际成功后执行器误读resourceId导致1 failure（已经提交的来源/观察保留，不能称无副作用或回滚）；46轮缺organization参数400；47轮过早切换READ@2，导致@1校验403（正常保存DRAFT已提交并保留）；49轮PREPARE授权到期，浏览器输入403，未生成文书；52/53轮只读对账执行器误把opaque file key拼为裸路径，原件实际为产品适配器.blob文件，分别1 error；54轮改用原有LocalMaterialFiles后实际重跑通过。相关失败日志/XML/截图原样保留，不删除或改写，不修改产品权限/CAS/发布/文件规则消除失败。

修改前 verify-all 退出0，47份基线/41需求行一致。记录前54轮 verify-before-records.log实际退出1：TEST_EVIDENCE_STALE:project；本轮专项不能冒全仓新鲜完整回归。记录同步后的实际结果另保存本目录verify-after-records.log。未修改咨询/裁决模块修绿，也未用旧全仓0覆盖当前陈旧证据阻断。

记录同步后 verify-after-records.log 亦实际退出1，具体阻断仍为 TEST_EVIDENCE_STALE:project，基线47/41一致。不能把本轮独占专项XML或修改前退出0称为当前全仓通过；最后一次机械记录检查另存 verify-final-public-input.log。51轮截图已由Codex直接视觉检查，页面真实显示组合0及新公开案例/两份材料通过，未点击或伪造generate。

## 2026-10-03 同案“报告目标→法律引用”实际准备及冻结契约阻塞（55～62轮）

本轮只推进 IMP-ENF-DOCUMENT-DRAFT-05，不重新设计模板或改咨询/冻结领域规则。真实业务验收仍 BUSINESS_ACCEPTANCE_PENDING。以下是已执行的准备和失败证据，**连接尚未完成、文书产品I仍NOT_RUN**。

### 已实际完成

- [55轮运行对象选择](enforcement-document-draft-20261002/ENF14-CNIPA-REPORT-SELECT-20261003-55/report-object-selection.json)：真实DM8只读查询发现当前ACTIVE证据规则均为 PROJECT_TEST/CONTROLLED_INPUT_DEFINITION，行为规则均为 COUNTERFEIT_PATENT。不能套到CNIPA商标案例、把案型改成PROJECT_TEST或搬用其他案件报告。
- [56轮同案法源准备](enforcement-document-draft-20261002/ENF14-CNIPA-LEGAL-SOURCE-20261003-56/source-linkage.json)：复用44轮已正常准入的同一个CNIPA官方案例解读HTML及原HTTP采集证据，没有编造新网页。原HTML SHA fd1c6ffb91d32829a8393cccdf9197f7e2710d405a80baa932db10bdabf1c562；从原正文p7/p12精确抽取第四十八条引用段和第五十七条讨论段，候选逐段locator/SHA固化于 legal-catalog.json。它们是**官方解读原文**，不是独立法规全文或已确认适用意见，effectStatus/reviewStatus=REVIEW_REQUIRED，transitionStatus=UNKNOWN，保留历史法版本/行为时点/具体适用缺项。PUBLIC_COLLECTION_ALLOWED不升级法律效力或商业权利。
- 58轮真实HTTPS调用 /conduct-rules 返回404。根因是 ProductEnforcementApplication 的正式ComponentScan遗漏已有Evidence/Conduct模块，而非需要创建新领域。只补该正式启动入口的两个现有配置包，没有改ENF-04/07领域实现；59轮独占当前源码编译并重启同一5444，保留旧server日志/pid于本机 runtime-history/ENF14-CNIPA-LEGAL-DRAFT-20261003-59。原5311业务/5312独立权威、材料根、案件快照、映射和法源目录保持原对象；未触碰5443咨询。
- [59轮正常产品草稿落库](enforcement-document-draft-20261002/ENF14-CNIPA-LEGAL-DRAFT-20261003-59/same-case-legal-preparation.json)：两名既有受控技术身份经5174正常OAuth/PKCE登录，仅由正常 /conduct-rules 命令保存商标规则草稿 ENF14-CNIPA-TRADEMARK-ENF14-CNIPA-LEGAL-DRAFT-20261003-59@1；真实HTTP读取与DM8 payload相等，DRAFT、REVIEW_REQUIRED、noDefensesConfirmed=false；DRAFT_CONDUCT_RULE事件/回执各1，报告表数量未增加。候选由既有FileLegalKnowledgeSource实际读取，原HTML及两段SHA验证通过。**草稿未ACTIVE，未声称发布/法律确认/报告生成。**
- 60轮真实浏览器已OAuth200、profiles200，但输入403；拒绝后旧执行器等待已清屏的main导致失败留证挂起。保留 actual console 对应观察及 aborted-browser-observation.json；只终止本轮确定的进程，不动此前独立浏览器进程。修复执行器先记录HTTP失败、先保存failure.json、密码框存在才清空、失败截图有限时；未改变页面权限或产品状态。
- 61轮通过既有ProductGrants/独立水位重新授予受控技术账号现时精确READ及PREPARE；不改账号真实性、来源有效期或直接写业务表。两项权限/客户端准备单独计数，非领域I。
- [62轮真实浏览器](enforcement-document-draft-20261002/ENF14-CNIPA-LEGAL-BROWSER-20261003-62/result.json)：实际5174页面导航、正常OAuth/PKCE、读取3基线、输入HTTP200、143项当前条件；截图 runtime-input-state.png 已由Codex直接视觉检查。新案例、两材料、原件SHA与组织/现时读取通过；**组合仍0**，缺同案CONFIRMED/ACTIVE引用。浏览器没有prepare/generate/query文书动作，productsGenerated=0。

### 无法在现有冻结边界内伪装补齐的连接

实际 LegalReferenceTargetStore 只消费 CONDUCT_REPORT / PROCEDURE_REPORT 及确切payload anchor。下面不是正式业务验收或“请业务方再填一张表”的前置条件，而是当前产品连接的代码/来源合同限制：

1. **Conduct发布矛盾**：ConductEvaluator 对 noDefensesConfirmed=false 的规则，不传defenseRefs必产生 DEFENSE_UNASSESSED，传任意非空defenseRefs必产生 DEFENSE_REVIEW_REQUIRED；ConductRuleService.verifyExample 又禁止带任何gap的发布正例。因此带抗辩标准的规则不能产生发布要求的零缺项正例。独立组件反例仅用明确 CONTROLLED_COMPONENT 数据，把其他条件全部满足，分别实际运行两种defenseRefs，仍只能INSUFFICIENT；不把控制样本称真实案件或真实历史权利。不能为绕过矛盾填写“无抗辩已确认”。这需要先允许最小冻结契约兼容修正，当前没有该授权，未改实现。
2. **Procedure来源限制**：FileProcedureSource.root明确只接受 origin=PROJECT_AUTHORED_FICTIONAL。不能把已采集的真实公开案例标成虚构来获得程序报告，也不能造立案/行为日期或期限规则。
3. **Evidence可解析材料**：既有VerifiedEvidenceSource需要明确Reference元数据；原HTML/派生JSON没有该行。后续只能创建明确标记机械派生、带原文SHA/locator和系统关联元数据的新材料，经正常准入并新增不可变案件版本；不能修改原始官方HTML冒充官方Reference。
4. **同案法律核验**：CNIPA原文注明2013修正商标法第四十八条，并讨论第五十七条；只能证明该官方解读的引述。原文2019-11-15是结案日期，不能替代行为时点。独立历史法规版本、历史权利、完整抗辩标准和具体适用尚未证明；不从公开可访问推导VERIFIED/CLEAR/CONFIRMED。新规则草稿保留这些缺项，不发布失败规则，不伪造正例或批准。

**当前结果：可消费组合0→0；合法同案报告尚未生成；法律引用尚未确认；prepare/generate/query NOT_RUN。** IMP保持进行中，不扩审核/DOCX/恢复。下一安全动作是先解决上述冻结发布契约的兼容授权/修正边界，然后继续同案派生材料正常准入→证据整理→有缺项的真实行为报告→适用核验→确切anchor引用；实际组合≥1后才浏览器选择→prepare→generate→query。此顺序不是新D或新的真人验收门槛，不允许借它假称完成。

### 本轮机器证据和具名阶段核对

| 执行 | 实际范围 | tests | failures | errors | skipped |
|---|---|---:|---:|---:|---:|
| 55 | 上游规则/引用只读选择，非领域I | 1 | 0 | 0 | 0 |
| 58 | HTTPS草稿命令实际404失败 | 1 | 1 | 0 | 0 |
| 59 | HTTPS/双DM8规则草稿准备，非文书/报告I | 1 | 0 | 0 | 0 |
| 59 | 运行装配及冻结发布契约反例组件C，非I | 2 | 0 | 0 | 0 |
| 61 | 客户端/当前权限准备，非领域I | 2 | 0 | 0 | 0 |

XML按执行后缀复制至各自证据目录，59/61使用同一独占源码快照顺序执行且报告后缀不同，历史XML不覆盖。57轮Map.of超出10项编译失败未进测试，maven.log原样保留；58/60失败不改写为通过。59轮成功是草稿命令与对账，不是发布，更不是ENF-14完整I。

Codex具名阶段技术核对（2026-10-03）：同案原始字节/HTTP/候选locator/SHA可复核；正式运行装配已真实修复；草稿正常命令、当前版本、DB、事件/回执可复核；发布矛盾已有实际组件反例，不能称法律/业务审核通过。**最终技术复核未通过，IMP不冻结。** 修改前 verify-all 实际退出0；记录前59轮 verify-before-records.log 实际退出0、47/41基线未变。记录后重新运行结果单独保存，不把专项、组件或旧0冒全仓当前通过。

记录后首次 verify-after-records.log 实际退出1：Node读取 docs/project-plan.json 时 open/UNKNOWN/-4094，尚未完成全仓检查，不改写为通过；原日志保留。原样重跑 verify-after-records-retry.log 实际退出0，47文件/41契约一致，不改无关模块。该结构验证通过不消除同案报告/法律核验的实际缺项，也不代表文书产品I完成。最终记录机械复查另存 verify-final-records.log。


## 2026-10-03 用户授权的最小发布兼容修正（63～68轮）

本会话用户明确允许修正已证实的冻结发布契约矛盾。主计划登记 FIX-ENF-CONDUCT-PUBLICATION-01，依赖原已通过R/D及已冻结IMP-CONDUCT；本轮仅该兼容修正，不重开D、不重新设计、不改咨询，不把正式业务验收作为技术开发门槛。技术决定见 docs/adr/ADR-ENF-CONDUCT-PUBLICATION-01.md。父任务 IMP-ENF-DOCUMENT-DRAFT-05 仍进行中。

### 实际代码变更与不变边界

ConductRuleService.verifyExample 仅新增6行、替换1行分类条件。实际评估仍先运行原ConductEvaluator并精确匹配expected outcome、全部gaps、规则ID/版/类型、样例指纹：

- 仅已配置非空抗辩标准、noDefensesConfirmed=false的规则，允许发布正例明确预期 DEFENSE_UNASSESSED / DEFENSE_REVIEW_REQUIRED；案件输出仍完整保留该gap和INSUFFICIENT，不能伪报SUSPECTED或删gap。
- 所有其他覆盖缺项（要件、权利、依据等）仍阻发布正例；发布负例必须含非上述固定待复核项的真实覆盖缺口，仅换一个抗辩引用不算负例。
- 原verifyComplete的完整配置、空抗辩确认来源、依据效力/范围、发布批准及当前授权门禁不变；无抗辩已确认分支仍需零缺项正例。
- 未改API、DB迁移、案件评估/结论、人工审核/权限、锁定来源、幂等/CAS或历史存储。65轮 unchanged-contracts.json 逐文件比较59/65产品构建，评估器、契约、分析服务、控制器、锁定证据、CaseScope及DM8 Store七文件SHA完全相同。

这是配置覆盖验证与个案抗辩最终复核的分离，不是确认抗辩不成立、不证明法规有效或具体适用、不写正式决定。

### 组件与真实产品验证

| 执行 | 实际范围 | tests | failures | errors | skipped |
|---|---|---:|---:|---:|---:|
| 63 | 新发布分类正反例C | 9 | 0 | 0 | 0 |
| 63 | 原案件评估器C回归 | 6 | 0 | 0 | 0 |
| 63 | 运行装配/案件抗辩缺项保留C | 2 | 0 | 0 | 0 |
| 65 | 正常HTTPS/双DM8发布兼容I | 1 | 0 | 0 | 0 |

63轮组件包含缺权利、缺要件、伪造零gap/SUSPECTED、仅抗辩负例、重复样例指纹、无抗辩历史分支等；组件不冒真实I。旧59轮矛盾反例XML原样保留，当前测试只改名称/注释以说明案件评估缺项继续保留，不改写历史机器结果。

[65轮真实产品结果](enforcement-document-draft-20261002/ENF14-PUBLICATION-I-20261003-65/publication-compatibility-i.json)经已有5174浏览器正常OAuth/PKCE取得两名既有受控技术主体令牌，再调用实际5444产品API及5311业务/5312独立权威。没有H2、Mock、Stub或Skip替代本项I。

两条受控配置规则分别用无/有抗辩材料正例，正常draft@1→publish@2→read/trial，发布返回200、ACTIVE@2；试跑仍INSUFFICIENT并分别保留DEFENSE_UNASSESSED/DEFENSE_REVIEW_REQUIRED。同键发布返回原回执，异载荷同键及旧版另键CAS均409；伪造结果/缺权利/缺要件/仅抗辩负例409；依据REVIEW_REQUIRED拒409，未获批准scope拒403，真实独立水位撤销PUBLISH_RULE后403。各失败规则只保留先前合法DRAFT、事件/回执各1，无发布版或额外事件/回执；报告表数量未增加。

正向规则发布时历史版本2行，DRAFT/PUBLISH事件和回执各2，request/actor/operation/revision逐项相等，payload SHA留证；试跑不膨胀业务回执。两条受控规则完成后通过正常disable接口停用，历史不删，停用事件/回执追加到各3条。样例里的权利/案件/证据等明确为CONTROLLED配置验证输入，不是CNIPA公开事实、不保存真实行为报告、不声称业务批准；publicCaseRulePublished=false、reportGenerated=false。

65轮只在部署配置中批准精确 CONFIGURATION_TEST_ONLY / TEST_ONLY 范围以验证产品发布门禁，不称公开法源的法律批准；66轮已撤下该测试批准配置，5444继续使用65轮修复产品代码、原业务/权威DM8、原材料根、原公开案件和映射/法源目录。旧运行server日志/pid分别保留于本机runtime-history/ENF14-PUBLICATION-I-20261003-65及ENF14-PUBLICATION-RESTORE-20261003-66。64轮仅编译准备，未实际执行I，不计通过数量。

[66轮浏览器回归](enforcement-document-draft-20261002/ENF14-PUBLICATION-BROWSER-20261003-66/result.json)实际导航、正常OAuth/PKCE、三类模板读取200、输入读取200，143项条件，可消费组合仍0；截图已由Codex直接视觉检查。当前公开案例/材料/SHA/组织与读权仍正常，但同案报告/确认引用未形成。**prepare/generate/query均NOT_RUN、productsGenerated=0。** 不能用受控规则或别案引用补齐。公开CNIPA商标草稿仍DRAFT/REVIEW_REQUIRED，尚需同案可解析派生材料正常准入、完整有来源的抗辩/依据及效力/适用核验、真实报告目标和确切anchor引用；未知行为日期/历史权利等不编造。程序来源的虚构标记限制未改动，不给真实公开案例贴虚构标签。本次授权只解除最小代码矛盾，不等于给专业法源批准。

### 完整后端尝试与verify-all分开记录

67轮首次全后端在独占当前源码快照执行：27套/106项，failures=0/errors=8/skipped=0，因咨询既有公开数据/工作区路径在隔离快照不存在而截断，尚未执行后续模块；失败XML和 full-backend.log 全部保留，不修改咨询或断言。

68轮在全新同源码快照中，仅调整两个临时POM的Surefire工作目录为原工作区，原/执行POM SHA和适配原因保存在 execution-context.json；仓库POM、Java业务源、测试断言均未为此更改，也未把该执行适配当产品发行。实际全后端329套/1085项、failures=0/errors=20/skipped=119、命令退出1。20错误都是运行测试缺必需上下文，具体为：

- EnforcementFollowupDm8MigrationTest、DocumentTemplateDm8IntegrationTest、LegalReferenceDm8IntegrationTest 缺DM8_JDBC_URL；
- JobPackDm8IntegrationTest、RealBusinessCollectionDm8Test 缺JOB_PACK_DM8_URL；
- DocumentRuleAttemptRuntimeDm8Test、DocumentRuleNegativeRuntimeDm8Test（2项）、EnforcementDocumentRuleFacilityFixtureTest 缺ENF_ACCEPTANCE_BUSINESS_URL；
- DocumentRuleDependencyRuntimeDm8Test 缺ENF_DOCUMENT_RULE_DEPENDENCY_RESULT；
- DocumentRuleFrozenNegativeRuntimeDm8Test（9项）缺ENF_DOCUMENT_RULE_NEGATIVE_MANIFEST；
- DocumentRuleRecoveryRuntimeDm8Test 缺ENF_DOCUMENT_RULE_RECOVERY_BASELINE。

这些缺值不是Docker/DM8设施不可用，也不是本次分类修正产生的执法断言失败；默认全仓上下文没有绑定冻结任务各自专用Secret/产品执行manifest/baseline，不能乱填旧manifest或把缺输入改Skip/降低断言。全仓119条件跳过不冒真实I；**全量Maven没有通过**，不把专项或结构验证升为全量成功。67/68所有XML按实际执行编号分别存xml模块子目录，不删除、不改写。

63/65专项原XML均已按执行后缀固化，并用不同文件名供当前机械检查读取，保留原机器时间，未覆盖旧报告或改mtime制造新鲜度。修改前/设计后verify-all均实际0；记录前65轮 verify-before-records.log 实际0、47文件41契约不变。该脚本检查基线/计划/结构/它所读取的报告，与67/68独占完整Maven结果分开；不称全仓Java绿色。记录后结果另保存 verify-after-records.log。

### Codex具名限定最终技术复核

责任人：Codex。日期：2026-10-03。范围：FIX-ENF-CONDUCT-PUBLICATION-01，不是ENF-14父IMP或正式业务验收。

核对原需求中配置完整性与缺项保留、ADR技术边界、仅6行新增/1行分类替换、7文件不变SHA、63轮17项C、65轮真实产品发布正反/幂等/CAS/撤权与DB审计对账、受控规则正常停用和临时批准撤下、66轮浏览器真实读取与未完成边界。结论：**最小授权修正技术复核通过，FIX可完成；父IMP保持进行中。** 不批准公共法律效力、不确认真实抗辩、不代替真人业务验收；BUSINESS_ACCEPTANCE_PENDING继续独立。完整Maven上下文阻断明确保留，不把它写为通过。


## 2026-10-03 事实记录不完整稿真实切片与配置恢复事故（69～81轮）

本轮仍只实施IMP-ENF-DOCUMENT-DRAFT-05；发布兼容修正/评估/法律批准及冻结ENF不改。47文件/41契约基线未改变，69前置verify-all实际0。按现有ENF14/13必填缺失可保存不完整草稿、不可审核合同，修正FACT_RECORD空引用输入与集合筛选层；不取消法律引用必填，不假设法律适用，不创建专业Conduct/Procedure报告作为该分支未声明前置。详细限定见[ADR](../adr/ADR-ENF-14-FACT-INCOMPLETE-01.md)。已有引用仍核同案/当前/CONFIRMED/ACTIVE/SHA/当前Grant；其他两类型不允许空引用。缺项仍blocking，审核/导出拒绝不变。

### 真实已执行结果

- 69组件四套36项 tests=36/failures=0/errors=0/skipped=0；prepare无写、缺引用不完整稿、未确认已有引用失败关闭、另外两类型拒空引用及审核/导出既有反例。XML独占保存在ENF14-FACT-INCOMPLETE-20261003-69，不计真实产品I。
- 73指定文书动作授权准备1项、75现时只读/产品客户端准备2项，均零失败错误跳过；独立技术权限准备不计领域I，没有签署业务审核。71/72/76/77/78浏览器超时/定位失败证据保留；修复登录按钮稳定性/身份异步等待及Ant双汉字按钮实际可访问名，不绕过OAuth或禁用按钮。仅shell finally可能遮盖Node退出码的旧轮不得计成功，以实际failure/result及XML核实，80执行额外显式传递Node退出码。
- 74发行到原5444/5311/5312，原进程PID1160的日志和pid保留在私有runtime-history/ENF14-FACT-RUNTIME-20261003-74；新PID34584。5174仍正式前端、正常OAuth/PKCE，没有咨询复用/H2/Mock。来源用途未延长。
- [80真实浏览器结果](enforcement-document-draft-20261002/ENF14-FACT-BROWSER-20261003-80/result.json)：可消费组合0→1，实际选择同案Case@1/材料@3+@3/FACT_RECORD模板@1/映射规则@2，通过页面prepare→generate→query。实际文书ENF14-FACT-DOCUMENT-20261003-70@1，INCOMPLETE_DRAFT，legalReferences=[]，全部未知字段和法律引用缺项保留；提交审核及DOCX按钮真实禁用。记录步骤URL/时间/HTTP/响应/manifest、页面截图fact-record-product.png；不是仅HTTP200或空输入。
- [81真实对账](enforcement-document-draft-20261002/ENF14-FACT-RECONCILE-20261003-81/fact-document-reconciliation.json)：真实DM8版本1、不可变JSON原件SHA与DB一致，正文摘要一致；GENERATE事件/回执各1，同request_key、主体一致，review行数0；专用XML tests=1/failures=0/errors=0/skipped=0。这是浏览器已生成产物的读库/读件对账，不把此项扩大为整个ENF14 I。

- 实际文书原件 SHA：`d30cb68de511c5ccf75d357eab7f36740db6667cd67ac908d743e34e8da17e26`；正文 SHA：`164d82f4f56f96cab747f13d8b680ae36610fa19c7d71c2dd5816f2bd6625a13`。
- 仍缺12个来源路径：`case.id`、`task.ref`、`record.recordedDate`、`case.region`、`case.stage`、`materials[].ref`、`materials[].note`、`legal.references[].title`、`legal.references[].sourceVersion`、`legal.references[].locator`、`legal.references[].verbatim`、`review.comment`；没有通过文字修订闭合受保护来源，也没有人为同意引用。

### 20个Maven错误及恢复适用验证

[逐测试错误和配置清单](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/environment-errors-and-impact.json)从68原XML提取20项（9项参数化反例分别列出），保留historicalXml绝对路径。DM8_JDBC三件、JOB_PACK_DM8三件来自既有本机专用容器/DPAPI，仅进程注入；没有猜密码或写密码。其余ENF_ACCEPTANCE连接与规则browser result/negative manifest/dependency result/recovery baseline及对应文件根必须逐一确认专用执行上下文，不注入别案或不匹配旧manifest。不删除测试、不把119旧条件跳过计通过。

79为全新独占源码/报告上下文的适用回归，实际5项：4通过、0failures、1error、0skipped，Maven退出1。三项独立schema迁移/模板/法律引用及JobPack存储测试通过；RealBusinessCollectionDm8Test不是配置缺失了，而是实际fixed-ID任务已领取冲突。**79不通过，不能冒当前全仓Maven成功。** 68完整回归1085/0/20/119历史仍保留；本轮没有通过组件/对账取代完整回归。

### 安全事故：不正确的固定ID测试执行上下文

责任：Codex本轮为恢复JOB_PACK_DM8配置，将RealBusinessCollectionDm8Test指向现有ENFACC_BIZ而不是独立空schema，属于错误执行上下文。该冻结测试先导入既有12个公开待办，然后领取固定ID ENF-RBC-CNIPA-GUIDANCE-CASE-1；遇到既有领取状态拒绝，finally仍清理固定任务/包ID和同采集批次。已停止该上下文重跑，不改冻结JobPack代码、不重建同名记录冒原历史、不撤销主库或用测试批准填回。

[只读影响核查原日志](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/collection-impact-audit.log)：当前enf8_real_business_task总数0；目标task/event、目标ENF-JOB-CNIPA-GUIDANCE-CASE-1的revision/event/receipt/integration_trigger均0；事实记录新文书revision仍1。测试清理造成现有记录影响，不是历史失败证据陈旧，不得称无副作用。历史文件证据/失败XML未删，DB记录恢复尚未完成。

当前安全后续为先核可信原备份和原任务/包版本，在独立恢复库核对，再决定有边界的原记录恢复；不得直接回灌整个主库丢失新文书、来源或当前授权，不能声称已恢复。现有旧独立恢复容器/卷可发现，但尚未证其包含受影响记录的最新版本。该事故停止后续产品写操作，不让正式B变成新增开发门槛。

### 限定技术核对与剩余项

Codex具名技术核对：80/81能够证明同案不完整事实稿的浏览器/API/DM8/文件/SHA/事件/回执真实闭环；69只证明组件。未执行真人文书审核、同版DOCX下载、修订/候选/采用浏览器后半程或共同物理恢复，不做整个IMP最终复核/冻结。仍需受影响原记录可信恢复、12来源路径及同案确认引用的真实补全（不能猜填）、有权主体实际同版审核，再完成既有其余D01～D09和另外两类切片。IMP保持进行中，正式BUSINESS_ACCEPTANCE_PENDING。

记录后的verify-all实际退出0，原日志在ENF14-FACT-RECONCILE-20261003-81/verify-after-records.log。该脚本通过只证明本次治理/结构/基线检查，不否定79 Maven错误、68全量20错误/119跳过或数据库清理事故；不称完整回归/正式业务验收通过。主计划列修正后另执行verify-after-final-records.log，不预填其结果。


## 2026-10-03 79轮事故范围与独立历史读回（82轮；未修复主库）

用户本轮要求先处理事故、不继续产品写入。已在唯一主计划登记REC-ENF-JOB-PACK-79-01（P0、进行中；依赖冻结功能/恢复能力；主库修复退出条件包括原记录完整性、差异/回退及明确批准），IMP-ENF-DOCUMENT-DRAFT-05仍进行中。以80/81组合1和ENF14-FACT-DOCUMENT-20261003-70@1不完整稿实际生成/对账为准，不沿用早期组合0/NOT_RUN结论。未重跑固定ID清理测试、未修改业务/冻结模块或咨询/裁决。

### 停写与当前精确范围

受影响DM8为127.0.0.1:5311，codex-enf-i-business，schema ENFACC_BIZ。82只读SELECT前/后/最终导出确认：enf8_real_business_task总数0、task_event0；目标ENF-JOB-CNIPA-GUIDANCE-CASE-1的revision/event/receipt/integration_trigger/file均0。12个原待办精确ID/候选/包ID/批次/版本见隔离历史和拟恢复差异，不猜其它包受影响。此处“触发0”是现状，不能从删除后的0推断事故前必无触发；历史候选触发亦0，仍受覆盖时间限制。

为落实停止产品写入，已仅停止本会话启动且PID/--product-enforcement/70轮classpath匹配的5444 PID34584；保存日志/pid，未重启产品导入器，防止自动重建同名待办冒恢复。5311/5312仍运行；5443正常监听，咨询/裁决未停止或改配置。记录见[runtime-write-freeze](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-audit-20261003-82/runtime-write-freeze.json)。前置verify-all实际1/TEST_EVIDENCE_STALE；47文件/41契约一致，未改变需求基线。

### 可核查恢复来源清单

证据统一沿用79事故目录的recovery-audit-20261003-82，不另建任务控制台账。

| 候选 | 容器创建时间（UTC） | 本轮动作 | 恢复能力/限制 |
| --- | --- | --- | --- |
| codex-enf-penalty-enf-pen-recovery-20261001-163 | 2026-10-01T19:05:08.626975953Z（北京时间10月2日03:05） | 停止旧卷:ro挂载核SHA，复制到全新卷/5367后SELECT | 实际读得12待办及目标包历史、12原件可核；未证明最新完整 |
| codex-enf-penalty-enf15-16-cold-20261001-132 | 2026-10-01T04:37:41.118327711Z | 只读核物理摘要，未启动原容器 | 更早历史候选；未读DB、不当最新证据 |
| codex-enf13-restore-enf13-cold-20261001-92 | 2026-10-01T01:39:25.780051851Z | 只读核物理摘要，未启动原容器 | 更早历史候选；未读DB、不当最新证据 |

[完整来源时间/摘要/日志清单](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-audit-20261003-82/recovery-source-provenance.json)记录三卷摘要、原冷拷索引、79日志、保全服务日志及既有功能/真人现场证据的时间/SHA。另有25个相关容器元数据清单和不同命名前缀ENF13补记，不输出容器Env/口令。时间是明确UTC；DB记录的无时区timestamp字符串按原值导出，不猜时区。

**可信性限制：**旧163卷启动/执行过恢复验证；当前22个文件中6个摘要与原启动前冷拷清单不同（清单Compare差异12条）。不能称保留卷为原冷拷未动副本或最新主库备份。82只证明当前保留历史的字节精确复制及实际可读。网页/原件、截图、旧测试结果不能替代完整DB历史，快照后到事故前的未知变更仍未证。

### 实际独立验证及拟恢复差异

复用既有独立DB+文件物理恢复的安全子步骤，限定设计见[recovery-boundary](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-audit-20261003-82/recovery-boundary.md)。没有调用含主库Grant/客户注册/产品写动作的整套旧恢复脚本。

- 全新隔离容器codex-enf-incident-79-readback-20261003-82、卷同名-data、端口5367；源旧163卷始终停止并以:ro挂载，拷贝容器network none。启动前22文件逐SHA相等；未回灌5312权威，未启动恢复产品服务/importer，仅实际JDBC SELECT。读回后已停止新副本并保留，不启动原候选库。
- [隔离历史原始导出](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-audit-20261003-82/isolated-candidate-history.json)：12个待办中目标ENF-RBC-CNIPA-GUIDANCE-CASE-1为v2/CLAIMED，另11个v1/UNCLAIMED；工作流均WAITING_REAL_HUMAN_ACTION。原领取事件1，目标包v1/contentRevision1/WAITING_REAL_HUMAN_ACTION，包事件1/回执1、触发0、enf8_job_pack_file0；目标原包SHA8c2bb88616657863d79e4e9dead218a0e11568b8b922078ea29d10d3df85763c。领取原记录时间2026-09-29 19:03:18.266648，包创建19:03:18.359779（DB原字符串）；与既有真人现场记录A领取/包v1相符，不制造批准/岗位/回执/评价。
- 实际定位既有存储中的12份rawFileKey原件（不是从网页重新采集），SHA全部与保留DBpublic_payload匹配；复制到独立文件根%TEMP%/ENF-INCIDENT-79-20261003-82-files并逐份重新读取复算SHA12/12。原URL、抓取时间、原件定位、逐SHA见[原件及链核查](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-audit-20261003-82/isolated-originals-and-chain.json)及[回读保全汇总](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-audit-20261003-82/isolation-preservation-summary.json)。包列SHA与原payload内SHA字段一致；另外记录原payload字节SHA，不把它冒原业务摘要重算结果。
- 领取event版本/主体与task吻合，包revision/id与payload吻合；包event/receipt同request_key/主体/动作/版本/状态一致。历史主体3d2c34cf-4beb-42e1-9df8-5ff7158a4e83在当前身份库ACTIVE；这不自动证明岗位或某个现时动作授权。旧库Grant绝不拿来当当前授权。
- [拟恢复差异](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-audit-20261003-82/proposed-recovery-diff.json)为16条原记录：task12+task_event1+包revision1+receipt1+event1；触发/文件注册行数拟写0。逐行原键/版本/摘要已列；主库16条写入NOT_RUN、无批准。恢复只能保留原字节/时间/缺项，不重算成READY，不补事件、不新建同名数据。
- 新文书ENF14-FACT-DOCUMENT-20261003-70@1仍在；原件回读SHA与DM8一致，before/after/final文书元数据相同。选择的id2_current_epoch授权投影前后一致，权威未恢复；该投影核对不是全权威重新验收。主库业务写入0，原旧卷未修改。

### 剩余边界和下一步

Codex限定技术复核：以上只读范围、独立物理复制/历史/原件/关系和主库保全证据真实可复核；不是整个事故恢复完成，也不增加ENF14领域I计数。旧卷不能证明快照后未知历史；若有缺失的更晚版本/事件/回执，当前不可恢复范围必须保留待查，不能从材料或旧测试补造。来源时间覆盖和主库定向16行修复均需明确复核/批准；既有恢复合同只覆盖独立验证，不自动批准主库数据修复。

拟恢复风险及回退：仅确切缺失键、原payload和类型化原时间，在保全修复前快照后按FK顺序单事务执行；任何同键异字节、授权或原件变动、并发写或缺证均拒绝并回滚未提交事务。提交后的补偿需准确新增键/摘要及另批准，不能宽泛前缀删数据，更不能整库回滚丢新文书/来源或复活旧权威。未批准之前停在只读/隔离验证。

79五项4通过1error/0skip、68二十errors/119skips保持原结果；本轮没有重新运行固定ID测试或将它们转成通过。后置治理日志另存incident-verify-after.log，以实际退出为准，不用治理抵消事故。安全恢复阻塞解除后才继续事实记录适用修订/candidate/diff/adopt；必填及同案CONFIRMED/ACTIVE引用/有权主体真实审核满足才允许review和同版DOCX。另两类和整个IMP不提前关闭，正式BUSINESS_ACCEPTANCE_PENDING继续独立。

82轮记录后的verify-all实际退出1，具体TEST_EVIDENCE_STALE:project；原日志recovery-audit-20261003-82/incident-verify-after.log，47需求文件/41契约基线检查通过。未用只读审计充当Maven测试证据、未改XML时间、未清理旧失败或修改无关代码修绿。主库修复仍NOT_RUN，事故任务及ENF14 IMP仍进行中。


## 83轮：事实记录当前切片核对与事故定向修复方案（2026-10-03）

本轮按最新主计划优先处理REC-ENF-JOB-PACK-79-01，ENF14 IMP仍进行中，未增加发布/评估/ENF11/16前置，未修改业务代码或打开其他文书分支。80/81已证明组合1和ENF14-FACT-DOCUMENT-20261003-70@1的prepare/generate/query；缺同案CONFIRMED/ACTIVE引用及12来源路径继续按合同缺项，审核/同版DOCX不代签。

新增真实只读SELECT：[primary-readonly.json](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-audit-20261003-83/primary-readonly.json)。目标12待办及目标包/事件/回执仍0，新文书和选取id2_current_epoch投影与82最终值相等。5444无监听；未启动产品/旧卷/复制新卷，未写主库或授权，未重新计算领域I数量。

[observations.json](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-audit-20261003-83/observations.json)保存时间原证据、20历史errors的11个测试类/逐测试和所缺配置、79实际4通过1error以及剩余上下文清单。既有受控连接组已恢复不代表可以对运行schema重跑固定IDfinally清理测试；恢复/浏览器专用manifest不能由其他旧证据冒充。受影响适用回归与68完整回归分开，原失败/skips不转通过。本轮Maven执行0。

备份源inventory采集2026-10-01T19:04:41.397Z、物理摘要文件19:04:59.070Z～19:05:06.740Z只约束操作证据，不证明DB截止；源卷运行后6文件差异仍保留。79 Maven日志文件时间窗口2026-10-03T12:34:38.416Z～12:35:24.684Z，首个缺失审计12:39:01.456Z不当DELETE commit。精确数据截止与删除提交仍UNKNOWN；容器创建/停止绝不替代截止。公开证据及两个既有私有证据目录限定搜索1971个≤4MB文稿/json/txt/log，未发现目标包v1后的原版本/事件，不证明事故前无更晚记录；WAL/redo/全量审计尚无覆盖。

[16行历史定向修复方案](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-audit-20261003-83/recovery-design.md)明确原字节/版本/时间保留、unique冲突拒绝、task→task_event和revision→receipt→event顺序、独占写窗口单事务/rollback、全等NO_OP幂等、提交后单独补偿批准、当前新文书及授权保全。Codex具名方案技术复核通过边界审查，但不是主库授权/执行/验收；业务SHA独立复算、现时约束和所有写者隔离仍为批准后执行前核验条件。主库写脚本未生成，16条写入NOT_RUN，未知历史不补造。

前置verify-all实际1/TEST_EVIDENCE_STALE，47文件/41契约一致，原日志verify-before.log。只读检测449工作区XML，最新ProductSourcePurposeVersionTest.xml mtime2026-10-03T13:13:03.316Z，晚于它的是裁决MaterialOrganizationConfiguration.java/CaseApiFixture.java（13:57:19.970Z）。生成器扫描backend/db内所有非target文件，不仅java/sql；该mtime关系造成全仓陈旧门禁，不能声称新的ENF14断言失败。未改XML时间、移除报告、改无关模块或跑危险测试修绿。独立证据不被该扫描器当作工作区全量新鲜性证明。

下一产品动作：明确批准且定向历史安全恢复后，恢复入口并续事实记录revise→candidate/diff→adopt；满足既有必填/同案引用/有权主体实际审核才review→同版DOCX。不关闭IMP，正式BUSINESS_ACCEPTANCE_PENDING独立保留。后置治理结果在本节后据真实执行追加。

83轮记录后verify-all实际退出1，仍TEST_EVIDENCE_STALE:project；47原需求文件/41契约检查通过，日志recovery-audit-20261003-83/verify-after.log。不是ENF14新实测失败，不是全仓通过；旧20errors、79清理事故及未知历史均未消除。5444保持停机，主库写入0，等待上述限定历史修复的明确批准。


## 84轮：摘要、现时约束与写者只读预核验（2026-10-03）

限定REC-ENF-JOB-PACK-79-01三项剩余预核验；不重复1971文件搜索、旧卷复制、12原件SHA，不启动任何产品/旧库/测试/importer，不写业务库/Grant。两个新scripts/java取证工具只读分析/SELECT与生成JSON，未改领域、发布门禁或咨询/裁决。5444继续无监听，ENF14既有不完整稿成果保持有效，IMP仍安全阻塞，正式B仍Pending。

### 原业务摘要：证据不足，不能提升候选

[preflight.json](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-preflight-20261003-84/preflight.json)保存82原导出文件字节SHA、payload导出字符串UTF8字节SHA、原算法完整输入和实算结果。使用JobPackService.build的UTF8 SHA-256和JobPackContract Java record toString顺序；facts/findings/publicInput通过原payload解析，不JSON重序列化，不改82文件。源文件/合同SHA、Git源版本0860e98ca7800e7dae76775f683c444f3243a639及读取的Snapshot.class SHA在[算法/入口证据](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-preflight-20261003-84/writer-entrypoints-and-algorithm.json)；这不证明历史运行二进制身份。

目标包ENF-JOB-CNIPA-GUIDANCE-CASE-1@1实算业务SHA为0afa384a8e09b2b61505cb2bea65b66719125f39f0f07781e003b967a134f9cc，原列/内嵌保存值8c2bb88616657863d79e4e9dead218a0e11568b8b922078ea29d10d3df85763c，**不一致**。原导出共952个Unicode U+FFFD，其中目标包payload含138个；12 task及1 package_revision即13条候选涉及替换字符，原中文标题/来源机构/publicFacts等值不能证明无损。不能把文件SHA或列与内嵌SHA相同当业务复算通过。尚不证明这是DB原内容损坏还是导出编码链转换，也不猜回中文；本轮按批准范围不重开隔离副本重取。83方案的原字节保留条件当前不满足，原方案/原输出保留，由此新增结论限制其执行。

### 现时约束：五表实际核对，无当前键冲突

[comparison.json](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-preflight-20261003-84/comparison.json)逐列对照V062/V066：五表45列类型、VARCHAR长度及nullable匹配，TIMESTAMP实际精度6；PK/UQ分别为task_id/candidate_id/package_id，task_event request_key，revision(package_id,revision)，receipt request_key，event event_id/request_key。task_event→task及event→receipt FK符合原方案次序；包被已有file/trigger FK引用，其非写入关联纳入目录核对。CHECK原条件已读取，包WAITING_REAL_HUMAN_ACTION及领取状态约束保持启用。

共57次参数化SELECT覆盖41组不同主键/唯一别名（重复PK/index检查分别留证），当前全部matching_rows=0；未使用FOR UPDATE、DDL、迁移、结构改动或业务命令，查询timeout10秒、只读连接、事务rollback。当前读时无冲突不代替批准执行窗口最终核验。

初次Oracle式TRIGGER_TYPE字段在当前DM8目录报-2111；保留原错误。只读目录实际列为TRIGGERING_TYPE，修正取证查询后[最终目录](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-preflight-20261003-84/catalog-final.json)五表ALL_TRIGGERS/USER_TRIGGERS均空。未把字段错误当无触发器。首次取证工具缺当前合同classpath的编译错误preflight.log及成功补当前本地classes的preflight-run.log都保留，均不计领域测试。

### 写者识别：全部隔离未证，不停未知进程

[writers-clarified.json](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-preflight-20261003-84/writers-clarified.json)保存有边界Windows进程/监听观察，原盘点writers.json保留；盘点脚本自身包含关键词导致误命中已识别剔除，不当产品/测试进程。未见执法Java产品或5444监听；5311/5312及5174监听存在。两个shell/node只命中enforcement关键词，不据此证明写者/工作性质，未停止它们。Docker仅已有46个执法容器状态元数据，无环境/命令行/密码输出，未启动或停库。

读取V$SESSIONS遭现时ENFACC_BIZ权限拒绝-5504/28000；未新增Grant或切管理员越权。数据库其他会话、远程客户端、定时/worker部署及盘点后新进程仍UNKNOWN，执行窗口无法肯定隔离必须拒绝。静态已定位Dm8两个Store、JobPack Controller、启动PublicCollectionTaskImporter及三类DM8/HTTPS测试写入口；Importer遇不存在任务会正常seed，固定ID测试finally删除collection_batch记录，均为重启/重复领取/测试风险，不执行它们验证。源路径/摘要保留不输出凭据或原命令行。

### 统一判定、治理及下一边界

Codex具名预核验结论：**16条候选EVIDENCE_INSUFFICIENT，不允许主库恢复提升**；当前无键冲突并不抵消原字节/业务SHA缺口。精确备份截止/删除提交UNKNOWN、更晚版本未找到但不证不存在保持83边界，不扩WAL恢复或搜索。主库修复NOT_RUN、生成恢复写脚本0、产品写入0、领域/浏览器测试执行0，5444保持停机，文书修订/候选/审核/DOCX未开展。

明确批准之后仍必须先取得无损原字段并通过业务摘要、确认最新历史/重复领取风险处置，再在批准窗口重核实际约束/触发器/唯一键、全部写者隔离、当前新文书/来源/权威保全及单事务验收。任何条件不满足仍拒绝。16条旧历史恢复不代表事故前完整恢复；服务重启需要独立明确的最新历史和重复领取处置边界，不能自动解锁。

前置verify-all实际退出0，47基线文件/41契约一致，verify-before.log保留；83旧stale失败日志不删除、不改XML，不代替其他会话补跑裁决/咨询，不声称治理0已修复事故。后置结果按实际执行追加。

84后置verify-all实际退出0，47文件/41契约基线一致，日志recovery-preflight-20261003-84/verify-after.log。当前门禁结果与83历史stale失败分开保留；本轮未补跑咨询/裁决或改XML，Maven/浏览器测试0，治理0不代表恢复完成或产品闭环通过。Codex预核验最终结论仍EVIDENCE_INSUFFICIENT：无损原payload/业务SHA及所有写隔离不足；主库修复NOT_RUN，5444停机，IMP安全阻塞。下一动作仅经明确批准后安排无损重新取证与限定16条受控修复窗口；最新历史/重复领取风险和服务重启边界不得自动放行。


## 85轮：现存隔离副本无损重新取证与业务SHA修复（2026-10-04）

按82批准的只读核查/独立恢复合同，仅在既有codex-enf-incident-79-readback-20261003-82及其独立卷/5367内重读16条精确原键。未新建/复制卷、未启动旧源卷、未触及5311/5312、咨询/裁决代码库容器或预算，未启动执法产品/importer/测试；5444保持停机。独占卷只有该停止容器消费，5367原端口无占用，固定镜像/独立挂载/端口和无产品命令符合原合同，[启动前边界](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-reexport-20261004-85/replica-gate-before.json)。本协调指令不当主库修复或服务重启批准。

### 编码缺陷定位与原证据保留

82旧导出原字节SHA为5a3843dc486fa1abe3f676a78bffd3f5ebfd695adcd53a19f33bfa40d41671bf，大小244510字节；严格UTF8解码失败，字面UTF8替换字符字节EF BF BD计0。84中952个U+FFFD和13条受影响候选来自容错UTF8解码的观察，而不是已经证明的DB原值替换字符；84当时据此拒绝提升仍有效，85新证据进一步定位链路。见[原文件编码观察](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-reexport-20261004-85/old-export-encoding-observation.json)。不猜原文件代码页、不转写原中文，不改82/83/84文件或旧SHA/失败结果。

新exact-ascii-candidate-keys.json只列原16条ASCII主键，不能当原字段来源；中文/原payload/时间等全部从隔离副本重新SELECT。首次工具路径字符转义编译错误reexport.log、第二次严格UTF8拒绝旧文件reexport-run.log均在JDBC连接前，失败日志保留且副本都已停回；离线修正/编译通过后仅一次实际16键SQL重新取证，不计领域I。最终成功reexport-run-direct.log退出0。

### JDBC直接UTF8写出及无损往返

[无损原16记录](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-reexport-20261004-85/retained-history-utf8.json)由Java Files.writeString(UTF_8,CREATE_NEW)直接写文件，不经控制台/PowerShell编码转换。16记录=12 task+1 claim event+1 package revision+1 receipt+1 event，199字段（166非空）逐字段保存原JDBC值、UTF16单元/Unicode码点、UTF8字节长度、文本和文件字节SHA、字段原文sidecar。非空VARCHAR/CLOB同时用getString及getCharacterStream比对；13 CLOB的驱动length与读出字符长度一致。29非空TIMESTAMP保存DB原字符串、JDBC LocalDateTime及nanos，实际类型值往返一致，不加未知时区；另11空时间字段保持NULL。

字段UTF8文件往返和整个JSON读取后199字段均完全相等；新导出U+FFFD=0，文件SHA7c7739b07835709484c4a125f0f4c2e6ef00eab741c2ced458113da498aa88cf。UTF8字节是JDBC已解码原文本的可复核编码表示，不冒DM8物理存储原字节，未猜未验证DB摘要函数。见[往返与业务SHA](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-reexport-20261004-85/roundtrip-and-business-sha.json)。源代码及编译工具/合同类摘要在replica-gate-after.json与导出内算法来源列出。

### 业务摘要、关联与候选有限提升

按保留JobPackService.build原领域UTF8 SHA-256及Java record格式复算，没有重序列化替代原payload。目标ENF-JOB-CNIPA-GUIDANCE-CASE-1@1保存列SHA、payload内SHA与实算均为8c2bb88616657863d79e4e9dead218a0e11568b8b922078ea29d10d3df85763c，完全一致；原payload文本UTF8SHA另为0ad7dc7bbacb66530d577149d2f0182f143683a8d2e85be95a625890e2df6e8b，两者意义不混用。原算法输入另直接保留business-sha-input.utf8；新副本原记录无替换字符且业务SHA匹配，说明此前不一致是82文件编码/读取链问题，而非这批副本原记录损坏。旧84实算0afa384a…作为失败解码证据保留。

[原记录可信及关联](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-reexport-20261004-85/historical-record-trust-and-links.json)逐16原键/版本列新UTF8原行摘要。目标task v2/CLAIMED、另11 task v1/UNCLAIMED、包v1/contentRevision1/WAITING_REAL_HUMAN_ACTION不变；ASCII键/版本/时间/事件fingerprint与82一致。领取主体/任务/版本/时间、包源/组织/标题/claimedBy、event/receipt request_key/actor/operation/version/state/time全部吻合；trigger/file注册仍0，未补事件或批准。12 rawFileKey/SHA/原URL/capturedAt/版本逐项与82已有原件保全索引匹配；本轮不重复原件SHA，不冒当前原件重新物理验收。

Codex具名有限技术复核：16条对应候选提升为HISTORICAL_ORIGINAL_RECORD_TRUSTED，仅证明保留副本这些原历史及既有原件关联可信，**最新完整性仍NOT_PROVED**。准确备份截点/删除提交未知、更晚版本未找到但不证不存在不变，未扩搜索/WAL或重复84约束/写者盘点。本结论不是完整事故恢复、现时授权/专业审核或ENF14产品实测通过。

### 停机保全及尚未批准范围

成功后只停止本会话启动的5367副本并保留，见[停止后边界及工具SHA](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-reexport-20261004-85/replica-gate-after.json)。旧源卷未启动，主库/权威连接0，业务写入0、迁移/Grant0、恢复写脚本0、领域/浏览器测试0，5444保持停机。REC及IMP仍进行中/安全阻塞；已有不完整稿成果有效，未开展修订/候选/采用/审核/DOCX或其他文书分支，正式B仍Pending。

下一步仅请求主库16原历史限定修复的明确批准；批准后仍先保全当前数据，在实际执行窗口重核原件/原键/唯一别名/约束、全部写者隔离及当前新文书/来源/授权保护，任何冲突/未知拒绝。84会话查询权限不足仍是执行窗口拒绝条件，不越权或停其他任务。限定16旧历史不等于事故前完整恢复；不自动解锁产品，最新历史未知及重复领取/重启风险必须单独明确处置边界。

前置verify-all实际0，47需求文件/41契约一致，verify-before.log；后置据实际结果追加。治理通过不抵消原事故、历史20errors/119skips/79error或83stale，不补跑其他轨道或改XML修绿。

85后置verify-all实际退出1，TEST_EVIDENCE_STALE:project，47文件/41契约基线一致，原日志recovery-reexport-20261004-85/verify-after.log。前置0/后置1分别保留；不修改XML时间或补跑其他轨道以抵消并发修改造成的新鲜性门禁。85仅取证工具/隔离读取成功，不当Maven/浏览器/全仓通过。最终5444与5367监听0，副本exited，主库修复NOT_RUN；16旧历史可信与最新完整性NOT_PROVED、全部写隔离UNKNOWN和未获定向修复批准分别保留，停止在此边界，不继续空转重复取证。


## 86轮：用户批准登记与实际执行窗口门禁（2026-10-04）

本会话用户直接回复“批准”，对应上一条完整限定请求：仅在执行窗口的现时冲突、全部写隔离及数据保全门禁通过后，定向恢复16条可信旧历史；不代表事故前完整恢复，5444停机，服务重启/重复领取风险另行批准。[批准原文及范围](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-approved-20261004-86/user-approval.json)。不是协调指令代批准，不虚构消息ID或业务审核主体，不再请求重复的16条恢复批准。

### 已实际完成的批准后预核验

使用既有DPAPI业务身份只访问5311/ENFACC_BIZ执行非锁定SELECT；未访问5312、旧源卷或5367，未执行产品/importer/测试。原85导出文件SHA及业务SHA仍匹配，当前16原键及唯一别名全部缺失/无冲突，元数据45列和PK/UQ/FK/CHECK与已记录合同匹配。[现时键/约束/会话核查](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-approved-20261004-86/current-key-schema-session-preflight.json)、[现时约束目录](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-approved-20261004-86/current-constraints-triggers.json)。按TABLE_OWNER=ENFACC_BIZ而非只限定触发器OWNER补核五目标表所有可见触发器所有者，结果0；查询无FOR UPDATE/LOCK/DDL/Grant，见target-triggers-all-visible-owners.json。

[修复前保全快照](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-approved-20261004-86/current-protected-snapshot.json)用Java直接UTF8保存原键/别名、目标关联、新文书全列和选取现时授权投影，不经控制台转码。[当前原件及保护核查](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-approved-20261004-86/current-originals-and-preservation.json)：按批准后执行前条件实读12份产品现存原件，SHA12/12匹配；新文书ENF14-FACT-DOCUMENT-20261003-70@1位于var/enforcement-document-draft，其原件SHA与DB一致，选取元数据与83相同。没有复制/覆盖产品文件。

### 现时授权差异及保全边界

当前授权投影581行，较83同581行有7个原键epoch/current_grant_id变化、新增/删除0，涉及已有功能主体的模板动作；本轮未写任何授权，不猜更改人/时点，也不把差异伪称相同。见authorization-projection-delta-since83.json。此处只记录历史到当前的变化，不自动判为本轮副作用或批准它们；真正执行修复必须保全86及执行窗口最新现时值，绝不回灌83/旧备份授权。当前快照不是全权威核验。

### 真实未通过门禁：全部写隔离

现有ENFACC_BIZ再次读取V$SESSIONS仍权限拒绝-5504/28000，不能确认全部数据库写者隔离。本地5444无监听、相关Java关键词盘点为空及本机5311连接观察都不是全部DB写隔离证明。没有新增Grant、用管理账号越权兜底、停止未知进程/其他会话，亦未取得表锁或降级该门禁。

[执行门禁决策](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-approved-20261004-86/execution-gate-decision.json)为REFUSE_MUTATION_UNTIL_ALL_WRITER_ISOLATION_GATE_PASSED：已获恢复批准但该条件缺失，主库修复NOT_RUN，INSERT/UPDATE/DELETE0，恢复写脚本未生成。需要的最小新增权限是明确允许用本机现存受控5311管理身份，仅只读核验恢复相关写者/会话/锁；或者设施管理员提供可核的独占执行窗口。不能索取聊天密码/猜秘密，不需要再批准16条恢复本身，不新造身份/业务批准/设施或专业门槛。

Codex具名执行前复核：当前可信来源、无键冲突、原件、新文书及约束证据已形成，全部写隔离未证，按批准的失败关闭条件停止写入。原批准继续有效；权限条件补齐后仍需重核窗口数据，方可单事务16条；当前授权7变化不回滚。最新历史完整性/准确截止/删除提交未知保留。5444继续停机，不自动解锁产品或推进文书修订/候选/审核/DOCX及另两类，REC/IMP仍进行中、正式B Pending。

### 全仓验证独立边界

前置verify-all实际退出1，47文件/41契约基线一致，当前具体是共享产品权限文件backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product/ProductSourceScopeGrants.java **308行超过300硬限**，不是本轮恢复写入/ENF14测试失败，也不是沿用85 stale结论。原日志verify-before.log；本轮没有修改该文件、咨询/裁决或XML来修绿。后置结果按真实执行追加，不将只读预核验算Maven或产品I。

86后置verify-all实际退出1/TEST_EVIDENCE_STALE:project，47文件/41契约检查通过，日志recovery-approved-20261004-86/verify-after.log；与前置共享ProductSourceScopeGrants.java 308>300结构失败分别保存，不沿用单一旧原因或修改无关模块/XML。5444监听0，原16主库写回仍NOT_RUN（写隔离会话权限门禁真实未通过），用户条件批准已生效且不需重复；等待管理身份只读核验的具体权限或设施可核隔离窗口，不以治理失败冒恢复失败，也不以批准冒完成。

## 87．管理只读核验与已批准16原历史定向恢复实际完成（2026-10-04）

用户直接回复“允许”，批准范围仅是现存5311管理身份只读核验恢复相关会话与锁。与86用户直接“批准”的有条件16条修复分别登记，见[87批准原文](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-admin-readonly-20261004-87/user-approval.json)。未新增Grant、未停止其他会话、未管理写入、未输出或仓库保存凭据；不授权产品重启。安全凭据只由既有专用容器/DPAPI注入子进程并清除。

### 管理只读结果与实际执行窗口

[会话/锁真实观察](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-admin-readonly-20261004-87/session-lock-observation.json)：5311当时只有本轮SYSDBA只读核验连接，事务INS/DEL/UPD均0；五表对象目录可读，存在TRX_ID=0的OBJECT/IX目录锁行，未把这些行解释为活动写者、也不终止它们。未读SQL_TEXT/CUR_SQLSTR/凭据字段值；目录元数据列名不是读取对应敏感值。单次没有其他会话不等于整个恢复窗口已经隔离。

因此使用普通ENFACC_BIZ对仅五表取得EXCLUSIVE/NOWAIT事务锁，探测后rollback释放，写行0；实际恢复时重新获取并持有全部五锁，直至同事务逐字段检查及commit完成。这个事务级写隔离不依赖停未知会话、整库停机、管理员写入或新授权。无写预演precheck及加入同批全部版本历史拒绝条件后的precheck-final均退出0；两次原结果各自保留，不把预演当恢复。正式写入前即时确认5444监听0。

### 原16条真实恢复结果

[正式事务结果](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-admin-readonly-20261004-87/application/restore-execution-result.json)与application-execution.log：Java退出0，2026-10-04T01:05:24.624790600Z提交；INSERT16、UPDATE0、DELETE0。仅使用85可信UTF8原件输入（文件SHA7c7739b07835709484c4a125f0f4c2e6ef00eab741c2ced458113da498aa88cf），不使用82有损解码。166非空字段侧文件摘要及原值一致、原业务SHA重新按现有算法匹配；payload不重序列化。执行窗原键/所有唯一别名无冲突，同12个任务/包全部历史范围无现存部分/新版，五表列/PK/FK/约束列/CHECK定义保持，按TABLE_OWNER查触发器0。

恢复顺序为12 task→1 task_event→1 package_revision→1 receipt→1 event。原主键、状态、操作者、组织、领取时间、payload及本地TIMESTAMP保留，不补批准、回执内容、用途期限或触发。目标任务仍v2/CLAIMED，另11任务v1/UNCLAIMED，包v1/contentRevision1/WAITING_REAL_HUMAN_ACTION；READY没有发生，NEXT-V2-INT-ENF-01未触发。单事务提交前读取199全字段/29非空类型化时间与原值一致、五表总数增量精确12/1/1/1/1、新文书/当前授权/文件保全相等，见application/restored-in-transaction.json及before-commit-decision.json。

### 独立新连接与保全

[独立对账](enforcement-document-draft-20261002/ENF14-ENV-REGRESSION-20261003-79/recovery-admin-readonly-20261004-87/independent-reconciliation.json)：使用新普通业务连接，SELECT和文件读取、写行0、退出0。16行199字段全部相等，29时间类型值无损、不发明时区；13 CLOB内容全字段相等。12当前来源原件SHA全部相等；新文书ENF14-FACT-DOCUMENT-20261003-70@1全列及原件SHA不变。执行窗口最新581授权投影逐项不变，不回灌83/备份授权；86记录较83的7变化仍是独立历史事实，不猜操作者或时点。包trigger/file登记仍0，原事件/回执按原键/原关联保存，没有新业务副作用。

### Codex具名限定恢复技术复核与未解除边界

Codex，2026-10-04：复核条件批准、可信85输入、普通业务身份单事务五表写隔离、原键/别名/新增历史拒绝、类型化绑定、提交前后全列相等与独立新连接保全证据，**16已知可信历史原记录定向恢复通过**。不是完整事故前最新数据恢复、不是正式业务验收、不是ENF14最终I，不新增Maven tests数量。现存历史仅到已知备份，精确备份截止/DELETE提交及中间更晚记录仍NOT_PROVED；不能称所有历史已找回，不能再次领取或自动重建丢失历史。REC/IMP保持进行中。5444仍0监听，恢复后未启动服务/产品/importer/测试/文书写流程。服务重启及潜在重复领取风险仍需独立确认，不借恢复批准扩权。

所有86及此前失败/原文件均保留，没删XML/失败输出、没停止咨询/裁决或改业务代码。新增仅限定事故工具和证据；重复写入入口受目录存在及键冲突拒绝，不自动重试/盲目upsert，提交后无自动补偿删除；未来复核可运行只读对账工具，不再次调用写入工具。

### 全仓验证分开记录

87前置verify-all实际1：共享ProductSourceScopeGrants.java 308行>300；47需求文件/41契约基线一致。日志verify-before.log；未改该共享文件/无关咨询裁决模块，不把恢复通过冒全仓通过。后置真实结果另追加；本轮恢复/会话取证不计产品或领域测试数量。

87后置verify-all实际退出0，日志recovery-admin-readonly-20261004-87/verify-after.log，47文件/41契约基线一致；前置308行失败原件保持，后置当时全仓状态通过单独记录。本轮没有修改ProductSourceScopeGrants.java、咨询/裁决或测试XML来修绿；不能把并行工作空间的前后状态变化归因于本轮恢复。5444仍停机，主库仅原16条提交、最新历史完整性NOT_PROVED，REC及IMP继续进行中。

## 88～89．79事故后指定测试误连/清理防护技术切片（2026-10-04）

主计划先登记ENG-ENF-TEST-ISOLATION-79-01及本日RUN-ENF-UNATTENDED-20261004-01，技术设计见[88隔离设计](enforcement-document-draft-20261002/ENF14-TEST-ISOLATION-20261004-88/test-isolation-design.md)。仅改变测试/工具，未改已冻结Job Pack或其他领域的产品实现，不开启5444，不重跑87恢复，主库及权威库无连接/新写入。

### 连接和清理防护已落地

RealBusinessCollectionDm8Test在第一次构造连接/迁移/import前强制EnforcementDm8IsolationGuard：显式run、随机ENFISO schema、独立loopback端口、独立容器/卷同runId角色标签、实时Docker唯一卷使用者、精确数据挂载、TEMP本轮文件根及容器ID/schema/volume所有权marker；拒绝主/产品/恢复端口、共享目录/卷、缺/错误配置和marker、已消费运行。连接后首次USER_TABLES必须空白；已有schema/固定ID历史拒绝，不是skip。保留全部原正向/反例业务断言，不降低9事件/9回执、CAS/领取反例或技术状态要求；增加Importer重入前后任务/回执/事件不变断言。已去掉事故finally按包和batch DELETE及递归删文件；成功/失败都保留本轮独立数据/原件，不实现未知归属清理。

### 本轮失败与修复分开保留

88 guard组件16/0/0/0通过；初次独立DM8调用在Windows Docker自造JSON模板引号传递处解析失败，真实XML tests1/failures0/errors1/skipped0，real-isolated-dm8.log和原XML均保留；该错误发生在产品Jdbc连接/import前。独立5381及新schema/卷/文件根保留，无删除。不是NOT_RUN转通过。改为Docker对每个白名单字段输出json（不读取Config.Env/秘密），补充未知挂载、文件归属、重入反例后，重新创建89独立5382/卷/schema/根及源码快照，不复用88失败对象和报告。

### 89真实验证

[独立资源清单](enforcement-document-draft-20261002/ENF14-TEST-ISOLATION-20261004-89/isolated-resource-manifest.json)：独立容器codex-enf-rbc-isolated-enf14-test-isolation-20261004-89、5382、ENFISO_83DD5E491561及同名独立卷、TEMP/enf-owned-ENF14-TEST-ISOLATION-20261004-89/files；只用本地DM8镜像，未拉镜像/使用旧源卷。新实例基础设施初始化只为新隔离技术schema RESOURCE，无现时业务身份/授权或主库Grant改变，随机秘密只注入进程并清除，未保存仓库。

独占Maven源码/报告（1187源码文件SHA清单各轮分开保存），两个最新XML分别guard组件 **22/0/0/0**、RealBusinessCollectionDm8Test隔离存储机制 **1/0/0/0**，均Maven退出0；原生DM8迁移V062/V066、12官方公共候选读取/原件、领取幂等/冲突及任务包版本/事件/回执技术机制实执行，Importer重入不覆盖已领取任务。授权/人工动作仍是明确标记机制主体和组件端口，不冒实际产品身份许可、真人批准或ENF14端到端I，不增加正式验收结论。

[真实拒绝与保全探针](enforcement-document-draft-20261002/ENF14-TEST-ISOLATION-20261004-89/real-rejection-preservation.json)：受控既有89隔离库只读连接，真实Docker/文件根检查后，同轮重入FileAlreadyExists拒绝，主5311输入IllegalState拒绝且主连接0；隔离七张相关表完整行指纹和所有本轮文件SHA前后完全相等、业务写0、删除0。探针退出0，不混入Maven/领域I计数。原schema/容器/卷及失败日志均保留；没有执行全库或历史回滚。

Codex于2026-10-04具名复核：此指定RBC测试/工具防护切片通过，ENG任务完成；不代表全仓全部DM8测试已保护/20旧环境errors119skip已解决，更不代表ENF14产品完成。RUN及IMP继续进行中，正式BUSINESS_ACCEPTANCE_PENDING、最新历史完整性NOT_PROVED。5444监听0，不启动Importer服务、重复领取或审核/DOCX。

### 全仓真实状态

88前置verify-all0；89前置及同步完成记录前实际verify-all1/TEST_EVIDENCE_STALE，47文件/41契约一致，分别verify-before.log、verify-before-completion-record.log。隔离新鲜XML只覆盖此切片，不能冒全仓新鲜回归；不删历史报告、改写时间或复制成全仓结果。后置实际日志另保存。咨询/裁决业务/数据库/服务未动。

89同步防护完成后verify-all实际1/TEST_EVIDENCE_STALE，verify-after.log，47/41未变。只停止经实际containerId/runId/role核对本轮自建的88/89独立测试容器来限制闲置资源占用，未删除容器/卷/文件，未停止其他会话或任何咨询/裁决/主库服务。两容器docker stop 20秒后实际ExitCode137，分别2026-10-04T02:55:41.424689795Z/02:56:02.458266215Z，不称干净冷停机或共同物理恢复通过；测试和只读对账原证据取于停机前，保留实际关停边界。5444仍0监听。

## 90．事实记录修订/候选/采用的新增组件边界与重启前只读复核（2026-10-04）

在既有ENF14 D内只增加DocumentDraftWorkflowTest三条未覆盖组合反例，未改产品实现/模板架构/任何冻结域：

- candidate创建后依赖变化，adopt拒绝且旧稿/候选/回执不变；历史可读不等于允许当前消费。
- 已采用候选的同键重放，在当前撤权后仍拒绝；不靠成功旧回执恢复授权，无新增版本/回执。
- 原来源缺项贯穿人工说明修订→独立候选→显式采用；旧版/manifest保留，采用后仍INCOMPLETE_DRAFT且blocking诊断保留。提交、批准及导出均拒绝，渲染器未调用，无审核/导出记录。

新独占源码快照ENF14-FACT-WORKFLOW-SAFETY-20261004-90，整类DocumentDraftWorkflowTest **11/0/0/0**、Maven实际退出0，原XML和workflow-components.log保存本目录。Inputs/Access/Store是明确组件端口，测试文本为controlled技术数据；不冒真实CNIPA案件/真人批准、HTTP/UI或DM8 I。不把旧已通过8条重复回归算新增业务能力，新增加的范围仅上面3条组合边界；本案产品修订/采用仍未运行。

[Importer/CLAIMED/重复领取只读复核](enforcement-document-draft-20261002/ENF14-FACT-WORKFLOW-SAFETY-20261004-90/startup-importer-claim-readonly-review.md)保存三份产品源文件SHA及方法行为，复用87原恢复与89实际独立反例；不改冻结行为，不启动Importer/自动seed/claim或主库业务命令。记录同actor/同组织新key可新增CLAIM事件、Controller缺包时createPublic分支，均不能在未知历史下自动执行。最新历史完整性NOT_PROVED，重启/重复领取风险决定owner仍项目负责人/数据负责人，exit为明确受控决定；今天不反复催真人。正式业务B Pending，审核/DOCX必填和有效同案引用不猜填。

下一安全队列：只准备/执行严格独立且声明范围的事实记录工作流与实际DM8事务组合、失败补偿及事件/回执/文件对账；不能复用88/89失败或旧库作为新轮、不能把组件或存储机制冒产品I。当前业务服务仍停驻，IMP未完成。后置verify-all以本轮实际日志记录，不为全仓陈旧状态改写任何历史XML或时间。

90后置verify-all实际退出1/TEST_EVIDENCE_STALE，ENF14-FACT-WORKFLOW-SAFETY-20261004-90/verify-after.log；47文件/41契约基线一致。专项组件新鲜11/0/0/0不等于全仓通过；旧错误/skip/失败原件没有删除、覆盖或改时间。5444监听0，无主库或其他域写入。下一轮以主计划最新记录选择安全隔离工作，不再重跑已完成指定RBC防护或87恢复。


## 2026-10-04：91～93事实记录工作流组合真实隔离DM8 I

仅按RUN-ENF-UNATTENDED-20261004-01及既有ENF14已过D续测试/工具，不改变冻结领域或业务模型。设计见[91轮边界](enforcement-document-draft-20261002/ENF14-FACT-WORKFLOW-DM8-20261004-91/isolated-workflow-design.md)。新DocumentDraftWorkflowIsolatedDm8IT组合实际DocumentDraftService/Workflow/DM8 Store，Inputs/Access明确机械受控适配器，不冒CNIPA同案输入、实际身份授权、产品浏览器、真人批准或正式业务验收。

91独占快照编译因Inputs同名接口歧义实际退出1，原test-compile.log保留，未创建DB/容器、未运行测试。修正限定名后92编译0/真实独立5383存储事务I **1/0/0/0**，未覆盖91。随后只将新增测试完整保留并重命名IT及运行器白名单接线，防止默认组件回归意外执行真实DM8；断言文本等价校验保存在92 report-integrity.json，原测试并未删除或减断言。93另建新5384/ENFISO_C84F9E06E345/独立容器卷和TEMP根，以全新源码及独占报告显式执行最终IT **1/0/0/0**，Maven退出0；没有借用旧92 XML。编译skipTests仅编译准备，不计验证通过。

[93对账](enforcement-document-draft-20261002/ENF14-FACT-WORKFLOW-DM8-20261004-93/workflow-reconciliation.json)：生成→人工文本修订机制→独立候选/字段差异→显式采用实际落DM8；第二连接读3个完整稿版本+1缺项稿，1候选，共5原件SHA匹配，5事件/5回执。生成/修订/采用同键重放不加版本；旧版本/manifest保留、采用为PENDING_REVIEW、不继承审核。依赖变化/撤权/CAS旧版拒绝。新原件落盘后故意抛异常，真实事务回滚且新原件补偿，原有全部文件名字/SHA完全不变，无第四版/多余事件回执。缺来源稿仍INCOMPLETE_DRAFT，提交/审核/导出实际拒绝、无审核记录、渲染器未调用。此处人工文本是标明CONTROLLED的机械测试文本，不冒真实人员动作。

92/93 XML原件、执行时刻、资源清单、原件SHA与源码哈希各自固化；Surefire XML本身未给timestamp属性，report-integrity.json明确以独占路径、run suffix、资源创建/实际执行/对账时间核验新鲜，不补造XML时间。93 [Codex具名限定复核](enforcement-document-draft-20261002/ENF14-FACT-WORKFLOW-DM8-20261004-93/technical-review.md)通过，不等于IMP最终技术复核。测试后只核精确本轮ID及标签停止92/93隔离容器，退出137原样记录、卷/文件保留，不称洁净物理恢复。5444监听0，主库连接/业务写入0，87恢复未重跑，咨询/裁决/冻结业务不变。

91前置、92后置及93记录前verify-all实际均1/TEST_EVIDENCE_STALE，47/41一致，原日志保存；专项新鲜通过不冒全仓绿，旧20环境errors/119skip和所有历史失败保留。IMP进行中、最新历史完整性NOT_PROVED、正式B BUSINESS_ACCEPTANCE_PENDING。下一安全队列：候选原件落盘后的失败补偿、缺原件/SHA破坏及缺引用审核拒绝的严格隔离反例，不自行重启服务/重复领取/真人审核/本案新写。

93记录后verify-all实际退出1，仅TEST_EVIDENCE_STALE；verify-final-records.log保存，47文件/41契约一致。未将专项I结果导入/覆盖全仓历史报告。


## 2026-10-04：94候选原件与缺引用真实隔离反例

按最新主计划安全队列，测试范围增量，领域/产品代码不修改。设计与Codex限定复核见ENF14-FACT-WORKFLOW-NEGATIVES-20261004-94/negative-slice-design.md及technical-review.md。全新独立5385、ENFISO_567329C5D255、带本轮标签容器/卷及TEMP根，beforeConnect归属+空schema证明后迁移；93及87原资源不复用、不重启，不触主库/咨询/裁决。独占源码/报告，原组合断言全部保留并补新反例，最终真实隔离机制I **tests=1/failures=0/errors=0/skipped=0**、Maven退出0；不是产品/身份/浏览器/真人验收。

候选原件及DB行落盘后故意失败，真实事务回滚，候选不存在、事件/回执无增量、旧全部文件名字/SHA不变。对本轮刚生成的文书及候选分别做SHA破坏和精确原件隔离移位，实际读取全拒绝；归还本轮相同字节后文书/候选/SHA不变。没有删除业务历史或重跑恢复工具，不将隔离故障归还冒充共同物理恢复。新增空法律引用Input形成INCOMPLETE_DRAFT，submit/review/export实际拒绝；原缺来源断言保留；审核/导出行各0、渲染器不调用、无真人批准。

[对账原件](enforcement-document-draft-20261002/ENF14-FACT-WORKFLOW-NEGATIVES-20261004-94/workflow-reconciliation.json)实际5文书版本/1候选/6原件SHA，事件/回执各6；report-integrity.json固化原XML SHA、源代码SHA/本轮suffix和时间链，不补造缺失XML timestamp。故障只作用新鲜本轮对象，容器停止仅本轮精确ID/标签，卷文件保留，若Exit137原值记录而不冒干净物理恢复；5444仍0监听。

94前置与记录前verify-all实际1/TEST_EVIDENCE_STALE，47/41一致；历史20环境errors/119skip和旧失败不改写，专项通过不冒全仓绿。IMP仍进行中，最新历史NOT_PROVED，正式B Pending。下一合法安全项：已CLAIMED同actor不同key的独立存储行为及Importer重启风险证据；主库不试、服务不重启、真人不代签。


## 2026-10-04：95领取/Importer隔离证据与本日安全队列收尾

全新5386/ENFISO_07CE755EC316/独立容器卷与TEMP根，首次datasource前guard及空schema证明；真实Importer/TaskStore组合，不调用产品服务。新增明确IT测试只导入现有公开原件、执行受控领取机械反例，不创建JobPack、不提交身份/批准/回执/评价，无测试真人审核。独占源码/报告实际 **tests=1/failures=0/errors=0/skipped=0**，Maven0。12公开任务原件SHA均一致；task同key重放不增事件，同actor/org的新key实增第2个CLAIM事件，但任务version2/claimedAt/updatedAt/owner/publicInput全部不变，新key再重放不增。不同actor/org及同key冲突fingerprint拒绝，无异常事件/文件副作用；Importer再入完整12任务与全部文件名/SHA相同。JobPack revision/receipt/event和integration trigger均0。

[95原对账](enforcement-document-draft-20261002/ENF14-CLAIM-RESTART-SAFETY-20261004-95/claim-reconciliation.json)、[Codex限定复核](enforcement-document-draft-20261002/ENF14-CLAIM-RESTART-SAFETY-20261004-95/technical-review.md)及report-integrity.json保留原XML/源码SHA与实际时间链。Importer/TaskStore源SHA与90原只读复核完全相同，未改冻结产品。实测同actor新key只增加事件属于风险证据，不被解释为重启批准或新的业务规则；不在主库重复领取。测试容器仅本轮精确ID/标签停止，卷/文件保留；退出137不冒干净物理恢复，5444仍无监听。87恢复工具不重跑，主库连接/业务写入0，其他域完全不动。

94记录后、95记录前verify-all实际1/TEST_EVIDENCE_STALE，47/41一致；收尾后按实际日志记录，不以独立I代替全仓通过，20历史环境errors/119skip及全部旧失败保存。

本日授权的具体安全队列已全部完成：指定测试误连/清理防护88～89、事实记录增量组件90、实际工作流/事务组合93及故障反例94、Importer/已CLAIMED/重复key的只读和隔离证据90/95。RUN-ENF-UNATTENDED-20261004-01仅此限定工程队列收尾，不代表IMP/REC/业务完成。当前无已准入且可在现有授权中继续的产品写入/浏览器/恢复/审核动作，不扩大范围新增实验、不反复催人或空转。剩余blocker：最新历史完整性NOT_PROVED与未批准服务重启（owner项目/数据负责人，exit为历史处置证据及显式运行授权）；同案CONFIRMED/ACTIVE引用与真人审核（owner有权业务负责人，exit为真实专业核验/授权动作）。IMP仍进行中，BUSINESS_ACCEPTANCE_PENDING保持；不得将安全收尾当正式验收。

95收尾后verify-all实际1/TEST_EVIDENCE_STALE，verify-final-records.log保留、47/41一致。按已授权安全队列完成且无合法续行任务停止空转，已通过自动化工具删除本日automation调度（返回deleteStatus=deleted）。仅结束本日限定安全续行，不关闭IMP/REC或业务Pending，也不授予重启/主库写入权限。


## 2026-10-04：96有限受控重启与事实记录续行决策材料（未执行/未批准）

按协调会话本轮明确指令，仅复用87/88～95原件并只读源码，形成[受控重启决策材料](enforcement-document-draft-20261002/ENF14-RESTART-DECISION-20261004-96/controlled-restart-and-continuation.md)及[source/API/版本原件锚点](enforcement-document-draft-20261002/ENF14-RESTART-DECISION-20261004-96/source-and-evidence-anchors.json)。不重复Maven/浏览器/恢复/网络搜索、不连接主库、不重启服务、不修改冻结产品/咨询/裁决，不恢复定时或今日无人值守。首个本地patch工具旧binary路径已不存在，仅定位当前工具后正常完成文件patch，无测试/运行影响。

材料将87已知16原历史的单事务及独立199字段/29时间/12原件SHA核对、95实际Importer重入保全、95同actor/org新key新增CLAIM事件但version/时间不变三类证据分开绑定到实际源码及现有接口。Controller缺包时createPublic风险单列；这些不证明更晚历史完整、合法新的领取或现时产品就绪。启动脚本强制非空public-candidates-path不能原样安全重用；现有Importer空值返回/cleanup.enabled=false是已有控制，新的全局只读模式或claim业务语义不是已有能力，需限定D/变更另批。

推荐最小范围只有：负责人显式接受“已恢复16可信原记录但更晚历史NOT_PROVED”的运行边界；先停机只读核对现有发行/根/目录/原件/当前独立权威及入口限制，关闭既有后台写者后受控启动5444；再正常OAuth/PKCE登录并GET既有对象。正常登录会写OAuth授权/会话，方案明确单独授权，不误称纯SELECT；Grant/epoch/案件/文书/JobPack写及claim禁止。任何前置不成立不启动，任何未允许写或SHA/水位差异只停精确本轮进程并保全，不重跑87恢复或删除新历史。现有访问控制若不足，保持停机并另提最小部署D，不加开发旁路。

ENF14按80/81精确同案Case@1/材料@3+@3/模板@1/映射@2/文书@1定位；来源12路径和必填/可选分别列明，只有summary/review_comment为实际模板可编辑字段。legalReferences=[]以及56/59 REVIEW_REQUIRED不冒CONFIRMED/ACTIVE，Case标识不直接填政府案号/记录日期；FACT_RECORD不额外引入ENF11/16。真实修订/候选/采用需另明确工程写许可，专业来源/引用核验及同版审核须有权主体实际动作；不把93/94隔离I当本案产品闭环。唯一批准问题在材料§8，当前仅提问，未获重启/专业批准。

前置verify-all实际退出1/TEST_EVIDENCE_STALE，47/41基线一致，verify-before.log保留；后置继续留真实退出，历史20环境errors/119skip和所有旧失败不改写。RUN仅限定工程队列已收尾，REC/IMP仍进行中、正式B Pending，本有限材料完成后停止本轮，不定时轮询/凑任务。

96后置verify-all实际退出0，verify-after.log保存；前置1/TEST_EVIDENCE_STALE保留，47/41未变。本轮运行治理脚本及其既有自检，不执行全仓Maven/浏览器/数据库/恢复，后置0不能解释为旧20errors/119skip消失、最新历史完整、服务已恢复或同案业务已验收。

## 97：条件式受控读取停机预核（2026-10-04）

用户按96顺序的条件式授权及实际停止点见 [预核复核](enforcement-document-draft-20261002/ENF14-CONTROLLED-READ-PREFLIGHT-20261004-97/preflight-review.md) 与 [离线原件/容器/源码锚点](enforcement-document-draft-20261002/ENF14-CONTROLLED-READ-PREFLIGHT-20261004-97/offline-preflight.json)。13/13已知原件SHA一致；两个现有主业务/权威容器exited/255，四端口无监听，不能执行现时DB核查。未证明禁止业务写入口，故未受控启动；没有数据库连接/恢复/容器启动/登录或业务操作，没有新增领域I。前置治理verify-before.log实际0/47/41；后置原始日志另存，不冒产品恢复、未知历史完整或业务验收。

## 98：有限受控读取部署前置设计（2026-10-04）

技术任务ENG-ENF-CONTROLLED-READ-98-01仅在docs/08控制。[详细设计及唯一新增批准问题](enforcement-document-draft-20261002/ENF14-CONTROLLED-READ-DESIGN-20261004-98/controlled-read-design.md)、[部署技术ADR](enforcement-document-draft-20261002/ENF14-CONTROLLED-READ-DESIGN-20261004-98/ADR-ENF-CONTROLLED-READ-98-01.md)、[入口副作用及源码SHA](enforcement-document-draft-20261002/ENF14-CONTROLLED-READ-DESIGN-20261004-98/route-source-audit.json)、[最小化容器/引擎线索](enforcement-document-draft-20261002/ENF14-CONTROLLED-READ-DESIGN-20261004-98/container-exit-evidence.json)保留。当前Docker Linux named pipe不可用，未执行新inspect/logs/events或DB连接；97同刻255与宿主重启线索不足断言原因/损坏。新增入口控制待范围批准，不实施或启动隔离I；NOT_EXECUTED不记skip/通过。前置verify-before.log实际1/47文件41契约一致，咨询两任务完成证据缺失与TEST_EVIDENCE_STALE保留，不修外域；后置日志单独保存。原两库仅在保全与控制验证/授权条件成立后恢复进程再新SELECT，数据库启动非纯SELECT。未重复87～95、未业务写/Grant/专业动作/定时续行，IMP/REC/业务Pending不关闭。

## 99：批准的受控读取部署入口实现及组件收口（2026-10-04）

[用户限定批准](enforcement-document-draft-20261002/ENF14-CONTROLLED-READ-IMPLEMENTATION-20261004-99/user-approval.json)后仅实现执法部署过滤器/策略/显式import及受控参数/发行校验。当前[原XML/源码锚点](enforcement-document-draft-20261002/ENF14-CONTROLLED-READ-IMPLEMENTATION-20261004-99/report-and-source-integrity.json)Java C53/0/0/0，工具C2十一项/0/0/0；Maven C2/C3及工具独立进程实际0。C1 Java52/0/1/0和日志/独立构建保留；C2与C3按受影响源SHA选择不重复累计。只证明组件，未运行真实DM8/浏览器/OAuth产品I或恢复。Docker named pipe仍不可用，不启动共享Docker/原卷/5444、不写库/Grant、不重复87～95；发行固定、原卷保全和现时数据库/独立权威仍待真实出口。[Codex限定技术复核](enforcement-document-draft-20261002/ENF14-CONTROLLED-READ-IMPLEMENTATION-20261004-99/technical-review.md)通过仅该C/工具切片，ENG/REC/IMP继续进行中，最新历史NOT_PROVED/业务Pending。前置verify-before.log与实现后verify-implementation.log实际1/TEST_EVIDENCE_STALE，47/41一致，后置日志独立保留，不改历史/外域修绿。
