# PC-COMPARISON-RELEASE-PACKAGE-01 离线工程检查专项证据

日期：2026-10-07（Asia/Shanghai）。此文件只说明本专项命令和原件，不维护任务或门禁状态。

## 实现边界

只新增 `scripts/lib/consultation-comparison-release.mjs`、`scripts/verify-consultation-comparison-release.mjs`、`scripts/lib/consultation-comparison-release.test.mjs`。本目录为获准专项证据。未改主计划、ADR、requirements、后端、前端或既有部署/打包工具。

- 输入是实际工作区文件、实际 JAR、主会话前后 snapshot；entries 是可选佐证，不是包声明。工具无上传包、客户端迁移 SHA、客户端类目录或业务批准接口。
- Snapshot 必须非空、无重复，数组或 files 数组仅接收 path/sha256（兼容 sha）。前后规范化完整路径/hash 集合相同，并与当前磁盘集合逐项完全相同。范围为 backend/db/scripts 递归，排除 target/node_modules/.git 目录，仅 java/xml/yml/yaml/json/sql/py/ps1/mjs。拒绝集合遗漏、新增文件、路径穿越、符号链接、读取失败。
- 解析实际 ZIP 目录，并用受管 JAVA_HOME/bin/jar 的 `jar tf` 交叉复核；全包成员解压长度及 CRC 均检查，不抽取成员、不启动产品类。必需七类固定为 ContextController/PreparationController/SchemaGuard/MatrixController/MatrixCalculation/ModelAdmission/Inputs；读取类头，不声称专业行为已验证。固定咨询 HTML 和 consultation-product-assets.json，核清单与实际资产字节/hash/字节数、完整资产覆盖及 HTML 引用；禁止静态目录内其他 HTML（包括 index 和裁决专页）。可选 --jar-sha256 加强主会话字节固定，未提供时固定本次磁盘实际 SHA，并在输出前再次复核。
- 固定原件 V094/V095/V098/V099/V100/V101 与 FK 前置 V032/V034/V036/V037/V042/V047；V092 约束扩展依赖 V037 建表。共 13 个正迁移文件按版本排序，全部固定本轮真实 SHA。读取 6 个 U 脚本原件固定 SHA，核首条语句为非空拒绝 CHECK(1=0)，记录反向顺序，不执行 SQL。`migrations.formalStatus=NOT_VERIFIED`，不认定数据库结构或已应用状态。
- 仅单份工程 manifest，内容为 hash/ref、工程范围与未执行边界，不拷配置凭据、SQL、HTML、源码或业务原文。输出限本证据目录；校验开始即移除同路径既有成功 manifest，失败不发布成功文件；全部复核后临时文件原子重命名。
- 明确不支持 ZIP64、多盘、加密包；拒绝过大成员/包。JAVA_HOME 必须是本机受管 JDK，缺失不回退 PATH。工具不会构建、应用迁移、连接 DB、部署、运行模型或批准正式使用。

## 实际专项验证

命令：`node --test scripts/lib/consultation-comparison-release.test.mjs`。

原件：`special-tests.log`。30 tests，30 pass，0 fail，0 skipped，0 cancelled，0 todo。

测试每例自建系统临时目录，不写共享 target；迁移原件仅复制到隔离测试工程，JAR 为明确标注的生成最小检查 fixture，目录由真实受管 `jar tf` 读取，不生成业务结果、不代替产品包。包含正常工程包、SchemaGuard 缺失、旧通用入口/裁决入口、路径穿越/链接、迁移及前置缺失、迁移/逆脚本/JAR/前后输入/当前磁盘/资产 hash 漂移、假 entries、空/重复 snapshot、漏必要源码、JDK 读取失败、无 jar、依赖成员 CRC 损坏、CLI 成功及失败清理等。

本次实际运行的工作区 CLI：

```powershell
node scripts/verify-consultation-comparison-release.mjs --before docs/evidence/consultation-comparison-release-20261007/package-build/source-before.json --after docs/evidence/consultation-comparison-release-20261007/package-build/source-after.json --jar backend/apps/platform-api/target/agent-platform-consultation-product-0.0.1-SNAPSHOT.jar --output docs/evidence/consultation-comparison-release-20261007/package/manifest.json
```

原件：`current-cli.log`。本次运行为 REJECTED / MISSING_FILE：主会话约定的 source-before.json 和 source-after.json 在执行时尚不存在，因此不生成 manifest。不用事后自造 snapshot 冒构建前后证据，不将隔离测试成功冒当前产品包核验通过。主会话提供真实前后输入后可按同一命令重跑。产物构建 profile 为既有 bundle-consultation；本执行者不运行它。

未运行 Maven、前端构建、全量测试、verify-all、Docker、数据库、SQL、模型或业务请求。未变更 requirements 基线。正式迁移、运维、来源、模型与专业验收不在该工程工具结论内。
