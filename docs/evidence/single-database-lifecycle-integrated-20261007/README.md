# 单库生命周期工具：冻结工程交付原件

本目录仅保存 IMP-AGENT-SINGLE-DB-LIFECYCLE-SCRIPTS-01 的工程实现及原件；主计划由主会话独占维护。本交付不变更任务/R/D/业务验收状态，也不是三域业务整包恢复放行。

## 已实际实现及验证

- `scripts/inventory-single-database-lifecycle.mjs`：执行前重新读取精确 Docker ID、volume、归属、非 root/非特权、CPU/内存及绑定，随后实际 JDBC 库存。六 schema 表/列/约束及 CHECK/FK/索引及列/sequence/view/trigger/依赖/逐表行内容摘要，12 账号直接、角色递归继承及 PUBLIC 权限。缺 catalog/账号/schema 不记完整。
- `scripts/execute-single-database-lifecycle.mjs`：明确区分 empty/existing；要求逐项原始迁移键、SQL 原件 SHA、完整已审对象映射、显式依赖顺序、前后置检查、精确批准和实际执行期 writer fence。任何 DDL 前校验全部脚本/依赖。既有对象路径还要求实际认证保护与当前库存匹配，禁止恢复 authority。原版脚本只允许受限前向 CREATE/ALTER ADD；账号、授权、复杂过程/trigger DDL 不自动放行。
- `scripts/snapshot-single-database-lifecycle.mjs`：接收经过 SHA 固定和明确批准的 DM8 原生导出/完整写入者 fence 适配器。无适配器、无全部写入者责任、无数据库/文件资料许可，拒绝动作。共同静止区间前后全库存/行摘要和文件 SHA 相等；AES-256-GCM 认证封包；使用期限取批准/数据库许可/所有文件许可的最短期限，审计保留不延长使用。过期时只读有限公开 header 并在密文 body 前拒绝。**本轮没有编造或交付一个 DM8 原生导出/fence 适配器，也没有执行三域业务原生整包。**
- 共享算法在 `scripts/lib/single-database-lifecycle-{runtime,snapshot,target}.mjs`。原 prepare 入口仍是 offline index，不启动上述操作，不把前检当初始化/保护/恢复完成。

### 最终原件

- `dm8-inventory-live-freeze07.json` / `dm8-inventory-exit-freeze07.json`：真实5492只读，六 schema，36 表，303 catalog 对象，12 账号，归并24条 FK，未解析目标/环均为0；逐表内容 SHA，不输出行内容。只覆盖固定部署 CONPROD/CONAUTH/ENFPROD/ENFAUTH/ADJPROD/ADJAUTH，不冒全实例其它工程 schema 库存或三域业务状态。
- `run07-result.json` / `h2-engineering-run07-exit.json`：19项真实独立 file-H2 工程检查。原版 V032 与 authority V001 在预置空 schema 执行，原版 V033 在认证保护后向既有对象前向升级；业务记录未 seed。另设零业务行的显式工程 sequence/view/trigger 作正向 catalog 控制，runtime 未被授予 admin。实际权限探测区分 owner 自域、跨域拒绝和未授权 runtime 拒绝。
- `run07-actual-snapshot.json`：实际 H2 SCRIPT 逻辑导出及认证封包，资料为本轮自有空 schema/内部 SQL 与显式 catalog 控制；不是 DM8 原生物理备份，不含旧 Source 输入或第三方业务资料。
- `run07-actual-independent-restored-inventory.json`：认证后导入到新的独立 file-H2；全部对象、列、约束/CHECK/FK、索引、sequence/view/trigger、行摘要及账号权限与保护前实际库存完整相等。当前 authority 包外证明为 false；无 Issuer/JWT、来源撤回或业务当前权限水位验证声明。
- `engineering-db/run07/journal-empty/events.jsonl`、`journal-upgrade/events.jsonl`、`journal-failure/events.jsonl`：本地原键、SQL SHA、逐步前置/成功/失败现场和哈希链；failure 原件显示原版 V033 第一张表已提交而第二张表碰撞失败，未删旧表/修旧行/声称全回滚，下一次执行拒绝盲重放。目录被 gitignore 保护但本地原件保留。
- `focused-tests-frozen.log` / `focused-tests-frozen-exit.json`：137项，136通过，1因 Windows symlink 权限跳过，零失败。包含接口单测 doubles、原 offline/migration-plan/deployment 专测；不是137项业务验收。真实 JDBC 成功仅以上实际工程原件计数。
- `managed-secret-scan.json`：130个受管秘密值、150份公布工程文本检查，零泄露。原始 Docker inspect 的 Env 不公布；原机器字节以 CurrentUser DPAPI 保存于本目录 `.private-vault/*.raw.dpapi`，公布版本仅脱敏 Env 值。独立 H2 凭据/备份密钥亦在此私有目录 DPAPI 文件，ACL 限当前用户/SYSTEM。

早期连接失败、H2跨进程重新打开引发真实生成索引/顺序差异以及非管理员创建 trigger 拒绝均保留，不计成功。最终 H2 harness 用单个持有连接的独立 JVM 串行执行；catalog-only trigger 由工程建库管理连接创建，不给 owner/runtime 提权。生成索引差异仍会触发库存漂移拒绝，算法没有去除对象或忽略真实漂移换绿。

## 资源及许可边界

5492完整ID `e5e5000c0d55a3da109f7963067416bd12e0014800391856cb74c2647318d1b2`；5493完整ID `a2df7cece3db3f81dbaeae5d72564092a0f24b975a7afdfba3fb2751035f3233`。核两个专属volume、helper精确PID/loop/backing 3GiB原件及原任务标记。每个数据库2CPU/3840MiB；两capless resident helper各128MiB，运行单库时总预算4GiB。5493本轮未启动，二者未同时运行。最终两DB停止/无监听，helper保留256MiB，不擅自停止/扩容/删除。主会话独占协调5497；本交付不再启动数据库/JVM。

旧 Source `source-engineering-01/owned-input/license.json` 的 validUntil=2026-10-07T10:07:02Z，即北京时间18:07:02，当前已过期。只核许可元数据，未打开其旧封包、未复用/延长 Source 输入。裁决原 manifest 仅 MECHANICAL_DOMAIN_TEST，其旧独立恢复回执只作参考。不能把原身份/来源/执行/裁决独立回执拼成同点整包。本轮保护与恢复全部限新建自有 file-H2及本轮工程原件。

## 复验命令与前置

统一回归及再次占用资源只能由主会话批准协调。没有自动启停、正式迁移、连接切换或删库入口。

```powershell
# 轻量专测，不是 verify-all / 全量
node D:/daima/xinji/agent_platform/scripts/lib/single-database-lifecycle-runtime.test.mjs
# 建议由主会话使用完整 node --test 专项命令，见冻结交付 JSON。

# 当前5492停止，此命令会拒绝，不会自行启动；若主会话批准且自有目标运行，可给新输出名。
node D:/daima/xinji/agent_platform/scripts/inventory-single-database-lifecycle.mjs D:/daima/xinji/agent_platform/docs/evidence/single-database-lifecycle-integrated-20261007/dm8-inventory-config-freeze07.json NEW_RECEIPT.json

# 若主会话再批准顺序资源占用：核helper/限额及二者停止后只启动5492库存并finally停止。
& D:/daima/xinji/agent_platform/docs/evidence/single-database-lifecycle-integrated-20261007/run-dm8-inventory.ps1 -RunName main-approved-next

# 新建隔离file-H2原件，旧run名/DB/vault均拒绝覆盖；不得使用主会话Issuer DB。
& D:/daima/xinji/agent_platform/docs/evidence/single-database-lifecycle-integrated-20261007/run-h2-engineering.ps1 -RunName run08
```

`javac-final-round2.log` 为独立 javac 成功原件。harness 的依赖为已存在 JDK21、H2 2.2.224、DM JDBC/Jackson独立lib；不运行Maven、不改共享target、不打开固定应用发行包或旧保护包。重新编译应使用本目录专属输出，主会话先核冻结清单，不以运行旧class代替源代码冻结核验。

## 具体未达出口

1. 新DM8无账号/无schema时的自动建库、账号及最终权限配置未实现；empty指**已预置6schema/12账号的空对象初始化**，不能冒从零建库。
2. 真实DM8空库/既有库DDL未执行；实际DDL/保护/独立恢复的执行证据为file-H2工程。DM8验证本轮只有完整只读库存；后续DM8执行的发布/保护/fence批准仍需主会话统一协调。
3. 三域代表性业务 DB＋必要文件的同点DM8原生整包、独立DM8恢复及业务回执/引用/迁移/当前权限水位核对未完成。没有完整写入者责任证明、资料许可全集及已批准native/fence适配器；旧Source许可到期具体阻止该输入复用，不阻止已完成的独立自有工程。
4. 原native dump、旧独立域恢复证据可供主会话参考，但没有拼包/解密旧包/迁移现行业务/连接切换/删除行为。专业/法律/业务验收与结构检查独立，均不因本工具专测通过而改变。
5. 按用户续令未跑post verify-all或全量；开始阶段已有verify-before.log，其成功不替代本轮最终统一回归。主计划/ADR/backend/db/shared target/verify-all源码均非本任务修改对象。

冻结清单见 `source-freeze.json`；最终交付事实见 `delivery-receipt.json`。后续不自行扩展源码、启动资源或接续主计划其它任务。
