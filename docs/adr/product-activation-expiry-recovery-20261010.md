# 同主体过期激活恢复详细设计（2026-10-10）

设计任务：`ARC-CON-NORMAL-ACTIVATION-RECOVERY-D-01`。本文件是该任务的详细设计（第7节为二遍限定冻结），不是第二份实施计划或状态台账；D门、实现及验证状态只以docs/08 §7.8.3为准。实施仅依docs08登记任务及第7节边界；本文不批准运行身份写入或部署。

## 1. 依据、责任与适用范围

- R依据：主计划已完成的`REQ-CON-R-CORE-12`及`ARC-CON-PRODUCT-IDENTITY-D-01`；docs/69 §1.3–1.5；docs/02 §38–39及`Q-CON-NORMAL-ACTIVATION-RECOVERY-01`。协议设计由项目负责人2026-09-27委托开发团队，不是外部身份/生产操作授权。
- 本次设计责任方Codex，日期2026-10-10，范围为项目自有Issuer内HUMAN主体、原维护登记者恢复已过期且未激活的一次性激活凭据。不得扩为ACTIVE密码重置、DISABLED恢复、SERVICE登记、替换subject或代本人设置密码。
- 原始证据：`../evidence/single-database-installation-20261009/approved-execution/consultation-normal-delivery51/safe-delivery51.json`和`actual-subject-state51.json`。固定25 JAR SHA为`69d8462924cd920bbda3d501b92b0b3f492f49844f1f3e7ca811f6dd7c217e1d`；13:23只读证据不是18:00以后的现时查询。
- 原subject为`2b18f742-15f8-4ac8-b476-a89d4b90f141`，login为`consultation-owner-20261010`；证据状态PENDING/version1，激活到期2026-10-10T05:18:31.733837Z（北京13:18:31.733837）。原部署TTL为PT10M，passwordMinimumLength为16；实现必须读取部署策略，不硬编码本次值。
- 当前源码依据：`ProductSubjects.java`、`ProductIdentityController.java`；数据依据`db/dm8/V032__product_identity.sql`及`V046__product_workload_subject.sql`。现有事件没有重发操作，现表没有持久幂等请求收据，旧代码只有register/activate/disable，不能声称恢复功能已经存在。

## 2. 必须保持的产品边界

1. 维护请求只能由当前可信mTLS证书、独立维护登记及SUBJECTS职责证明取得actor；请求体、普通JWT或自填header不能提供维护身份。原REGISTER事件的actor必须恰为现时维护actor；缺失、重复矛盾、换登记者均拒绝，不新增维护接管规则。
2. 同一个Issuer/subject，HUMAN、PENDING、password_hash为空、原激活摘要及到期字段存在、`expiresAt <= serverNow`、`expectedCredentialVersion == currentVersion`才允许首次重发；全部在主库行锁内复核。客户端时间、历史快照及原token不作准入依据。
3. 新生成256bit密码学随机一次值，新摘要替换旧摘要，到期取本次服务器时间+原部署TTL，CAS单调加版。不能延长旧值、复活旧摘要、修改login/subject、缩减密码策略或自动激活。
4. 主体更新、不可变REISSUE事件、持久幂等成功收据在同一主库事务提交；任一失败回滚全部。独立维护ALLOW审计仅证明请求维护准入，不能冒充主库业务提交成功。
5. 不创建Grant/Source/初始Task，不更改官方状态，不调用模型。账号创建/恢复均不代表有业务访问权限。原本人的使用及本机保存确认不重索，但不能将交接摘要伪造为原话字节。

## 3. 拟定API与最小数据合同（须完成D复核才准实施）

拟定正式维护路由`POST /v2/identity/subjects/{id}/activation-recovery`，请求仅`expectedCredentialVersion`及规范UUID格式的`Idempotency-Key`。actor与issuer均由服务器可信配置及现时维护证明提供；不接受TTL、secret、password、login或grant字段。主体恢复服务是身份领域所有，不由Agent直接写表。

首次成功响应只含`subjectId,requestId,credentialVersion,expiresAt,delivery=FIRST_RESPONSE,oneTimeActivation`；禁止缓存（Cache-Control:no-store）、访问日志/body捕获/证据收集秘密。响应一次值只交给可信维护方用于既有本人私下交付，不通过普通GET读取或新的验证专用页面展示。

现有schema不能记录持久请求结果，拟定专属最小收据表：`issuer,actor_ref,request_id,subject_id,expected_version,result_version,activation_expires_at,occurred_at`。主键为`(issuer,actor_ref,request_id)`，绑定该issuer/subject的外键；before/result版本连续。不是通用幂等框架，不存明文、可逆密文、密码或一次值摘要副本。主体事件操作约束须保留REGISTER/ACTIVATE/DISABLE/SERVICE_REGISTER/SERVICE_DISABLE并增REISSUE。新增migration编号只能在实施准入时依据当时最新序列分配，不提前占用或执行DDL。

收据适用与现有身份事件相同的既有审计/恢复治理，不凭本设计臆造保留天数、删除作业或生产备份外主体水位。本切片不解决原本未闭合的主体/会话回灌防护，不因新收据宣称完整身份恢复验收。迁移回退不得删除成功恢复审计/收据；存在REISSUE事件/收据时必须拒绝破坏性回退，走获准完整恢复程序。

## 4. 并发、幂等与响应丢失的安全合同候选

**限定技术选择：只持久化非秘密收据，不可再次取回一次值。** 第7节冻结，不伪称用户原话或秘密托管授权。

- 每次请求均重验当前维护证明及原REGISTER actor，然后锁定subject。在锁内先查询该actor/issuer/requestId收据，逐字段核subject/expectedVersion；同键不同payload拒绝409。
- 同键已提交：只返回原收据元数据和`delivery=NOT_REDELIVERED`，`oneTimeActivation`不返回；不推进版本、不改变过期时间、不新增成功事件。即使其后已激活/停用，也仅可返回该actor原有请求收据，不能重做恢复或称当前仍PENDING。
- 同键未提交且当前仍符合首次准入：执行首次事务。同subject不同键并发由行锁/CAS串行，最多一个提交，其余409；不同subject同actor同键由收据唯一约束阻止第二个提交，整个失败事务回滚，不吞唯一约束错误。
- 客户端超时/连接断开/503为UNKNOWN，**保留同键并查询重试**，不得换键盲发。若查到成功收据但一次值未收到，显式报告“已签发但不可再次交付”；不自动注册、失效重发或替本人激活。等待该次值真正过期后，由原登记者以新键及新预期版本再执行正式恢复。
- 若接收端已保存在既有current-user DPAPI位置，应从本人受控本机保存继续本人流程；服务端不能用“可能保存成功”推断已激活，亦不读取或泄漏本机秘密作为治理证据。
- 上述取舍牺牲立即重取能力，但没有新增密钥托管/可逆secret存储事实。本人只有机制已真实部署可用且明确准备好后才接收私下短时资料，由本人设密码激活；维护者或Agent不代设密码。

## 5. 失败与专属反演清单

| 场景 | 预期 | 必须证明的零副作用/原件 |
|---|---|---|
| 无TLS、无证书、错误证书、无SUBJECTS、维护登记撤销或独立库不可达 | 拒绝；不可达503 | 无subject/version/成功event/收据变化 |
| ACTIVE、DISABLED、SERVICE、未知subject/跨Issuer、REGISTER actor不符或登记证据缺失 | 409或维护拒绝，不泄漏其他主体正文 | 不生成新secret，不恢复状态 |
| PENDING未过期、旧version、缺少摘要/到期、非法requestId/请求字段 | 400/409 | 不改变TTL/version |
| 恰好到期 | 可恢复；恰好新到期后激活必须拒绝 | 使用服务端Clock，而非客户端时间 |
| 首次合法恢复 | 同subject/version+1、原TTL、新摘要及REISSUE与收据 | 旧一次值无法激活，新值只能本人激活一次 |
| 同键重复、同键错payload、不同键并发、不同subject同键竞争 | 重复只元数据/冲突回滚；最多一次提交 | 不重复审计成功事件、不延TTL、不再发secret |
| 事件写入失败、收据插入失败、CAS失败、事务提交结果未知 | 事务回滚或UNKNOWN同键核查 | 无孤立主体更新；不把错误当成功 |
| 首次响应丢失、secret已保存但未激活、重复请求发生在激活/停用以后 | 非秘密收据、状态不反转、无法重交付显式说明 | 不代激活、不新建账号、不日志明文 |

## 6. D出口与后续实现准入

原草案曾保持D未过，第7节和docs02二遍结论限定闭合后状态只见docs08。后继IMP-CON-NORMAL-ACTIVATION-RECOVERY-01仅源码/组件，真实DM8迁移、发行/部署/本人交付均另有当前准入。窗口更新至2026-10-13 18:00北京，不复活旧token，不自动批准恢复执行。

## 7. 二遍语义复核与限定技术D冻结（2026-10-10）

责任Codex；依据docs69基线V1 §1.3—1.5、docs02原协议技术委托与本Q、51非秘密证据、当前ProductSubjects/ProductIdentityController/ProductMaintenanceProof及V032/V046。来源SHA和27条义务见 `../evidence/activation-recovery-design-20261010/second-semantic-review62.json`。此为开发团队技术推导，不是原需求、实测或新增身份权利。

### 7.1 准入与HTTP确定合同

每次POST（含历史收据查询）都重新沿现有TLS/mTLS链require(SUBJECTS)，不信请求体/JWT/自填header。独立维护数据库/审计不可达503、未授权403，停止主库业务操作；ALLOW只是维护准入，不是业务提交。现有require的线性化点为该次现时核验，不声称独立库与主库跨库原子撤权，不扩共享维护事务机制。actor为维护subject#证书指纹，必须恰等唯一REGISTER事件actor（0→1）；缺失/重复/矛盾409，换证不隐式接管。

请求subjectId、Idempotency-Key须严格小写标准UUID，body只能expectedCredentialVersion正整数且能加一；未知字段/TTL/password/secret/login/issuer/actor/grant400。不能修改全局Jackson政策。Issuer只取部署配置。首成功与重复统一200，用delivery区分；冲突409，存储错误或提交未知503；不返回数据库正文。成功/错误均no-store，不捕获body或secret日志；不改变既有activate失败401或其他路由异常合同。

主库事务先锁同Issuer subject，再核REGISTER actor，先查actor/issuer/requestId收据并严格核subject/expectedVersion。正确历史收据必须优先于当前版本/状态检查，返回原resultVersion/expiresAt、delivery=NOT_REDELIVERED，oneTimeActivation字段完全缺席；这是历史结果，不声称当前PENDING/可激活。错subject/payload通用409，不泄漏另一主体。无收据才校验当前HUMAN/PENDING/password_hash为空/原摘要和到期存在、expiresAt<=serverNow及version相等。ACTIVE/DISABLED/SERVICE/未知或跨Issuer/actor不符拒绝，不生成签发值。原token不作凭证，随机只在新请求合法后生成。

### 7.2 事务、竞争与精确写集

仅在同一主库事务：一个id2_subject的activation_sha/activation_expires_at/credential_version，追加一REISSUE事件，插入一幂等收据；login/subject/kind/password/status及其余身份/Grant/Source/Task不动。32随机字节，只存SHA256；行锁内同一个serverNow，expires=now+已配置activationLifetime（本部署PT10M），version=expected+1，先检查版本/时间有效域防溢出。CAS含Issuer/subject/HUMAN/PENDING/expected/已到期，必须影响1行；提交成功才可返回一次值。失败全回滚。

同一subject与activate/disable共享行锁；恰好到期可以恢复，激活at expiry必须拒绝。不同键同subject串行仅首提交，旧版本409；同键同payload不重复事件/收据/延TTL。不同subject同actor同键竞争由收据PK(issuer,actor_ref,request_id)决定唯一成功；约束异常不得在rollback-only原事务吞掉继续成功，必须整体回滚后再以新只读事务核归属，确证冲突409，无法确证503/UNKNOWN。生成但回滚的随机字节不得交付，不称第二签发。

事件/收据/CAS任一失败无孤立更新；主库提交未知不能推定回滚/成功或换键重试，只沿同键查询。收据不存secret/可逆密文/摘要副本，result=expected+1、正整数及同Issuer/subject外键。正迁移保留原operation集合并增REISSUE；逆迁移有任何收据或REISSUE必须拒绝破坏性回退，不DROP原件、不吞错误。迁移编号按实施时当前末尾分配。组件fixture实际加载正迁移并执行逆SQL反例，不冒DM8物理证明。

### 7.3 响应丢失与本人交付

首次成功提交后的首次HTTP响应可含一次值，重复POST没有值且不可取回；连接断开/超时/503为UNKNOWN，保留同键重查。已提交但未收值：明确“已签发、不可再次交付”，等该expiresAt真正到期后，原登记者才用新键/当时现时版本申请，未到期换键409。不自动重签/延TTL/新建subject/激活。若本人受控current-user DPAPI保存确实成功，可继续既有本人流程；服务器不读本机secret，不把历史收据当收件或当前激活状态。激活/停用后同键只回历史元数据，新键不能反转。

此安全取舍不需要新增密钥托管或可逆secret事实，依据原技术协议委托由开发团队决定。没有现行立即重取MUST证据，不能臆造它阻塞当前窄D；未来立即重交付须独立D。真实交付仍需机制实际可用且本人准备好，Agent不设密码或代激活，不将秘密放证据。

### 7.4 D结论与实施边界

27条设计反演明确解决当前切片问题，不是测试通过。限定D通过，只准稳定后继源码/专测：ProductSubjects.java、ProductIdentityController.java、新ProductSubjectsRecoveryTest.java、新ProductSubjectRecoveryControllerTest.java、一对V/U迁移及确切受影响fixture/迁移声明。不得改ProductMaintenanceProof职责、primary安装profile、部署、源权限或单库比较器。不扩ACTIVE密码重置、DISABLED恢复、SERVICE恢复、维护接管、审计保留天数或生产备份外主体水位。本切片不解决原有主体/会话回灌缺口，不宣称完整身份恢复验收。现有固定25发行未具新能力，51状态是历史证据非现时查询。源码→专属组件→治理/受影响回归后，运行迁移/发行/本人交付再另准入；不把原维护长窗当自动执行授权。


## 8. 62限定源码与组件事实（不是运行/本人验收）

D后治理已结束exit1仅既有project TESTS_FAILING/STALE，47/41一致；按唯一计划稳定实现Task自动接续。两身份类已实现正式POST、当次SUBJECTS/原REGISTER actor、同subject行锁/版本CAS、原TTL/新32字节值及同事务REISSUE+非秘密收据。新增V107/U107源码（实施时原最新V106），不改变primary安装profile或当前固定25发行。两新专测实际加载V032/V046/V107到独立H2，并使用正式Controller注解的MockMvc路由（非真实TLS部署）；恢复并发、跨subject唯一竞争回滚、事件/收据失败、历史收据、丢响应、旧值失效/到期、新旧timestamp精度及400/403/409/503无秘密空正文均有组件证据。

最终run05六套31/31、0失败/错误/跳过，包含新两套20项与原主体/维护/初始化四套11项。源六文件SHA/原XML/log及限制见 `../evidence/activation-recovery-design-20261010/component62-run05/receipt.json`。run01/02通过原件、run03三HTTP断言失败（专测只配置Jackson而漏标准String converter）、run04一Mockito旧抛错stub重绑定错误全部保留；修正的是测试栈/绑定方式，没有移除断言、降权限门禁或虚称产品物理失败。首次脚本SyntaxError/长组合命令工具策略拒绝/Maven reportsDirectory参数未生效也记录，不把历史红隐藏。

**仍未完成**：U107有受管DM8 DECLARE/RAISE_APPLICATION_ERROR守卫，结构参照既有U105，但本次H2不能执行该DM8块，未宣称正逆SQL都实测。实际U107拒绝非空收据/REISSUE与空库逆迁移尚需独立精确DM8准入；该实现Task暂不退出。更广后端源绑定回归、正式包/运行迁移/发行/现时身份核证/本人交付另证。真实DM8迁移、恢复、部署、秘密交付均0；设计反例与组件实测数不混同，当前固定25仍旧能力，旧值继续过期不可用。


## 9. 63源引用完整性集成最小工程D（2026-10-11北京）

62源码后完整治理于2026-10-10 23:58结束exit1：四项新增源完整性测试红，实际错误GRANT_SOURCE_CHANGED/源码SHA断言；不是四项权限失效，更不是全绿。两身份类的受控源引用仍钉旧SHA，完整性守卫正确拒绝，原log `verify-after-implementation62.log`保留。跨本机午夜后的日期为2026-10-11，原维护截止仍2026-10-13 18:00北京不变。

责任Codex。此最小工程D仅扩大确切受影响源码引用声明写集到 `deployment/single-database/application-capabilities.json`：刷新ProductIdentityController.java的6处、ProductSubjects.java的18处sha256，旧值必须等62保存的原始源码字节，新值必须等当前31组件绑定源码。保存cap原件并逐叶校验，只有这24个已知路径引用的sha256可变；任何对象列表/字段/SQL/原迁移SHA/privilege/重数/账号/业务scope变化均拒绝。不是新增恢复收据权限D，不把V107添入当前安装profile，不扩权限白名单或capacity。

该文件是后续源码编译的受控引用合同，不是已安装primary profile。已运行source、旧固定25 JAR、原105迁移/674权限及其封存installed profile/origin/raw不得修改；旧包/旧安装原件不以新源码SHA追溯重写。之前“current main profile consumes catalog without recompiling”专测仅证明受控源模板目录规模未缩减，不是在线profile或容器字节复核。在线/封存primary未操作的结论来自本轮精确文件写集与无DB/部署命令，不能由该fixture自证。禁止改比较器、adapter、已部署primary entry/封存installed profile、权限实际执行或部署。正式运行新V107的最小权限/迁移与发行尚需后续详细D/当前准入，本段没有定义或赋予收据表runtime/backup权限。

出口：保存before字节、24引用逐项old/new及全其余JSON逐叶不变证明；受影响安装/对象授权编译专测和完整治理重跑；历史四红保留，结果只据新log，不能对失败log换名伪补绿。源引用刷新只是工程集成，不升级31组件为DM8/运行/本人验收，U107实际验证仍未完成。来源当前源码/62六源SHA、cap原受控引用、verify-all四红及单独47/41一致复核，范围明确且技术阻断答案具证，不要求人类重批维护。


63源模板聚合引用补充（同日，责任Codex）：24引用刷新后对象授权专测通过50项，安装编译文件顶层因INSTALLATION_METADATA_SHA_MISMATCH拒绝（原件source-reference-component63.log保留），证明校验仍执行而不是旁路。根因固定源码模板deployment/single-database/installation-profile.json的capabilitiesSha256仍指原cap字节。最小源集成D只允许该源码模板一个capabilitiesSha256同步至已证明“其余全部字段不变”的新cap全文SHA；保存模板原字节，逐叶要求除该一字段外全相等，包括15账号、原迁移列表/SQL SHA、266表/已安装目录结构/十view、105原迁移绑定、检查、授权执行false及所有资源字段。该模板不是本轮的实际primary部署/封存运行profile，不改其运行原件、primary entry/容器/卷/JAR，不复写任何旧snapshot origin。V107不加进它，不增收据表权限；本次只是新源编译的连锁完整性引用刷新，旧包仍仅按旧原件证明。若发现需要第二个字段或改变运行安装数据则立即拒绝，不能将“同步SHA”变权限放行。


63集成结果：24条受控源引用及源模板一个聚合SHA已同步。原字节、逐引用old/new、其余全部字段/数组/重数相等证明见 `../evidence/activation-recovery-design-20261010/source-reference-integration63.json` 与 `source-template-aggregate63.json`。受影响安装/对象权限/JDBC容器专测退出0，原输出source-reference-component63b.log；没有修改权限定义、添加V107安装/收据权限或改变已部署primary。首次4源完整性红及随后的聚合摘要拒绝均保留，未通过重新命名洗绿。后置全量见verify-after-source-reference63.log，最终退出据原件；当前实现Task仍进行中，U107实际守卫验证/新表运行最小权限和迁移发行详细D/确切writer锁准入未完成，不能操作本人恢复或交付。


## 10. 64旧授权源定位保留最小工程D（2026-10-11）

63完整结构治理退出1，仅既有TESTS_FAILING/STALE，47/41一致、无新增组件红；这不证明授权源语义。独立离线语义检查发现24条源码引用不只有SHA，还含原具体行号（Subjects:55/75/91/97/105/113，Controller:126/143）。插入新方法/类型/进口会使原定位失真，因此不得用63结构绿掩盖；63原件保存，不追改旧报告。

责任Codex，范围仍同一实现Task，不加权限、API或状态。最小实现策略改为把新恢复方法/嵌套record追加到原类末尾，用完全限定名引用新依赖，保留62保存的原始类正文（最终class闭括号前的全部字节/方法/行）不动。对cap已有24引用逐项断言当前line正文恰等原字节版本相同行正文、上下文仍为原方法；失败即拒绝。不改cap line或其余字段。仅按第9节同步24个源码SHA及源模板一个capabilitiesSha256；原文件和中间版本保存。这是源代码布局/证据定位纠正，行为合同仍第7节，不改变原授权方法、迁移/对象/权限/重数或已部署primary。

出口：原类前缀完整字节相等、24具体定位逐项相等、只新增恢复成员的代码审查；新源码SHA绑定31专项/原身份受影响测试，再完整治理。U107 DM8实际执行、运行收据权限/发行D仍未通过实测；不能由本纠正放行任何DB/部署或本人恢复。该策略避免扩大引用字段白名单，不把原source hash当业务权限证明。来源原类字节、cap原line和63完整log，全部既有离线原件。


64实施核证：两原类末尾追加新成员，原class闭括号前全部源字节不变，24既有源引用的行正文与原版本逐项相等；不改cap line/权限/数组。相应24源码SHA与源模板单个聚合SHA按既有最小D同步，中间文件均保留，详细证明source-anchor-integration64.json。新最终源八文件SHA、六套31/31、0失败/错误/skip及原XML见component64/receipt.json；前一run05和63仍作历史，不覆盖。最终全量原件verify-after-source-anchor64.log，结果据日志，不以先前结构绿替代本次验证。实现Task仍不退出：DM8 U107实际验证、新收据运行/备份权限及安装发行D/确切准入未完成，当前固定25/本人激活路径没有新能力；实际迁移/恢复/部署/交付0。原需求/咨询专业、Grant/Source/模型及父A/B/C不因本源码变更放行。


64运行权限缺口的精确离线推导：当前咨询绑定为business CONPROD/CONPROD_SVC，维护authority CONAUTH/CONAUTH_SVC/CONAUTH_BAK。当前cap已经对ID2_SUBJECT_EVENT有SELECT+INSERT，原REGISTER读取不需要补它的读权限；新receipt尚未声明。依据新代码仅SELECT历史收据/INSERT成功收据，未来当前咨询MVP的最低声明仅CONPROD_SVC对CONPROD.ID2_ACTIVATION_RECOVERY_RECEIPT的SELECT/INSERT、无GRANT OPTION；不需UPDATE/DELETE/ALTER/ANY/PUBLIC，不据此给其他域加表。原15账号business没有backupUser，不能把CONAUTH_BAK跨入CONPROD；业务表保护仍走既有native全源保护边界，后续新增对象的snapshot/map真实覆盖另准入，不凭源码自证已备份。来源SHA和限制见minimum-permission-derivation64.json。本段是技术最小权限推导，未修改cap对象/权限或安装对象表，未执行GRANT/迁移；新的installed catalog/迁移发行与准确writer锁/资源详细D仍未冻结，不以此段运行。


## 11. 65真实逆迁移与安装发行的有界详细设计（2026-10-11）

责任Codex；技术推导不是原需求或新的管理员批准。任务状态仅docs/08。前置：64八源SHA本次逐项一致，31组件证据继承不重算进展；64完整治理只有既有健康红，本次verify-before-detail65.log另留原件。原55连续批准原文明确覆盖新备份、既有独立目标恢复验证以及过设计实现门的咨询修复部署；截止2026-10-13 18:00北京不变。原01管理员范围为六owner/六runtime/三authority backup初始化及最小DCL、精确读核验、停写原生保护和独立恢复，不由窗口自动扩为任意SYSDBA验证写。原文和受限工具SHA见validation-release-task-registration65.json。

### 11.1 ARC-CON-U107-DM8-VALIDATION-D-01：物理验证写集和拒绝边界

已导入目标789636…及target-tool e6e403…与原六schema/15账号/674授权、native import59和失败比较原件必须保留；targetNeverImported必须false。原59 empty入口和初始化/import不能重用。当前只读inspect：source及source-tool在线，target及target-tool停止；咨询9b/679在线，固定25 SHA见read-only-observation65.json。这只是观测，不是维护围栏、全部writer证明或运行准入；var递归*.lock未发现也不构成跨Docker/数据库锁证明。

最低验证合同有三类独立case：E空收据/空REISSUE执行完整V107及U107，逆向后无receipt且event原五值约束恢复；R只有一合法receipt时完整U107在首守卫拒绝，receipt、subject、event和约束逐字/库存不变；H只有一合法REISSUE事件时同样拒绝，事件原件保留。H与R不得混在一例冒两个拒绝覆盖。SQL必须从当前V107/U107原字节读取、绑定SHA；DECLARE块保持完整，客户端分隔符“/”必须由经审查解析器处理，不能将逐行切分或H2改写当DM8执行。数据库错误码/SQLSTATE原样留证但不记录凭据，必须区别期望-20001业务拒绝与语法/权限/连接错误。每次DDL先记PREPARED、实际调用结果或UNKNOWN、只读核对；连接未知立即停，不重发DROP。

原六业务/authority schema不能作为QA写集。候选是在已导入同target新增三个SYSDBA拥有的隔离非产品schema，绝不新建登录用户/账号/授权，不接正式应用。三个namespace及subject/event最小前置定义必须来自V032/V046原定义，case输入明确仅验证数据、不代最终产品能力；E可由U107删除本case receipt，R/H失败数据不自动清空/删除。命名、DDL精确SHA、前后六域库存/授权/目录比较和所有新增object记录必须冻结后才可实施。当前**尚不批准该候选**：新隔离schema和SYSDBA DDL/DML写不在原01精确列举中，55恢复验证的概括措辞不足以证明这组新增管理员写集已经获准。只需确切补足此差异，不能要求重批原维护窗口。

现有SingleDatabaseLifecycleJdbc.execute要求user==schema、禁止SYSDBA等管理员，forward白名单/fallback不能执行U107 DECLARE/DROP；dockerJdbc还钉mainClass/class SHA。因此不能透传U107、换class或借create-only API绕过。若批准隔离验证，应先另登记同用途受限工具工程任务，复用既有精确ID/network/非root/readonly/资源/源返回检查，新增的唯一模式钉false neverImported、三命名空间、三case及全部SQL SHA；禁止通用execute、跨schema、任意SQL、GRANT、初始化/import和原六域写。反例须覆盖SYSDBA之外、false→true伪装、原schema、额外语句、SQL/工具SHA漂移、资源/ID/窗口/锁丢失和UNKNOWN。不能把本候选说明当该新工具D已完成。

运行前必须现时核所有writer（五应用/发布器、DB sessions/主机进程、受管job）、原有锁及保护包，独占维护锁不抢陈旧锁；各精确writer停止并PID0后才停source/tool，核唯一在线DM8再启动原target/tool。仅复用59检查/恢复方法，不执行其empty guard或import流程。任何验证异常立即停target/tool、核PID0再依原顺序返回source/tool/原五writer；读取完整source inventory/catalog及严格TLS，逐项与操作前保护原件比较，预算≤7GiB/4CPU。若源返回未证，保持业务writer停止、报告确切失败，不声称安全返回。保留至少20分钟返回裕量；超过窗口/身份/保护/锁不执行。

物理D尚不退出：隔离管理员写授权证据、已导入专用受限入口和现时全writer/保护准入未闭合。VER-CON-U107-DM8-01不开始。跨grantor主恢复D依然阻塞，本验证也不能替代该判据或放行父A/B/C。

### 11.2 ARC-CON-RECOVERY-INSTALL-RELEASE-D-01：最小升级、保护与失败合同

精确升级只涉及CONPROD.ID2_SUBJECT_EVENT operation约束和新CONPROD.ID2_ACTIVATION_RECOVERY_RECEIPT；现有事件SELECT/INSERT足够，新表仅CONPROD_SVC SELECT/INSERT，无GRANT OPTION；不UPDATE/DELETE/ALTER/ANY/PUBLIC，不给其他域或CONAUTH_BAK，不重放原674授权/105初始化。源码cap/profile目前未纳V107/新对象和权限，必须新认证升级声明、安装结果和source bindings，不改封存primary/profile/旧包来伪造覆盖。原55有条件咨询修复部署授权继续有效，但这不是升级工程及保护已合格。

当前V107按DROP旧约束→ADD含REISSUE约束→CREATE receipt，执行port autoCommit=true。不能声称一次原子迁移；任一步可能提交后响应丢失。升级前完整停写+现时native源保护、精确目录/权限盘点、旧JAR/配置SHA及源返回可用。维护期正式新旧writer均不放行，逐步持久journal记录绑定的唯一SQL和前后目录。未知结果只读查询准确对象/约束及授权状态，不自动重放。若DROP已成而ADD未成，不允许应用运行无约束；若ADD已成但receipt未成，完整U107会在缺表处失败，不能盲执行逆迁移。须有另经审查的精确部分失败修复判据/SQL和DM8实测，未知/不匹配状态继续停写。这里没有批准新增修复SQL、也未修改当前V107顺序。

仅在receipt存在且receipt_count=0、REISSUE count=0并持独占停写围栏时，完整U107才有资格按其守卫尝试回退；成功仍须结构/原五值约束核验。非空receipt或任一REISSUE已提交就永久禁止破坏性回退/旧包冒兼容；保留新表和事件，暂停新恢复入口并前向修复。逆脚本查空与DROP之间必须无并发writer，否则TOCTOU可丢历史；不把SQL守卫等同自动围栏。receipt不含secret但仍是产品历史，不能删它来解锁回退。

新增表不被旧b0原生包或旧266库存/map自动覆盖。安装前包保护原未变源；安装后必须重新全源库存、目录、映射、new-object coverage及认证native包，并按获准独立恢复出口证实该新包。CONAUTH_BAK仍只覆盖authority，不能用跨business GRANT补洞。当前target已导入且跨grantor等价未通过，不能重import、拿count相同洗绿或声称新包恢复实证已完成。若新包恢复需另一目标/不同物理写集，先记录精确差异，不擅加资源。

实际部署准入还须真实U107三case、正常候选工程校验、源码绑定受影响更广回归、精确升级执行器/partial-DDL修复实测、真实保护恢复闭合、当时窗口/所有writer锁/预算/可信TLS。工程与部署实证齐备后才问本人是否准备好十分钟私下交付；此前不产secret、不恢复subject、不代设密码/激活。当前安装发行D不退出；后台旧咨询保持原25运行，不因离线包启动新路径。

### 11.3 IMP-CON-RECOVERY-NORMAL-PACKAGE-01：独立正常候选包工程D

已通过第7/9/10节源码D；本切片只是已有产品发行工具的离线构建，不执行业务API/DDL或模型调用，不生成身份。复用bundle-consultation及stage-consultation-product-assets，固定consultation-product-classes/static/app受控输出，不改共享target/classes、产品页面或profile门禁。Maven只用现有离线依赖，JAVA_HOME=JDK21、MAVEN_OPTS=-Xmx768m；-Dmaven.test.skip=true仅跳过本次测试编译/执行、不重复未变31组件，候选构建不能当新回归通过。

输出新独立finalName agent-platform-consultation-activation-recovery-20261011-65；预检目标JAR不存在，所有现有target JAR及原运行固定25 SHA先存、完成后严等，禁止clean/覆盖旧候选/部署文件。不下载依赖；无共享构建writer才开始，前置治理子进程结束后单一执行。SQL只作为同SHA sidecar，当前Flyway exclude db/**行为如实保留，不能因sidecar存在声称V107自动安装。八源SHA必须和64证据一致，并锁当前pom/stage/frontend inputs；构建后检查manifest、SpringBoot loader及咨询assets清单、两identity class/嵌套类型、javap真实方法/POST注解activation-recovery、禁混三域产品HTML。每项输出留原件，失败不得部署或覆盖固定25。

该Task出口仅候选JAR与sidecars及源/旧包完整性证明；标工程完成不代表安装、部署、DM8验证、本人激活或验收。完成后自动接续仍合法的安装/工具D，不绕过物理阻断。


65正常候选实际核证：normal-package65/maven-exit65.json记录离线bundle-consultation构建退出0，测试编译/执行显式跳过；不作为新31通过。唯一新JAR及SQL sidecars SHA、实际class/javap/POST路由/咨询启动分支、咨询唯一HTML与assets每字节SHA核验见normal-package65/candidate-receipt65.json和verify-candidate65-03.log（退出0）。旧69个target JAR逐项SHA严等、固定25在原9b容器/app/platform-api.jar前后SHA严等，八源/806后端main source-resource及排除log/tsbuildinfo的真实前端inputs保持。旧原始广观察before-build.json保留，真实构建输入以build-inputs65.json为准，生成日志/tsbuildinfo不是源输入。

初次产物检查误写Start-Class=PlatformApiApplication，实际原源码与JAR都是AgentPlatformApplication；第二次javap默认中文GBK被UTF8严格读取拒绝，两份检查器/原trace均保留。第三次按原源码确定主类并实际javap验证--product-consultation分派，设置英文JVM输出、保持路由/资产/类/旧包断言，退出0；没有产品源码或JAR重建来迁就检查，也未把失败消音。脚本build-candidate65.ps1确实等待已知前置治理worker结束、拒绝并发Java/Maven/前端writer，再单一构建，不运行新包。

verify-before-detail65.log完整退出1，1844工程测试/179质量测试均0失败；47基线/41台账一致，仅既有TESTS_FAILING:project和TEST_EVIDENCE_STALE:project。该运行在设计修改前启动、完成时覆盖已写详细D，不冒全部修改的最终后置；未变64完整治理仍是前置依据。最终后置独立执行verify-after-detail-package65.log，按原log结论，不声称全绿。候选工程出口已满足，安装/物理验证D和主跨grantor阻断仍未闭合；实际DB迁移/身份恢复/部署/交付均0。


65首后置原件verify-after-detail-package65.log退出1：47/41基线一致，但候选Task已完成行缺实际完成日期，新增COMPLETED_WITHOUT_EVIDENCE并依法中止后续阶段；不能报仅旧红。唯一纠正为主计划补2026-10-11实际工程完成日期及实际候选证据列，不改状态判据/测试/产品源码/部署或需求。首红及日期前主计划字节保留master-plan.before-completion-date65.md。纠正后再独立完整verify-after-detail-package65-02.log，最终结论以该原log为准，原健康红仍不隐藏。


## 12. 66部分DDL最小入口设计与授权差异复核（2026-10-11）

责任Codex；原业务D不变。原01人类批准绑定requestOriginal，原scope逐项为六域空初始化/minDCL、库存核验、围栏原生保护/独立恢复；原55扩连续窗口/条件咨询修复部署，没有单列三新SYSDBA隔离schema/自编QA表及正逆破坏性DDL。缺技术模式/技术任务ID本身不是缺批准；现有SYSDBA能力足够也不是新增数据库权限。确切未被原scope证明的只是**已导入789636目标上的额外隔离写操作**，不是源停返、备份、既有六账号或窗口。源SHA/原文及结论见scope-partial-entry66.json。建议合并新增执行范围只含ACTR66_EMPTY、ACTR66_RECEIPT、ACTR66_EVENT三个SYSDBA拥有的非产品schema及V032/V046原定义最小subject/event fixture、当前V107/U107三case；不新账号/GRANT，不改原六域，不初始化/import，不擦非空案例。三case固定输入是自编隔离工程验证数据，绝不进入正式产品身份/Source。精确SQL/物理入口及现时围栏仍须独立D，即使该scope补证也不直接运行。安全替代为继续下面只读元数据编译器；H2不当真实出口，不能用旧批准或技术D自行补授权。

### 12.1 ARC-CON-RECOVERY-PARTIAL-DDL-ENTRY-D-01限定D

范围仅新增scripts/lib/consultation-activation-recovery-migration.mjs、对应.test.mjs、scripts/prepare-consultation-activation-recovery-migration.mjs及verify-all测试登记；不是SYSDBA/JDBC执行模式，不扩旧port、不改产品源码/SQL/profile/cap/JAR/已部署。技术依赖第7/10节源码D和第11.2部分DDL合同；与物理安装D分离，不能依赖未过的安装D开DB。需求来源是已冻结同身份恢复合同的历史保全/失败恢复，不添加业务状态或接口。

输入为严格有限元数据：版本8.1.4.170、精确target ID、schema=CONPROD、UTC捕获时间、raw inventory SHA、约束识别结果OLD5/RECOVERY6/ABSENT/OTHER、receipt识别结果EXACT/ABSENT/OTHER、十进制字符串计数及NOT_RUN/KNOWN/UNKNOWN上次SQL结果。OTHER/未知额外字段/数值精度损失/缺表却非null行数/负数、其他目标/schema/版本全部拒绝或保留停写。结构标签只代表上游核验的元数据声明，本工具不能认证其真实性；输出必须metadataTruthProven=false/executionAuthorized=false/migrationExecuted=false，并绑定观察SHA及V107/U107原SHA。必须当前原SQL bytes及固定64摘要匹配，不接任意SQL/URL/替代脚本，不反向改原migration。

FORWARD仅判定原V107三个autoCommit步骤的下一条：OLD5+无receipt/无历史→DROP_OLD_OPERATION；ABSENT+无receipt/无历史→ADD_RECOVERY_OPERATION；RECOVERY6+无receipt/无历史→CREATE_RECEIPT；RECOVERY6+EXACT→SCHEMA_READY（还不代表权限/部署/保护）。无约束但EXACT且所有operation合法时只能建议ADD_RECOVERY_OPERATION前向恢复，不删历史。OLD5+EXACT、OTHER及缺receipt却REISSUE非零等无法来自受控正常路径/矛盾结构HOLD，不揣测。每次只返回一条原SQL的标识和SHA，不返回可执行批次；运行端后续应逐步journal并新只读复核，不能复用原观察跑下一步。

REVERSE：有任何receipt/REISSUE→FORWARD_ONLY，无破坏性SQL建议；完整新结构且两计数0最多返回GUARD_REVERSE_REQUIRED（绑定完整DECLARE块），不得从离线查空直接建议DROP。任何partial→PARTIAL_REVERSE_NOT_ADMITTED，不将完整U107用于缺表或无约束状态。回退的后续DROP/ADD必须待真实三case、独占writer锁和已审查逆向journal入口另D；本工具不新增“自动清理”。UNKNOWN始终REOBSERVE_REQUIRED、无下一SQL，不按时间/旧count猜提交结果。非法operation count>0保持HOLD。

CLI只读已保存的非秘密JSON，大小≤64KiB，输入/输出解析绝对路径限定workspace且逐祖先拒绝symlink；输出独占wx且输入输出不可同文件，不建/删目录、不调用child_process/Docker/JDBC/网络或secret环境。输出JSON计数只是历史元数据，不是现时writer/保护准入。错误只输出稳定代码、不回显内容。反例覆盖额外权限字段/任意SQL、伪target/schema/version、未导入模式字段、摘要漂移/软链、各partial/UNKNOWN/非空历史、BIGINT字符串、CLI独占/遍历/输入原件不变。保持旧SingleDatabaseLifecycleJdbc和dockerJdbc源码SHA严等。此限定D通过仅允许上述元数据工具/专测，物理安装/U107/本人分支仍阻塞。


66限定入口实现证据：partial-entry-receipt66.json绑定三个新文件及verify-all仅新专测登记；41专项/96状态矩阵及实际CLI反例退出0、无skip。最初对U107的LF分隔假设被原CRLF字节拒绝，partial-entry-component66.log/源中间字节保留；只将已钉原SQL的分隔符识别改为CRLF/LF，完整DECLARE内容不改/不拆，SQL原SHA与反例不放宽，第二次partial-entry-component66-02.log通过。八原业务源/SQL/cap/profile和两个受管Java/JDBC入口SHA严格未变；31组件/65正常包继承不重跑/重包，无DB/资源写或secret。输出永远executionAuthorized=false，schema-ready明确不是runtime-ready；已得到“DROP成功缺约束→仅建议原ADD”“ADD成功缺表→仅建议原CREATE”“UNKNOWN→不重发”“历史非空→不逆向”的具体机械行为，但**没有**DM8 partial修复执行、可信metadata采集/SQL executor、安装部署或普通本人可用实证。原物理D/三真实case/新receipt权限与全源保护恢复/跨grantor阻断仍未退出。最终完整治理见verify-after-partial-entry66.log，依据实际退出/原log判断不把专项通过当项目全绿。


## 13. 67已导入隔离验证受限JDBC步执行器源码D（2026-10-11）

责任Codex；继承66最终治理/未变业务源、31组件和候选65，不重包/重做离线诊断。任务ARC-CON-RECOVERY-ISOLATED-JDBC-STEP-D-01→IMP-CON-RECOVERY-ISOLATED-JDBC-STEP-01。本节限定D只准scripts/java/ConsultationActivationRecoveryJdbcStep.java和scripts/java/ConsultationActivationRecoveryJdbcStepTest.java及原证据目录中的独立javac/test输出；不改SingleDatabaseLifecycleJdbc、dockerJdbc、profile/cap/产品类/迁移或正式JAR。只有标准JDK/JDBC，不新依赖/平台，不连接真实库编译验证。

闭合操作集为V107三个原DDL单步及U107完整guarded inverse；SQL只从当前V107/U107固定SHA原字节加载，保留完整DECLARE及CRLF，不接传入SQL。唯一targetId=78963630b94072dd64c1a7701200c4c48bf837350a9f85b5c9126cfd934ff481，schema只ACTR66_EMPTY/ACTR66_RECEIPT/ACTR66_EVENT；仅SYSDBA/DM8 8.1.4.170/既有target内部别名5236。不能进入原六schema/SYSDBA schema、创建schema/fixture/账号/GRANT、dminit/import或清空记录。fixture须由另一已获scope及详细D的步骤准备，本源码不暗含它。

执行器为没有main/CLI/Docker调用的封闭JDBC库入口；调用者必须给实际Connection、当前LiveGuard和Journal三个非空端口。LiveGuard每次metadata/SQL前核同target/schema/runUUID的当前操作授权、writer独占锁和物理守卫；本库不把hash/布尔值当人类授权，不提供“默认通过”guard实现。部署调用/批准原件解析、真实锁/容器绑定仍须后继已导入专用适配D，不用源码可编译冒本分支可运行，旧dockerJdbc仍拒绝调用此类。测试中的代理Connection/Guard是组件验证，不是产品替代路径或scope批准。

metadata读取只在被允许schema：DBA_TABLES确认subject/event基础存在及receipt是否存在；DBA_CONSTRAINTS读取唯一指定operation CHECK，严格识别原五值/新六值/缺失，拒绝其它表达式；定向计数REISSUE/非法operation/receipt。V_DROP要求OLD5+无receipt/无历史；V_ADD要求缺约束/无非法operation，若receipt缺而已有REISSUE拒绝；V_CREATE要求NEW6+receipt缺/无REISSUE。重复、旧观察或矛盾state不重发DDL，每次重新查metadata。表/约束“EXACT”尚不由本最小查询证明完整receipt结构，物理运行前还须可信全结构核证，不以COUNT冒定义正确。

U_REVERSE_COMPLETE只允许完整新operation约束+receipt存在。先执行原完整DECLARE块（包括两真实非空case的实际raise尝试）；非空观察下guard若没有拒绝，立即停止且不执行任何DDL。任何receipt/REISSUE存在绝不DROP；原guard的-20001与其它SQLException分开保留vendor/SQLSTATE，不将语法/连接错误冒通过。只有两count为0且guard正常返回、LiveGuard仍成立，再逐步原DROP receipt/原DROP operation/原ADD五值；每条破坏DDL前再核REISSUE/receipt数量与guard。外部独占围栏必须覆盖全部步骤，本库多次查空不声称消除无围栏TOCTOU。任何partial逆向前态拒绝整U，逆向中途失败不清理/自动重试，保持停写并走目标停止/源返回。

每条SQL调用必须先Journal.appendAndSync(PREPARED)，再LiveGuard/实际SQL，返回后appendAndSync(COMPLETED/REFUSED)。内建ExclusiveFileJournal仅新建指定绝对文件、拒绝软链/既存文件，持FileLock并force(true)，无secret/SQL正文，runId/固定step及SQL SHA记录；不是任务状态账。PREPARED持久化失败时不执行SQL；SQL已尝试后任何异常（含提交后journal失败）标UNKNOWN，不猜回滚、禁止执行后继语句。Guard或metadata拒绝不生成业务成功结果。异常只稳定代码+净化SQLSTATE/vendor，不携带数据库正文/凭据。源码有真实Statement.execute调用，不以返回模拟成功冒实现。

验证：独立javac、代理JDBC验证真实受控SQL调用顺序/原块完整性、每个V partial/重复、U空成功/两历史拒绝/guard错误未拒绝/SQL异常和journal提交后失败、current guard丢失、原schema/账号/版本/URL与任意SQL不可表达，内建journal既存/软链和前后原端口/业务SHA。反例不扩为H2/真实DM8判据。详细D已闭合仅源码切片；三schema物理scope**仍未批准**，实际DM8三case/partial修复/部署/本人出口0，源安全返回要在真实操作后独立实证。此D不补批准，不关闭安装D/原跨grantor阻断。


67实际源码核证：两个新Java类已实现封闭JDBC步调用、当前schema/account/version/URL/autoCommit检查、实际定向metadata/历史读取、原V单步/U完整guard及非空禁止DDL、PREPARED fsync/后继停止、UNKNOWN与PARTIAL_COMMITTED_STOP_REQUIRED区分；无main或驱动连接/秘密/资源操作入口。内建新建独占FileJournal实测既存不覆盖、错误run及Windows junction父路径拒绝。jdbc-step-component67/receipt67.json绑定最终两个源及classes04字节；javac67-04/tests67-04实际退出0，31新专属代理JDBC组件通过，不混同原身份31，也不是H2或DM8实测。第一版专测泛型条件Class推断编译红javac67.log及原测试源码保留，仅改类型变量没有删断言；后续增加metadata异常净化、持久化前guard丢失拒绝、提交后日志UNKNOWN/partial已提交停止等边界，最终原块CRLF字节比较及闭合SQL调用顺序已核。八业务源/原两执行port和66元数据工具/verify-all SHA全严等；65/原身份31不重做。

此源码出口不等于已部署受管入口：旧dockerJdbc依旧钉原mainClass并拒绝新类，尚无本类已安装容器调用层或真实LiveGuard。未来调用层还须同一scope批准原件、exact target/tool/network/class SHA、现时完整writer锁/资源与真实journal绑定、完整fixture定义盘点和源停返；不得用测试ACCEPT guard或hash/布尔值作运行权限。fixture安装源码/真实安装也未本轮执行。本次没有DriverManager连接/目标启动或迁移/部署/secret；主物理D/三schema授权/跨grantor依然阻塞。合法后继回到这两个原详细D落实实际准入，不扩旧owner-forward端口。最终完整治理原件verify-after-jdbc-step67.log，按原结果留健康红，不用组件计数替代业务进度。


## 14. 68可信调用与现时准入最小源码D（2026-10-11）

责任Codex。稳定任务ARC-CON-RECOVERY-ENTRY-ADMISSION-D-01→IMP-CON-RECOVERY-ENTRY-ADMISSION-01；前置67内核源/31新代理组件、66和候选65，均未变不重跑。仅新增scripts/lib/consultation-activation-recovery-entry.mjs及.test.mjs、scripts/java/ConsultationActivationRecoveryJdbcEntry.java、ConsultationActivationRecoveryFixtureGuard.java、ConsultationActivationRecoveryEntryTest.java，verify-all登记新Node专测及原E/entry-admission68编译/projection/来源证据。核心67/原Java/JDBC端口/产品源码、SQL和发行原件不改。本节限定D准离线源码，不给三schema物理授权、不部署。来源是原恢复合同的不可破坏回退/UNKNOWN/现时维护许可与已有原生保护/独占责任机制，不新增业务功能或诊断平台。

真实调用链封闭到固定789636 target/e6e403 tool/047a31 network、67三schema和四Operation。独立新main是严格逐行JSON/RPC桥，不透传SQL/用户/URL/密码/TTL；仅已验证的host wrapper可回复单调sequence+随机nonce的GUARD/JOURNAL ACK，无默认通过guard。原dockerJdbc不放宽，只复用validateOwnedJdbcToolResource、窗口55原字节验证和openSnapshot认证。由新窄wrapper核全套新桥/67嵌套class/dependency SHA、alias/ownerTask/nonroot/readonly/无JVM注入，固定classpath/java/有限timeout。参数仅继承三个LIFECYCLE_JDBC_*环境名称，无秘密值argv。所有外部消息都是不可信输入；extra key、重复JSON字段、超长、错误run/schema/nonce/sequence、UNKNOWN均拒绝，原SQL只readonly侧挂原件。

批准/当前D：每次GUARD重新读取docs08中原物理D已完成及其确切scope批准引用，和同根scope证据、原人类原件/角色/线程/版本/日期/结论/三schema/target/截止的SHA链。运行请求不能提供approved=true或临时替换proof。真实新scope原件当前不存在且原物理D仍阻塞，真实wrapper必须在spawn/创建锁/读secret前拒绝。本结构检查只验证已审阅证据的完整性，不证明法律或自然语言结论；scope语义必须由具源人类原件与唯一计划决定。窗口复用readContinuation55的原文核验，只取窗口/原预算，不调用empty-target resource入口，也不以窗口action当ACTR授权。20分钟返源保留线。

现时资源：来源source/tool+五app/publisher全固定七ID必须stopped/PID0；target/tool running且同ownerTask/image/network/alias/volume绑定，无其它在线owned/network资源，一在线DM8/≤7GiB/4CPU。每次当前Docker实际读，不能仅用历史inspect或供应布尔值。CIM风险writer进程当前PID/create/parent/命令SHA与已审阅责任原件核对（自身控制器/已跟踪docker exec子进程除外）；未知或不可读进程拒绝，不kill/删锁。原source维护锁不得存在；target锁wx新建且每次核本run随机marker，不抢旧锁。桥内真实连接每次GUARD先验当前V$SESSIONS总数恰1，不豁免其它SYSDBA session/旧fence连接；此表含全部会话的本版本手册依据定向留证。主机/DB只读核验不能在无独占责任前提下声称解决特权管理员并发；这些判据与当前批准/责任/锁并列，任何一项不满足保持停止。

持久日志：只读inspect证实既有target-tool只有/jdbc与/prep两个RO bind，故不臆造container RW journal。采用host独占新journal+FileHandle.sync；Java Journal RPC收到host完成fsync ACK才可返回给67执行器。PREPARED/JOURNAL拒绝即不执行SQL，COMPLETED ACK丢失停止UNKNOWN，记录不含secret/SQL正文/行数据。桥无响应/格式错误/timeout/guard丢失只停止自有子进程并保留锁/失败原件，不杀未知worker，不自动启动/停止DB或清理partial；真实编排仍须停target/完整返源并核库存/TLS后才能独占持有者释放自己的锁。成功响应也不自动放行产品/源返回。

fixture完整绑定：entry-admission68/fixture-contract68.json由真实59源码库存的subject/event metadata和固定V032/V046/V107/U107四SHA派生，不导入历史数据。subject/event列、默认、PK/FK及CHECK来自8.1.4.170实证；receipt定义仅来自当前V107的技术预期，不能冒已安装实证。桥内FixtureGuard实时核该schema限定两/三表、列顺序/类型/长度/nullability/default、PK/FK当前同schema关系、全部CHECK/启用验证/operation仅原五/新六/允许partial缺失、无trigger/view/sequence/额外DDL对象；receipt两比较AND式只认有限完整表达式，不宽放任意条件。三schema仅固定工程数据范围（空/最多一subject、一receipt或一REISSUE），不读实际密码/secret内容；同时对未知结构拒绝、只读state不能自行初始化/修正。底层内部生成index/constraint名称不能以名字相同冒定义，本slice有界核语义结构，native完整目录及原六域不变仍需物理阶段实际前后库存，不宣称本slice已验证全native目录。

现时保护：不启target、不备份/导入；实际调用要求同run/current source停写保护原件、认证snapshot及确切file SHA/仍有效期限、source停时晚于捕获且原Source返回合同留证，复用openSnapshot只在全部当前scope/资源门过后用受管key认证、清零native bytes，不输出plaintext。每次hash和有效期重核，旧包/旧token不会由本main复活。缺记录/身份/结构/权限/保护/lock或UNKNOWN不发ACK、不调用SQL。full source return由原编排责任者完成，此切片不模拟通过。

出口分开：源码/真实API调用组件（假的Docker/审批/stdio只能组件）；真实DM8三case/partial修复；安装/正常产品部署；本人操作。只有第一项本轮可实现。反例覆盖当前真实计划/缺scope spawn=0、批准引用漂移/过窗/预算/所有writer/unknown/CIM/锁/host journal失效、tool/class/dependency/hash/alias/env/mount漂移、fixture细字段/FK/check/额外对象/session>1、RPC nonce/seq/extra/duplicate/UNKNOWN，原源/67/端口严等。不得用组件数据派发真实命令/生成新批准原件。物理D和范围批准未过，不接身份secret。此限定D通过仅上述源码切片。

68实际源码/离线增量（2026-10-11，未退出）：三新增Java独立classes02编译，38代理JDBC/内存RPC组件通过；47新增Node组件通过（假scope只在owned component-scratch，真实计划scope拒绝发生在observe/command/key/lock前）。first red java-component01保留：receipt mock误用contract描述占位而非固定两比较AND表达式，修的是mock、未宽放实际Guard。Node首绿存在FileHandle GC警告，后修正成功/失败均关闭自有descriptor但保留锁/日志，component03无该警告。已增加转义JSON键重复检测、nonce去重、首GUARD及operation/完成phase绑定、完整transitive manifest集合、真实镜像/role/原mount来源/固定volume核验；原67/原两port/源8/66 SHA严等。组件不证明真实DM8元数据与授权。

源返回出口纠偏（派生技术决定，不是原需求结论）：原未验证草稿把 supplied verified/strictTlsVerified 布尔值和声明摘要当返源释放条件，不足。当前releaseAfterSourceReturn明确报CURRENT_SOURCE_RETURN_TLS_INTEGRATION_REQUIRED_LOCK_PRESERVED，任何SQL成功/失败均不删除lock，不启动/停止资源。本切片**未完成**源返回严格TLS producer/当前full6/15库存、预算/一在线资源证据及独占owner释放接线；须先补同任务详细技术D/源与专测再完成68，不把门禁拒绝冒作已实现返源能力。现行scope/原物理与安装D仍未通过。完整收据与后置治理见原E entry-admission68/receipt68.json；不创建另一任务状态账。

68本轮后置完整治理实际结果：verify-after-entry68.log exit1，第一组件阶段1932/1930通过/0失败/2跳过、后续179/179通过；47源文件/41需求行基线一致。唯一错误仍原TESTS_FAILING:project、TEST_EVIDENCE_STALE:project，不把现有project健康红洗绿；结构通过与本轮组件绿不代表物理/法律/业务验收。收据落证后再次完整治理日志固定verify-after-entry68-recording.log，仅确认记录没有引入新红，源码任务仍进行中。

### 14.1 68同任务剩余出口详细技术D（2026-10-11，先D后码）

owner=Codex唯一写集；ARC-CON-RECOVERY-ENTRY-ADMISSION-D-01补充，实施仍IMP-CON-RECOVERY-ENTRY-ADMISSION-01，不加新通用工具/并行ledger。受理本次委派只是源码优先级，不新增三schema物理范围。继承原38Java/47Node原件和最终治理同字节pre；精确来源source-return-design68.json。原67/66/31/65不重包或单独重跑。本轮新增测试只执行exit68命名子集，完整治理按既有要求。

返源释放D：恢复动作由原编排责任者完成，本wrapper不启动/停止任何资源。release输入是固定format/run/source/ownerPid的请求而不是“verified”回执。闭包ownerPid=创建者process.pid及wx锁随机marker；同一闭包并发释放/活动docker子进程/未知worker/原source锁/marker换人/任一失败均不unlink，失败也不kill外部worker。历史失败/UNKNOWN不放行后继SQL，但允许原owner在完整安全返源证明后释放自身锁；不会自动重试SQL。

当前资源读前、库存后、TLS后及最终释放前都核固定九ID；target/tool必须stopped/PID0，source/sourceTool/既有五writer running/PID>0且owner标签完整；引擎所有在线ID恰这七个，source是唯一在线DM8、预算正数≤7GiB/4CPU；source镜像/primary-role/原volume、sourceTool当前有界nonroot/同network/原RO /jdbc和无JVM注入、原发布器loopback5446/5447/5448及app绑定不可变。current host responsibility对PID/create/parent/commandSHA逐项核，不豁免未知进程。四次资源指纹必须同Pid/StartedAt/mount/image/network/limits（不包括瞬时探测worker）；责任证据及policy/snapshot/source return plan引用逐次重读，不复用历史inspect供应布尔。window55只作为原维护窗口，返源不要求重新ACTR写授权，窗口closed则保锁不启动/停止/拒绝外部安全恢复。

库存真实producer复用原SingleDatabaseLifecycleJdbc+source-snapshot-config56.json固定SHA的inventoryJdbc，固定action=inventory、sourceId、6schema/15account/原restore-profile，无caller SQL/main/classpath；原adapter自己实时核source/sourceTool/class。默认调用是真实jdbcRequest，组件DI才是假port；本轮不会调用真实producer。每次producer原输出先bounded32MiB、wx保存并sync，解析后assertInventory(原deployment)+assertRestoreCatalog+deriveCompleteNativeForeignKeys，重算inventoryDigest/实际restoreCatalog/nativePrivileges哈希与同run认证保护比对，绝不读取供应inventorySha256/catalogSha256当结论。TLS前后各一次新producer，两个实际摘要严格等于保护；历史59库存不能代当前调用结果。任何raw/parse/结构/全量/摘要/producer失败保持原锁。完整source returned并不等于业务验收。

TLS真实producer继承59安全返回既有consultation三公开GET合同：/app/consultation、/.well-known/openid-configuration、/login，consultation.agent-platform.internal:5446；原public-client23固定SHA只取public CA ref，CA原确切path/SHA，server DER SHA de4f652f8f99db36e7b7049fab104de0c0918a0e68dcc2dbd26fad6451374f06。真实DNS单127.0.0.1，直连loopback（无proxy/redirect）、TLS1.2、标准CA链+SAN/hostname+当前cert时间/EKU由标准TLS验证及额外server pin；无client cert/private key/cookie/CSRF/login/模型。每个GET 200、最大1MiB/20s，只存status/证书pin/bodyDigest/长度，不存body/header/cookie；OIDC issuer及正常login form仍核既有59合同。供应verified=true不能代真实握手/GET；组件只通过明确DI/mock socket/producers，不产生实际CA、证书或凭据。证据包含当次source/五writer PID/StartedAt与run绑定，前后资源不一致拒绝。

最后资源/host/文件marker重核→新source-return-proof.json wx+sync持久记录两份原inventory及TLS投影/actual digest/current-resource digest/owner/run/marker hash，成功后只unlink本闭包自己的target lock；原日志/失败/包/source lock/其它锁绝不删除。proof写或fsync/unlink失败则不得报成功，不自动重试。仅文件fsync，不声称Windows目录fsync或跨崩溃原子删除；有proof且锁仍在是需owner reconcile状态，不冒释放完成。

目录级class D：现有sha256sum之外固定find classes -mindepth1 -printf相对路径/type，有界输出；仅确切production class集合且type=f，无子目录/额外class/资源/symlink。普通class/hash/权限异常均前spawn拒绝。

有界出口测试集：新增返源full6/15缺失/摘要真实漂移/旧布尔、current target/source/tool/writer/预算/未知host/源重启、实时TLS标准选项/名称链pin/非200/过大及断连、owner/marker/并发/日志落盘与unlink失败拒绝；目录额外/缺失/symlink；protocol重复nonce/错误操作RESULT/REFUSED伪成功/无GUARD/尾部/重复RESULT。Java只补RECEIPT/EVENT非空工程fixture及错case/秘密字段拒绝新子集，不重跑旧38。齐套即source task退出；真实DM8/批准/安装D/跨grantor继续分离，不追求无限反例。

14.1实施组件出口（2026-10-11）：默认复用原source inventoryJdbc生产者、固定action inventory+profile、原public CA/SAN/server pin/TLS1.2/三公开GET；完整raw wx+fsync、assertInventory/RestoreCatalog/nativeFK、真实inventory/catalog/nativePrivileges摘要重算，TLS前后两库存与当前Pid/StartedAt/网络/预算/七在线resource全等，source package和source pin重核。默认不是verified布尔口，测试DI只在owned scratch。own marker/ownerPid、活动子进程、source其它锁、并发释放、raw/proof fsync及unlink失败拒绝保锁；持久proof不声称unlink原子/业务验收。闭包release只核既有返源、不会恢复/启停资源。

本轮新增49 Node/11 Java组件绿（node-exit68-04.log/java-exit68-03.log），旧47/38原件继承未单独重跑。原67/66/身份31/65不重包或专门重跑；source8/原两ports/67全部class及66三源SHA严等。Java只编改动FixtureGuard+新测试，Entry生产classes02及core04继承；17个生产class manifest精确目录及SHA，未来安装仍未获准。nonempty RECEIPT/EVENT正向、错case/历史缺失、固定event/request ID及password/activation/disability/version拒绝已补。首red node-exit68-01保留：immutable JSON可选字段undefined被canonical拒绝，修为JSON inspect正规投影，不宽放缺资源/预算/权限。TLS明确仅发送Host公开域名头，无Cookie/Authorization/private-client证书，body只hash/长度、真实issuer/login字段核59原合同。本轮真实SQL/TLS连接/资源启停/secret交付0；有限源代码出口待后置治理核定即结束，不追加假想反例。

14.1源码任务实际退出（2026-10-11）：verify-after-exit68.log完整exit1，仅原TESTS_FAILING:project/TEST_EVIDENCE_STALE:project；第一阶段1981/1979通过/0失败/2跳过、后续179/179通过，47/41基线一致。有限源码/编译/C/原SHA/前后治理出口齐，IMP-CON-RECOVERY-ENTRY-ADMISSION-01在唯一计划已完成；不把真实DM8/人类三schema scope/跨grantor/安装/产品部署/本人激活带过门。旧部分原件和每次红/绿均保留，旧源码原字节.before-exit68可恢复。落证后按固定verify-after-exit68-recording.log复核；不再造假想反例。按既有路线转已过R/D的IMP-CON-PRODUCT-SOURCE-REGISTRY-01候选登记与撤回独立专项，仍不开放ACTIVE/模型/原文/实际注册或数据库操作。


## 15. 69 U107验证与安装保护的具体执行合同（设计补强，不退出D）

2026-10-11，owner=Codex；接续现有ARC-CON-U107-DM8-VALIDATION-D-01、VER-CON-U107-DM8-01、ARC-CON-RECOVERY-INSTALL-RELEASE-D-01，不重做31/65—68、候选包或组件。原件/SHA见minimum-recovery-design69/design-evidence69.json；既有V107/U107/fixture-contract68/bundle68完全不改。用户工程委托仅准设计，ACTR66或跨grantor scope仍需确切来源。

### 15.1 ACTR66三case与部分DDL真实出口

写集不超过SYSDBA拥有的ACTR66_EMPTY/RECEIPT/EVENT及68固定fixture-contract：从原V032/V046已钉定义产生subject/event工程前置，固定V107三个原DDL和完整U107 guard。R仅一个合法receipt且无REISSUE，H仅一个合法REISSUE且无receipt；两类分别验证。旧67/68已经有封闭JDBC/可信调用源码，不再次说完全无入口；尚缺批准后的fixture准备、真实安装/版本/模式/所有writer围栏，而不是让人类补技术ID。

E的顺序明确为：记录OLD5/无receipt/历史空→V_DROP_OLD提交并只读核缺约束（确定部分状态，业务writer始终停）→以原ADD恢复NEW6并核非法operation为0、receipt仍无（第二个确定部分状态）→V_CREATE_RECEIPT并核PK/FK/version CHECK与空计数→完整U107→核receipt消失、OLD5及原subject/event数据一致。这条单链用已有三步入口验证两个真实已提交partial的前向修复，不新增破坏式故障注入，也不称它是响应丢失/崩溃测试。R/H各自加载68明确非空工程历史，完整U107只允许首guard预期拒绝，所有行/结构保持；语法/权限/连接错误不能充期望RAISE拒绝，保存实际vendor code/SQLSTATE及原声明块定位。

任何UNKNOWN暂停：只读当前对象/约束/历史和原journal；不凭旧计数重发DROP/ADD/CREATE，不把不完整状态交给完整U107。原67四操作不会修复任意U逆向中断：若receipt已DROP而旧约束未恢复，保留partial目标并返源；后续需对确切剩余原SQL单独审查，不在本轮扩大执行器。R/H历史不删除或清空。本轮三真实case/partial修复均未执行；source-only组件与物理结果分列。

### 15.2 安装／保护的严格先后与各自放行点

1. **执行D前置**：跨grantor最终等价规则/适用范围有证；上述U107及确定partial实测闭合；冻结正常65候选JAR/SQL/source bindings、独立正式升级声明（只增新receipt及两权限、原674不重放）与封闭CONPROD升级/partial核对入口。67/68仅准隔离ACTR66，不能把namespace改成CONPROD充产品安装工具。源工程实现须先登记/过相应D；本轮不改cap/profile、比较器或业务源码。
2. **副作用前保护与物理准入**：依原55窗口取当前全源保护，包括51实际持久身份和全部六域/15账号，固定旧JAR及当前结构/权限/raw。普通writer先全停，当前锁、所有host/DB writer、预算、秘密受管交接及安全返回余量具证才动作。还必须提前确定新包独立恢复的确切空目标/受限初始化导入范围、资源/存储与失败返源办法；缺这个后继出口不先写产品DDL。原已导入789636及其六域/失败原件不可重初始化、覆写或重import。候选新空目标尚无精确物理ID/写集批准，不自动创建，也不把旧targetNeverImported改true。
3. **维护态安装**：仅CONPROD.ID2_SUBJECT_EVENT的原operation CHECK、CONPROD.ID2_ACTIVATION_RECOVERY_RECEIPT及其原PK/FK/version CHECK；按原V107 DROP→ADD→CREATE逐步提交/持久journal和现时metadata核对，不能称原子事务。新表仅CONPROD_SVC SELECT/INSERT，无GRANT OPTION；原event已有权限不重授，账号/角色/ANY/PUBLIC/其他域/CONAUTH_BAK一律不扩。两个GRANT语句及安装声明在执行前冻结SHA；UNKNOWN先核实际对象/原授权，无盲重发。安装态仍禁止任何新旧writer执行恢复API。
4. **安装后认证保护**：先读原六域/15账号完整库存、目录、所有引用和新receipt coverage，生成新安装对象/权限/迁移声明及认证source绑定，再取得新的全源native+文件包。旧b0/266表/map/65 sidecar不冒新表保护；新表存在但漏入dump/map/typed row digest即停止。包有效期由原合法保留依据限定，不为演练延TTL或打开到期包。
5. **新包一次独立恢复**：仅在另有确切批准的真正空目标上按既有流程一次初始化/导入；不对789636实验现场重放。源及目标串行、一在线DM8。完整新package/catalog/raw/所有数据与权限/完整新receipt PK-FK-CHECK、该表两权限及旧674逐项比较，并应用已证而非泛化的grantor规则；任何未知/数据/权限差异仍拒绝。新表或新包实际恢复未证不开放writer、不安装新业务入口。停止恢复目标后返回当次受保护source，保留全部失败与源返回证据。
6. **正式发行与本人入口**：维护态核安装版/新JAR/TLS/真实当前授权及受影响回归准入、写锁释放证明后，才显式恢复获准writer。本人是否准备十分钟交付只在上述工程/发行/保护出口闭合后询问；此前不产secret、不代激活/设密码/授Task或Source Grant。普通身份R/D与当前SOURCES维护身份不混用。父A/B/C仍独立，工程/DM8/专业/业务验收不互代。

新receipt全源恢复只能在维护态安装之后获得，不能把未来I提前盖为D证据；另一方面必须在产品写入前审定完整保护路径和可执行退出，不能用安装后再临时找目标的办法破坏保护。当前第1/2步未齐，因此既有安装D仍阻塞、产品DDL不执行。

### 15.3 非空历史、partial与返回保护

- 无新历史且完整结构时，只有经实际验证、真实历史计数重核及原guard授权的完整U107才可作为候选逆向；任何receipt或REISSUE存在都FORWARD_ONLY，旧JAR/旧包不通过删历史回退。非空时停止相关writer、保留原件，修复前向兼容后另准入。
- DROP完成/ADD未成：operation约束缺失，咨询writer不得启动；仅原ADD在已核无非法operation下为候选前向修复。ADD完成/receipt未成：只能原CREATE恢复确切结构；完整U107缺表不盲执行。DDL、GRANT或响应结果不确定：journal保留UNKNOWN，只读现时核对，已提交不重发；metadata不充分则停住，不以时间猜状态。
- 68的隔离返源合同核的是当次未变source完整保护；它不能验证故意升级后source的合法差异。正式安装必须有单独已审升级声明绑定的返回读核：只允许operation CHECK、新表/原约束和两个GRANT精确变更，原subject/event/其他六域数据和权限保持；当前新profile/原件不覆盖旧profile。不存在可用“允许任意预期差异”口。
- 安装partial或新包恢复失败时不自动用旧包覆盖源、drop新表或启动旧writer。若源对象不满足已审完整合同，保持维护态和相关writer门闭，保留当前产品历史/包/失败journal及锁，报告确切已提交步骤；源回到running只是资源返回，不是升级成功或普通登录可用。

本节是已登记任务的具体设计，不新增产品行为或运行许可。69时scope、实测、封闭安装入口、新对象认证与新空目标恢复准入分别有缺口；70的真实范围批准见第16节，不追改69原件。原维护截止2026-10-13 18:00北京不重问/不延长。

## 16. 70 真实批准消费与入口来源纠正

2026-10-11，责任Codex。评估会话01a1240e-be83-7cc2-86fe-23863cb72550的真实用户消息01a127ff-5b30-7320-aeb3-9b782a7ed48d明确“以后类似这种的一律批准，不要停下来”，紧邻assistant方案msg_0f7138d0b5e49192016acabfade77087d2870e6ed81e6d451a列出已导入789636内ACTR66_EMPTY/RECEIPT/EVENT、fixture及V107/U107限定验证。当前用户进一步明确同资源/用途/费用/窗口/禁界内由开发自主执行，并已转给协调方。确切返回内容/提案范围摘录及工具检索出处见scope-consumption70/provenance70.json；摘录不是完整传输原件，turn时间不是用户消息时间，replyToMessageId仅按相邻上下文导出的关联。

范围结论：三schema QA已获批准，不逐项重问原窗口；不包括额外跨grantor DCL实验、产品GRANT、新账号、模型、部署或C，不改原六schema/失败原件、不重初始化/导入。跨grantor等价是15.2正式安装/整体验收前置，不是唯一计划中隔离QA登记依赖。范围成立不表示物理D、fixture准备、当前保护/锁/writer责任或安装版已通过。

### 16.1 FIX-CON-ACTR66-APPROVAL-ORIGIN-01 窄源码D

依赖已完成ARC-CON-RECOVERY-ENTRY-ADMISSION-D-01与IMP-CON-RECOVERY-ENTRY-ADMISSION-01。68的assertCurrentScope把sourceThreadId硬编码为协调会话，错误拒绝这次核实的评估会话批准。仅增加精确评估会话加精确用户消息ID的已审来源组合；保留原协调来源路径，不开放任意会话。仍核所有原fields、D行path/SHA、提案关联、scope/target/schema/window及import原件；其余资源/当前保护/fixture/返回合同不变。来源完整性组件不推断批准语义。

只改host JS和专测辅助束hash：scratch使用当前JS来源SHA，历史bundle68不动；新bundle70仅改变host JS一项，原17 class、四依赖、fixture/Java/SQL pins不变，不重编译/重包。新增两项scope70组件：已审评估来源组合通过、同会话错误消息ID拒绝。scratch内假设D完成和占位内容只是机械测试，不是真实物理D或DB证据。出口：两项专测/原pin差异及前后治理落证，旧字节可恢复，完成只在docs08登记。

### 16.2 三case实际准入的剩余工程

继续ARC-CON-U107-DM8-VALIDATION-D-01，不再等待范围。实际host activation-validation68安装目录缺失；现67/68四操作仅消费已存在fixture，没有建schema/seed入口。需先确定同批准边界内封闭fixture准备/安装合同并登记窄实现任务，禁止借旧任意SQL/owner-forward或把SYSDBA能力当现时准入。source当前在线、已导入target及其tool停止；在新全源保护含51身份、全writer/host/session/锁归属、一在线库及返源预留证据齐前不启target、不停source。此技术待齐保留物理D未退出，不把范围批准或两组件充真实三case结果。

### 16.3 FIX-CON-ACTR66-FIXTURE-CONTRACT-01 窄源码D

责任Codex，2026-10-11；依赖已完成的IMP-CON-RECOVERY-ENTRY-ADMISSION-01，仅ACTR66隔离工程fixture，不变产品身份/恢复逻辑。直接来源为V032/V046固定原SQL、fixture-contract68中CK_ID2_SUBJECT_ACTIVATION和现FixtureGuard第110行：HUMAN/PENDING真实CHECK要求activation_sha/activation_expires_at非空，guard却要求二者为空，非空R/H不能满足两者。scope-consumption70/diagnosis-final-result70.log用实际H2 JDBC/原DDL和源码谓词复现两个事实，非DM8 metadata/逆迁移证明；首次提取pattern过宽红原件保留。

修复只让既有guard识别符合原CHECK的固定工程输入：issuer/subject/login/kind/PENDING及版本1不变，password_hash/disabled_at仍空；activation_sha必须恰为64个ASCII零，activation_expires_at必须恰为TIMESTAMP '2000-01-01 00:00:00'，同时显式拒绝NULL。这是获准fixture的无已签发token、固定过期、自编标识数据，不生成可用凭据，不恢复真实subject，不登录/调用产品API。不能改CHECK、弱化成任意非空、加载真实identity或外部资料。R/H用途仅证明U107历史计数拒绝，仍不充业务恢复例。

把同一实际过滤谓词以包内静态函数复用给专测，避免测试维护另一份谓词。新H2实库专测至少核旧NULL数据被原CHECK拒绝、精确固定行通过原CHECK且不被guard拒绝、不同摘要/不同时间虽过CHECK仍被guard拒绝；H2不能代真实DM8。原metadata/Session/row计数/非空历史/固定ID及window/源保护/返回合同不变。只编译变更guard及专测，核心67/Entry旧class继承；新bundle71仅guard class与guard source及已完成70 host source变化，旧68/70 bundle不覆盖，source/class生成及原17成员保持分别核hash。专测helper仅在自有scratch更新这次guard source pin，不把旧历史bundle当当前运行包。

出口是源码/实际CHECK与谓词专项/编译及前后治理/原字节齐套，物理D仍未过；完成后回到同一ARC-CON-U107-DM8-VALIDATION-D-01的封闭fixture准备/可信staging/新保护与当前全writer准入，不重问三schema批准、不增加跨grantor依赖。完整schema创建/seed工具尚未实现，本小修不冒fixture已准备或QA已执行。

### 16.4 IMP-CON-ACTR66-ENTRY-STAGING-01 离线受管目录准备D

依赖已完成IMP-CON-RECOVERY-ENTRY-ADMISSION-01；当前原JDBC host bind根内缺失activation-validation68。本节只准现有授权资源的host文件准备，不启动容器、不运行Java/SQL、不用秘密、不改挂载或正常产品部署。选择71已编译且四项实际CHECK/谓词通过的固定bundle；依bundle71逐项核现时source SHA及17 class SHA，16旧class从67/classes04与68/classes02/03中按名称和SHA取唯一一致原件，新guard仅71/classes71。不得含Test class、新JAR/任意脚本或不同SQL。

目标唯一是approved-execution/jdbc-native05/activation-validation68，创建前必须确认不存在且父路径每级为workspace内真实非symlink目录；所有文件以独占新建写入，禁止覆盖已存在/未知路径。目录形状只有classes内原17 class、fixture-contract68.json、db/dm8内原V107/U107。完成后对精确成员/类型/bytes及SHA核对，写目录证明；若失败保留partial与原件，不删、不盲重跑。不把host成功复制当实际容器版本/JDBC能力/物理D：tool停止时尚无容器find/hash实测，68物理调用前仍必须核原mount和现时完整classProof。原来源/SQL/fixture/历史bundle不改，当前源库及writer全不动。本任务只是缩小已批准QA的真实工程缺口，schema创建/seed/源保护/物理三case均未执行。

70/71有限实际出口：真实批准来源两项专测通过；实际H2原CHECK/guard谓词4项通过，Java21仅guard+专测编译成功（PATH Java17版本误用红保留）；bundle71只改guard/source对应叶，其余16class不变。staging实际独占创建20文件/85606字节并逐项重核，source PID60521及目标/tool停状态未变。scope-consumption70/verify-after70.log完整exit1：1983/1981通过/0失败/2跳过、后续179/179通过，47/41一致，仅原两project健康红；该运行中推进了71独立编译/专测/staging，不声称它是所有当前源码从头同字节回归。有限工程证据见fixture-contract71/finite-exit71.json；记录后最终verify-after-recording71.log再完整复核现时源码/台账，原红不擦。三caseDM8/安装/本人/专业验收0，当前物理D仍未退出。

### 16.5 ARC-CON-ACTR66-FIXTURE-PREP-D-01 接续具体审查（历史输入，冻结结论见16.6）

依赖71 fixture修复与68入口源码均已完成；唯一计划新登记本源码D→IMP-CON-ACTR66-FIXTURE-PREP-01，不另造台账或通用诊断平台。本轮已从原V032/V046/V107审查到如下固定输入：三个SYSDBA-owned ACTR66 schema；仅原V032首两张subject/event表以及V046前四条subject和原event CHECK改到OLD5的两条ALTER，不执行key/Grant/workload或其他原SQL。R/H subject须符合16.3固定行；R only一个receipt、H only一个REISSUE，数据标识/版本/时间来自已批准自编工程fixture，不取51真身份或其秘密。seed须在NEW6/receipt完整时分别只插该case一条历史，不提前绕OLD5 CHECK插REISSUE。E保持无行。

当前四操作的FixtureGuard要求subject/event已存在，所以不能把准备伪装为V_DROP_OLD，不能用旧owner-forward或在host任意拼SQL执行。待冻出口是新增封闭PREPARE/两SEED请求的确切字段、原DDL有序prefix metadata/当前空schema归属、现时单session与每步host RPC/持久journal、单事务seed提交UNKNOWN、原六域不变/资源预算/一在线库及完整返源保留锁，以及新class/SQL/source集合和实际受管目录版本绑定。特别核68每entry仅可用一次且只能经完整源返回释放lease，不能假设现工具允许在同一lease连续复用多个操作。

这些是技术设计的已定位输入及待闭合边界，不是桥已实现或physical D通过；本节未冻结前不写准备桥、不启动target或SQL。批准已明确，缺口由Codex自主完成，不向人类重复索取技术ID/同scope许可。跨grantor不接到此隔离准备D的前置；正式安装/新保护独立恢复仍遵15.2。

### 16.6 ARC-CON-ACTR66-FIXTURE-PREP-D-01 有限源码D冻结（2026-10-11）

责任Codex，技术推导而非新业务/人类授权。来源16.1已核批准、16.3合法过期行、原V032/V046/V107/U107及entry-admission68/fixture-contract68.json原生元数据。只准受控QA桥源码，不改业务SQL/接口/身份/GRANT/产品状态/旧六schema或已安装文件，不授物理执行权。结构核验与语义复核分别取证。

**请求和序列。** START严格type/runId/schema/operation/previousOutcome五字段，后者只能CLEAR。policy固定ACTR66_TRUSTED_FIXTURE_POLICY/v1；CASE_EMPTY/CASE_RECEIPT/CASE_EVENT仅绑定各同名ACTR66 schema，不接SQL/步骤/行/URL/恢复策略。每case全新run、同run保护包、租约、一次JDBC进程；PREPARE九DDL→R/H subject INSERT+独立COMMIT→原V107三步→R/H一条history INSERT+独立COMMIT→原完整U107→终态只读复核。E零行。完整返源释放后才下个case新准入，不能复用单次入口/保护/连接。

**SQL与prefix。** 1仅白名单CREATE SCHEMA AUTHORIZATION SYSDBA；2/3逐字取V032前两CREATE；4～9逐字取V046前六ALTER（kind、drop/add activation、kind CHECK、drop OLD3/add OLD5）。整文件SHA分别7a025bd04bdf8b73a22e503148fc38e901b9ca1ed33bf412363992417c26aded、da746a24b53fb458523cef825122d643212b711c8f0163d7f744d88a7d6400d4。提取器仅识别已pin有限语法的分号/固定布局，不是通用SQL解析器。fixture-plan73.json保存原SQL/source pins、9动作及SHA、prefix0～9、seed和case完整journal顺序；host/Java硬pin生成物SHA，不自由传计划。旧bundle/20文件不覆盖/re-pin，新独占目录仅/jdbc/activation-validation73，不改原RO bind。

prefix0选定QA名及同名对象不存在；1空schema；2仅subject原10列；3另有event；4起subject11列；5仅activation CHECK缺失；6新activation但kind CHECK缺失；7 kind完整；8仅operation缺失；9 OLD5。每步前后同session核表/列/默认值/顺序、PK/FK动作/列、index shape、确切CHECK名/状态/表达式/类型计数、零行/无额外对象。10列取native合同前10列，其他PK/FK/index复用；旧activation/OLD3只接原pin表达式及有限NOT(IS NULL)原生渲染候选，不宽松语义等价。未知渲染拒绝保留，不自动修正。其他QA名不写，已完成case保留。

**归属和连接。** installed-dm8-admin46.pdf SHA d7d6ad7f0e9b2b0d3d249aa7ac61cfd66f5cf09e20324acbc599325c4deb9a6c，596～597页定义SYSOBJECTS NAME/ID/SCHID/TYPE$=UR或SCH/SUBTYPE$=USER/PID父ID；installed-schema-owner-dictionary73.json仅查询依据，手册不单独证明具体schema→user关联。首次DDL前校准SYSDBA默认schema与UR/USER SYSDBA父关联恰1行，否则CREATE前fail closed；随后同法要求新schema owner SYSDBA，无猜测/fallback。逐次live核SYSDBA/DM8.1.4.170/确切target URL、V$DM_INI COMPATIBLE_MODE=0、V$SESSIONS=1及当前schema；host实际资源/进程责任/锁/保护/窗口守卫不可省。

**固定seed事务。** subject只16.3固定行：issuer urn:actr66:fixture，身份/actor/request/event全actr66-fixture，HUMAN/PENDING/version1，password/disabled NULL，activation SHA64个ASCII零，expires/created均TIMESTAMP '2000-01-01 00:00:00'；R仅receipt expected1/result2且两时间同固定时刻；H仅REISSUE before1/after2且occurred同固定时刻，不改subject冒真实恢复身份。INSERT与JDBC COMMIT独立journal，各先PREPARED fsync ACK再动作；COMMIT SHA绑定固定动作描述，不伪称SQL。只在commit尚未尝试时rollback；尝试后异常/ACK失败UNKNOWN，不猜rollback/重连/重试/擦除，仅留现场返源。前后核唯一完整行含时间，不拿现Guard不完整时间谓词代替。

**日志和终态。** DDL PREPARED/COMPLETED/UNKNOWN；REFUSED仅实际尝试前拒绝或原U_GUARD vendor -20001；fsync失败无成功ACK。持久保存sanitize ERROR确切code/outcome/sqlState/vendorCode/automaticRetryAllowed=false再fail close，不接任意字段/类型。Step.Result新增vendorCode/sqlState、保留旧两参数构造和旧Entry RESULT原字段，新桥消费真实返回。R/H必须12DDL/2seed事务/最后U_GUARD REFUSED、vendor=-20001、原block SHA及SQLSTATE；E必须15DDL/0seed/U四步完成/code0/state NONE。E最终OLD5/无receipt/零行，R/H最终NEW6/完整receipt结构、固定subject/selected history1。权限/语法异常不冒守卫通过。

**出口与边界。** 扩现host admission/lease/返源，不复制绕过；旧policy保留四操作/17class/root68行为。新精确source/class/dependency/plan/SQL集合编译后记录，不预估class数通过。Java480秒+5秒kill、host490秒；新case开始须离截止30分钟、逐步仍留20分钟返源；旧120/125秒不变，256RPC/256KiB不扩。失败留租约，只有原完整source inventory前后+真实TLS GET返源生产者释放，不凭child成功/陈旧证明释放。

本有限源码D技术阻断已有明确fail-closed答案，允许IMP实现；桥完整出口须case/schema/request/plan/prefix/owner/mode/session、INSERT/COMMIT前后journal故障/UNKNOWN、vendor/旧profile边界专测、JDK21编译、源包SHA和前后治理。代理协议非DM8，H2 CHECK/seed非物理metadata。物理D仍阻塞：实际容器版本、最新全源含51保护、全writer/current host锁责任、旧六原生前后对账、一在线DM8及返源输入齐套另过门，不把本D/编译当三case执行。

73有限源码证据：fixture-plan73.json独占生成62583字节，SHA d1faa9f7d55d3a7cd75cb730475c82e5cbda7f7d0f650cb41ca666bc0957754e，27项plan/prefix描述/有序journal/结果/原pin漂移正反通过；这不是native prefix运行核验。固定FixtureSeed无main/任意SQL/秘密或默认guard，只接受硬pin机器计划、封闭Kind及case绑定、确切连接/sole session/mode、固定唯一行，INSERT和JDBC commit前均需guard/journal，commit尝试后异常留UNKNOWN且不rollback猜测。17项实际H2事务与显式DM元数据/host代理专项通过，含COMMIT调用前后异常、前/后fsync、commit前guard丢失、固定subject时间漂移和重复seed；不是DM8/session/host实测。JDK21仅编译该2个production class及专测；尚无完整transitive运行包、FixturePrefix/新case main/共享host profile/raw guard vendor接线。原71所有6 source pins/17class及旧20安装文件逐项SHA不变，无staging/SQL/secret/源目标启停。本有限组件不退出IMP；后继具体源码动作与状态只见docs08。记录helper最初假定verify-all为LF而实际CRLF，纠正仅原记录helper、红保留record-source-first73.log；原verify-all注册前字节采用精确逆插入重建，明确不是事前原件捕获。有限receipt见fixture-preparation73/source-component-receipt73.json，所有修改后治理见verify-after-source73.log，不预报其绿色。

**74完整源码接线与独立暂存证据（2026-10-11，责任Codex）。** 同一ADR16.6实现已包含native prefix0～9、SYSDBA默认schema归属关联校准、固定三case Java main、共享host fixture profile、真实guard vendor/SQLSTATE透传及原完整返源producer。每case一次START/run/lease/JDBC，重复并发run及运行中release均拒绝；UNKNOWN停止后继，持久sanitize ERROR，最终CURRENT_STEP核验后才受理RESULT。旧四操作/17class/root68 profile保留，新增profile明确22class/13source/4dependency/固定plan pins，真实计划物理D必须同时绑定scope和确切bundle引用，尚未放行。原seed/plan字节不重生成，原V/U语义不改。

实际编译及证据：[更正后的完整收据](../evidence/activation-recovery-design-20261010/fixture-integration74/source-integration-receipt74-02.json)、[bundle74](../evidence/activation-recovery-design-20261010/fixture-integration74/bundle74.json) SHA 7022ae8ae3333a50be4c830aced267395b1ea7e3d310a47643024b0f27c0a29e、[JDK21编译原件](../evidence/activation-recovery-design-20261010/fixture-integration74/compile-bundle74.json)。production全部22class同时编译、13source前后SHA相同，测试class不入包；4dependency沿原71严格匹配。

受影响验证：完整Node host127/127（新增fixture29及旧98）、plan/journal27/27、Java完整三case与prefix/owner/ACK/final反例40项、旧Step31/Entry38及exit68新增11、实际H2 seed事务17/fixture谓词4通过。协议与native catalog均显式component model，H2只证明对应事务/谓词，DM8执行仍0。三次编译/模型测试红和修正前源保留；收据第一版把ContractTest写成CheckTest的记录错误已由74-02注明更正，原收据及实际日志不覆盖，不冒真实SQL通过。

[Host精确暂存](../evidence/activation-recovery-design-20261010/fixture-integration74/staging-receipt74.json)：批准的原RO bind host子目录activation-validation73独占新建，22class+原fixture+固定plan+V/U共26文件/190806字节，所有成员/类型/hash核验；旧activation-validation68原20文件前后SHA完全相同。只读states前后source PID60521/StartedAt不变，target/tool停止。不容器exec、不secret/SQL/启停/DCL/账号/模型/产品部署，host暂存不代container classProof或物理D。

[物理D只读接续观察](../evidence/activation-recovery-design-20261010/fixture-integration74/admission-readonly74.json)：9确切资源状态/限额、全在线ID、37候选host进程的PID/parent/create/name/命令SHA及两指定维护锁已捕获；锁均不存在，未创建真实lease，host角色尚未绑定fresh case责任原件。旧56b保护回执创建于2026-10-10T11:11:50.662Z且至原维护截止有效，只读回执不证明当前51身份覆盖或same-run保护，不打开认证包/secret、不拿旧包放行。下一准入仍需同case新全源保护含51、完整writer/host/session责任、实际target/tool版本和container classProof、旧六native前后对账、一在线DM8与逐case完整返源。跨grantor不作为QA阻断，也不把本源码退出当安装/恢复验收。

74源码后置完整治理实际结束：[verify-after-source74.log](../evidence/activation-recovery-design-20261010/fixture-integration74/verify-after-source74.log) exit1，主测试2012/2010通过/0失败/2跳过、plan27/27、质量179/179，47源文件/41需求行基线一致；仅继承TESTS_FAILING:project和TEST_EVIDENCE_STALE:project，不称全绿。另[目录级再核](../evidence/activation-recovery-design-20261010/fixture-integration74/staging-member-proof74.json)证明新26/旧20文件及各自3目录精确、无额外成员或祖先跳转，不写installed目录。依此完成有限IMP源码出口，物理D/安装及产品验收仍分门；状态记账后再运行完整治理，实际原件见verify-after-recording74.log，未预报其结论。

74接续物理D来源流程核对（派生技术结论，不是新物理准入）：[protection-flow-review74.json](../evidence/activation-recovery-design-20261010/fixture-integration74/protection-flow-review74.json)记录原56b三源码SHA与三已存在输出。execute-source-protection56.ps1遇既有events拒绝，finally逐项恢复五writer且报告sourceDatabaseStopped=false；不能原样重放、覆盖旧56b，不能把保护后writer恢复且可能再写的历史回执当当前case连续same-run保护。下一本D设计出口必须绑定新owned run/approval/package/host责任/输出路径，固定完整源捕获后持续fence且不恢复writer→停source→唯一目标/tool准入→一case→目标/tool停→原source/五writer返回→实际全源inventory前后及严格TLS→原owner release。捕获/转场/SQL/返源任一步未知都留原件、不发后继case、不猜重试。原55范围/截止/资源与20分钟返源、30分钟case开始余量不扩；只复用原机制，不执行旧脚本、不新账号/GRANT/初始化/导入、不写旧六schema。当前缺实际run绑定和责任/保护事实，本段只是来源核对与阻断出口，物理D仍未过。

### 16.7 ARC-CON-ACTR66-CONTINUOUS-FENCE-D-01 有限生产接线D（2026-10-11）

责任Codex，实施IMP-CON-ACTR66-CONTINUOUS-FENCE-01；接收instruction ARC-CON-U107-DM8-VALIDATION-D-01/continuous-fence-and-return-admission/01。依据55原维护批准、70确切ACTR66批准及74完整桥/流程原件，不重问范围、不重做74、不将本有限源码D当物理父D或产品D。75前置完整治理exit1仅原TESTS_FAILING:project/TEST_EVIDENCE_STALE:project，47/41一致；主2012/2010通过/0失败/2跳过、plan27/27、质量179/179，原件continuous-fence75/verify-before75.log。

**封闭输入与所有权。** 每次仅CASE_EMPTY/CASE_RECEIPT/CASE_EVENT之一，首选EMPTY；由固定入口生成fresh UUID及continuous-fence75/run-UUID独占目录，run/approval/package/host责任/返源计划全绑定。审批只派生原55/70既有来源与用途，不伪造新HUMAN_USER审批；source六schema/15账号/current51纳入同一完整捕获，不排除行或复活旧56b包。源native模板56b（SHA6170eab0136c1d9d3f6196e6684937a2d8ff6040a56fc2e9c5e1127fa4230c15）、原adapter/fence/恢复profile与origin pins保持，fresh审批记录runId、新责任/输出引用及其重新计算的配置摘要。Scope/bundle74/物理父D均由现行唯一计划核，缺门即拒绝；完整74 policy仅在fresh认证保护生成后创建，不把null/占位或声明布尔值当保护。

全链另持fixed source-target controller wx/fsync锁，原source46 native锁只由原adapter持有和释放，原entry68 target lease仍由74创建和真实完整返源producer释放；不偷锁或删除失败锁。controller锁成功完整返回才释放，失败保留controller锁/原件及禁止后继；原target lease只有原owner且原完整库存/TLS producer实际证明返回后才可释放，释放不把SQL失败改成功。owned run append-only事件不是新的任务状态台账。每个副作用先PREPARED fsync，终态COMPLETED或UNKNOWN fsync；落盘失败不执行。新调用不得重用run、覆盖历史输出或重发未知动作。

**固定生产顺序。** 只读初始9资源/全部在线ID/network/host/锁/manifest审查→controller锁→停原两publisher和三application writer（每个一次stop，inspect实际PID0）→fresh full source inventory和SOURCE_IDLE探针→复用sealSnapshot及原实际SHARE围栏/native export，SOURCE_HELD探针和全源前后inventory，保持五writer停止→认证包/原origin/catalog和现时51数据摘要绑定、fence进程及source native锁已实际退出→停sourceTool及source并核PID0→原IMPORTED target existing-only启动/本次StartedAt后READY及tool启动→TARGET_IMPORTED探针、原六/15完整前置库存→74固定case一次→旧六/15完整后置库存严格同目标对账→停止target/tool核PID0→原source/sourceTool及五writer按原sequence返回同image/config/mount/网络/IP和本次ready→原完整全源库存前后、严格TLS三GET→原target lease及controller success释放。无dminit/bootstrap/import/GRANT/产品部署或C。

源seal释放native SHARE之后到source停的空隙仍由五writer持续停止、全host责任/会话核验和controller独占维持，不提前返写。source snapshot确认不等于目标旧六等价：QA只比较同一已导入target旧六的schemas/accounts/restoreCatalog/nativePrivileges前后全量摘要和原始输出；不使用跨grantor归一化、不把源与目标674差异引入QA依赖，也不宽放旧六字段。新ACTR66对象不纳入旧六scope，更不删除验证现场。

**只读原生探针。** 新单类ConsultationActivationRecoveryAdmissionProbe75，仅零argv和封闭SOURCE_IDLE/SOURCE_HELD/TARGET_IMPORTED phase；SYSDBA、两确切既有URL、DM8.1.4.170、SYSDBA当前schema、autocommit、COMPATIBLE_MODE0、SYSDBA默认schema owner关联校准均须匹配。仅固定SELECT，读取V\$SESSIONS的SESS_ID/USER_NAME而不读SQL_TEXT；ID以字符串保存，不损失BIGINT。IDLE和TARGET恰1会话，HELD恰2且原lease前后HELD证明仍有效；全部USER_NAME=SYSDBA、session ID独一。export期间会话由单次封闭原native调用及持有的真实SHARE责任约束，不猜其内部session数量。字段来源installed-admin-sessions-page-725/726.txt（原安装手册V\$SESSIONS定义），mode/owner来源16.6已核字典；具体归属未知即refuse，不把手册当运行证明。probe独立JDK21编译，唯一新basename在原/jdbc RO bind根wx暂存及pin，不改74目录26成员或旧20文件，不开通任意SQL。

**host与会话归属。** fresh责任必须每个PID/parent/create/命令SHA和有证role一致，unknown/不可读一律拒绝；不凭进程名、父PID、同库用途或声明verified放行。持久source fence是已封闭原producer的维护连接，探针/完整inventory串行，退出后sourceTool top须仅sleep；case只能原74追踪的单Docker/JVM。实际当前PID33744 powershell（2026-10-09创建）CommandLine不可读，未拥有可核NON_DB_PROCESS/CONTROLLER_LAUNCHER证据；该实时准入仍阻塞，有限源码可继续，未齐不取secret/lease/启停。不得杀该未知进程或用推测记录替换。

**预算、秘密和失败。** 固定9ID/原镜像/volume/RO mount/alias/no-restart，一在线DM8、≤7GiB/4CPU；开始pipeline保守留60分钟，整个受控工作窗≤40分钟且至少原20分钟返源余量，74 case仍30分钟开始余量/480+5秒及host490秒不扩。SourceStage不取secret；真正运行仅在现行scope/D/host/锁/资源/源用途门全部过后由原受管引用在专属worker内存消费现时凭据/key，不输出、落盘、argv传递、新建或复活secret；snapshot32字节key的base64/hex仅内存转换使原seal/74协议一致，finally清零/恢复环境。未知stop/start只inspect确认，不再发相同行为；若target停止不能实际确认，绝不启source造两在线库。捕获、启动、case或返源失败均保现场，不执行下一case；已停的原资源只在目标PID0/自有worker退出后返回。全源或TLS不能证实则留controller/未释放的target lease，返回层与SQL层分开记录，不凭source running宣布返回通过。

有限源码D已对技术选择/失效关闭给出明确答案，允许上述小范围接线和专测；物理父D仍须完整可执行入口/构建pins/必要治理及现时责任输入具证。当前未知host责任不由本D豁免；新包必须在真实维护流程中生成，规划与组件不能冒当前保护。成功、UNKNOWN、源捕获失败、stop/start未知、target旧六变化、返源库存/TLS漂移、journal fsync失败、并发/重用run和非白名单case/URL均测试；组件与真实运行分层。

### 16.12 ACTR78 持有worker、逐角色返回及失败锁核销详细D（2026-10-11）

派生技术责任Codex，设计任务ARC-CON-ACTR77-FAILURE-RETURN-D-01；不新增业务身份、数据权利、网络配置或C许可。前置完整治理见continuous-fence75/verify-before-failure-design78.log：47文件/41契约一致，检查执行到底，exit1仅TESTS_FAILING:project和TEST_EVIDENCE_STALE:project；不声称全仓绿。事实依据是run7fec的原physical-execution76.log/events.jsonl、recovery77-exit.json及下列现时接口。PID41388没有现场归属记录，设计解决可证的接口缺口，不追认该历史PID属于围栏。

**接口事实与修复范围。** single-database-native-fence-container.mjs的persistentFenceProtocol实际以shell:false创建唯一ChildProcess，却只返回acquire/assertHeld/release/abort；single-database-lifecycle-native.mjs的lease不暴露其身份，且release的finally即解本进程native锁。continuous-runtime的facts只接受静态host责任；其returnWriter对每个网络要求相同IPAddress。continuous-fence的整个返回循环共用一个try，因此某角色返回失败阻止后继角色。两个Java publisher的validateAddresses固定目标ID/DNS/内部IP，publisherInterface要求各一个172.17和172.25地址，从现时接口选监听地址，不固定bridge单IP；该事实不允许泛化任何应用网络例外。

**可信worker能力。** 仅从封闭原native invocation实际spawn返回对象构造不可由请求/责任JSON伪造的进程能力，记录spawn前后时间、PID、父进程及冻结command/args摘要；不包含环境或秘密。首个现时CIM事实绑定必须PID相同、父为创建者、create处于spawn窗口内、完整可读命令满足封闭调用的精确命令构造；绑定后PID/parent/create/commandSHA每次严格相同。不能仅因相邻时序、同父或进程名认领。能力只在本run的source fence实际HELD、原native锁和lease仍有效时传给continuous host检查；unknown、不可读、PID复用、错误命令、错误源、已释放/退出或多个候选均拒绝。普通host责任及共享entry默认守卫不变；不得开放通用childPid或任意子孙豁免。先完成能力绑定再调用SOURCE_HELD及持有期间库存；协议状态与身份证据分别保留。

**退出与native锁。** RELEASED应答只证明数据库协议，kill调用只证明请求；两者均不能证明进程退出。新增有限waitClosed，由实际close事件证明，最长沿原15000ms协议上限，不循环重启进程。release/abort及acquire失败清理必须等待真实close，超时/错误保留native锁与原失败证据，不允许source停机/转场或控制锁释放；避免finally无条件unlock。active失效与退出责任分开，失效后不能继续inventory/capture。旧调用者也获得真实退出保证，测试假进程必须显式模拟close而非凭kill计数宣称结束。stdout end、close、重复应答、release应答后仍活、退出超时、spawn失败及途中assert失败均测试。

**返回网络合同。** 三应用和所有本项目网络仍按原IP/网络ID固定；仅原咨询679和裁决1bf发布器的原默认bridge（ID3fc351592a63a334572db0a34af42b586da11586350ffc4e6450835bc956c5bb）允许Docker自动重分配IPv4及伴生endpoint/MAC。每次核原名称、驱动、scope、internal、IPAM、subnet172.17.0.0/16、gateway、IPv6设置及原配置；地址必须是合法非网络/广播/网关单播IPv4且不重复。本项目网络的ID/IP/目标DNS/alias/IPAM约束不变，原image/config/mount/ports/转发0/固定目标绑定仍严格核。记录旧新地址和当前network inspect，不disconnect/connect或修改网络。未知网络、第三角色bridge漂移、跨子网、gateway/IPAM/ports/target漂移均拒绝。consultation严格CA/SAN/server-pin三GET仅证明咨询合同，不冒三域HTTP验收；裁决发布器当前启动与原固定配置另核，不伪造裁决TLS结果。

**逐角色失败返回。** 目标/tool停止且source/sourceTool可证安全后，每个原被停角色分别在原PREPARED/COMPLETED/UNKNOWN落盘协议下最多尝试一次；一个角色错误须保留并尝试其余可安全返回角色。每次前重新核target/tool PID0、source身份与运行、worker已退出及全局责任/资源/预算。全局安全事实未知时不启动任何后继；journal不可写时不执行副作用。已UNKNOWN的start不重发，仅inspect。汇总全部角色错误；有任何返回错误不释放controller或target lease，也不宣布sourceReturned。case错误与return错误分别保留，原失败case永不因此改成功；完整库存/TLS即使成功仍不能掩盖某角色未知。

**旧失败场景独立核销。** REC-CON-ACTR78-FAILED-CONTROLLER-01只处理run7fec source-only失败场景，依赖本设计及修复完整专项/版本化pins。执行前现时scope/窗口、fresh host责任、固定9资源、7在线原角色/target与tool PID0、source PID60521和StartedAt不变、sourceTool仅sleep、native锁缺失均须证明。原控制marker必须逐字节为`7fec27da-6018-49ed-934c-08c67979d144 44916 1f50d164-2cbe-40db-8320-a38fecc506fb`，SHA d1482d5908144b2817f7796910f92fc32eda02443925ebd32febabfb2e3f18f0；原owner44916与未知41388均现时不存在。不凭旧记录宣称死亡。前述门齐后仅用原受管秘密内存消费和固定inventory SELECT读取源两次，中间执行原咨询严格TLS；原source-baseline.json SHA88c90427ae8714315d19ab06282cd5fdb3f4eab8e19b8bad276631c9e4f9a625，完整schemas/accounts/restoreCatalog/nativePrivileges逐项及摘要对比，无grantor归一化、不屏蔽任何差异。保留两次原输出与比较/TLS和当前资源责任证明；不取snapshot key、不导出、不停writer、不启target、不写SQL。

核销另持本机独占reconciliation wx/fsync锁，所有核销调用先取得它，现行case仍因旧controller存在拒绝。归档旧锁原字节及绑定证明采用独占新建/fsync，不覆盖原run文件/events。最终在单一主执行者下再次核实际死owner、marker/SHA、文件身份/类型和全部安全门；仅从原活动锁路径退休这个已核对象，留归档及失败结果，不称原controller正常release或原case成功。文件身份变化、归档失败、读核/TLS差异、未知活owner/新进程、锁状态变化均保留活动锁并退出；不能提供受控排他和文件身份核验的平台不得退休。核销失败不自动再试，先只读核现时结果。禁止通用stale-lock删除入口；原target lease若存在即拒绝本source-only流程，不借此释放它。

**实施与退出。** 先登记FIX-CON-ACTR78-WORKER-RETURN-01，再改上述四个模块及其聚焦测试；版本化manifest75-06与新构建receipt，纳入两个shared native模块和新增能力/测试的实际pins，计数按实际测试输出核验，05和全部失败原件不覆盖。专项至少覆盖身份伪造/不可读/重用/退出、窄bridge正反、首/中/末角色错误仍有独立安全返回、全局unsafe禁止返回、journal失败及所有锁核销拒绝场景。结构验证、组件测试、真实源核销和新QA分别判定。详细工程D允许编码，不证明已有保护包、源已完整返回、物理case通过或父A/B/C完成；真实核销还须可执行工具/专项/现时准入齐备，不立即删锁或重试7fec。

79工程构建复核补充：首个06候选（Node401/401、Java12/12）原件保留。真实continuous入口还须从唯一计划解析FIX-CON-ACTR78-WORKER-RETURN-01及REC-CON-ACTR78-FAILED-CONTROLLER-01两行均确切已完成，缺行/重行/相似ID/其他状态在秘密、控制锁和启停之前拒绝，不能只凭旧物理D与新包绕过失败核销。该补门产生新源码，现行构建采用07与新收据，不覆盖06；计数由实际测试总结校准，旧05及失败run不变。本补充仅强化已登记依赖的机械消费，不新增运行批准。

82核销入口派生工程纠正（同REC任务，不新增产品许可）：81实际首次完整source raw SHA与原baseline相同，但公开CA因Codex Windows包缓存映射被新入口误拒绝；复用既有最终部署D6.16.1及Get-FinalPhysicalWindowsReference的确切logical/root/physical绑定与原CA SHA，不接受任意重定向。81失败run5f2f3828-04fc-4bd4-848d-5134fef8c3c8、owner39324、failure SHA625444866a84795d7ed7b4c9a8a326bafc394a64b4e87e2ddc37893ca6c399a8，原controller逐字节/文件身份未变，无第二inventory/TLS/归档/退休动作。其自身互斥原件SHA8b6cdb19f83f4e1f1b78c0e8ec703cad27295228a1aa6c6d7fbdaaf55240d37a、dev3838357650/ino1688849861848710/birth与ctime1791711470808749300/117字节已只读核明。仅在新run全部现时source-only门齐、39324确切不存在、原failure/source raw pins与上述无后继输出现时成立后，独占归档并退休这一自身互斥到原81失败目录；非通用stale-lock入口，不动原controller/target lease。未来本入口在退休原controller尚未尝试的失败返回中，只归档自身仍持有且逐字节/文件身份匹配的互斥，原controller必须未变；原controller退休已尝试或身份不明时保留自身互斥现场。原81包/失败/第一次raw保留，82另包/专项/前后治理与新run审查，不自动重试81或7fec。

### 16.11 ACTR77 Windows测试互斥命名隔离（2026-10-11）

派生工程责任Codex，任务FIX-ENG-ADJ-TLS-TEST-MUTEX-ISOLATION-01，仅共享维护能力验证，不新增产品或业务合同。verify-after-failure-return77.log的2013主测试中出现一项新失败：41 preflight mock返回REFUSED而非OK，治理停止，后继检查未执行，不能并入原两健康红。独立同名测试通过；受控持有Local\\AgentPlatform-Final-Recovery-local-publish-test-adjudication时，同一41测试确定exit1，释放测试锁后独立通过。41/42两个测试文件的mock deployment.manifestSha256均为local-publish-test，两个真实guard都以该manifest+adjudication组成同一Windows命名mutex；verify-all的文件并行度2会使无关联fixture相互排斥。

仅将41与42 fixture的manifest标识分别限定为文件角色加当前Node测试进程PID；同一fixture过程内名称保持稳定，两个文件和两次并行执行不共锁。生产guard、真实manifest、mutex名字算法、WaitOne(0)、未知拒绝、原资源/发行SHA及治理并行度均不改。测试断言附上mock拒绝码方便诊断，不输出真实秘密。先保存碰撞原件，修正后在旧固定测试锁被持有时验证两个新fixture均通过，再完整运行两文件及后置verify-all；生产源码SHA前后严等。任务退出仅测试fixture碰撞修复与正反专项，不冒物理case修复；整体治理是否执行完及旧健康红另外如实记录。

### 16.10 ACTR77 失败现场剩余角色安全返回（2026-10-11）

派生技术责任Codex，任务REC-CON-ACTR77-SAFE-RETURN-01，沿原55/70/16.7的失败返回授权，不批准新case、网络配置、DB启动、重导入或释放失败锁。run7fec27da实际在FRESH_SOURCE_CAPTURE以CONTINUOUS_UNREGISTERED_HOST_41388停止；SOURCE_IDLE和SOURCE_HELD探针已完成，但未形成保护包，未停source/tool、未启target/tool或执行fixture。返回咨询应用成功后，咨询publisher已实际启动但默认bridge地址由原172.17.0.3变成172.17.0.2，严格IP返回门拒绝并终止后续返回；不能因此称SQL成功或完整返源。

只读现场确认source PID60521/StartedAt未变，target/tool均PID0，sourceTool top仅sleep，原source native锁不存在，PID41388已不存在；尚停止的裁决应用4f、裁决publisher1bf和执法应用e0e三角色，其返回动作尚未尝试。依据现有失败返回义务，按原顺序仅对这三个精确ID分别start一次；每步核原image/config/mount/network成员/预算、实际Running/PID/就绪，未知不重发。原咨询两角色不再次start/stop；不disconnect/connect或修改任何网络，不接触entservice或旧库，不读秘密、不SQL、不删锁。原始结果/events与控制锁保留；另存只读实际metadata及严格公共CA/SAN/server-pin TLS结果。服务恢复与完整库存/全部IP/原控制锁释放分开判定；剩余角色或TLS失败具证保留，不能把running当全部返回证明。此安全返回D只处理本次停服余项；host持有worker归属、默认bridge动态地址合同和锁恢复由下一工程D核证，不豁免未知进程。

### 16.9 ACTR76 外项目隔离与持续绑定限定D（2026-10-11）

本项为派生技术决策，责任Codex，任务FIX-CON-ACTR76-PROJECT-RESOURCE-ISOLATION-01。用户明确entservice-dm8属于其他项目、不得动；agent-platform-dm8只在确认为旧库后停止。实际旧库3fd7f963的5237端口、独立卷与当前f3dfa7aa/5496单库不同，停止结果exit137，原容器/卷保留，不冒正常数据库关闭。此指令不批准切换、删除、复活秘密或业务权限。

原readEntryFacts将全Docker主机ps当本项目ownedRunning，导致独立项目被要求停机。共享entry默认事实采集与守卫继续失效关闭；只在continuous生产适配器中使用受限采集器，并将同一采集器注入case与返源。例外只认完整ID35d790643744cb8405a6755b348f6fd0ab2ab9b2e8937421ae6288cd655e3395、名称/entservice-dm8、镜像6409a1d5、空labels、唯一bridge网络3fc35159、唯一独立bind D:/docker/dm8/entservice-data到/opt/dmdbms/data及唯一5236端口绑定。每次全主机running ID核对并只读inspect选定非秘密字段；缺失、重复、其他未知资源或任一例外字段漂移均拒绝，不按名称/标签泛过滤，不观察其SQL或解密其配置。外项目容器不进入本项目预算、启停、SQL、锁、恢复和验收范围；本项目固定9资源/精确网络成员/一在线DM8/7GiB与4CPU及全host责任仍严格核验。独立bridge不等于证明无法通过主机端口访问本项目，host责任与实际会话探针仍不可省略。

持续绑定中Mounts和HostConfig.Mounts属于无序挂载集合，应规范化排序再比较全字段；其余运行字段不放宽。测试覆盖精确外项目/无外项目、错误ID/名称/镜像/标签/挂载/网络/端口/重复/未知及正常重排与真实变化；新采集器和专测纳入版本化manifest，不覆盖75旧包。当前资源基线必须按manifest SHA读取并在每次pins复核，不能只记录但不验证。记录外项目观察和旧库停止事实，明确源码测试、治理和物理case分层；旧控制器已不存在，必须fresh run/责任，不凭旧进程证据准入。本D仅退出工程隔离修复，不宣布物理验证或单库A/B/C完成。

### 16.8 ACTR75 主计划 manifest 链接准入修正（2026-10-11）

fresh `CASE_EMPTY` run `a051ad4b-3882-4c40-bf15-e76f5b6c35b0` 在 PID33744 已安全退出、全部现时 host 命令摘要可读且责任绑定后，以 `CONTINUOUS_CURRENT_SOURCE_MANIFEST_GATE_REQUIRED` 在秘密、lease、启停和 SQL 之前拒绝。原因不是 manifest 或批准缺失：主计划任务 `IMP-CON-ACTR66-CONTINUOUS-FENCE-01` 已完成，并以 docs 文件内合法 Markdown 目标 `evidence/activation-recovery-design-20261010/continuous-fence75/manifest75.json` 绑定 SHA `5ef7e74521c0f76494c61edd554dd23b89dfb478d4466cb1f8fbc35f280d6e4c`；原运行门却要求正文出现工作区相对字面量 `docs/evidence/...`，与主计划链接基准不一致。

限定修复只解析任务行中的精确 Markdown 链接目标，并要求目标严格等于从 `docs/08-总开发实现计划.md` 出发的 `evidence/...` 路径；同时从任务行提取独立64位十六进制 token，要求对应 SHA 精确存在，并保持任务状态必须为 `已完成`。同一规则适用于 manifest、scope approval 和 fixture bundle 三个现行计划证据门。错误目录前缀、相似后缀、错误 SHA、非完成状态仍拒绝。不接受任意 substring，不改变 manifest 自身格式/bridge/source/staged pins，不放宽 scope/bundle 内容、host、资源、锁、秘密或物理执行门，也不将本修复视为物理D通过。

修复改变了受管 host 源字节，旧 `manifest75.json` 和 `source-build-receipt75.json` 按不可覆盖规则保留。当前执行改用版本化 `manifest75-02.json` 与 `source-build-receipt75-02.json`，新增 pin 构建器、entry host 模块及其专项；同一次封闭构建实际通过164项Node与12项Java组件，DM8连接和SQL写均为0，已暂存probe class SHA保持不变。运行入口只认主计划当前任务行精确引用的新manifest及SHA，不能用旧完成结论掩盖源码漂移。

### 16.13 ACTR84 主机责任拒绝最小诊断D（2026-10-11）

ACTR85派生技术补充（2026-10-12，责任Codex，FIX-CON-ACTR85-BRIDGE-RETURN-01）：run2fc第一STOP后Docker新增Status.IPAM.Subnets地址计数由容器移除改变，原静态比较误拒，尚无库存/SQL/case。只将确切172.17.0.0/16的IPsInUse/DynamicIPsAvailable归一化用于配置比较；严格核Status/IPAM/Subnets/counter完整字段，整数非负、两数和65536、IPsInUse为当前endpoint数量加网络/网关/广播三保留地址，所有endpoint必须合法本子网非保留单播地址且无重复。未知Status、计数矛盾、配置或Status存在性变化继续拒绝；不豁免整个Status，不改网络/权限/业务合同。原16.12安全返回义务在target/tool PID0、source不变、失败worker死亡及原合同/窗口/预算/host/锁均现时核明后，只一次START本轮已停止的679发布器；证据独占存原run/publisher-safe-return85.jsonl，不删除失败controller锁，不宣称完整库存返回、case通过或父交付完成。复用完整post-binding前检（源码未变），11聚焦反例初次3失败/8通过明确复现计数误拒及非法endpoint漏拒；修复后版本化09，原08及失败原件保留，聚焦和原Node/Java组件、规定后检后返回VER-CON-U107-DM8-01。锁核销仍须针对2fc独立证据，禁止套用7fec/81的marker。

本项仅消除 run83 暴露的拒绝证据缺口，不追认已退出的 PID9696 来源，不批准新的物理重试。现行主机进程责任守卫继续对每个可见进程严格核 PID、parentId、createdAt、command SHA 和获准角色；未知、不可读、同 PID 身份漂移仍失效关闭，不增加按名称、父进程或短生命周期的豁免。

REC-CON-ACTR85-FAILED-CONTROLLER-01精确恢复D：2fc原marker逐字为`2fc5f10a-a0c4-47ae-96a0-e292956e68ce 34152 a2611d4f-fc91-40f8-bc7e-2ba8c560146e`（无换行），SHA0a9486ff74cfdb0bbbe2ea5c750138199942434b4bce5d6d2ff14c1314d87968，文件dev3838357650/ino3096224745585511/birth1791769649675807300/ctime1791769649676819200/79字节。原四事件与失败输出固定绑定，无SOURCE_BASELINE/FRESH_SOURCE_CAPTURE/START_SOURCE/START_TARGET/FIXED_CASE及相应输出；source PID60521/StartedAt不变、target/tool PID0、native/target锁缺失、自有worker34152及controller3620现时不存在；679恢复回执只一次START并证明其余八状态未变。09完整pins/组件/规定后检退出后，用现时scope/窗口/7GiB/4CPU/固定九资源/完整host责任和两个publisher窄网络合同准入；仅source SELECT两次完整六schema/15账号/restoreCatalog/nativePrivileges库存，之间原严格CA/SAN/server-pin三GET，复用81完整库存摘要及TLS核验。两次库存完整且同摘要、资源全程PID/StartedAt/绑定不变、原失败四事件及输出集合未变，才能独占fsync归档原marker/身份和证明，最后再核死owner/现时责任/原锁完整身份后同卷rename至原失败run/retired-controller85.lock。核销只说明当前source完整健康且本轮没有数据库动作，不证明不存在的原baseline前后相等、不复写原RESULT或改成sourceReturned，不接受任何通用marker/目录参数；未知和失败留证据与锁，不重试已未知动作。操作脚本自有mutex成功退出释放，失败保留自己的精确互斥现场；不清理旧失败或历史数据库，不扩大已批准物理范围。

拒绝瞬间新增可信固定结构 `ACTR84_HOST_RESPONSIBILITY_DIAGNOSTIC/v1`，原因仅为 `UNREADABLE`、`UNREGISTERED` 或 `DECLARED_IDENTITY_MISMATCH`。`observed` 只保存 PID、parentId、createdAt、name、commandSha256；同 PID 声明存在时，`declared` 只保存相同身份字段和 role，否则为 null。字段必须经类型白名单投影，禁止保存 CommandLine、argv、环境、凭据、SQL 或任意请求附加字段。诊断私有绑定于守卫实际创建的 Error，普通 JSON 或调用者不能伪造；现有 `failure-N.json` 增加 `hostDiagnostic`，不新建状态台账。

外部错误码继续使用 `CONTINUOUS_UNREADABLE_HOST_<PID>` 或 `CONTINUOUS_UNREGISTERED_HOST_<PID>`，`automaticRetryAllowed:false` 不变。专项必须覆盖不可读、未登记、同 PID 声明漂移、精确匹配成功、伪造错误无诊断及序列化无命令明文/环境/秘密。本设计只允许守卫、运行时 failure 投影和专项测试改动；不读取秘密，不取锁，不执行 SQL、容器启停或物理重试，不操作 `entservice-dm8`。

2026-10-12接管绑定补充：上述三个已批准诊断源码必须纳入现时连续入口清单。复用原构建器，只将独占输出和入口固定引用从07推进为08；旧07及原probe字节保留，原完整Node/Java构建检查、全部source/staged pins及计划证据门不减。此为同FIX-CON-ACTR84-HOST-REFUSAL-DIAGNOSTIC-01的必要集成出口，不另建框架或产品候选；08绑定后立即返回已批准且已过D的fresh CASE_EMPTY现时准入，不扩大其他case、资源、权限、窗口或发行范围。

### 16.18 ACTR90 4f7目标退场与原源返回（2026-10-12）

责任Codex，来源4f7fa3d9-cc44-48ab-a13d-42f0da0f01ea原events/failure/source-baseline/两target库存及现时Docker/CIM。目标启动/probe/旧六before通过，entry constructor在原sourceHashes核验拒绝ENTRY_ORIGINAL_SOURCE_CHANGED，未到entry lease或case；旧六after一致。RETURN_UNKNOWN首目标STOP前拒绝UNREGISTERED_HOST_43452，未自动返回：source及tool停止、五writer停止，target PID84627/StartedAt06:50:09.927413031Z、tool PID84836/StartedAt06:50:42.54112777Z在线。43452事后未捕获不能推断角色；现时另核明四工具精确tuple：41584/33412/cmd/06:56:44.9700030Z/SHAda4ffa6b86162a46a6eafc1b00be87ea4f0d72afcd8116d9cad4c3cba174f7f7、39108/33412/cmd/06:56:45.0423110Z/SHAa395d3c719e143921d1cba89202ff26a3b35726d041e6b3ef5d1cdde207aaf1b、44964/41584/node/06:56:45.1946640Z/SHAf956211ca0ab50ebdf87e37a1e8af5af74024fbb5bad7b9922a1c26561735b49、45236/39108/node/06:56:45.2148000Z/SHAcb1f3c5dfb07756034a291eb952a87de900771acedb0332de3635399e6dd97f1；需实际CIM命令与已核明工具命令SHA逐项匹配，无名称或父进程泛豁免。

复用原连续finally安全返回义务，持自有wx/fsync恢复互斥且原控制锁保留，每步先核九资源/原绑定/当前完整host/预算/一在线库/原事件无RETURN动作、无native或target lease、死owner及原锁NTFS完整身份，PREPARED fsync后仅一次STOP targetTool、STOP target；两者PID0才一次START source及sourceTool，再五角色各一次START。仅目标工具idle sleep时停止，原source停止epoch5:17:25.555794417Z/FinishedAt06:50:02.71841978Z必须未改变；每次动作后实际State/PID/new StartedAt、source/target新epochREADY及三产品READY证据通过，其他资源未改变；错误保留、不盲重试。窗口原10-13 18:00、一在线DM8/7GiB/4CPU不扩，无SQL/秘密/重新snapshot/新case。原marker `4f7fa3d9-cc44-48ab-a13d-42f0da0f01ea 36048 3e7fabc8-7c85-43c4-9330-17a290ea64b3`，SHA3ea3b3c29e74e0fc864ff1d00647c8fc10dc4989b12b07e4b9926a992da045a9，dev3838357650/ino1125899908647469/birth1791787368463112900/ctime1791787368463112900/size79。恢复先服务出口，完整source-only两库存/TLS及原baseline闭合和死owner/精确身份核销另在此必要步骤完成；原sourceReturned=false和失败不改。

旧74的entry source pin b31d0317d85daf2b0d0cca5c368498617ce727836d2063f9d0936235c88fb32d与现行11实际4a45f02d965332dfb69fc65b689b38311fbd32777b0d2e8d5da6c548c19d8fba不一致，不能盲更新摘要或绕过守卫；需要恢复后有界复核历史已批准源码差异和原传递包，再按必要集成D接续。不得再用整轮物理转场发现可在无副作用阶段核出的陈旧包。

### 16.19 ACTR91 陈旧传递包的必要集成与提前拒绝（2026-10-12）

责任Codex，稳定必要任务FIX-CON-ACTR91-TRANSITIVE-BUNDLE-01，父VER-CON-U107-DM8-01。4f7在停源/启动目标后才因旧74 sourceHashes拒绝；REC90完整返源已退出。对当前entry源码仅逆转16.8的taskHasExactReference函数及两处调用，可逐字重现旧包b31d0317d85daf2b0d0cca5c368498617ce727836d2063f9d0936235c88fb32d摘要；当前4a45f02d965332dfb69fc65b689b38311fbd32777b0d2e8d5da6c548c19d8fba由02至11各清单验证。由此限定差异为已批准的链接准入修复，不改变Java、SQL、fixture、scope或权限。

在原74目录追加bundle74-02.json与独占集成证明，保留旧包及全部失败。生成器先要求上述重现摘要成立、唯一不同source为entry、其余12源码、22class本地stage与原编译class、4依赖、固定plan/fixture全部原摘要一致；只更新已核明entry源pin，不重编Java或重新stage。连续builder/runtime/fence改精确引用新包、生成版本化12清单。原物理D行追加新包精确引用，原批准/旧D/历史链接不改。

连续pins在admit及所有现时核验中，先对传递包13个固定源码路径集合、重复/缺失/附加项、合法SHA及实际字节逐项拒绝；使用原受控路径读取，不读秘密/取锁/停写/启停/执行SQL。原entry创建、每步live及返源原校验保留。反例证明陈旧包在控制锁/STOP之前拒绝，成功及缺失/重复/错误摘要/未知路径均覆盖；相关回归及规定前后检后回父fresh CASE_EMPTY。新操作worker只追加16.18四个已核明完整工具tuple，仍现时逐字段匹配，不引入进程名称/父进程泛豁免或工具工厂新权限；未知重启仍拒绝。

### 16.20 ACTR92 宿主进程按项目影响准入（2026-10-12）

责任Codex，来源用户本聊天明确“没有，这种类似的进程如果对当前的项目没影响，都不需要管”，33执行准入失败/拒绝后置，以及原SingleDatabaseFence、AdmissionProbe75和FixtureGuard。此最新用户限制取代“任意宿主shell新PID都必须补名单”的工程调度前置，不取代真实数据库独占、权限、数据用途或失败返回合同。33未知50372本身不补认、原失败保留。已有source share锁连续持有全部原六schema表；SOURCE_IDLE/SOURCE_HELD/TARGET_IMPORTED probe核V$SESSIONS数量与用户；FixtureGuard每步前后核唯一会话；旧六/完整source前后库存另核，因此应把额外宿主检查限定在可观察的项目影响，不将普通工具的存在等同数据库写入。

仅readProjectFacts76的显式projectImpactScope模式采集受控Windows进程与TCP连接当前快照；默认模式仍旧全host。请求由原固定九资源当前绑定派生本地发布端口、项目容器IP、网络、确切资源名/ID及工作区/受管引用目录；采集失败、未读命令、PID/创建时间/命令摘要不完整均不能静默省略。直接DB客户端(java/disql/dbeaver/navicat/wsl/bash/sh)、当前controller/worker执行链、命令涉及项目资源/目录/受管资料、实际到本项目端口或容器数据库IP的TCP连接，均保留在严格责任集合；原host guard及native worker完整身份检查不改，未知或漂移仍拒绝。

只有命令可读、非直接DB客户端、不在执行链、不含项目引用且当前无项目连接的普通进程，才从责任集合分离为hostScopeObservation中的脱敏观察，不给NON_DB角色、不赋权限、不自动登记工厂后代，不表示永久无写权限。每次facts重新分类；未来项目引用/连接出现即进入严格集合并拒绝未知。Windows连接观察失败不能按空连接处理，连接owner必须在CIM中存在且创建时刻不晚于连接观察时刻；受控根缺失、PID重复、时间不明或祖先循环拒绝。普通进程的祖先已退出/被复用，不单独证明项目影响，记录ancestryComplete=false，其他项目关联信号仍正常保留；不把它声明为可信工厂或项目角色。TCP检查是补充信号，不替代真实DM8会话、share锁、全部writer STOP、每步独占、原六不变与完整返源；外项目只观察隔离，不管理其进程/服务。

稳定任务FIX-CON-ACTR92-PROJECT-HOST-SCOPE-01，限定改既有project isolation模块/专项和连续75显式调用、操作责任生产者；entry源码/74-02/Java/SQL未变，不更新其pin或重编。观察记录只存PID/parent/创建时刻/命令SHA/布尔关联信号及目标端口，禁止命令明文、环境、秘密或任意载荷。反例覆盖不可读/未知TCP状态/端口/内网IP/直接DB/项目命令/执行链/祖先缺失/身份漂移；普通工具新PID无影响不阻塞，实际关联者未登记仍失败。版本化13清单和规定前后检后返回fresh CASE_EMPTY；其他case、正式发行/切换暂停不扩大。

### 16.21 ACTR93 不可读进程的有界现时复查（2026-10-12）

责任Codex，父VER-CON-U107-DM8-01；aa84准入拒绝PROJECT92_HOST_COMMAND_UNREADABLE，无events/物理case，拒绝后九资源State全等run观察。拒绝没有记录PID，不能追认来源；独立CIM曾观察到短命pwsh空命令，另采集不可读为空，仅支持退出竞态假设。必要步骤FIX-CON-ACTR93-EXITING-HOST-CAPTURE-01只针对当前采样中不可读候选PID做一次Get-CimInstance精确查询，无延时循环。查询报错拒绝；仍存在时要求原PID/parent/name/creation相符并补真实命令SHA，仍不可读或身份漂移拒绝；查询空才剔除已退出身份并记录脱敏退出及复查时刻，不给角色。执行根缺失、项目TCP连接owner缺失继续原拒绝，真实DM8会话/连续停写/库存/每步围栏不变，不宣称已退出者历史上无影响。

复查输入须来自实际受控CIM生产者；正反例验证已退出普通候选、不读命令的存活进程、PID复用、缺时刻/重复复查、执行根和项目连接owner；14清单/既有Java精确复用及规定后检后回fresh CASE_EMPTY。原entry/74-02/Java/SQL不改，用户无关进程限制不替代未知身份拒绝；不重复历史返源或开启另一个维护路径。

### 16.22 ACTR94 3976失败的完整返源与双锁核销（2026-10-12）

责任Codex，稳定REC-CON-ACTR94-FIXTURE-SOURCE-RETURN-01，父VER-CON-U107-DM8-01。3976事件证明P_CREATE_SCHEMA已COMPLETED，随后原生PREFIX_EXTRA_OBJECTS_FORBIDDEN，没有P_CREATE_SUBJECT PREPARED；旧六目标前后库存相等另核。目标/tool停止、七原角色已返回；完整返源门因child信号退出exitCode=null拒绝，原sourceReturned=false/失败保留，不把服务返回冒完整返源。

只复用REC90已有完整两源库存、严格CA/SAN/server-pin三GET、九资源/原绑定/现时宿主作用域/死owner/源epoch/预算/窗口/互斥及确切锁原件fsync归档流程。source PID88814/StartedAt2026-10-12T10:44:52.711096614Z必须不变，29268/47864必须死亡，目标/tool PID0，source tool只sleep。源库存两次须等本run完整baseline，目标旧六before/after须相等；任何未知失败保锁不重试。原entry与controller两个锁分别以确切marker、SHA和dev/ino/birth/ctime/size绑定，归档原字节后同卷rename至本run保留目录，目标partial schema不改、不启目标、无新case/目标SQL/DDL/DCL/发行。

首次恢复在任何秘密/锁/SQL之前因ORIGINAL_SIX_CHANGED拒绝。对3976两份原始target库存有界核对证明schemas/accounts/nativePrivileges全部相同；11类restoreCatalog的columns相同，每条记录的完整规范JSON及重复计数相同、无增删，只是无序结果行排列改变。仅本次恢复比较时对categories.rows按完整canonical JSON排序，保留重复及原columns/所有其他字段和原始字节；记录两raw SHA/原有序摘要/排序后列表摘要。不能丢列、去重、只按ID比较、忽略值或容许新的类别。源库存两次与baseline仍使用原完整严格摘要，不因这个目标观察差异扩大源恢复合同；代码库oldSixDigest75未改，后续产品运行的无序目录语义作为实际缺口另处理。

controller marker为3976b3ce-6226-48e9-aa88-1b08b9fe064b 29268 bde4bc8b-dbf8-4f95-b9e6-2729a5f2db0a，SHAf8e9ece3229355f55e3e14fc2976c77d748a6c7a7034c0bb9abe1a900415a373，dev3838357650/ino1407374885393396/birth1791800647569540100/ctime1791800647570068000/size79。entry marker为3976b3ce-6226-48e9-aa88-1b08b9fe064b 5374f180-1368-41c7-bb89-7513a51591b2加LF，SHA38e30fd1b3db16e3a21bacd097ed9fd72d566b17e4982c83f480e3bd89d4ebd2，dev3838357650/ino281474978550794/birth1791801539955408400/ctime1791801539955913400/size74。两个锁顺序归档逐项写PREPARED/COMPLETED，不以一个成功假称两个皆成功；原失败目录新增recoverable retired锁以外字节不改。互斥失败保原件，停止后的下个动作明确记录；不能借核销清空已存在测试namespace或绕CLEAR/初始不存在合同。

### 16.23 ACTR95 实际关闭事件与无序目录行比较（2026-10-12）

责任Codex，FIX-CON-ACTR95-RETURN-CLOSE-CATALOG-01，父VER-CON-U107-DM8-01，依赖94完整返源退出。3976子进程错误触发kill，entry释放入口用child.exitCode!=null判断退出；Node信号退出会实际close但exitCode仍null。只在当前唯一child实际close回调记录关闭事实，释放门必须已close；exit/kill请求、设置exitCode、error及时间经过都不替代close。失败SQL仍失败/UNKNOWN，原所有者/无runPending/七角色/目标与tool停止/锁身份/完整两源库存/TLS等释放条件不变，不让close信号生成SQL成功或允许后继命令。

3976目标前后原始库存证明schemas/accounts/nativePrivileges相同，11种目录列和完整记录多重集相同，只rows排列不同。oldSixDigest75只把restoreCatalog.categories各rows规范JSON排序作为摘要输入，保留重复、全部列/字段和值/类别及其他结构；不覆盖raw，不去重，不按部分键比较、不忽略新的属性。反例覆盖同记录换序、真实增删/列/值及重复次数变化；源恢复原严格完整摘要暂不改。

只集成变动entry源码及其测试，无Java/SQL变化；新bundle74-03必须逐项证明原74-02其余12源码、22classes、4依赖及plan/fixture未变，源包保留；连续16绑定新包/entry/比较器/专项及收据，Java12只可复用输入未变11原件，禁止冒本轮重新执行。先工程后检，再核当前partial schema及PREFIX_EXTRA_OBJECTS的真实对象与受限续作合同；不直接重发CLEAR、不DROP/重置schema、不追加业务来源用途/发行。

### 16.24 ACTR96 原生schema对象与仅schema前缀续作（2026-10-12）

责任Codex，FIX-CON-ACTR96-NATIVE-SCHEMA-PREFIX-01，父VER-CON-U107-DM8-01。3976真实target库存保存的DBA_OBJECTS视图首UNION把每个SCH以OWNER=OBJECT_NAME=该schema返回；六产品schema的nativeSchemaObjects均实证这一行为。旧prefix在创建后要求DBA_OBJECTS计数零，后续也排除不了SCH，故必然拒绝。仅排除OBJECT_TYPE='SCH'且OBJECT_NAME=OWNER的自身对象，独立SYSOBJECTS schema唯一性/SYSDBA owner校准保持，其他对象仍按原完整表/列/约束/数据/会话检查拒绝。

3976原attempt SHA=f2bccb156bf57894de3f1376b0e7d30e6628dc59b970ba3bb0c7682c9815d629证明仅P_CREATE_SCHEMA PREPARED/COMPLETED和PREFIX_EXTRA_OBJECTS_FORBIDDEN三记录，无P_CREATE_SUBJECT；94原result SHA=85f24db53a158162b629be1b5813fa7eea84f7a569606ab6a496ea6e1a6180e0证明两确切锁归档及完整源健康。仅CASE_EMPTY/ACTR66_EMPTY可用RECONCILED_SCHEMA_ONLY_3976，不接收任意prefix、run、SQL或前缀长度，不为UNKNOWN DDL推定结果。新run从现时完整保护/停写/原生会话/旧六门重新进入；createEntry之前复用现有bootstrap-inventory读取ACTR66_EMPTY对象并保存真实raw，必须只有确切自身SCH。随后Java再校准owner及prefix1空表/零行/无其他对象，跳过已完成CREATE，按原固定八准备DDL/V107/U107继续。原1与新14 DDL分别留证，原失败不改成功，若当前对象、owner或原件不符则写前拒绝并按既有完整返源流程返回。

只版本化新的host stage/bundle/manifest，不覆盖原74/73产物。Java生产者变化必须实际编译并跑真实源码调用的组件回归；未变依赖/SQL/plan/fixture原件逐项复用。新启动只限已批准CASE_EMPTY，其他两case准入范围不随之扩大；不得DROP/reset schema、重发CLEAR、新增账号/Grant/业务资料/模型/正式部署/C切换。任何原始目标实测仍由当前完整源保护、独占资源与新鲜责任守卫决定，源码或组件出口不替代实测。

### 16.17 ACTR89 5f3五原角色安全返回（2026-10-12）

责任Codex，来源5f3c0797-c89c-402e-9d07-36c6d2f4cbc8原失败事件、failure-3固定诊断/source-baseline/source-fence-worker及现时CIM/Docker。五STOP均COMPLETED，source完整baseline与idle/held probe通过，capture末段因新Codex code-review MCP进程41976拒绝，RETURN_UNKNOWN在首动作前拒绝；未STOP source/START target/创建entry/执行case。source原PID80626/StartedAt2026-10-12T05:17:25.555794417Z未变，source tool只sleep，目标/tool PID0，源native worker7968及主worker44768/controller38828必须现时退出且native/target锁缺失。

复用16.10/16.12返回义务和85单角色恢复方法，仅对本轮五已停且RETURN_WRITER从未PREPARED的精确原角色各一次start，逐操作前现时固定九资源/原绑定/责任/范围/窗口/预算/源epoch/目标停止/工具idle/原marker文件身份通过、PREPARED fsync后才动作，逐角色实际running/new epoch及三产品READY证明，原两个publisher窄bridge动态合同不减；新未知停止并保留原件。核明新增NON_DB工具完整tuple为41976/33412/cmd/created2026-10-12T06:02:13.8694360Z/SHAa395d3c719e143921d1cba89202ff26a3b35726d041e6b3ef5d1cdde207aaf1b及33620/41976/node/created2026-10-12T06:02:13.9619510Z/SHAcb1f3c5dfb07756034a291eb952a87de900771acedb0332de3635399e6dd97f1，实际launch_code_review_mcp.cmd和对应server.mjs已读核，与原同工具命令SHA一致；只绑定这两个当前tuple，未核明者继续拒绝。不补写原host文件或按名称/父进程泛豁免，不关闭全局Docker引擎/外项目。

第一阶段无秘密/SQL/启source-target/锁核销，真实恢复五角色后在同必要步骤复用source-only完整SELECT两库存与严格CA/SAN/server-pin三GET，完整schemas/accounts/catalog/nativePrivileges必须等于5f3原baseline；原native锁/target lease缺失、死owner、当前原角色/host/预算/权限/窗口、失败events完整pin与确切控制marker NTFS身份全核明，自有wx互斥/fsync原bytes/最后再核后仅同卷rename原5f3锁。不能借过往309ac/7d证明替代本轮失败返源；不重采保护、不重新case。11项目源码/产品pins未变，复用88完整工程前检，状态/证据仍规定后检；原sourceReturned=false/case失败保留，恢复与单库交付分开。

ACTR89现时核对补充：同批Codex app-tools新增35376/33412/cmd/created2026-10-12T06:02:13.9274700Z/SHAda4ffa6b86162a46a6eafc1b00be87ea4f0d72afcd8116d9cad4c3cba174f7f7及44608/35376/node/created2026-10-12T06:02:14.0507520Z/SHAf956211ca0ab50ebdf87e37a1e8af5af74024fbb5bad7b9922a1c26561735b49，实际launch_codex_app_tools_mcp.cmd及server.mjs命令已核明。四tuple均现时逐字段绑定，无泛豁免。89第一检查拒绝UNKNOWN_HOST_35376，source-service-safe-return89.jsonl唯一REFUSED记录且未PREPARED/START，原五仍停止、source/target未变；自有mutex marker仅`ACTR89 3884`加LF，SHAa7a64b0a5b9b8cde3921e9e85f05fe6608fe9fe26b657622333b0b14e9b1fc96，dev3838357650/ino281474978511355/birth1791785390683867100/ctime1791785390684976100/size12。确认3884死亡、唯一REFUSED/无动作/此完整NTFS身份后仅同卷归档此自身mutex至原5f3/retired-recovery89-refused-3884.lock，再新独占source-service-safe-return89b.jsonl执行原五一次恢复；原89和5f3失败原件保留，不重复未知资源动作。

### 16.16 ACTR88 责任文件生产者时间字段对齐（2026-10-12）

来源309ac98c-0bd0-4885-a9aa-15ba7d089e58真实事件及host-responsibility原件，责任Codex。目标启动/原生probe/旧六before通过，createActivationRecoveryEntry首load的assertHostWorkers拒绝CURRENT_HOST_RESPONSIBILITY_REQUIRED，因为接管worker只生产date，原下游合同要求capturedAt。拒绝先于classProof/开保护包/目标lease/SQL；原fixed case未执行，旧六after与before一致，完整返源成功。仅将D:/AgentPlatformTempRecovery/single-db-takeover-worker-20261012.mjs未来新run生产的责任记录加入与原date同一个实际时间的capturedAt，保留两字段；不修改309ac或旧责任文件，不改消费者守卫、产品源码、既有11清单/probe或其他角色。复用原controller每run脚本SHA绑定与无秘密准入，后继fresh case不能重放原run。

此必要步骤内仅复用16.15/16.14精确核销方法处理309ac控制锁：marker `309ac98c-0bd0-4885-a9aa-15ba7d089e58 48876 4a32ed7d-8583-41d2-bd13-d64c0a6d7355`，SHA7c6f7b044cf370a28f33d4c4720542649479a8267d4298a9b48fa0750b41532d，dev3838357650/ino3659174699035058/birth1791781501194220500/ctime1791781501194220500/size79。原11 producer完整source baseline/两return/严格TLS及RESULT sourceReturned=true/returnCode NONE固定pin，附加两target库存的oldSixDigest75一致证明及CREATE_FIXED_CASE_ENTRY UNKNOWN/无FIXED_CASE/无entry目录和target lease，确认原worker48876/controller40720死亡。现时source PID80626/StartedAt2026-10-12T05:17:25.555794417Z、九资源/原角色/host/scope/预算/窗口/目标与tool PID0/原native锁缺失/marker身份/独占自有互斥及fsync原件归档/最后再核完全保留；仅同卷rename至309ac/retired-controller88.lock，无新秘密/SQL/启停/通用锁入口。项目源码与11 pins未变，因此复用87完整工程/组件验证，不重建候选；任务字段与证据由原规定治理检查消费。原失败不改成功，核销后立即回父fresh CASE_EMPTY；未知或epoch变化留锁报告。

### 16.15 ACTR87 单次输入诊断及确切返源锁接续（2026-10-12）

责任Codex，来源为7d2e71fa-5dce-4b78-86ec-80155b895252原事件、target-start-input86.json、目标StartedAt04:00:53.562760233Z以来日志及完整返源证明。目标在carrier5秒TIMEOUT关闭时04:00:58.944878231Z退出1，只见generic失败无ACK；源完整返回且case/target SQL未开始。只读核得初始化证明/INI原SHA及attempt字段匹配。输入未送达是待证假设，不据此扩大资源守卫豁免。

仅将已有有限carrier从5秒调整为15秒、关闭余量仍5秒，不延长原90秒就绪窗口；一次start/一次输入/无信号代理及全部现时守卫不变。入口读取JSON行时明确拒绝空输入、超过4096字节或非法编码/JSON；只输出固定码，不输出输入、异常详情或秘密。组件覆盖有效完整行、EOF、非法编码/JSON及超限，旧身份/窗口/数据证明不变。11清单独占保留10/7d原件；carrier TIMEOUT仅证明本机子进程关闭，不证明传输或启动。下一fresh case必须用同run ACK及新epoch就绪验证假设；没有新结果不做同条件重跑。

此任务内7d已完整返源锁接续只复用16.14精确核销方法，绑定marker `7d2e71fa-5dce-4b78-86ec-80155b895252 28872 40aa5926-7331-4e9d-9c22-a56e53d640c8`，SHA358a7caefe86f519f34aa82fbf0c25ca91e1ca32bb8db5cb2808ac86d7c7479a，dev3838357650/ino1407374885337698/birth1791777201216616400/ctime1791777201216616400/size79。复用7d完整baseline/两return库存/严格TLS及RESULT sourceReturned=true/returnCode NONE/无target SQL；现时source PID77838/StartedAt2026-10-12T04:01:19.063691736Z、九资源/原角色/host/scope/预算/窗口/目标与tool PID0、native锁缺失、worker28872/controller33428死亡及marker完整身份通过才准同卷rename至7d/retired-controller87.lock。自有wx互斥、原件pin、归档fsync、最后现时再核方法不减；11组件与规定后检先退出，无新SQL/秘密/启停或通用参数。核销后回父fresh CASE_EMPTY，原case失败不改，epoch/输入/权限/新鲜性不符留锁报告。

### 16.14 ACTR86 已导入目标启动协议D（2026-10-12）

72ed精确已返源控制锁核销（REC-CON-ACTR86-RETURNED-CONTROLLER-01）：原结果sourceReturned=true、returnCode=NONE/returnErrors空，COMPLETE_SOURCE_RETURN确实COMPLETED；baseline和两return raw完整schemas/accounts/catalog/nativePrivileges摘要均fb23d546357d1858051c4219189c229f517823adba63e1ccb51552cd222e6223，严格TLS三GET200/原CA与server pin符合原合同。原target未收到stdin，target tool及entry lease从未启动，无target SQL；不把STARTUP_READINESS_DEADLINE改成功。与无返源证明的7fec/2fc不同，本任务复用这一已有完整producer原件，仅在source同PID75476/StartedAt2026-10-12T02:40:05.952170475Z、完整九资源合同/原角色/host/预算/窗口/目标tool PID0/原native-target锁缺失且worker48988/controller31292现时不存在时归档。确切79字节marker `72ed8ab4-d79f-4a60-bfca-a644af66c3d3 48988 bd60c923-9943-4706-a45c-9d5e8e2cc10e`，SHA ab77ea15e36c60867718cdfb1e5669be8774479fb624ebb49c52bb211076fde4，dev3838357650/ino1407374885329667/birth1791772178016209200/ctime1791772178017213100。另持自有wx/fsync互斥，原失败events/return proof/raw/TLS/source template原bytes pins与原D/manifest09先固定归档证明；现行10全部pins与工程后检退出具证后，仅退休这一个原marker，先wx/fsync原bytes归档、最后重核文件完整身份及死owner/现时门、同卷rename，原RESULT不改。输入/配置/范围/epoch或必需新鲜性变动即拒绝复用，失败留原件/锁，不自动重试。核销不声称当前业务数据永不变或新case已过；下一case仍新run/new source baseline/fresh连续保护，不能借72ed旧包跳过停写围栏。此核销无新秘密/库存SQL/导出/启停/target动作，避免重复已经有效的两次返源库存和整个保护流程；只读核对不能扩大此marker范围。

72ed在START_IMPORTED_TARGET超时，目标入口实际无新日志；owned-restore-entry.py对已有INI也先readline再要求明确启动指令，而连续运行只有docker start。原55模式还要求targetNeverImported=true，与59已导入journal相矛盾。因此不是就绪延迟，不能延长等待或借旧空目标合同启动。

仅增加START_EXISTING_IMPORTED_ACTR86内部模式，保持原role/name/UID1000/caps0/owner、初始化证明和INI原SHA及init-attempt逐项校验。输入仍封闭base字段，补原55续作SHA、targetNeverImported=false、fresh run UUID、原导入journal SHA97bc275b613ce8a801d5ea64715c194a3e0244ea940f1f2a9fb65e0a9b3324e4及原scope70 SHA f0c07ab3cc90059055d7190886f1909a63b7edf30db5c86f455551fff857b4d0；packageSHA只取本run已认证的新保护包，sourcePidZero由原控制器现时inspect证明，原截止10-13 18:00不延。只返回原dmserver调用，无初始化、导入、DCL、账号或新容器分支。旧模式的窗口、neverImported=true和拒绝原包规则不改。

复用runNativeCommand：原一次start后，通过docker attach --sig-proxy=false向唯一确切target发送一次小于4096字节的无秘密JSON行；仅关闭这一输入carrier，不传播信号给数据库。载体最多5秒加原5秒关闭余量；返回0或可信TIMEOUT且closeObserved=true才证明载体已退出，其余错误保留并拒绝。不在载体存活时用host豁免或另开观察者；退出后原全局facts/host/资源/一在线DM8守卫照常复核。原90秒就绪窗内同时要求当前StartedAt以来日志含同run的IMPORTED_ACTR86入口确认和SYSTEM IS READY，输入仅发送一次，无重新start、重发stdin或旧日志替代。就绪未知仍原停target→完整返源→留失败锁流程。此为原已批准隔离启动的技术补齐，不是产品部署或新增物理scope；只改停止目标的bind入口源及连续运行接线/专项，版本化10把入口和测试都pin，原09/72ed原件保留。后继确切72ed锁核销仍单独登记并保持其原case失败，不能通用删锁。

### 16.25 ACTR97 6f13返源及入口收口（2026-10-12）

责任Codex，父VER-CON-U107-DM8-01，复用16.22恢复设计。唯一失败run=6f13c815-4944-4951-8016-66941ffed355；18条日志恰为八准备DDL及V_DROP_OLD各PREPARED/COMPLETED，无结果及悬空PREPARED，禁止推定剩余DDL已完成。原RESULT为ENTRY_EXIT_UNKNOWN_LOCK_PRESERVED/BOUNDED_PUBLIC_CA_REQUIRED。九资源与源epoch PID93277/StartedAt2026-10-12T12:58:04.696089565Z、死36076/39160、原target/tool停止、原维护范围、预算和完整host责任必须现时核明。原target-inventory-5与8六正式schema摘要必须相同；source-only完整两库存和原baseline严格相同及严格三GET/TLS通过后，仅同卷rename两个确切锁，不改原结果、不启动case/target或做SQL写入。

entry marker为run加bb031f5e-c0e1-48b7-ad37-41cb06b5714a及LF，SHA f88dcc0ee34685da3ad67c3db71f683942f1e8193d7dc3cb8c518d60f93b4288，NTFS dev3838357650/ino281474978568432/birth与ctime1791809077975531100/size74。controller marker为run加39160及fc56cfb4-796a-43f4-b630-0691bd71a419，SHA60e2af61ddda4424be0767b46d36d11c86321ba78f74018ced58ae42aa2fcf3d，dev3838357650/ino3659174699096049/birth1791808016215733400/ctime1791808016216242900/size79。原件pin/独占wx互斥/fsync/最后重核与归档回读不减。复用17源码固定原件，先恢复再改入口。

入口证书只复用82 PUBLIC_CA的确切logical/physical双路径及SHA、文件类型/大小/realpath和真实TLS守卫；不允许任意重定向。退出状态需保存实际code/signal及已完成步骤，不将elapsed近480秒当作已证timeout。重复采集优化必须保证每次完整项目作用域观察依旧执行，不能缓存跨SQL的资源/权限/期限事实；本节不授予任意partial prefix续作。

ACTR97恢复实测已通过：reconcile97-ff27f507-aaf6-48f2-8d3b-d302e5b1a4bd完整两库存/TLS/双锁归档具证。唯一续作RECONCILED_V_DROP_OLD_6F13，仅ACTR66_EMPTY/CASE_EMPTY；固定3976创建、6f13十八日志与本次proof/result原件SHA，累计10已完成DDL/新5条分别留证，不重发已完成SQL。现时新保护与停写、原生bootstrap对象raw保存后，由原Java prefix8完整校验（两固定表、完整列/键/索引/约束、SYSDBA owner、零行、无额外对象、唯一会话）证明与V_DROP_OLD后的精确结构相同，且原JdbcStep的V_ADD_NEW入口复查ABSENT/无receipt/空历史，才执行剩余V_ADD_NEW、V_CREATE_RECEIPT、U_GUARD和三逆DDL。未消费的其余case/任意prefix继续拒绝。

删除的是readProjectFacts76内随后被完整impact覆盖的第一次全局CIM采集；每次完整现时impact/连接/资源采集保持，无缓存。新鲜写前守卫及每条SQL原30秒queryTimeout保持。原480秒总时限没有容纳多次宿主检查；本受限suffix最多900秒（宿主910秒关闭兜底），不是SQL时间放宽。启动距批准截止的返源预留提高至45分钟，不延总授权；真实close code/signal与剩余步骤单独持久化，超时与未知仍停后继/完整返源/不重试。此为已有同CASE、同SQL集的精确已对账续作，不扩大业务或物理范围。

ACTR97实际退出与后继：774eb834在2026-10-12T14:56:44Z完成CASE_EMPTY_REVERSE_COMPLETED、完整返源和双锁释放，5新DDL/0seed，原10不重放。97完整后检已结束、仅原健康红，源码18未再改变。scope70原人类明确批准三个限定schema及V/U验证，16.6/16.7本已定义逐case新run/新保护/一连接与完整返源。physical-d-admission97-history.json把既有批准与现行18和真实EMPTY出口对账，下一顺序为CASE_RECEIPT、CASE_EVENT，各自原schema必须不存在、固定已批准过期自编数据/两事务，预期原U107以-20001拒绝且历史完整保留，未知必须返源保锁不重试。不是将EMPTY的实测结论外推给另两例，也不解除正式发行/切换暂停。此为同父VER内后继准入，复用当前工程证据，不新增构建或基础设施。
