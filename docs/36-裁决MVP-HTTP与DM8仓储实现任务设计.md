# 裁决 MVP HTTP 与 DM8 仓储实现任务设计

版本：V0.1  
日期：2026-09-16  
关联任务：`MVP-ADJ-V0-03-DESIGN`  
目标实现任务：`MVP-ADJ-V0-03`  
设计输入：`docs/32` 至 `docs/35`、已通过的 `MVP-ADJ-V0-02`。  
设计性质：只批准合成 V0 的本地 HTTP/DM8 适配器；不批准真实中心 API、案件/法规/模板数据、正式裁决、签发、文件、模型或专业审核实现。

## 1. 仓储端口与并发收敛

`AdjudicationStore.StoredWork` 增加独立 `persistenceVersion`；`save` 接受调用方读取到的 `expectedPersistenceVersion`。创建工作使用 0，后续写入必须执行：

```text
UPDATE adj_work_item
SET row_version = row_version + 1
WHERE work_item_id = ? AND row_version = ?
```

影响行数不是 1 时整笔事务回滚并返回 `VERSION_CONFLICT`。业务对象的引用/草稿版本仍由领域层校验；`row_version` 只处理多实例并发，不能成为业务版本或正式状态。

同一数据库事务必须完成：锁定/创建工作主行、按当前不可变快照重建 14 表记录、写入幂等绑定及精确重放所需的合成快照。任何明细、约束、序列化或幂等写入失败，工作版本、领域明细和幂等记录全部回滚。相同主体/操作/键与相同指纹返回原快照；不同指纹返回 `IDEMPOTENCY_CONFLICT`。

仓储重建必须恢复所有集合顺序、来源版本、前序引用、草稿章节和测试复核；不能把“最新版本”替换为历史操作针对的确切版本。当前授权仍由读取用例每次重新检查，DM8 所有者字段不是永久权限。

## 2. 最小 HTTP 范围

统一前缀 `/api/v0/adjudication`，可信测试主体由请求头到服务端上下文的现有本地机制提供；请求体不得提交角色、授权结论、复核人、正式状态或签发信息。

| 路由 | 应用服务 | 关键输入 |
|---|---|---|
| `POST /work-items` | `openWork` | 案件/材料受控引用，`Idempotency-Key` |
| `POST /work-items/{id}/material-inspections` | `inspectMaterials` | 幂等键 |
| `POST /work-items/{id}/legal-candidate-sets` | `matchLegalCandidates` | `inspectionVersion`、幂等键 |
| `POST /work-items/{id}/citations/actions` | `cite` | `expectedVersion`、候选代码、动作、理由、幂等键 |
| `POST /work-items/{id}/drafts` | `createDraft` | `inspectionVersion`、幂等键 |
| `POST /work-items/{id}/drafts/actions` | `reviseDraft` | `expectedVersion`、替换内容、理由、幂等键 |
| `POST /work-items/{id}/drafts/test-reviews` | `reviewDraft` | 确切草稿版本、`CONFIRM/RETURN`、理由、幂等键 |
| `GET /work-items/{id}` | `read` | 当前授权上下文 |
| `GET /work-items/{id}/{resource}/{resourceId}/versions/{version}` | `readVersion` | 固定资源白名单与确切版本 |

响应固定包含 `SYNTHETIC_V0`、`NOT_OFFICIAL_DECISION`。不实现来源正文下载、中心写回、模板管理、DOCX/PDF、签发、裁决、送达、办结、归档或通用 Task/Runtime API。

## 3. 错误映射

| 领域错误 | HTTP | 边界 |
|---|---:|---|
| `INVALID_INPUT`、`REASON_REQUIRED`、`INVALID_ACTION` | 400 | 不写任何记录。 |
| `UNAUTHENTICATED` | 401 | 不接受请求体身份替代。 |
| `UNAUTHORIZED` | 403 | 不泄露受限正文或对象细节。 |
| `NOT_FOUND`、`SOURCE_NOT_FOUND` | 404 | 安全策略可统一隐藏对象存在性。 |
| `SOURCE_UNAVAILABLE`、`MATERIALS_INSUFFICIENT`、`VERSION_CONFLICT`、`IDEMPOTENCY_CONFLICT` | 409 | 分类稳定，不覆盖当前数据。 |
| `UPSTREAM_FAILURE` | 502 | 不伪装为空结果。 |

所有错误返回 `requestId`、稳定 `code` 和安全消息，不返回 SQL、磁盘路径、凭据或受限正文。

## 4. 实现测试矩阵

| ID | 场景 | 退出断言 |
|---|---|---|
| ADJ-DB-01 | 正常快照往返 | 14 表重建结果与内存快照字段/顺序一致。 |
| ADJ-DB-02 | 缺项/冲突往返 | 两类问题及来源不丢失，确认仍被阻止。 |
| ADJ-DB-03 | 草稿修订与历史读取 | 两版草稿、章节、来源、差异和前序引用无损恢复。 |
| ADJ-DB-04 | 测试复核往返 | 确切草稿版本、理由、主体和非正式标识恢复。 |
| ADJ-DB-05 | 同键同输入跨实例重放 | 返回原对象和原版本，不重复写明细。 |
| ADJ-DB-06 | 同键异输入 | `IDEMPOTENCY_CONFLICT`，原快照不变。 |
| ADJ-DB-07 | 两连接乐观冲突 | 至多一方提交，另一方 `VERSION_CONFLICT`。 |
| ADJ-DB-08 | 中途约束失败 | 工作版本、明细和幂等记录全部回滚。 |
| ADJ-DB-09 | 新仓储实例恢复 | 进程内存清空后可读取当前及精确历史版本。 |
| ADJ-HTTP-01 | 正常九路由链 | HTTP→Service→DM8 完整，限制标识始终存在。 |
| ADJ-HTTP-02 | 无权/不存在/失效 | 状态码和错误码稳定，不泄露正文。 |
| ADJ-HTTP-03 | 版本/幂等冲突 | 返回 409，数据库历史不被覆盖。 |
| ADJ-ARCH-01 | 正式边界 | 无签发、裁决、办结、中心写回路由或字段。 |

实现完成前必须运行裁决专项、全量 Java/真实 DM8 和治理校验；浏览器测试属于后续工作台任务。

## 5. D 门结论

前置样例、领域、用例、API、逻辑记录、14 表物理模型和本地 DM8 约束均有证据；仓储事务、跨实例版本、HTTP 范围、错误映射、13 项测试及排除项已固定。新增行为均为可撤销的本地技术适配，未新增法律、权限或正式流程语义。

结论：`MVP-ADJ-V0-03` 的 D 门只对本文限定的合成 V0 本地仓储和 HTTP 适配器通过。可以实现 DM8 仓储、Spring 装配、九路由控制器和自动测试；不得将其外推为 ADJ-04/05/06/10/11/12 整项 D/V/A、真实中心接入或业务验收。
