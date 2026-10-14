# 三应用正式部署入口：集成输入合同与专项边界

任务：IMP-SINGLE-DB-FORMAL-DEPLOY-01。依据仅为主计划登记行及 `docs/adr/single-database-final-deployment-20261008.md`。本文说明源码接口与专项证据，不建立状态台账、不批准实际资源或业务状态。当前脚本只支持原确切批准的新 primary，拒绝原多库地址、替换/迁移/删除及模型准入推定。

## 1. 调用与运行依赖

在仓库根目录使用 **PowerShell 7.5+**（使用 `ConvertFrom-Json -DateKind String` 保留时区）和现有 Node：

```powershell
pwsh -NoProfile -File D:/daima/xinji/agent_platform/scripts/start-single-database-applications.ps1 -Configuration <绝对配置路径> -ValidateOnly
# 无 ValidateOnly 才进入受批准的 Docker inspection/create/start。此工作者没有执行这一模式。
```

ValidateOnly 不调用 Docker，不解密 secret，不启动应用；仍核对窗口、引用文件/secret 密文 SHA、compiler 原文、全部结构/最小授权和前置回执。实际模式在任何 Docker 调用前解密全部三域 secret；create/start 前重新核对原批准、窗口、输入文件 SHA 与库存时效，重复 inspect 确切网络 ID、primary 归属/安全参数和稳定 ext4 卷，并在每个create前重查名称碰撞。Docker create 直接使用 networkId，不在检查后再按可重名网络解析。先 create 三个应用，再逐个 inspect 确切创建资源与 start；途中失败保留部分容器/卷，**不删除、不停止旧服务、不越过窗口自动回滚**。实际 inspect/create/start 的成功不是业务验收或运行健康证明。

## 2. 顶层配置（禁止额外字段）

`schema = SINGLE_DATABASE_APPLICATION_DEPLOYMENT/v1`

| 字段 | 类型/约束 |
|---|---|
| approval | `{path,sha256}`：绝对路径，原 resource-approval01 的字节 SHA `649f93fd58081b7ca00f0887b547f1608f0a62f55f546227dc597d799000fff7` |
| request | `{path,sha256}`：原 resource-request-original01 字节 SHA `8c6c5b46f977732b4dcb1825b54620ae4edac1b700004a3c2f967a70738d3a17` |
| compiledInstallation | `{path,sha256}`：compileInstallation 的**完整原始输出**，format `SINGLE_DATABASE_COMPILED_INSTALLATION/v1`，stage `application-settings` |
| installationRequest | `{path,sha256}`：原编译请求 JSON，含 `stage:application-settings`、完整 deployment v2 和完整实际 inventory；workspaceRoot 若提供必须为本仓库 |
| manifestSha256 | compiler 输出内的 manifest canonical SHA；不等于 compiledInstallation 文件字节 SHA |
| initializationReceipt | `{path,sha256}`：第3节初始化/存储证据封装 |
| inventoryReceipt | `{path,sha256}`：第3节当前完整库存封装 |
| releaseKind | `engineering-candidate` 或 `final-release`；候选不会被自动升级 |
| releaseEvidence | 仅 final-release 必需：`{path,sha256}`，内容见第5节 |
| image | 原固定基础镜像 `sha256:14731f0ec76c841877c8858e1a29413d7acacda5c5ce567eaf6e5f19913b7cb1`；不 pull，不允许隐式 VOLUME |
| network | `agent-platform-single-final-20261009`；实际 inspect 核对 owned/Internal/primary 在网 |
| networkId | 精确64hex；当前主会话已给定 `047a31bbbe9c568ed7cfb3099195432c091ec1d3de595f6375d1e2ce06c4ad65` |
| secretRoot | `C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009` |
| filesVolume | 同一新3GiB named volume；允许现有 primary named volume 的 `files/<domain>` 子路径，或独立获准、同范围有真实配额证明的新 `agent-platform-single-final-files-...` 卷；不要求另建卷 |
| applications | 精确三条，第4节；不允许额外应用/域/环境字段 |

所有引用文件均必须为普通文件，文件和父路径不能为 reparse point，必须核对 SHA；不提供口令/私钥正文。编译请求与 compiler 输出不能仅截取 configurations 数组。读取现有 compiler 的输出时采用真实 `canonical/hash`，不另建 grants catalog。

原窗口按绝对时刻比较：`2026-10-08T19:00:00Z <= now < 2026-10-10T10:00:00Z`，分别是北京时间2026-10-09 03:00和2026-10-10 18:00；7GiB/4CPU、最多一个在线 DM8 不扩展。原 SHA 丢失、变更或截止均拒绝，不需要重新申请已有批准。

## 3. 两个前置证据封装

两个 JSON 都有以下共同字段（用已有真实原件生成封装，不能以封装替代实际初始化/库存/容量证明）：

```text
targetId: 与 manifest.targetId 和实际 deployment.database.containerId 一致的64hex
manifestSha256: 顶层 manifestSha256
installationRequestSha256: installationRequest 文件字节 SHA
actualJdbc: true
complete: true
errors: []
capturedAt: 带 Z 或显式偏移的 ISO-8601 绝对时刻
```

`initializationReceipt`：

```text
format: SINGLE_DATABASE_APPLICATION_INITIALIZATION/v1
storage:
  database:
    volume: agent-platform-single-final-primary-data-20261009
    device: /dev/agent-platform-single-final-primary-loop
    hardLimitBytes: 3221225472
    enospcVerified: true
    noExpansionPath: true
    filesystemUuid: 实际 ext4 UUID（标准小写 UUID）
    backingFile: VM内已核实的确切普通背文件绝对路径（/mnt/docker-desktop-disk/.../primary.ext4）；不要求计算整块3GiB内容SHA
    proof: {path, sha256} # 已保存原始容量/UUID/背文件/无扩容路径核验输出
    subpaths: [db, logs] # 此顺序
  files:
    volume: 顶层 filesVolume
    device: 确切 /dev/agent-platform-single-final-<name>-loop 稳定设备
    hardLimitBytes: 3221225472
    enospcVerified: true
    noExpansionPath: true
    filesystemUuid: 实际 UUID
    backingFile: 与既有核验一致的VM内背文件绝对路径；复用primary卷时可与database相同
    proof: {path, sha256} # 原件应包含目录归属和模式核验
    directories:
      consultation: {subpath: files/consultation, uid: 10001, gid: 10001, mode: '0700'}
      enforcement: {subpath: files/enforcement, uid: 10002, gid: 10002, mode: '0700'}
      adjudication: {subpath: files/adjudication, uid: 10003, gid: 10003, mode: '0700'}
```

初始化采集时刻不得在窗口前或未来；不强制初始化动作本身15分钟内重做。实际模式还 inspect 两组绑定（允许同一卷）：`Driver=local`、`Options.type=ext4`、`Options.device` 与封装一致、选项含 `nodev,nosuid,noexec`，`Labels.task=IMP-SINGLE-DB-FINAL-BASELINE-01`。**没有把 hard-limit label 当容量证明，也不要求新增这个 label。**

`inventoryReceipt`：

```text
format: SINGLE_DATABASE_APPLICATION_INVENTORY/v1
inventorySha256: hash(canonical(installationRequest.inventory))
# 加上以上共同字段；capturedAt 距开始校验/每个副作用不超过15分钟
```

脚本只读重新执行现有 compiler 的 application-settings 阶段，真实检查六schema/15账号、完整安装结构、必须的最小对象授权、effective/PUBLIC/越权等库存规则，且输出与提供的 compiledInstallation 完全 canonical 一致。**工作者没有采集实际库存**；`actualJdbc/complete` 不能用 mock 结果填写。主会话的真实 collector、初始化和原始机器输出负责证明证据来源；该入口的离线验证不声称实时 JDBC 或独立证明库存不可伪造。

deployment 必须为现有 `SINGLE_DM8_DEPLOYMENT/v2`，固定 JDBC `jdbc:dm://agent-platform-single-final-primary-20261009:5236`、原新 primary 卷和 ownerTask。当前主会话目标为 `f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f`，应写入真实编译请求；脚本以 manifest/回执/实际 inspect 三方绑定，不用名称替代 ID。实际 DB 必须 nonroot/cap-drop ALL/read-only，4026531840B/2CPU，只挂 db/logs，且原 restore 若存在必须停止。

## 4. 每域 application 输入

```text
domain: consultation | enforcement | adjudication
name: agent-platform-single-final-<domain>-20261009
uid: 10001 | 10002 | 10003
jar: {path: 主机会读到的普通 JAR 绝对路径, sha256, containerSource: 下述固定域根/platform-api.jar}
properties: {path: 普通配置绝对路径, sha256, containerSource: 下述域根/application.properties}
environment: {<requiredEnvironment 中 deployment-config 的每个环境变量名>: 完整明确的非secret字符串}
secrets: {<requiredEnvironment 中 managed-secret 的每个环境变量名>: {path: 原privateRoot/<ENV>.dpapi, sha256: 密文字节SHA}}
files: {volume: 顶层filesVolume, subpath: files/<domain>, target: /product/files/<domain>}
mounts: [{path, sha256, containerSource, target}, ...] # 普通本域只读文件，至少key-store/trust-store
```

- 容器文件来源域根：`/mnt/docker-desktop-disk/agent-platform-single-final/<domain>`。JAR/properties 为该根下简单文件名；只读引用可在其安全子目录，不能含 `.`/`..` 段。
- 每个 mounts target 是 `/product/refs/<domain>/<简单文件名>`，不得重复。server SSL key-store/trust-store 必须解析到这两个已登记只读目标；`file:/...` 形式支持；client-auth 必须 `want` 或 `need`，TLS必须启用。
- 任意绝对文件属性必须指向已登记本域 target 或 `/product/files/<domain>` 的安全子路径，不能借字段指向 DB、其他域或整个卷。adjudication 的 policy-file 也必须是其登记只读文件；key-directory 可以是域内 files 子路径。
- propertiesContent 必须与 compiler 字节文本完全相同；不自行补缺字段、fallback URL、owner 或默认身份。参考环境列表直接使用 compiler.requiredEnvironment，固定值和动态 JDBC/Issuer 不另通过环境覆盖。
- DPAPI `.dpapi` 为当前用户 `ConvertFrom-SecureString` 的密文文本，先 Trim **加密文本**，支持文件末尾换行；兼容现有 `.xml` Import-Clixml SecureString/PSCredential，拒绝明文和其他格式。密文文件 SHA 包含换行，不对 SHA 输入做 Trim。解密值不被输出、不进入 argv，仅通过 `--env ENV_NAME` 传给 Docker 子进程；finally 精确恢复原值、空值或缺失状态。
- 独立 main：consultation `cn.xinji.agentplatform.security.product.ProductConsultationApplication`；enforcement `cn.xinji.agentplatform.security.product.ProductEnforcementApplication`；adjudication `cn.xinji.agentplatform.adjudication.casework.ProductAdjudicationApplication`。全部使用 `PropertiesLauncher` 和独立 `-Dloader.main`，shared-instance、各自schema/runtime/Issuer必须与 deployment 一致。
- resource 固定：127.0.0.1:5446/5447/5448 -> 8443；nonroot、capdrop ALL、readonly、no-new-privileges、768MiB、0.5CPU、64MiB域UID tmpfs、restart=no；task/domain/manifestSHA/releaseKind labels。不发布外部地址。只挂本域 files subpath 和登记只读文件，绝不挂整个 files/DB 卷。
- 真正 start 前还 inspect 新容器的 ID/name/user/labels/CPU/memory/network/ports/entrypoint/launch command/每条mount，拒绝 drift；启动命令内校验容器主机实际挂入 JAR/properties/只读引用 SHA及本域文件目录uid/gid/mode，然后才 exec Java。无 logs/healthcheck 冒业务验收，模型准入始终为 false。

## 5. 最终发行与结果

final-release 的 releaseEvidence 内容：

```text
format: SINGLE_DATABASE_FINAL_RELEASE/v1
manifestSha256: 顶层值
ready: true # 只消费主会话独立固定发行就绪证据，不由此入口自动认定
applications: [{domain: consultation, jarSha256: 固定SHA}, {domain: enforcement, jarSha256: 固定SHA}, {domain: adjudication, jarSha256: 固定SHA}]
```

每个固定SHA必须等于实际 application.jar 的文件SHA。该输入只标识发行类别，不能替代完整回归/专业/正式验收。当前历史红回归不被脚本修改。输出显式含 releaseKind、businessVerified=false、modelAdmissionGranted=false；ValidateOnly 的 scope 为 OFFLINE_CONFIGURATION_ONLY；真实模式只报 START_COMMANDS_ONLY_NOT_BUSINESS_ACCEPTANCE，不能据此关闭父任务或宣称三域正常业务运行。

## 6. 专项与不做事项

测试文件 `scripts/lib/single-database-final-deployment.test.mjs`。真实 compiler 检查的库存与JAR等是明确标注 synthetic 的**测试夹具**，只用于 no-external 单元专项；Docker命令全部mock，并有直接docker tripwire。唯一真实解密是独占证据目录下自建、随后清理的 temporary DPAPI sentinel，不读取生产凭据。测试不改业务skip、profile/compiler/Java/SQL/主计划/verify-all。

完整批原始日志为 `targeted-tests01.tap`（52项）和 `targeted-tests02.tap`（55项），对应 `.result.json`。同卷接线最终修订的3项聚焦命令原始输出保留在本聊天 session 42316，`targeted-tests03.result.json` 登记范围与最终源码SHA；没有重新执行完整56项最终源码套件，不将3项扩大为全套结果。。早期专项发现过 JSON 默认时间转换丢UTC偏移及 absent env恢复成空字符串，两者修复记录见 `early-test-reconciliation.json`；不掩盖先前失败。结构、mock命令验证和实际部署/真实业务验证严格分开。

遵从主会话资源独占和本次禁止全量构建/共享验证器限制，未执行 verify-all 或全量构建；未操作真实 Docker/DB/模型/凭据，不更新唯一主计划。任务集成状态由主会话在唯一主计划登记。


交接限定：2026-10-08 主会话要求停止扩展。主会话报告原生PUBLIC REVOKE vendor-5571失败、completed0并修正共享白名单D/guard；本入口不处理该权限分支。实际部署未成立，旧环境未修改。最终实际compiler结果变化后，必须由主会话重生成完整请求/manifest/库存回执；入口不会接受过期manifest或绕过共享检查。

