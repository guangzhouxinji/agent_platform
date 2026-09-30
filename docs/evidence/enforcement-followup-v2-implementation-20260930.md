# 执法 V2 ENF-12/17 实现与验收证据（2026-09-30）

## 1. 范围与状态

- `IMP-ENF-DOCUMENT-TEMPLATE-03`：实现已形成，最终开发验收未完成，当前 `WAITING_INFRA`。
- `IMP-ENF-LEGAL-REFERENCE-03`：实现、C 和真实 DM8 事务 I 已形成；HTTPS/浏览器/恢复尚未收口，当前 `WAITING_INFRA`。
- `IMP-ENF-DOCUMENT-RULE-04`、`IMP-ENF-PENALTY-04`、`IMP-ENF-DOCUMENT-DRAFT-05`：依赖未满足，未启动。
- `FUNCTIONAL_READY / TECHNICAL_VALIDATED` 仅沿用已冻结的 ENF-01～11 与 Job Pack 结论；本文件不声明 ENF-12/17 已完成，也不改变 `BUSINESS_ACCEPTANCE_PENDING`。

## 2. ENF-12 已执行证据

### 2.1 C、DM8 I 与构建

可复核 XML 与成功轮截图已固化在 `docs/evidence/enforcement-followup-v2-implementation-20260930/`；该目录不包含后续失败轮的 `failure.json`，避免把诊断产物误作通过证据。

| 验证 | tests | failures | errors | skipped | 结论 |
|---|---:|---:|---:|---:|---|
| `DocumentTemplateServiceTest` | 4 | 0 | 0 | 0 | 字段、重复区块、版本、失败与孤儿清理 C 通过 |
| `DocumentTemplateStoreTest` | 2 | 0 | 0 | 0 | 仓储事务与回执结构 C 通过 |
| `DocumentTemplateDm8IntegrationTest` | 1 | 0 | 0 | 0 | 随机隔离真实 DM8 schema、原件、预览、发布与失败无副作用通过 |
| `EnforcementFollowupDm8MigrationTest` | 1 | 0 | 0 | 0 | V074/V075 建表及反迁移通过 |
| 前端定向测试 | 8 | 0 | 0 | 0 | ENF-12/17 工作面与共享 API 定向测试通过 |
| 前端生产构建 | - | - | - | - | `tsc -b && vite build` 退出 0 |

### 2.2 真实 HTTPS/OAuth/浏览器

- 产品入口：`https://127.0.0.1:5444`；工作面：`https://127.0.0.1:5174/app/enforcement/document-templates`。
- 使用公开 PKCE Client `enforcement-document-template-20260929`、受控 `FUNCTIONAL_VALIDATION` 身份和 Product Grant；没有 Client Secret、密码或凭据写入仓库/evidence。
- `PROJECT-FOLLOWUP-TEMPLATE-20260929-05` 实际完成登录、DOCX 上传、DM8 草稿、样例预览、发布、原件下载、停用；HTTP 失败 0，原件下载 SHA 与冻结输入 `5656160c4173f2888ea79cece7b79786904ff55999108b5b1a0e252b7b40a2fd` 一致。
- 技术复核发现该轮“预览下载”选择器误点“下载原版”：HTTP 实际路径为 revision file，下载 SHA 等于原件，而 DB 中预览 SHA 为 `f186fc64bba0d91ac80200bc695be81f41a10e2f9c18b2f5be4f0ca88faff27b`。因此该项未计为通过。
- 同一产品后续草稿 `PROJECT-FOLLOWUP-TEMPLATE-20260930-09` 已由真实页面创建并生成预览；恢复模式可定位确切模板，但预览下载按钮尚未产生 GET 请求，仍需在运行时恢复后继续定位。未把可见按钮、旧文件或失败轮次改记为 PASSED。

### 2.3 独立 DB+文件物理恢复

- 首轮一致性快照使用独立容器/卷 `codex-enf-followup-restore-20260929-01`、端口 5319，以及独立文件根 `%TEMP%/codex-enf-followup-files-20260929-01`；源业务 DM8 为 5311，源/目标 DB 与文件根均不同。
- 独占报告：`DocumentTemplatePhysicalRestoreDm8Test-ENF12-FINAL-20260929-01`，`tests=1, failures=0, errors=0, skipped=0`，XML SHA `fb2bec2554dffe332eb57d58bf617c0ff6ae51e549848e26de13d3880352d764`。
- 实测 `PROJECT-FOLLOWUP-TEMPLATE-20260929-05` 恢复后为 `DISABLED`，4 条回执、4 条事件；原件及预览文件 SHA 与 DB 相符，恢复文件根无 `.tmp` 孤儿。
- 该恢复证明已执行能力，但因最终浏览器成功基线尚需包含真实预览下载，最终验收必须对最终成功任务重新制作一致性快照；不能复用本轮直接宣称任务完成。

## 3. 已修缺陷

1. 独立执法启动装配补入 ENF-12/17 configuration，消除真实产品 API 404。
2. ENF-12/17 工作面为每个写操作生成独立幂等键，消除固定 `Idempotency-Key: undefined` 导致的跨操作回执冲突。
3. ENF-17 目录补齐 `sources/provenance` 解析与校验，不把来源说明丢失后继续执行。
4. 模板列表核权使用合法版本 1，不再请求禁止的版本 0。
5. 恢复测试采用显式环境门禁；通用构建未注入设施时只保持未运行，最终独占报告必须且已经是零跳过。通用构建的 skip 不计为真实 I 通过。

## 4. 当前阻塞与自动恢复条件

2026-09-30 本机 Docker Desktop Linux Engine 退出；`com.docker.service` 为 `Stopped`，当前会话无管理员权限启动该服务。业务 DM8 5311、权威 DM8 5312 和恢复 DM8 5319均不可连接，5444 因权威库连接失败而启动失败。

设施恢复后的固定顺序：

1. 启动 Docker Desktop/`com.docker.service`，确认 5311、5312、5319 独立容器恢复；
2. 运行 `scripts/start-enforcement-acceptance.ps1`，确认 5444 HTTPS；
3. 运行 `scripts/start-enforcement-vite.ps1`，确认 5174 `/v2` 代理；
4. 以 `ENF_FOLLOWUP_RESUME=true` 继续 `PROJECT-FOLLOWUP-TEMPLATE-20260930-09`，取得真实 `/previews/{previewId}/file` GET、下载事件和 SHA；
5. 重做最终 DB+文件一致性物理快照及独占零跳过恢复测试；
6. Codex 具名复核通过后才完成 ENF-12，并启动 ENF-13；ENF-17 同理完成 HTTPS/浏览器/恢复后才放行 ENF-15/16。

## 5. 全仓校验

- 本轮 `node scripts/verify-all.mjs` 的治理基线与自测通过，但项目健康被 `TESTS_FAILING:project` 截断。
- 直接原因是并行全仓测试在未注入恢复环境时覆盖了新恢复测试的共享 XML；现已增加显式环境门禁并用独占后缀重新取得零跳过成功 XML。
- 这不等全仓已绿，也不以执法专项结果冒充全仓通过。未修改咨询或其他无关模块。

## 6. Codex 具名技术复核

复核人：Codex。结论：ENF-12 的领域、DM8、文件、OAuth/PKCE、上传、预览生成、发布/停用、原件下载和恢复主体实现成立；但浏览器预览下载与最终成功基线的共同恢复仍缺实际通过证据，故 `IMP-ENF-DOCUMENT-TEMPLATE-03` 不得标记完成。ENF-17 同样不得在 HTTPS/浏览器/恢复收口前完成。正式模板批准、正式法律适用及业务验收继续独立 `BUSINESS_ACCEPTANCE_PENDING`。
