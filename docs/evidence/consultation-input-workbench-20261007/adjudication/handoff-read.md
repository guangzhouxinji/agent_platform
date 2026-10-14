# FIX-ADJ-REPORT-HANDOFF-READ-01：限定读取消费修复证据

日期：2026-10-07。任务准入来自 docs/08 已登记任务及 docs/adr/agent-runtime-product-binding-20261005.md 末尾限定技术 D。本文件仅记录本切片代码与实际验证，不维护并行任务状态或替代主计划。

## 本次代码

- `frontend/src/features/adjudication/AdjudicationWorkspace.tsx`：普通 load 使用抽出的同一机械绑定；Agent 交接追加同案 originals GET，所有读取通过后一起提交 view/history/originals/head。保留现有报告目标、当前可写头、修订确认和焦点规则。复用 run 的 epoch、错误/失权处理，再以身份/案件/工作版/输入版/机构/范围/历史上下文快照隔离旧回调和异步提交；失权、卸载或新读取不会复活旧正文/错误。
- `frontend/src/features/adjudication/workspaceReadBinding.ts`：组合既有 bindCaseRead 与 bindOriginalRead，不另定义业务合同；交接检查沿原精确 ReportHandoff，并保持 WorkArea 的同机构/范围/输入上下文限制。读取当前头，不把确认历史版当作可写头。失败撤下整组展示，恢复仍使用已有 GET 刷新入口。
- `frontend/src/features/adjudication/workspace.agentHandoffRead.test.tsx`：45 个新独立用例。包括真实 WorkArea/AgentTaskPanel → Workspace → Reports 组件接线（只 mock 网络/身份/摘要；不发真实 Case 或模型请求），原件正文与历史原件 SHA、来源坐标、跨案子记录、非法历史、报告定位/焦点、取消未保存稿、失权、迟到 GET/摘要、切案覆盖防护、目标清除及仅 GET 恢复。

父 IMP-AGENT-ADJUDICATION-01 的 Case/Report/人审及专业报告生成方法未改变；无新 POST、无自动重生成、无后端/API/共享 contracts 修改。未 reset 脏工作区。没有写主计划或修改任何既有测试；用户允许必要修改的 workspace.agent.test.tsx 在此目录实际不存在。

## 实际验证

工作目录：`frontend`。

```text
npx vitest run src/features/adjudication/workspace.agentHandoffRead.test.tsx src/features/adjudication/workspace.binding.test.tsx src/features/adjudication/WorkArea.test.tsx src/features/adjudication/originalReadBinding.test.ts --reporter=default --reporter=json --outputFile.json=../docs/evidence/consultation-input-workbench-20261007/adjudication/handoff-read-vitest.json
```

最终：4 文件 / 130 项通过 / 0 失败 / 0 跳过；其中新专项 45 项，workspace.binding 35 项，WorkArea 19 项，originalReadBinding 31 项。真实网络未调用。实际输出及 JSON 分别是 `handoff-read-vitest.log`、`handoff-read-vitest.json`。

此前第一轮：127 通过 / 1 失败，失败为新切案测试仍停留在报告 tab 时查找案件名称；仅修正该新测试的等待和 tab 操作。原始失败报告保留为 `handoff-read-round1-vitest.*`。第二轮 128 项通过原件保留为 `handoff-read-round2-vitest.*`；随后追加两项等待交接后切案/切输入清目标测试，最终 130 项通过。

额外必要兼容专项：

```text
npx vitest run src/features/adjudication/workspace.handoff.test.tsx --reporter=default --reporter=json --outputFile.json=../docs/evidence/consultation-input-workbench-20261007/adjudication/legacy-handoff-vitest.json
```

实际：10 通过 / 4 失败，退出码 1。原始输出保留 `legacy-handoff-vitest.log`、`legacy-handoff-vitest.json`。不能声称所有既有专项通过。

## 尚需集成处理的缺口

1. 既有 `workspace.handoff.test.tsx` 用同步 boolean 记录 onOpenReport，直接同步断言成功/取消；当前真实合同需要等待原件 GET 与 SHA，因此合法目标分支返回现有 WorkAreaProps 已允许的 Promise<boolean>。其前两项失败为 Promise 对 boolean 断言；后两项立即点击切案/输入刷新时读取 busy 尚未结束，按钮仍被禁用。需该既有测试的授权维护者调整异步等待及夹具；本次禁止修改此文件，保留失败如实交接。新专项已异步覆盖上述四项行为并通过，未为迁就同步测试绕过原件校验。
2. 未跑全量 Vitest、build、TypeScript 构建、verify-all 或全仓 structure 检查。只按 scripts/verify-structure.mjs 的原行数规则核对本次三个源码文件 hard limit；结果和源码 SHA 见 `handoff-read-source-snapshot.json`，不等同结构门全通过。
3. 本切片不证明真实身份/来源准入、专业方法、浏览器 E2E 或业务验收。用户明确禁止更新主计划，集成者应依据本实际证据回填唯一控制记录；本文件不替其关闭任务或父任务。
