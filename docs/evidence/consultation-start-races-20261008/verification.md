# 咨询启动竞态工程验证

任务：FIX-CON-START-HANDOFF-RACE-01；父任务VER-CON-USER-START-MTLS-01仍PARTIAL。既有启动D的技术修正，不产生新的来源、模型或部署批准。

## 已实现的行为

- bindAttempt的历史对账合同不再兼任新HTTP发送准入。claim/execute前按当前human COMPARE、材料和只读持久交接围栏重核Task/Input/版本/取消代数/命令期限/ACTIVE委托及当前授权版本；已取得attempt时还核ACTIVE槽、token及lease。
- claim后已过命令/租约窗口，保留原键/attempt/UNKNOWN，不发送execute或换键；取消/版本/委托/围栏漂移同样拒绝下一动作。
- 迟到HTTP回执闭合前重核actor及材料；GET绑定、回执闭合与返回前重核READ及Input。取消坐标不读取失效Goal；GET不新增COMPARE要求，不重新claim/execute/model。
- 本地重核不持锁跨HTTP；重核之后发生的竞态仍由既有Worker领取、模型和原子提交的现时围栏约束，不承诺撤回已发外呼。

## 实际证据与范围

final-specialized-exit.json真实退出0；specialized-result.json来自当前三份XML：75/0失败/0错误/0跳过。新file-H2测试46例使用真实Commands/Application、独立文件库、事务/锁、实际关闭后重开及逐表前后快照；Input/Task directory/权限/委托发放/Transport/结果Reader是明确端口double，不算真实Issuer/Source/mTLS整链。新测试未插入COMMITTED/report/receipt/invocation行；不可信迟到回执只是恶意端口响应，不能当实际计算结果。既有真实传输层专项另保留在first-specialized-exit.json，覆盖范围不扩大。

最初file-H2每个短连接关闭重开，专项约10分钟。之后仅维持本测试独占连接并WRITE_DELAY=0，真实重开用例明确关闭最后连接；保持同样断言/持久文件，不改产品行为、不改成内存数据库、不筛选或skip，最后专项约45秒。原慢执行原件保留。

## 实际集成出口

源码已固定，受管自有RBC10 fresh schema/volume/owner marker（2CPU/2GiB，loopback5497）经现有guard与新inspection核验后，运行未筛选完整后端。RBC04—09不重用，不停止原服务或其它环境，不删除卷。全量已退出0，411 XML实际2169/0失败/0错误/129条件跳过，源码前后SHA相同；generic后端工程构建退出0，不是后继发行。RBC10实际已停止，marker已消费且卷保留，下一全量不得重用10。治理以verify-after-exit原件为准。

## 未证明

CLAIMS确切映射及Source准入仍NOT_GRANTED；无新真实模型预算/外呼、无正式新Task或浏览器验收、无专业计算结论、无部署/现行迁移/单库切换。真实dimp许可缺项独立保持门禁。

## 结构治理失败及当前源码最终工程出口

上文RBC10记录保持为拆分前原件。verify-after实际退出1：Commands330/竞态测试503行分别超过300/400硬限，不能预判治理通过。按事务SQL发送围栏、纯回执文本校验和file-H2生命周期fixture职责拆分，保留全部原46用例，不压行/不跳过。拆分后三套专项75/0/0/0，原件refactor-specialized-result.json及其复制XML。

verify-refactor实际退出1，仅TEST_EVIDENCE_STALE:project；结构已通过，未把旧RBC10冒当前源码。新独占RBC11经受限资源/所有权/guard/inspection核验，未筛选全量2169/0失败/0错误/129条件跳过、411 XML，源码前后SHA相同。全部XML逐份复制并复算；refactor-backend-build-exit.json退出0，仅工程构建。verify-integrated退出0，47文件/41需求基线未变；状态修改后治理以verify-final-exit.json为准。原失败日志保留，包括第一次inspection误用不存在manifest文件名的只读失败。

RBC11确切容器ac09effd0ac62f2b930a268ef601159a18018f42ab18b0e2e1186955b6858ef9已停，marker已消费，卷及凭据引用保留；RBC04—11均不得重放。原咨询实例只读inspect仍running、RestartCount0；本批不启停现行服务，不连接或改变现行库，固定A字节及SHA不变。没有模型外呼、正式新Task、CLAIMS正向映射或新dimp。

真实Issuer/Source/mTLS父任务仍PARTIAL；新竞态端口doubles只证明产品应用与持久存储工程。原生恢复消费者前序工程出口已继承，不重复实施或开启过期包，真实DM8三域整包导出/恢复仍缺确切现时数据/文件/目标/DDL及完整writer原件。裁决03B当前D明确03C才做新页面，不从结构READY推定专业/人工职责已决定。
