# V10.20／ADR §8 间接消费专项最终复验

当前机器回执：../current-receipt.json。只说明本次独立专项，不维护主计划状态，不声称全量、真实历史或验收完成。

## 源码已冻结

- 新增 ProductHistoricalIndirectConsumptionTest.java：299行，SHA-256 `1c0e2bbb31c77f0cd33073a7e86f8cad407081a6d5f34a1867dd1921d7a90ad7`。与attempt-06实际编译输入逐字节相同；没有在复验后改测试。
- 主会话修复的Workspace／Tools／Controller及本次依赖源码、主计划V10.20、历史隔离ADR §8已做只读冻结快照，pin见frozen-files.json。生成最终receipt时现行文件仍全部与冻结SHA一致，见frozen-source-checked.json。
- 本执行者没有修改生产、主计划、ADR、db或仓库scripts；复验后的操作只读取保存的自有工程原件并追加证据说明。共享Runtime未因本专项改变。

## 独立编译／实跑

attempt-06 javac exit=0，278类；Jupiter专项19成功、0失败、0跳过、exit=0。原始javac/JUnit日志、参数、编译类pin分别在../attempt-06和build-class-digests.json。仅选择本新增测试类；没有Maven／Reactor／全量／shared target写入。

## 双库全表、SHA与原结果不变

19个场景各有完整before/after原件，两份JSON逐字节一致；覆盖自有业务file-H2的全部PUBLIC表和独立授权file-H2的全部PUBLIC表（AUTHORITY.*）。both-databases-unchanged.json逐项列组合SHA以及分别规范化的主库／授权库SHA、表数量，before=after。

23条跨场景result_set记录的stored result_sha逐项before=after，见result-sha-unchanged.json；成员、字段证据、选择、回答与独立授权高水位也包含在上述双库全表证据中。坏摘要测试在准备期故意制造f*64，检测期间该坏SHA仍未变化；不把故障设置误写成修改真实原件或通过摘要验证。

## 七个实际持久Run均未推进

run-no-advance.json直接读取本次保存的head/revision原行，而非用测试名称推定。

| 场景 | head version | revision数量 |
|---|---|---|
| 窗口外精确answer-turn缺证 | 1→1 | 2→2 |
| 精确retrieval缺证，当前另有合法result | 1→1 | 2→2 |
| 精确retrieval-citations缺证，当前另有合法result | 1→1 | 2→2 |
| 仅READ回调读取闭合依赖，无WRITE | 1→1 | 2→2 |
| 仅READ回调核缺证依赖，无WRITE | 1→1 | 2→2 |
| 当前result缺证、restart/advance拒绝 | 0→0 | 1→1 |
| READ撤权先于历史缺证、restart/advance拒绝 | 0→0 | 1→1 |

所有head整行及加密revision/ciphertext/nonce/payload SHA均相同。期间新增revision=0、新STOP/ERROR revision=0。缺证案例通过真实store.load回调在进入推进前抛Unverified，reason=HISTORICAL_RESULT_UNVERIFIED；不再接受先返回STOP/ERROR再检查code。

## 引用变体与仅READ覆盖

- retrieval:、retrieval-citations:、answer-turn:三个明确协议均经真实领域requireDependencies，走已有Search.read或Dialogue.read/JDBC隔离。当前合法新result及最近三条合法PROPOSAL不能遮住Run旧缺证坐标。
- 只READ合法反例先通过真实Grant/Watermark撤销WRITE，并断言Tools.current(seed)确实404；同一身份通过READ CurrentExecution callback复用requireDependencies读取闭合retrieval-citations仍成功，不擅增WRITE。
- 只READ缺证反例在READ有效／WRITE撤销／当前合法新result条件下，callback必须得到Unverified+CODE而非WRITE不足404。该案例也未推进Run、未派发模型或创建invocation。
- 原Dialogue／Workspace／解释重放、现时READ/WRITE与Source拒绝先行、硬摘要损坏和无关合法工程数据可用反例继续通过。

## 旧红灯仍完整保留

原attempt-05、旧junit-summary.json、原verification.md、原handoff.json、旧冻结清单和Runtime实际推进/泛化STOP原件未覆盖。其14成功/2失败仍是当时真实结论。新预推进Unverified／稳定CODE／双库与Run全不变是对同一故障关闭合同的加强，不是删assert换绿。当前结论仅在新current-receipt.json及本目录记录。

## 范围限制与交接

工程自有file-H2／真实产品JDBC与持久化checkpoint，Source/内容投影仍是工程边界double，Jwt为自有已解析身份fixture。不是Controller HTTP／来源权利／原文文献字节／OAuth/TLS／专业模型全链。未知协议、其它Runtime阶段和真实三历史样本仍未单独验证；真实历史样本=0。没有当前DB、真实历史三数据或保护包读取。

RBC09未消费，全量由主会话独立启动；本专项不竞争其构建输出或DB，不把用户的DM8六schema/36表锁实证记作本次执行。不再修改backend/db/scripts或重新编译／跑专项；接收方可直接用已冻结测试进入其全量。
