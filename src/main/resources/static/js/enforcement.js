(() => {
  const ORGANIZATION = "org-v0";
  let evidenceByClue = {
    "V0-SYN-CLUE-001": [
      ["V0-SYN-EVD-001", "截图记录"],
      ["V0-SYN-EVD-002", "商品照片"],
      ["V0-SYN-EVD-005", "受限证据"],
      ["V0-SYN-EVD-006", "可失效来源"]
    ],
    "V0-SYN-CLUE-002": [
      ["V0-SYN-EVD-003", "时间附件 A"],
      ["V0-SYN-EVD-004", "时间附件 B"],
      ["V0-SYN-EVD-007", "不可解析音视频"]
    ]
  };
  let clueVersions = new Map([
    ["V0-SYN-CLUE-001", "v1"],
    ["V0-SYN-CLUE-002", "v1"]
  ]);

  const panels = [...document.querySelectorAll("[data-workspace-panel]")];
  const navItems = [...document.querySelectorAll("[data-workspace]")];
  const openForm = document.querySelector("#enforcement-open-form");
  const processForm = document.querySelector("#enforcement-process-form");
  const clueSelect = document.querySelector("#enforcement-clue");
  const actorSelect = document.querySelector("#enforcement-actor");
  const evidenceOptions = document.querySelector("#evidence-options");
  const actions = document.querySelector("#enforcement-actions");
  const reasonInput = document.querySelector("#enforcement-reason");
  const status = document.querySelector("#enforcement-status");
  const empty = document.querySelector("#enforcement-empty");
  const sections = {
    summary: document.querySelector("#enforcement-summary"),
    fields: document.querySelector("#enforcement-fields"),
    evidence: document.querySelector("#enforcement-evidence"),
    relations: document.querySelector("#enforcement-relations"),
    analysis: document.querySelector("#enforcement-analysis"),
    history: document.querySelector("#enforcement-history")
  };
  let snapshot = null;
  let actor = actorSelect.value;
  let busy = false;

  const text = value => value === null || value === undefined || value === "" ? "未提供" : String(value);
  const escape = value => text(value).replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
  const key = prefix => `${prefix}-${crypto.randomUUID()}`;
  const latest = values => values?.[values.length - 1];

  function activateWorkspace(name) {
    panels.forEach(panel => { panel.hidden = panel.dataset.workspacePanel !== name; });
    navItems.forEach(item => {
      const active = item.dataset.workspace === name;
      item.classList.toggle("active", active);
      if (active) item.setAttribute("aria-current", "page"); else item.removeAttribute("aria-current");
    });
  }

  function setBusy(value) {
    busy = value;
    document.querySelectorAll("#enforcement-workspace button, #enforcement-workspace select, #enforcement-workspace textarea")
      .forEach(control => { control.disabled = value; });
    document.querySelector("#enforcement-workspace").setAttribute("aria-busy", String(value));
  }

  function showStatus(message, tone = "info", code = "") {
    status.hidden = false;
    status.className = `result-status tone-${tone}`;
    status.innerHTML = `<strong>${escape(message)}</strong>${code ? `<span>${escape(code)}</span>` : ""}`;
  }

  async function request(path, options = {}) {
    const response = await fetch(path, options);
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error(payload.error?.message || "请求未完成");
      error.code = payload.error?.code || `HTTP_${response.status}`;
      error.status = response.status;
      throw error;
    }
    return payload;
  }

  function headers(write = false, reviewer = false) {
    const selectedActor = reviewer ? "v0-enforcement-reviewer" : actorSelect.value;
    const result = { "X-V0-Actor": selectedActor, "X-Request-Id": crypto.randomUUID() };
    if (write) {
      result["Content-Type"] = "application/json";
      result["Idempotency-Key"] = key("workbench");
    }
    return result;
  }

  function renderEvidenceOptions() {
    const items = evidenceByClue[clueSelect.value] || [];
    evidenceOptions.replaceChildren(Object.assign(document.createElement("legend"), { textContent: "合成证据" }));
    items.forEach(([id, label], index) => {
      const wrapper = document.createElement("label");
      wrapper.className = "check-option";
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.name = "evidence";
      checkbox.value = id;
      checkbox.checked = index < 2;
      wrapper.append(checkbox, document.createTextNode(`${label} · ${id}`));
      evidenceOptions.append(wrapper);
    });
  }

  function evidenceLabel(item) {
    if (item.source.sourceId === "V0-SYN-EVD-007") return "不可解析音视频";
    if (item.availability === "SOURCE_UNAVAILABLE") return "来源失效";
    return item.issue || "合成证据";
  }

  async function loadSyntheticCatalog() {
    try {
      const [cluePayload, evidencePayload] = await Promise.all([
        request("/api/v0/synthetic-center/enforcement/clues"),
        request("/api/v0/synthetic-center/enforcement/evidence")
      ]);
      const selected = clueSelect.value;
      clueVersions = new Map(cluePayload.items.map(item => [item.source.sourceId, item.source.version]));
      evidenceByClue = Object.fromEntries(cluePayload.items.map(item => [item.source.sourceId, []]));
      evidencePayload.items.forEach(item => {
        if (evidenceByClue[item.relatedClueId]) evidenceByClue[item.relatedClueId].push([item.source.sourceId, evidenceLabel(item)]);
      });
      clueSelect.replaceChildren(...cluePayload.items.map(item => {
        const option = document.createElement("option");
        option.value = item.source.sourceId;
        const subject = item.subjects?.[0]?.name;
        option.textContent = `${subject || "合成线索"} · ${item.source.sourceId.replace("V0-SYN-", "")}`;
        return option;
      }));
      if (clueVersions.has(selected)) clueSelect.value = selected;
      renderEvidenceOptions();
    } catch (error) {
      showStatus("合成目录读取失败，使用固定回归样例", "warning", "SYNTHETIC_CATALOG_FALLBACK");
    }
  }

  function list(values, renderer = escape) {
    if (!values?.length) return '<p class="muted-value">无</p>';
    return `<ul class="data-list">${values.map(value => `<li>${renderer(value)}</li>`).join("")}</ul>`;
  }

  function sourceButton(source) {
    if (!source?.sourceId) return "";
    return `<button type="button" class="inline-link" data-enforcement-source="${escape(source.sourceId)}">${escape(source.sourceId)} · ${escape(source.version)}</button>`;
  }

  function versionButton(resource, id, version, label) {
    return `<button type="button" class="version-button" data-version-resource="${resource}" data-version-id="${escape(id)}" data-version="${version}">${escape(label)}</button>`;
  }

  function render(current) {
    snapshot = current;
    empty.hidden = true;
    Object.values(sections).forEach(section => { section.hidden = false; });
    const work = current.workItem;
    const clue = latest(current.clueAnalyses);
    const relation = latest(current.relationSets);
    const analysis = latest(current.auxiliaryAnalyses);

    sections.summary.innerHTML = `
      <div class="section-heading"><h2>当前工作项</h2><span class="status-chip">${escape(work.scopeLabel)}</span></div>
      <dl class="summary-grid"><div><dt>工作项</dt><dd>${escape(work.workItemId)}</dd></div><div><dt>组织</dt><dd>${escape(work.organizationRef)}</dd></div><div><dt>线索来源</dt><dd>${sourceButton(work.clueRef)}</dd></div><div><dt>创建时间</dt><dd>${escape(work.createdAt)}</dd></div></dl>`;

    sections.fields.innerHTML = `<div class="section-heading"><h2>线索字段与冲突</h2><span>${clue ? `版本 ${clue.version}` : "待处理"}</span></div>${clue ? `
      <div class="field-columns"><div><h3>主体候选</h3>${list(clue.subjectCandidates, item => `<strong>${escape(item.roleAsStated)}</strong> ${escape(item.name)} ${sourceButton(item.source)}`)}</div>
      <div><h3>事实候选</h3>${list(clue.suspectedFacts, item => `${escape(item.value)} ${sourceButton(item.source)}`)}</div>
      <div><h3>时间 / 地点</h3>${list([...(clue.timeCandidates || []), ...(clue.placeCandidates || [])], item => `${escape(item.value)} ${sourceButton(item.source)}`)}</div></div>
      <div class="issue-columns"><div><h3>缺失项</h3>${list(clue.missingItems)}</div><div><h3>冲突</h3>${list(clue.conflicts, conflict => `<strong>${escape(conflict.field)}</strong>${list(conflict.candidates, candidate => `${escape(candidate.value)} ${sourceButton(candidate.source)}`)}`)}</div></div>` : '<p class="muted-value">处理证据后显示候选字段。</p>'}`;

    sections.evidence.innerHTML = `<div class="section-heading"><h2>证据引用</h2><span>${current.evidenceReferences.length} 项</span></div>${current.evidenceReferences.length ? `<div class="record-table" role="table"><div class="record-row header" role="row"><span>来源</span><span>版本</span><span>可用性</span><span>收集信息</span></div>${current.evidenceReferences.map(item => `<div class="record-row" role="row"><span>${sourceButton(item.source)}</span><span>${escape(item.version)}</span><span><span class="availability ${escape(item.availability).toLowerCase()}">${escape(item.availability)}</span></span><span>${escape(item.collector || item.collectionMissingReason)}</span></div>`).join("")}</div>` : '<p class="muted-value">尚无证据引用。</p>'}`;

    sections.relations.innerHTML = `<div class="section-heading"><h2>证据关系与时间线</h2><span>${relation ? escape(relation.completeness) : "待生成"}</span></div>${relation ? `<div class="field-columns"><div><h3>候选关系</h3>${list(relation.relations, item => `<strong>${escape(item.issue)}</strong> · ${escape(item.type)}<br>${escape(item.reason)} ${sourceButton(item.source)}`)}</div><div><h3>时间线</h3>${list(relation.timeline, item => `<strong>${escape(item.kind)}</strong> ${escape(item.originalValue)} ${sourceButton(item.source)}`)}</div><div><h3>未处理 / 缺口</h3>${list([...(relation.unprocessedItems || []), ...(relation.gaps || []), ...(relation.needsReviewReasons || [])])}</div></div>` : '<p class="muted-value">处理证据后显示候选关系。</p>'}`;

    sections.analysis.innerHTML = `<div class="section-heading"><h2>辅助分析</h2><span class="status-chip warning">NOT_OFFICIAL_DECISION</span></div>${analysis ? `<div class="analysis-outcome"><strong>${escape(analysis.auxiliaryOutcome)}</strong><span>版本 ${analysis.version}</span></div>${list(analysis.items, item => `<span class="item-kind">${escape(item.contentKind)}</span> ${escape(item.content)}<small>${escape(item.uncertainty)}</small>`)}<div class="issue-columns"><div><h3>限制与缺口</h3>${list([...(analysis.gaps || []), ...(analysis.unprocessedItems || []), ...(analysis.needsReviewReasons || [])])}</div><div><h3>人工审核记录</h3>${list(current.reviews, item => `${escape(item.action)} · v${item.subjectVersion}<br><small>${escape(item.reason)} · ${escape(item.actorRef)}</small>`)}</div></div>` : '<p class="muted-value">尚未形成辅助分析。</p>'}`;

    const history = [
      ...(current.clueAnalyses || []).map(item => versionButton("clue-analyses", item.analysisId, item.version, `线索分析 v${item.version}`)),
      ...(current.evidenceReferences || []).map(item => versionButton("evidence-references", item.evidenceRefId, item.version, `证据 ${item.source.sourceId} v${item.version}`)),
      ...(current.relationSets || []).map(item => versionButton("relation-sets", item.relationSetId, item.version, `关系集 v${item.version}`)),
      ...(current.auxiliaryAnalyses || []).map(item => versionButton("auxiliary-analyses", item.analysisResultId, item.version, `辅助分析 v${item.version}`))
    ];
    sections.history.innerHTML = `<div class="section-heading"><h2>版本历史</h2><span>${history.length} 个快照</span></div><div class="version-list">${history.join("") || '<span class="muted-value">暂无历史版本</span>'}</div>${current.reviewImpacts?.length ? `<h3>复核影响</h3>${list(current.reviewImpacts)}` : ""}`;
    processForm.hidden = false;
    actions.hidden = !analysis;
  }

  async function run(action) {
    if (busy) return;
    setBusy(true);
    try {
      const payload = await action();
      if (payload.data?.workItem) render(payload.data);
      showStatus(resultLabel(payload.resultType), payload.resultType === "MATERIALS_INSUFFICIENT" ? "warning" : "success", payload.resultType);
      return payload;
    } catch (error) {
      showStatus(error.message, error.status >= 500 || error.status === 403 ? "danger" : "warning", error.code);
      return null;
    } finally {
      setBusy(false);
    }
  }

  function resultLabel(type) {
    return ({ WORK_ITEM_CREATED: "工作项已创建", AUXILIARY_ANALYSIS_READY: "辅助分析已生成", MATERIALS_INSUFFICIENT: "材料不足，已保留可用结果", REVIEW_RECORDED: "人工审核已留痕", CLUE_ANALYSIS_READY: "线索修订版本已保存" })[type] || "操作已完成";
  }

  openForm.addEventListener("submit", event => {
    event.preventDefault();
    actor = actorSelect.value;
    run(() => request("/api/v0/enforcement/work-items", {
      method: "POST",
      headers: { ...headers(true), "X-V0-Organization": ORGANIZATION },
      body: JSON.stringify({ clueId: clueSelect.value, clueVersion: clueVersions.get(clueSelect.value) || "v1" })
    }));
  });

  processForm.addEventListener("submit", event => {
    event.preventDefault();
    const evidenceIds = [...evidenceOptions.querySelectorAll("input:checked")].map(input => input.value);
    run(() => request(`/api/v0/enforcement/work-items/${encodeURIComponent(snapshot.workItem.workItemId)}/process`, {
      method: "POST", headers: headers(true), body: JSON.stringify({ evidenceIds })
    }));
  });

  document.querySelector("#revise-clue").addEventListener("click", () => {
    const current = latest(snapshot?.clueAnalyses);
    if (!current) return;
    run(() => request(`/api/v0/enforcement/work-items/${encodeURIComponent(snapshot.workItem.workItemId)}/clue-analyses/${encodeURIComponent(current.analysisId)}/actions`, {
      method: "POST", headers: headers(true, true), body: JSON.stringify({ action: "REVISE", expectedVersion: current.version, reason: reasonInput.value.trim() })
    }));
  });

  async function review(action) {
    const current = latest(snapshot?.auxiliaryAnalyses);
    if (!current) return;
    await run(() => request(`/api/v0/enforcement/work-items/${encodeURIComponent(snapshot.workItem.workItemId)}/auxiliary-analyses/${encodeURIComponent(current.analysisResultId)}/actions`, {
      method: "POST", headers: headers(true, true), body: JSON.stringify({ action, expectedVersion: current.version, reason: reasonInput.value.trim() })
    }));
  }
  document.querySelector("#confirm-analysis").addEventListener("click", () => review("CONFIRM"));
  document.querySelector("#return-analysis").addEventListener("click", () => review("RETURN"));

  document.querySelector("#enforcement-workspace").addEventListener("click", async event => {
    const source = event.target.closest("[data-enforcement-source]");
    const version = event.target.closest("[data-version-resource]");
    if ((!source && !version) || busy || !snapshot) return;
    setBusy(true);
    try {
      const workId = encodeURIComponent(snapshot.workItem.workItemId);
      const payload = source
        ? await request(`/api/v0/enforcement/work-items/${workId}/source-links/${encodeURIComponent(source.dataset.enforcementSource)}`, { headers: headers() })
        : await request(`/api/v0/enforcement/work-items/${workId}/${version.dataset.versionResource}/${encodeURIComponent(version.dataset.versionId)}/versions/${version.dataset.version}`, { headers: headers() });
      const dialog = document.querySelector("#source-dialog");
      document.querySelector("#source-title").textContent = source ? "来源定位" : "精确版本";
      document.querySelector("#source-details").innerHTML = `<div class="json-detail">${escape(JSON.stringify(payload.data, null, 2))}</div>`;
      dialog.showModal();
    } catch (error) {
      showStatus(error.message, "danger", error.code);
    } finally {
      setBusy(false);
    }
  });

  navItems.forEach(item => item.addEventListener("click", () => activateWorkspace(item.dataset.workspace)));
  clueSelect.addEventListener("change", renderEvidenceOptions);
  actorSelect.addEventListener("change", () => { actor = actorSelect.value; });
  renderEvidenceOptions();
  loadSyntheticCatalog();
})();
