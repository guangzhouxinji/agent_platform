# 裁决单案领域内核实施证据

2026-10-02，IMP-ADJ-V2-TASK-01A；唯一任务状态见[主计划](../../08-总开发实现计划.md)，不是新的状态台账。限定合同见[ADR D1](../../adr/adjudication-task-first-loop-20261002.md)及docs/02 §70。

## 代码与来源追踪

- ADJ-04/09：不可变CaseInput/State、双方Material、材料补正supersedes链及当前集合；每次输入变化递增inputVersion并保留原材料/报告，工作/材料状态不等正式状态。
- ADJ-05/06的首案人工直接选择切片：Citation原文/解释分离，来源id/版本/定位/摘要及用途时点绑定。无自动法规检索、法律适用认定。
- ADJ-07人工输入切片：Claim必要特征逐字核原文、人工选择理由、权项新版本保留历史。没有模型/OCR解析。
- ADJ-09辅助对应切片：Finding只保存人工关系与实际摘录，规则分别检查支持、反向、冲突、无法判断及缺一必要特征。材料不可读不展示正文，不借销售/照片默认推结构。
- ADJ-11/12工作报告切片：以实际输入生成六章节及来源，内容摘要UTF-8长度帧绑定；人工修改新增Report版本，旧稿/旧复核保留；确切当前版提交，全部输入贡献者/作者/提交者不能复核；退回与REVIEWED_NOT_OFFICIAL明确不写官方状态。
- ADJ-08：只输出OFFICIAL_STATUS_NOT_VERIFIED、STABILITY_AND_EQUIVALENCE_NOT_ANALYSED边界，未实现专业判断，不标该需求实现/验证。

实现文件为新增裁决域：

- [CaseWorkContract](../../../backend/adjudication/src/main/java/cn/xinji/agentplatform/adjudication/casework/CaseWorkContract.java)：不可变工作契约/历史与当前集合。
- [AdjudicationCaseWork](../../../backend/adjudication/src/main/java/cn/xinji/agentplatform/adjudication/casework/AdjudicationCaseWork.java)：实际命令/完备规则/报告/版本/复核。
- [CaseWorkIntegrity](../../../backend/adjudication/src/main/java/cn/xinji/agentplatform/adjudication/casework/CaseWorkIntegrity.java)：真实内容摘要与确定性编码。
- [单元/反例](../../../backend/adjudication/src/test/java/cn/xinji/agentplatform/adjudication/casework/AdjudicationCaseWorkTest.java)及[签入输入消费](../../../backend/adjudication/src/test/java/cn/xinji/agentplatform/adjudication/casework/CaseWorkInputPackTest.java)。pom只增本模块test范围依赖；无共享平台/旧V0/CON/ENF代码编辑。

## 实际执行

命令：mvn -f backend/pom.xml -pl adjudication,consultation,enforcement -am test。

[implementation-regression.log](implementation-regression.log)退出0：裁决41、咨询106、执法92，全部0失败/错误/跳过。裁决XML原件为[40项](TEST-AdjudicationCaseWorkTest.xml)与[1项](TEST-CaseWorkInputPackTest.xml)。该命令只声明这三个领域模块测试，未冒platform-api全量、真实DM8、浏览器或专业效果。

初轮32项实际通过；结构checker随后发现新领域类321行超300，按契约/命令/摘要职责拆分，不提高阈值。拆分首轮method reference指向旧类造成编译失败，原件[core-tests-split.log](core-tests-split.log)保留，修正引用后32项通过。再补8个源码版本冲突/来源性质冒充/失效材料/已提交旧稿/跨案/未知时点/配置与纠正反例，以及1个签入最小包消费，共41项通过。失败记录不计为通过。

## 数据、授权与未覆盖边界

[最小输入manifest](../../../data/adjudication/task-first-loop-input-v1/manifest.json)及[records](../../../data/adjudication/task-first-loop-input-v1/records.json)是一案、双方两份材料和一份原创工程权项文本，明确PROJECT_AUTHORED、notRealCase，非实际专利/法条/裁决金标，限项目内工程测试、不用于生产来源准入或外部模型。用户本轮第6项提供最小数据集制作授权，manifest记录责任/目的和真实字节SHA。测试显式消费，不自动进入产品来源；无真实法条、时点、地域及逐要素关系就按产品规则生成不完整辅助稿并拒提交，不能以数据替代规则。单元闭环中的原创法条样例也只是机械映射反例，不是业务结论。

actor字符串仅为领域审计输入，不是真实身份/grant证明。01B必须由可信JWT/session及当前逐案授权/来源守卫提供actor，并同库CAS/事务提交；本内核无Controller、存储Adapter或默认放行身份，不能称端到端已上线。真实权限、DM8恢复、HTTP与UI、专业方法/正式模板及业务验收全部未在本证据中宣称完成。

## 兼容与治理原件

[boundary-comparison.json](boundary-comparison.json)检查152条既有CON/ENF执行任务：状态、前后置无差异，没有引用新增裁决前置。初始255个两域业务文件中一个执法DocumentDraftWorkflow由并行工作独立更新，本轮未编辑/覆盖/回退，保留其当前成果；回归按实际当前源完成，不能声称该外部修改由本轮评审。共享平台、数据库、两域部署/路由和页面均未修改。

[首次校验](verify-initial-failure.log)因已有TEST_EVIDENCE_STALE失败，实际补跑[基线领域测试](baseline-tests.log)后[实施前校验](verify-before.log)通过。设计后曾遇并行咨询ProductPatentFieldRecoveryModelsTest缺H2表失败，保留[verify-design.log](verify-design.log)，没有修改咨询测试或隐去失败；其后其他工作修复该测试，本轮[verify-core-split.log](verify-core-split.log)通过。后续并行源变更触发stale的原件也保留，实际刷新当前三域测试后[verify-regression-refresh.log](verify-regression-refresh.log)通过。

最终代码/数据执行后[verify-implementation.log](verify-implementation.log)退出0，47需求来源文件/41台账基线一致。此为结构/工程C证据，不证明法律或专业正确，也不提升十二项完整ADJ D/V/A。主计划回写后再次verify-all另存verify-closeout.log，按其真实结果记录。
