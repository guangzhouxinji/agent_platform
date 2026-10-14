# FIX-ADJ-ORIGINAL-READ-BINDING-01 专项实现与测试证据

日期：2026-10-07。范围依据主计划已登记任务及 docs/adr/agent-runtime-product-binding-20261005.md「导出与案件原件消费限定D」。本文件仅为实现/测试证据，不是任务状态台账；主计划和ADR未修改。

## 实现

- originalReadBinding.ts 校验 Source 的 id/version/locator 非空、sha256 为64位小写十六进制、kind 为既有四种类型，以五项完整坐标检查重复和当前 CaseInput 来源精确存在。
- 允许其他合法历史 CaseInput 来源，也允许不同坐标拥有相同正文SHA。不从来源ID推断案件归属，不替服务器签署来源许可或权限。
- 每一项原文非空，调用已有 digest 对未经trim/换行转换的 UTF-8 原文计算 SHA；任何历史或当前原件错误都拒绝整组新读取。
- Workspace 在 case/history 绑定及全部原件绑定完成后统一提交展示。异步摘要成功及异常路径均重新检查 epoch；过期响应不提交正文、URL或错误，也不清空较新案件。
- 原件错误清除案件/历史/原件/报告定位展示；已确认写入回执不转为未知写入，仅通过原回执版本GET恢复，不重POST。
- 既有三个 Workspace 测试文件的机械空 originals fixture 已改为实际原文 + 真实 UTF-8 SHA + 对应 CaseInput 来源。既有反例目的和断言保留；空 originals 仍用作明确负例。

## 直接修改文件

1. frontend/src/features/adjudication/AdjudicationWorkspace.tsx
2. frontend/src/features/adjudication/originalReadBinding.ts（新增）
3. frontend/src/features/adjudication/originalReadBinding.test.ts（新增）
4. frontend/src/features/adjudication/workspace.binding.test.tsx
5. frontend/src/features/adjudication/workspace.test.tsx（必要fixture修复）
6. frontend/src/features/adjudication/workspace.handoff.test.tsx（必要fixture修复）

未修改共享 api/contract、咨询/执法、主计划/ADR、requirements；未使用正式对象、真实模型或正式DB。

## 实际专项测试

运行目录：D:/daima/xinji/agent_platform/frontend

```text
npx vitest run src/features/adjudication/originalReadBinding.test.ts src/features/adjudication/workspace.binding.test.tsx src/features/adjudication/workspace.test.tsx src/features/adjudication/workspace.handoff.test.tsx --reporter=verbose
```

最终结果：退出码0，4文件通过，89用例通过，无失败或跳过。逐用例原始输出：FIX-ADJ-ORIGINAL-READ-BINDING-01-vitest-final.txt。首次专项原始输出：FIX-ADJ-ORIGINAL-READ-BINDING-01-vitest.txt（4文件/79用例，随后追加10个用例）。

独立helper测试使用 Node WebCrypto 调用未mock的既有 digest，Node createHash 仅用于构造独立预期SHA。Workspace fixture 使用 Node createHash 模拟服务器原文SHA；延迟摘要mock仅用于隔离异步epoch，不作为产品摘要替代路径。

覆盖：合法当前/历史来源、共享SHA的不同完整坐标、五坐标分别不匹配/非法、重复、空列表/非列表/非法记录/非法原文、中文emoji UTF-8、空格及CRLF漂移、历史原件损坏、整组撤下、历史快照选中来源、摘要基础设施异常、迟到成功/失配/异常、新案隔离、锁定/卸载、确认回执保留及仅GET恢复，以及原有命令绑定/报告交接测试。

## 未执行的验证边界

按主会话要求未执行构建、全量前端、verify-all；未启动后台、真实模型、正式DB或浏览器运行验收。集成类型检查/构建、全量与verify-after、主计划退出状态由主会话统一办理。本证据仅证明列出的工程专项，不宣称正式业务/专业验收。
