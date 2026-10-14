# ComparisonInput 下一主链实证

日期：2026-10-05；任务：IMP-CON-PRODUCT-COMPARE-01 / PC-COMPARISON-INPUT-01。
设计：docs/adr/consultation-comparison-input-20261005.md，沿用已过原对照D及prepare/确认限定D。状态仍只由docs/08控制。

## 实际实现

正式 POST /v2/consultation/tasks/{taskId}/patent-comparisons 创建不可变ComparisonInput，GET /{comparisonId} 读取输入回执及持久候选进度。产品配置直接启用，无开发profile/模拟模型/固定结果或权限旁路。公开回执不返回内部授权快照。

创建要求Task COMPARE+READ、当前确切FeatureRevision/packageSha、Goal/Input/Task版以及原件/权项材料SHA。Task锁内固定本次现时权限、材料快照、原确认版、purpose/method和前comparison关系；输入、全部候选QUEUED槽、current指针、创建事件/幂等回执同事务。候选QUEUE不是实际对照运行结果。用户输入以外不保存第三方正文或原文片段。

不同请求载荷或旧前指针409；旧特征/Task/材料或现时权限版不能消费。相同材料历史可经新当前授权读取，但不能用它复活旧执行。读取校验native列/候选/完整包/事件；末尾权限失败或事件写入失败全事务回滚。V095/U095仅四张con5表，FK/唯一/CHECK正逆实测，无运行库应用；有输入历史先拒绝回退而不是删记录。

## 实际测试与保留证据

- v876前置verify-all退出1，仅TEST_EVIDENCE_STALE；47基线文件/41需求契约一致，上一回合裁决计划字段问题已由其轨道修正，不归本次功劳。
- v877测试初版继承prepare测试，但新增setup提前确认特征，污染了继承测试的空库/前版预期，真实失败日志保留。改为独立测试、组合复用prepare产品命令和测试准备，未降低产品约束；v878实际11项全通过。
- v879十套回归通过。随后公开回执收敛，内部currentPreparationSnapshot/authorityBindings/actorIssuer不外露；v881实际12项通过，含正式Controller MockMvc C。不是实际Issuer/JWKS或真实HTTP部署I。
- v882最后同输入十套72次执行，0失败/错误/跳过，Maven退出0；其中新InputTest为12个独立测试，旧BoundaryTest继承12项重复执行已明示，不冒72个独立场景。源码/冻结SHA、迁移SHA与十套原XML保存在comparison-v882-build-inputs.json和comparison-v882-xml/。基线为model-v861-build-inputs.json加明确窄覆盖，不冒当前完整脏工作树。
- 新测试覆盖合法创建/读/消费、真实候选原件绑定、QUEUED真实进度、公开回执最小化、严格JSON/匿名拒绝、相同载荷重放/异载荷冲突、前comparison CAS、历史读取/新特征使旧输入失效、COMPARE权限拒绝、当前源授权版漂移使消费失效、缺事件表/最后权限失效全回滚、候选丢行、错误method/packageSHA及空/非空逆迁移。
- v880/v883独立结构均退出0；v884证据更新前及v885后置治理均退出1仅TEST_EVIDENCE_STALE，47/41一致。原件见comparison-v885-post-verify.log；补记本结果后的复核另存comparison-v886-final-verify.log。不覆盖旧全量报告冒绿。

真实采集原件仍经既有prepare及原件适配验证。H2主体和授权端口只在测试目录作机械C；本轮没有新来源启用、正式主体发行、模型调用、DM8迁移/恢复、真人确认或专业B。父专业对照任务不结案。

## 剩余 Worker 接线的真实技术边界

ProductWorkerAccess/Delegations当前绑定WRITE/TASK_WORK及con3_search_input、QUERY源用途；ProductComparisonScopeGrants当前明确只签HUMAN源范围Grant。它们不能直接变成Worker COMPARE/COMPARISON授权，也不能把服务JWT或伪造人类JWT塞入人类读取链。

后续应在原对照D §5/6下闭合精确comparison/inputSha/候选的服务授权、委托、Source现时用途和围栏绑定，再接产品计算→矩阵校验→业务提交→持久化/消费。该缺口是尚未交付的工程适配，不是等待业务方提供外部资料。本输入切片未签发Worker租约、未写矩阵、未把空回调或QUEUED称COMMITTED；已完成Task/围栏/Source原件能力继续复用。真实部署I与模型字段入模许可仍按实际范围取证，不能由本轮C放行。
