# 字段执行接线与重放范围验证

日期：2026-10-02。任务：IMP-CON-SEARCH-02。依据：docs/70 §5.4、docs/02 §69，已通过的字段整链D；不重开基础协议。

执行器复用正式 Worker/Task/围栏 publish，显式字段 Scope 选择旧 TITLE 链或完整字段 Source批、逐字段索引、语义融合/完整CE和 persistFields。当前结果消费的 Source 守卫按v3所选字段核 QUERY、同一Active快照及历史来源决议/epoch与独立现时水位，旧TITLE不读取V084。

实际失败与修复：

- field-execution-v378.log：24项、4失败；持久输入漏字段以参数异常而非状态异常拒绝。
- field-execution-v379.log：24项、4失败；改变持久输入字段后，原请求SHA未变化，同键重放没有核原Scope。
- 修复并保留原拒绝断言：非法持久字段封装为状态异常；重放必须核原Attempt的scope SHA与本次来源、文本字段、实际过滤一致。
- field-execution-v380.log：六套24项、0失败/错误/跳过。
- verify-all-v378-resume-pre.log因上述真实测试失败退出1；verify-all-v380-pre-record.log修复后退出0，需求基线仍47文件/41需求。

上述日志位于 backend/apps/platform-api/target。验证仅C：旧TITLE真实采集内容及机械授权/围栏故障回归、字段排名/事实/批/存储组件。未执行正式新字段Worker事务，不以编译或旧TITLE回归冒字段产品I。当前运行v357，Task仍TITLE，HUPD未启用；继续字段页消费/只读历史恢复和V084适用物理验证。
