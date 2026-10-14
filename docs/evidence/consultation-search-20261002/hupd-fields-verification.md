# 原生多字段内容投影C（2026-10-02）

对应IMP-CON-SEARCH-02，D依据docs/70 §3.3、docs/02 §60。内容格式适配不是新增产品流程，未增加身份、路由、用途、迁移或来源启用。

ProductCapturedPatentFields只映射TITLE/title、ABSTRACT/abstract、CLAIMS/claims、DESCRIPTION/full_description；逐原件SHA及顶层字段范围重核，仅所选内容和定位投影，固定字段次序确定锚，所有map不可变。空/未知/null集合、缺/空/非文本所选原字段、错SHA/locator全部拒绝，不从其他字段回填。原生适配仍先整包核验及源/采集版/manifest/records绑定；旧read固定TITLE，正式SourceBatch和Goal仍仅允许旧准入字段。

实际输入是20条已采原件；新增6项C穷举15个非空字段组合并逐项按字节切片解码与原值比对。原件损坏反例只修改已采记录的副本，重建错误字段范围以检验拒绝；不是合成业务数据、Source激活、请求结果或Benchmark标签。

## 实际测试

- backend-regression-v343.log首轮全量缺真实环境输入：API693项、0断言失败/20配置错误/117跳过，实际退出1；不计通过。
- 使用已存在run-search-regression-with-product-inputs.ps1加载实际执法浏览器证据、正式部署数据库权限和一次性真实DM8 schema，backend-regression-v343-product-inputs.log实际退出0；schema TSTE8EB3EC3已由具名既有脚本清理，只移除本次测试数据，没有删除产品schema/实例/卷。缺配置不是业务代码问题，不改测试跳过策略或伪造输入。此全量运行编译的是字段修改前代码，不冒字段后置全量。
- hupd-fields-directed-v344.log首次测试编译失败：测试误用生产private ranges函数；没有修改生产可见性来迁就测试。失败原件保留。
- 测试自身流式定位修正后，hupd-fields-directed-v345.log实际退出0，8套32项、0失败/错误/跳过：SelectedFields6、SourceDocuments4、SourceInventory7、RegistryVerification1、SearchExecution5、SearchCitations2、CitationRecords3、CitationFileVerification4。
- verify-all-v345-code.log实际退出0；47需求基线文件、41台账一致。此前V9.448 TEST_EVIDENCE_STALE及V9.449 TESTS_FAILING日志保留，由真实回归和受影响新代码测试恢复绿色。

## 未完成边界

新增内容源码仅C，实际产品运行v342还未包含本切片。HUPD仍候选、有三份许可差异；未用其检索/Benchmark、未开放新字段正式请求或模型。字段感知索引、结果逐字段引用、请求版本绑定和持久化/恢复仍需在当前任务内完成。既有TITLE性能/正式I证据是对应旧发行时点，本修改不直接继承其性能结论。
