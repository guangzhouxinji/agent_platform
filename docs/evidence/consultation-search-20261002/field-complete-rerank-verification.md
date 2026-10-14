# 完整字段重排接线C（2026-10-02）

同IMP-CON-SEARCH-02、docs70 §4.4/4.6及docs02 §64/67。PatentFieldRerankedQuery仅接受HYBRID_FIELD_RRF/v1与精确候选文本集合，只调用scoreComplete；全部有限回执基数/身份/模型一致后稳定排序，方法版HYBRID_FIELD_RRF_COMPLETE_CE/v1及模型版绑定完整规则/W。旧TITLE阈值不适用于字段RRF，没有把负logit当概率或强制截断至0..1；上游截断保留。

v367八套26项0失败/错误/跳过，含实际固定ONNX两项未跳过；新字段接线4项机械C覆盖全窗方法、负logit不偷套TITLE阈值、稳定并列/字段来源、缺/重复/未知/非有限回执、旧方法/多余文本/不支持provider拒绝，旧领域/产品TITLE受影响回归通过。

模型C仍是明确机械文本算法验证，非真实专利Benchmark；产品字段模型查询/结果版本/持久化/恢复尚未接入，运行v357/Task仍TITLE，HUPD未进入模型或检索。
