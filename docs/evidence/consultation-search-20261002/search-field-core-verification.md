# 多字段查询内核技术复核（2026-10-02）

任务仍为IMP-CON-SEARCH-02；设计依据docs/70 §2.2、docs/02 §57。没有新增Source用途、身份、状态、权限、API或保留规则。

## 实现与反查

- PatentQuerySyntax.parseFields：TITLE/ABSTRACT/CLAIMS/DESCRIPTION已准入集合非空校验；显式未选字段拒绝；无字段操作数在所选字段内OR，短语保持同字段。PATENT_QUERY_FIELDS/v2与原PATENT_QUERY/v1分开。
- PatentQueryExpansion.expandFields：沿原QUERY_EXPANSION/v1词典，仅正向词项扩展；新版Plan绑定字段语法版。没有修改任何旧查询标签、阈值或词典关系。
- PatentQueryRetrieval.prepareFields：先检查文献身份、SHA、字段映射及去重，再投影所选字段、共同硬AST过滤与BM25F；不把原始文献池的未选字段返回后续消费者。不发行或代替权限；当前字段集合必须由产品请求层逐字段核准。
- 原parse/expand/prepare入口与TITLE历史语义保持原版；新入口是最终字段能力内核，不是开发身份/profile/产品旁路。当前正式产品路由仍拒绝未准入字段；本切片不冒完整字段路由已接入。

## 实际命令与范围

Java21/Maven定向：PatentFieldQueryTest、PatentQuerySyntaxTest、PatentQueryExpansionTest、PatentBm25fQueryTest、PatentQuerySyntaxBenchmarkTest共16项、0失败/错误/跳过，实际退出0；日志search-field-core-directed.log。新增8项是机械查询约束C，其构造文本不是专利来源、业务结果、专业标签或Source准入证据。

受影响consultation模块完整回归97项、0失败/错误/跳过，退出0；日志search-field-consultation-regression.log。未把本模块回归说成新全后端回归。

产品调用兼容定向在apps/platform-api及依赖实际编译后，ProductTaskGoalSchemaTest、ProductTaskGoalContinuationTest、ProductPatentSearchExecutionTest、ProductPatentSearchRankingTest共9项、0失败/错误/跳过，退出0；依赖中的上述16项亦通过。日志search-field-product-compatibility.log。这些原产品C含受控存储/模型组件，不冒正式DM8、真实模型或HTTP I；原v330正式TITLE三轮I和原模型/全量证据仍按原记录复用。

原不可变6查询TITLE语法Benchmark回归保持Recall@10/nDCG@10/空集正确率=1、Precision@10=.30、100次一致；没有用HUPD候选字段进行检索/Benchmark。没有用当前已到期来源/Task/Grant生成新正向业务证据。

核查结果：TITLE原排序/文献池/实际扩展一致，新版本计划明确区分；仅ABSTRACT、跨字段AND、同字段短语、负项和扩展边界、字段投影、非法范围、精确号码、空池与坏文献/重复版本全部通过。专业/来源用途准入和完整多字段语义、API、持久化/恢复尚未验收；当前任务不结案。
