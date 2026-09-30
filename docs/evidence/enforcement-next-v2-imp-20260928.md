# ENF-08/09、ENF-10/11 唯一产品 IMP 执行证据（2026-09-28）

本记录只登记实际已执行的 C/I/HTTP，不替代 `docs/08-总开发实现计划.md` 的任务状态。来源为 `ARC-ENF-PROCEDURE-04`、`ARC-ENF-CONDUCT-05` 已通过的产品 D；项目输入不产生现实法定效力或业务 B。ENF-01～07 未作为本次实现修改目标。

| 轨道/场景 | 预期 | 实际执行和禁止副作用 | 状态 |
| --- | --- | --- | --- |
| 程序 P02/P04 C | 固定项目工作日基准 9/17、暂停及顺延 9/22；缺事件/日历/未知中断不得 PASS | `ProcedureEvaluatorTest` 3/3；逐日轨迹、缺项、错程序、缺完成事件与逾期 FAIL 均断言。无存储写入 | PASS_C |
| 程序 P02/P04 组合反例 C | 重复起算、暂停未配对/倒序、日历覆盖不足不能推算为 PASS | `ProcedureEvaluatorTest` 2026-09-29 专项 4/4、零跳过；前三种截止项均 `UNDETERMINED` 并保留具体缺口，短日历截止项 `UNDETERMINED` 且无 dueDate。纯计算测试没有 DB、文件、回执或官方状态写入 | PASS_C |
| 程序 P01/P04/P05/P06 C（H2 Oracle 模式事务） | 未核发布、自复核拒绝；上游503不生成报告；整改/重检旧报告不覆盖；撤权和错版无回执 | `ProcedureProductTest` 1/1：自复核拒绝回执仍 1、来源503后仍2；草稿/发布/校验/整改/重检阶段各 5 条事件及回执、3 个报告版本；旧版无整改，重检缺现时案件Grant时回执仍4，失权和错版后计数不增；后续停用见P03行 | PASS_C（其余组合待测） |
| 程序 P03 历史规则停用 C | 已产生报告保留确切规则版，停用后新校验拒绝 | `ProcedureProductTest` 2026-09-29 专项 1/1、零跳过：ACTIVE v2 停用后读为 DISABLED；既有 REPORT-1 v1 仍指向规则 v2；新 REPORT-2 拒绝且报告仍3条、回执/事件各6条（仅停用命令增加一条），后续撤权重放也不增。H2 产品事务，无原件文件或官方状态写入 | PASS_C |
| 程序 I | 双独立连接重放一致、并发同键唯一、失败回滚无半回执 | `ProcedureDm8Test` 1/1，在执法专用 `127.0.0.1:5257` 随机独立 DM8 schema 执行 V059/U059，双连接并发同键返回同一回执，最终2条回执/2事件（含首条），故障事务无规则记录，结束后清理schema；未使用咨询库 | PASS_I（双部署HTTP/物理恢复待测） |
| 程序登录 HTTPS | PKCE 登录、未认证 401、无 Grant 403、有发布 Grant 但不完整候选仍拒绝、未知中断、撤权后旧键失败；项目范围已核方法可由独立复核人发布并逐项校验，整改重检同键回执 | `ProcedureHttpsTest` 1/1：随机TLS/HTTP；D候选试算`UNDETERMINED`、发布409、撤权旧键403且计数不增；另用`procedure-v2-acceptance-v1`项目事件/日历和本次V2 MATERIAL原件输入，由独立具名复核Grant发布`PROJECT_TEST`规则，校验逾期`FAIL`及9/17轨迹；整改/重检各同键重放，旧v1无整改、新v3前驱v2；错程序`NOT_APPLICABLE`、缺事件/材料`UNDETERMINED`且均无PASS。DB最终8回执/8事件/5报告版本，无官方状态写入，非现实法定期限 | PASS_HTTP（双部署/恢复待测） |
| 程序 P06 原件引用失效关闭 C/HTTPS | 只有已核V2原件与现时来源/Grant可进入校验；旧报告读取遇原件缺失不吐正文 | `FileProcedureSourceTest` 先红2例失败1例，修复后2/2通过；`ProcedureHttpsTest` 2026-09-29 1/1零跳过：项目MATERIAL通过真实产品服务UPLOAD→具名收集→异人VERIFY形成v3、独立来源水位READY、精确EVIDENCE READ/ANALYZE Grant；临时移走该私有`.blob`后旧报告GET 503，文件还原后GET 200，规则3版/报告5版/回执8/事件8不增。源manifest仅作验收事件/日历输入，原件来自本次V2产品链；未改冻结MATERIAL或官方状态 | PASS_C_HTTP（双部署/物理恢复待测） |
| 程序 P06 同版本来源漂移 C | 旧报告不能在同caseVersion材料Ref、事件或日历变化后以旧结论正常读出 | `ProcedureProductTest` 2026-09-29 专项1/1：产品报告v1后将同版本材料Ref替换，`readReport`重算确切规则/案件/日历逐项并拒绝；回执/事件各仍3，恢复原输入后可读。H2产品事务，无原件文件或官方状态写入；不替代物理恢复 | PASS_C |
| 行为 C01～C04 | 候选拒发布、已核输入边界和不足/人工版本、来源故障/规则停用；相似但未绑定事实不补要件，反证和抗辩不自动给等级 | `ConductEvaluatorTest,ConductProductTest,CaseFileConductScopeTest` 2026-09-29 专项14/14、零失败/错误/跳过；H2 Oracle模式事务及失败无报告/回执；未绑定事实仍MISSING，专利/假冒要件独立，冲突/抗辩保持INSUFFICIENT且无severityGrade。纯计算反例无DB/文件写入；产品事务反例断言计数不增 | PASS_C（C06双部署/恢复待测） |
| 行为 I | 独立 DM8 事务、失败无半回执 | `ConductDm8Test` 1/1，执法专用 5257 随机独立 schema，V060/U060及前置表应用/回滚，报告/回执/事件计数并清理 | PASS_I（双实例/恢复待测） |
| 行为登录 HTTPS | PKCE登录、401/403、候选不足、发布拒绝、撤权同键重放失败；锁定原件到辅助分析/具名退回/重跑 | `ConductHttpsTest` 2026-09-29 1/1零跳过：随机TLS端口、独立产品H2+独立授权H2；候选试跑`INSUFFICIENT`，有发布Grant仍拒未核依据，撤销EDIT后旧键403；V2 MATERIAL三份原件经Evidence锁定组织后分析，历史权利未知保持`INSUFFICIENT`且显式scope UNKNOWN/sourceRef/gap，具名RETURNED v2保留、重跑v3 `REVIEW_REQUIRED`；移走原件后读取503且行为域仍3报告/6回执/6事件，不写官方结论 | PASS_HTTP（C06双部署/恢复待测） |
| 两条工作面 | V2 API/PKCE 路由、加载/空/错误/403清屏和窄屏布局 | 前端专项9/9及生产构建通过。`-Pbundle-frontend`随机TLS产品服务上，Procedure Chromium具名登录、规则v2/报告v3与直调V2 API一致，报告READ撤权后同页403并清空，输出`ENF_PROCEDURE_BROWSER_OK`及`ENF_PROCEDURE_BROWSER_WITHDRAWAL_CLEARED`，DB仍3规则版/5报告版/8回执/8事件；Conduct Chromium具名登录、逐事实kind/Ref与直调一致，报告READ撤权后403清空，输出`ENF_CONDUCT_BROWSER_OK`及`ENF_CONDUCT_BROWSER_WITHDRAWAL_CLEARED`，行为DB仍3报告/6回执/6事件。截图`frontend/target/enforcement-{procedure,conduct}-v2-{desktop,mobile}.png`，桌面/390px无页面级横向溢出，长轨迹表内部滚动后复跑 | PASS_BROWSER（双部署/恢复待测） |

`node scripts/verify-all.mjs` 在需求基线 47 文件和 41 ledger 行及治理自测通过后，曾停于 `TESTS_FAILING:project`；2026-09-29再次运行时项目计划验证623任务通过，随后结构检查报行为HTTPS测试文件405行超过400行上限，行为线正在修整。不能声明全局通过，也不回改咨询或冻结模块。程序专项 Maven 从仓库根目录执行 `mvn -f backend/pom.xml -pl apps/platform-api -am test '-Dtest=ProcedureEvaluatorTest,ProcedureProductTest,ProcedureDm8Test,ProcedureHttpsTest' '-Dsurefire.failIfNoSpecifiedTests=false'`；DM8 测试通过独立容器进程环境注入连接口令，未把口令落盘。本记录没有声称物理共同恢复、双部署真实时序、全部 P/C 场景或最终开发验收已完成。

2026-09-29 程序新增定向 `ProcedureEvaluatorTest` 4/4 与 `ProcedureProductTest` 1/1 均退出0；其后 `verify-all` 一次退出0，一次受并行项目报告 `TEST_EVIDENCE_STALE:project` 截断。P06反查发现当前项目manifest仅列 `PROJECT-RECORD-A@1`，现有 `FileProcedureSource` 未逐Ref核V2原件及文件字节，缺原件失败关闭不能据此判通过；已安排冻结合同内回归与修复。当前会话没有 `ENF_DEV_DB_PASSWORD` / `ENF_DEV_AUTHORITY_PASSWORD`，5257/5258端口虽监听而Docker控制面无响应；未执行新的双部署/冷恢复，不将跳过记作I。正式法定依据、期限及业务B仍独立。

P06红灯实测：`mvn -q -f backend/pom.xml -pl apps/platform-api -am test '-Dtest=FileProcedureSourceTest' '-Dsurefire.failIfNoSpecifiedTests=false'` 执行2例、失败1例、零跳过；`missingReferencedOriginalCannotBeReportedAsPresent` 在缺少任何MATERIAL原件时预期拒绝，实际返回快照（`FileProcedureSourceTest:18`）。这是冻结合同内缺原件失败关闭的实现缺陷，当前不得判P06通过；后续修复及回归结果另记。

修复限于程序只读适配器：精确`id@version`、VERIFIED/机构/ANALYZE用途与期限、现时EVIDENCE Grant、UPLOAD/收集/异人核验链、来源独立水位及原件SHA逐项验证；报告读回也重核当前来源和日历。修复后`FileProcedureSourceTest` 2/2与真实PKCE TLS `ProcedureHttpsTest` 1/1通过，缺原件503及还原200均已实测。不能由此推断独立DM8冷恢复或双JVM时序已经执行。

P06后续验证夹具已编译：`ProcedureIndependentDm8HttpTest`拟在两个独立JVM、专用业务/权威DM8上由MATERIAL v3原件→第二JVM发布→第一JVM校验→第二JVM读报告并发同键/撤权，预期4回执/4事件/1报告/1原件；`ProcedureIndependentRestoreDm8Test`要求独立冷恢复DB+文件根并保持权威库未回灌，拟核报告200→缺原件503→复原200→撤权403且零增量。2026-09-29定向5例中3通过、2因`ENF_DEV_*`与独立恢复输入缺失而跳过，P06两项均`NOT_RUN`，编译不等实测。最新`node scripts/verify-all.mjs`退出0（47基线、41需求、623计划任务及结构/证据检查）；这不替代具名最终技术复核或业务B。

两线剩余 I 的环境责任方为执法专项设施维护方：提供可访问且与咨询隔离的业务/独立权威 DM8 地址与非输出凭据（`ENF_DEV_DB_URL/PASSWORD`、`ENF_DEV_AUTHORITY_URL/PASSWORD`），并恢复专用容器控制面以在静止点将产品DB和确切原件文件冷备到另一个DB/文件根。恢复者需分别给程序`ENF_PROCEDURE_RECOVERY_GATE/RESTORED_DB_URL/RESTORED_ROOT`、行为`ENF_CONDUCT_RECOVERY_GATE/RESTORED_DB_URL/RESTORED_ROOT`指向真实新实例，权威库不回灌；退出条件是两线双JVM和恢复专项零跳过、逐DB/文件/回执及禁止副作用对账通过。当前5257/5258端口监听，但本会话无上述凭据，Docker控制面返回500；不得重启或借用咨询环境、伪造冷恢复，故最终具名复核与开发验收均等待实际I。

2026-09-29设施复核：四项`ENF_DEV_*`地址/凭据与两个`RECOVERY_GATE`在当前执行环境均不存在，`com.docker.service`为Stopped。主计划新登记`INFRA-ENF-INDEPENDENT-I-01`为独立`WAITING_INFRA`设施依赖，两个IMP继续进行中而其最终I/验收受阻。行为恢复断言加固：checkpoint记录源文件根，恢复入口要求目标DB URL同时不同于业务DB和权威DB、目标文件根不同于源根；现有报告2、回执/事件各4、逐原件SHA、200/503/403和零新增断言不变。这是测试准备，尚未取得物理恢复执行证据。

加固后定向`mvn -q -f backend/pom.xml -pl apps/platform-api -am test '-Dtest=ConductIndependentDm8HttpTest,ConductIndependentRestoreDm8Test' '-Dsurefire.failIfNoSpecifiedTests=false'`退出0，但Surefire分别为`tests=1, skipped=1, failures=0, errors=0`；只证明夹具编译/发现，不是I通过。后置`node scripts/verify-all.mjs`退出0（47基线、41需求、624计划任务，结构107项软警告）；两个IMP未做最终具名复核，仍不得完成或进入后继。

## 2026-09-29 两 IMP 依赖复核与设施前置

| 分类 | 当前可核范围与完成条件 | 本轮结果 / 后续状态 |
| --- | --- | --- |
| 当前环境可执行 | P/C 冻结反例、同键异载荷冲突/重放不增回执、失败关闭、授权撤回、精确原件、设施配置拒错与恢复 DB/文件/回执断言；只核现有后继的依赖，不启动其 D/IMP | 定向 `EnforcementIndependentIPrerequisitesTest` 2/2、`ProcedureProductTest` 1/1、`ConductProductTest` 7/7、`ConductEvaluatorTest` 6/6、`CaseFileConductScopeTest` 1/1，合计17/17、零失败/错误/跳过，Maven退出0。源/目标 DB 同端点（含 URL 参数伪隔离）、非 DM8、目标与权威同端点、文件根双向嵌套/不存在均有拒绝断言；这些是假配置预检，不是物理隔离证明。行为恢复夹具现在逐原件比较 checkpoint、DB SHA 与恢复文件字节 SHA，并在回执重放与缺原件503后核事件不增长；夹具本身未在冷恢复环境执行。现有产品 C 同键异载荷冲突、故障与撤权断言通过；合同无独立业务租约语义，不新增租约产品状态或验收条件。后继 `NEXT-V2-D-ENF-JOB-01` 仍阻塞，本轮仅反查依赖，不启动。 |
| 必须等待双独立 DM8 | 执法专用业务与权威 DM8 不同物理端点、具名安全凭据、两个独立 JVM 的产品 HTTPS 时序/并发、重放/冲突/撤权与 DB/回执/事件/原件禁止副作用 | `WAITING_INFRA`；四项 `ENF_DEV_DB_URL/PASSWORD`、`ENF_DEV_AUTHORITY_URL/PASSWORD` 当前执行环境均未注入。程序/行为双部署 I 均 `NOT_RUN`；曾发现为 skipped 的测试不计通过。端点字符串预检不能证明底层物理隔离，设施方须提供隔离事实并由执行者核对。 |
| 必须等待独立冷恢复 | 第三 DM8 目标、非重叠目标文件根、专用容器/冷备控制面，静止点共同复制产品 DB 与原件；权威库不回灌；恢复后 HTTPS、DB/文件 SHA、回执/事件、缺原件503和旧权限403对账 | `WAITING_INFRA`；两条 `RECOVERY_GATE` 与恢复 DB URL 未注入，目标文件根和可操作冷恢复环境未提供。程序/行为恢复 I 均 `NOT_RUN`；只有实际共同物理恢复零跳过后才能复核。 |

本轮前置 `node scripts/verify-all.mjs` 一次在并发写 `docs/project-plan.json` 时发生 UNKNOWN 文件访问错误；重试通过47基线/41台账自检并生成624任务，随后被其他项目 `TEST_EVIDENCE_STALE:project` 截断。此全仓失败不改无关模块，定向17/17与未运行的四项真实I分别记账。设施到位后先核端点、凭据、源/目标文件根和全新 gate，再分别执行双 JVM I 与共同冷恢复 I，逐项记录实际/预期、禁止副作用；两 IMP 各自具名技术复核后再单独判开发验收。当前仅真实双 DM8 I 与独立冷恢复 I 被设施阻塞，其余可执行工作继续推进。

主计划和本证据更新后再次执行 `node scripts/verify-all.mjs` 退出0：47基线、41需求、624计划任务及结构/证据门均通过；该结构结果不替代仍未运行的四项独立 I。

## 2026-09-29 真实 I 上线前最终门禁审计

本节仅审 `IMP-ENF-PROCEDURE-02` 的 P06 和 `IMP-ENF-CONDUCT-02` 的 C06。`READY` 表示准备条件已经核实，不表示真实 I 通过。设施方为执法专项设施维护方；执行与具名技术复核人为 Codex（在真实执行后逐项签出），业务 B 另行管理。两条 IMP 均仍进行中，四项真实 I 均 `NOT_RUN`。

| 门禁项 | 双独立部署 HTTPS I（程序 / 行为） | 独立 DB+原件冷恢复 I（程序 / 行为） |
| --- | --- | --- |
| 前置环境 | `WAITING_INFRA`：专用业务与独立权威 DM8 的可访问地址、SYSDBA 级测试建/删随机 schema 凭据未注入；Docker 29.7.2 控制面当前可响应为 `READY`，但不是双库可用证明 | `WAITING_INFRA`：还需第三物理 DM8 目标、独立文件根、可控静止点的 DB+文件共同冷备/回灌及独立权威不回灌的设施证明 |
| DB/文件隔离 | `READY`：测试用随机独立 schema、私有 `@TempDir` 原件根；入口拒 H2、业务与权威同 host:port（含参数伪隔离）。`WAITING_INFRA`：设施方核物理实例/卷标识及不借咨询库；不同端口本身不足以证明物理独立 | `READY`：恢复测试拒目标与业务/权威同 host:port、文件根相同或双向嵌套（`toRealPath`）；`WAITING_INFRA`：设施方实际第三实例、目标根、卷与源分离证明，静止点文件 SHA 与 DB 快照对应 |
| 配置与保密 | `WAITING_INFRA`：安全注入 `ENF_DEV_DB_URL/PASSWORD`、`ENF_DEV_AUTHORITY_URL/PASSWORD`；不得在命令行/聊天/证据中输出密码。`READY`：PKCE、TLS、产品 jar 第二 JVM 与随机端口夹具存在 | `WAITING_INFRA`：每线 `ENF_{PROCEDURE,CONDUCT}_RECOVERY_GATE/RESTORED_DB_URL/RESTORED_ROOT` 均缺。gate 目录须独立、具访问控制且本轮全新；checkpoint 含随机 schema 密码，行为还含短时 token，不得纳入公开日志/提交，演练后按设施保密流程销毁 |
| 测试数据 | `READY`：程序项目日历/事件 manifest、MATERIAL 服务生成并核验的 v3 原件；行为三份项目原创原件经 MATERIAL/Evidence 产品服务建立并锁定，项目规则试跑。种入阶段使用测试侧无授权守卫的领域服务调用，**不是**登录 HTTP 来源全链执行证据；真实 HTTP 部分仅指两个 JVM 的发布/校验/报告读取或人工复核及撤权 | `READY`：同轮生产者的 `enf3` 原件与 `enf6`/`enf7` 产品 DB、确切文件 key/SHA、回执/事件计数写入 checkpoint；程序还需 `acceptance.json`，行为还需 `case.json` 随原件共同复制。`WAITING_INFRA`：尚无真实源/目标快照 |
| 启停与健康 | `READY`：测试创建随机 schema、启动测试 JVM 和产品 jar 独立 JVM，第二 JVM `/login` 200 才继续；`finally` 停第二进程、逆迁移/删测试 schema。失败或中断需设施方核残留随机 schema/进程，不可误删共享实例 | `READY`：生产者写全新 checkpoint 后等待，设施方在静止点冷备并恢复到第三 DB/文件根；恢复消费者先核 DB/文件再启动独立 TLS 产品实例。程序消费者写 `.restore-ready`→生产者撤权写 `.withdrawn`→消费者写 `.verified`；行为须设施方在物理复制完毕后写 `.resume`，消费者 `.restore-ready` 后生产者撤权、消费者 `.verified`，生产者现在也等待完成。`WAITING_INFRA`：设施方必须提供可执行的具体卷/DB 冷备步骤和隔离目标 |
| 执行命令与零跳过 | `READY`：在仓库根先 `mvn -q -f backend/pom.xml -pl apps/platform-api -am package '-DskipTests'` 生成当前产品 jar（这是打包，不计 I）；注入双库后分别运行 `mvn -q -f backend/pom.xml -pl apps/platform-api -am test '-Dtest=ProcedureIndependentDm8HttpTest' '-Dsurefire.failIfNoSpecifiedTests=false'` 和同式 `ConductIndependentDm8HttpTest`。退出 0 **且**对应 Surefire `tests=1, skipped=0, failures=0, errors=0`、产品标记日志及 DB 对账同时成立才判通过 | `WAITING_INFRA`：设全新 gate/第三 DB/目标根后，分别在独立执行会话启动对应 `*IndependentDm8HttpTest` 生产者，待 checkpoint 出现并静止复制，启动同式 `ProcedureIndependentRestoreDm8Test` 或 `ConductIndependentRestoreDm8Test` 消费者；行为复制完成后由设施方创建 `.resume`。两会话均退出0、两个 Surefire 各 `tests=1, skipped=0` 和 `.verified` 才判该线共同恢复通过。不得单进程顺序运行生产者再消费者而死等；不得仅凭 Maven 退出0或 marker 判通过 |
| 预期与对账 | `READY`：程序同键并发 200/200 但草稿1、发布/校验后报告1、回执/事件各4；撤精确 Grant 后双 JVM 403，零新增。行为两 JVM 读200/200、同键人工复核200/200但报告2、回执/事件各4；缺原件双503、撤权双403且计数不增。各场景核原件文件仍在、确切 Ref/SHA | `READY`：程序恢复报告200→移原件503→还原200→当前权威撤权403，报告1、回执/事件各4及原件 SHA 不变；行为报告200、同键复核200但回执/事件各4→缺原件503→撤权 GET/旧 POST 403，报告2、回执/事件各4，逐原件 checkpoint/DB SHA/文件 SHA 相等。`WAITING_INFRA`：仅实际备份外权威及目标库/根实测可判此结果 |
| 重复/失败恢复 | `READY`：预检 3/3 拒旧 checkpoint、`.resume/.restore-ready/.withdrawn/.verified` 任一残留，拒同库/非 DM8/嵌套根；超时/坏 SHA/缺文件/失效授权应失败且不能写 `.verified`。`WAITING_INFRA`：真实重复恢复须新 gate 与独立目标重跑；故意失败的物理回灌及其日志尚未运行，不可称通过。冻结合同没有额外独立破坏性恢复验收项，已定义失败关闭的现有断言仍须实际执行 | 同左；不同 URL/路径只是必要条件，物理独立及快照一致性由设施记录加产品对账共同证明 |
| 假通过/隐藏跳过 | `READY`：四项真实 I 仅各有入口 `Assumptions.assumeTrue`；缺环境时每项 `skipped=1`，Maven 退出0也必须判 `NOT_RUN`。双部署测试可不设 recovery gate 而仅证明双部署，绝不计共同恢复 | `READY`：消费者入口同样会 skip；旧 gate 已拒重放，行为生产者现等待 `.verified`。无 mock/H2 作为双库或恢复替代；种入用无守卫领域服务与项目输入仅是测试数据准备，不能冒充登录来源全链或法律事实 |
| 证据产物/具名复核 | `WAITING_INFRA`：保留设施实例/卷隔离确认、命令退出码、两个 Surefire XML、两 JVM HTTPS 状态/产品标记、脱敏第二 JVM 日志、源 DB 回执/事件/报告及文件 SHA 前后表。Codex 核 P06/C06 的确切规则/Ref、同键唯一、撤权旧回执与零写，分别签结 | `WAITING_INFRA`：保留静止点、实际备份/恢复操作记录和目标实例/卷身份、DB/文件 SHA 清单、checkpoint 的非敏感摘要、两会话退出码/XML、HTTP 200/503/403、恢复源/目标/权威水位及回执/事件前后表、失败时禁止副作用。Codex 分别复核程序/行为的恢复版本、权威未回灌、重复/失败边界，再单独判两 IMP；不得保存 checkpoint 密码/token 到证据 |

本轮实测：`EnforcementIndependentIPrerequisitesTest` 3/3、`ProcedureProductTest` 1/1、`ConductProductTest` 7/7、`ConductEvaluatorTest` 6/6、`CaseFileConductScopeTest` 1/1，合计18/18零跳过；四个 `*Independent*Test` 各 1 例、各 `skipped=1`，均 `NOT_RUN`。当前产品 jar、`keytool`、项目输入存在，`mvn ... package '-DskipTests'` 退出0只证明打包；Docker `info` 返回29.7.2。四项双库地址/密码、两线 gate/第三 DB URL/文件根均未注入。设施之外无已确认的产品业务代码阻断（`BLOCKED`=无）；行为种入的无守卫边界已显式限定，不能宣称其种入阶段也通过登录 HTTP。未修改 ENF-01～07、咨询或后继任务。

本节落盘后的 `node scripts/verify-all.mjs` 退出0，47基线、41需求、624任务及结构/证据门通过；仅证明当前准备和台账一致，不改变四项 I 的 `NOT_RUN`。

2026-09-29设施自主复查：当前进程的四项 `ENF_DEV_*` 地址/凭据及两线 `RECOVERY_GATE/RESTORED_DB_URL/RESTORED_ROOT` 均未注入；因此 DB 连接、第三目标物理身份、文件根访问、静止点共同回灌和新 gate 不能实测，分别为 `WAITING_INFRA`，四项真实 I 为 `NOT_RUN`。Docker `info` 实测服务端29.7.2可响应，控制面存在为 `READY`，但尚未证明执法专用卷和冷备权限。没有新业务代码 `BLOCKED`、没有 I 的 `PASSED/FAILED`。设施方自行准备，不需在聊天提供密码；现有 `v2-i` 自动复查按15分钟周期执行，未变化静默，全部真实连接/隔离/恢复条件齐备时先执行双部署，再按两线 checkpoint/gate 协议执行独立冷恢复，零跳过对账后 Codex 分别具名复核与判验收。该周期检查不能宣称瞬时事件触发；两 IMP 保持进行中，不启动后继。

## 2026-09-29 自主设施落地与真实 I 执行（覆盖上节时点状态）

上节 `WAITING_INFRA/NOT_RUN` 仅为当时快照。本地镜像 `cnxc/dm8:20251114-kylin` 新建执法专用业务 `codex-enf-i-business` 127.0.0.1:5311、独立权威 `codex-enf-i-authority` :5312，以及程序恢复目标 `codex-enf-i-procedure-restore-2` :5314、行为恢复目标 `codex-enf-i-conduct-restore-2` :5316。四容器各有独立同名 `-data` 卷；本轮 `docker inspect` 核四个不同容器 ID/卷且均运行。未借咨询或旧5257/5258。随机密码仅从本地容器环境注入测试进程，不打印、提交或写入证据。Docker控制面、专用DM8连接、产品jar/TLS、项目输入、两套恢复DB/文件根、新gate、静止点卷复制和源/目标隔离均 `READY`；外部 `WAITING_INFRA` 无，设施 `BLOCKED` 无。

| 实测 | 执行与预期 | 实际结果及禁止副作用 |
| --- | --- | --- |
| 程序双JVM HTTPS | 在专用5311/5312注入进程环境，运行 `mvn -q -f backend/pom.xml -pl apps/platform-api -am test '-Dtest=ProcedureIndependentDm8HttpTest' '-Dsurefire.failIfNoSpecifiedTests=false'`；重放唯一、撤权旧键失败 | Maven退出0，到达 `ENF_PROCEDURE_DUAL_DM8_HTTP replay=200/200 receipts=4 events=4 revoked=403/403 original=verified`；同键与撤权不增回执/事件，原件仍在。 |
| 行为双JVM HTTPS | 同式 `'-Dtest=ConductIndependentDm8HttpTest'`；两实例读/复核及缺件/撤权失败关闭 | Maven退出0，到达 `read=200/200 review=200/200 missingOriginal=503/503 revoked=403/403 reports=2 receipts=4 events=4`；失败后计数不增。 |
| 程序共同物理恢复 | 全新 `codex-enf-procedure-i-20260929-02` gate的checkpoint后停止5311，用只读源卷 `cp -a` 至全新5314卷；确切 `enf3` 原件和 `acceptance.json` 复制到独立根，5312权威不回灌；再启动源/目标并运行 `ProcedureIndependentRestoreDm8Test` | 生产者/消费者退出0；`.restore-ready/.withdrawn/.verified` 均在；`report=200 missingOriginal=503 restored=200 staleBackupAuthority=503 receipts=4 events=4 originals=1`。只读目标DB报告1、回执/事件各4；恢复原件SHA `13232E21158563F603515832514B58FA9F7A17CC0B4E19996F816D596889D6CC` 与DB相等，失败后不增计数。 |
| 行为共同物理恢复 | 全新 `codex-enf-conduct-i-20260929-02` gate；停止5311、只读源卷复制到全新5316卷，三份原件及 `case.json` 复制到独立根，5312不回灌；复制后 `.resume`，并行运行 `ConductIndependentRestoreDm8Test` | 生产者/消费者退出0；`.resume/.restore-ready/.withdrawn/.verified` 均在；`reports=2 receipts=4 events=4 originals=3 replay=200 missingOriginal=503 staleBackupAuthority=503`。只读目标DB报告2、回执/事件各4、材料3；三原件SHA分别 `EB4379058974551BD3318C5ABBFDA277EA83CF4EDB74A11EB7C4E85AE8D420E9`、`E6EA3D535970CFF67C9C10247444561E6AA7526008BEB9E97EF1DA24EABDFB5A`、`1E4711D03DC5785001B290E23F02903472E2B2B9AB3AC08C8003E0D7BAD2E571`，与checkpoint/DB/恢复文件一致；旧POST 503空正文，不增回执/事件。 |

失败轮如实保留：程序首次恢复在独立权威撤权后返回503而非原测试预期403；反查 `ProductGrantWatermark` 发现备份历史摘要与备份外水位不一致，符合冻结失败关闭，修正测试断言后新gate重跑。程序另一次在DM8启动窗口连接失败，健康后重试；一次并行构建覆盖共享jar导致生产者 `NoClassDefFoundError`，未到checkpoint，重试成功。行为首次跨短时token窗口返回401，仅修测试夹具为恢复后重新PKCE登录，新gate重跑成功。未改产品业务代码；失败轮不计通过，失败目标卷未复用。

证据限制（上一成功轮）：当时Maven退出0、到达断言后产品标记，独立DB/文件只读对账及 `.verified` 保留；随后共享 `target/surefire-reports` 被并行无环境运行覆盖为 `skipped=1`，不得引用为该轮零跳过报告。以下全新独立轮已补足此缺口，旧checkpoint未复用。

### 独立重取零跳过报告与具名复核

2026-09-29 Codex在本机执法专用5311业务/5312权威上分别重跑 `ProcedureIndependentDm8HttpTest` 与 `ConductIndependentDm8HttpTest`，Maven各退出0；两轮到达 `ENF_PROCEDURE_DUAL_DM8_HTTP replay=200/200 receipts=4 events=4 revoked=403/403 original=verified` 和 `ENF_CONDUCT_DUAL_DM8_HTTP ... read=200/200 review=200/200 missingOriginal=503/503 revoked=403/403 reports=2 receipts=4 events=4`。并发同键/撤权/缺件后DB回执与事件不增长。新轮生产者分别使用 `%TEMP%/codex-enf-procedure-i-20260929-03/checkpoint`、`%TEMP%/codex-enf-conduct-i-20260929-03/checkpoint`；在checkpoint后停止5311业务DB，分别用只读源卷 `cp -a` 至全新 `codex-enf-i-procedure-restore-3-data` / `codex-enf-i-conduct-restore-3-data`，并在停机期间复制精确原件及 `acceptance.json` / `case.json` 到独立文件根。独立权威5312从未停止或回灌。恢复目标新容器5317/5318与源/权威各有不同卷，启动源/目标后分别运行 `ProcedureIndependentRestoreDm8Test`、`ConductIndependentRestoreDm8Test` 消费者；程序和行为生产者/消费者均退出0，两gate均有 `.restore-ready/.withdrawn/.verified`，行为另有 `.resume`。一次人工读取Java Properties的路径转义导致文件复制准备未开始；纠正路径后仍在同一停机静止点复制，未用错误路径进入消费者，不计测试失败。

新轮恢复断言：程序报告200→缺原件503→还原200→备份外权威撤权后503空正文，DB报告1、回执/事件各4；原件SHA `13232E21158563F603515832514B58FA9F7A17CC0B4E19996F816D596889D6CC` 与DB相等。行为报告2、回执/事件各4、材料3及三原件逐项checkpoint/DB/文件SHA相等（上一表三值本轮再核相同），同键复核200、缺件503、备份外权威撤权后GET/旧POST 503空正文，报告/回执/事件不增。两个恢复测试在写 `.verified` 之前核上述HTTP、DB、文件和历史摘要/独立水位不匹配；这不作真实法定效力或业务B声明。

六份原始Surefire XML已从共享输出即时复制到 `docs/evidence/enforcement-independent-i-20260929/`：`procedure-dual.xml`、`procedure-producer.xml`、`procedure-restore.xml`、`conduct-dual.xml`、`conduct-producer.xml`、`conduct-restore.xml`。逐份以XML解析核 `tests=1, skipped=0, failures=0, errors=0`，各对应Maven退出0；SHA-256依次为 `165AAC8DABDD12CB279DC43724471E38E4D4686A203BD8683C6A4C5F30D1227D`、`BF4833ACD1E434E45DD38E1EA36E5AB49064E5FACF6E1CC50670FA069B92707C`、`1BBD63B751B0C6522FA15946F1C1264FE85FB5EDF8A9BD9EADFDE0DA0F45FE45`、`3FAD6D4805DA383BAEDE29F1B02E9F39C75F6D202B884E2658D9A8687C0EC081`、`039F24FC55D1FC59BEBC74C22819FC49C41AA29E1F85AFC88190E5267E34D866`、`4266A699386CF82C66A5A5253463F860B9493623F37D39FAA9D2C5CCB1837860`。复制前检查XML不含两容器SYSDBA密码、checkpoint随机测试密码或常见凭据字段；checkpoint本身含凭据，留在本机受控TEMP，不提交。旧共享XML/skipped不用于本轮判定。

具名技术复核：Codex，2026-09-29。逐项反查 `docs/57` 第6.26/6.27节的P01～P06/C01～C06及本文件既有C/HTTPS/Chromium记录；本轮独立双JVM与物理DB+原件恢复只补P06/C06，不新增合同条件。源码核双JVM测试入口假设在缺设施时会skip，但本轮六份XML均实际运行零skip；报告标记位于断言之后。程序缺起算/日历拒发布、轨迹/中断四态、错程序/历史版、故障503、整改新版本及撤权恢复；行为历史权利未知/逐行为不串用、反证抗辩不足、不出等级、人工版保留及撤权恢复，均由既有C/HTTP/浏览器与本轮I合并覆盖。源/目标不同容器和卷、原件SHA与恢复DB、回执/事件及失败后的禁止写入均已核；授权历史摘要与备份外水位不一致时产品失败关闭，不用403替代503，也不生成成功旧回执。未发现未覆盖冻结项、MUST_NOT违规或新增无来源业务行为。分别判 `IMP-ENF-PROCEDURE-02`、`IMP-ENF-CONDUCT-02` 冻结范围最终开发验收通过，实际法律效力、真实岗位及业务B仍独立；ENF-01～07和后继均未修改/启动。执行编号、XML写入时间、测试类、gate、独立卷和摘要的反覆盖绑定见 `docs/evidence/enforcement-independent-i-20260929/FINAL-RUN-MANIFEST.md`。
