# 咨询不可验证历史结果的只读隔离技术D

依据2026-10-07用户本轮明确授权及FIX-CON-HISTORICAL-BUILD-DIAGNOSIS-01既有六组合诊断。任务FIX-CON-HISTORICAL-RESULT-ISOLATION-01；R沿咨询检索/对照现有批准合同，限定D为以下故障关闭细化，不新增专业判断或来源用途。

1. 保留原结果、引用、选择、回执和原始SHA；不通过重算/补造构建回执、更改业务/案件正式状态或删除实现隔离。不得直接处置现行三结果。
2. 从历史结果记录的精确source/contract/decision/epoch/field/model及原始执行时间检查所需构建见证，不能使用来源的现时新版本或新构建替换历史事实。缺失、冲突、未来构建或错误绑定的结果不可作为已验证结果展示、引用、解释、进入比较或续跑；当前权限检查仍须先执行，隔离检查不提供读取许可。采用只读可复算判定，不以固定三ID硬编码旁路或新的绕过开关放行其它损坏记录。
3. 缺证错误带稳定机械代码HISTORICAL_RESULT_UNVERIFIED，不泄露未授权结果正文；已生成选择、后续输入或模型回答如依赖隔离结果，同样不得作为有效业务结果消耗。确切原件独立合法引用不因为某检索结果隔离而一律禁用，但不能继续声称隔离检索结果可用。
4. 恢复检查区分硬损坏与缺构建见证。报告列结果/字段/缺项及依赖影响，overallIntegrityVerified=false；现有严格verify入口不得被改成绿灯或取消其它原件/摘要/权限校验。技术报告允许列明部分可用与不可用范围，不宣称无问题完整恢复。
5. 隔离副本验证原记录与SHA不变、关联路径拒绝、无关已闭合合法数据仍可消费；工程自有数据不计真实历史样本，真实历史复制必须逐字节来自许可用途仍有效的留存原件，不打开过期保护包。缺历史可复制原件时明确仅工程副本证据，不能伪造三份实际原始结果。

文件责任：隔离执行者独占新ProductHistoricalResultIsolation类及其测试，ProductPatentSearchPages/Receipts/Citations/Selection、ProductComparisonDocuments、必要结果解释读取与恢复检查挂点；禁止修改Identity/Grant/Source批准/Start执行/Model Gateway/主计划。新增迁移若不可避免先向主会话协调，当前设计无须历史DML或迁移。

## 6. 集成实证后的最小技术细化：新TITLE结果构建出处

本轮未筛选回归揭示新隔离消费合同与现有TITLE生产路径不闭合：旧TITLE混合检索结果只存关系/得分，未记录实际embedding版本。不能根据当前或唯一构建猜历史模型，也不能让新正常结果提交后立即不可消费。派生技术任务 `FIX-CON-TITLE-BUILD-PROVENANCE-01`：在未占用V104增量表为**新结果**保存精确source/contract/decision/epoch/TITLE/model及已存在的真实build SHA/数量/完成时间和记录SHA；从当前受管实际Index读模型版本，使用本次源快照捕获已有真实构建，发布事务内重核并与结果原子写入。无表/无确切构建/冲突/未来构建失败关闭，不新增用途、模型批准、业务决定、评分或保留期限（沿结果原合同）。旧数据绝不补录，不以现时模型替换旧出处；旧缺证仍隔离。读时只认可本结果已记录的绑定及历史build/向量原件，记录损坏硬拒绝。现行库/固定A不应用V104，后继发行须显式列新增脚本。此为既有R/D下生产与消费证据合同的技术闭合，不扩大正式运行授权。

## 7. 后继发行校验的代际绑定（REL-CON-START-SUCCESSOR-BUNDLE-01）
原固定A的校验模式、迁移pin及证据不变。新增显式后继代际SUCCESSOR_WITH_ISOLATION_V1：内置核验V102/V103/V104原SQL SHA、Start与历史隔离/新TITLE出处必要类字节，不接受调用者自报类或脚本pin。V104依赖既有CON4_RESULT_SET，不把前置V064推定为待重放迁移；目标实际前置仍须现时schema检查。无脚本、错SHA、缺类或代际未知拒绝发布工程清单。包含Start/新出处的后继包不得选择旧A代际绕过新校验；固定A本身仍沿旧校验模式。输出明确仅离线包/源码绑定，不能据此批准DDL、部署、历史DML、模型或业务运行；新代际无破坏性逆迁移方案，失败保持安全停写直至兼容性证明。1949及A原件保留，不静默替换。只有必要整链准备完成后才固定最终发行与集中申请。
## 8. Run窗口外精确依赖消费前重核（2026-10-08）
`FIX-CON-HISTORICAL-RUNTIME-DEPENDENCY-01`沿本ADR第5节隔离合同及既有咨询R/D，修复实际专项揭示的间接消费遗漏：工作台最近三条回答和当前结果不能代替Run已持久化的精确依赖。咨询领域适配在现时Task READ（执行另核WRITE）、当前Goal/版本/scope/来源绑定通过后，遍历checkpoint已知retrieval:、retrieval-citations:、answer-turn:坐标。结果及引用通过已有Search.read核精确Task/source/current授权及历史见证；回答通过已有Dialogue.read核精确turn/result/SHA。Controller的CurrentExecution回调在加载/保存Run时使用同一领域检查；读取不擅增WRITE要求，Tools.current执行路径保留WRITE。缺证必须在checkpoint推进前传播HISTORICAL_RESULT_UNVERIFIED，不新建STOP或执行revision，不由共享Runtime吞为execution-error；原结果/回答/SHA/Run均不改。未知引用协议不自行发明新业务规则，继续明确未覆盖范围。

本细化不扩大来源权利、模型用途、专业方法或恢复权限，不改变三域通用Runtime故障协议。主会话独占ProductRetrievalWorkspace、ProductConsultationAgentTools、ProductConsultationAgentController；子智能体独占新增ProductHistoricalIndirectConsumptionTest。原始14通过/2失败与实际revision推进原件留存。更新反例从允许durable STOP收紧为加载/推进前拒绝及全DB/SHA不变，不降低标准换绿。工程对照必须证明无关闭合结果和READ-only历史检查仍可用；实际三历史样本仍0，不改现行数据。

## 9. 后继发行输入与拆分helper校验闭合（FIX-CON-SUCCESSOR-RELEASE-CLOSURE-01）
仅细化第7节工程发行D：后继必要类加入ProductComparisonStartHandoffGuard及ProductComparisonStartReceiptChecks；先拒绝全部缺类，再以受管JDK javap -verbose只读解析每个必要类（不加载执行产品代码），拒绝解析非零/超时、诊断及确切类名不符，不能仅凭CAFEBABE通过。固定A校验及原pin不变。后继快照显式选择代际，在原backend/db/scripts完整范围上增加frontend/src、frontend/public（存在时）的全部源文件/资源及frontend根的ts/tsx/js/jsx/mjs/cjs/json/html/css及构建配置；排除生成产物dist、target、node_modules、tsbuildinfo，不接受自选覆盖名单。包括测试源码是保守冻结，不意味着业务样本。CLI前后快照与校验代际一致，遗漏/漂移失败关闭。输出仍OFFLINE_ENGINEERING_ONLY；反例fixture仅证明拒绝，后继正向须真实工程编译及实际咨询资产清单。独立新名工程包不是最终发行固定/发布批准，不覆盖A/1916/1949或现行资源；无DDL/来源/业务/模型授权变化。主会话独占校验器、CLI、专项及唯一主计划。

## 10. 当前输入/无模型接收发行的完整代际（FIX-CON-CURRENT-RELEASE-GENERATION-01，2026-10-08）
沿第7/9节发行R/D及V10.37已通过的最终产品输入/分层合同，本轮实际发现旧SUCCESSOR_WITH_ISOLATION_V1只pin V102/V103/V104，不证明V105持久未领取回执及V106仅DISPLAY范围所需约束，亦未要求新Handoff/DocumentCatalog/只读Workspace类。旧代际及固定A保持原pin/检查，旧包/原批准不改；新增显式SUCCESSOR_INPUT_HANDOFF_V2，不新增业务状态或迁移。

V2内置当前真实V105/V106字节SHA，合并原V102—V104；V105依赖V102，V106必须先具备既有con4_task_source_request及V056的ck_con4_scope_query_grant。库存明确既有前置不是待重放DDL：无日志不能盲重放初始化或V094/V095；执行前必须核实际约束、表结构、非空现状及V106是否已前向完成。本切片不操作目标DB。逆向恢复QUERY必填可能损坏非空DISPLAY-only历史，V105非空逆迁移亦拒绝；不以旧JAR换回自动恢复写入。

V2必要类涵盖现有Start/隔离/出处类及ProductComparisonHandoffController/Store、ProductComparisonDocumentCatalogController、ProductComparisonInputConfiguration、ProductConsultationWorkspacePageController、ProductRetrievalWorkspaceController/Configuration、ProductRetrievalDialogueConfiguration，仍使用真实JDK逐类结构核验及固定资产/源码完整绑定。含Handoff/DocumentCatalog的新包不能用V1或A绕过检查。调用者不能自选脚本pin或必要类。

CLI补只读--migrations模式输出当前内置全部前向SHA/依赖/既有前置及NOT_INSPECTED_OR_APPLIED语义；该模式不能带JAR/输出/快照参数、不创建最终发行manifest、不应用DDL，不声称目标已满足。现有--snapshot和verify模式显式选择V2；输出仍仅离线工程证据，不放宽现时身份、来源、保护、模型或发布准入。

文件负责人主会话，修改范围仅现有release helper/test/CLI及本ADR/唯一主计划；不改产品业务实现/SQL/包/现行环境。退出：迁移真实SHA及顺序读取、V105/V106缺失/漂移、缺必要类、V1/A冒新代际、未知代际及CLI参数冲突失败关闭；原A/V1专项保持；集成新隔离未筛选回归/构建/verify-all。旧CLAIMS来源批准18:00后不得再用于新实证；完整回归沿既有可选许可条件真实记录未执行，不修改测试或转skip放行。父发行仍PARTIAL，保护使用依据/历史真实副本及正式发布独立门禁。
