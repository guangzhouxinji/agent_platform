(() => {
  const REQUESTER = "v0-authorized-user";
  const terminal = new Set(["SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED"]);
  const labels = { QUEUED: "排队中", PROCESSING: "处理中", SUCCEEDED: "检索完成", PARTIAL: "部分完成", FAILED: "检索失败", CANCELLED: "已取消" };
  const form = document.querySelector("#trademark-search-form");
  const summary = document.querySelector("#trademark-task-summary");
  const candidates = document.querySelector("#trademark-task-candidates");
  const empty = document.querySelector("#trademark-search-empty");
  const history = document.querySelector("#trademark-history");
  let currentTaskId = null;
  let pollingToken = 0;

  const element = (tag, className, value) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (value !== undefined) node.textContent = value;
    return node;
  };
  const values = id => document.querySelector(id).value.split(/[，,]/).map(value => value.trim()).filter(Boolean);
  const selected = name => [...document.querySelectorAll(`[name="${name}"]:checked`)].map(input => input.value);

  async function request(path, options = {}) {
    const response = await fetch(path, options);
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || `请求失败 (${response.status})`);
    return body;
  }
  function headers(extra = {}) { return { "X-V0-Requester": REQUESTER, ...extra }; }
  function payload() {
    const status = document.querySelector("#trademark-status").value;
    const region = document.querySelector("#trademark-region").value;
    return {
      inputMode: "TEXT", name: document.querySelector("#trademark-name").value.trim(),
      categories: values("#trademark-categories"), goodsServices: values("#trademark-goods-services"),
      dimensions: selected("trademark-dimension"),
      scope: { statuses: status ? [status] : [], regions: region ? [region] : [], dataSources: [] }
    };
  }
  function setBusy(value) {
    form.querySelectorAll("button, input, select").forEach(control => { control.disabled = value; });
    form.setAttribute("aria-busy", String(value));
  }
  function renderTask(task) {
    currentTaskId = task.taskId;
    empty.hidden = true;
    summary.hidden = false;
    summary.replaceChildren();
    const banner = element("div", `task-banner ${["SUCCEEDED", "PARTIAL"].includes(task.status) ? "success" : task.status === "FAILED" ? "failed" : ""}`);
    const text = element("div");
    text.append(element("h2", "", labels[task.status] || task.status), element("p", "", `任务 ${task.taskId} · 第 ${task.attempt} 次执行 · ${task.rankingVersion}`));
    const actions = element("div", "task-actions");
    if (["QUEUED", "PROCESSING"].includes(task.status)) {
      const button = element("button", "secondary-button", "取消任务"); button.type = "button"; button.dataset.trademarkAction = "cancel"; actions.append(button);
    }
    if (task.status === "FAILED") {
      const button = element("button", "secondary-button", "重试任务"); button.type = "button"; button.dataset.trademarkAction = "retry"; actions.append(button);
    }
    banner.append(text, actions);
    summary.append(banner, element("p", "synthetic-warning", "当前结果来自 120 条拟真合成商标记录，仅用于功能验证。不代表真实商标库、近似结论、注册成功率或 Recall@K。"));
    const details = element("dl", "summary-grid");
    [
      ["数据模式", task.dataMode], ["商标名称", task.inputSnapshot.originalName],
      ["标准化名称", task.inputSnapshot.normalizedName], ["类别", task.inputSnapshot.categories.join("、")],
      ["商品/服务", task.inputSnapshot.goodsServices.join("、")], ["实际范围", task.actualScope || "等待执行"],
      ["候选总数", String(task.totalCandidates)], ["返回数量", String(task.candidates?.length || 0)],
      ["限制", task.limitation || "-"], ["追踪号", task.traceId]
    ].forEach(([term, value]) => { const row = element("div"); row.append(element("dt", "", term), element("dd", "", value)); details.append(row); });
    summary.append(details);
    renderCandidates(task.candidates || []);
  }
  function renderCandidates(items) {
    candidates.hidden = false;
    candidates.replaceChildren();
    const heading = element("div", "section-heading"); heading.append(element("h2", "", "候选商标"), element("span", "", `${items.length} 条`)); candidates.append(heading);
    if (!items.length) { candidates.append(element("p", "muted-value", "当前确认范围内没有候选；不等于不存在相同或近似商标。")); return; }
    const grid = element("div", "patent-candidate-grid");
    items.forEach(item => {
      const card = element("article", "patent-candidate trademark-candidate");
      const head = element("header");
      const title = element("div");
      title.append(element("h3", "", item.name), element("p", "candidate-meta", `${item.sourceId} · ${item.registrationNumber} · ${item.categories.join("、")} 类 · ${item.status} · ${item.sourceVersion}`));
      head.append(title, element("span", "patent-score", item.relevanceLabel));
      card.append(head, element("p", "candidate-excerpt", `商品/服务：${item.goodsServices.join("、")}`));
      const dimensions = element("div", "dimension-list");
      item.dimensions.forEach(dimension => dimensions.append(element("span", `dimension-${dimension.state.toLowerCase()}`, `${dimension.dimension} · ${dimension.state === "EXECUTED" ? "已执行" : "未分析"}：${dimension.basis}`)));
      card.append(dimensions);
      grid.append(card);
    });
    candidates.append(grid);
  }
  async function poll(taskId) {
    const token = ++pollingToken;
    for (let count = 0; count < 60 && token === pollingToken; count += 1) {
      const task = await request(`/api/v0/trademark-search-tasks/${encodeURIComponent(taskId)}`, { headers: headers() });
      renderTask(task);
      if (terminal.has(task.status)) { await loadHistory(); return; }
      await new Promise(resolve => setTimeout(resolve, 150));
    }
  }
  async function loadHistory() {
    const tasks = await request("/api/v0/trademark-search-tasks", { headers: headers() });
    history.replaceChildren();
    tasks.slice(0, 8).forEach(task => {
      const button = element("button", "history-button"); button.type = "button"; button.dataset.trademarkTask = task.taskId;
      button.append(element("strong", "", `${labels[task.status] || task.status} · ${task.inputSnapshot.originalName}`), element("span", "", `${task.candidates.length} 条候选 · 第 ${task.attempt} 次执行`));
      history.append(button);
    });
    if (!tasks.length) history.append(element("p", "muted-value", "暂无历史任务"));
  }
  form.addEventListener("submit", async event => {
    event.preventDefault();
    const body = payload();
    if (!body.name || !body.categories.length || !body.goodsServices.length || !body.dimensions.length) return;
    setBusy(true);
    try {
      const task = await request("/api/v0/trademark-search-tasks", { method: "POST", headers: headers({ "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() }), body: JSON.stringify(body) });
      renderTask(task); await poll(task.taskId);
    } catch (error) { window.alert(error.message); } finally { setBusy(false); }
  });
  summary.addEventListener("click", async event => {
    const action = event.target.closest("[data-trademark-action]")?.dataset.trademarkAction;
    if (!action || !currentTaskId) return;
    try {
      const task = await request(`/api/v0/trademark-search-tasks/${encodeURIComponent(currentTaskId)}/${action}`, { method: "POST", headers: headers() });
      renderTask(task); if (!terminal.has(task.status)) await poll(task.taskId); else await loadHistory();
    } catch (error) { window.alert(error.message); }
  });
  history.addEventListener("click", async event => {
    const taskId = event.target.closest("[data-trademark-task]")?.dataset.trademarkTask;
    if (taskId) renderTask(await request(`/api/v0/trademark-search-tasks/${encodeURIComponent(taskId)}`, { headers: headers() }));
  });
  document.querySelector("#load-trademark-history").addEventListener("click", () => loadHistory().catch(error => window.alert(error.message)));
  document.querySelector('[data-workspace="trademark-search"]').addEventListener("click", () => loadHistory().catch(error => window.alert(error.message)));
})();
