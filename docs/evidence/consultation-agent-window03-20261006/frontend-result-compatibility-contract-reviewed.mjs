import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { stripTypeScriptTypes } from 'node:module';
import { isDeepStrictEqual } from 'node:util';

// Offline evidence only. No HTTP, model, DB, build, or repository-wide test commands.
const directory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(directory, '../../..');
const source = path.join(root, 'frontend/src/features/consultation/product/agentResultHandoff.ts');
const sourceBytes = fs.readFileSync(source);
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const preservedNames = ['frontend-result-compatibility.mjs', 'frontend-result-compatibility.json', 'frontend-result-compatibility.md'];
const preserved = preservedNames.map(name => ({ name, sha256: sha(fs.readFileSync(path.join(directory, name))) }));
const reviewedPaths = ['frontend/src/features/agent/AgentTaskPanel.tsx', 'frontend/src/features/consultation/product/ConsultationProductPage.tsx'];
const reviewedSources = reviewedPaths.map(relativePath => {
  const bytes = fs.readFileSync(path.join(root, relativePath));
  return { path: relativePath, sha256: sha(bytes), lines: bytes.toString('utf8').split(/\r?\n/) };
});
const excerpt = (index, start, end) => ({ path: reviewedSources[index].path, sha256: reviewedSources[index].sha256, start, end, sourceLines: reviewedSources[index].lines.slice(start - 1, end).map((text, i) => ({ line: start + i, text })) });
const names = ['after-run-workspace.json', 'native-run-read.json', 'native-answer-read.json'];
const report = {
  task: 'VER-CON-AGENT-NATIVE-POSITIVE-01', observedAt: new Date().toISOString(),
  scope: 'OFFLINE_CURRENT_BINDING_FUNCTION_REAL_RETAINED_RESULT_COMPATIBILITY',
  source: { path: path.relative(root, source).replaceAll('\\', '/'), sha256: sha(sourceBytes) },
  firstEvidencePreserved: preserved,
  staticContractReview: {
    method: 'READ_ONLY_SOURCE_REVIEW_NOT_UI_EXECUTION', runtimeVerified: false,
    sources: reviewedSources.map(({lines, ...metadata}) => metadata),
    findings: [
      { ...excerpt(0, 11, 26), conclusion: 'execute clears handoff before await; stale epoch returns; nonempty Run and expected Task checked; when expectedRunId supplied, mismatched Run throws before onView(result). Scope changes/unmount increment epoch. This is static review only.' },
      { ...excerpt(0, 28, 28), conclusion: 'create/start passes no expectedRunId because Run ID does not yet exist; retains creation request key/body for reconciliation. Response goes through execute nonempty Run and expected Task checks. No client-side proof of server idempotency or runtime create behavior.' },
      { ...excerpt(0, 34, 37), conclusion: 'Editing Run ID clears onView; read passes requested runId, advance and execute pass checkpoint.runId as expectedRunId; URL and validation use same captured ID. All go through execute.' },
      { ...excerpt(1, 56, 71), conclusion: 'Actual callback is onAgentView, not onRunChange. It increments handoffEpoch and clears prior result display. agentRequest returns request data to panel; it does not directly set agentView or bind results.' },
      { ...excerpt(1, 73, 86), conclusion: 'readAgentResult captures previous validated checkpoint; rereads live Run by previous.runId and Workspace; epoch/marker checks after each await; binding is computed at line 81, then line 82 independently checks Task, Run ID, checkpoint version and nonnull Turn before any apply/setTurns at line 85. Thus live is not literally prevalidated at function call, but mismatched data cannot reach this display commit through this branch in the reviewed source.' },
      { ...excerpt(1, 134, 134), conclusion: 'AgentTaskPanel receives expectedTaskId=taskId, scope containing token/task/input, request=agentRequest, onView=onAgentView. No alternate onRunChange callback found in current file.' },
    ],
    conclusion: 'No concrete request-Run mismatch bypass into result display identified in these reviewed entry points. This is not runtime/asynchronous-chain or browser E2E proof. Function lacks request context by contract; isolated changed-runId probe is not a function responsibility violation.',
  },
  endToEndPassed: false,
  inputs: [], checks: [], newProductRequests: 0, newModelCalls: 0, newDatabaseCalls: 0,
  browserE2e: false, professionalAcceptance: false, businessAcceptance: false,
  typeCheckOrFrontendBuild: false,
  method: 'Node stripTypeScriptTypes on exact current source; import and execute in isolated OS temporary directory; no reimplementation of binding logic. Negative probes clone real input only; they are not positive product results.',
};
const reportPath = path.join(directory, 'frontend-result-compatibility-contract-reviewed.json');
const markdownPath = path.join(directory, 'frontend-result-compatibility-contract-reviewed.md');
function save() {
  if (fs.existsSync(reportPath) || fs.existsSync(markdownPath)) throw new Error('Refusing to overwrite existing reviewed report; retain original and use a new report suffix for subsequent runs');
  fs.writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n');
  const rows = report.checks.map(c => `| ${c.id} | ${c.expected} | ${c.actual} | ${c.classification ?? (c.passed ? 'PASS' : 'FAIL')} |`);
  fs.writeFileSync(markdownPath, [
    '# 本轮前端真实结果离线兼容验证（两层合同复审）', '',
    `- 任务：\`${report.task}\`；时间：${report.observedAt}。`,
    `- 实际结论：**${report.status}**。`,
    '- 仅读取本轮三个保存原件；正向直接调用当前 bindConsultationResult。负例仅变更内存克隆，不覆盖真实输入。',
    '- Node 内置类型擦除后执行原函数；独立 OS 临时目录，无共用 dist、构建输出或前端源码修改；不等于 TypeScript 类型检查。',
    '- 新业务 HTTP / 模型 / DB 调用均为 0；未运行 verify-all、全仓回归或浏览器。',
    `- 当前源码 SHA256：\`${report.source.sha256}\`。`,
    ...(report.identity ? [`- 真实 Task：\`${report.identity.taskId}\`；Run：\`${report.identity.runId}\`；Turn：\`${report.identity.turnId}\`；本页 ${report.identity.members} 个成员 / ${report.identity.citations} 个回答引用。`] : []),
    '', '| 检查 | 预期 | 实际 | 结果 |', '|---|---|---|---|', ...rows,
    '', '## 两层合同只读审查（不是运行 UI）',
    ...report.staticContractReview.sources.map(source => '- 源码：' + source.path + '；SHA256：' + source.sha256),
    ...report.staticContractReview.findings.flatMap(f => ['','### ' + f.path + ':' + f.start + '-' + f.end, f.conclusion, '```text', ...f.sourceLines.map(l => l.line + ': ' + l.text), '```']),
    '', report.staticContractReview.conclusion,
    '', '## 第一次原件保留', ...report.firstEvidencePreserved.map(f => '- ' + f.name + '；SHA256：' + f.sha256),
    '', '## 范围与未完成项', ...report.limitations.map(s => `- ${s}`), '',
    '文件 SHA、逐项检查及不变性验证见同名前缀 JSON；不输出回答、凭据或来源原文。', '',
  ].join('\n'));
  console.log(JSON.stringify({ status: report.status, checks: report.checks.length,
    passed: report.checks.filter(c => c.passed).length,
    nonResponsibleProbes: report.checks.filter(c => c.classification === 'NOT_FUNCTION_RESPONSIBILITY').length,
    failed: report.checks.filter(c => c.passed === false).map(c => c.id),
    files: [reportPath, markdownPath] }, null, 2));
}
const missing = names.filter(n => !fs.existsSync(path.join(directory, n)));
if (missing.length) {
  report.status = 'PENDING_REAL_INPUT';
  report.limitations = ['真实原件尚未齐全：' + missing.join(', '), '未伪造正向结果；输入齐全后重跑本脚本。', '不是浏览器 E2E、当前授权或业务/专业验收。'];
  save(); process.exitCode = 2;
} else {
  const original = names.map(name => {
    const bytes = fs.readFileSync(path.join(directory, name));
    report.inputs.push({ name, bytes: bytes.length, sha256: sha(bytes) });
    return JSON.parse(bytes.toString('utf8'));
  });
  const [workspace, run, answer] = original;
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'consultation-result-compatibility-'));
  const modulePath = path.join(temp, 'agentResultHandoff.mjs');
  try {
    const js = stripTypeScriptTypes(sourceBytes.toString('utf8'), { mode: 'strip' });
    // The current module must remain standalone after its type-only imports are erased.
    if (/^\s*(?:import\s|export\s.*\sfrom\s)/m.test(js)) throw new Error('Unexpected runtime import in binding source');
    fs.writeFileSync(modulePath, js);
    report.isolatedExecution = { directory: temp, sharedFrontendOutputTouched: false, emittedModuleSha256: sha(Buffer.from(js)) };
    const { bindConsultationResult: bind } = await import(pathToFileURL(modulePath).href);
    const check = (id, expected, actual, passed) => report.checks.push({ id, expected, actual, passed });
    const positive = bind(run, workspace);
    check('real-current-task-run-result-binds', 'BOUND_REAL_TURN', positive ? 'BOUND_REAL_TURN' : 'REJECTED', positive !== null && isDeepStrictEqual(positive, answer));
    check('native-answer-equals-workspace-turn', 'EXACT_NATIVE_ANSWER', 'compared', workspace.dialogue.filter(t => isDeepStrictEqual(t, answer)).length === 1);
    check('real-run-task-input-result-chain', 'CONSISTENT', 'compared', run.checkpoint.task.id === workspace.task.id && run.checkpoint.task.goal.description === 'confirmed-input:' + workspace.goal.inputId && answer.taskId === workspace.task.id && answer.resultSetId === workspace.result.resultSetId && answer.resultSha === workspace.result.resultSha);
    report.identity = { taskId: workspace.task.id, runId: run.checkpoint.runId, turnId: answer.turnId, members: workspace.result.members.length, citations: answer.citations.length };
    const turnOf = w => w.dialogue.find(t => t.turnId === answer.turnId);
    const reject = (id, mutate) => {
      const r = structuredClone(run), w = structuredClone(workspace);
      mutate(r, w, turnOf(w));
      const value = bind(r, w);
      check(id, 'REJECTED_NULL', value === null ? 'REJECTED_NULL' : 'BOUND_TURN', value === null);
    };
    const other = value => value + '-offline-negative-probe';
    reject('wrong-runtime-task', r => { r.checkpoint.task.id = other(r.checkpoint.task.id); });
    reject('wrong-workspace-task', (r,w) => { w.task.id = other(w.task.id); });
    reject('wrong-answer-task', (r,w,t) => { t.taskId = other(t.taskId); });
    reject('missing-run-id', r => { r.checkpoint.runId = ''; });
    const changedRun = structuredClone(run);
    changedRun.checkpoint.runId = other(changedRun.checkpoint.runId);
    const changedRunResult = bind(changedRun, structuredClone(workspace));
    report.checks.push({ id: 'wrong-nonempty-run-id-only', expected: 'OBSERVE_ONLY_NOT_FUNCTION_RESPONSIBILITY', actual: changedRunResult === null ? 'REJECTED_NULL' : 'BOUND_TURN_NOT_REJECTED', passed: null, classification: 'NOT_FUNCTION_RESPONSIBILITY', owner: 'AgentTaskPanel request-response identity checks and ConsultationProductPage pre-display handoff checks', endToEndVerified: false });
    reject('wrong-confirmed-input', r => { r.checkpoint.task.goal.description += '-other'; });
    reject('wrong-goal-task-version', (r,w) => { w.goal.taskVersion += 1; });
    reject('wrong-answer-task-version', (r,w,t) => { t.taskVersion += 1; });
    reject('wrong-workspace-task-version', (r,w) => { w.task.version += 1; });
    reject('wrong-result-set', (r,w,t) => { t.resultSetId = other(t.resultSetId); });
    reject('wrong-result-sha', (r,w,t) => { t.resultSha = other(t.resultSha); });
    reject('wrong-terminal-answer-reference', r => { r.checkpoint.outcome.reference += '-other'; });
    reject('nonterminal-run', r => { r.checkpoint.phase = 'ACT'; });
    reject('duplicate-answer-observation', r => { r.checkpoint.observations.push(structuredClone(r.checkpoint.observations.find(o => o.reference.startsWith('answer-turn:')))); });
    reject('duplicate-answer-turn', (r,w,t) => { w.dialogue.push(structuredClone(t)); });
    reject('duplicate-result-member', (r,w) => { w.result.members.push(structuredClone(w.result.members[0])); });
    reject('duplicate-result-citation', (r,w) => { w.result.citations.push(structuredClone(w.result.citations[0])); });
    reject('duplicate-result-citation-same-count', (r,w) => { w.result.citations[1] = structuredClone(w.result.citations[0]); });
    reject('duplicate-answer-citation', (r,w,t) => { t.citations.push(structuredClone(t.citations[0])); });
    reject('missing-answer-citations', (r,w,t) => { t.citations = []; });
    for (const field of ['memberId','sourceId','documentId','documentVersion','sourceVersion','rawSha','locatorRef','sourceUrl']) {
      reject('wrong-answer-citation-' + field, (r,w,t) => { t.citations[0][field] = other(t.citations[0][field]); });
    }
    reject('wrong-answer-citation-field-locator', (r,w,t) => { t.citations[0].fieldLocators = { ...t.citations[0].fieldLocators, offlineChangedLocator: 'different' }; });
    reject('blank-answer', (r,w,t) => { t.output.answer = '   '; });
    reject('missing-retrieval-observation', r => { r.checkpoint.observations = r.checkpoint.observations.filter(o => !o.reference.startsWith('retrieval:')); });
    reject('missing-citations-observation', r => { r.checkpoint.observations = r.checkpoint.observations.filter(o => !o.reference.startsWith('retrieval-citations:')); });
    report.limitations = [
      '首次报告的函数级错 Run 失败归因经两层合同复审更正；首次原件字节保留，新报告不覆盖。此修正不改变探针实际未拒绝的事实。',
      '35 项负责范围内离线检查（包括真实绑定、答案/链一致性和字节不变性）独立计数；仅改非空 Run ID 的 1 项记为函数非负责人观察，不是 PASS 或 FAIL，也不证明上游运行防护。',
      '创建请求无预先 Run ID，不能要求其与不存在的 expectedRunId 比较；服务端创建幂等与恢复不在离线验证范围。',
      'readAgentResult 在 bind 调用之后、显示提交之前检查 live Run ID 和 checkpoint 版本；不能把该计算次序描述为 live 在函数入参前已完成所有比较。当前静态入口未发现错 Run 绕过显示提交的具体路径。',
      '没有运行 AgentTaskPanel 或 ConsultationProductPage 的异步/UI 链；静态代码审查不等于运行验证或浏览器 E2E，endToEndPassed=false。',
      'Task 版本与引用 documentVersion/sourceVersion 在函数负例中验证；checkpoint.version 的请求快照一致性属于页面交接校验，仅静态审查。',
      '负例仅由真实输入的内存克隆构造，非另一个真实 Run；不核实当前授权、模型质量、专业或业务验收。不修改任何产品源码/主计划，不建议机械添加冗余参数。',
    ];
  } catch (error) {
    report.checks.push({ id: 'offline-execution', expected: 'SUCCESS', actual: error.name, passed: false });
    report.limitations = ['离线执行异常；没有冒充正向通过。', '请检查脚本执行环境与输入结构；没有发起产品请求。'];
  } finally {
    if (fs.existsSync(modulePath)) fs.unlinkSync(modulePath);
    fs.rmdirSync(temp);
    report.temporaryOutputRemoved = true;
    report.firstEvidenceBytesUnchanged = preserved.every(p => sha(fs.readFileSync(path.join(directory, p.name))) === p.sha256);
    report.reviewedSourceBytesUnchanged = reviewedSources.every(p => sha(fs.readFileSync(path.join(root, p.path))) === p.sha256);
    const unchanged = report.inputs.every(i => sha(fs.readFileSync(path.join(directory, i.name))) === i.sha256) && sha(fs.readFileSync(source)) === report.source.sha256;
    report.checks.push({ id: 'source-and-input-bytes-unchanged', expected: 'UNCHANGED', actual: unchanged ? 'UNCHANGED' : 'CHANGED', passed: unchanged });
  }
  report.responsibleCheckSummary = { total: report.checks.filter(c => c.passed !== null).length, passed: report.checks.filter(c => c.passed === true).length, failed: report.checks.filter(c => c.passed === false).length, nonResponsibleProbes: report.checks.filter(c => c.passed === null).length };
  report.status = report.checks.every(c => c.passed !== false) && report.firstEvidenceBytesUnchanged && report.reviewedSourceBytesUnchanged ? 'PASSED_RESPONSIBLE_OFFLINE_CHECKS_ONLY_NOT_E2E' : 'FAILED_OFFLINE_CHECKS_OR_EVIDENCE_CHANGED';
  save();
  if (report.status !== 'PASSED_RESPONSIBLE_OFFLINE_CHECKS_ONLY_NOT_E2E') process.exitCode = 1;
}
