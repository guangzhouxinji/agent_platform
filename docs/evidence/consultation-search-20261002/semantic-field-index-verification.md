# 最终字段索引维护接入C（2026-10-02）

对应IMP-CON-SEARCH-02，D依据docs/70 §4.2、docs/02 §62。现有维护索引入口接收单字段，缺省TITLE，空值不默默默认；SOURCES维护权限及Source该字段QUERY现时授权/独立水位不变。loadField原生按完整摘要/文献/字段定位投影，旧采集格式非TITLE拒绝；Task查询仍仅TITLE。

ProductSemanticIndex构建/重读按field_name绑定参数，独立向量、采集版绑定与回执，幂等摘要含field且旧TITLE摘要保持。同字段/模型/文献版增量复用，跨字段键冲突；模型/向量/绑定/回执同库事务。没有迁移、库、账号、开发profile或临时provider进入产品路径。

v351基本27项、v352扩展33项通过；v353补真实旧TITLE采集格式缺摘要拒绝后6套34项0失败/错误/跳过，实际退出0：Controller2、FieldVectors6、SemanticIndex9、SearchExecution5、SelectedFields6、SourceDocuments6。完整日志semantic-field-index-directed-v353.log；verify-all-v353-code.log实际退出0、47/41基线一致。

维护路由MockMvc与mechanical actor/provider仅组件C，验证接线/权限拒绝、四字段隔离、回执/增量/跨字段冲突、当前水位变化零写；不冒正式维护HTTP/DM8、真实语义模型/专业质量。原件投影C直接读取真实采集原件，没有Source准入或索引写入。HUPD候选许可差异仍保留，不参与模型/检索/Benchmark。

运行仍v342，本次源码尚未发行。反查当前ONNX adapter旧embed按maxTokens截断；新长字段必须增加完整token窗口表示与独立版本，不能把单首窗口宣称完整字段语义。当前字段索引接线C不代表长字段模型/Benchmark已闭合；继续直接修复本切片，旧TITLE/历史模型行为不改。
