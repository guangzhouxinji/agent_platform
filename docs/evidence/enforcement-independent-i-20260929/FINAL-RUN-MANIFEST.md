# 执法 V2 真实 I 最终运行清单

- 执行编号：`ENF-I-FINAL-20260929-03`
- 执行日期：2026-09-29
- 执行与具名技术复核：Codex
- 范围：`IMP-ENF-PROCEDURE-02`、`IMP-ENF-CONDUCT-02`
- 结论：两项 IMP 冻结范围真实 I 通过；后继任务未在本轮启动。

## 隔离设施与 Gate

| 用途 | 实例 / Gate | 独立存储 | 最终状态 |
| --- | --- | --- | --- |
| 业务 DM8 | `codex-enf-i-business:5311` | `codex-enf-i-business-data` | RUNNING |
| 权威 DM8 | `codex-enf-i-authority:5312` | `codex-enf-i-authority-data` | RUNNING，恢复时未回灌 |
| 程序恢复 DM8 | `codex-enf-i-procedure-restore-3:5317` | `codex-enf-i-procedure-restore-3-data` | RUNNING |
| 行为恢复 DM8 | `codex-enf-i-conduct-restore-3:5318` | `codex-enf-i-conduct-restore-3-data` | RUNNING |
| 程序 Gate | `%TEMP%/codex-enf-procedure-i-20260929-03/checkpoint` | 独立恢复文件根，1 份原件 | `restore-ready=true`、`withdrawn=true`、`verified=true` |
| 行为 Gate | `%TEMP%/codex-enf-conduct-i-20260929-03/checkpoint` | 独立恢复文件根，3 份原件 | `resume=true`、`restore-ready=true`、`withdrawn=true`、`verified=true` |

## Surefire 结果与反覆盖绑定

XML 在每个成功进程退出后立即复制到本目录。所有文件均解析为 `tests=1`、`failures=0`、`errors=0`、`skipped=0`，并包含对应测试断言后的 `ENF_*` 完成标记。文件 UTC 写入时间、测试类和摘要共同绑定本轮，不使用后来可能被覆盖的共享 `target/surefire-reports`。

| 文件 | UTC 写入时间 | 测试类 / 用例 | SHA-256 |
| --- | --- | --- | --- |
| `procedure-dual.xml` | `2026-09-29T03:31:00.0175322Z` | `ProcedureIndependentDm8HttpTest.independentHttpsNodesReplayOnceAndRejectWithdrawnOldKey` | `165AAC8DABDD12CB279DC43724471E38E4D4686A203BD8683C6A4C5F30D1227D` |
| `procedure-producer.xml` | `2026-09-29T03:36:56.4168492Z` | `ProcedureIndependentDm8HttpTest.independentHttpsNodesReplayOnceAndRejectWithdrawnOldKey` | `BF4833ACD1E434E45DD38E1EA36E5AB49064E5FACF6E1CC50670FA069B92707C` |
| `procedure-restore.xml` | `2026-09-29T03:36:54.8965602Z` | `ProcedureIndependentRestoreDm8Test.physicalDbAndOriginalRestoreObeysUnrestoredAuthorityAndFailsClosed` | `1BBD63B751B0C6522FA15946F1C1264FE85FB5EDF8A9BD9EADFDE0DA0F45FE45` |
| `conduct-dual.xml` | `2026-09-29T03:32:21.0986431Z` | `ConductIndependentDm8HttpTest.twoIndependentHttpsJvmsReconcileReviewAndCurrentAuthority` | `3FAD6D4805DA383BAEDE29F1B02E9F39C75F6D202B884E2658D9A8687C0EC081` |
| `conduct-producer.xml` | `2026-09-29T03:39:25.1541484Z` | `ConductIndependentDm8HttpTest.twoIndependentHttpsJvmsReconcileReviewAndCurrentAuthority` | `039F24FC55D1FC59BEBC74C22819FC49C41AA29E1F85AFC88190E5267E34D866` |
| `conduct-restore.xml` | `2026-09-29T03:39:24.0032946Z` | `ConductIndependentRestoreDm8Test.physicalDbAndOriginalsRestoreWhileCurrentAuthorityNeverRollsBack` | `4266A699386CF82C66A5A5253463F860B9493623F37D39FAA9D2C5CCB1837860` |

## 对账与失败关闭

- 程序：恢复后报告 1、回执 4、事件 4、原件 1；原件 SHA-256 为 `13232E21158563F603515832514B58FA9F7A17CC0B4E19996F816D596889D6CC`。报告读取 200，缺原件 503，还原后 200，备份外权威撤权后 503 空正文；失败路径不新增回执或事件。
- 行为：恢复后报告 2、回执 4、事件 4、原件 3；三份原件 SHA-256 为 `EB4379058974551BD3318C5ABBFDA277EA83CF4EDB74A11EB7C4E85AE8D420E9`、`E6EA3D535970CFF67C9C10247444561E6AA7526008BEB9E97EF1DA24EABDFB5A`、`1E4711D03DC5785001B290E23F02903472E2B2B9AB3AC08C8003E0D7BAD2E571`。同键复核 200，缺原件 503，备份外权威撤权后 GET/旧 POST 均 503 空正文；失败路径不新增报告、回执或事件。
- 双 JVM：程序重放 200/200、撤权 403/403；行为读取 200/200、复核 200/200、缺原件 503/503、撤权 403/403。没有 H2、Mock、Stub、Synthetic 或 Skip 替代真实 I。

## 具名技术复核

Codex 于 2026-09-29 对照 `docs/57-V2执法全能力详细设计.md` 第 6.26、6.27 节及 P01-P06 / C01-C06 冻结合同复核：真实双 JVM、双 DM8、第三恢复 DM8、DB 与原件共同物理恢复、当前授权水位、回执/事件以及禁止副作用均有实际执行证据；六份 XML 全部零跳过且摘要固定。未发现未覆盖冻结项、MUST_NOT 违规或无来源新增行为。`IMP-ENF-PROCEDURE-02` 与 `IMP-ENF-CONDUCT-02` 最终开发验收通过。

真实法定效力、真实历史权利、现实岗位和业务 B 不在本开发验收结论内。
