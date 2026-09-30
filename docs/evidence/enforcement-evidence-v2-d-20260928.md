# Evidence V2产品D独立复判（2026-09-28）

执行者：Codex，执法开发技术与项目文档一致性方法审查；不是现实执法/法律专家签收。范围仅ENF-06/07的`ARC-ENF-EVIDENCE-02`。原始依据为ZF-06/07及总览，项目负责人边界见docs/02第45节，最终合同见docs/57第6.24节；MATERIAL唯一实现冻结，不复用其无ANALYZE用途的317字节原件。

| D审查项 | 实际输入/设计核对 | 结论和禁止越界 |
|---|---|---|
| 案件与规则 | 项目`PROJECT_DOCUMENT_REVIEW`快照v1，两必要事项、非法律项目方法版本1、三份原件与正反关系；发布须核字典/依据/映射/正反边界，试跑零案件写 | 合同可执行；规则仍为DRAFT输入，产品发布是C/I，不是假已发布法定规则 |
| 材料与关系 | A一份关联两事项，B同标识/时间矛盾，C无关；三份确切字节摘要、行定位和逐用途许可由新包固定 | 预期关系不是产品分析；无原件/失权/跨案/错版不能生成成功分析或回执 |
| 状态与人工 | 不可变发布版、EvidenceSet锁版、机器/来源提示/人工修订分层，审核待审核/确认/退回，排除与规则变化标需复核 | 不覆盖旧机器/人工版本，不把材料数量或预填关系当充分性 |
| API/授权/恢复 | docs/57第6.24节定义规则/整理/修订/审核V2入口、确切Grant与三次现时复核、事务回执、独立水位及DB/文件共同恢复闭包 | 未实现、未运行；E01～E05 C/I/HTTP/E2E均NOT_RUN，不用MATERIAL执行证据冒充 |
| 真实业务边界 | 项目案件/身份/规则/原件仅在声明范围，不具中心案件、第三方权利、法定效力、生产部署批准 | 现实启用及专家B独立，不是本产品D前置 |

`node scripts/verify-enforcement-evidence-acceptance-input.mjs --self-test`实际退出0：1个有效包、6个拒绝（越用途、伪中心案件、伪发布、字节篡改、定位错位、关系缺失），只证明输入自洽，未调用产品。三份原件SHA和限制见manifest；不使用Synthetic投影/V0/历史案例。D语义复核结论：冻结ENF-06/07首切片的输入、状态、权限、故障、恢复、人工边界与E01～E05退出条件已明确，无需要伪造现实资格的产品D阻塞项。允许`ARC-ENF-EVIDENCE-02`通过并进入唯一`IMP-ENF-EVIDENCE-02`实现；实现必须先实测规则发布/停用、EvidenceSet及逐事项正反关系、人工修订/重跑、撤权/回执/恢复，不得提前标C/I或最终验收。

## IMP-ENF-EVIDENCE-02 首条草稿产品闭环（进行中）

- 唯一V2产品新增独立`enf4_evidence_rule/matter/receipt/event`及八桶事务回执锁的V050/U050；规则草稿与读取服务只依`EvidenceRuleStore`和确切授权端口，适配器在DM8事务内锁、查同键、插规则/事项/回执/事件。没有复写MATERIAL表/服务，没有发布或整理案件的默认分支。V2 `/evidence-rules`及`/{id}/versions/{revision}`走现有产品TLS/JWT和`EVIDENCE_RULE`资源精确Grant。
- C预期/实际：合法草稿v1、两事项可读；同键同载荷原回执，异载荷冲突，撤权后旧键拒绝，规则/回执/事件各一；H2迁移正逆及`EvidenceRuleProductTest`1/1零失败/跳过。I预期/实际：执法专用5257 DM8随机用户执行V050，第二独立JDBC/仓储重放与读取一致、异载荷拒绝、回执/事件各一，U050逆迁移与临时用户清理；`EvidenceRuleDm8Test`1/1零失败/跳过。该I的授权为测试端口，不冒称双库产品授权I。
- HTTPS预期/实际：独立TLS产品Issuer登录/PKCE，未登录401、缺`EVIDENCE_RULE` Grant403且回执0；具名成员授EDIT_RULE后POST草稿/同键200、授READ_RULE后GET版本200；撤EDIT_RULE后旧POST403，回执/事件各1且无额外规则。输出`ENF_V2_EVIDENCE_RULE_HTTP login=PKCE draft=DRAFT replay=SAME withdrawal=403 receipts=1 events=1`；`EvidenceRuleHttpsTest`1/1零失败/跳过。该HTTP使用H2双库，和上述真实DM8持久化I为不同层次。
- 本闭环只完成规则草稿/读取/事务回执，不是E01发布前审核或E02 active/disabled，也没有ENF-07 EvidenceSet、关系/缺口/人工修订、浏览器或恢复演练；E01～E05仍未全通过，`IMP-ENF-EVIDENCE-02`不得标完成。正式案件/规则/岗位/法律效力/业务B不由项目测试取得。
- 冻结草稿失败边界补测：未知规则统一拒读，已存在同ID/版换新请求键返回冲突，事务无新回执/事件；原键异载荷仍冲突。`EvidenceRuleProductTest,EvidenceRuleDm8Test,EvidenceRuleHttpsTest`在执法5257真实DM8及登录TLS上重跑各1/1、零失败/错误/跳过；HTTP新键为409，撤权旧键403，回执/事件仍各1。没有改MATERIAL服务或表。

## ENF-06发布与停用首批产品C/I（进行中）

- 执行者：Codex，2026-09-28；范围是唯一最终产品的规则生命周期，不是现实案件或法律效力审核。V051/U051为不可变发布版补`enf4_evidence_publication`审核依据版本、范围、审核Ref和输入摘要，停用另记`enf4_evidence_disabled`，旧规则正文不改；没有修改MATERIAL。发布入口要求项目显式批准的案件类型/依据Ref/版本/范围配置（缺配置失败关闭）、精确`PUBLISH_RULE` Grant、至少一正一反例且逐事项覆盖/缺口预期与规则必要事项一致；试错不写规则、审核、事件或回执。停用另需v2精确`DISABLE_RULE` Grant；旧发布版可按现时READ权读取且状态为DISABLED，不可用于将来新整理。
- C预期/实际：v1草稿两必要事项，错误反例缺口与错误依据版本分别拒绝，发布前`enf4_evidence_publication=0`；有效正例覆盖两项、反例缺EVENT_TIME后发布为v2 ACTIVE，同键重放不新增；停用为DISABLED，同键重放不新增，异键重复停用冲突，v1草稿正文不变；规则2、发布记录1、停用记录1、回执/事件各3，撤权后旧草稿回执拒绝。`EvidenceRuleProductTest`1/1零失败/跳过，V050/V051正逆迁移通过。
- I预期/实际：执法专用`127.0.0.1:5257` DM8随机用户、V050/V051正向迁移，两个独立JDBC/仓储实例分别发布、同键重放和停用，跨连接读取ACTIVE→DISABLED、回执/事件各3；U051/U050逆迁移和临时用户清理。`EvidenceRuleDm8Test`1/1零失败/错误/跳过。测试授权端口不冒称独立权威库的产品授权I。
- HTTPS预期/实际：独立TLS产品Issuer登录/PKCE，经`/v2/enforcement/evidence-rules/{id}/publications`及`/{id}/versions/2/disable`实发请求：未授发布/停用Grant均403，错误正反例409且发布记录0；授精确Grant后发布200、同键200、版本GET ACTIVE，停用200后GET DISABLED；撤发布Grant后旧发布键403，撤编辑Grant后旧草稿键403，规则/发布/停用各2/1/1、回执/事件各3不增。输出`ENF_V2_EVIDENCE_RULE_HTTP login=PKCE draft=DRAFT publication=ACTIVE disable=DISABLED withdrawal=403 receipts=3 events=3`；`EvidenceRuleHttpsTest`1/1零失败/跳过，HTTPS测试使用隔离H2业务/权威库，不冒称DM8 HTTP。
- 执法三项专项在5257凭据仅临时进程环境注入后退出0，零失败/错误/跳过；`mvn -q -f backend/pom.xml test`全量退出0；本轮`verify-all`在主计划/证据落账前退出0（47基线、41需求、619任务）。E01只完成正反例发布校验，尚缺只读试跑/完整映射及页面；E02已有发布/停用初测，但版本依据读取、进一步双实例授权与恢复未闭合；E03～E05未执行。`IMP-ENF-EVIDENCE-02`继续进行中，不标最终开发验收，不扩ENF-08～17。
- E02审核依据读取增量：`GET /v2/enforcement/evidence-rules/{id}/versions/{revision}/publication`沿用现时精确`READ_RULE` Grant，只对已发布版返回basisVersion、scope、reviewRef、reviewDigest及reviewedBy；草稿无发布元数据。H2 C核64位摘要与REVIEW-001、停用前后不可变；专用5257 DM8第二连接读范围、第一连接停用后仍读同一审核Ref；登录PKCE HTTPS读摘要和停用后basisVersion。三项定向各1/1零失败/错误/跳过，前置`verify-all`退出0。E02仍缺双独立产品实例授权时序及恢复实测，不能判全过。
- 上述依据读取变更后再次运行`mvn -q -f backend/pom.xml test`全量退出0；不把全量中未配置专用DM8而跳过的I当作专项证据，专项5257运行已单独零跳过。此前主计划落账后`verify-all`退出0。

## E01锁定实际原件只读试跑（进行中）

- 执行者：Codex，2026-09-28。规则草稿`expectedMaterial`的`line-prefix:`映射是项目文档方法的显式可配置提取条件，不取预填的`expectedRelations`作为产品分析。`EvidenceRuleTrial`只依来源端口；配置的只读文件Adapter逐次核manifest的ANALYZE用途、案件快照、逐原件Ref/v1/SHA/长度、实际字节和撤回/有效期，再从实际A/B/C文本提取行值。输入包新增案件事件Ref `PROJECT-EVENT-001`以区分跨事件C；原件字节/SHA未改。该Adapter只提供声明用途的试跑输入，不把`INPUT_ONLY_NOT_VERIFIED`升成EvidenceVersion，后续真实来源可换Adapter，同一领域试跑语义不变。
- 预期/实际：A/B均有ITEM-001且时间分别10:00与11:00；逐事项返回标识`PRESENT`、时间`CONFLICT`及A/B各自Ref、v1、SHA、行3/4定位和值；C事件不匹配列`unrelatedMaterials`，只选C时两事项均`MISSING`，无事实确认/证明关系/完整证据链结论。错误SHA被拒，案例READ/ANALYZE Grant撤回后旧试跑403；试跑前后`enf4_evidence_receipt`仍只有草稿1条，不新增规则/发布/分析记录。V2入口`POST /v2/enforcement/evidence-rules/{id}/versions/{revision}/trials`通过登录Issuer/PKCE、规则READ、CASE READ/ANALYZE和逐原件EVIDENCE READ/ANALYZE精确Grant，返回前再核现时授权。
- `node scripts/verify-enforcement-evidence-acceptance-input.mjs --self-test`1正6拒退出0；`EvidenceRuleProductTest,EvidenceRuleDm8Test,EvidenceRuleHttpsTest`在专用5257 DM8零失败/错误/跳过，各1/1；HTTPS业务及独立权威为隔离H2，DM8 I为独立双JDBC随机用户，二者不互冒。前置`verify-all`退出0（47基线、41需求、619任务）。E01只读试跑子项已实测，不代表E01页面/全部负例或E02双实例/恢复已收口；E03～E05仍未开始。

## E03/E04确切EvidenceSet首条产品组织闭环（进行中）

- 执行者：Codex，2026-09-28。V052/U052新增ENF域组织修订、事务回执和事件。新A/B/C原件先通过冻结MATERIAL现有`registerSource→observe→draftRule/publish→discover→registerMaterial→COLLECT_ATTEST→异人VERIFY`产品服务形成实际`VERIFIED v3`，用途显式ANALYZE；MATERIAL代码、表与合同未修改。Evidence自己的案件快照文件Adapter绑定案件Ref/v1、类型、组织、事件Ref及逐个新材料ID/v3/SHA；组织服务再核发布规则ACTIVE、CASE READ+ORGANIZE、逐原件READ+ANALYZE、核验动作/异人/来源与私有字节、独立现时来源水位，并在命令入口、事务内及返回前复核。案件组织与规则组织不符拒绝。仅引用原件，不复制文件。
- C预期/实际：三份确切v3原件锁定后，逐事项分别有ITEM_IDENTITY的支持/补强候选、EVENT_TIME的支持/矛盾候选及A/B行定位/原值；C跨事件记录对两事项均`IRRELEVANT_CANDIDATE`，单列`unrelated`，不冒充未处理或覆盖。分析状态`PENDING_REVIEW`，`originKind=MACHINE_CANDIDATE`，不作采信；事实发生/收集/上传三类时间分栏，C的事实时间未知不补造。错SHA、跨组织均拒且组织/回执/事件仍各1，同键同载荷原回执，撤权后旧回执及详情拒绝。`EvidenceOrganizationProductTest`1/1零失败/跳过，V035/V050/V051/V052实际产品服务及私有文件链；产品读取与另一JDBC仓储结果一致。
- I预期/实际：专用执法5257 DM8随机用户正向V035/V050/V051/V052，实际产品命令登记/收集/核验三原件与Evidence组织，第二独立JDBC读取相同修订/时间/关系，U052→U035逆迁移并清理随机用户；`EvidenceOrganizationDm8Test`1/1零失败/错误/跳过。该I对MATERIAL服务和Evidence使用测试授权端口，不冒称真实双库Grant I。
- HTTP预期/实际：独立TLS产品Issuer登录/PKCE，Evidence组织POST/同键、GET详情经精确CASE/EVIDENCE/EVIDENCE_ORGANIZATION及规则Grant和独立来源水位返回200；错误SHA403且回执不增，撤CASE/ORGANIZE Grant后旧同键403，发布规则停用后历史组织详情仍200；组织/回执/事件各1、MATERIAL原件/私有blob各3且Evidence未增文件。MATERIAL样本准备确实调用其产品服务，但使用测试授权端口，HTTPS的Evidence授权与双库水位为真实产品入口；不能把此测试冒称MATERIAL端到端HTTP。`EvidenceRuleHttpsTest`1/1零失败/跳过。
- 五项Evidence专项在专用5257配置下各1/1零失败/错误/跳过；首次组织HTTPS因测试复发CASE READ epoch误用0，以及孤儿清理API误断新原件数，均更正测试序列/断言并重跑通过。`verify-all`前后结构硬门通过，47基线、41需求、619任务。本闭环只覆盖E03关系与E04锁版/三时间的部分正反例；未测排除后缺口、不可解析材料、浏览器/分页、双实例撤权、人工修订/恢复，E03～E05不得标全过。

## E05人工修订与独立审核首段（进行中）

- 执行者：Codex，2026-09-28；输入为上述通过MATERIAL产品服务实际核验的三份v3原件及Evidence组织v1。预期/实际：具名修订者对A的EVENT_TIME原文`line 4`追加`UNVERIFIED`和理由，生成v2，v1机器候选仍可按确切版本读取；具名不同审核者对v2确认生成v3，保留人工关系、审核理由和审核者。旧期望版、空/错原文定位及同人自审被拒，不产生修订；同键同载荷回放原回执，组织修订/回执/事件各3，错SHA和跨组织请求也未增加这些计数。撤权后旧修订回执与详情拒绝，未以旧许可放行。
- 实际命令：专用5257 DM8容器的密码仅注入测试进程环境，`mvn -q -f backend/pom.xml -pl apps/platform-api -am '-Dtest=EvidenceOrganizationProductTest,EvidenceOrganizationDm8Test,EvidenceRuleHttpsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`退出0；Surefire三项各tests=1、errors=0、failures=0、skipped=0。H2 C核v1/v2/v3和事务计数；DM8 I使用随机用户及第二独立连接读v3并逆迁移；HTTPS经两个真实测试成员分别登录PKCE并授确切版本Grant执行修订、审核与拒绝。测试身份不是现实岗位，H2 HTTPS与5257 DM8 I不互冒。前置`verify-all`退出0（47基线、41需求、619任务）。E05的排除/撤权依赖失效、重跑、双实例及恢复后回执尚未执行；本段不构成E05全过或最终开发验收。

## E03/E04组合反例增量（进行中）

- 执行者：Codex，2026-09-28；沿用产品服务生成并核验的A/B/C v3原件。预期/实际：只选跨事件C创建另一个确切EvidenceSet，两个事项均`MISSING`，仅有逐事项`IRRELEVANT_CANDIDATE`，状态仍`PENDING_REVIEW`而非事实完整；A错v2（实际核验v3）与错SHA均被拒；停用已发布v2规则后新组织被拒，旧组织v1和人工历史仍可读。拒绝请求不生成修订、回执、事件：一条主组织的v1/v2/v3加一条C缺口组织v1，三表均恰为4；无Evidence新文件，也未改MATERIAL。测试使用现有组织服务与真实产品原件链，不把规则草稿或预填关系当结论。
- `mvn -q -f backend/pom.xml -pl apps/platform-api -am '-Dtest=EvidenceOrganizationProductTest,EvidenceOrganizationDm8Test' '-Dsurefire.failIfNoSpecifiedTests=false' test`在专用5257 DM8临时环境凭据下退出0；Surefire H2 C和独立DM8双连接I各tests=1、errors=0、failures=0、skipped=0。前置`verify-all`退出0（47基线、41需求、619任务）。这没有覆盖E03所有专业反例、E04不可解析/分页/浏览器或E05依赖失效与恢复，均继续NOT_DONE。

## E05排除材料后的有效集合重算（进行中）

- 执行者：Codex，2026-09-28。冻结合同的排除动作在Evidence组织追加v4，不删除MATERIAL原件、不覆写v1机器/v2人工/v3异人审核。输入是已核验A/B/C v3、已确认组织v3及具名修订者的B排除原因；预期/实际：v4的EvidenceSet仅A/C，B的候选关系及三类时间不再进入新版，两个事项由`CONFLICT/PRESENT`变成`PRESENT/PRESENT`，A的人工修订仍保留但新版为`REVIEW_REQUIRED`而非沿用v3确认；排除的材料ID、理由和操作者持久化。旧v3仍是`CONFIRMED`历史，按现时授权才可读。缺ID、旧期望版拒绝且零写；同键同载荷回原v4回执，撤精确`CORRECT`后旧键403。组织修订/回执/事件各为主组织v1～v4加仅C缺口组织v1的5条（C/I），HTTPS主组织各4条；Evidence未复制文件，MATERIAL原件仍为三份。
- 专用5257 DM8随机用户与第二连接的`EvidenceOrganizationProductTest,EvidenceOrganizationDm8Test`各tests=1、errors=0、failures=0、skipped=0，正逆迁移执行；登录PKCE TLS `EvidenceRuleHttpsTest` tests=1、errors=0、failures=0、skipped=0，确切Grant版本/纪元轮换后v4读、v3历史读、停用规则后重新授权v2历史读均通过。首次HTTPS测试因同资源不同版本的Grant纪元冲突，以及外层历史读取仍持被撤v2 Grant而失败，修正测试授撤顺序后重跑通过，未改变产品授权代码。前置`verify-all`退出0；本段仅证明显式排除，来源/权限撤回的依赖失效、双实例及DB+文件物理恢复仍NOT_DONE，E05不标通过。
- 原件缺失失败关闭增量：在H2和专用5257 DM8各自的临时私有文件根暂移B `.blob`（原始验收输入文件和MATERIAL代码不变），预期/实际：仍引用B的已确认v3详情拒读；已排除B的v4可读且同键排除重放只读原回执，修订/回执/事件均不增。将临时文件移回后v3恢复按现时授权读取；两项定向各1/1、零失败/错误/跳过。此处是文件不可达反例，不是DB+文件共同物理恢复，也不是来源撤回自动失效或双实例实测。

## Evidence DB与原件共同物理恢复（授权闭包仍待实测）

- 执行者：Codex，2026-09-28。`EvidenceOrganizationDm8Test`在专用5257随机schema通过现有MATERIAL产品服务实际登记/收集/异人核验A/B/C三份原件，Evidence服务持久化主组织v1～v4、仅C缺口组织v1、五条组织回执和事件后，`ENF_EVIDENCE_RECOVERY_GATE`暂停清理。检查点及临时凭据仅写入本机临时目录`enf-evidence-recovery-0fc22a1c1e9847428086aa56d9f84f7a`，未签入仓库或输出凭据。暂停窗口复制三份实际私有`.blob`与案件快照，三SHA分别为`eb4379058974551bd3318c5abbfda277ea83cf4edb74a11eb7c4e85ae8d420e9`、`e6ea3d535970cff67c9c10247444561e6aa7526008beb9e97ef1da24eabdfb5a`、`1e4711d03dc5785001b290e23f02903472e2b2b9ab3ac08c8003e0d7bad2e571`；仅停止执法5257 DB，将真实数据卷冷拷到新`agent-platform-enf-evidence-restore-db-20260928`卷后立即重启原容器，独立`agent-platform-enf-evidence-restore-20260928`在5292启动并显示DB OK。未停止或复制咨询/独立授权5258容器。
- 恢复专测以5292新DM8和复制文件根实际重建Evidence仓储与服务，预期/实际：主组织v1机器/v2人工/v3确认/v4排除状态与关系版本均可读，三份恢复原件实际字节SHA逐项等于产品`enf3_material`引用，`enf4_organization_receipt/event`各5；`exclude-b`旧键同载荷返回v4且计数不增，授权端口拒绝时旧键/详情拒绝且计数不增。`EvidenceOrganizationRecoveryDm8Test`退出0、tests=1、errors=0、failures=0、skipped=0，输出`ENF_EVIDENCE_PHYSICAL_RESTORE revisions=4 evidenceFiles=3 receipts=5 events=5 replay=SAME unavailable=DENIED`；生产者恢复并退出0。该专测的授权端口为测试实现，不能证明5258独立现时Grant/来源水位在旧备份回灌后的产品拒绝；冷拷也不是加密生产备份。Evidence自己的DB+文件实际物理闭包已证，独立水位恢复时序、双部署HTTP、浏览器及其余反例仍未完成。更新前`verify-all`被并行`TEST_EVIDENCE_STALE:project`截断，不改无关模块。

## Evidence双独立JVM/DM8 HTTP时序（已执行）

- 执行者：Codex，2026-09-28。新随机Evidence业务schema在专用5257真实DM8执行V032/V033/V035/V050～V052，使用冻结MATERIAL产品服务准备并核验A/B/C，再经Evidence产品服务持久化v1～v4和五条回执/事件；独立5258授权schema执行V001/V002/V005。两个独立Spring/TLS JVM共用这两套执法专用DM8及同一私有文件根，正式Issuer测试成员经登录PKCE取得token，按确切CASE/EVIDENCE/EVIDENCE_RULE/EVIDENCE_ORGANIZATION版本签发Grant并激活独立来源水位。首跑错误空历史SHA使启动正确失败关闭；第二跑旧生产者键与新主体不一致返回409，均不计通过；修正测试设施与当前具名成员的新键后全链重做。
- 预期/实际：两个JVM的`GET /v2/enforcement/evidence-organizations/ORG-CASE-001/versions/4`均200且正文相同；同一时点并发`POST .../exclusions`排除无关C，两边均200，事务只形成一份v5、回执和事件由5增至6。撤v4精确READ Grant后第二/第一实例均403；撤v4 CORRECT后第二实例旧键403；5258独立来源水位撤回后第一实例旧键403；回执/事件均维持6，未复制/更改MATERIAL原件。`EvidenceIndependentDm8HttpTest`退出0，Surefire tests=1、errors=0、failures=0、skipped=0，输出`ENF_EVIDENCE_DUAL_DM8_HTTP jvms=2 business=5257 authority=5258 before=200/200 concurrentCommand=200/200 after=403/403 oldReceipt=403 receipts=6`；随机双schema正逆迁移及两进程清理执行。它是实际双部署时序，不代替旧备份与独立水位共同恢复。更新前`verify-all`因并行全量`TESTS_FAILING:project`截断，未修改无关模块。

## Evidence浏览器V2工作面与撤权（已执行）

- 执行者：Codex，2026-09-28。构建现有SPA并注入`enf-evidence-client`，产品TLS路由`/app/enforcement/evidence`通过真实Issuer/PKCE登录；Chromium切换Evidence组织面，GET当前V2产品`/v2/enforcement/evidence-organizations/ORG-CASE-001/versions/4`返回200并呈现`REVIEW_REQUIRED`、确切材料Ref/SHA、逐事项关系和时间。无`/api/v0`请求。桌面及390px移动截图在本机`frontend/target/enforcement-evidence-v2-*.png`，移动页面不横向溢出，长表格仅在自身容器滚动。
- 预期/实际：撤销v4精确READ Grant后同一浏览器重读为403，页面清除组织详情和会话并显示当前授权不可用；组织回执和事件各维持4、MATERIAL原件未改。`ENF_EVIDENCE_BROWSER_WITHDRAWAL_CLEARED`；`EvidenceRuleHttpsTest`带`-Pbundle-frontend`及`ENF_BROWSER_E2E=1`退出0，Surefire tests=1、errors=0、failures=0、skipped=0。前几次脚本误定位隐藏表单/AntD面板类以及移动表格撑宽均不计通过；修正可见表单定位和Evidence表格自身滚动后完整复跑。此为真实浏览器+V2 HTTPS/H2隔离权威，不冒称专用DM8双实例（其独立专项已另证）。变更前`verify-all`退出0；E01～E05余例及旧备份独立水位尚未通过，IMP继续进行中。

## E01/E02浏览器规则读取与只读试跑（已执行）

- 执行者：Codex，2026-09-28。在同一真实HTTPS/PKCE浏览器会话先从V2入口读取已发布`RULE-001 v2`，页面再经既有publication API显示`REVIEW-001`、依据版本/范围、审核者及不可变摘要；用项目获准A/B/C原始输入的确切Ref/v1/SHA发起只读试跑，逐事项出现`CONFLICT`及定位，不以预填关系或试跑结果作事实采信。切换组织面读取真实VERIFIED v3整理v4，撤组织READ后清屏。试跑前后规则/组织回执分别保持3/4，无新案件事实或材料写入，未请求`/api/v0`。`EvidenceRuleHttpsTest`带前端bundle与Chromium退出0、1/1零跳过；本段仅闭E01/E02的浏览器正向读取/试跑和依据定位，草稿修订历史、分页/不可解析、依赖失效等剩余反例仍须单列实测。

## E01～E05冻结反例待收口（未通过，非新增验收）

- 冻结依据：`docs/57-V2执法全能力详细设计.md`第6.24节及本任务原E01～E05退出。当前规则服务只创建v1草稿并从v1发布v2，暂无同一规则新内容形成新草稿、发布历史隔离的产品动作；已发布版读取和停用不代替该反例。需在冻结版本语义内修缺陷并实测旧引用不变、新草稿不可用于整理、拒绝请求零发布事件。
- 当前组织来源只处理项目文本原件并在不可达/不符时失败关闭；尚未实际执行不可解析输入/显式未处理或部分结果、源故障组合、案件分页/原件定位预览与对应现时权限，以及撤回依赖后已确认版的有效状态/重跑。现有缺原件拒读、排除B后v4重算、来源撤回旧键拒绝分别只计其确切子项，不能推全过。以上由开发侧继续解决，不记`WAITING_EXTERNAL_INPUT`，也不扩到HANDOFF或ENF-08～17。
- 因E01～E05余例未全过，具名**最终**技术复核尚未执行，`IMP-ENF-EVIDENCE-02`保持进行中；已完成的恢复、双实例和浏览器实测不重开D，也不新增第六验收条件。失败项修复后只重跑对应C/I/HTTP/E2E及禁止副作用。

## Evidence旧备份与独立现时水位（已执行）

- 执行者：Codex，2026-09-28。专用5257业务DM8随机schema由冻结MATERIAL产品服务生成并核验A/B/C原件，再以真实Evidence产品HTTP形成v1～v5、六条组织回执/事件和当前身份/Grant/会话；两个独立JVM同步执行同键排除只落一份v5。静止点仅停止5257并冷拷真实业务DB卷至全新5293恢复卷，同点复制三份私有原件和案件快照，立即重启5257。5258独立授权/来源/删除水位从未停止或回灌。恢复端按产品配置启动第三个Spring/TLS实例并逐份核产品`enf3_material` SHA，现时权威下GET v4和POST旧排除键均200，`enf4_organization_receipt/event`仍各6。
- 随后原两个实例撤v4精确READ/CORRECT Grant及独立来源READY状态；预期/实际：5293仍是撤权前旧业务快照，同一产品HTTP的v4 GET和旧键POST均非200并失败关闭，回执/事件仍各6，三份原件不变。恢复专项`EvidenceIndependentRestoreDm8Test`及生产者`EvidenceIndependentDm8HttpTest`分别退出0、Surefire各1/1零失败/错误/跳过；输出`ENF_EVIDENCE_OLD_BACKUP_CURRENT_AUTHORITY restored=5293 authority=5258 before=200/200 after=DENIED/DENIED receipts=6 files=3`。第一次5292恢复的测试授权端口不再充当独立水位证据；5293这轮使用真实产品Grant和备份外权威。该执行不是生产加密备份批准。变更前`verify-all`受并行`TEST_EVIDENCE_STALE:project`截断，不修改无关模块。E01～E05剩余反例和具名最终复核仍未完成，IMP不标完成。

## E01～E05剩余冻结反例实测（2026-09-28）

- 执行者：Codex；项目有权原创规则、案件快照及通过MATERIAL产品链收集/异人核验的原件，非V0/Synthetic/历史案例。E01/E02预期新草稿不改旧发布版：同一`RULE-001`已发布v2后创建v3草稿，v3不能取得发布元数据或作为ACTIVE版，发布v4后旧v2正文及`REVIEW-001`审核Ref/摘要仍不变；错误发布无新增规则/审核/回执/事件。H2与执法独立5257随机DM8的`EvidenceRuleProductTest,EvidenceRuleDm8Test`各1/1零失败/错误/跳过；登录V2 HTTPS实测v3草稿、v4 ACTIVE、发布记录1→2、规则回执3→5，旧v2审核仍为`REVIEW-001`。发布命令中草稿v1与未生成的v2双版本精确Grant冲突属冻结授权缺陷，现统一核被审核草稿版并在回执返回前复核；撤权旧回执仍403，未放宽读发布版的独立READ授权。
- E03/E04预期坏UTF-8及缺案件事件Ref显式未处理、仍可解析部分不得伪造关系：三份实际MATERIAL `VERIFIED v3`原件中一份可解析、两份`UNPARSEABLE_UTF8`/`EVENT_REFERENCE_MISSING`，规则试跑和持久化组织v1均列两项未处理，关系仅关联可解析原件，坏字节事实时间为空且原因`UNPARSEABLE_ORIGINAL`。全不可解析拒绝；缺私有blob、来源撤回及撤回后旧组织键均拒绝。第二DB连接读取组织v1一致；拒绝路径后`enf4_organization/receipt/event=1/1/1`、规则回执/事件2/2、`enf3_material=3`，三份私有blob字节未变，未复制Evidence文件。`EvidencePartialResultProductTest,EvidencePartialResultDm8Test`在H2/独立5257各1/1零失败/错误/跳过。
- E04预期确切组织版本逐事项服务器分页及原件定位预览：Chromium经当前V2 TLS/PKCE读已核验组织v4，两个`pageSize=1`页各一条且不重复，错误游标/页大小组合409，错误原文定位403；浏览器预览由V2产品入口返回200，最后一次实测原文字节SHA与锁定摘要同为`eb4379058974551bd3318c5abbfda277ea83cf4edb74a11eb7c4e85ae8d420e9`，行高亮可见。撤精确READ后预览403、工作面403清屏；组织回执/事件仍各4，材料行3、三份blob SHA不变，无`/api/v0`请求。`EvidenceRuleHttpsTest`用`-Pbundle-frontend`及`ENF_BROWSER_E2E=1`退出0、1/1零失败/错误/跳过；未捆当前前端时的旧页面找不到预览和测试关闭按钮定位失败不计通过。
- E05预期失权旧确认版不能凭历史权利读取/回执重放，但历史不删且有权修订者可沿冻结排除动作重跑：真实V2 HTTPS撤审核者对B原件的精确READ，旧v3 GET为403，DB旧v3仍`CONFIRMED`、组织回执/事件各3未增；另一有权修订者排除B生成v4 `REVIEW_REQUIRED`，不把B作为新版支持，原件材料行3、B SHA及三份blob不变；恢复精确READ后旧v3按现时许可仍可读取。该反例在`EvidenceRuleHttpsTest`产品场景实测并随上述七项串行专项复跑，均1/1零失败/错误/跳过；不新增通用重跑命令。
- 前置及本轮`verify-all`需求基线47/41一致；状态/证据刷新被并行项目报告`TEST_EVIDENCE_STALE:project`截断时不改咨询。上述是冻结反例的技术执行证据，不代替现实业务B；具名最终技术复核与任务终态复判仍待后置结构/专项验证。

## IMP-ENF-EVIDENCE-02具名最终技术复核（2026-09-28）

- 复核人：Codex（项目技术复核，不冒充执法专业/法律B签收）。按`docs/57`第6.24节冻结E01～E05：E01只读试跑零事实/整理写、缺项拒发布；E02 v2不可变审核与v3草稿/v4发布隔离、停用仅禁新运行；E03同一原件多事项、矛盾/无关/缺口与人工来源分层；E04确切EvidenceSet/三时间、显式未处理、部分结果、分页及原件预览现时校验；E05人工v1～v4保留、排除局部重算、失权旧确认版拒读及有权新修订。没有把候选变官方事实、把不可解析当完整或把历史授权当现时授权。
- 执行证据逐层复核：本轮规则、部分、组织H2/独立5257 DM8与登录HTTPS七项定向各1/1零失败/错误/跳过；捆绑当前前端的Chromium HTTPS场景再跑退出0、1/1零跳过，`EvidenceV2OrganizationPanel.test.tsx` 1/1通过。DB检查规则发布2版、部分组织1及回执/事件1/1、浏览器组织4/4；拒绝路径不增加规则/组织回执/事件或`enf3_material`，私有原件三份SHA/字节不变。此前已通过的真实`enf3_*`/`enf4_*`+原件共同物理恢复、备份外5258独立水位旧回灌失败关闭、双独立JVM/DM8 HTTPS时序均按本文件对应章节复用，未以测试授权端口替代产品回灌验证。
- 技术结论：冻结E01～E05与五项最终验收条件在声明的项目有权输入/隔离环境内通过；`IMP-ENF-EVIDENCE-02`完成最终开发验收。正式案件、现实材料权利、岗位任命、法律效力、生产备份/部署批准及专业事实采信仍是独立真实业务启用/B，不由本技术复核授予。无新增Evidence能力或验收条件，不重开已通过D，也不启动HANDOFF/ENF-08～17。主计划终态写入后的全局`verify-all`退出0：47项需求基线、41条需求台账、619项计划及结构/证据硬门通过。
