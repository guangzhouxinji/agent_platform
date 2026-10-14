# 91轮事实记录工作流组合隔离验证

归属：IMP-ENF-DOCUMENT-DRAFT-05，依赖既有已过D的事实记录工作流及已完成ENG-ENF-TEST-ISOLATION-79-01；仅测试/工具切片，不新开业务任务或架构。

目标：真实DocumentDraftService、DocumentDraftWorkflow与Dm8DocumentDraftStore组合，验证生成、修订、独立候选/差异、显式采用、历史、CAS/幂等、失败事务及原件/事件/回执一致。Inputs/Access明确为受控测试适配器，只证明真实隔离持久化和事务，不证明CNIPA同案输入、产品授权、浏览器、真人审核或正式业务验收。

隔离：全新5383、随机ENFISO schema、带本轮标签的独立容器/卷、精确TEMP文件根；首次连接前执行现有隔离guard，空schema方可迁移，原有资源拒绝复用。无主库连接/业务写入，5444保持停机，不运行87恢复。只注入进程秘密，不输出凭据。

出口：新鲜独占XML tests>0/failures=errors=skipped=0，逐版原件SHA、DB行/事件/回执对账，缺来源审核拒绝，事务失败只补偿本轮新原件，旧历史/文件不变。失败原样保留；不清理已有历史、不自动删库卷。同类两次无进展转安全队列。

前置verify-all实际1/TEST_EVIDENCE_STALE，47文件/41契约一致；旧20环境errors/119skip不改写。不称全仓通过。
