# 独立实际 file-H2 / JdbcTemplate 验证

2026-10-07，R/D沿既有隔离ADR，本轮明确批准工程自有隔离验证。只新增 ProductHistoricalResultIsolationJdbcTest.java（205行，内含独立Jupiter入口）；未改冻结主文件、其他测试、主计划、脚本或SQL/迁移文件。冻结清单所有旧文件SHA核对不变，见verification-receipt.json。本文件只记录专项事实，不维护另一个任务状态账本。

## 实际执行

- 使用H2 2.3.232 JdbcDataSource + 未继承/未mock的实际Spring JdbcTemplate，jdbc:h2:file: 指向JUnit @TempDir。17张工程自有最小表通过真实CREATE TABLE/INSERT建立，无真实来源数据或业务COMMITTED预填；SUCCEEDED仅为隔离合同要求的工程计算回执字段，不宣称业务已完成。
- 缺见证场景 CHECKPOINT SYNC 后重新创建实际JdbcTemplate打开同一磁盘库，读取记录一致。核心require拒绝、原严格恢复verify拒绝、实际恢复report返回缺项/具体选择依赖，overallIntegrityVerified=false、fullOriginalValidationCompleted=false。空的自有原件目录保持为空，未打开现行或过期包。
- 独立闭合工程tuple（从初始自有数据建立，不修改/补造真实历史事实）通过核心require/inspect及产品回执read。没有调用闭合场景全量严格恢复，因为没有专业重算/原始采集证据；不将核心通过冒充完整恢复通过。
- 自有坏摘要场景仅在工程库做受控UPDATE注入损坏，核心和实际恢复report均抛IllegalStateException，未软化为缺证通过。损坏注入后的同点记录在检查前后保持一致。
- 每一场景从实际SQL读取全部17表（含原结果/字段/选择/向量记录），规范化保存*-before.json与*-after.json；SHA相同，见*-receipt.json。比较的是原逻辑记录与摘要，不宣称H2物理文件读操作内部字节完全不变。
- javac --release 21 exit=0；真实JUnit Jupiter独立engine 3成功/0失败/0跳过，exit=0，见javac.log/junit.log。runner main放在唯一新增测试中，未改旧runner。junit.log没有把工程结果算作真实三个历史样本。
- 编译/runtime引用既有冻结classes与原classpath，只在本子目录新增独立classes、参数、日志、快照与依赖。H2本机缓存未找到，固定2.3.232从官方Maven Central取得，文件SHA与上游.sha256一致；不运行Maven，不写共享target，不跑全量或verify-all。
- @TempDir数据在JUnit结束后清理；持久化保存本目录的实际SQL快照、报告、回执，未保留或读取任何真实DB副本。

## 范围不足

实际通过仅为本轮3项工程file-H2验证。不证明DM8兼容、正式迁移、当前Issuer/Grant/Source许可整链、真实三历史原件、HTTP/UI、模型留存恢复或闭合场景专业重算恢复。最小DDL只覆盖该只读边界所需列，不是产品schema替代。旧冻结代码保持不变；新增测试交付后同样冻结，等待主会话全量。

复现：使用javac.args；运行新测试的main入口并提供isolation.jdbc.evidence.dir，runtime classpath为本子目录classes加classpath-reference.txt。不连接任何外部数据库。
