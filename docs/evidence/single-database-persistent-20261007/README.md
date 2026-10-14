# VER-AGENT-SINGLE-DB-PERSISTENT-01 实际持久工程原件

2026-10-07（Asia/Shanghai）。本目录为工程原件，不维护第二份任务状态台账。唯一状态及后置统一 verify 由主会话维护；没有修改主计划、共享部署脚本/pom/源码、旧 IT 或旧证据，没有操作5301/5302/5491/现行应用或A发行物。47个需求原件前后摘要一致。

## 实际出口

`persistent-engineering-result.json` 汇总实测及边界：

- 专属 primary/restore volume 内各固定 **3221225472字节（3GiB）** ext4镜像；实际有效容量各 **3094126592字节**。各自 UID1000/cap=0探针写满触发真实 **ENOSPC**。`primary-enospc-round2.log`、`restore-enospc.log`。
- 精确分配此前不存在的loop491029/491030，helper设备规则仅各自编号和loop-control；没有扫描、挂载或detach其他loop，没有privileged容器/宿主根挂载/全局Docker或宿主设置变更。
- helper初始化后以prctl清空全部capability，再转UID1000睡眠持有挂载。外层镜像root:0600，DM8不能访问/扩大镜像或外层volume。派生local volume使用该helper的精确 `/proc/<PID>/root/dev/owned-loop` ext4设备，DM8始终UID1000、cap-drop ALL、no-new-privileges、只读根。
- 自有实际DM8创建六schema/十二专用账号/三个Issuer。显式永久禁用、无密码、无Grant的工程主体使三域授权历史非空、generation=1；不是业务授权/真实来源或正式Task。
- 首次夹具完成 **2/2**，同容器实际stop/start后以新JVM **2/2**，另一自有容器/镜像原生恢复后以新JVM **2/2**；三个成功阶段均零失败/零跳过。36表排序SHA/行数、账号独立登录、Issuer、隔离权限及错误水位拒绝保持；基线文件没有重新播种或改写。
- `dexp FULL=Y`导出12用户/36表，原生 **63737字节**，无警告。独立vault密钥AES-256-GCM封为 **63774字节**；认证与篡改拒绝真实执行。原生SHA `7200c61a73ff979133098da01ca635ced524d39e418861c35bb168c7a5ab8f8d`。宿主仅落密文，不落原生明文文件。
- 源停写/停止后，另一3GiB目标认证解密→`dimp FULL=Y IGNORE=N`，没有警告，恢复后沿同一基线通过。`native-import.log`、`dm8-after-restore-junit.log`。
- 源阶段helper128MiB+DM8 3968MiB=4GiB；恢复阶段两个helper各128MiB+唯一DM8 3840MiB=4GiB，单资源2CPU、swap总量不增加。实际预算在 `restore-active-resource-budget.json`。实验结束两DM8均停止，仅两cap=0 helper共256MiB，无5492/5493监听；未删除任何资源。`final-retained-resource-budget.json`。

## 保留的反例与修正范围

原storage-opt=128M探针成功fsync160MiB，原件 `quota-enforcement.txt`；接受选项不代表限额生效，仍不能把普通writable-layer/local-volume称bounded。

目录bind `/proc/<helperPID>/root/bounded`被runc拒绝，原件 `loop-closure64-consumer.log`。改为local driver挂载指定helper内**唯一设备节点**后，64MiB小实验先证明真实ENOSPC，再创建3GiB原件。没有修改宿主共享传播。

首轮helper read-only根不能docker cp；次轮旧setpriv/libcap-ng不能处理新kernel的全部capabilities，均保留错误。随后按 `/proc/sys/kernel/cap_last_cap` 对所有bounding cap执行prctl，再setuid清空effective/permitted，resident五组cap均为0。helper运行态与外层镜像属性原件为两个 `*-helper-20261007-runtime.txt`。

首个3GiB探针64MiB cgroup因页缓存OOM，留下约60MiB自有零字节夹具；`primary-enospc-oom.json`保留。后续探针512MiB、每8MiB fsync/fadvise，两个3GiB目标均得到实际ENOSPC，成功探针只移除自身O_EXCL创建的scratch，不删除容器/volume或其他文件。

初始SYSDBA大写hex秘密不满足DM8大小写复杂度，失败原件保留，改为独立生成符合复杂度的秘密。首次IT在internal网络下没有实际publish端口而连接拒绝；新专属bridge关闭masquerade后只发布127.0.0.1。此网络是独占工程隔离网络，**不宣称关闭masquerade就是完整恶意外发防火墙**；未运行模型/外部适配器，无生产凭据。真实DM8写FK事件还要求同schema父表REFERENCES；仅四个V032父表对自己的service账号增加REFERENCES，无跨schema/system权限。原失败与部分夹具保留，显式 `complete-owned-seed` 仅核确切0/1禁用主体、无Grant/事件和generation0，追加缺失原件，不抹除失败，不绕当前权限/水位。正常initialize仍拒绝已有实例或基线。

## 工具、复验与恢复生命周期边界

仅新增IT和本目录工具。`compile-focused.ps1` 从已存在工程发行物抽取classes/lib，独立编译，无Maven/共享target写入。`isolated-build-round5`为最终源码编译与四项离线守卫原件；旧构建失败/尝试目录保留。离线BeforeAll预期拒绝不是DM8测试通过。真正DM8模式通过需runner无参数且2项成功、零失败/零跳过。

IT环境为SINGLE_DB_PERSISTENT_前缀：OWNER、PHASE（initialize/complete-owned-seed/after-restart/after-restore）、URL（只5492/5493）、RESOURCE_ID、STORAGE_PROOF（真实quota properties，≤3GiB及ENOSPC/错误码/原件SHA）、BASELINE（此目录identity-baseline.properties）和自有vault各账号_PASSWORD。配置版本/密文封包不是业务恢复放行令；实际挂载/归属由部署者复验，不能伪造quota report。

`owned-loop-helper.py`、`owned-enospc-probe.py`、`owned-dm8-entry.py`只属于本任务工程资源。`seal-native-dump.mjs`只接受精确新容器名和task标签/unprivileged UID1000目标，最大128MiB，认证成功前不接触恢复目标。**不能拿它处理A的668MB原件**。原生dump及DPAPI vault未复制现行业务资料；vault在忽略目录 `.private-vault/`，ACL限制当前用户与SYSTEM，凭据/密钥不进入公布证据；实际秘密扫描结果 `managed-secret-leak-check.json`。

派生volume的device路径绑定当前helper PID。每次启动消费者前必须核helper完整container ID、task标签、实际PID、唯一loop编号/backing file、外层volume身份和设备归属；helper退出/重建/PID变化时旧路径不得复用，更不得依靠PID重用。重新挂载要在专属新helper以显式resume核同一root:0600文件长度/ext4标签及任务标记，分配另一个已证明不存在的自有编号，并建立新的派生mount volume；不扫描/复用别人的loop。本次证明DM8容器stop/start和独立恢复，**没有证明Docker VM/daemon或helper重启自动恢复**。

## 未外推范围与顺序接续

没有证明真实来源撤回/当前source epoch、三域代表性Task/Run/Checkpoint/文件/外呼回执、专业Golden、生产容量性能/迁移/业务验收，也不保证特权人员同时回滚所有schema可自动发现。A的668MB/91表工程与本36表身份夹具完全分开。任务整体是否退出由主计划登记，不能从这批身份工程关闭依赖来源/业务门禁。

本次持久**当前可执行工程出口**完成且DM8停止后，按用户新委派顺序接续PC-COMPARISON-USER-START-01的两个独立存储文件，仅H2/专属编译，不耗上述DM8、不改V102或A；不是并行争用数据库或共享构建。
