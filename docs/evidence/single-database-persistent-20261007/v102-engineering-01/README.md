# V102 新隔离 DM8 工程：PARTIAL 原件

任务 VER-CON-COMPARISON-START-DM8-01，按 V10.12/咨询 ADR 新增限定技术 D 执行。主计划是唯一状态记录；本目录仅保存实际工程证据。

## 实际结论

- 初始化真实 DM8：93 assertion通过、3未满足，Java exit2（initialize-result.json）。
- 字典/FK诊断：8通过、0未满足，exit0（diagnostic-result.json）。
- 真正 stop/start 后新JVM：10通过、0未满足，exit0（after-restart-result.json）。
- 初始三项中，一项已由实际字典及反例证实为 JDBC metadata 漏报；剩余两项为**同一个 V102 NULL fencing_token CHECK 缺口**。不能标全退出条件通过。

以上是机械 assertion，含归属/初始化守卫，不是 JUnit业务用例、正式迁移/发布或模型验收分母。

## 真实范围

只用自有主容器 e5e5000c0d55a3da109f7963067416bd12e0014800391856cb74c2647318d1b2，127.0.0.1:5492。复核原父任务label、固定image、helper PID/loop491029/backing file、3GiB固定外层及root:0600，再启动。仅新建 STARTMAIN、STARTEMPTY 及各自 _SVC；DB创建前四个用户名实查均不存在，原件fresh-users-before-ddl.json。

固定 V095/V098/V099/V100/V102 逐字节应用；U102原字节执行 guard后DROP，仅失败即停止的runner允许接续DROP。没有更改db/backend/主计划/共享部署或备份脚本。

六个最小工程祖先是 con3_task/id2_subject/con5_feature_revision/con5_preparation/con5_prepared_document/con3_worker_delegation，与冻结H2专项需要的字段/复合键匹配，不是正式Task/Identity完整schema。工程Task version5/auth17，工程issuer/human无登录/凭据/权限签发能力；Clock固定2026-10-07T08:00:00Z，仅确定性存储协议验证，不是现时业务执行窗口。

真实reserveExact/dispatchOnce/bindDelegation/bindAttempt/markReconcile/readExact/lookupOriginalKey/commitFromExactReceipt由冻结ProductComparisonStartCommands执行。矩阵/native receipt闭合行是**自有工程fixture**，只核存储类对原attempt坐标及hash的拒绝/提交，不宣称真实模型响应、模型批准、Source、TLS、HTTP或完整MatrixStore业务链。

## 通过事实

实际21列/configuration_version VARCHAR200 NOT NULL、主键/原键唯一、四条FK及错误Task/Input/candidate/delegation/attempt反例。错误Task版本/cancel/Input SHA/window、委托auth5/16与Task5/auth17区分、错fence/lease/config-id/receipt候选/SHA拒绝。原键immutable replay/unknown fence、同时两个线程reserve和dispatch只有一个赢家、取消projection保留COMMITTED、B已知失败只显式新键而保留A历史均实际执行。

新运行账号只获确切同schema表SELECT/INSERT/UPDATE/DELETE/REFERENCES，没有RESOURCE或DDL权限；STARTMAIN -> STARTEMPTY与反向读/写拒绝，runtime CREATE/ALTER拒绝。所有跨schema探针只针对这两个新工程schema，未触旧SRC/原域。

非空U102 guard实际返回SQLState22000/error -20001，successfulStatements=0，没有DROP，19表摘要前后一致。STARTEMPTY确实空表才执行原guard+DROP，剩余18表，缺V102时冻结store构造失败关闭。没有删除未知attempt历史或已有Docker资源。

主进程真实停止（PID=0）再启动（同container ID、新PID/StartedAt），新JVM核19表原行数/排序逐行SHA完全一致。当前main保留11个命令历史。原RECONCILE_REQUIRED精确attempt/token保留，同键不再预留/发送，新键仍被围栏拒绝；所有读取/拒绝后基线未变。

## 未通过：固定 CHECK 的 NULL 三值逻辑

V102现在present-attempt分支使用 fencing_token>0，没有显式 fencing_token IS NOT NULL。

对已有合法delegation/attempt/fence1/lease的自有命令，两次独立事务反例：

1. SET fencing_token=NULL —— DM8接受，而合同要求拒绝。
2. SET status='COMMITTED',fencing_token=NULL —— DM8接受，而合同要求拒绝。

两次均显式rollback，原业务历史/基线未污染。原件initialize-result.json内 expectedRefusal=true、actualRefusal=false、faultRolledBack=true。其余lease/delegation/attempt残缺和fence0等反例拒绝。

派生技术解释：NULL>0为UNKNOWN，SQL CHECK允许UNKNOWN，不能将>0当成NOT NULL。**最小候选修正**是present-attempt分支加 fencing_token IS NOT NULL AND fencing_token>0，须由主会话确认、修改并冻结共享迁移。子分支没有修改或施加修复SQL，不宣布固定迁移通过。后续若授权受控替换本新schema的CHECK，必须保留现有11条历史/摘要，不重新create用户或静默覆盖原件，也不能称已正式迁移。

## JDBC FK 元数据误报已核清

driver getImportedKeys只返回4个组件行，漏了FK_CON5_START_INPUT引用复合UNIQUE的两个组件。

ALL_CONSTRAINTS自有STARTMAIN字典实际列出四条FK（v102-constraint-dictionary.json）；对command更换成不存在Task及另一个存在Input的Task，两个实际反例均拒绝SQLState23000/code -6607并rollback。因此不是Input FK物理缺失。初始未满足记录保留不改写，诊断原件作后继解释。

## 原件/安全

入口v102-engineering-result.json；初始化/诊断/重启结果及execution-result.json；v102-physical-metadata.json；v102-constraint-dictionary.json；nonempty/empty-u102-result.json；main-before-restart-baseline.json；restart-command-coordinate.json；before/stopped/after-v102-restart.json；frozen-input-check-after.json。

每个阶段单DM8 3840MiB加两个128MiB capless helper，总4GiB，各2CPU，原硬限文件3,221,225,472 bytes、有效ext4 3,094,126,592 bytes不扩大。最终两个DM8都停止，5492/5493无监听，两个helper共256MiB。禁止端口5301/5302/5491/5495/5496未触。

凭据只有本目录.private-vault/managed-secrets.dpapi.xml，DPAPI和限制ACL，gitignored；旧SYSDBA仅用原自有vault，不读现行凭据。classes/亦gitignored；独立javac，不Maven/shared target。store/test latestfreeze SHA和固定SQL前后不变，47份requirements不变。

主会话负责登记实际PARTIAL、共享SQL修正及统一verify。不能因Source或持久身份之前通过而抹掉本次约束缺口。
