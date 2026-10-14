# 字段历史恢复证据校验

日期：2026-10-02；任务：IMP-CON-SEARCH-02；依据：docs/70 §5.4、docs/02 §69。

最终恢复实现：v3分支复用外层Task/Input/历史执行围栏/Worker/请求/选择/回执关系检查，载入FieldEvidencePersistence验证父Root与完整摘要。重核Input字段及实际过滤精确并集、Source集合、scope、实际规范查询和扩展原件；每个成功来源重读历史manifest/records/coverage与对应字段原件，不使用现在的ACTIVE权限恢复历史。原件版、采集版、SHA、逐字段定位必须相同，词法score/rank/terms/fieldScores按原输入重建，K60聚合和RRF融合按记录事实核对。

语义历史核注册模型的完整字段表示版、维数/窗口、历史Source决议/epoch、执行前build、全原件向量绑定/定位/维数/数值和build SHA。不重新推理修改机器输出；本轮没有声称重建真实模型质量或法律结论。独立现时Grant/Source水位仍由既有恢复命令前后核验，本分支不启用来源、不复活撤权、不写历史。

备份范围核查：scripts/backup-consultation-container.ps1 使用正式专用备份身份执行 dexp SCHEMAS=CONPROD，全schema包含V084两表，不另设数据库或平行备份台账；本轮未生成含字段结果的正式物理包，不计物理恢复I。

验证：field-recovery-v383.log五套22项通过；v384六套24项中模型机械H2一错误，原因是测试内存库在连接关闭后消失，补DB_CLOSE_DELAY=-1；v385六套24项0失败/错误/跳过。覆盖字段历史精确事务正例、字段/扩展/coverage/原SHA篡改、错误epoch、晚于执行的build、错build摘要、错字段locator、坏向量、旧TITLE恢复回归。真实HUPD仅原件投影/定位历史闭包C，未查询/模型/启用。精确号码在机械C中无模型执行，普通查询缺模型拒绝。verify-all-v385-pre-record.log退出0，基线47文件/41需求未变。后置verify因独立轨道修改触发TEST_EVIDENCE_STALE，全量v386正在执行；不将未完成全量记通过。

限制：当前运行仍v357，V084未正式物理应用。上述为C，不是正式字段Worker I、字段物理联合备份恢复或真实Benchmark；继续当前任务目标确认接线及适用新迁移物理取证，合格真实来源准入仍独立。
