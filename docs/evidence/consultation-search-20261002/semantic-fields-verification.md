# 字段向量快照读取C（2026-10-02）

对应IMP-CON-SEARCH-02，D依据docs/70 §4.1、docs/02 §61；复用V067/V070字段维度，无迁移/路由/来源启用/新权限。

loadField严格接收单字段文献投影，TITLE/ABSTRACT/CLAIMS/DESCRIPTION之外、空/null或跨字段内容在DB读取前拒绝。SQL对field_name绑定参数并同源/采集版/模型一次加载；逐文献键/版、原件SHA/字段locator/向量SHA、维度与有限L2值核对。旧load固定TITLE，历史恢复仍走该入口，不因新参数重解释旧结果。

## 实际测试与失败原件

- semantic-fields-directed-v347.log：21项、0断言失败/4错误，退出1。新H2结构漏建V067外键父表con4_source_admission，并非产品迁移变更；补空父表结构，没有来源准入记录，不伪造ACTIVE。
- semantic-fields-directed-v348.log：21项、0失败/错误/跳过，退出0。
- 补齐非有限/错维度/不归一化及错模型/源/重复文献反例，semantic-fields-directed-v349.log：4套23项、0失败/错误/跳过，退出0。FieldVectors6、原SemanticIndex6、SearchExecution5、真实原件SelectedFields6。
- 首轮列入不存在的ProductPatentSearchRecoveryVerifierTest没有执行，不计通过；原SearchExecution实际包含结果/选择历史恢复与篡改反例。独立机械存储测试不是物理DM8恢复I。
- verify-all-v349-code.log首次生成project-plan.json碰瞬时Windows文件锁UNKNOWN/open，失败原件保留；verify-all-v349-code-retry.log真实重试退出0，47/41需求基线不变。未改生成器或检查标准。

字段存储测试使用明确机械向量/H2，仅证明存储隔离和完整性，不冒真实语义模型、Benchmark、业务专利结果或正式身份。产品代码未增加测试provider/profile/数据候选；HUPD仍候选，不参与字段检索/模型/Benchmark。正式运行仍v342，新读取源码仅C，字段索引构建和正式请求整链仍未完成。
