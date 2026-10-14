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
const names = ['after-run-workspace.json', 'native-run-read.json', 'native-answer-read.json'];
const report = {
  task: 'VER-CON-AGENT-NATIVE-POSITIVE-01', observedAt: new Date().toISOString(),
  scope: 'OFFLINE_CURRENT_BINDING_FUNCTION_REAL_RETAINED_RESULT_COMPATIBILITY',
  source: { path: path.relative(root, source).replaceAll('\\', '/'), sha256: sha(sourceBytes) },
  inputs: [], checks: [], newProductRequests: 0, newModelCalls: 0, newDatabaseCalls: 0,
  browserE2e: false, professionalAcceptance: false, businessAcceptance: false,
  typeCheckOrFrontendBuild: false,
  method: 'Node stripTypeScriptTypes on exact current source; import and execute in isolated OS temporary directory; no reimplementation of binding logic. Negative probes clone real input only; they are not positive product results.',
};
const reportPath = path.join(directory, 'frontend-result-compatibility.json');
const markdownPath = path.join(directory, 'frontend-result-compatibility.md');
function save() {
  fs.writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n');
  const rows = report.checks.map(c => `| ${c.id} | ${c.expected} | ${c.actual} | ${c.passed ? 'PASS' : 'FAIL'} |`);
  fs.writeFileSync(markdownPath, [
    '# 本轮前端真实结果离线兼容验证', '',
    `- 任务：\`${report.task}\`；时间：${report.observedAt}。`,
    `- 实际结论：**${report.status}**。`,
    '- 仅读取本轮三个保存原件；正向直接调用当前 bindConsultationResult。负例仅变更内存克隆，不覆盖真实输入。',
    '- Node 内置类型擦除后执行原函数；独立 OS 临时目录，无共用 dist、构建输出或前端源码修改；不等于 TypeScript 类型检查。',
    '- 新业务 HTTP / 模型 / DB 调用均为 0；未运行 verify-all、全仓回归或浏览器。',
    `- 当前源码 SHA256：\`${report.source.sha256}\`。`,
    ...(report.identity ? [`- 真实 Task：\`${report.identity.taskId}\`；Run：\`${report.identity.runId}\`；Turn：\`${report.identity.turnId}\`；本页 ${report.identity.members} 个成员 / ${report.identity.citations} 个回答引用。`] : []),
    '', '| 检查 | 预期 | 实际 | 结果 |', '|---|---|---|---|', ...rows,
    '', '## 范围与未完成项', ...report.limitations.map(s => `- ${s}`), '',
    '文件 SHA、逐项检查及不变性验证见同名前缀 JSON；不输出回答、凭据或来源原文。', '',
  ].join('\n'));
  console.log(JSON.stringify({ status: report.status, checks: report.checks.length,
    passed: report.checks.filter(c => c.passed).length,
    failed: report.checks.filter(c => !c.passed).map(c => c.id),
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
    reject('wrong-nonempty-run-id-only', r => { r.checkpoint.runId = other(r.checkpoint.runId); });
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
      'wrong-nonempty-run-id-only 实测是否拒绝见检查表。函数无 expectedRunId 入参，Workspace/Turn 无可比 Run ID；不能把上游 AgentTaskPanel 的请求 Run ID 防护当作本函数已经验证。',
      'AgentTaskPanel 源码有 expectedRunId 与响应 Run ID 比较；本切片不运行其 UI/异步请求链，所以不证明端到端错误 Run 拒绝，也不由本函数结果推断真实跨 Run 数据泄露。',
      '真实原件正向绑定仅证明工程离线数据兼容；不核实当前权限、浏览器渲染/交互、专业相关性、模型质量、生产或业务验收。',
      'Run checkpoint.version 是运行进度版本，不等于 Task/文档/来源版本；不发明其必须相等的规则。本脚本验证 Task 版本及引用 documentVersion/sourceVersion。',
      '引用负例是由真实输入构造的内存篡改探针，非真实产品请求或另一个真实 Run；未创建新业务结果。',
      '不修改主计划/前端；函数级 Run ID 缺口需主会话决定后续处理。本报告不关闭任务或调整门禁。',
    ];
  } catch (error) {
    report.checks.push({ id: 'offline-execution', expected: 'SUCCESS', actual: error.name, passed: false });
    report.limitations = ['离线执行异常；没有冒充正向通过。', '请检查脚本执行环境与输入结构；没有发起产品请求。'];
  } finally {
    if (fs.existsSync(modulePath)) fs.unlinkSync(modulePath);
    fs.rmdirSync(temp);
    report.temporaryOutputRemoved = true;
    const unchanged = report.inputs.every(i => sha(fs.readFileSync(path.join(directory, i.name))) === i.sha256) && sha(fs.readFileSync(source)) === report.source.sha256;
    report.checks.push({ id: 'source-and-input-bytes-unchanged', expected: 'UNCHANGED', actual: unchanged ? 'UNCHANGED' : 'CHANGED', passed: unchanged });
  }
  report.status = report.checks.every(c => c.passed) ? 'PASSED_OFFLINE_ONLY' : 'FAILED_COMPATIBILITY_EXPECTATIONS';
  save();
  if (report.status !== 'PASSED_OFFLINE_ONLY') process.exitCode = 1;
}
