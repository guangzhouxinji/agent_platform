# 必要集成回归修复专项证据（2026-10-07）

依据本轮明确授权：沿FIX-CON-HISTORICAL-RESULT-ISOLATION-01已过D修复测试集成；追加fixture元数据修复沿主会话登记的FIX-CON-TITLE-BUILD-PROVENANCE-01最小D。本文只记录本专项事实，不改主计划，不声称统一未筛选回归01已通过。旧freeze与旧失败原件没有覆盖。

## 修改范围

仅四个授权独占文件：
- ProductHistoricalResultIsolationTest.java：JUnit @TempDir作为测试工作目录和恢复原件根；不再Path.of(null)。属性isolation.evidence.dir仅用于显式请求导出工程自有原件/报告，与测试取数和断言无关。
- ProductHistoricalResultIsolationJdbcTest.java：真实file-H2仍位于JUnit @TempDir；实际DDL/INSERT/重开/摘要对比及报告照常执行。默认工程证据写入自有临时目录；属性isolation.jdbc.evidence.dir只在指定时复制这些工程产物，不控制测试数据根。
- ProductModelTurnRecoveryVerifierTest.java：补齐实际H2搜索回执/来源tuple/完整成员字段与正确持久化摘要；模型回答绑定真正计算的工程结果SHA，不再使用随意固定SHA。正常过期历史proposal/explanation仍要求verify=1、当前Task版本=4，并新增原request/结果SHA保持断言。新增绑定双方一致但存储结果摘要被篡改时仍由生产隔离硬拒绝用例。
- ProductHistoricalResultIsolationFixture.java：getTables只精确报告已设置modelTurns的CON3_MODEL_TURN；CON4_RESULT_TITLE_BUILD和未知表都不存在。新增二状态元数据断言，并在legacy缺模型用例中设置modelTurns=true，证明其它表存在不伪造TITLE出处表。

模型恢复的正常样本为工程自有EXACT_PUBLICATION/v1无语义结果，记录真实成员/原字节SHA/来源tuple/执行时间与v2结果摘要。该合法产品方法本来不要求语义构建，不是用未知method、FAILED来源、硬编码虚假构建或生产旁路骗过隔离；依然走production require。它不是实际三历史、正式许可或当前身份授权样本。没有改任何原正常通过期望为异常期望、没有skip/assumption。

## 编译与测试

- 集成修复01：先在两个属性未设置时独立JUnit运行原三类修复测试，30成功/0失败/0跳过，日志保存在相邻integration-test-repair-01。此结果发生在主会话追加TITLE出处协调前，单独留存不覆写。
- 集成修复02：读取并复制当时current Core、ProductTitleBuildProvenance、ProductObservedTables与其当前EvidenceCodec等生产源码为production-readonly-snapshot，仅独立编译该只读副本与四个测试文件。没有写原生产源码，主会话仍独占生产/V104。
- 使用既有classpath与固定H2依赖，javac --release21 exit=0，输出只在本目录classes；未用共享target、Maven或verify-all。快照版本与修复文件SHA见repair-receipt.json。
- junit-without-properties.log头部明确两个属性=null，实际31成功/0失败/0跳过；含16隔离单元、3实际file-H2、12模型历史恢复测试。Jupiter runner不注入输出属性或额外schema，不过滤这三类中的任何测试，不使用Maven全量注入。
- junit-optional-export.log用于独立检查显式工程导出合同；最终实际计数见该原始日志与repair-receipt.json。导出只到本目录optional-output，未覆盖旧原件。
- 原测试字节在修复01及修复02各自original-tests保留；编译日志、不同运行日志均分别保存，不将旧失败改成成功。

## 限制与接收

本专项仅证明这些测试在标准JUnit临时目录生命周期下不依赖外部属性，并证明追加元数据修复与current Core接线相容。没有执行新的TITLE结果生产capture/store/V104原子回滚验收，也没有执行全量Maven、DM8、真实三历史、当前Issuer/Grant整链或HTTP/UI；这些由主会话全量接续。所有数据为工程自有；没有读取现行/过期保护包或凭据。

交接后四个独占文件冻结，不继续其他实现。本专项实际剩余失败与可选导出最终计数以repair-receipt.json为准；统一未筛选回归01仍保留原实际失败，不宣布其被本专项替换或变绿。
