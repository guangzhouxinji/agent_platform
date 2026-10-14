# ADR：裁决首案可信API、同库持久化及恢复（01B D1）

2026-10-02，技术责任Codex；授权来源为本轮项目负责人明确要求在01A上完成可信API/持久化恢复、单案完整生命周期及负例，不重做01A、不提前进01C。唯一状态见docs/08 IMP-ADJ-V2-TASK-01B；R沿REQ-ADJ-R-CORE-14和第8.3节。完整法定角色/正式流程不由本技术决定确认。

## 1. 所有权与兼容

01A三领域类原样保留，新的domain application ports依赖契约不依赖JWT/JDBC/vendor；apps/platform-api下新增adjudication/casework适配器。新ProductAdjudicationApplication独立组件扫描，复用已有ProductIssuerConfiguration、ProductGrantWatermark、ProductMaintenanceProof，不改它们或咨询/执法启动类。没有跨域业务写入、不启动两域既有运行库DDL、不自报角色、不使用V0。新增V086只裁决主库表；独立来源授权DDL命名adjudication_source_authority.sql，不装进主库全域迁移。权限历史与来源使用批准是独立库当前事实，不随裁决工作快照恢复回滚。

## 2. 可信主体与来源

全部/v2/adjudication工作API要求TLS和验证签名/issuer/audience/session/kid/到期的既有JWT。服务只取可信JWT subject；请求不接受actor/role或权限字段。每命令核id2_subject HUMAN+ACTIVE、当前sid/jti会话、id2_current_epoch精确当前grant、domain ADJUDICATION、organization/case task_scope/data_scope、resource_type ADJ_CASE/id、resource_version=1（独立授权资源版本，不是工作版）、动作CREATE/READ/WRITE/HUMAN_REVIEW及purpose CASE_AUXILIARY。CREATE必须有预先精确批准的案/机构/范围grant；后续scope取持久事实而非请求。只有辅助工作复核，无正式岗位/签发/裁决权限。

任何正文来源均须独立库adj2_source_use事先批准，精确case/机构/范围、Source id/version/locator/hash/kind、READ/SAVE/ANALYZE用途、期限、依据Ref+SHA。Source输入性质不提供许可，也不能把工程包改称真实来源。维护API仅已登记TLS客户端证书的SOURCES角色可登记或撤回；不能由Agent/普通JWT发行来源许可。登记不含默认许可，撤回不可复活同key。id2授权水位前/事务内后/返回前复核；来源准入/撤回有自己的独立generation水位，与主库事务分离。任何PENDING/过期/变更/失权失败关闭，返回前失败不暴露正文。已提交事务之后失权可隐藏回执但不伪称写入回滚，原幂等键在重新具备权限后对账；不宣称跨库原子撤权线性化。

## 3. 最小API合同（正式页面可直接消费）

前缀/v2/adjudication/case-works。POST创建（input+originalText）、GET指定案件当前、GET /{id}/versions及/{id}/versions/{v}历史、GET /{id}/audits、GET /{id}/originals、GET /{id}/reports/{rev}。返回工作version/inputVersion、材料和权项历史、引用/人工对应、报告/诊断/准确审核状态；不可读材料不返回正文。所有读取重新核当前授权/来源，不用历史授权读历史。

POST /{id}/context-revisions、/materials、/claims、/citations、/findings、/reports、/reports/{rev}/revisions、/reports/{rev}/submit、/reports/{rev}/reviews。每修改携Idempotency-Key与expectedVersion，报告操作另携reportSha256；new key重复提交拒409，同键原输入成功重放仅原回执，body/actor/案/operation/版本不同409。人工退回后必须新报告版再提交、独立人再审，不能同旧稿覆盖决定。

最小包无法律时点/地域/程序，允许context-revisions保存显式新来源修订（不猜时点），不改id/机构/dataScope，inputVersion递增旧稿失效。已有引用时不允许直接换上下文（CONTEXT_IN_USE），避免旧法条被静默重用；未来完整引用撤回/重新选择按后续切片。Claim/材料/引用/Finding/报告行为调用01A，不补法定状态。报告Policy服务端固定版本配置，不接受客户端关闭必要章节或双人复核。

响应：成功receipt含key/action/caseId/workVersion/inputVersion/reportRevision及source/内容摘要关联；400缺原文/非法字段，401无效JWT，403无精确权限/来源用途，404无可见对象，409版本/幂等/状态/旧稿冲突，503持久完整性/独立水位不可核。异常不返回材料/SQL/堆栈。允许字段白名单，伪actor拒400。

## 4. 物理持久与恢复

V086：adj2_case_work保存id/机构/范围及head_version/head_sha；adj2_case_revision不可变每命令全State的UTF-8 payload_sha/previous_sha；adj2_case_original保案件原文真实UTF-8正文与源摘要（上下文补正保历史、不只存定位）；adj2_case_audit逐版本审计FK；adj2_case_receipt幂等回执；adj2_case_command_lock有限行锁。一次主库事务：锁命令bucket、读取/锁head，核当前权限与来源，核CAS、运行01A、追加revision+一条audit+receipt，CAS推进head，事务内重核。READ/versions在主库事务内锁定同案head，避免并行提交被误判为损坏；命令返回前按确切已提交快照重核权限，不以最新头竞态替换回执。任何失败全部回滚；没有部分引用采用、孤立报告或内存真相。

payload codec按确定JSON属性/Map/Set顺序编码，UTF-8真实SHA；重载核head与最大版本、每行SHA/previous chain、连续work/input版本、案/机构/范围、完整当前state引用/材料摘要/权项链/来源关系、逐Audit行与State一致、所有receipt与确切版本相等；Report构造器自核01A内容摘要。完整核对失败返回503，不降级空内存。重新构造Repository/服务或重启HTTP实例读同库必须完全相等。历史版本与审查状态保留，当前源撤回后即使主库回到旧快照也不能读取。

源许可维护只在独立库事务：generation PENDING、追加不可变use/event、推进当前批准/REVOKED及READY；服务before/after观测同generation且精确记录合法。证据Ref+SHA必须具名维护人提供，不把技术核验当第三方法律许可。

## 5. 测试、实测与退出

H2仅机械事务/CAS/重放/rollback/引用漂移/伪actor/错版本/旧稿/自审/重复状态/跨案/源失权/历史损坏反例；真实TLS HTTP用真实JWT签名及session/grant/current水位，不mock授权。真实DM8使用具名随机隔离schema，在已有两个本地DM8上部署V086与独立来源DDL，不触碰CON/ENF现用schema；跑同一个API生命周期并停止/重启API重新读取，保存HTTP回执、DB行数/链摘要和全测试XML，清理仅明确创建的隔离schema。输入是01A工程包，补充上下文/工程原文用于机械引用测试均明确PROJECT_AUTHORED，绝不冒真实法条/案件或专业金标；无专业/法定验收声明。

每轮前后verify-all与咨询/执法领域模块回归。01B完成必须实际API+数据库+恢复证据同时具备；只有H2/MockMvc不宣称DM8通过，未实测保持进行中，不进入01C。单人技术反演：以上窄D可实施，未配置身份/独立许可/真实方法就失败关闭；没有正式业务状态更新、不改变共享平台或十二项完整D/V/A。
