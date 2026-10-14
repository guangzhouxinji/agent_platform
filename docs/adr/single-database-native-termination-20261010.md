# 原生保护命令有界终止的限定技术决定

日期：2026-10-10（Asia/Shanghai）。Task：FIX-SINGLE-DB-NATIVE-EXPORT-TERMINATION-01。仅共享工程缺陷，不改变业务/权限/资料/窗口；状态仍仅docs/08。

## 1. 来源、R与依赖

当前用户明确：裁决真实client/runtime依赖不可解除时立即选择已批准整体保护/独立恢复剩余工程；原有“先绕开/并行/技术优化”人类来源沿quality-gate-order34/human-source-records。开发责任方Codex已核前置FIX-SINGLE-DB-LIFECYCLE-DEADLINE-01及ARC-AGENT-SINGLE-DB-ENGINEERING-D-01完成。已有IMP-SINGLE-DB-NATIVE-FENCE-01/RESTORE限定D及有限超时失败关闭合同不重开。源级具体缺口见[独立有界审计](../evidence/single-database-installation-20261009/approved-execution/adjudication-entry35/native-remaining-engineering-audit.json)：export缺restore已有timeout -k 5；两个宿主command只发TERM并等待close，忽略TERM/close缺失时可永远pending。不是声称真实DM8已故障。原始源码与原失败反例固定于native-termination36。

## 2. 限定D与失败反演

- nativeExportInvocation只为既有timeout增加-k 5，与既有恢复端一致。原schema、账号、密钥stdin、GRANTS/DROP/输出名/未知结果禁止重试/实际lease与窗口核验保持不变，不删partial/日志。
- 原native模块command就地补软终止→最多5秒后强制SIGKILL→最终拒绝，最后拒绝不再依赖close事件。仅持有本次spawn返回的子进程对象，不枚举/杀宿主其他PID、Docker角色或服务；不新增外部控制平台。生产timeout边界不放宽。恢复端复用这一有限实现，保留原错误分类及秘密不进公共失败。
- timeout、超大stdout、流错误均失败关闭；一旦触发失败，后续exit0不可反转为成功。stdout临时缓冲及失败bytes清零，晚到数据不留存；error/close/kill失败竞态只结算一次，清全部timer。spawn不可用直接拒绝，不回显stderr或stdin。
- shell timeout控制本次native进程组，宿主层兜底控制本次docker客户端/自有子进程；即使已请求KILL也不虚称服务器真实退出或数据无副作用，失败仍按既有未知/partial责任处理，不能盲重发。
- 纯替身强制close缺失、TERM忽略和错误竞态；真实无DB自有Node子进程正常/超时正反可执行。前后治理按规范，原20错误/143跳过保留。本轮不运行dexp/dimp、不持真实锁/连接、不停写/重启固定25、不加角色/身份或模型。

## 3. 准入与出口

此为已过单库工程D的有界失败恢复细化，输入/输出/负责进程/状态/资源和拒绝路径已闭合，无新增法律/业务/权限决定，限定技术R/D通过后实施。新增任务仅依赖已完成deadline/工程D，不依赖受阻的裁决client或普通成员。退出：先保存原超时悬置/无-k反例，再有限修复、所有新增正反与原native/export/restore受影响专项、必要治理/源码SHA；只标命令终止工程完成，不冒完整原生保护恢复或A/B/C。