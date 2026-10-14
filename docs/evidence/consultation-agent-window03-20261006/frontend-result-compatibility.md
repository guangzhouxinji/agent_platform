# 本轮前端真实结果离线兼容验证

- 任务：`VER-CON-AGENT-NATIVE-POSITIVE-01`；时间：2026-10-06T13:34:32.215Z。
- 实际结论：**FAILED_COMPATIBILITY_EXPECTATIONS**。
- 仅读取本轮三个保存原件；正向直接调用当前 bindConsultationResult。负例仅变更内存克隆，不覆盖真实输入。
- Node 内置类型擦除后执行原函数；独立 OS 临时目录，无共用 dist、构建输出或前端源码修改；不等于 TypeScript 类型检查。
- 新业务 HTTP / 模型 / DB 调用均为 0；未运行 verify-all、全仓回归或浏览器。
- 当前源码 SHA256：`64cfcf2a574d363cd1243aead2268b349f4ecdcd586627a57a496eeba11eaad6`。
- 真实 Task：`c91aa8d3-0716-4e33-a6b5-2a89461cb1c8`；Run：`afa57612-ab59-3807-9a98-f7e48c704a7a`；Turn：`4f4f6f11-0208-4bc0-b13e-5efe4a4a0b4e`；本页 20 个成员 / 20 个回答引用。

| 检查 | 预期 | 实际 | 结果 |
|---|---|---|---|
| real-current-task-run-result-binds | BOUND_REAL_TURN | BOUND_REAL_TURN | PASS |
| native-answer-equals-workspace-turn | EXACT_NATIVE_ANSWER | compared | PASS |
| real-run-task-input-result-chain | CONSISTENT | compared | PASS |
| wrong-runtime-task | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-workspace-task | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-task | REJECTED_NULL | REJECTED_NULL | PASS |
| missing-run-id | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-nonempty-run-id-only | REJECTED_NULL | BOUND_TURN | FAIL |
| wrong-confirmed-input | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-goal-task-version | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-task-version | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-workspace-task-version | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-result-set | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-result-sha | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-terminal-answer-reference | REJECTED_NULL | REJECTED_NULL | PASS |
| nonterminal-run | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-answer-observation | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-answer-turn | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-result-member | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-result-citation | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-result-citation-same-count | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-answer-citation | REJECTED_NULL | REJECTED_NULL | PASS |
| missing-answer-citations | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-memberId | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-sourceId | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-documentId | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-documentVersion | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-sourceVersion | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-rawSha | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-locatorRef | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-sourceUrl | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-field-locator | REJECTED_NULL | REJECTED_NULL | PASS |
| blank-answer | REJECTED_NULL | REJECTED_NULL | PASS |
| missing-retrieval-observation | REJECTED_NULL | REJECTED_NULL | PASS |
| missing-citations-observation | REJECTED_NULL | REJECTED_NULL | PASS |
| source-and-input-bytes-unchanged | UNCHANGED | UNCHANGED | PASS |

## 范围与未完成项
- wrong-nonempty-run-id-only 实测是否拒绝见检查表。函数无 expectedRunId 入参，Workspace/Turn 无可比 Run ID；不能把上游 AgentTaskPanel 的请求 Run ID 防护当作本函数已经验证。
- AgentTaskPanel 源码有 expectedRunId 与响应 Run ID 比较；本切片不运行其 UI/异步请求链，所以不证明端到端错误 Run 拒绝，也不由本函数结果推断真实跨 Run 数据泄露。
- 真实原件正向绑定仅证明工程离线数据兼容；不核实当前权限、浏览器渲染/交互、专业相关性、模型质量、生产或业务验收。
- Run checkpoint.version 是运行进度版本，不等于 Task/文档/来源版本；不发明其必须相等的规则。本脚本验证 Task 版本及引用 documentVersion/sourceVersion。
- 引用负例是由真实输入构造的内存篡改探针，非真实产品请求或另一个真实 Run；未创建新业务结果。
- 不修改主计划/前端；函数级 Run ID 缺口需主会话决定后续处理。本报告不关闭任务或调整门禁。

文件 SHA、逐项检查及不变性验证见同名前缀 JSON；不输出回答、凭据或来源原文。
