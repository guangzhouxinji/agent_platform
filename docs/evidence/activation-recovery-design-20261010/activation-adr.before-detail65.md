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
