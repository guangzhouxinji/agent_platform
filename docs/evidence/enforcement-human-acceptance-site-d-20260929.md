# 执法 V2 真人业务验收现场窄 D

- 任务：`ARC-ENF-HUMAN-ACCEPTANCE-SITE-D-04`。
- 日期：2026-09-29。
- 责任：Codex 技术复核；真实人员、岗位任命与操作结果仍由实际业务主体负责。
- 目标：`ENF-RBC-CNIPA-GUIDANCE-CASE-1`，包 `ENF-JOB-CNIPA-GUIDANCE-CASE-1`。

## 冻结现场合同

1. 只使用已有产品 Issuer、OAuth/PKCE、Subject、Session、Grant、Job Pack、Evidence、Receipt、Event 和 Integration Trigger。不建第二套身份、审批或审计体系。
2. 真实人员的登记、激活与 Grant 只走已有 `/v2/identity` 维护 API；不手改 DB。测试身份只验证机制，不计真人输入。
3. 领取人提交 `ACTOR_POSITION`、`HUMAN_RECEIPT`、`BUSINESS_EVALUATION`；另一真实人员提交 `APPROVAL`。领取人不得自批，任一事实的提交人不得自验。
4. 两人交叉完成异人核验：批准人核验领取人提交的三项，领取人核验批准人提交的批准。
5. 只有四类人工事实的最新版全部有效且异人验证通过，才由领域服务派生 `READY`；最后核验事务内写入唯一 `NEXT-V2-INT-ENF-01` 触发。
6. 未领取的公开来源待办对已登录主体可见，领取仍必须同时通过确切组织与包的 `CREATE/COLLECT/READ` Grant；已领取任务仅对拥有该包现时 `READ` Grant 的主体可见，使第二人可直达而无授权主体不得从列表获取已领取任务。
7. 界面退出必须调用 `/v2/identity/logout` 撤销当前 bearer session 后再清理本地受限内容。

## 失败与禁止副作用

- 无现时 Grant、无有效 Session、自批、自验、旧内容版、过期岗位、证据 SHA 不匹配或并发版本冲突均失败关闭。
- 失败不得新增任务包版、人工事实、成功回执、事件或集成触发。
- 任何机制测试不得将 `DATA-ENF-JOB-PACK-01` 改为 READY；现场前仍保持 `WAITING_REAL_HUMAN_ACTION / BLOCKED`。

## D 结论

输入、身份、授权、两人职责、界面路径、审计、派生状态、幂等触发和失败副作用已固定。`ARC-ENF-HUMAN-ACCEPTANCE-SITE-D-04` 通过，只放行 `IMP-ENF-HUMAN-ACCEPTANCE-SITE-04`的现场就绪收口，不生成任何真人事实。
