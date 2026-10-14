# 逐字段引用/历史兼容C（2026-10-02）

同IMP-CON-SEARCH-02、docs70 §5.3/docs02 §65。新增Citation不可变fieldLocators、显式readFields现时逐字段QUERY/完整Active快照一致校验，原生内容按所选字段读取并核documentVersion/rawSha/anchor/matchFields；旧采集格式非TITLE拒绝。旧read及旧构造仅TITLE，空映射JSON省略，旧JSON读回相等，不重写历史。

实际定向v361退出0：ProductHupdSelectedFieldsTest 9、ProductPatentSearchCitationsTest 2、ProductPatentSearchExecutionTest 5、ProductSourceResultSchemaTest 1，共17项0失败/错误/跳过；原件内容C逐条核20真实原件×15组合的引用定位/版本，旧JSON/新JSON往返；权限/不同epoch/撤回以机械故障组件C验证，不注册测试ACTIVE或业务身份，不冒正式产品I。

原件HUPD仍候选，许可冲突未改变，仅允许内容完整性适配，不参与检索/模型/Benchmark。新字段Task正式请求及结果摘要/持久化/恢复尚未整链接入，因此正式产品查询仍TITLE，运行v357未替换。

另只读研究allenai/us-patents，冻结revision19aea30f9ca32bfc8f0e05c445a384da4e758aa9。作者README声明ODC-BY且intended research/educational，实际合同仅corpus_id/filing_date/patent_type/拼接text；corpus_id无语义、原文经去重/段落删除，未声明原始专利ID或原样Claims/Description。因此本轮不下载数据、不注册/启用，不从拼接text猜专利标识或原文，不以API license标签直接放行。依据作者冻结README及HuggingFace元数据，不作法律结论。该候选不影响独立字段产品代码继续。
