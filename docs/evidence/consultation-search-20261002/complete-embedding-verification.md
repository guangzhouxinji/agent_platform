# 长字段完整token表示纠正C（2026-10-02）

对应IMP-CON-SEARCH-02，D依据docs/70 §4.3、docs/02 §63。不改变Source权利/用途/身份或旧TITLE版本，不新增库/迁移/配置旁路。

最终领域模型端口新增显式embedComplete，缺能力默认失败。正式ONNX适配以固定tokenizer全部非重叠窗口/stride0、每窗口旧MEAN_POOL_L2、正文token加权mean再L2，8192窗口预算及完整形状/有限值/维度/权重/零范数校验；无partial向量或旧embed回退。非TITLE索引及查询都使用完整操作，新模型版绑定表示规则/W/maxTokens/D/dimension；旧TITLE模型版、嵌入和历史数据不改。

## 实际执行

complete-embedding-directed-v354.log初轮27项0失败/错误/跳过，退出0。v355补不支持完整操作的provider零写/无回退和字段模型版反例，6套28项0失败/错误/跳过，实际退出0：CompleteEmbedding3、真实OnnxProvider2、Controller2、FieldVectors6、SemanticIndex10、SearchExecution5。精确XML原件已复制本目录，未用零执行冒通过。

本机实际固定模型SHA98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f、tokenizerSHA2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8；原多语言正反和新增机械长文本测试实际运行。完整性/SHA按正式provider核验，没有换模型/摘要以过关。

机械输入前段重复battery，后段重复coating，用于检验截断与全窗口，不是采集专利/专业qrels。v355实际：短文本新旧cos0.999999999971；前段/旧长文本cos1.000000000000；与尾部语义coating的旧cos0.106859604331、新完整cos0.694547589116；完整norm0.999999999007；重复执行向量确定性及旧TITLE原值保持。预先断言尾部影响>0.05没有修改，不把该机械结果作为专利质量指标或调高现有Benchmark。

verify-all-v355-code.log真实退出0，47/41基线不变。当前新增实现仅C，真实固定模型C不等于正式HTTP/DM8字段查询I，HUPD仍候选且不参与模型/检索/Benchmark。当前运行v342，后置完整回归/正式发行待本任务继续；不冒新恢复I、生产部署或业务验收。
