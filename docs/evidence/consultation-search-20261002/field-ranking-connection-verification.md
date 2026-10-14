# 最终字段模型排名接线C（2026-10-02）

同IMP-CON-SEARCH-02、docs70 §4.7/docs02 §68。ProductCollectedPatentTitles/SourceBatch携带同次原件投影的不可变DocumentEvidence/逐字段locator，索引失败整源过滤也过滤对应证据。ProductPatentFieldSearchRanking使用成功Source快照/原件字段、逐Source/字段实际searchField并核Source/采集版/文献/locator，硬约束同池做字段RRF、显式完整CE、保存贡献字段模型/原分数/rank/定位。

只拼所选原文/字段标签，无TITLE/未选正文回填；Source/字段响应/结构错误在CE前整体失败，不包装为Hybrid成功。仅完整CE执行异常明确RERANK_FAILED/字段Hybrid/LOW_CONFIDENCE。无字段校准明确FIELD_CALIBRATION_UNAVAILABLE，不套旧TITLE阈值。真实空池无伪造模型输出；成功语义召回为空但词法有候选仍实际完整重排。缺语义rank持久值用null而非NaN，不改变既有有限TITLE值/摘要。

v369十套47项0失败/错误/跳过。新排名5项机械接线/失败C；真实原件批投影携带20条ABSTRACT/CLAIMS精确locator；字段核心/源批/原件/索引/旧TITLE受影响回归通过。机械Source/模型仅组件C，未创建测试ACTIVE身份或正式Source，不冒真实模型多字段I/专利Benchmark。

当前运行v357、正式Task查询仍TITLE。本切片输出事实尚未新结果版本/持久化/恢复绑定，不能据此开放字段请求或宣称全任务完成。下一收口字段请求/结果版本与恢复合同。
