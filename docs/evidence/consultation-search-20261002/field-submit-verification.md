# 最终代码字段请求、围栏提交和结果消费C

日期：2026-10-02；任务：IMP-CON-SEARCH-02；依据：docs/70 §5.4、docs/02 §69。

本次直接验证ProductPatentSearchExecution.execute实际经过显式字段Source批、精确号码或字段Hybrid/完整重排、正式publish围栏事务、persistFields、完整字段事实重读、正式Page与同键重放。不是仅调独立存储接口或空业务回调。成功分支实存成员/范围/定位/模型/分数/回执，租约COMMITTED；缺字段事实表时整个事务回滚，五类结果表均0、租约仍ACTIVE。错误token、当前Source字段撤回、撤权Worker均拒绝，历史不可变事实保留。

验证输入与等级：明确机械C。H2应用真实产品DDL，权限/来源与模型返回为组件故障替身，文本为机械投影，不创建真实产品Source或正式人员，不称真实专利、真实模型推理、Benchmark或业务I。生产代码没有新增测试profile、固定候选或旁路。

实际轮次：v388缺测试类型导入、v389错误包导入导致编译失败；v390新字段成功提交与页消费已经执行，但测试在撤回异常后复位替身时意外调用抛错，改为doReturn复位。v391六套17项通过；扩展Hybrid完整CE实际同事务后，v392六套18项0失败/错误/跳过。日志在backend/apps/platform-api/target/field-submit-v388.log至v392.log。verify-all-v392-pre-record退出0，47文件/41需求基线未变。

限制：运行仍v357，V084只完成隔离DM8迁移/反迁移C，尚无正式新字段业务I或含真实字段结果的物理恢复/Benchmark；不得以本机械提交C升级不合格来源。
