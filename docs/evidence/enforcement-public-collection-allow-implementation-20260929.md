# 执法公开来源 COLLECTION_ALLOWED 实施证据

## 1. 执行摘要

- Task：`IMP-ENF-PUBLIC-COLLECTION-ALLOW-02`
- 批次：`ENF-PUBLIC-COLLECT-20260929065202`
- 抓取时间：`2026-09-29T06:52:02.012Z`
- 来源机构：国家知识产权局
- 实际请求：12；HTTP成功：12；唯一原始摘要：12
- 判定：`COLLECTION_ALLOWED=12`、`PUBLIC_DATA_READY=12`、`WAITING_REAL_BUSINESS_FACTS=12`、`READY=0`、集成触发=0

本轮重新请求旧6个官方页面，并新增6个官方页面；没有沿用旧批次状态标签作为新判定。全部来源经HTTPS官方白名单、同源重定向、2xx、HTML类型、原始字节保存、SHA重读、标题/发布日期和正文定位校验后才生成`COLLECTION_ALLOWED`。

## 2. 数据与来源覆盖

12个候选覆盖6类官方材料：案例汇编、指导通知、指导说明、行政裁决、程序批复和规则参考。新增来源包括重大专利侵权纠纷行政裁决办法、药品专利纠纷行政裁决0076/0075/0074/0061号及行政裁决案件信息停止公示批复。

逐来源URL、HTTP信息、原始文件、抓取时间和尝试结果见`data/enforcement/public-job-pack-candidates-v1/collection-attempts.json`；原始SHA、正文定位和解析摘要见`manifest.json`；任务映射与状态见`candidates.json`。12个来源的原始SHA均不同，批次无重复字节。

## 3. 事实与权利边界

已正式纳入任务包的公开事实：真实公开任务/文书、发布机构、标题、发布日期、原始URL、HTTP元数据、原始页面、SHA、正文定位，以及原文中可定位的公开处理结果或公开意见。未抽取的公开身份/角色保持`NOT_EXTRACTED`，没有猜测补齐。

每个候选均固定：

- `collection.decision=COLLECTION_ALLOWED`
- `publicDataState=PUBLIC_DATA_READY`
- `state=WAITING_REAL_BUSINESS_FACTS`
- `rights.copyrightLicense=NOT_ASSERTED`
- `rights.commercialLicense=NOT_ASSERTED`
- `rights.legalRights=NOT_ASSERTED`
- `workflow.taskPackPublicInputAllowed=true`
- `workflow.readyAllowed=false`
- `workflow.integrationTriggerAllowed=false`

仍缺且只能由真实系统行为取得的五类内部事实：当前真实系统操作者、当前岗位授权、本系统实际人工批准、本系统正式人工回执、内部业务评价。当前各项完成数均为0。

## 4. 实际验证

- `node scripts/collect-enforcement-public-job-pack.mjs`：退出0，12/12实际官方页面成功。
- `node scripts/collect-enforcement-public-job-pack.mjs --verify-only`：退出0，12份原始文件、正文及批次摘要复算一致。
- `node scripts/collect-enforcement-public-job-pack.mjs --self-test`：退出0，覆盖跨域/类型/摘要/重复、无证据采集允许、采集允许冒充法律权利、提前READY、公开身份冒充当前身份和缺原件轨迹。
- `node scripts/verify-all.mjs`：实现后退出0；632项计划、治理、结构、新采集包离线校验和安全自测全部通过。状态回写后的后置复跑被并行咨询检索控制器/配置及测试晚于最近Java报告的改动截断为`TEST_EVIDENCE_STALE:project`；本任务未修改该轨道，也不把专项通过冒充后置全仓通过。

## 5. 具名复核与状态

具名技术复核人：Codex。`IMP-ENF-PUBLIC-COLLECTION-ALLOW-02`满足冻结D，可完成。

`DATA-ENF-JOB-PACK-01`当前为`PUBLIC_DATA_READY / WAITING_REAL_BUSINESS_FACTS / BLOCKED`。公开采集不再等待独立许可证文件；内部五类真实行为未齐，因此任务包不是`READY`，`NEXT-V2-INT-ENF-01`未触发。
