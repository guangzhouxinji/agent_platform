# 本轮前端真实结果离线兼容验证（两层合同复审）

- 任务：`VER-CON-AGENT-NATIVE-POSITIVE-01`；时间：2026-10-06T13:37:57.094Z。
- 实际结论：**PASSED_RESPONSIBLE_OFFLINE_CHECKS_ONLY_NOT_E2E**。
- 仅读取本轮三个保存原件；正向直接调用当前 bindConsultationResult。负例仅变更内存克隆，不覆盖真实输入。
- Node 内置类型擦除后执行原函数；独立 OS 临时目录，无共用 dist、构建输出或前端源码修改；不等于 TypeScript 类型检查。
- 新业务 HTTP / 模型 / DB 调用均为 0；未运行 verify-all、全仓回归或浏览器。
- 当前源码 SHA256：`64cfcf2a574d363cd1243aead2268b349f4ecdcd586627a57a496eeba11eaad6`。
- 真实 Task：`c91aa8d3-0716-4e33-a6b5-2a89461cb1c8`；Run：`afa57612-ab59-3807-9a98-f7e48c704a7a`；Turn：`4f4f6f11-0208-4bc0-b13e-5efe4a4a0b4e`；本页 20 个成员 / 20 个回答引用。

| 检查 | 预期 | 实际 | 结果 |
|---|---|---|---|
| real-current-task-run-result-binds | BOUND_REAL_TURN | BOUND_REAL_TURN | PASS |
| native-answer-equals-workspace-turn | EXACT_NATIVE_ANSWER | compared | PASS |
| real-run-task-input-result-chain | CONSISTENT | compared | PASS |
| wrong-runtime-task | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-workspace-task | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-task | REJECTED_NULL | REJECTED_NULL | PASS |
| missing-run-id | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-nonempty-run-id-only | OBSERVE_ONLY_NOT_FUNCTION_RESPONSIBILITY | BOUND_TURN_NOT_REJECTED | NOT_FUNCTION_RESPONSIBILITY |
| wrong-confirmed-input | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-goal-task-version | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-task-version | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-workspace-task-version | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-result-set | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-result-sha | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-terminal-answer-reference | REJECTED_NULL | REJECTED_NULL | PASS |
| nonterminal-run | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-answer-observation | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-answer-turn | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-result-member | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-result-citation | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-result-citation-same-count | REJECTED_NULL | REJECTED_NULL | PASS |
| duplicate-answer-citation | REJECTED_NULL | REJECTED_NULL | PASS |
| missing-answer-citations | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-memberId | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-sourceId | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-documentId | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-documentVersion | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-sourceVersion | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-rawSha | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-locatorRef | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-sourceUrl | REJECTED_NULL | REJECTED_NULL | PASS |
| wrong-answer-citation-field-locator | REJECTED_NULL | REJECTED_NULL | PASS |
| blank-answer | REJECTED_NULL | REJECTED_NULL | PASS |
| missing-retrieval-observation | REJECTED_NULL | REJECTED_NULL | PASS |
| missing-citations-observation | REJECTED_NULL | REJECTED_NULL | PASS |
| source-and-input-bytes-unchanged | UNCHANGED | UNCHANGED | PASS |

## 两层合同只读审查（不是运行 UI）
- 源码：frontend/src/features/agent/AgentTaskPanel.tsx；SHA256：6e90dbf5f72f4f480c7eb895314fef49e42608324242f2251f53b5be7095d9e7
- 源码：frontend/src/features/consultation/product/ConsultationProductPage.tsx；SHA256：b5bfd2e35c9cc8e298f27ca48f9459da58be55f5b2d2a14effb7af1086dfab12

### frontend/src/features/agent/AgentTaskPanel.tsx:11-26
execute clears handoff before await; stale epoch returns; nonempty Run and expected Task checked; when expectedRunId supplied, mismatched Run throws before onView(result). Scope changes/unmount increment epoch. This is static review only.
```text
11:  useEffect(()=>{epoch.current++;onView?.(null);setView(null);setRunId("");setError("");setBusy(false);setUncertain(false);creation.current=null;return()=>{epoch.current++}},[scope,expectedTaskId]);
12:  async function execute(work:()=>Promise<AgentRunView>,advanced=false,expectedRunId?:string){
13:   const current=++epoch.current;setBusy(true);setError("");onView?.(null);
14:   try{const result=await work();if(current!==epoch.current)return;
15:    if(!result?.checkpoint?.runId||(expectedRunId!==undefined&&result.checkpoint.runId!==expectedRunId)
16:      ||(expectedTaskId!==undefined&&result.checkpoint.task?.id!==expectedTaskId)){
17:     throw Object.assign(new Error("运行响应与当前任务或请求的运行编号不一致。已清空结果，请重新读取当前任务和运行。"),{status:409,responseMismatch:true});
18:    }
19:    setView(result);setRunId(result.checkpoint.runId);setUncertain(false);creation.current=null;onView?.(result);if(advanced)onAdvanced?.();}
20:   catch(cause){if(current!==epoch.current)return;const status=(cause as {status?:number}).status;setView(null);
21:    if((cause as {responseMismatch?:boolean}).responseMismatch&&creation.current){setUncertain(true);}
22:    else if(status===401||status===403){setView(null);setRunId("");creation.current=null;setUncertain(false);onDenied();}
23:    else if(status===409||status===404){setView(null);creation.current=null;setUncertain(false);}
24:    else {setUncertain(true);}
25:    setError(cause instanceof Error?cause.message:"运行请求失败");
26:   }finally{if(current===epoch.current)setBusy(false);}
```

### frontend/src/features/agent/AgentTaskPanel.tsx:28-28
create/start passes no expectedRunId because Run ID does not yet exist; retains creation request key/body for reconciliation. Response goes through execute nonempty Run and expected Task checks. No client-side proof of server idempotency or runtime create behavior.
```text
28:  function start(){if(startInput===undefined)return;const packet=creation.current??{key:crypto.randomUUID(),body:structuredClone(startInput)};creation.current=packet;void execute(()=>request<AgentRunView>("",packet.body,packet.key));}
```

### frontend/src/features/agent/AgentTaskPanel.tsx:34-37
Editing Run ID clears onView; read passes requested runId, advance and execute pass checkpoint.runId as expectedRunId; URL and validation use same captured ID. All go through execute.
```text
34:    <label>运行编号<input value={runId} disabled={disabled||busy||!!creation.current} onChange={event=>{setRunId(event.target.value);setView(null);onView?.(null)}} autoComplete="off"/></label>
35:    <button disabled={disabled||busy||!runId||!!creation.current} onClick={()=>void execute(()=>request<AgentRunView>("/"+encodeURIComponent(runId)),false,runId)}>读取当前运行</button>
36:    <button disabled={disabled||busy||!checkpoint||stopped||uncertain} onClick={()=>void execute(()=>request<AgentRunView>("/"+encodeURIComponent(checkpoint!.runId)+"/advance",{},crypto.randomUUID()),true,checkpoint!.runId)}>执行下一步</button>
37:    <button disabled={disabled||busy||!checkpoint||stopped||uncertain} onClick={()=>void execute(()=>request<AgentRunView>("/"+encodeURIComponent(checkpoint!.runId)+"/execute",{},crypto.randomUUID()),true,checkpoint!.runId)}>执行至当前出口</button>
```

### frontend/src/features/consultation/product/ConsultationProductPage.tsx:56-71
Actual callback is onAgentView, not onRunChange. It increments handoffEpoch and clears prior result display. agentRequest returns request data to panel; it does not directly set agentView or bind results.
```text
56:   function onAgentView(view:AgentRunView|null){
57:     handoffEpoch.current++;setAgentView(view);
58:     if(view||agentView){setPage(null);setTurns([]);setSelected([]);setNotice("");}
59:   }
60:   function invalidateEditedHandoff(){
61:     if(agentView){handoffEpoch.current++;setAgentView(null);setPage(null);setTurns([]);setSelected([]);setNotice("");}
62:   }
63:   async function agentRequest<T>(suffix:string,body?:unknown,key?:string):Promise<T>{
64:     if(!token)throw new Error("请先登录。");
65:     setPage(null);setTurns([]);setSelected([]);setNotice("");
66:     const current=epoch.current;
67:     try{return await request<T>(token,path(taskId)+"/agent-runs"+suffix,body,key);}
68:     catch(failure){
69:       if(current===epoch.current&&failure instanceof ConsultationError&&failure.status===401){setToken(null);forgetLogin();}
70:       throw failure;
71:     }
```

### frontend/src/features/consultation/product/ConsultationProductPage.tsx:73-86
readAgentResult captures previous validated checkpoint; rereads live Run by previous.runId and Workspace; epoch/marker checks after each await; binding is computed at line 81, then line 82 independently checks Task, Run ID, checkpoint version and nonnull Turn before any apply/setTurns at line 85. Thus live is not literally prevalidated at function call, but mismatched data cannot reach this display commit through this branch in the reviewed source.
```text
73:   async function readAgentResult(){
74:     if(!token||!agentView||!workspace?.goal)return;
75:     const current=epoch.current, marker=++handoffEpoch.current, previous=agentView.checkpoint;
76:     setPage(null);setTurns([]);setSelected([]);
77:     const live=await request<AgentRunView>(token,path(taskId)+"/agent-runs/"+encodeURIComponent(previous.runId));
78:     if(current!==epoch.current||marker!==handoffEpoch.current)return;
79:     const data=await request<Workspace>(token,path(taskId)+"/retrieval-workspace");
80:     if(current!==epoch.current||marker!==handoffEpoch.current)return;
81:     const turn=bindConsultationResult(live,data);
82:     if(live.checkpoint.task.id!==taskId||data.task.id!==taskId||live.checkpoint.runId!==previous.runId||live.checkpoint.version!==previous.version||!turn){
83:       setAgentView(null);throw new Error("运行、目标或结果已变化，请重新读取当前任务和运行后核对。");
84:     }
85:     apply(data,taskId);setTurns([turn]);setAgentView(live);
86:     setNotice(live.checkpoint.outcome?.reason==="NEED_HUMAN"?"已读取本次运行的回答与来源；来源覆盖不完整，仍需人工核查。":"已读取本次运行的回答、检索结果与来源；不构成专业或法律结论。");
```

### frontend/src/features/consultation/product/ConsultationProductPage.tsx:134-134
AgentTaskPanel receives expectedTaskId=taskId, scope containing token/task/input, request=agentRequest, onView=onAgentView. No alternate onRunChange callback found in current file.
```text
134:       {token&&workspace?.goal&&<AgentTaskPanel expectedTaskId={taskId} scope={token+":"+taskId+":"+workspace.goal.inputId} disabled={busy} startInput={{expectedTaskVersion:workspace.task.version}} request={agentRequest} onView={onAgentView} onDenied={()=>{clear();setError("当前会话或任务权限已不可用，已清空结果。请重新读取任务并核对。");}}/>
```

No concrete request-Run mismatch bypass into result display identified in these reviewed entry points. This is not runtime/asynchronous-chain or browser E2E proof. Function lacks request context by contract; isolated changed-runId probe is not a function responsibility violation.

## 第一次原件保留
- frontend-result-compatibility.mjs；SHA256：94e1bcad07ca9254da47cdb4644d35d087138b50630e5dbcd3456ab137e40b66
- frontend-result-compatibility.json；SHA256：9fc29f6126b163d46404d809a7eace77284ef82a07f47e75577765f5e706dd8d
- frontend-result-compatibility.md；SHA256：ed9eee892b39a3e3c3992ed07edb205002b928cdfd61c2d67c069c0f62e70be8

## 范围与未完成项
- 首次报告的函数级错 Run 失败归因经两层合同复审更正；首次原件字节保留，新报告不覆盖。此修正不改变探针实际未拒绝的事实。
- 35 项负责范围内离线检查（包括真实绑定、答案/链一致性和字节不变性）独立计数；仅改非空 Run ID 的 1 项记为函数非负责人观察，不是 PASS 或 FAIL，也不证明上游运行防护。
- 创建请求无预先 Run ID，不能要求其与不存在的 expectedRunId 比较；服务端创建幂等与恢复不在离线验证范围。
- readAgentResult 在 bind 调用之后、显示提交之前检查 live Run ID 和 checkpoint 版本；不能把该计算次序描述为 live 在函数入参前已完成所有比较。当前静态入口未发现错 Run 绕过显示提交的具体路径。
- 没有运行 AgentTaskPanel 或 ConsultationProductPage 的异步/UI 链；静态代码审查不等于运行验证或浏览器 E2E，endToEndPassed=false。
- Task 版本与引用 documentVersion/sourceVersion 在函数负例中验证；checkpoint.version 的请求快照一致性属于页面交接校验，仅静态审查。
- 负例仅由真实输入的内存克隆构造，非另一个真实 Run；不核实当前授权、模型质量、专业或业务验收。不修改任何产品源码/主计划，不建议机械添加冗余参数。

文件 SHA、逐项检查及不变性验证见同名前缀 JSON；不输出回答、凭据或来源原文。
