# 执法公开材料采集与候选任务组包执行证据

> 历史状态说明：本文件记录首批6个候选的旧`WAITING_LICENSE`批次。采集许可规则及现时12个候选状态已由`enforcement-public-collection-allow-implementation-20260929.md`覆盖；原始执行事实继续保留。

## 1. 执行边界

- Task：`IMP-ENF-PUBLIC-JOB-COLLECT-01`
- 执行批次：`ENF-PUBLIC-COLLECT-20260929062652`
- 执行时间：`2026-09-29T06:26:52.991Z`
- 输入合同：`docs/evidence/enforcement-public-job-pack-d-20260929.md`
- 数据目录：`data/enforcement/public-job-pack-candidates-v1`
- 采集器：`scripts/collect-enforcement-public-job-pack.mjs`
- 事实边界：只生成 `PUBLIC_SOURCE_FACT` 候选，不写执法业务表，不推断 `BUSINESS_REALITY_FACT`。

## 2. 实际网络采集结果

本轮实际请求 6 个国家知识产权局官方 HTTPS 页面，6 次成功、0 次失败，均为 HTTP 200；6 份原始 HTML 字节摘要互不重复。原始 URL、HTTP 元数据、抓取时间、原始文件、SHA-256、标题、发布日期、正文定位和正文摘要分别固化在 `manifest.json`、`collection-attempts.json` 和 `raw/`。

| 候选 | 来源类型 | 场景 | 发布日期 | 原始 SHA-256 | 状态 |
|---|---|---|---|---|---|
| 2023年度知识产权行政保护典型案例 | 官方案例汇编 | 正常汇编/附件未采集 | 2024-04-26 | `ed3bc26d813c88a91e6aa514b87f6ea82981c77df0ad1e63157b720321b89ef3` | `WAITING_LICENSE` |
| 第三批知识产权行政保护指导案例通知 | 官方指导通知 | 多案例边界/通知型材料 | 2023-12-28 | `626d5f8991174571ed67f36b27b3c3d569ae5ae59773dc7e00e7e27432d980cf` | `WAITING_LICENSE` |
| 指导案例1号理解与适用 | 官方指导说明 | 详细指导材料 | 2021-04-01 | `fd1c6ffb91d32829a8393cccdf9197f7e2710d405a80baa932db10bdabf1c562` | `WAITING_LICENSE` |
| （2025）国知药裁0077号 | 官方行政裁决 | 正常裁决候选 | 2026-09-08 | `0835914753ec10cfc256ad45ff1eefe6221efa484f0c773eb29f6ad969ba204d` | `WAITING_LICENSE` |
| （2026）国知药裁0062号 | 官方行政裁决 | 证据不完整挑战 | 2026-08-20 | `36f6eb85cbfec82b1bb52525ae49bae22ae1e3d6373beb4e9bc4ad4dd26c8529` | `WAITING_LICENSE` |
| 追加当事人后重新计算办案期限的批复 | 官方程序批复 | 程序时限边界 | 2026-08-18 | `5842dbdf1c297aca04e6226febccadc01746734f46c3a4c3329426a96464be3d` | `WAITING_LICENSE` |

来源机构数为 1，实际来源页面数为 6，来源类型为 5 类：官方案例汇编、官方指导通知、官方指导说明、官方行政裁决、官方程序批复。候选任务包 6 个，覆盖正常、证据不完整和程序边界场景；场景标签只描述测试输入形态，不改写公开文书事实。

## 3. 已验证事实与待采集事实

已由公开网络验证：官方原始 URL、发布机构、标题、发布日期、抓取时间、HTTP 200、内容类型、原始 HTML、原始文件 SHA-256、正文定位、正文纯文本摘要、来源可信等级、ENF-01～17 的输入用途映射和去重结果。

未由公开网络证明：来源保存/业务运行/索引/模型/分享许可，当前真实操作者，现实岗位与组织授权，内部审批过程，实际人工批准，正式人工回执，业务评价。6 个来源的许可证据均为 0，因此 `LICENSE_VERIFIED=0`、`PUBLIC_DATA_READY=0`；内部五类事实的提交数和异人核验数均为 0。

公开网页中的人员、机构、决定和处理结果没有被映射为当前操作者、当前岗位、内部批准、正式人工回执或业务评价。`candidates.json` 对每个候选都固定 `importAllowed=false`、`readyAllowed=false`、`integrationTriggerAllowed=false`。

## 4. 校验与反例

实际执行：

- `node scripts/collect-enforcement-public-job-pack.mjs`：退出 0，6 个真实官方页面、6 个唯一摘要、6 个 `WAITING_LICENSE`、0 个工作流导入。
- `node scripts/collect-enforcement-public-job-pack.mjs --verify-only`：退出 0，逐文件重读并复算原始摘要、正文摘要和批次摘要。
- `node scripts/collect-enforcement-public-job-pack.mjs --self-test`：退出 0，覆盖跨域 URL/重定向、伪内容类型、摘要篡改、重复字节、无证据伪 `LICENSE_VERIFIED`、公开人物误映射当前身份和缺原始文件轨迹。
- `node scripts/verify-all.mjs`：实现完成后的整仓轮次退出 0，新采集包的离线校验和安全自测已纳入全仓校验。主计划状态回写后的首轮复跑曾被并行咨询 Java 证据陈旧截断；最新复跑已通过计划校验，但被并行咨询 `ProductPatentSearchExecution.java` 323 行超过 300 行硬限制截断。该阻断不来自执法采集专项，本任务未修改咨询代码，也不把专项通过冒充该后置全仓轮次通过。

采集器只允许 `https://www.cnipa.gov.cn/art/`，仅接受同源重定向和 HTML 成功响应；同一目录版本内容漂移时拒绝静默覆盖，须提升目录版本并重新复核。

## 5. 技术复核与状态结论

具名技术复核人：Codex。复核结论：采集、原始证据、解析、去重、候选映射、失败关闭和反例均满足冻结窄 D，`IMP-ENF-PUBLIC-JOB-COLLECT-01` 可标记完成。

`DATA-ENF-JOB-PACK-01` 仍为 `WAITING_DATA / BLOCKED`：公开候选 6 个，但来源许可 0/6，现实身份岗位、实际人工批准、正式人工回执和业务评价均未提交/未异人核验。`NEXT-V2-INT-ENF-01` 未触发。解除条件是许可证据可核且现有最终产品在线采集中的现实业务事实全部通过异人核验；不得用当前公开材料自动补齐。
