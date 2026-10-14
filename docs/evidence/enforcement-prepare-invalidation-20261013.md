# 执法重新准备撤下旧结果：隔离源码与组件证据

任务：FIX-ENF-DRAFT-PREPARE-INVALIDATION-01；父任务：IMP-ENF-DOCUMENT-DRAFT-05；需求：ENF-14。沿用 enforcement-followup-v2-d-20260929.md §6.1 及已完成恢复、会话修复的 D，不扩大业务行为。

用户 2026-10-13 授权执法/裁决隔离独立实现，随后明确要求实施。共享集成 owner、数据库和部署权限未转移。实现位于 D:/daima/xinji/agent_platform_parallel/enf-fix-01，主工作树产品源码未修改。

## 原问题与行为差异

成功准备后再次 prepare，原页面仅在成功回调赋值 prepared。第二次返回409/422后 pending被释放，旧prepared仍存在，生成/重生成按钮继续可用。此前诊断21项中原17项通过、新4项失败；本次页面原始SHA仍为53f23776948da92fc784a3d9b46552d3f4edaed9516bb4f754472c34a1429668，可复用该反例证据。这是前端陈旧结果问题，不证明绕过后端校验。

现在prepare发起前立即setPrepared(null)。原恢复模块继续保存path/body/key；有效成功响应才恢复prepared，409/422拒绝后保持撤下，网络/503或无效响应仍须原键对账。ready=false仍可生成缺项稿或准备候选，不改变审核/导出要求。未改API、后端、恢复helper或共享组件。

## 验证与限制

2026-10-13受影响8文件198项通过、0失败，包含新增重新准备等待态、生成/候选409/422拒绝、网络/503原键恢复、无效prepare响应以及当前ready=false可操作与新manifest hash消费。沿用会话失权/迟到、绑定、导出、回执和读取测试。TypeScript noEmit检查退出0。UI控件/HTTP采用测试替身，仅组件证据，不是真实登录、浏览器、DM8或真人采用。

前置node scripts/verify-all.mjs已自然结束，首阶段2083项、2043通过、38失败、2跳过，之后阶段未执行。32项缺少隔离快照中被排除的历史run/reconcile原件；按源码固定引用和SHA补齐5个原件后，涉及共同输入的定向用例通过。剩余6项涉及历史发布/存储的绝对路径绑定，保留失败，未放宽校验或修改历史原件。前置PowerShell启动包装器未取得顶层ExitCode（记录为null），不得将包装器退出0算成功；verify-all日志明确报告首阶段exit1并停止。新运行器独立记录子进程status/signal/error，修复记录工具不修改产品。

完整后检按相同命令在隔离目录执行，终态见本任务交付目录的post.exit.json及原始stdout/stderr；本段不预判通过。主计划只记已证明的源码/组件层，不标全仓或产品真实I完成。

## 原件位置与下一步

交付原件根：D:/daima/xinji/agent_platform_parallel/01a1334f-enf-adj-20261013。

- enf-snapshot.json：59,894文件、6,132,932,281字节；记录排除目录及逐文件复制摘要，无复制时变化/缺失。不是共享主线全仓冻结声明。
- prepare-diagnostic/prepare-reproduction02.log：未修复页面的4个反例，产品源未变。
- enf-fix-verification/pre.stdout.log、pre.stderr.log、pre.exit.json：首轮全量前检原件，失败及未知退出字段保留。
- enf-fix-verification/snapshot-repair02-inputs.json：5个固定历史引用及摘要；snapshot-repair02.exit.json：输入补齐后的定向验证退出0；第一修复轮缺第二个历史引用的失败另存不覆盖。
- enf-fix-verification/regression.stdout.log、regression.exit.json：198项受影响回归。
- enf-fix-verification/typecheck.exit.json：类型检查退出0。

父交付从“最新prepare失败仍可消费旧结果”推进为“源码已撤下并有组件证据”。完整后检、共享集成及实际输入/部署/真实流分别取证。后检结束后交付两文件精确产品补丁与本领域计划行补丁；集成owner在安全边界核共享源SHA和冲突，不能整体覆盖旧计划。父任务实际案件输入/来源/运行准入仍按原计划，不借修复放行部署或03B/03C。


共享集成检查点（2026-10-13，root）：修改前SHA与交付一致，apply检查通过；git apply将页面原7行LF统一CRLF造成第一SHA比较失败，字节原件见consultation-human-ordinary-20261013/enforcement-git-apply-normalized.txt。首次修复命令cwd错误，在写入前FileNotFoundError；并列28测试通过只覆盖规范换行状态。现已以确切原字节重建唯一setPrepared(null)插入，规范化内容与apply后相同，两文件最终SHA均等于交付afterSHA；不是产品逻辑失败。原完整隔离后检已自然exit1：2083项/2075通过/6失败/2跳过，原路径失败保留，后继治理未运行，覆盖原“待终态”描述。原件D:/daima/xinji/agent_platform_parallel/01a1334f-enf-adj-20261013/enf-fix-verification/post.exit.json及stdout/stderr。共享回归随后单独记录，不部署/真实案件/B。
