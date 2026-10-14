from pathlib import Path
import json,hashlib
r=Path.cwd(); e=r/'docs/evidence/activation-recovery-design-20261010'; e.mkdir(exist_ok=True)
paths=['docs/08-总开发实现计划.md','docs/02-待确认事项清单.md','docs/adr/product-activation-expiry-recovery-20261010.md','docs/adr/single-database-recovery-assessment-20261007.md']
for name in paths:
 p=r/name; backup=e/(p.name+'.before-second-review62'); assert not backup.exists(); backup.write_bytes(p.read_bytes())
p=r/paths[3]; s=p.read_text(encoding='utf-8'); s+='''

### 9.27.9 62固定版本回收规则有界原文复核

2026-10-10，责任Codex；只复用60b安装安全手册印刷49—52页（object-authority-page-0060/0061、permission-page-0062/0063）及既有语法提取，不新搜索/盘点。例1/2仅SYSDBA回收其自身授出的权限，例3的owner回收/级联/多路径链起点也都是owner自授，未提供owner回收SYSDBA授出权限的例证或跨grantor匹配规则。因此8.1.4.170/mode0/本次674无转授权集合的同语句REVOKE影响范围仍不能由原文闭合。主计划将该D证据化阻塞，owner=Codex技术执行者，出口仅当前版本官方明确规则或另行详细D、最小精确写集/可逆退出及确切已导入writer锁准入的受控实测。本轮不登记或执行DB实验，不借未知进程放行，不重import/改权限/扩大比较规则。替代路线执行已有咨询恢复D二遍复核，不关闭单库父A/B/C。
'''; p.write_text(s,encoding='utf-8')
p=r/paths[2]; s=p.read_text(encoding='utf-8').replace('本文件是该任务的详细设计草案','本文件是该任务的详细设计（第7节为二遍限定冻结）').replace('当前不得据本草案编写业务API或迁移。','实施仅依docs08登记任务及第7节边界；本文不批准运行身份写入或部署。').replace('**候选选择：只持久化非秘密收据，不可再次取回一次值。** 这是需在D复核中特别判定的技术取舍，不标记为用户已确认的事实。','**限定技术选择：只持久化非秘密收据，不可再次取回一次值。** 第7节冻结，不伪称用户原话或秘密托管授权。')
a=s.index('## 6. D出口与后续实现准入'); s=s[:a]+'''## 6. D出口与后续实现准入

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
'''; p.write_text(s,encoding='utf-8')
p=r/paths[1]; s=p.read_text(encoding='utf-8'); idx=s.index('\nCodex负责的最小技术出口：',s.index('Q-CON-NORMAL-ACTIVATION-RECOVERY-01')); s=s[:idx]+'''\n\n62二遍限定技术结论（2026-10-10，Codex）：来源docs69 V1 §1.3—1.5、原技术委托、51证据及当前三身份类/V032/V046 SHA，详见激活恢复ADR第7节及second-semantic-review62.json。同subject/原REGISTER actor与当次SUBJECTS、HUMAN过期PENDING/行锁CAS、原TTL、单事务REISSUE+非秘密收据已冻结；同键只历史元数据不再交付，UNKNOWN保原键，真正到期才新键/现时version。HTTP/事务唯一竞争/rollback-only及历史结果不等当前状态有确切合同，当前窄D阻断闭合。安全取舍为开发团队技术决定，不称用户原话/密钥托管批准；原Q未确认描述保留历史，不代表代码/实测/运行身份写入/本人验收。\n'''+s[idx:]; p.write_text(s,encoding='utf-8')
p=r/paths[0]; s=p.read_text(encoding='utf-8').replace('计划版本：V11.20','计划版本：V11.21',1); lines=s.splitlines()
for i,l in enumerate(lines):
 if l.startswith('| ARC-SINGLE-DB-RESTORE-ACTUAL-DELTA-D-01 |'):
  ps=l.split('|'); ps[4]=' 阻塞 '; ps[-2]=' 62原文有界反查：SYSDBA/owner例均自授回收，不证明跨grantor匹配，来源ADR9.27.9。owner=Codex；出口仅固定8.1.4.170/mode0/674无转授权同语句回收明确规则或另D/精确已导入准入实测。本轮不启动实验/猜通过；替代路线执行已有咨询恢复D/源码，父A/B/C不退出 '; lines[i]='|'.join(ps)
 if l.startswith('| ARC-CON-NORMAL-ACTIVATION-RECOVERY-D-01 |'):
  ps=l.split('|'); ps[4]=' 已完成 '; ps[10]=' 2026-10-10 '; ps[11]=' [限定D第7节](adr/product-activation-expiry-recovery-20261010.md)及[二遍语义证据](evidence/activation-recovery-design-20261010/second-semantic-review62.json)，docs02原Q具源/版本/责任/范围结论 '; ps[-2]=' 62限定技术D通过：原actor/当次SUBJECTS、同subject过期HUMAN PENDING/行锁CAS/原TTL、新值同事务事件与非秘密收据；同键不重交付、UNKNOWN保原键、到期才新键，历史结果不称当前状态。27条设计反演不冒实测；接续稳定IMP-CON-NORMAL-ACTIVATION-RECOVERY-01仅源码/专测，不准运行身份写入/部署/本人激活 '; lines[i]='|'.join(ps); insertion=i+1
new='| IMP-CON-NORMAL-ACTIVATION-RECOVERY-01 | 正式同主体过期激活恢复源码与专属组件 | P0/M0.6 | 待开始 | P0 | ARC-CON-NORMAL-ACTIVATION-RECOVERY-D-01 | VER-SINGLE-DB-CON-NORMAL-INPUT-01 | 否 | 按激活恢复ADR第7节实现可信维护链POST、同subject行锁CAS/REISSUE与非秘密持久收据；专测实际加载正逆迁移、边界/未知结果/竞争/维护拒绝；治理与受影响回归完成，组件不替代DM8/发行/本人验收 | - | [限定D](adr/product-activation-expiry-recovery-20261010.md)，序号实施时核末尾 | owner=Codex单一源码/共享治理；两身份类、两新专测、一对正逆迁移及确切受影响fixture/声明；不改维护职责/primary/部署，不运行DB或交付秘密 | D通过后接续源码/专测；运行迁移/固定发行/本人交付另准入，原窗口/资源与源返回不变 |'
lines.insert(insertion,new); p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
sourcepaths=[r/'docs/69-最终产品五项基础协议ADR.md']+[r/'backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product'/f for f in ['ProductSubjects.java','ProductIdentityController.java','ProductMaintenanceProof.java']]+[r/'db/dm8'/f for f in ['V032__product_identity.sql','V046__product_workload_subject.sql']]
obs=['TLS certificate/SUBJECTS reevaluated','authority unavailable stops main writes','original unique REGISTER actor','same issuer/subject','HUMAN only','PENDING only','password absent','digest/expiry exist','server expiry boundary','version/overflow','no grant/source/task','strict body/UUID','one serverNow and deployment TTL','32-byte new secret, old digest invalid','subject/event/receipt atomic','same-key no secret','same-key payload conflict','same-subject key race','cross-subject key race','rollback-only outside handling','event rollback','receipt rollback','UNKNOWN same key','ACTIVE/DISABLED history only','lost response no redelivery/extension','new key only after expiry','undo rejects evidence loss']
record={'taskId':'ARC-CON-NORMAL-ACTIVATION-RECOVERY-D-01','date':'2026-10-10','responsible':'Codex delegated technical design','kind':'DESIGN_REVIEW_NOT_EXECUTED_TESTS','sources':[{'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sourcepaths],'obligations':[{'id':f'CON-RECOVERY-D-{i+1:02}','contract':v,'resolution':'Frozen ADR section7; implementation and actual tests still required'} for i,v in enumerate(obs)],'conclusion':'Bounded D passed; no code/DB validation or runtime admission','preGovernance':'restore61 completed exit1 only existing TESTS_FAILING/STALE,47/41 unchanged','secretPersisted':False,'runtimeSideEffects':False,'initialEvidenceWriterFailure':'first inline Python script SyntaxError before execution; no writes; corrected script not a stream retry'}
(e/'second-semantic-review62.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8'); print('D62_FROZEN; RESTORE_D_BLOCKED; IMPLEMENTATION_REGISTERED_NOT_STARTED')
