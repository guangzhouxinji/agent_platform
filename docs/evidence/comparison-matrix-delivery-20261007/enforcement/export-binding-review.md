# FIX-ENF-EXPORT-READ-BINDING-01 专项执行及代码复核证据

日期：2026-10-07。本文仅保存本次执行/复核证据，不是任务或门禁状态台账；主计划由主会话维护。

## 边界与依据

- 依据主计划既有 FIX-ENF-EXPORT-READ-BINDING-01 及 `docs/adr/agent-runtime-product-binding-20261005.md` 的“导出与案件原件消费限定D”。核对了既有 DocumentDraftContract.ExportArtifact/Receipt 及客户端原请求围栏。
- 本次产品代码/测试仅写四个文件：EnforcementDocumentDraftV2Page.tsx、enforcementDraftExportBinding.ts、enforcementDraftExportBinding.test.ts、EnforcementDocumentDraftV2Page.export.test.tsx（均位于 frontend/src/features/workflows/）。已有绑定/恢复测试只读取和运行，没有修改。
- 没有修改共享 API/types、主计划、ADR、咨询或裁决文件。没有创建其他任务、读取正式过期对象、启动业务服务、请求模型或正式数据库，也没有使用/修改主会话后端的可变 DB。
- 用户提供的 verify-before：仅 TESTS_FAILING，基线47/41一致。这是主会话提供的前置结果，本执行者未重跑、未独立验证该全量结果。

## 代码反查结论

1. submitWrite 在既有 assertDraftWriteReceipt 后，对 EXPORT 额外核验原 path/key/body 的 draftId/requestId/revision/contentSha，以及合法 exportId。失配继续原未知写入围栏，仅允许原 path/body/key 对账；不会提前宣告提交确认。
2. 有效回执到达后，既有 recovery.confirm 在任何 GET 前清除未知写入。保存原 EXPORT 回执和原稿件最小坐标/manifest 的内存快照，不增加 API 或持久化字段。
3. 元数据 id/draftId/revision/contentSha256/requestId 必须同时匹配原回执/原稿件；manifest 使用现有同域比较合同，包含组织/类型、案件/模板/规则、证据/法律引用及可选 conduct/penalty。artifactSha256 必须合法。失配在文件 GET 前拒绝。
4. 只有文件原件实际 SHA-256 与 metadata.artifactSha256 相等且上下文仍有效，才创建 object URL、触发浏览器下载。metadata/file/arrayBuffer/digest 每个异步阶段均检查上下文；页面实际交付前再核上下文。
5. POST 确认后的元数据 GET、文件 GET 或 SHA 验证失败保留原 exportId 的仅重读入口；恢复路径没有 POST 依赖。未交付期间不允许另发导出代替恢复。原服务权限核验、审核/签发规则保持不变。
6. 身份失效、退出、文书/输入上下文切换和卸载撤销旧消费上下文；失权/退出清除敏感内存。有效上下文的访问拒绝仍沿原页面处理，不用历史回执授予权限。
7. 页面 hard220：最终源文件正好220行；哈希及其他文件行数见 export-binding-source-sha256.json。

## 实际执行

工作目录：D:/daima/xinji/agent_platform/frontend。

首轮命令：

```text
npx vitest run src/features/workflows/enforcementDraftExportBinding.test.ts src/features/workflows/EnforcementDocumentDraftV2Page.export.test.tsx --reporter=verbose
```

原始输出：export-binding-vitest-first.log；2个文件、63个用例通过。

最终专项及受影响域回归命令：

```text
npx vitest run src/features/workflows/enforcementDraftExportBinding.test.ts src/features/workflows/EnforcementDocumentDraftV2Page.export.test.tsx src/features/workflows/enforcementDraftReadBinding.test.ts src/features/workflows/EnforcementDocumentDraftV2Page.binding.test.tsx src/features/workflows/enforcementDraftRecovery.test.ts src/features/workflows/enforcementDraftReceipt.test.ts src/features/workflows/EnforcementDocumentDraftV2Page.recovery.test.tsx src/features/workflows/EnforcementDocumentDraftV2Page.test.tsx --reporter=verbose --reporter=json --outputFile.json=../docs/evidence/comparison-matrix-delivery-20261007/enforcement/export-binding-vitest-final.json
```

真实命令退出码0，8个文件、164个用例全部通过，无跳过。新导出测试为44个helper用例及20个页面用例，共64个；其余100个是现有执法页面/绑定/回执/恢复专项回归。

原始输出：export-binding-vitest-final.log；Vitest原生机器报告：export-binding-vitest-final.json；最终四文件 SHA-256：export-binding-source-sha256.json。上述结果不由人工改写测试报告生成。

## 验证性质与留给主会话的事项

- 本次是 Vitest/jsdom 客户端合同及页面接线验证。请求/身份/元数据/文件是独立测试替身，原件 SHA 使用 node:crypto 的实际 SHA-256 运算；测试字节不是正式业务 DOCX，也不是获准替代业务数据或正式审核证据。
- 专项包含元数据各坐标失配、全部manifest维度、非法artifactSHA、文件SHA不等、读取失败后的GET-only恢复、重复读取失败、错误回执保留原键围栏、失权/退出清理、卸载后的迟到元数据和迟到摘要。原有审核与业务规则没有新增推断。
- 按分工未运行构建、前端全量或 verify-all；最终集成类型检查/构建、全量测试、verify-after及主计划状态/证据链接更新由主会话统一执行。
- 未执行真实浏览器业务下载、真实后端集成、正式身份/授权、模型/DB或专业/业务验收；本结果不能证明这些门禁通过。
