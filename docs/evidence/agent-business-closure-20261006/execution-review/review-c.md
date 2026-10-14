# 独立边界审查 C：IMP-AGENT-REQUEST-EXECUTION-01

日期：2026-10-06（Asia/Shanghai）。本记录只保存本次审查事实，不是新的计划或状态账本。仅主会话维护 docs/08。

## 交付与执行边界

唯一新增 Java 文件：`backend/apps/platform-api/src/test/java/cn/xinji/agentplatform/runtime/AuthorizedAgentExecutionBoundaryTest.java`，6 个边界测试，136 行。未修改已有产品、测试、master 或配置；未运行共享 Maven，未启动数据库、未签发身份或修改授权。测试夹具使用 DefaultListableBeanFactory（与主会话已修复的夹具一致）。Java/Maven 编译及运行结果由主会话统一提供，本审查不声明这些新测试已通过。

审查依据：docs/08 第0节及7.8.3任务行；docs/01、docs/09；docs/adr/agent-runtime-product-binding-20261005.md 最新“三域任务结果交付限定技术D”及“正式请求内连续执行”；docs/adr/consultation-model-invocation-fence-20261006.md；docs/69、docs/54、docs/71 的相关现行合同。

## 结论

截至本次所读源码，**没有确认可复现的 P0/P1 产品漏洞**，不以未跑测试宣称安全验收。主会话报告 focused26 二轮通过及旧 BeanFactory 夹具失败留存，该结果由主会话负责留证，不是本审查独立执行结果。

1. AuthorizedAgentExecution 在 read、advance、最终 project 前调用同一原始 token 的 JwtDecoder；三域 execute 回调将重新解码的 Jwt 传入原 read/advance。正式 decoder 继续签名/issuer/audience 与现时 session/subject/key/独立水位检查。没有发现凭据持久化或新后台线程。
2. 三域重新构造原领域 context。咨询核当前Goal/Input/scope/来源与权限；裁决核输入版本、现时访问和来源；执法核 PREPARE/READ、文书输入、来源及期限。最终 read 不直接复用早先返回的领域 View。域内提交的原有前后授权/来源检查仍不能被机械 decode 替代。
3. STOP 不再 advance；512 转换上限仅投影当前检查点；中断失败不伪造完成。恢复应读取同一已持久化 Run，不把 STOP 变回运行，不授予旧权限。
4. 前端依 epoch 丢弃跨scope旧请求结果及异常；已存在跨任务迟到响应专项，主会话新增 execute 单请求、不确定结果先读取和跨scope父结果清空专项，因此本审查不重复添加前端测试。

## 必须保留的实质风险与不外推边界

### R1：Checkpoint CAS 不等于工具调用独占（领域幂等/围栏不能删除）

AgentRuntime.act 在 pending 已存在时先调用 Skills.invoke，再提交 checkpoint CAS；两个并发 execute 可同时到达同一个 invocation。CAS 只让一个检查点提交成功，不能阻止两个请求触达工具。因此每个有副作用工具必须使用同一 invocation 或已批准等价业务键的原领域幂等回执和围栏；禁止将本次连续循环改为新的随机业务键。所读咨询检索复用 input/worker 键，解释复用 invocation 派生键；执法程序/草稿、裁决命令沿既有调用键及领域版本合同。没有据此确认重复产品副作用，但此不变量是放行条件。

新增并发测试故意证明“invoke 两次、同键业务替身只产生一次效果、CAS输家不得project、STOP重放不再invoke”；它使用内存 CAS 和幂等替身，**不证明三域真实DM8/模型/外部系统 exactly-once**。若真实领域工具缺少上述围栏/幂等，应阻断该域发布，而不是在共享循环中吞掉冲突后伪造成功。

### R2：机械有界不等于强制取消长调用

512 上限和 Thread.isInterrupted 只在转换边界执行。它们不会主动中断正在进行的同步工具/数据库/HTTP调用，也不保证浏览器断连使Servlet线程中断。当前限定D承诺请求内转换上限与边界中断，并未批准持久后台执行或强制用户取消协议，故不把此判作已确认D违约。运维/产品说明不得宣称“关浏览器立即停止”或“整个execute请求严格在某时限返回”；长调用超时依各既有适配器，恢复必须沿原未知提交围栏，不能另发随机键。

### R3：工程绿色不解除正式来源/身份和部署门禁

现有 mock JwtDecoder 的执行器测试、Standalone MockMvc参数注入及新内存并发测试不证明正式发行JWT、真实Key/Session撤回、独立水位故障或三域真实来源撤回链。正式执行分支仍受docs/08现时准入与部署前置限制。本审查不扩大原来源许可，不将历史过期Grant/Source复活。需要主会话保留正式安全专项、真实数据库/HTTPS工程证据与业务验收之间的分层。

## 新增六个测试（不重复原专项）

- 回调使用每次实际 decode 返回的不同 Jwt，不偷用请求最初 principal。
- 已提交一次转换后JWT撤回：下次read及最终project均不可进入，已提交状态不伪装回滚。
- STOP后的最终领域project发现来源撤回：拒绝返回保留检查点。
- 缺身份/缺Decoder：领域callback均不可调用。
- 持久化pending后请求中断：保留中断标志、无结果投影；重新进入同一Run沿同一pending ID恢复到NEED_HUMAN。
- 两个execute同时重放同一pending：真实AgentRuntime + 内存CAS/幂等领域替身，输家抛冲突且不project；成功STOP后重复execute无工具调用。

## 校验记录

修改前执行 `node scripts/verify-all.mjs`：退出1，原需求47文件/41契约基线一致，末尾报告“1 test evidence stage(s) remain failed / NOT green”。不把结构校验当法律/业务正确性，未改要求基线或校验标准。修改后按同命令复核，完整输出另存本目录 verify-after.log 和 verify-after-exit.json。该命令不是 Maven；它不能代替主会话对新增测试的编译运行。