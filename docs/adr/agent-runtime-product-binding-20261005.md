# ADR-AGENT-BINDING-02：持久化与三域增量接线的限定技术设计

日期：2026-10-05。来源：本轮附件指令（用户授权技术重构、并行增量推进，不替代正式权限/法律批准）、docs/01/09/54、既有三域正式API/服务。状态唯一见docs/08。任务：ARC-AGENT-VERTICAL-02，IMP-AGENT-RUNTIME-KERNEL-01，IMP-AGENT-MODEL-GATEWAY-01及三域接入任务。

## 1. 最小依赖与R/D范围

Phase是推荐顺序，不再以Runtime全部尾项或另一业务Agent完成作为前置。三域只依赖内核已交付机械协议和自己的既有能力；不能复用其他Agent内部实现。当前R是用户批准的技术接线及各域已获准的业务能力合同；当前D只覆盖下述持久化、共享模型传输和受控既有能力编排，不代签新增法律规则/材料用途/专业验收。

## 2. 生产Checkpoint数据服务端口

V096/U096：共享run head和不可变revision。Head精确绑定agentId/taskId/goalId/goalVersion/scope和父输入截止时间；版本CAS；revision保存加密checkpoint+audit事件并计算SHA。AES-256-GCM，随机nonce和绑定run/agent/task/version的AAD，密钥只从正式配置获得；不给默认密钥。JSON大小有限，不存JWT、API密钥、权限快照或第三方原始材料正文。Goal只保存业务输入引用和机器完成合同，输入/源仍由原领域保管。

生产适配器通过CurrentAccess回调在读、事务开始和提交前核查现时Task/Goal/Input/权限/保留；通过ExecutionFence回调核查调用方当前围栏。不会建立通用身份或自行签发权限。工具的业务写入继续使用原命令幂等和领域围栏；Checkpoint并发CAS不替代业务命令围栏，也不宣称跨系统exactly-once。故障原件保留，load校验SHA/密文/AAD/绑定；恢复不能跨Agent/Task借用。保留期限由当前父任务/输入/来源已批准截止取最早，不自创无限保留；适配器过期拒绝读取并支持按现有截止清除。

## 3. 共享模型入口

共享ModelGateway端口位于kernel，供应商HTTP在model adapter内；域持有Prompt/用途/输出校验。共享传输接收显式provider/model/endpoint/protocol/timeout/输入输出预算/批准版本和目的；不自行放大审批、切换模型或重试不确定外呼。保持原exactRequest字节、finish_reason、模型一致性、usage、输出上限和中断语义。咨询受管配置优先复用；裁决从已批准MaterialModelPolicy取得精确模型，通过显式配置endpoint与批准绑定接入，不再由材料adapter写死模型名和URL。其他供应商/目的/模型仍需原批准，不把可配置当已批准。

## 4. 三域独立Agent

各模块独立Definition/Skills/Tools/Policy/Prompt/Evaluation版本和独立Runtime实例，不在kernel放业务流程。增量先复用当前真实检索/证据程序/材料报告服务；三个Agent仅调用自己领域端口。工具只提交候选/草稿或调用已有确定性规则，不调用授权写入、review/publish/正式处罚或裁决状态端口。机器完成依领域证据和缺口；须人工时NEED_HUMAN，不因为模型声明完成而SATISFIED。

可恢复工具错误进入Evaluate/Re-plan，错误引用保存机械分类，不记录密钥/异常正文。权限失效停止且不恢复旧授权，预算不足停止；未知提交状态必须用原幂等回执对账，不盲目重发模型请求。各域后续细化仍在同一主计划追踪，不用当前窄切片冒全部业务完成。

## 5. 验证与安全

H2测试适配器真SQL/事务/CAS/并发/加密/回滚/重启；真实DM8使用新隔离测试库或随机自有schema，不运行迁移到原业务库。已有缺实证前置的20错误需恢复原对账环境，随机空库不能冒替；不因此冻结新实现。共享模型测试使用受控本地HTTP/TLS供应商，明确不等于供应商/专业效果验收。三域接线逐条运行实际服务与持久化并保存恢复/权限/重规划/失败原件，未穿透的范围不标DONE。


## 共享工作台限定增量技术 D（2026-10-05）

本切片仅消费已实现的三个领域 `agent-runs` 合同，不新增身份、正式决定或跨领域调用。共享 React 组件接收领域授权请求端口，展示服务端 Goal/Phase/Plan/Cursor/Pending/Observation/Event/Outcome，不预设成功进度。裁决从当前案件版本显式启动不使用模型的材料组织/候选/缺口/辅助报告链；模型选择仍由现有批准材料合同控制，不自动发送材料。咨询必须已有确切 confirmed Goal；执法启动必须已有完整原生 Input 与程序检查合同，缺合同不在前端猜测规则。其他分支可以先用确切 Run ID 读取和继续已授权运行。

执行步骤为显式单次推进，不以静态动画/自动成功替代后台运行。失权清除正文；网络不确定保留同一创建键且禁止未经对账换键提交；推进网络不确定先读取 Run。切换任务/身份丢弃旧响应。停止后的继续按钮禁用，人工复核沿各领域原生业务入口，不在 Runtime 中伪造审批。历史列表/全文证据解析/自动执行与完整三域 E2E 为后续退出条件，不在此设计切片声明实现。


## 当前装配与部署前置

三个独立产品入口均显式导入共享 `AgentRuntimeConfiguration`/`AgentRunPersistence`，不要求扫描其他业务Agent。默认 `AGENT_RUNTIME_ENABLED=false`；启用必须显式配置32字节Base64 `AGENT_RUNTIME_CHECKPOINT_KEY`，不得用测试密钥/历史默认身份。需要先通过现有受控迁移入口应用V096（U096为回滚原件），本轮未向现存业务schema运行迁移。独立数据库权限应限定于两个Runtime表及各领域已有授权端口；密钥保管/备份/轮换沿部署系统，不写入报告或Checkpoint。

咨询额外要求既有identity、source engine、model、workspace四项开关和真实mTLS Worker/维护配置；缺任一项不能装配产品Agent Controller。裁决额外要求`adjudication.v2.enabled`及原CaseSourceAuthority/Grant水位；执法额外要求`enforcement.v2.enabled`及原材料/规则/模板/程序配置。三域端点均处于原JWT安全匹配路径内；各Controller/Tool/SQL事务重核当前权限。Runtime开关不授予任何领域权限。

共享HTTP Gateway已经接入回答、检索对话、材料整理、专利候选分析/特征提取和意图提取；健康探针与完整受管路由/成本汇总/可用性审计仍需独立核查。保留历史HTTP状态可追溯的结构化Failure，不泄露供应商响应或凭证。裁决供应商和模型批准限制不放宽。

复验入口：`scripts/verify-agent-runtime.ps1`。`-Dm8`仅使用明确注入的两个独立DM8实例，Checkpoint与CaseApi测试创建/清除随机自有schema。脚本保留前后verify-all红灯及全量历史错误，专项通过不能覆盖全量失败。原始XML、日志和结果留存；不冒法律正确性、真实案件效果或真人采用。


## 固定时钟回归限定技术D（2026-10-05）

恢复环境的全量真实暴露既有H2工程夹具混用固定Clock和系统Instant：Comparison准备/输入测试固定2026-10-05T12:00Z，但共用admit用壁钟填写collected_at，21:00北京时间后越过其固定valid_until。数据约束正确，不改数据库/生产授权或许可。仅新增测试工具显式Clock重载，让固定Clock夹具的Task/Grant/Source共用同一时间；其他调用保留原壁钟语义。旧冻结业务/专业规则不重开，旧失败、源SHA和测试源原件保存。新回归任务仅证明42个既有测试不依赖当前日期；不冒来源用途或真人验收。

## 咨询正式入口传输准入补齐限定技术D（2026-10-06）

同一现行正式Product Identity传输合同继续适用于咨询Agent Run、检索工作台、检索执行与模型对话。安全链已将这些端点置于JWT认证下，但ProductIdentityTransportFilter路径名单缺少它们，不能由参数注入HTTP测试证明HTTPS约束成立。本增量只补齐上述现有路由族的传输匹配；使用既有Task根路径及完整子树覆盖所有HTTP方法，避免后续Task端点再次漏列，不改变其他域或未知端点的认证/路由准入：不安全传输或编码/反斜线URI拒绝403，独立授权水位不可用拒绝503；HTTPS仍经现有JWT签名、固定issuer/audience、现时session/subject/key及Task Grant校验。不得把传输过滤当作授权、把JWT夹具当正式发行/真实mTLS Worker或供应商证明；被拒绝请求不得进入Controller或产生Run/模型调用。保留旧端点与三独立Agent边界，不新增产品状态、迁移或权限。验证分为过滤器逐路由反例和真实安全链/签名验证器工程测试；测试密钥、H2及边界替身均明确标记，不替代真实部署或业务Golden。

## 正式咨询资源发布限定技术D（2026-10-06）

正式咨询部署现有ProductConsultationWorkspacePageController转发/app/consultation.html，复用当前frontend/consultation.html和ConsultationProductPage（含已有AgentTaskPanel），不新增产品页面/路由/字段/身份。通用bundle-frontend只生成index.html，不可冒正式咨询入口；历史v750封存覆盖发布也不可冒当前Agent源码。增加显式bundle-consultation构建，编译当前仓库与既有咨询独立Vite入口，使用独立API类输出目录及JAR名。拒绝同时选择通用工作台和咨询产品打包；包内咨询资源仅从本次独立dist-consultation完整输出取字节，不从旧target/classes静态资源或旧JAR补齐。

独立前端输出固定于frontend/dist-consultation并完整重建；资源暂存仅可清理本工作区backend/apps/platform-api/target/consultation-product-classes/static/app，删除前校验绝对路径、真实祖先和无符号链接，禁止路径逃逸或清理共享target/classes。输入资源禁止链接、通用index及其他HTML入口，验证consultation入口引用的JS/CSS确实存在并记录逐文件SHA；暂存后重新校验字节。校验失败不得生成通过/发布声明。JAR实际内容和映射验证是工程证据，不证明供应商、OAuth发行、真实TLS/Worker、合法来源、专业Golden或生产运维验收。构建不写DM8、不签发或延长Grant，不推断Runtime配置/密钥及V096/V097已部署；这些依旧是下一实际部署准入。

正式部署准入只读核验：仅按本机受管container-deployment.json的原DB名称、完整ID、loopback5301/5302与既有schema凭证启动原已停止数据库用于SELECT元数据；不启动历史API/Worker、不执行迁移/DDL/DML、不续期Task/Source/Grant/Session，不校正物理数据或绕过供应商原件缺口。报告现时数据库时间、schema表存在、未过期Task/已确认Goal/现时Grant/Worker/来源MODEL用途、原独立水位状态与历史D适用边界；不得将READONLY设置当已证明SQL无副作用的替代。仅在本轮确切启动时收尾恢复原停止状态，已有运行服务不停止。若无当前有效授权/来源或迁移未实施，门禁真实执行分支，继续不依赖这些授权的发布工程和其他获准验证。


## 派生看板长标识符显示限定技术D（2026-10-06）

咨询只读准入原件的真实表名/来源/完整阻塞说明进入主计划后，1440px看板实际document.scrollWidth=1488，定位summary-card/current-blocker中未断行长Token；保持所有原文字、任务ID和阻塞内容，summary-card采用overflow-wrap:anywhere，既有min-width:0不变。不通过overflow:hidden、删内容、缩短完整ID或放宽布局检查处理问题，不增加业务页面/身份/权限状态。仅修改生成器，不手改派生HTML。既有真实1440/390浏览器检查继续，另对超长来源SHA渲染做DOM临时输入的无截断/换行/宽度反例（只为看板布局测试，不是业务输入或任务证明）。原失败、诊断宽度和截图留存，修改后再次生成JSON/HTML并复验；报告工具源变更不沿用输入过期的全量证据。


## 三域任务结果交付限定技术D（2026-10-06）

来源为本轮用户明确三域并行交付指令及既有ARC-AGENT-VERTICAL-02/WORKBENCH-D-02，不代签来源、业务规则或授权。主计划只由主会话更新；领域实现子智能体只能修改分配文件与各自证据。所有分支先核原受管数据库现时准入，仅SELECT；核精确容器ID/端口/本轮前运行状态，不启动旧API/Worker，不执行DDL/DML/续期/签发，不输出密钥或token，收尾恢复只由本轮启动的原停止数据库。工程可修/已批准部署可执行/外部决定分列，不由一个域的过期结论外推其他域。

执法UI限定消费当前DOCUMENT_DRAFT可消费manifest与既有Procedure.CheckCommand(id,caseRef,caseVersion,ruleId,ruleVersion,calendarRef,calendarVersion)。document字段只能取当前选中manifest，caseRef/version从manifest确切派生并只读；程序检查标识/已批准规则和日历坐标由操作者显式给出，必填不提供虚构默认，不从文书规则推导程序规则。仍由后端现时PREPARE权限、程序Source及绑定合同决定能否执行；换组织/输入/身份清空选择与旧结果，不自动提交审核。缺有效程序坐标时禁止启动而非猜测。

裁决UI仅将服务端实际Run观测/Outcome中的case-report引用与当前案件服务端报告revision/work版本/SHA关联，匹配后定位已有报告编辑/复核区；陈旧、跨案或失权不显示旧报告，不自动采信/提交/裁决。原模型材料默认不发送，专业状态缺口不得生成推定结果。本切片不添加模型自动推理规则。

正式请求内连续执行：新增三个既有agent-runs/{runId}/execute子路由，沿原JWT、HTTPS、Grant和领域围栏。共享执行器在读取、每次advance和最终结果投影前使用正式JwtDecoder重新验证同一请求JWT（签名、issuer/audience、key/session/subject现时状态），并重新进入领域context核Task/Goal/Input/Source保留与权限。仅复用现有advance的Checkpoint CAS、pending Invocation ID与调用幂等；不改变领域计划、完成合同或人工门，不保存Bearer凭据，不产生脱离请求的后台线程。请求内循环有机械上限512次转换，并检查中断；达到上限仅返回真实当前Checkpoint，不虚构成功或新业务状态。STOP立即结束，未知外呼沿现有围栏失败关闭，权限失效请求拒绝并不回传旧证据；重复/并发请求沿CAS及原域命令幂等控制，不宣称跨系统exactly-once。

工作台消费一条execute请求展示真实响应及现有恢复对账；不在浏览器循环advance。结果未确认先读当前Run后再决定继续，保留同一创建键与原输入。此设计是受授权调用的完整Run驱动，不是持久无人值守Worker；真正后台重启接续需要正式Worker/调度与授权准入，仍不在本切片冒完成。测试身份、供应商/源端口替身只证明工程合同，真实三域业务验收继续单列。


## 裁决原受管部署执行限定D（2026-10-06）

DEP-ADJ-AGENT-NATIVE-01复用§1/2、原裁决正式主体与原受管5301/5370部署，用户本轮明确B类部署立即执行。只在完整容器ID/loopback/ADJLLM20261003主schema核对、两Runtime表均不存在后执行原V096；任何已有表/部分迁移须拒绝覆盖，保留U096但不自动删除保留Run。前后记录原案件head/revision/Grant/预算及业务表行数和SHA，不写权威库、不扩业务数据或授权。

运行密钥为新随机32byte Base64，以当前Windows用户DPAPI SecureString置于既有受管私有vault，绝不采用测试默认/复用身份或模型密钥；禁止替换已有密钥。只将秘密放本次启动进程内正式配置，不进入命令行/源码/证据。保留期限沿已批准父输入/来源最早截止，不修改原许可截止。

发行当前源码回归过的独立ProductAdjudicationApplication及正式工作台；配置Runtime显式启用，材料模型显式关闭（现有生产功能开关，不是替身），无供应商外呼/Worker/自动复核/后台承诺。原启动脚本的provider依赖不得成为无模型请求分支的必要条件；本次受控启动配方原件与发行指针均保留旧版本，核完整JAR SHA和组件条目后切换。正常登录只沿已有正式主体、真实OAuth/PKCE/签名/Session，不新造主体或签发绕行JWT。若现时来源拒绝案件/任务，记具体403及作用分支，不能调整源或删材料来掩盖。现有案件仅材料整理用途及缺项反例，不是专业完整任务Golden。


## 咨询原工作台结果交接限定技术D（2026-10-06）

任务FIX-CON-AGENT-RESULT-HANDOFF-01，复用已批准咨询Task/Goal/检索/Dialogue与正式工作台R/D，用户本轮明确咨询交付最高优先。已观察到ConsultationProductPage未消费AgentTaskPanel.onView：execute产生真实检索/解释后仅面板显示引用，页面result/dialogue仍为启动前读取内容。仅补领域结果读取关联，不改业务状态、来源用途、模型动作、人工确认或领域服务。

输入是现有AgentRunView及现时GET retrieval-workspace。使用原页面新增结果读取按钮；先GET同一Run，再GET现时workspace，校验runId/运行版本、task.id、Goal.description精确confirmed-input:<inputId>、Task/Goal版本、retrieval与retrieval-citations结果集引用、answer-turn引用、EXPLANATION的taskId/taskVersion/resultSetId/resultSha以及非空回答。COMPLETED的outcome必须引用同回答，NEED_HUMAN仅接受现有partial-source-coverage并保留人工边界。当前domain GET仍逐次检查权限/来源/保留；客户端匹配只是防止错结果交接，不能替代服务器授权或证明未来持续许可。

页面只显示绑定成功的该回答及当前结果/原来源组件，不重新生成解释，不循环advance；其他STOP或不完整阶段不提供交接。新Agent请求、换Run、重读任务、目标修改、退出及任何失权/网络不确定都清空旧交接，异步响应通过页面epoch及Run读令牌防迟到写入。目标/版本/结果不匹配拒绝交接并要求重新读取。不会将任务完成、人工待审或正式业务办结混同。

验证：领域绑定正常/缺口/错Task/错输入/错结果摘要/旧版本/非法出口反例，以及真实页面加共享面板的读取-交接、403及迟到响应组件测试；正式咨询构建。组件API返回来自测试夹具，明确非正式身份/原生模型/专业Golden。部署批准和现时来源/Task准入不因该修复通过；父任务继续保留真实出口门禁。


## 咨询原受管部署与正向验证限定D（2026-10-06）

DEP-CON-AGENT-NATIVE-01归IMP-AGENT-CONSULTATION-01及现有CON产品R/D。依据用户本轮明确“解除咨询当前运行准入，发布并验证同一Task/Run的正向闭环”；此指令批准执行已有合同内工程部署，不代签第三方权利或新增业务用途。精确原主库容器8d9d4abcb1646396716565fd35477a7a921f370cb9a4812efb96c28d28549d52/loopback5301/CONPROD；原独立权威库43ef58d3c34df4a3c8075e8a2efd55fa59a8f1cd4cc97f5a4f8f574bdc1cf888/loopback5302/CONAUTH。迁移只用CONPROD服务主体连接主库，V096四个Runtime DDL及V097一个围栏DDL；三表必须全不存在，存在/部分迁移拒绝覆盖。记录全部原用户表的行数/内容摘要前后相等；不输出认证/模型/密码内容，不修改任何域/权限/来源记录。保留原DDL与SHA、失败原件，不自动破坏性回滚。

Runtime使用新随机32byte密钥，DPAPI存既有私有vault，不复用模型/身份密钥或提供默认值。依现有bundle-consultation从当前源码打包，记录包内咨询静态资源与当前dist SHA、包SHA以及必要Runtime配置/正式Controller条目。沿现有受管HTTPS/mTLS/模型配置与不可变证据只读挂载，不复制秘密进仓库/证据。启动前核完整容器ID、原配置及其包SHA，新包指针与旧包/配置保留；使用既有受控发布脚本，不从旧隔离构建树或旧HEAD发行。原DB在部署期间保持运行，不停止裁决正在使用的5301及其他居民；仅本轮启动的5302若部署失败且无保留服务依赖可恢复停止。

VER-CON-AGENT-NATIVE-POSITIVE-01先核正式维护主体身份/职责及已有负责人委托。新增业务对象仅走原正式mTLS维护端点及身份/Source/Task合同；期限、来源用途、保留依据及真实任务目标须当前有证据，不从旧短期脚本推导。新对象不覆盖旧Task/Grant/来源合同，模型处理仍限已批准供方/公开非保密目标和具有MODEL用途的结果最小元数据。当前环境机制验证与正式业务/专业验收分开。

运行只沿真实OAuth/PKCE主体登录，创建/读取同Task/Goal/Run并单请求execute；Search工具沿既有真实mTLS Worker和实模型检索，Answer沿已有受管真实模型，无替身/固定候选。保存经脱敏的Task/Run/input/result/turn/来源版本/调用账本/最终结果与读取证据。再次execute或读取同STOP Run不得新增外呼副作用。原生UI必须用浏览器验证，HTML200不冒任务E2E；当前来源/身份失败保留真实拒绝，不扩大许可强行过关。完整动态重规划、专业Golden、真人选择与正式业务办结不由一个正向切片自动关闭。


咨询实际发行修正：FIX-CON-DEPLOYMENT-READY-01仅将原start-consultation-container.ps1两处Started AgentPlatformApplication就绪判断改为当前main实际选择的Started ProductConsultationApplication，未知应用/进程退出/超时仍失败并保留回滚机制。正式OAuth客户端沿既有注册consultation-workspace-product，在构建时明确VITE_CONSULTATION_CLIENT_ID，不改源码默认、不增测试身份；先验证客户端注册仍对应现时正式回调，未配置的包不能冒正式可登录发行。脚本/测试源变化须新未筛选回归原件，旧报告保留。

咨询部署挂载实证修正（同FIX-CON-DEPLOYMENT-READY-01）：2026-10-06实际原生docker挂载Windows JAR成为目录，候选JVM在任何应用启动前失败。运行文件允许在既有Linux受管根的全新runtime-consultation-*私有目录暂存，不位于源证据目录、不进入源码；仅当前JAR、原正式证书和已核模型不可变字节，10001所有者/目录500/文件400，独立manifest SHA及逐文件校验，无网络暂存helper。原Windows路径仍用于本地原件校验，新增可选runtimeRoot/containerJar/runtimeManifestSha及mount.containerSource仅指定同一受管私有根，拒绝跨根、遍历及symlink；发布前以实际容器内test -f和SHA证实JAR及整根清单，旧未配置调用同样先核实际挂载JAR，失败不得触碰旧部署。迁移不重跑，原Runtime密钥复用本轮已合法生成的DPAPI密钥，不重发或覆盖。原发布回滚仅重启原先确实运行的容器，原停止状态必须保持；否则旧环境误启动违反本轮限定D。此为准确发行/资源与恢复技术校正，不改变任务/来源用途或模型供应商。

咨询网络定位实证修正：原配置保存的172.17.0.3/.4地址在数据库停止/重启后已指向不同容器，当前权威库实际.3；候选因权威凭据在错误库失败，未进行任务/来源初始化。使用新受管consultation-product Docker网络及已核完整ID的两个原库DNS名，不改变原loopback端口或原bridge，不依赖易变IP；网络必须标签agent-platform.role=consultation-product且包含配置的两精确库ID。发布脚本仅为该受管网络增加可选network属性，生产连接指向已核原库名称:5236，不能混用主/权威schema或替换数据库。

部署依赖收口：DEP-CON-COMPARISON-TABLES-01只部署已过ARC-CON-PRODUCT-COMPARE-D-01并在IMP-CON-PRODUCT-COMPARE-01实现/机械验证的V094和V095（六+四张不可变准备/特征/输入空表，原SQL中的FK/约束）。当前包原ProductComparisonInputConfiguration在身份/Source Engine开启时需要这些表，不能关闭整个身份/来源或建开发代替页绕过。精确CONPROD连接、十表全不存在及所需父表/键现有核验、迁移SHA、原所有旧表摘要前后相同；只CREATE既有十表，不执行V092用途扩张、不生成任何专业输入/确认/候选或COMPARISON Grant。专业任务状态不因空表部署升级。

发布就绪判断实证纠正（2026-10-06 16:42）：上述将日志改为ProductConsultationApplication的假设错误，保留该历史说明但以本段纠正为准。实际入口AgentPlatformApplication.main在--product-consultation分支调用独立ProductConsultationApplication context，Spring Boot日志仍推断main入口名AgentPlatformApplication。恢复实际Started AgentPlatformApplication就绪标记，测试同时核显式--product-consultation命令；日志名不证明领域隔离，独立context须由入口/扫描配置及既有隔离测试验证。进程退出和超时仍失败，未改变业务合同；16:39:56回归1591/0/0/40在此脚本变更前完成，不冒变更后原件。

当前准入初始化限定D（DEP-CON-CURRENT-ADMISSION-01，2026-10-06）：用户已明确同意CON-AGENT-TASK-USER-20261006-01所列40篇原采集PatentsView/公开机器人吸尘器检索/现批准DeepSeek仅最小结果元数据/18:00北京时间最晚截止。复用原正式组织、HUMAN主体、维护者证书和原保留合同；先实读组织版本与CREATE现时epoch，正式维护API签全新CREATE Grant，正式OAuth成员创建全新Task和READ，再按authorization_version而非goal/task版本签WRITE。按原withdraw-product-use退役已过期原用途，原不可变capture/declaration除contractVersion/validUntil/requestedUses外不变，新合同verify、精确Task/Scope用途决议和现时来源水位，TITLE批准MODEL，CLAIMS不批准MODEL且当前输入仅TITLE。只使用原采集40篇，不补采或伪造来源权利，原rightsEvidenceStatus保持UNRESOLVED。签新证书/AGENT workload和精确Task Grant，各自有效期不超过18:00，不改旧workload。新私有受管runtime保存原JAR/模型/证书字节及新worker leaf；新用途决议追加到原受管证据根并新建完整manifest，旧原件/manifest不覆盖，当前指针走正式发布脚本。该初始化合同不执行检索或Agent Run，VER任务仅在实读准入完整后执行一次Run并读取/重放核验；标题预读显示批准40篇无robot/vacuum标题匹配，不把不相关语义候选或不足结果冒正常目标完成，不私换主题。原生浏览器E2E仍受真实工具/证书环境限制，API结果不冒UI验收。

追加非空正向运行初始化D（DEP-CON-POSITIVE-ADMISSION-02，2026-10-06）：用户CON-AGENT-TASK-USER-20261006-02/v1批准在原40篇及相同最小元数据/18:00截止内追加转子叶片焊接检索。新Task、Goal、Input和Run，不改机器人任务原目标/Run/回答。现时实读已有组织CREATE Grant及42df271e-d4de-444f-aad4-847e59b39855真实AGENT注册/证书/配置仍有效，则复用其身份而为新Task签全新WRITE及Worker Task Grant，不续旧授权。按原单当前来源用途合同机制withdraw现用途、新contract-02及VERIFY/精确新Task用途决议/Scope，capture不变且TITLE有MODEL、CLAIMS无MODEL；追加原件并新完整证据manifest，只改私有发行配置manifest引用，不重建原JAR/模型或Runtime密钥，受管脚本发布。旧目标/Run/输出/原审批和文件保持不变；历史Source投影withdraw属于明确退役动作，不冒旧任务仍有现时来源访问。QUERY限定TITLE和soldering blades rotor语义别名，检索全原40篇，不固定候选/指定命中ID。先核非空成员、精确文档/来源/字段/引用、回答支持同resultSha，再核重放和终态重启恢复及账本。结构/机制结果不能自动成为专业或生产验收；浏览器入口按真实工具能力验证，不绕过证书。


## 咨询同Run交付上下文与引用最小技术校正（2026-10-06）

来源为用户本轮咨询优先、已过R/D切片继续实现指令，技术责任主会话；任务FIX-CON-AGENT-RUN-CONTEXT-01与FIX-CON-AGENT-CITATION-BINDING-01复用既有共享工作台及同Run结果交接D，不新增业务动作、权限、专业规则或来源用途。共享面板读取/advance/execute响应须与请求Run一致；咨询显式传入当前taskId并核返回checkpoint.task.id，错绑定失败关闭，不接受目标/证据投影；明确读取的错响应按确定冲突清除运行。创建请求的错响应并不能证明服务端没有创建Run，必须保留原创建键/输入并按既有未知提交合同对账，不允许换键重建。上下文变化丢弃旧响应及创建键，旧Goal/证据/父结果不能重新显示。

回答与来源投影以当前工作区READ结果为准；结果每成员恰有一个相同sourceId/documentId/documentVersion/sourceVersion的来源引用，回答引用只能指向当前成员并匹配其当前完整来源坐标rawSha/locatorRef等，重复或错引用拒绝。零成员/零引用的合法样本无结果说明保留；PARTIAL仍人工待核，不提升为完成或专业判断。工程组件与集成测试使用测试输入，不声称正式浏览器/模型/专业验收；实际产品发行与现时业务调用另门禁。上一批18:00到期Task/来源/Worker不复活，当前仅源码/合同校正及隔离验证。


## 咨询确认新运行窗口的实际初始化D（2026-10-06，DEP-CON-AGENT-CONTEXT-ADMISSION-03）

来源为本对话负责人“确认”CON-AGENT-TASK-USER-20261006-03/v1的原40篇/转子叶片焊接/既有最小元数据/最多1实际结果解释调用、确认时至2026-10-07 00:00北京时间范围。复用已过R/D的新对象初始化与正式发行合同，签入任何到期对象前实读当前组织版本及epoch；CREATE不足时由当前正式独立维护者签新的Grant对象（不是续原Grant），成员正常OAuth/PKCE创建全新Task/READ，维护者按authorizationVersion签新WRITE。新SOURCE contract-03/用途decison绑定新Task与Scope，原capture字节不变、TITLE可MODEL、CLAIMS无MODEL；原当前用途按正式withdraw退役，历史Task/Run/输出原件不改。

旧Worker已过18:00，不复活；以原受管CA和现时维护委托签全新clientAuth leaf、注册新AGENT workload及新Task Grant，不超过00:00。先确认原维护证书/注册与批准仍有效，新增凭据只保存在受保护私有目录/DPAPI，不进仓库或证据。新私有runtime-03含已核验JAR、原模型/TLS材料及新worker leaf，逐文件SHA/只读/所有者验证；新证据manifest追加原用途文件并保留旧manifest，精确原两库/网络/服务容器替换和回滚按既有脚本执行，不迁移已具表、不停止承载其他域的库。

Goal确认后正式rebind新来源到taskVersion2；核正式身份、Source epoch、当前model policy、Worker配置与新leaf期限，并核无预执行检索。准入完成后才启动1个Run和1次execute请求，在截止内读同Run/结果/回答，再做终态重放和现时失权/新READ/终态重启对账。失败先读取原Run/围栏，不因未知外呼重新创建Run或再次调用；预算最多1实际结果解释调用，不调用proposals、不扩大既有供应商预算。正常/来源支持与真实调用费用/质量分证，不冒浏览器或专业/生产验收。


本窗口CREATE实证纠正（2026-10-06 21:14）：首次新Grant签发409，原件保留。ProductGrants.issueExact要求current_grant_id为空，不会因expires_at过去自动清空epoch指针；只读证实原CREATE epoch79、原Grant version1/ACTIVE但18:00已到期、Run总数仍2。沿既有正式revoke合同由维护者精确撤销这个到期对象（不改其期限），CAS到epoch80并新签Grant对象到epoch81，再创建新Task；不绕过冲突、不修改SQL权限、不重用失败请求键的新摘要。此为新对象替代必要退役技术步骤，不续期或复活旧Grant。


本窗口保留上限实证纠正（2026-10-06 21:17）：新Task请求00:00触发400，原件保留，生产校验没有放宽。现时受管Task/Source Registry为PT2H，Goal/结果为PT1H；批准00:00仅最晚截止，各对象仍受原更短保留上限。Task和来源/Worker对象共享本次技术期限min(批准截止,初始化时钟+min(Task/Source Registry maxAge)-30秒精度/执行余量)，来源Scope/Worker Task Grant不晚于Task。Goal/结果由原服务自动取min(各自原maxAge,Task期限)，最终以真实服务回执为准，不延长旧对象或修改保留配置。已新签CREATE epoch81可覆盖较短新Task，复用这个现时新Grant，不再次签发。

本窗口实际准入顺序与精度纠正（2026-10-06 21:29）：Worker注册成功后首个Task Grant返回503，SELECT证实Task为AWAITING_GOAL且Worker epoch不存在、未产生Grant。沿已有ProductGrants.issueWorkload合同先确认Goal令Task IN_PROGRESS/v2，再原新Worker签Grant epoch1成功，不重建身份、不放宽Task状态。来源scope原初始化按ProductSourceScopeGrants截到毫秒，重绑却使用原100ns请求值触发已有非扩权503；SELECT证实scope v1/旧Grant未变，将新请求expiresAt向下截为已有毫秒期限后rebind v2成功，不扩大用途/时间、不改生产校验。两次失败及SELECT原件见window03证据目录；本轮未改产品源码，1次实际解释预算已使用，终态恢复不重复外呼。

## 咨询浏览器恢复坐标最小技术实现（FIX-CON-AGENT-BROWSER-RECOVERY-02，2026-10-06）

来源为当前用户“先工程准备、未知提交对账原Run、不换键、不使用已耗尽调用额度”指令；技术负责人主会话，复用ARC-AGENT-WORKBENCH-D-02及同Run/同创建请求已批准消费合同，不增加领域动作或权限。仅为咨询增加可选的浏览器同标签页恢复端口，共享面板默认不持久化其他域输入。

恢复记录只在sessionStorage存当前issuer/subject身份索引、Task ID、已确认Input ID/Task version和期限，以及原创建随机键+仅expectedTaskVersion请求体，或一个已经服务端核验的Run ID；不存JWT/密码/Goal正文/来源/回答/结果/权限快照。身份索引由当前会话JWT的iss/sub作非权威命名空间，只有正式workspace READ成功后才创建恢复端口；该索引从不授予权限。新登录同身份可以找回原请求；不同身份/Task/Input/version不复用。TTL不超过服务端当前Task/Goal最短期限；字段缺失、损坏、过期时不恢复、不自动换键重发；读取损坏时失败关闭，允许用户以已知Run手动重新读取对账。无可核期限或身份索引时不启用浏览器存储，不降低服务端准入。

发送创建请求前必须先保存原随机键和精确expectedTaskVersion，保存失败不发送。跨刷新或重登录只恢复请求/Run坐标，不自动调用POST；未知创建只允许用户对账相同键与相同输入。已有Run只预填编号且要求重新GET读取；不恢复旧Checkpoint/结果，不以坐标冒当前授权。失权立即清正文及运行投影，不据此续Grant；原未知提交的最小坐标在原期限内可保留供重新正式登录后对账，不因401误丢原创建键。成功读取/创建精确绑定后记录已知Run，409/404的确定冲突按既有合同处理；Task/Goal上下文变化不删除或复活历史业务对象。URL不携带Token或恢复键。

工程验证覆盖正常确认Goal→start→单次execute→同Run当前结果绑定，以及未知创建刷新同键、执行结果未知后刷新先GET、当前失权、身份/Goal变化、存储失败与到期；全部是隔离组件/API合同，不冒正式浏览器或专业验收。正式全浏览器任务待新Task/用途/Worker/模型单次范围有新批准后取证；旧对象不读不延长。

## 三域功能并行交付：本轮限定技术细化

来源：用户明确“先完成功能开发；执法裁决一起执行；整理完整指令并执行”，技术责任主会话开发团队。继承docs71、既有ProductPatentSelection、执法文书幂等和裁决单案/报告精确版本合同；不增加专业规则/评分、来源用途、运行批准或业务状态。本轮不重建评测/审阅系统。

- FIX-CON-SELECTION-READ-01（主会话）：当前咨询页面保存选择仅显示revision，不展示selectionId及实际已保存文献，也无已有GET selection入口。消费原POST /selections与GET /selections/{selectionId}真实Revision字段，显示Task/resultSet/结果SHA/选择版/文献版本与原文定位；用户显式输入结果集及选择编号读取已有持久选择，不新增列表/搜索API、不枚举别人的选择。响应校验所请求Task/resultSet/selectionId和合法SHA/非重复成员，保存后须精确绑定提交resultSha及所选成员。历史选择明确标出自身taskVersion，不把旧选择当本轮结果或自动勾选当前候选。上下文变化、失权、刷新或未核实结果清除正文；迟到响应不采用，不存正文/token到浏览器存储。不自动读取旧Task/Goal，不推定现时源权限，仍由原服务重核。网络未知保存不能换key再发，保留原body/key并明确同请求对账；如POST已确认而后续读取失败，不能重新POST冒恢复。
- FIX-ENF-DRAFT-COMMAND-RECOVERY-01（智能体A）：现有文书页面prepare/command生成随机键，网络未知后重试会另发键。沿用已有服务幂等/确切版本合同，新增内存中原path/body/key的不确定请求围栏与显式同请求对账；新写入需先解除旧不确定。真实成功Receipt到达即清该围栏，再执行原read，读取失败不是写入未知；确定HTTP拒绝和失权与网络/5xx未知分别显示。现有ENF请求封装仅新增保留真实status的失败类型，原消息与AccessDenied/Conflict兼容；该ENF共享文件本轮唯一修改者A。不持久化正文/身份、自动换键或改业务决定。切换输入/文书不得悄悄丢弃同身份下未知写入，失权/退出清敏感内存；取消未知不能声称服务器撤销。
- FIX-ADJ-REPORT-EXACT-FOCUS-01（智能体B）：WorkArea已有同Case/Run/报告SHA交接，但AdjudicationWorkspace的onOpenReport忽略target，仅切到报告tab；若仍在报告区并选择旧稿，可落到错误报告。消费现有精确target.revision/SHA，跳转并选中该确切报告；不能只聚焦页面外壳。未保存修订保留现有确认，不确认不导航；手动版本切换仍有效；Task/Case上下文变化清定位目标，不跨案复用。缺目标/错SHA拒交接，不自动改为最新稿，不新增业务命令/报告内容/专业推理。

三切片依赖已完成ARC-AGENT-WORKBENCH-D-02及各自原域D，是原IMP-AGENT-CONSULTATION/ENFORCEMENT/ADJUDICATION-01和IMP-AGENT-WORKBENCH-01的功能消费增量，不解除父任务的运行/专业门。先反例与正例专项，三个智能体不改同文件；各自不跑共享构建/全量。主会话集成固定源码后跑完整前端/两构建和verify-all；业务API/迁移未改变则不以无关后端重跑充进度。真实发行/浏览器操作仍需自身准入另取证。


## 三域结果精确消费接续的限定技术D（2026-10-07）

FIX-ENF-DRAFT-READ-BINDING-01沿已批准原生文书结果/回执消费合同：当前文书GET必须匹配请求id/revision，交接还核原manifest；确认写入后的GET必须核Receipt的draftId/revision/contentSha，不能把返回错误版本或错SHA展示为该命令结果。候选的baseRevision/baseContentSha及复核的revision/contentSha必须绑定已读稿，不替客户端授权；错误组合拒绝整个新展示，不改变专业文书、审核规则、导出或自动重写。历史、候选、复核读取与同身份上下文的异步响应隔离；写入已确认后GET失败仍保留已确认回执而不盲发POST。

FIX-ADJ-CASE-RECEIPT-BINDING-01沿现有CaseView/Receipt精确对象与同键对账：GET必须是请求Case，历史GET为确切工作版，当前GET和head一致；已有Case的命令回执必须属于原Case和原requestId，并带合法版本及SHA。跨案或错误键的200响应不能清未知请求再导航他案，按未知提交保留原Packet供同键对账，不能标正式拒绝或确认成功；有效回执后原Packet立即清除，后续GET失败不重发写入。创建时原Case未知，仍核原requestId及合法回执，不假设既有Case。专业分析、身份/来源范围及后台动作均不变。两切片只由原合同推导技术细化，未把测试假数据作为专业规则或正式批准。

同一限定消费D的集成细化：命令确认后使用已有GET /versions/{Receipt.workVersion}读取确切结果，而不是要求并发当前head仍停在该版；确认版若已不是当前head，按原历史快照只读规则展示，不能交接为当前可写报告。读取失败保留有效回执，并提供仅GET确切确认版的恢复入口，不重POST；普通当前/历史导航仍按原入口区分。

## 导出与案件原件消费限定D（2026-10-07）
FIX-ENF-EXPORT-READ-BINDING-01沿DocumentDraftContract.ExportArtifact/Receipt：导出GET元数据的id/draftId/revision/contentSha256/manifest/requestId必须绑定原EXPORT回执及稿件，artifactSha256合法且下载原件SHA吻合才交付；确认后的读取失败只重读原exportId，不能重POST产生新导出。保留现时服务权限与既有未知命令围栏，不改审查状态。
FIX-ADJ-ORIGINAL-READ-BINDING-01沿JdbcCaseWorkRepository.original/originals：CaseInput原件以UTF8原文SHA保存；消费校验合法Source坐标、原文SHA及当前CaseInput来源确切匹配，允许返回历史CaseInput来源，不以列表只含当前来源为条件。失配/空缺原件撤下新展示；异步摘要后再次核上下文，不能显示跨案迟到正文。此技术细化不新增资料用途或专业规则。


## 会话与Agent报告交接读取限定技术细化（2026-10-07）
FIX-ENF-SESSION-RESPONSE-01沿当前文书身份/请求恢复/精确导出合同：登录或身份读取、操作成功/失败/末尾回调必须属于原会话上下文，退出或卸载后不能复活身份/正文/错误/导出；不改变现时服务权限，不将旧未知写入误标确认。修复只隔离迟到响应。
FIX-ADJ-REPORT-HANDOFF-READ-01沿已批准Agent ReportHandoff与CaseRead/OriginalRead合同：Agent交接读取也必须重用普通案件已具备的同Case/工作版/历史/原件SHA校验，不只比较caseId/reportSHA后直接展示新CaseView；上下文改变不得提交旧正文。确认版与现时可写头保持区别，失败只GET重读，不能重POST生成或代人工复核。两者不新增专业/身份/来源/恢复行为，只消除同一批准合同消费分支的遗漏。


## 既有三域消费合同遗漏修正（2026-10-07）

FIX-ENF-DRAFT-CONTENT-INTEGRITY-01：沿ENF-14及既有确切草稿SHA消费合同，对原contentJson UTF-8字节复算SHA，不重新序列化正文；匹配后才交付正文/候选/复核，异步摘要后沿原会话围栏重核。失配撤下内容，已确认写入后读取失败仍仅GET恢复，不改变人工权限或审核结论。独立负责人子智能体A。

FIX-ADJ-CONFIRMED-READ-WITHDRAW-01：沿合法回执后仅GET确切确认版合同，在合法回执绑定成功后、确切版本读取前撤下旧view，保留回执及读取恢复入口；网络/503不得让旧表单和Agent入口恢复可写，错误回执仍保留原Packet，历史确认版仍只读。不改变案件规则、专业方法和权限。独立负责人子智能体B。


## 隔离HTTPS夹具端口原子绑定（2026-10-08）

FIX-REGRESSION-HTTPS-PORT-BIND-01仅修复实际RBC14端口竞争。禁止先关闭ServerSocket探针再启动服务器；嵌入HTTPS server.port=0交OS原子分配，启动后记录实际endpoint。每个自有夹具固定独立canonical Issuer用于JWT、会话、水位及Grant，重启保留不变；传输请求和浏览器origin/redirect使用实际endpoint。两个坐标不可混用，不变更产品Issuer合同或生产配置。配置覆盖若要求端口仍遵守原显式测试配置，不添加权限旁路。专项验证重启前token仍按同Issuer授权，及既有无权、只读、重放、并发合同；完整回归使用新RBC15，RBC14错误/原XML/现场保留。
