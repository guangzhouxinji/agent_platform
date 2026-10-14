# 字段目标确认与V084真实DM8迁移

日期：2026-10-02；任务：IMP-CON-SEARCH-02；依据：docs/70 §5.4、docs/02 §69。

最终目标确认：QUERY/SEARCH共享ProductPatentSearchFieldScope，requestedFields须精确等于所选非空文本字段+实际过滤字段；不强制TITLE。按所选范围解析语法/扩展，仍走正式Task授权、同库事务、任务版本、跨轮、原输入保留/围栏。COMPARE既有语法不扩大，新字段的专业对照仍须后续任务依据。

v387实际定向执行：ProductTaskGoalContinuationTest 2项（旧TITLE与ABSTRACT/CLAIMS确认、持久输入字段、同Task继续、旧Input/围栏/中途撤权拒绝）；GoalSchema 2、SourceResultDm8 1、SourceResultSchema 1、SearchExecution 5、FieldRecoveryModels 1、FieldRanking 6。七套18项、0失败/错误/跳过。日志：backend/apps/platform-api/target/field-goal-physical-v387.log。

新物理证据：配置现有5301 DM8，通过已具名临时schema的SourceResultDm8测试实际执行V084，读取con4_search_field_scope和con4_result_field_evidence，再执行U084；依赖DDL仅为准备前置schema，不重做身份/Task业务验收。测试finally清理自建子schema，外层日志明确cleanup TSTD9C7967F；不删除正式schema/容器/卷。该证据仅迁移物理C，不是正式主库V084应用、含实际字段结果的联合备份/恢复或业务I。

后置verify-all-v387-pre-record退出1，计划健康报TEST_EVIDENCE_STALE：其他独立域业务文件又晚于最新Java报告。此前全量v386为310套987项0失败/错误/39条件跳过，不扩大为覆盖随后修改。需求基线47/41未变；不改其他域代码、不降低检查标准。当前正式运行v357、Source用途仍未开放新文本字段，HUPD许可冲突候选仍不启用；继续合格真实来源核查/本任务可执行工作。
