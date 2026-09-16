(() => {
  const REQUESTER = "v0-authorized-user";
  const terminal = new Set(["SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED"]);
  const labels = {
    QUEUED: "排队中", PROCESSING: "处理中", SUCCEEDED: "检索完成",
    PARTIAL: "部分完成", FAILED: "检索失败", CANCELLED: "已取消"
  };
  const form = document.querySelector("#patent-search-form");
  const summary = document.querySelector("#patent-task-summary");
  const candidates = document.querySelector("#patent-task-candidates");
  const empty = document.querySelector("#patent-search-empty");
  const history = document.querySelector("#patent-history");
  let currentTaskId = null;
  let pollingToken = 0;

  const element = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  };

  async function request(path, options = {}) {
    const response = await fetch(path, options);
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.message || `请求失败 (${response.status})`);
    return data;
  }

  function headers(extra = {}) {
    return { "X-V0-Requester": REQUESTER, ...extra };
  }

  function scope() {
    const values = name => [...document.querySelectorAll(`[name="${name}"]:checked`)].map(input => input.value);
    const scalar = id => document.querySelector(id).value || null;
    const classification = scalar("#patent-classification");
    const region = scalar("#patent-region");
    return {
      patentTypes: values("patent-type"),
      regions: region ? [region] : [],
      classifications: classification ? [classification.trim()] : [],
      applicationFrom: scalar("#patent-application-from"),
      applicationTo: scalar("#patent-application-to"),
      publicationFrom: scalar("#patent-publication-from"),
      publicationTo: scalar("#patent-publication-to")
    };
  }

  function payload() {
    return {
      inputMode: document.querySelector("#patent-input-mode").value,
      input: document.querySelector("#patent-search-input").value.trim(),
      purpose: document.querySelector("#patent-purpose").value,
      confirmedFeatures: document.querySelector("#patent-features").value.split(/[，,]/).map(value => value.trim()).filter(Boolean),
      scope: scope()
    };
  }

  function setBusy(value) {
    form.querySelectorAll("button, input, select, textarea").forEach(control => { control.disabled = value; });
    form.setAttribute("aria-busy", String(value));
  }

  function renderTask(task) {
    currentTaskId = task.taskId;
    empty.hidden = true;
    summary.hidden = false;
    summary.replaceChildren();
    const banner = element("div", `task-banner ${["SUCCEEDED", "PARTIAL"].includes(task.status) ? "success" : task.status === "FAILED" ? "failed" : ""}`);
    const text = element("div");
    text.append(element("h2", "", labels[task.status] || task.status),
      element("p", "", `任务 ${task.taskId} · 第 ${task.attempt} 次执行 · ${task.scoringVersion}`));
    const actions = element("div", "task-actions");
    if (["QUEUED", "PROCESSING"].includes(task.status)) {
      const cancel = element("button", "secondary-button", "取消任务");
      cancel.type = "button";
      cancel.dataset.patentAction = "cancel";
      actions.append(cancel);
    }
    if (task.status === "FAILED") {
      const retry = element("button", "secondary-button", "重试任务");
      retry.type = "button";
      retry.dataset.patentAction = "retry";
      actions.append(retry);
    }
    banner.append(text, actions);
    summary.append(banner);
    const warning = element("p", "synthetic-warning", "当前结果来自 120 条拟真合成专利文献，仅用于功能验证。不得作为真实检索、法律判断或 Recall@K 证据。");
    summary.append(warning);
    const details = element("dl", "summary-grid");
    [
      ["数据模式", task.dataMode], ["输入方式", task.inputSnapshot.inputMode],
      ["原始输入", task.inputSnapshot.input], ["确认特征", (task.inputSnapshot.confirmedFeatures || []).join("、") || "自动提取"],
      ["实际范围", task.actualScope || "等待执行"], ["候选数量", String(task.candidates?.length || 0)],
      ["追踪号", task.traceId], ["限制", task.limitation || "-"]
    ].forEach(([term, value]) => {
      const row = element("div"); row.append(element("dt", "", term), element("dd", "", value)); details.append(row);
    });
    summary.append(details);
    renderCandidates(task.candidates || []);
  }

  function renderCandidates(values) {
    candidates.hidden = false;
    candidates.replaceChildren();
    const heading = element("div", "section-heading");
    heading.append(element("h2", "", "候选文献"), element("span", "", `${values.length} 条`));
    candidates.append(heading);
    if (!values.length) {
      candidates.append(element("p", "muted-value", "当前实际范围内没有候选；不等于不存在相关技术。"));
      return;
    }
    const grid = element("div", "patent-candidate-grid");
    values.forEach(value => {
      const card = element("article", "patent-candidate");
      const head = element("header");
      const title = element("div");
      title.append(element("h3", "", value.title), element("p", "candidate-meta", `${value.sourceId} · ${value.publicationNumber} · ${value.patentType} · ${value.version} · ${value.location}`));
      head.append(title, element("span", "patent-score", `${value.score} · ${value.relevanceLabel}`));
      card.append(head, element("p", "candidate-excerpt", value.matchingExcerpt || "无可展示片段"));
      const chips = element("div", "feature-chips");
      (value.matchedFeatures || []).forEach(feature => chips.append(element("span", "", feature)));
      card.append(chips);
      grid.append(card);
    });
    candidates.append(grid);
  }

  async function poll(taskId) {
    const token = ++pollingToken;
    for (let count = 0; count < 60 && token === pollingToken; count += 1) {
      const task = await request(`/api/v0/patent-search-tasks/${encodeURIComponent(taskId)}`, { headers: headers() });
      renderTask(task);
      if (terminal.has(task.status)) { await loadHistory(); return; }
      await new Promise(resolve => setTimeout(resolve, 150));
    }
  }

  async function loadHistory() {
    const tasks = await request("/api/v0/patent-search-tasks", { headers: headers() });
    history.replaceChildren();
    tasks.slice(0, 8).forEach(task => {
      const button = element("button", "history-button");
      button.type = "button";
      button.dataset.patentTask = task.taskId;
      button.append(element("strong", "", `${labels[task.status] || task.status} · ${task.inputSnapshot.input}`),
        element("span", "", `${task.candidates.length} 条候选 · 第 ${task.attempt} 次执行`));
      history.append(button);
    });
    if (!tasks.length) history.append(element("p", "muted-value", "暂无历史任务"));
  }

  form.addEventListener("submit", async event => {
    event.preventDefault();
    const body = payload();
    if (!body.input) return;
    setBusy(true);
    try {
      const task = await request("/api/v0/patent-search-tasks", {
        method: "POST",
        headers: headers({ "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() }),
        body: JSON.stringify(body)
      });
      renderTask(task);
      await poll(task.taskId);
    } catch (error) {
      window.alert(error.message);
    } finally {
      setBusy(false);
    }
  });

  summary.addEventListener("click", async event => {
    const action = event.target.closest("[data-patent-action]")?.dataset.patentAction;
    if (!action || !currentTaskId) return;
    try {
      const task = await request(`/api/v0/patent-search-tasks/${encodeURIComponent(currentTaskId)}/${action}`, {
        method: "POST", headers: headers()
      });
      renderTask(task);
      if (!terminal.has(task.status)) await poll(task.taskId); else await loadHistory();
    } catch (error) { window.alert(error.message); }
  });

  history.addEventListener("click", async event => {
    const taskId = event.target.closest("[data-patent-task]")?.dataset.patentTask;
    if (!taskId) return;
    renderTask(await request(`/api/v0/patent-search-tasks/${encodeURIComponent(taskId)}`, { headers: headers() }));
  });

  document.querySelector("#load-patent-history").addEventListener("click", () => loadHistory().catch(error => window.alert(error.message)));
  document.querySelector('[data-workspace="patent-search"]').addEventListener("click", () => loadHistory().catch(error => window.alert(error.message)));
  document.querySelector("#patent-input-mode").addEventListener("change", event => {
    const input = document.querySelector("#patent-search-input");
    if (event.target.value === "PATENT_NUMBER") input.value = "SYN202010001";
  });
})();
