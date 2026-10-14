# 完整文献窗口重排验证（2026-10-02）

范围：IMP-CON-SEARCH-02 / docs70 §4.4、docs02 §64。这是完整重排算法及旧TITLE兼容C，不是多字段正式产品I、专利Benchmark或业务验收；HUPD候选未进入模型。

实现：PatentReranker显式scoreComplete及ALL_DOCUMENT_WINDOWS_MAX_LOGIT/v1；不支持provider默认失败。固定ONNX保持旧score，另用仅切document、stride0、overflow全窗编码，完整query token逐窗一致，先校验全部窗口后实际全部推理/最大有限logit。缺输入/overflow、坏掩码/形状、无正文、8192窗口超预算、长query、任一推理失败拒绝，不返回部分批次。旧TITLE请求链/模型版/弱信号阈值未改变。

实际定向命令：Maven reactor apps/platform-api -am，四套OnnxCompleteRerankingTest、OnnxPatentRerankerTest、ProductPatentSearchRankingTest、ProductPatentSearchExecutionTest；显式传入本机patent-reranker-v1的model_quint8_avx2.onnx/tokenizer.json，模型和tokenizer由产品代码核固定SHA。

`backend/apps/platform-api/target/rerank-complete-v359.log`退出0；四套13项，0失败/错误/跳过。实际固定模型两项未跳过：短pair旧/完整score等值；机械长document完整尾窗score=6.765936374664，旧首窗=-9.433337211609，短pair=5.493449687958；完整两次确定性相等，600-token query明确失败。数字是机械算法反例，不是法律概率或真实专利质量指标。

组件C另核全部窗口执行、最大值不强制0..1、query变化/坏尾窗推理前拒绝、overflow缺失/嵌套/预算、无正文/坏掩码、NaN/尾窗失败及不支持provider无回退。完整Task字段查询、逐字段引用、结果/恢复版本仍待整链接入，不以本C宣布完成。
