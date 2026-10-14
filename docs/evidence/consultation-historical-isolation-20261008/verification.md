# 历史结果间接消费专项实测交接

仅 FIX-CON-HISTORICAL-RESULT-ISOLATION-01 的新增专项证据；主计划 V10.18、历史隔离 ADR 原件不改。不是新计划、任务状态台账或验收结论。

## 固定交付与实际结果

- 唯一新增仓库测试：`backend/apps/platform-api/src/test/java/cn/xinji/agentplatform/security/product/ProductHistoricalIndirectConsumptionTest.java`，280 行。
- 冻结测试 SHA-256：`d201494019990af37a95551e248953f7e1ec8c66152e545ba1109f11516247b6`；当前文件与 attempt-05 实际编译输入逐字节相同。
- D:/software/jdk-21/bin/javac.exe 独立编译：exit=0，产出278个类；实际 Jupiter 单类专项：16项，14成功，2失败，0跳过，exit=1。**全仓、任务或产品门禁不是绿灯**。原始日志、编译/运行参数见 attempt-05，机器汇总见 junit-summary.json。
- 完整结果以 attempt-05 为准；固定输入/关键生产只读副本及 SHA 见 frozen-files.json / frozen-source。没有修改冻结副本对应的现行生产文件。
- actual file-H2 + JdbcTemplate；复用既有 ProductHistoricalResultIsolationJdbcTest 的自有行构造与schema（反射访问私有fixture构造，仅调用 create，不选择/执行原fixture的任何 @Test），不修改旧测试，不mock隔离判定。
- TaskDirectory、TaskGoalReader、ConsultationAccess、GrantWatermark、Grants、SearchExecution、Pages/Receipts/Isolation、Dialogue、Workspace、ConsultationAgent、AgentRuntime、CheckpointCodec、JdbcAgentCheckpointStore 均为真实产品实现；授权使用两个独立自有file-H2和现时Grant/Session，撤权通过真实watermark.change/grants.revoke。
- SourceAvailability、SourceWatermark、内容引用投影、RetrievalWorkflow、模型端口为机械工程double。缺证读取实际贯穿产品SearchExecution和产品JDBC，不由double返回隔离结论。Jwt是自有已解析身份fixture，不计OAuth/签名验证/TLS。引用投影不计原件/许可验证，Workflow/Model在拒绝场景不执行外呼。
- 16场景均留存before/after全表规范化JSON和receipt；Snapshot直接读取BLOB字节而不是不稳定句柄文本。14成功场景全表SHA不变；2失败仅AGENT_RUN_HEAD/AGENT_RUN_REVISION变化，原结果、成员、字段证据、选择、模型turn均不变。汇总见 scenario-summary.json。坏摘要场景在测试准备期故意改自有result_sha以制造故障，冻结后未变；不是对真实历史原件的修改。

## 通过范围（14项）

1. Dialogue.read 已留存依赖回答：缺构建拒绝，机械代码 HISTORICAL_RESULT_UNVERIFIED。
2. Dialogue.invoke(EXPLANATION) 的 currentResult：新调用在invocation fence与模型派发前拒绝。
3. EXPLANATION同幂等键重放：重新走read和依赖验证，不以已存回答放行。
4. Workspace.read 当前Goal/result路径拒绝。
5. Workspace无当前Goal时，其窗口内已留存turn依赖同样拒绝。
6. null身份返回401；当前READ撤权返回404；WRITE撤权在解释前返回404。
7. 当前Source拒绝503先于历史缺证，未返回缺证结果内容。
8. 坏result_sha仍硬损坏拒绝，不降格为缺证。
9. 构建闭合的独立工程结果在Dialogue/Workspace可消费，不因隔离实现整体封锁。
10. 同库与缺证结果并存、无result依赖的PUBLIC PROPOSAL仍可read。
11. 新建适配器/真实SQL加密checkpoint续跑时，当前结果缺证在load/advance之前拒绝，Run head/revision不变。
12. 同续跑入口READ撤权优先404，Run head/revision不变。

## 生产遗漏A：可定位旧检索依赖被当前合法结果掩盖

失败测试：restartedRuntimeLocatableRetrievalMustRejectWithHealthyCurrentResult。

自有工程场景先在真实JdbcAgentCheckpointStore保存PLAN checkpoint，observation保留精确 `retrieval:<旧resultSetId>`。随后加入不同ID、时间更晚且见证闭合的合法自有结果；其构建晚于旧结果执行，**不能**为旧结果补证。旧回答被三条合法PROPOSAL挤出Workspace最近三条窗口。测试明确断言新result可正常消费，同时对旧turn直接调用Dialogue.read仍抛Unverified。

实际resume：没有拒绝；Run revision从1到2，phase=ACT，outcome=null，原 `retrieval:<旧ID>` 仍留在observations。不是完成/外呼成功证明，但已经证明带不可验证历史依赖继续规划并提交新checkpoint。机器输出见 runtime-observed-results.json；它由真实AgentCheckpointCodec离线解密自有snapshot产生，无数据库访问。

精确责任挂点：

- `ProductConsultationAgentTools.current(Checkpoint)`（22–28）：只读workspace、WRITE/Goal/scope，未验证checkpoint.observations中的可定位依赖。
- `ProductConsultationAgentController.context(String,String,Jwt,boolean)` 内 `JdbcAgentCheckpointStore.CurrentExecution` lambda（84–93）：接收c但只验证当前workspace/goal/bind，不核对c依赖；这是产品HTTP读取/续跑共享存储挂点。本专项未实例化Controller/HTTP，其未使用c是源码定位证据，不冒充该HTTP整链已跑。
- `JdbcAgentCheckpointStore.load(String)`（44–63）：确实在解密后以checkpoint调用current.require，但领域回调没有消费这些历史坐标；不是建议把咨询规则塞进共享存储。

风险：合法当前结果/窗口截断可以遮住仍留在Run里的历史检索依赖；旧引用会继续作为运行证据保留。**未证明真实历史正文外泄或已经完成模型调用。** 主会话可在咨询领域现时权限检查之后、推进之前核对已知可定位retrieval/retrieval-citations/answer-turn依赖；两处领域挂点须一致，不能只扩大Workspace的三条查询窗口或使用当前build替代历史。

## 生产遗漏B：精确旧回答被拒绝但机械缺证码丢失

失败测试：restartedRuntimeLocatableOldAnswerBeyondWorkspaceWindowMustKeepIsolationCode。

当前Workspace返回上述合法新结果及三条无依赖PROPOSAL，Run保留精确旧answer-turn。`ProductConsultationAgentTools.evaluate(Checkpoint)`（54–70，62精确dialogue.read）实际进入真实旧回答读取并触发缺证，但 `AgentRuntime.advance(String)`（62–65）通用RuntimeException捕获把它写成STOP/ERROR、reference=execution-error。测试先断言STOP且非COMPLETED，再断言稳定隔离码，最终仅机械码断言失败。**这一项没有放行有效回答、完成或模型调用**，不可夸大为直接绕过隔离；问题是领域拒绝被吞成不可定位普通执行错误。

主会话选择领域挂点在advance前传播该代码可同时消除A/B，不必为本问题扩大共享Kernel的领域知识；若采用共享故障协议，则仍须保留领域稳定机械代码、当前授权先行及通用硬损坏区分。本专项未做任何该类生产修改。

## 未覆盖／交接边界

- 工程自有输入、临时文件数据库与自有持久checkpoint；不是三份真实历史结果或来自真实Run的复制原件，真实历史样本=0。未打开保护包、当前DB或业务凭据；不造/补历史原件、构建回执或旧SHA。
- checkpoint用自有工程任务、已知可定位坐标和合法格式摘要，经真实codec/store保存；不是HTTP生成Run全链回放。retrieval-citations变体、未知引用协议、已终态STOP缓存读取、窗口外不带明确坐标的衍生文本、多层委派、其它Runtime阶段尚未单独覆盖。
- 未运行真实DM8、本次API/UI/TLS/OAuth、专业模型/检索重算、原始文献字节读取、独立合法原件脱离result的直接产品消费。合法对照证明工程result/turn路径仍可用，不证明来源权利或专业验收。
- 用户另行报告的六schema/36表SHARE锁实证、RBC09和全量准备不属于本次新执行结果；未消费RBC09或竞争任何现行DB/构建输出。
- 未运行verify-all/Maven/Reactor或全量；这是用户限定“只独立专项、不全量、不改控制文档”的执行边界，不声称总体结构验证/业务语义审核通过。只读核对47需求文件无增删/摘要差异（requirements-baseline-before/after）。777已有后端主源码/复用fixture及主计划/ADR摘要前后全同（source-digests-before/after）。

## 原始失败与复验保留

- attempt-01：编译失败（新测试误用Jwt.Builder.id、snapshot泛型）；原输入/日志留存，仅修新增测试。
- attempt-02：新测试snapshot用Blob.toString产生句柄假差异；因fixture诊断已明确停止该未完运行，exit=4294967295，不能计完整专项。原日志/说明留存。
- attempt-03：预打开自有H2默认用户名与原fixture sa不符，15场景全在fixture构造失败；不是产品缺口。
- attempt-04：修fixture后14成功/1失败，初步Runtime窗口场景无当前result，不能把普通不足证据终止声称已外泄。
- attempt-05：最终冻结的16项，14成功/2上述生产缺口失败。为Runtime加入不同ID/后于旧执行的闭合合法结果对照；未放宽拒绝断言、禁用测试或改生产“做绿”。

运行复现：在仓库根目录 `python docs/evidence/consultation-historical-isolation-20261008/run-isolated.py <全新attempt名>`；脚本拒绝覆盖旧attempt，仅选择新增专项一个类，javac输出在该attempt/classes，当前依赖通过-sourcepath/-Xprefer:source编译，不使用Maven/shared target。保存的runner对后续自有临时DB/Mockito scratch也固定java.io.tmpdir至该attempt/tmp；该工程输出路径设置不改变测试/产品语义。主会话修共享生产后使用新attempt复验，保留本次红灯。
