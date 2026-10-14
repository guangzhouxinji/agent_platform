# IMP-SINGLE-DB-DEPLOY-REFERENCE-01：只读受管文件直接 bind 交接

依据：唯一主计划登记任务及 `docs/adr/single-database-final-deployment-20261008.md` 第6.3节。本文仅说明接口增量及专项证据；不是新增计划、批准或状态台账。

## 1. 仅 ordinary mounts 新增一种来源

原有 VM 发行域根引用继续支持。新增直接来源例（路径示意，不创建或读取此生产文件）：

```json
{
  "path": "C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/consultation/certs/server.p12",
  "sha256": "<原受管普通文件的64hex SHA256>",
  "containerSource": "C:/Users/60329/AppData/Local/AgentPlatform/single-final-20261009/consultation/certs/server.p12",
  "target": "/product/refs/consultation/server.p12"
}
```

- `path` 与 `containerSource` 必须规范化后指向同一绝对 Windows drive 文件，且严格位于既有 `secretRoot/<当前domain>/` 下。只统一路径分隔符和 drive 大小写；其余路径段逐字比对，不把跨域/未登记别名折算为准入。
- 每个段必须非空；拒绝 `.`/`..`、逗号、控制字符、UNC/device、drive-relative、ADS、Windows 尾点/尾空格及保留设备名等。域边界带 `/`，不能通过 prefix sibling 绕过。
- source admission 在文件 hash/reparse 检查之前，未获准路径先拒绝；之后仍复用普通文件、SHA、文件及所有父目录 reparse 检查。目录不能作为只读文件引用。
- target 仍只能为 `/product/refs/<当前domain>/<简单文件名>`，不得为 `.`/`..`、重复 target、DB/其他域 target 或逗号注入。
- Docker 参数只生成 `type=bind,source=<同一受管Windows文件>,target=<本域refs>,readonly`。不复制、不移动受管文件，不要求将私钥搬入 VM 发行域根；模块没有新增读取文件正文的路径。

## 2. Docker Desktop 实际 inspect 同源核验

使用 D6.3 已实测的映射规则：

```text
C:/Users/.../consultation/certs/server.p12
→ /run/desktop/mnt/host/c/Users/.../consultation/certs/server.p12
```

仅 drive 转小写，剩余路径段不更名。核验同时覆盖：

1. `HostConfig.Mounts` 中唯一对应 target：Type=bind、ReadOnly=true；Source 可为同一 Windows 客户端来源，或其确切已映射 daemon 来源。
2. 实际 `Mounts` 中唯一对应 Destination：Type=bind、RW=false，Source **必须是上述确切映射后的同一文件**。错误 drive、其他文件/域、错误 daemon 前缀、缺失、未映射 Windows 来源或 writable 均拒绝，不用 HostConfig 代替实际 mount 证据。
3. 容器启动命令继续对每个只读文件执行 `test -f`、非symlink和 `sha256sum`，SHA 与原引用一致后才 exec Java。没有输出私钥正文或密码的命令。

## 3. 保持不变

- JAR/properties 的 `containerSource` 仍为既有 VM 发行域根；本增量不允许其改用 Windows secretRoot。
- 原 DPAPI 路径/读取规则、子进程环境变量名传递及 finally 对原值/空值/缺失值的恢复不变。
- 完整 compiler/manifest/库存/原批准/UTC绝对窗口/最小授权前置、三 main/Issuer/schema/runtime、资源硬边界、同卷files/domain、拒绝既有资源碰撞、部分失败保留不变。
- 不修改 compiler/Java/SQL/共享权限/主计划/verify-all，不处理实际105迁移或原生权限分支，不重新索取已有批准，不宣称实际部署或业务验收。

## 4. 实际改动与验证

源码仅两个文件：

- `D:/daima/xinji/agent_platform/scripts/lib/single-database-final-deployment.ps1`
- `D:/daima/xinji/agent_platform/scripts/lib/single-database-final-deployment.test.mjs`

证据仅 `D:/daima/xinji/agent_platform/docs/evidence/single-database-installation-20261009/approved-execution/worker-reference/`。测试临时目录也移到该专属目录，清理前核实解析路径仍在该目录内；junction 测试只对自建普通标记文件建立/删除自建link，不触碰生产 private root。

测试覆盖：直接 readonly bind、slash/drive 规范化、VM引用兼容、JAR/properties仍拒绝Windows源、其他域/DB/任意root/prefix碰撞、逗号及dot/ADS/UNC/device注入、duplicate target、坏SHA、目录及parent reparse、真实inspect错drive/错文件/错域/漏mount/可写/错误HostConfig映射、容器内SHA、create/inspect失败时环境恢复，以及既有全部部署套件。

- Docker全部mock并保留直接 `docker` tripwire；不会接触 Docker daemon。生产 root 引用仅为模拟路径和SHA，不打开生产文件。完整 compiler 消费检查使用明确 synthetic 的测试库存，不是实际数据库证据；唯一实际DPAPI解密为自建temporary sentinel。
- 完整串行命令：`node --test --test-concurrency=1 --test-reporter=tap scripts/lib/single-database-final-deployment.test.mjs`。
- 完整原始输出：`complete-reference-tests01.tap`；退出/计数/前后源码SHA及scope：`complete-reference-tests01.result.json`。完整结果以该文件实际退出和计数为准，不扩大聚焦结果。
- 初始聚焦断言误匹配 Java `-cp` 的失败原件仍在聊天 session 13566，解释见 `focused-initial-reconciliation.json`；仅修正测试中复制命令识别，不掩盖先前失败。
- 未执行真实 Docker/DB、全量构建或被禁止的共享 verify-all；未读取生产凭据或私钥。实际主会话集成/发行/三域使用仍须另取真实证据。

## 最终完整专项结果

完整最终源码套件：92项，92通过，0失败、0跳过、0取消、0TODO，退出码0；PowerShell语法检查通过。运行前后两个源码文件SHA完全一致（sourceStable=true）。原始输出及前后SHA以 complete-reference-tests01.tap / complete-reference-tests01.result.json 为准，不是聚焦结果累加。真实Docker、DB、生产凭据访问与实际部署验证均为false；没有全量构建或共享验证器执行。

