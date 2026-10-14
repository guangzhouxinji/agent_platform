# 生产挂点修复对应的专项加强（待主会话完成通知）

仅新增测试文件及本证据目录变化；不修改三个生产文件、共享Runtime、主计划、ADR、迁移、其它测试，不运行独立编译/测试或全量。主计划只读时已为V10.19；这不是本执行者更新。

## 合同不放宽

原attempt-05测试及其14成功/2失败日志、before/after、Runtime实测解密结果和原冻结源码均原样保留。该版本测试SHA为 `d201494019990af37a95551e248953f7e1ec8c66152e545ba1109f11516247b6`。

旧 `restartedRuntimeLocatableOldAnswerBeyondWorkspaceWindowMustKeepIsolationCode` 在该批实际返回STOP/ERROR、execution-error，精确域隔离码丢失。本轮改为 `restartedRuntimeLocatableOldAnswerBeyondWorkspaceWindowMustRejectBeforeAdvance`：同样保持已合法闭合的新当前结果、窗口外精确旧answer-turn、后于旧执行的构建见证，断言整个restart().advance在进入推进前直接抛 ProductHistoricalResultIsolation.Unverified，reason等于CODE，模型不派发，invocation行数0，**两独立自有数据库的全表内容/规范化快照SHA均不变、原result_sha不变**。不再接受先写STOP再检查reference；这是加强既有缺证不消费/稳定机械码/原件不改合同，不是删除稳定码assert或绕过失败。

全表snapshot此次同时纳入AUTHORITY.*（独立Grant高水位库），原主库表名保持不变。此前证据只断言主库全表，现在约束更强。

## 新增反例

- `restartedRuntimeLocatableCitationsMustRejectWithHealthyCurrentResult`：真实SQL checkpoint observations包含 retrieval-citations:<旧ID>，合法当前结果不能遮蔽此依赖；同样要求推进前Unverified+CODE+双库全表/SHA不变。
- `readCheckpointClosedDependencyRemainsUsableWithoutWriteGrant`：通过真实Grant/Watermark撤销WRITE且保留READ；先断言tools.current(seed)确实404作为负控，再用READ CurrentExecution callback在真实store.load中复用tools.requireDependencies(c)，闭合retrieval-citations依赖仍可读，checkpoint/双库不变。不得将current()的WRITE要求引入READ。
- `readCheckpointMissingDependencyReturnsIsolationCodeNotMissingWriteGrant`：同样无WRITE、READ有效、当前新结果合法、旧retrieval缺证且窗口外；READ store.load必须抛Unverified+CODE而不是错误的WRITE缺权404，双库/Run revision/原SHA不变。

READ callback在自有fixture中用真实Workspace.read完成现时READ/Goal/现时来源读取，再核exact goal/scope绑定，之后调用计划中的ProductConsultationAgentTools.requireDependencies(Checkpoint)，且不调用current()。这是该生产领域方法的SQL store回调复用验证，不宣称Controller/HTTP实例化或来源批准全链实证。

## 准备状态

本轮准备19个@Test、299行，源码SHA及逐项名称见prepared-receipt.json；源码快照在本目录。**compiled=false、executed=false**，旧16项实测结果不冒充新19项结果。新增调用以用户明确计划的requireDependencies(Checkpoint)签名为契约；未擅自提供mock/反射降级或修改生产补齐。

等用户明确通知三个生产文件完成之后，才从当前实际生产源码独立编译并使用全新attempt目录运行这19项；此前不启动编译/专项/全量，不消费RBC09。原attempt-01到05和原冻结清单均不覆盖。
