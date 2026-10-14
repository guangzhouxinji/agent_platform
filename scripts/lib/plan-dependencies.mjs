/** Strict master-plan dependency contract. No substring matching, shorthand or implicit gate approval. */
export const completedStatuses = new Set(["已完成", "已验证", "已验收", "跳过"]);
const taskId = /^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+$/;
const gateId = /^GATE:[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+$/;

export function parseDependencies(value, knownIds, { allowGates = false, notes = "" } = {}) {
  if (value === "-") return { ids: [], gates: [] };
  if (typeof value !== "string" || !value.trim()) throw new Error("DEPENDENCY_EMPTY: use - for no dependency");
  const ids = [], gates = [], seen = new Set();
  for (const token of value.split("、")) {
    if (token !== token.trim() || !token || seen.has(token)) throw new Error(`DEPENDENCY_INVALID_OR_DUPLICATE:${token}`);
    seen.add(token);
    if (gateId.test(token)) {
      if (!allowGates) throw new Error(`DEPENDENCY_GATE_NOT_ALLOWED:${token}`);
      const marker = `${token}=`;
      const at = notes.indexOf(marker);
      if (at < 0 || !notes.slice(at + marker.length).split("；")[0].trim()) throw new Error(`DEPENDENCY_GATE_UNDOCUMENTED:${token}`);
      gates.push({ id: token, satisfied: false, condition: notes.slice(at + marker.length).split("；")[0].trim() });
    } else if (taskId.test(token) && knownIds.has(token)) ids.push(token);
    else throw new Error(`DEPENDENCY_UNRESOLVED:${token}`);
  }
  return { ids, gates };
}

export function parseTaskRows(markdown) {
  const start = markdown.indexOf("#### 7.8.3"), end = markdown.indexOf("#### 7.8.4", start + 1);
  if (start < 0 || end < 0) throw new Error("TASK_TABLE_MISSING");
  const lines = markdown.slice(start, end).split(/\r?\n/).filter(line => line.trim().startsWith("|"));
  const rows = [];
  for (const line of lines) {
    if (!line.trim().endsWith("|")) throw new Error("TASK_TABLE_UNTERMINATED_ROW");
    const cells = line.trim().slice(1, -1).split("|").map(cell => cell.trim());
    if (cells.every(cell => /^-+:?$/.test(cell))) continue;
    rows.push(cells);
  }
  const [headers, ...values] = rows;
  if (!headers || headers[0] !== "taskId") throw new Error("TASK_TABLE_HEADER_INVALID");
  const seen = new Set();
  return values.map(cells => {
    if (cells.length !== headers.length || !taskId.test(cells[0]) || seen.has(cells[0])) throw new Error(`TASK_TABLE_INVALID_ROW:${cells[0]}`);
    seen.add(cells[0]);
    return Object.fromEntries(headers.map((header, index) => [header, cells[index]]));
  });
}

export function executionEligibility(task, taskMap, stageSatisfied) {
  const unfinished = task.predecessorIds.filter(id => !completedStatuses.has(taskMap.get(id)?.status));
  const gates = task.dependencyGates ?? [];
  const admitted = unfinished.length === 0 && gates.length === 0 && stageSatisfied;
  const state = completedStatuses.has(task.status) ? "DONE"
    : task.status === "阻塞" ? "BLOCKED"
    : task.status === "进行中" && admitted ? "RUNNING"
    : ["未开始", "准备中", "就绪"].includes(task.status) && admitted ? "READY" : "WAITING";
  return { state, unfinished, gates, admitted };
}

/** Only predecessor declarations create hard edges; successors are navigation, not inferred approvals. */
export function reverseDependencies(tasks) {
  const edges = new Map(tasks.map(task => [task.id, new Set()]));
  for (const task of tasks) for (const id of task.predecessorIds) {
    if (!edges.has(id)) throw new Error(`DEPENDENCY_UNKNOWN_TASK:${id}`);
    edges.get(id).add(task.id);
  }
  return edges;
}
