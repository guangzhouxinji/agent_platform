# 当前最终产品检索链实测快照

日期：2026-10-02。对应主计划任务：IMP-CON-SEARCH-02。此文件只保存执行证据，不控制任务状态或另建门禁台账。

## 实际修正与发行

单交叉编码弱信号规则会误拒真实标题的同义请求 aqueous adhesive，失败v322保留、候选未部署。联合规则同时要求无词法支持、CE峰低于-3.923138及多语言余弦峰低于0.533658；两模型严格校准绑定，任一信号充分或边界相等保留。校准输入与原评价查询分离，原标签/阈值/失败分母未改。同义反例实际语义峰0.58210722001722810，CE峰-10.000466346740723，不按旧评价调参数。

正式维护增加显式来源Scope重授权：精确旧Scope/当前Task/当前Source版本，原子撤销旧Grant并追加Scope版本/审计/回执。普通authorize仍拒绝跨来源；原请求摘要不变，新请求含旧Scope版本。C及正式维护请求/同键重放通过，旧Scope版请求503拒绝。

正式发行v330 SHA256：5366f601539bda14cfdf56b9b15bcdb0ec539d274ecf7e6a8f649f073bd83c73。正式入口scripts/start-consultation-container.ps1实际替换精确旧应用，现时应用ID 4c1bb79fcc95ed6aebde72cfc1d5200a10f894624bfc7a53f5d21481f4a8fa88；原应用保留回退。原5301/5302数据库及卷未变。

单一Linux原件Root新增capture326和用途决议，旧清单逐SHA不变；共2523文件，完整清单source-v330.sha256的SHA为941c32e033ed84c9d16ba2c12e284a8e6afb5efcec8e663f55814e4bbe208de4。产品/恢复校验共用此Root，未使用Windows旧副本拼接。

## 真实数据及正式事务

capture326新采3个QUERY响应，44份DETAIL原件逐SHA复用；不是重新下载44份详情。records SHA为62e1b94b052a8e837888033bd788701b085f1e0eb82de15702a2b2c7383da462；实际44条标题与原固定Benchmark逐项相同。原合同/来源版本重复登记409后使用真实新采版本，未改唯一约束。

首批源经正式维护PRODUCT_ENABLED，合同capture-contract-326/epoch95，决议bd629412-7d6e-47df-b2e1-c0587f9d6708，决议原件SHA78afd8692509a04cafc2098748efa723a74b66ec96bfe82fd017fe4bfff28f44。rightsEvidenceStatus仍UNRESOLVED，仅TITLE/PUBLICATION_DATE/IPC/CPC×QUERY，不开放摘要/权项/正文/法律状态。决议于13:27:21+08到期；Task于13:34:26+08到期。以下成功均在有效窗口实际发生，不将已到期对象用于新的成功请求。

正式Task fb9ccb8f-e68a-4912-90ca-302921ce16b1、成员OAuth/PKCE、现时DataScope和正式Worker 8ee17251-d89e-4b3e-bf79-017e5a1bc79c形成三轮最终产品链：

| Task内容版 / 围栏 | 实际查询 | 结果集 | 候选 / 成员 | 结果 |
| --- | --- | --- | --- | --- |
| 3 / 2 | quantum teleportation entangled photon | e4c31160-8cbf-4d85-a56a-ce6fdfdb12a7 | 44 / 0 | NO_RESULT |
| 4 / 3 | aqueous adhesive | d5066e37-ca50-47ea-acbf-767f2da0cb30 | 44 / 44 | LOW_CONFIDENCE |
| 5 / 4 | lithium battery | 06155ab8-b430-42bb-a8e9-6e987f33de94 | 44 / 44 | NORMAL |

三轮方法均HYBRID_RRF_CE_SEMANTIC_ABSTAIN/v3，摘要版RESULT_DIGEST_DECIMAL_12/v2；实际提交/同键重放、正式成员读取和独立DM8实读一致，非空两轮均三页44唯一连续。跨Task404、页大小冲突/篡改游标409。选择revision1和2固定文献版/原件SHA/locator，继续后原选择仍可按原结果集读取。错配新结果集读取旧选择404，不是旧记录丢失；验证调用已按正确原结果集重读200。

读取脚本第一次未加分页校验而错误期望首页面返回全部44，正式服务实际正常返回20；补正确分页参数后通过，未修改产品分页行为。旧Worker注册期限已过，续Task Grant503；正式撤权记录保留，随后用既有正式初始化机制创建现时Worker41，没有旁路或测试ACTIVE。

证据：本目录patent-search-result/replay/read-v326/v332/v333.json、selection-receipt-v332/v333.json、selection-history-v332-after-v333.json，以及search-two-signal-dm8-results-v336.log / search-source-reauthorization-dm8-v336.log。DM8证明Scope1～4及对应QUERY Grant全部REVOKED，Scope5为现时版本；这些状态实读不等于到期后的授权仍有效。

## 原不可变Benchmark与回归

V1 manifest SHA a55277e50d480a67a3041d66c6a1b8305350118592b85025714b981be0616c2d；V2 SHA d5cb25ff849c44833d680deec4eefd774625f512232943c67a7c18979894a8fd。仅修改评价程序纳入原负例和完整模型身份，不改原查询、标签、阈值或分母。

- V1全部10查询（9正/1负），空结果正确率1；正例Recall@10=.925926、Recall@50/100=1，Precision@10=.30/@20=.161111，nDCG@10=.905338/@20=.929677，MRR@10=.902778。原RRF nDCG@10=.956486，当前重排并未改善该小集合排序，不写成提升。
- V2全部5查询（4正/1负），Recall@50=1、Precision@10=.30、nDCG@10=1、负例空结果正确率1；独立同义LOW反例非空。真实模型100次一致，v335 p95=245.516ms/p99=249.262ms；这是检索组件时延，不是HTTP页面p95。既有适用HTTP页面SLO证据按主计划复用。
- v324定向/真实模型、v325全量、v329重授权定向、v331受影响后端全量、v335完整原评价与恢复文件C、v337逐查询完整指标实际退出0。全量一次性schema TST1A6B3C5C / TST20052217已清理。
- v334 verify-all实际退出0，47需求文件/41需求基线一致，未降低阈值或删除失败原件。原条件跳过不计实测通过。

完整逐查询指标见search-complete-quality-metrics-v337.log；定向/全量原日志保存在当前部署Vault的evidence目录，原v321/v322失败亦保留。

## 恢复一致性及边界

正式只读--task-recovery-verify使用CONPROD_BKP/CONAUTH_BKP、独立现时水位、同一完整Root、原历史模型及当前发行。v338失败原因是调用缺少历史模型挂载（原失败保留）；v339补现有模型/参数后实际退出0，检索结果/选择/版本/Grant/Source/原件的历史恢复闭包校验通过。未写DB，也未进行新的DB导入恢复，不能据此冒称新实例恢复；已过v39物理恢复复用，v305新包封装/提取亦不升级为新导入。

这是当前环境最终产品机制的技术证据，不是未来生产部署、第三方全部权利、专家相关性金标、专利法律结论或业务验收。全文/权项字段准入和完整专业/人工/成果分支仍依主计划与对应门禁执行。
