# VER-AGENT-SINGLE-DB-PERSISTENT-01 工程原件（2026-10-07）

此目录仅保存专项工具与实测结果，不是第二份任务状态台账。唯一状态仍由主会话维护主计划 V10.10 的 7.8.3；本会话未修改主计划、scripts、共享源码/pom、旧 IT 或旧证据。前置 verify 由主会话已执行，后置 verify 由主会话统一执行。本轮未通过专项退出条件，不能标为持久恢复已验证。

## 1. 实际磁盘反例与安全停止

Docker 当前使用 overlayfs / io.containerd.snapshotter.v1，参见 `storage-driver.txt`。`docker create --storage-opt size=2G` 虽返回成功，但仅说明选项被接受，不说明额度生效。

自有有限写入探针使用固定已有镜像 SHA 6409a1d…、task 标签、network=none、128MiB 内存含 swap、2CPU、pids=64，不发布端口、无凭据、无挂载。请求 writable-layer `size=128M`；一次 `dd bs=1M count=160 conv=fsync` 实际写入 167772160 字节，退出 0，根 overlay 显示约 1TB 容量。**该部署方式没有证明持久磁盘硬上限，且这次探针对所请求上限给出了直接反例。**普通 local volume 也不能从这次根层选项继承额度，未创建或启动无额度的持久数据库以冒充受限验证。

保留两个自有探针（一个 Created/只读，一个 Exited/有限 160MiB 写入），未删除任何资源。完整 ID、标签、限制与状态见 `owned-resource-inventory.json`。目前无自有运行容器、无持久数据库卷、无端口发布。5492/5493 监听核对见 `port-check.json`。5301/5302/5491 与现行应用均未操作；没有读取其凭据/业务数据。

安全替代是**保持数据库分支关闭，离线完成专项编译及拒绝守卫**，不是改用 tmpfs，也不是靠监控/告警、只配置 DM8 文件尺寸或磁盘剩余空间宣称 bounded。后续可由主会话提供独占固定容量文件系统/受硬 quota 的挂载；实际挂载有效容量及写满产生 ENOSPC/EDQUOT 需另取证，不能仅提交配置 JSON。未自行提升 Docker daemon 权限、挂载宿主 loop 设备、修改全局虚拟盘或 Windows 磁盘配置来绕过本次限制。

## 2. 新 IT 覆盖范围与真实未执行部分

新增 `backend/apps/platform-api/src/test/java/cn/xinji/agentplatform/security/product/ProductSingleInstancePersistentDm8IT.java`，仅工程夹具，三次独立 JVM 调用：

- `initialize`：仅 127.0.0.1:5492；拒绝已有或部分已有的十二账号与已有基线。创建六 schema/十二专用账号，安装既有 V032 与 authority V001。通过真实 ProductGrantWatermark.change 写入各域永久禁用、无密码、无 Grant 的工程主体及 DISABLE 历史，使 history_sha 非空/generation=1，不生成可登录的用户、不批准业务资源。
- `after-restart`：仅 5492；不建表、不重播夹具、不重写基线。必须由主会话先真实停容器/启动同持久存储，再新 JVM 读取。
- `after-restore`：仅 5493；不建表、不重播夹具，必须先真实原生导出、加密、停源、在另一自有硬限额目标隔离导入，再新 JVM 读取。独立目标启动时源须停止，总内存最多4GiB，单资源2CPU。加密密钥保留于独立受管 vault，不能写此目录或日志。

测试按同一基线比对六域所有迁移表的行数/排序摘要、十二账号存在/独立登录、三 Issuer 和授权水位；核跨域及业务/authority 读写互拒、业务 DDL 拒绝、错误 Issuer/PENDING/history_sha 错配/陈旧 generation 拒绝，及专池关闭不影响业务连接。负向探针只对自有工程 watermark 做暂时改变并恢复原摘要；若探针中断，保留 PENDING/错误原件供调查，不让工具据此放行业务。

这些是**已编译、待真实 DM8 执行的测试定义**。初始化、实际持久重启、原生导出加密、另一实例导入、恢复后一致性、真实来源撤回/epoch、三域 Task/文件/Checkpoint/外呼对账均未运行，不能由本次离线守卫或历史 tmpfs IT 外推通过。

## 3. 独立编译和 Jupiter 原件

`compile-focused.ps1` 仅在本目录的独立 classes/lib 输出编译，无 Maven、无下载、无共享 target 写入。只读已存在工程发行物 `agent-platform-single-db-engineering-20261007.jar`（SHA 在 build-result / inputs 原件）抽取 class/lib，再编译新 IT 和 `PersistentDm8JupiterRunner.java`。不是当前全部源码重建，也不把发行物历史报告冒成本轮真实 DM8 实测。

- 首次编译准备缺本地 AssertJ 3.24.2，保留 `isolated-build`，没有掩盖失败或下载依赖。
- 第二次改用实际已缓存 3.25.3，编译与四项离线前置守卫通过，保留 `isolated-build-round2`。
- 第三次把单纯“验证标记”改为需绑定实际资源/固定镜像/容量/溢出错误/原件摘要的 quota report 后，重新编译通过；最新原件 `isolated-build-round3`。这份 quota report 尚不存在，未伪造或自动生成。

四项守卫为缺 OWNER、缺 PHASE、错误现行端口（仅构造字符串，**未连接5301**）、缺 STORAGE_PROOF。Jupiter 实际执行 BeforeAll 并获得预期拒绝；每次 successfulTests=0、failedDescriptors=1。runner 仅在符合预期拒绝时返回0，显式 mode=OFFLINE_GUARD_NOT_DM8，**不是2项数据库测试通过**。原始日志及输入 SHA 均保留。

离线复编命令（需要新唯一目录，旧原件不覆盖）：

```powershell
& .\docs\evidence\single-database-persistent-20261007\compile-focused.ps1 -BuildName isolated-build-round4
```

真正 DB 执行前，由主会话独占部署协调，先核新容器的 task 标签、镜像、127.0.0.1端口、持久存储和实际限额/内存/CPU，才设置下列环境。新 IT 不是 Docker 部署脚本，也不会启动/停止/删除容器：

- `SINGLE_DB_PERSISTENT_OWNER=VER-AGENT-SINGLE-DB-PERSISTENT-01`
- `SINGLE_DB_PERSISTENT_PHASE=initialize | after-restart | after-restore`
- `SINGLE_DB_PERSISTENT_URL=jdbc:dm://127.0.0.1:5492`；只有 after-restore 使用 5493。
- `SINGLE_DB_PERSISTENT_RESOURCE_ID`：当前新目标实际资源 ID。
- `SINGLE_DB_PERSISTENT_STORAGE_PROOF`：本目录内真实 quota `.properties` 原件路径，需 task/resourceId/image/effectiveCapacityBytes（≤2GiB）/overflowError（ENOSPC或EDQUOT）/overflowWriteExit（非0）/probeEvidenceSha256。此文件的静态校验不替代部署者对实际挂载与错误原件的核对，不是产品恢复放行开关。
- `SINGLE_DB_PERSISTENT_BASELINE`：本目录绝对路径 `identity-baseline.properties`。初始化 CREATE_NEW，后续只读。
- `SINGLE_DB_PERSISTENT_SYSDBA_PASSWORD`，六 owner 与六 `_SVC` 的同前缀 `_PASSWORD`：独立 vault 内32～48字符工程秘密，不打印、不复用现行秘密。

用最新独立目录的 `java -cp ".../classes;.../lib/*" PersistentDm8JupiterRunner` 运行各阶段；无参数为真实 DB 模式，要求2项测试成功、零失败、零跳过。在有硬限额前**不要设置上述 DB 配置进行运行**。

## 4. 输入与验收边界

阅读输入摘要见 `read-inputs.json`。原 AGENTS、主计划V10.10任务条目与ADR§9.2、旧 tmpfs 资源/IT/批准均只读。47文件基线复核结果见 `requirement-baseline-check.json`；摘要没有变化。

来源链/来源撤回代表性与三域业务代表性必须按既定合同另选仍在许可/期限/用途内的输入。没有现时输入、责任依据或专业 Golden，就继续关闭该支路；本轮没有自行发明准入、金标、来源用途或业务验收结论。主会话收到本原件后，按唯一主计划登记磁盘前置实际限制及剩余执行项，并执行统一后置 verify；不把工具交付当成 VER-AGENT-SINGLE-DB-PERSISTENT-01 退出。
