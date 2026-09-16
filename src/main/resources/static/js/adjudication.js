(() => {
  const ORGANIZATION = "org-v0";
  const panels = [...document.querySelectorAll("[data-workspace-panel]")];
  const navItems = [...document.querySelectorAll("[data-workspace]")];
  const workspace = document.querySelector("#adjudication-workspace");
  const openForm = document.querySelector("#adjudication-open-form");
  const caseSelect = document.querySelector("#adjudication-case");
  const materialSelect = document.querySelector("#adjudication-material");
  const actorSelect = document.querySelector("#adjudication-actor");
  const process = document.querySelector("#adjudication-process");
  const actions = document.querySelector("#adjudication-actions");
  const reason = document.querySelector("#adjudication-reason");
  const replacement = document.querySelector("#adjudication-replacement");
  const empty = document.querySelector("#adjudication-empty");
  const status = document.querySelector("#adjudication-status");
  const sections = {
    summary: document.querySelector("#adjudication-summary"),
    materials: document.querySelector("#adjudication-materials"),
    candidates: document.querySelector("#adjudication-candidates"),
    drafts: document.querySelector("#adjudication-drafts"),
    reviews: document.querySelector("#adjudication-reviews"),
    history: document.querySelector("#adjudication-history")
  };
  let snapshot = null;
  let busy = false;
  let sourceIndex = new Map();
  let materialCatalog = [];

  const text = value => value === null || value === undefined || value === "" ? "未提供" : String(value);
  const escape = value => text(value).replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
  const latest = values => values?.[values.length - 1];
  const key = prefix => `${prefix}-${crypto.randomUUID()}`;

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
    workspace.setAttribute("aria-busy", String(value));
    workspace.querySelectorAll("button, select, textarea").forEach(control => { control.disabled = value; });
    if (!value && snapshot) updateActions();
  }

  function updateActions() {
    document.querySelector("#match-legal").disabled = !snapshot.inspections.length;
    document.querySelector("#select-citation").disabled = !snapshot.candidates.length;
    document.querySelector("#create-draft").disabled = !snapshot.inspections.length;
    document.querySelector("#revise-draft").disabled = !snapshot.drafts.length;
    document.querySelector("#confirm-draft").disabled = !snapshot.drafts.length;
    document.querySelector("#return-draft").disabled = !snapshot.drafts.length;
  }

  function headers(write = false, reviewer = false) {
    const values = { "X-V0-Actor": reviewer ? "v0-adjudication-reviewer" : actorSelect.value, "X-Request-Id": crypto.randomUUID() };
    if (write) {
      values["Content-Type"] = "application/json";
      values["Idempotency-Key"] = key("adjudication-workbench");
    }
    return values;
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

  function showStatus(message, tone = "info", code = "") {
    status.hidden = false;
    status.className = `result-status tone-${tone}`;
    status.innerHTML = `<strong>${escape(message)}</strong>${code ? `<span>${escape(code)}</span>` : ""}`;
  }

  function materialLabel(item) {
    const suffix = !item.data.available ? "来源不可用" : item.data.conflicts.length ? "存在冲突" : item.data.missingItems.length ? "存在缺项" : "材料完整";
    return `${suffix} · ${item.source.id.replace("V0-SYN-ADJ-", "")}`;
  }

  function renderMaterialOptions() {
    const selected = materialSelect.value;
    const items = materialCatalog.filter(item => item.caseRef.id === caseSelect.value);
    if (!items.length) return;
    materialSelect.replaceChildren(...items.map(item => {
      const option = document.createElement("option");
      option.value = item.source.id;
      option.textContent = materialLabel(item);
      return option;
    }));
    if (items.some(item => item.source.id === selected)) materialSelect.value = selected;
  }

  async function loadSyntheticCatalog() {
    try {
      const [casePayload, materialPayload] = await Promise.all([
        request("/api/v0/synthetic-center/adjudication/cases"),
        request("/api/v0/synthetic-center/adjudication/materials")
      ]);
      const selected = caseSelect.value;
      materialCatalog = materialPayload.items;
      caseSelect.replaceChildren(...casePayload.items.map(item => {
        const option = document.createElement("option");
        option.value = item.id;
        option.dataset.version = item.version;
        option.textContent = `合成案件 · ${item.id.replace("V0-SYN-ADJ-", "")} · ${item.version}`;
        return option;
      }));
      if (casePayload.items.some(item => item.id === selected)) caseSelect.value = selected;
      renderMaterialOptions();
    } catch (error) {
      showStatus("合成目录读取失败，使用固定回归样例", "warning", "SYNTHETIC_CATALOG_FALLBACK");
    }
  }

  function list(values, renderer = escape) {
    if (!values?.length) return '<p class="muted-value">无</p>';
    return `<ul class="data-list">${values.map(value => `<li>${renderer(value)}</li>`).join("")}</ul>`;
  }

  function sourceButton(source) {
    if (!source) return '<span class="muted-value">无来源</span>';
    const id = `source-${sourceIndex.size + 1}`;
    sourceIndex.set(id, source);
    return `<button type="button" class="inline-link" data-adjudication-source="${id}">${escape(source.id)} · ${escape(source.version)} · ${escape(source.location)}</button>`;
  }

  function versionButton(resource, id, version, label) {
    return `<button type="button" class="version-button" data-adjudication-resource="${resource}" data-adjudication-id="${escape(id)}" data-adjudication-version="${version}">${escape(label)}</button>`;
  }

  function render(current) {
    snapshot = current;
    sourceIndex = new Map();
    empty.hidden = true;
    Object.values(sections).forEach(section => { section.hidden = false; });
    process.hidden = false;
    actions.hidden = !current.drafts.length;
    const inspection = latest(current.inspections);
    const candidateSet = latest(current.candidates);
    const draft = latest(current.drafts);

    sections.summary.innerHTML = `
      <div class="section-heading"><h2>当前裁决工作项</h2><span class="status-chip">${escape(current.work.scopeLabel)}</span></div>
      <dl class="summary-grid"><div><dt>工作项</dt><dd>${escape(current.work.workItemId)}</dd></div><div><dt>组织</dt><dd>${escape(current.work.organizationRef)}</dd></div><div><dt>合成案件</dt><dd>${sourceButton(current.work.caseRef)}</dd></div><div><dt>创建时间</dt><dd>${escape(current.work.createdAt)}</dd></div></dl>
      <h3>输入材料</h3>${list(current.work.materialRefs, sourceButton)}`;

    sections.materials.innerHTML = `<div class="section-heading"><h2>材料检查</h2><span>${inspection ? `版本 ${inspection.version}` : "待检查"}</span></div>${inspection ? `
      <div class="field-columns"><div><h3>当事人陈述</h3>${list(inspection.partyStatements)}</div><div><h3>待核实事实</h3>${list(inspection.unresolvedFacts)}</div><div><h3>检查输入</h3>${list(inspection.inputs, sourceButton)}</div></div>
      <div class="issue-columns"><div><h3>缺失项</h3>${list(inspection.missingItems)}</div><div><h3>冲突项</h3>${list(inspection.conflicts)}</div></div>` : '<p class="muted-value">执行材料检查后显示结构化结果。</p>'}`;

    sections.candidates.innerHTML = `<div class="section-heading"><h2>法规候选与人工引用</h2><span>${candidateSet ? `候选集 v${candidateSet.version}` : "待生成"}</span></div>${candidateSet ? `
      <div class="field-columns"><div><h3>法规候选</h3>${list(candidateSet.entries, item => `<strong>${escape(item.code)}</strong><br>${sourceButton(item.source)}<br><small>${escape(item.location)}</small>${list(item.doubts)}`)}</div><div><h3>限制</h3>${list(candidateSet.limitations)}</div><div><h3>已选引用</h3>${list(current.citations, item => `${escape(item.action)} · v${item.version}<br>${sourceButton(item.legalSource)}<br><small>${escape(item.reason)}</small>`)}</div></div>` : '<p class="muted-value">生成法规候选后显示，不构成法律适用结论。</p>'}`;

    sections.drafts.innerHTML = `<div class="section-heading"><h2>合成草稿</h2><span class="status-chip warning">NOT_OFFICIAL_DECISION</span></div>${draft ? `
      <div class="analysis-outcome"><strong>草稿版本 ${draft.version}</strong><span>${draft.isDraft ? "仅草稿" : "状态异常"}</span></div>
      <div class="field-columns">${draft.sections.map(section => `<div><h3>${escape(section.anchor)}</h3><p>${escape(section.content)}</p>${list(section.sources, sourceButton)}</div>`).join("")}</div>
      <div class="issue-columns"><div><h3>草稿缺失项</h3>${list(draft.missingItems)}</div><div><h3>草稿冲突项</h3>${list(draft.conflicts)}</div></div>` : '<p class="muted-value">生成草稿后显示；不会生成正式裁决文书。</p>'}`;

    sections.reviews.innerHTML = `<div class="section-heading"><h2>测试复核记录</h2><span>${current.reviews.length} 条</span></div>${list(current.reviews, item => `<strong>${escape(item.action)}</strong> · 草稿 v${item.draftVersion}<br>${escape(item.reason)}<br><small>${escape(item.actorRef)} · ${escape(item.scopeLabel)} · ${item.notOfficialDecision ? "NOT_OFFICIAL_DECISION" : "状态异常"}</small>`)}`;

    const history = [
      ...current.inspections.map(item => versionButton("material-inspections", item.inspectionId, item.version, `材料检查 v${item.version}`)),
      ...current.candidates.map(item => versionButton("legal-candidate-sets", item.candidateSetId, item.version, `法规候选 v${item.version}`)),
      ...current.citations.map(item => versionButton("citations", item.citationId, item.version, `法规引用 v${item.version}`)),
      ...current.drafts.map(item => versionButton("drafts", item.draftId, item.version, `合成草稿 v${item.version}`)),
      ...current.reviews.map(item => versionButton("test-reviews", item.reviewId, 1, `测试复核 · ${item.action}`))
    ];
    sections.history.innerHTML = `<div class="section-heading"><h2>精确版本历史</h2><span>${history.length} 个快照</span></div><div class="version-list">${history.join("") || '<span class="muted-value">暂无历史版本</span>'}</div>`;
    updateActions();
  }

  function resultLabel(type) {
    return ({
      WORK_ITEM_CREATED: "裁决工作项已创建",
      MATERIAL_INSPECTION_READY: "材料检查已完成",
      LEGAL_CANDIDATES_READY: "法规候选已生成",
      CITATION_RECORDED: "法规引用已留痕",
      DRAFT_READY: "合成草稿已生成",
      DRAFT_REVISED: "草稿修订版本已保存",
      TEST_REVIEW_RECORDED: "测试复核已留痕"
    })[type] || "操作已完成";
  }

  async function run(action) {
    if (busy) return null;
    setBusy(true);
    try {
      const payload = await action();
      if (payload.data?.work) render(payload.data);
      showStatus(resultLabel(payload.resultType), "success", payload.resultType);
      return payload;
    } catch (error) {
      showStatus(error.message, error.status >= 500 || error.status === 403 ? "danger" : "warning", error.code);
      return null;
    } finally {
      setBusy(false);
    }
  }

  function workPath(suffix) {
    return `/api/v0/adjudication/work-items/${encodeURIComponent(snapshot.work.workItemId)}${suffix}`;
  }

  openForm.addEventListener("submit", event => {
    event.preventDefault();
    const selectedCase = caseSelect.selectedOptions[0];
    run(() => request("/api/v0/adjudication/work-items", {
      method: "POST",
      headers: { ...headers(true), "X-V0-Organization": ORGANIZATION },
      body: JSON.stringify({ caseId: caseSelect.value, caseVersion: selectedCase?.dataset.version || "v1", materialIds: [materialSelect.value] })
    }));
  });

  document.querySelector("#inspect-materials").addEventListener("click", () => run(() => request(workPath("/material-inspections"), {
    method: "POST", headers: headers(true)
  })));

  document.querySelector("#match-legal").addEventListener("click", () => {
    const inspection = latest(snapshot?.inspections); if (!inspection) return;
    run(() => request(workPath("/legal-candidate-sets"), {
      method: "POST", headers: headers(true), body: JSON.stringify({ inspectionVersion: inspection.version })
    }));
  });

  document.querySelector("#select-citation").addEventListener("click", () => {
    const candidate = latest(snapshot?.candidates)?.entries?.[0]; if (!candidate) return;
    run(() => request(workPath("/citations/actions"), {
      method: "POST", headers: headers(true, true), body: JSON.stringify({
        expectedVersion: snapshot.citations.length, code: candidate.code, action: "SELECT", reason: reason.value.trim()
      })
    }));
  });

  document.querySelector("#create-draft").addEventListener("click", () => {
    const inspection = latest(snapshot?.inspections); if (!inspection) return;
    run(() => request(workPath("/drafts"), {
      method: "POST", headers: headers(true), body: JSON.stringify({ inspectionVersion: inspection.version })
    }));
  });

  document.querySelector("#revise-draft").addEventListener("click", () => {
    const draft = latest(snapshot?.drafts); if (!draft) return;
    run(() => request(workPath("/drafts/actions"), {
      method: "POST", headers: headers(true, true), body: JSON.stringify({
        expectedVersion: draft.version, replacementText: replacement.value.trim(), reason: reason.value.trim()
      })
    }));
  });

  async function review(action) {
    const draft = latest(snapshot?.drafts); if (!draft) return;
    await run(() => request(workPath("/drafts/test-reviews"), {
      method: "POST", headers: headers(true, true), body: JSON.stringify({
        expectedDraftVersion: draft.version, action, reason: reason.value.trim()
      })
    }));
  }
  document.querySelector("#confirm-draft").addEventListener("click", () => review("CONFIRM"));
  document.querySelector("#return-draft").addEventListener("click", () => review("RETURN"));

  workspace.addEventListener("click", async event => {
    const sourceButtonElement = event.target.closest("[data-adjudication-source]");
    const versionButtonElement = event.target.closest("[data-adjudication-resource]");
    if ((!sourceButtonElement && !versionButtonElement) || busy || !snapshot) return;
    setBusy(true);
    try {
      let title;
      let detail;
      if (sourceButtonElement) {
        title = "受控来源元数据";
        detail = sourceIndex.get(sourceButtonElement.dataset.adjudicationSource);
      } else {
        title = "精确历史版本";
        const path = workPath(`/${versionButtonElement.dataset.adjudicationResource}/${encodeURIComponent(versionButtonElement.dataset.adjudicationId)}/versions/${versionButtonElement.dataset.adjudicationVersion}`);
        detail = (await request(path, { headers: headers() })).data;
      }
      document.querySelector("#source-title").textContent = title;
      document.querySelector("#source-details").innerHTML = `<div class="json-detail">${escape(JSON.stringify(detail, null, 2))}</div>`;
      document.querySelector("#source-dialog").showModal();
    } catch (error) {
      showStatus(error.message, "danger", error.code);
    } finally {
      setBusy(false);
    }
  });

  navItems.forEach(item => item.addEventListener("click", () => activateWorkspace(item.dataset.workspace)));
  caseSelect.addEventListener("change", renderMaterialOptions);
  loadSyntheticCatalog();
})();
