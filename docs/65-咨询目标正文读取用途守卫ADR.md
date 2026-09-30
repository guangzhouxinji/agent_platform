# ADR-CON-INPUT-DISPLAY-01：咨询合成目标正文读取用途守卫

状态：隔离单实例开发切片窄D及代码、合成C、本机隔离DM8开发I已完成；正式撤回、跨实例与真实I/B另证。日期：2026-09-25。

## 问题与范围

`DEV-CON-INPUT-01`的GOAL/SUPPLEMENT只有`SAVE`。当前`DevTaskInputPolicy.allows`无动作参数，`Dm8DevTaskCoreStore.read`与`Dm8DevTextResultStore.prepareEvidence`复用它后分别返回`messages.body`和`targetText`。现有身份/摘要测试通过不证明`DISPLAY`许可。docs/02第32节的项目负责人授权已签成独立`DEV-CON-COMPARE-INPUT-01`，旧manifest、确认版及回执不原地扩权。

本决定只处理项目原创合成目标正文的开发期读取；不改变创建/补充的`SAVE`，不包含专业关系、真实来源、正式身份、跨实例生产撤权或文件备份。CON-02/06/12的来源、版本和当前授权约束以及docs/61第16.1节为业务边界。

## 决定候选

1. 为`DevTaskCoreStore.Guard`增加独立的`inputDisplayAllowed(actor,kind,text,now)`，默认拒绝。任务读取和证据准备在返回每段目标正文前同时要求原`inputAllowed`验证旧签入文本，以及新守卫验证`DEV-CON-COMPARE-INPUT-01`相应`target.goal`/`target.supplement`字段的`DISPLAY`。写入/特征确认仍仅按原有守卫运行。
2. 新守卫仅在`dev-synthetic`/`test-synthetic`且任务核心显式启用时装配；配置必须提供新包manifest的绝对文件路径，载荷限定为同目录`input.json`。启动时核已审定manifest原始字节SHA-256 `a9ffd6d954f0e946907dfdb6f9073573b146154dbcbdb94cd2a0e56a0b2f28b2`。每次读取重新核manifest原始字节、载荷SHA、确切目标原文、组织/具名H、字段用途、有效期与`withdrawalVersion=0`；读失败或任何摘要/字段变化均拒绝，不缓存旧许可作为历史凭据。旧包仍由原守卫核对。此文件完整性机制只是单实例隔离开发的失效关闭，不声称跨实例撤权线性化。
3. `read`对每条实际返回的`message.body`逐条核对应消息种类及文本；`prepareEvidence`在任务锁下绑定`taskId + result.inputRevision + targetMessageRevision + kind + body`，来源成员仍沿用确切`setId/ordinal/recordId/sourceVersion/contentSha`。补充后历史集合不借最新目标替代旧输入。读取末尾以数据库时间和当前主体重新核两个输入用途及任务状态；失败统一拒绝且不返回部分正文。缺`DISPLAY`或签入漂移的响应采用现有`SOURCE_UNAVAILABLE`/无正文错误，不自动降级为专业或来源判断，不写任务、焦点、回执或期限。
4. `DevTaskInputPolicy`继续只管旧版`SAVE`完整性；新策略是独立端口适配，不改旧manifest、历史回执、已确认特征版本或签入数据。配置关闭时不注册新的业务路由；若任务读取原已开放而新包配置缺失，启动失败，避免运行中静默沿旧守卫返回正文。

## 验证与排除

C正例：具名H的GOAL/SUPPLEMENT任务读取，以及历史S1的证据准备，正文与确切消息修订一致；重启后相同签入字节仍可读取。C反例：缺新路径、旧包仅SAVE且新包不存在、新包manifest或载荷篡改、协调重算载荷摘要、字段移除DISPLAY、不同文本/组织/H、到期/撤回、旧集合重排、响应前撤权和DB不可用均无未授权正文与零写入。取消先提交时证据准备拒绝；任务GET仍可按当前许可读取获准历史，不把取消本身解释为撤销DISPLAY。隔离DM8 I复核双连接读/取消顺序和独立进程重启；文件/备份、跨实例共享撤回、真实身份/数据与业务验收notRun。

独立窄D结论：项目原创新包的`DISPLAY`签入、旧版仅`SAVE`、受控主体/失败关闭、两处正文返回、版本及响应前复核和C/I反例均已固定；上述单实例开发范围没有待推断的业务规则。只批准`IMP-CON-INPUT-DISPLAY-REMEDIATION-01`按本合同修复并验证，不宣称原需求自动授予`DISPLAY`，也不批准专业对照或生产跨实例撤权。结构校验不能证明法律或专业正确性。

## 实施验证记录（2026-09-25）

任务GET及证据准备GET已分别在返回目标正文前重核旧`SAVE`来源与新包逐字段`DISPLAY`，策略逐请求核manifest原始字节、载荷摘要、确切正文、主体和有效期；配置缺失启动失败。`DevComparisonInputPolicyTest`覆盖正文不匹配、载荷/manifest篡改、错误用途与主体撤回；`DevInputDisplayContractScenarios`以旧`SAVE`有效但新`DISPLAY`拒绝证明两处零正文、回执不增长。聚焦测试、仓库根完整`mvn -q -f backend/pom.xml test`及`node scripts/verify-all.mjs`通过。当前环境无`DM8_JDBC_*`连接配置，隔离DM8开发I、独立进程重启及双连接竞态未在本轮复验；此处仅记合成C，不升级真实I/B或跨实例撤权结论。

后续本机隔离DM8正向复验：`agent-platform-dm8`只绑定`127.0.0.1:5237`；从该容器已有配置在测试进程内注入连接信息，不输出密钥，`Dm8DevTaskCoreIntegrationTest`以随机临时schema执行V023～V025及反向U025～U023，1项零失败/零跳过并清理schema。其双Store和独立HTTP进程重启路径实际返回目标正文；进程探针加载新`DISPLAY`守卫及签入manifest。首次运行误用旧jar而启动失败，现有服务占用该jar；另名打包当前代码后复验通过，没有停止或覆盖现有服务。此I只证明新守卫正向读取与既有DB竞争/重启路径，尚未在DM8执行新包撤回、正文篡改、响应前失权与两种取消顺序的专门负例；原C与本I不可合并声称所有退出反例已验，更不表示跨实例即时撤权或真实B。

随后`DevInputDisplayDm8Scenarios`在同一隔离DM8两Store路径验证：旧`SAVE`守卫允许、独立`DISPLAY`拒绝时，任务读取和证据准备均返回`SOURCE_UNAVAILABLE`，回执及事件数量不变。专项1项零失败/零跳过，完整后端回归及结构校验通过；这验证存储侧拒绝，不模拟真实文件撤回协议或跨实例竞态。响应前失权及取消双序专门I仍需留证。

独立HTTP进程的文件漂移专项再以临时目录复制已签入manifest和载荷，只把临时绝对路径传入新构件。服务运行中篡改临时载荷后，任务GET及证据准备GET均409且无目标正文，回执数不增长；恢复原始字节后任务GET重新200。隔离DM8专项1项零失败/零跳过，临时schema与临时副本由测试清理，原签入包不变。该结果覆盖载荷漂移的请求间失效关闭，不覆盖请求内响应前的并发撤回线性化、跨实例缓存/文件可见性、取消双序或真实I/B。

随后在`DevInputDisplayDm8Scenarios.withdrawBeforeReturn`以受控Guard注入两次用途核验之间的失权：第一次`DISPLAY`返回允许，返回前第二次返回拒绝。隔离DM8两Store的任务读取和证据准备均抛`SOURCE_UNAVAILABLE`，各调用恰好核验两次，回执及事件数量不变；专项1项零失败/零跳过，完整后端回归和`verify-all`通过。该反例证明当前单实例存储路径会执行返回前重核并整请求拒绝；Guard注入不是签入文件真实撤回，不证明请求内真实文件/跨实例撤回的线性化。补充/取消双序和实际文件撤回的专门I仍未完成。

`DevInputDisplayDm8Scenarios.cancellationOrder`另以同一隔离DM8的两Store复核确定性顺序：取消先提交后，证据准备`TASK_CLOSED`，但任务GET在当前H及DISPLAY许可下仍可读获准历史；证据准备先返回确切GOAL且不写入，随后取消，再次准备拒绝。两条临时任务到期后经J清理，迁移逆序回滚通过；专项1项零失败/零跳过、完整后端回归及`verify-all`通过。此处并未在两连接中人为阻塞读事务与取消事务，不证明真正并发交错或返回前取消线性化；实际文件撤回同样未验。取消后历史读取与docs/61第11节原合同一致，先前笼统“取消先提交零正文”的表述在本ADR已纠正。

后续同专项在任务GET第一次`DISPLAY`检查处用栅栏暂停读取，另一连接先提交取消，再释放读取。原实现最后一次复核能看到取消，但以第一次任务快照构造`ACTIVE`响应，形成混合状态；修复为末次读取任务修订、取消epoch或状态变化时整次`VERSION_CONFLICT`，不返回正文。重试在当前H/用途许可下读到`CANCELLED`及获准历史GOAL；两条临时任务及竞态任务经J清理，随机schema正逆迁移通过。专项1项零失败/零跳过、完整后端回归及`verify-all`通过。该I证明本机两连接任务GET与取消的特定提交交错失效关闭；证据准备与取消的阻塞式交错、补充写入交错、实际文件撤回和跨实例即时撤权尚未实测，不能泛化成所有竞态已验。

独立HTTP进程再在运行中将**临时副本**`input.json`移走：任务GET与证据准备GET均409且不含目标正文，回执数不增长；在`finally`放回相同字节后GET恢复200。隔离DM8随机schema专项1项零失败/零跳过、完整后端回归和`verify-all`通过；原签入包未改。这是文件不可达时逐请求失败关闭，不是有权主体的正式撤回记录、请求内瞬时撤回或多实例同步协议。

再以两连接栅栏验证证据准备和取消：准备事务取得任务行锁并在首次`DISPLAY`暂停，取消由另一连接发起，释放后准备返回确切GOAL且零写，取消随后提交，再次准备`TASK_CLOSED`；回执/事件只由取消各加一。任务GET和补充另固定“首轮DISPLAY已过→另一连接补充提交→返回前复核”的交错，本次整读`VERSION_CONFLICT`且无混合正文，重读修订2含确切GOAL/SUPPLEMENT。两个新临时任务到期后由J清理、随机schema回滚通过；专项1项零失败/零跳过、完整后端回归与`verify-all`通过。首次专项运行在独立HTTP进程第二次启动处失败（仅得启动失败摘要，未进入新交错）；同一代码/构件重跑通过，根因未定位，保留探针启动稳定性风险。上述为本机同一DM8双连接切片，不证明实际文件撤回、跨实例身份/用途同步或生产竞态。

后续探针诊断加固：`Dm8DevTaskCoreProcessProbe`每次独立进程启动截断旧日志，使启动失败摘要只对应本次进程；停止后若强制终止仍未退出，先报明确错误而不继续第二次启动。完整后端回归496项零失败/错误、76项环境跳过，`verify-all`通过。当前无`DM8_JDBC_*`且Docker守护进程不可达，隔离DM8独立HTTP重启专项未运行；上述修改不证明偶发失败的根因或稳定性已修复，待环境可用后连续复验并保留每次独立日志和结果。

环境恢复后的限定复验（2026-09-25）：`agent-platform-dm8`只绑定`127.0.0.1:5237`，测试进程从容器现有配置临时注入连接参数，不打印凭据；以独立命名的当前构件`v2-display-reprobe-20260925.jar`运行`Dm8DevTaskCoreIntegrationTest`连续三轮。每轮均为随机临时schema的V023～V025正向与U025～U023逆向、两Store及独立HTTP进程重启，Surefire各记录1项、零失败/错误/跳过，测试清理临时schema。加固后的第二次启动未复现失败，但三次成功不能定位此前偶发根因或证明长期稳定；正式权威来源撤回、跨实例线性化、文件/备份及真实I/B仍未执行。`IMP-CON-INPUT-DISPLAY-REMEDIATION-01`按本ADR批准的单实例开发期范围结案；共享可信撤回及恢复栅栏转`ARC-V2-DEV-TRUST-STATE-D-01`独立审查，文件/备份转`ARC-CON-PERSIST-PHYSICAL-D-01`，不将本切片结案解释为完整CON-12或生产验证。

补充开发I（2026-09-26）：`DevComparisonInputDm8Support`将真实签入策略绑定到临时复制的manifest/载荷；`DevInputDisplayDm8Scenarios.signedFileWithdrawalDuringRead`在任务GET与证据准备GET的首次`DISPLAY`允许后暂停，实际把临时manifest撤回版本改为1，再释放返回前重核。两处均`SOURCE_UNAVAILABLE`、无正文及新增回执/事件；恢复签入原始字节后任务GET恢复读取。原始签入包不改，临时副本和schema由测试清理。从仓库根执行`mvn -q -f backend/pom.xml -pl apps/platform-api -am -Dtest=Dm8DevTaskCoreIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dv2.process.jar=v2-display-reprobe-20260925.jar test`，本机`agent-platform-dm8`随机schema专项1项零失败/错误/跳过，含原有独立HTTP重启；未重跑全量后端。此反例证明开发单实例文件状态在固定在途读取中的失败关闭，不证明权威撤回操作、跨实例传播、已发送字节追回或真实I/B。
