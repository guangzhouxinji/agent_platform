# FIX-ENF-SESSION-RESPONSE-01 专项实现与验证原件

日期：2026-10-07。本文件仅记录本次限定实现及工程检查，不建立任务/门禁/需求状态台账，不替代唯一主计划。

## 依据与边界

- 已读取根 AGENTS、主计划任务登记与 R/D 边界、`docs/01`、`docs/09`，及 Runtime product binding ADR 的“会话与Agent报告交接读取限定技术细化（2026-10-07）”。本修复按已登记限定 D 执行，父 IMP-AGENT-ENFORCEMENT-01 的确定性文书、授权、人工决定和专业规则合同不变。
- 已读取 UI skill 的适用规则和相关交互检查。本次仅修响应生命周期，保留既有页面结构、加载禁用与错误反馈，不引入视觉设计或无关重构。
- 按用户本次明确要求，不运行全量、构建、verify-all、迁移；不修改主计划、export helper、共享 API/contracts、后端、requirements 或既有 export 测试夹具。未 reset、未真实登录、未发真实业务请求、未调用模型。
- 需求基线仅以文件集合及 SHA256 进行只读核对：47/47，无新增、删除或摘要变化；原件见 `session-requirement-baseline.log`。这不是 verify-all，也不是语义/专业验收证据。

## 实现

1. 新增独立、组件内存级机械 helper `enforcementDraftSessionHelper.ts`：会话 epoch 独立于文书/输入读取 epoch；登录 token、身份读取及其失败回调逐 await 检查原会话所有权；操作 catch/finally 同时检查会话与操作序号。
2. 页面在退出发起时立即使旧会话失效并撤下本地上下文，而非等退出 HTTP 完成；仍调用原正式退出 API，未改变服务权限。卸载/现时失权也立即失效。当前有效登录/操作失败仍展示，当前操作 busy 仍正常释放。
3. 迟到写入失败不再触碰已清理的恢复对象；现时未知写入原键、原正文对账及已确认导出仅 GET 重读仍沿原实现。基线列表读取的成功提交也增加原会话校验，防止迟到成功复活列表。
4. 页面按 `text.split('\n').length`（含末尾空行）为 219 行，hard220 内；本次未更改已有 export helper 或恢复 helper。

## 实际执行

执行工作目录：`D:\daima\xinji\agent_platform\frontend`；均使用已安装本地 Vitest，不需要网络/真实端口。

独立专项命令：

```text
node node_modules/vitest/vitest.mjs run src/features/workflows/EnforcementDocumentDraftV2Page.session.test.tsx
```

最终专项结果：1 文件、22 测试通过，退出 0，见 `session-specialized-vitest.log`。覆盖登录成功/失败迟到、身份成功/失败/失权迟到、退出 HTTP 尚未完成时的隔离、卸载、有效登录/身份失权、有效操作错误/成功、旧操作不得改新登录 busy、迟到写入成功/未知失败/失权不得复活准备结果或恢复状态，以及 helper catch/finally/并发序号所有权。

必要相关域命令（不递归全域、不跑全前端）：

```text
node node_modules/vitest/vitest.mjs run src/features/workflows/EnforcementDocumentDraftV2Page.session.test.tsx src/features/workflows/EnforcementDocumentDraftV2Page.binding.test.tsx src/features/workflows/EnforcementDocumentDraftV2Page.export.test.tsx src/features/workflows/EnforcementDocumentDraftV2Page.recovery.test.tsx src/features/workflows/EnforcementDocumentDraftV2Page.test.tsx src/features/workflows/enforcementDraftRecovery.test.ts src/features/workflows/enforcementDraftExportBinding.test.ts
```

结果：7 文件、153 测试通过，退出 0，见 `session-domain-vitest.log`。其中已包含上述 22 项，不累计为 175 项。原未知写入对账、已确认导出读取失败不得重 POST、确切版本绑定及迟到导出不交付等原合同回归通过。

运行记录：编写中曾在仓库根目录启动 `npx vitest ...`，无输出后主动中断，退出 1，未取得测试结果；随后改用 frontend 本地 runtime。早期独立专项 19 项通过；新增迟到写入 3 项后重跑最终专项 22 项并保存日志。仅最终可复现运行计入上述结果。

文件内容摘要与行数见 `session-engineering-receipt.json`。

## 未验证 / 交接

- 未运行全量/构建/verify-all/迁移，故不宣称全量集成、完整类型构建或治理检查通过。
- 全部专项为延迟 Promise 和模拟 API/展示端口的工程验证，不是真实身份、业务、模型或专业验收。
- 本次不改唯一主计划；需由有权主集成会话据本证据维护该任务状态，不能把父任务标记为完整产品验收。
- 限定修复范围内没有已知未完成实现；既有真实来源/Grant及父任务业务验收门禁未解除。
