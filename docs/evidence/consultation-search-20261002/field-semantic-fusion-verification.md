# 字段语义融合与来源C（2026-10-02）

同IMP-CON-SEARCH-02、docs70 §4.6/docs02 §67。PatentFieldSemanticFusion对每字段成功召回独立cosine排序，等权K60 RRF；保留各贡献字段cosine/rank/原件locator，绑定完整所选模型版本。输入原件投影须完整所选字段与定位；重复/未知/缺字段返回/错文献版/SHA/定位/模型/非法cosine/超过1000候选失败。真实未入某字段TopK仅该字段贡献0，缺字段成功返回键则失败。

PatentHybridQuery新显式fuseFields和HYBRID_FIELD_RRF/v1，SemanticMatch语义字段不可变，semantic-only保留实际字段不借TITLE；旧七参数构造及fuse方法仍旧版，两种方法的字段来源形态不能混用。

v366五套14项0失败/错误/跳过：领域新5、旧Hybrid2、BM25F1、产品排序1、执行5。检查等权/非量纲混加、稳定并列、原始分数/排名/定位、截断和union总数、未入单字段TopK分母不缩小、空的成功返回/缺返回区分、版本/模型/原件错误及旧TITLE兼容。

这些文本/分数是明示机械组件C，不是采集来源、产品ACTIVE、真实ONNX链、专利Benchmark或专业金标；正式运行v357/Task仍TITLE。下一接入真实配置模型字段召回/完整重排/版本结果，不套旧TITLE置信阈值。
