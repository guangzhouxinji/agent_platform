# 多字段Source批快照C（2026-10-02）

同IMP-CON-SEARCH-02、docs70 §4.5/docs02 §66。ProductCollectedPatentTitles显式loadFields一次所选投影及过滤，旧格式仅TITLE；ProductPatentSearchSourceBatch显式loadFields/validateIndexesFields核逐字段QUERY全Active快照、精确文献字段集合。坏内容/任一所选索引不可用排除整源、全失败拒绝、权限/水位错误传播，不借TITLE或静默降字段。

实际v363定向退出0：ProductPatentSearchFieldBatchTest 4、ProductPatentSearchSourceBatchTest 2、ProductHupdSelectedFieldsTest 9、ProductSemanticIndexTest 10、ProductPatentSearchExecutionTest 5，共30项0失败/错误/跳过。真实HUPD20原件ABSTRACT/CLAIMS投影及日期过滤空结果通过；同源错epoch/撤回在读内容前拒绝、一个CLAIMS索引坏则整源排除、无TITLE回退、全源索引坏/权限变化拒绝、缺字段投影拒绝。

权限/索引故障由明确机械组件C产生，未注册测试ACTIVE/产品身份，不冒现时来源准入或真实模型质量。HUPD仅内容完整性C，未进入模型、检索或Benchmark。旧TITLE受影响回归保持。当前正式运行仍v357，Task查询仍TITLE，字段融合/请求/结果摘要/持久化/恢复仍需整链接入。
