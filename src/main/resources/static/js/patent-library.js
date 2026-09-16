(() => {
  const ACTOR = "v0-authorized-user";
  const form = document.querySelector("#patent-library-form");
  const status = document.querySelector("#patent-library-status");
  const summary = document.querySelector("#patent-library-summary");
  const results = document.querySelector("#patent-library-results");
  const pages = document.querySelector("#patent-library-pages");
  const detail = document.querySelector("#patent-library-detail");
  let page = 1;
  let requestSequence = 0;
  let loaded = false;

  const escape = value => String(value ?? "未提供").replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
  const headers = () => ({ "X-V0-Actor": ACTOR, "X-Request-Id": crypto.randomUUID() });
  async function request(path) {
    const response = await fetch(path, { headers: headers() });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) { const error = new Error(body.message || `请求失败 (${response.status})`); error.code = body.code; throw error; }
    return body;
  }
  function setBusy(value) { form.querySelectorAll("button,input,select").forEach(control => { control.disabled = value; }); form.setAttribute("aria-busy", String(value)); }
  function showStatus(message, tone = "info", code = "") { status.hidden = false; status.className = `result-status tone-${tone}`; status.innerHTML = `<strong>${escape(message)}</strong>${code ? `<span>${escape(code)}</span>` : ""}`; }

  function queryString() {
    const params = new URLSearchParams({ page: String(page), pageSize: document.querySelector("#patent-library-page-size").value });
    const fields = {
      applicationNumber: "#patent-library-application", publicationNumber: "#patent-library-publication",
      title: "#patent-library-title", applicant: "#patent-library-applicant", inventor: "#patent-library-inventor",
      classification: "#patent-library-classification", applicationFrom: "#patent-library-date-from", applicationTo: "#patent-library-date-to"
    };
    Object.entries(fields).forEach(([key, selector]) => { const value = document.querySelector(selector).value.trim(); if (value) params.set(key, value); });
    const type = document.querySelector("#patent-library-type").value; if (type) params.append("patentType", type);
    const state = document.querySelector("#patent-library-status-filter").value; if (state) params.append("status", state);
    params.set("includeDescendants", String(document.querySelector("#patent-library-descendants").checked));
    return params;
  }

  function renderPage(data) {
    summary.hidden = false;
    summary.innerHTML = `<div class="section-heading"><h2>查询结果</h2><span>${data.total} 条 · 第 ${data.page} 页</span></div><p class="synthetic-warning">${escape(data.limitation)}</p><dl class="summary-grid"><div><dt>数据模式</dt><dd>${escape(data.dataMode)}</dd></div><div><dt>来源版本</dt><dd>${escape(data.sourceVersion)}</dd></div><div><dt>缓存</dt><dd>${escape(data.cacheState)}</dd></div><div><dt>获取时间</dt><dd>${escape(data.fetchedAt)}</dd></div></dl>`;
    results.hidden = false;
    results.innerHTML = data.items.length ? `<div class="library-table"><div class="library-row header"><span>申请号</span><span>名称</span><span>申请人</span><span>类型 / 状态</span><span>操作</span></div>${data.items.map(item => `<div class="library-row"><span>${escape(item.applicationNumber)}<small>${escape(item.publicationNumber)}</small></span><span><strong>${escape(item.title)}</strong><small>${escape(item.sourceRecordId)}</small></span><span>${escape(item.applicants.join("、"))}</span><span>${escape(item.patentType)}<br>${escape(item.mappedLegalStatus)}</span><span><button class="inline-link" type="button" data-patent-library-detail="${escape(item.sourceRecordId)}">查看详情</button></span></div>`).join("")}</div>` : '<p class="muted-value">在当前合成数据范围和查询条件下未检索到专利。</p>';
    const totalPages = Math.max(1, Math.ceil(data.total / data.pageSize));
    pages.hidden = false;
    pages.innerHTML = `<button class="secondary-button" type="button" data-patent-library-page="${data.page - 1}" ${data.page === 1 ? "disabled" : ""}>上一页</button><span>${data.page} / ${totalPages}</span><button class="secondary-button" type="button" data-patent-library-page="${data.page + 1}" ${data.page >= totalPages ? "disabled" : ""}>下一页</button>`;
  }

  async function search() {
    const sequence = ++requestSequence; setBusy(true);
    try { const data = await request(`/api/v0/patent-library?${queryString()}`); if (sequence !== requestSequence) return; renderPage(data); detail.hidden = true; showStatus("专利库查询完成", "success", data.requestId); }
    catch (error) { if (sequence === requestSequence) showStatus(error.message, "warning", error.code); }
    finally { if (sequence === requestSequence) setBusy(false); }
  }

  const classifications = item => [...item.ipcClassifications.map(value => `IPC ${value}`), ...item.cpcClassifications.map(value => `CPC ${value}`), ...(item.designClassification ? [`外观 ${item.designClassification}`] : [])].join("、") || "源未提供";
  async function openDetail(sourceId) {
    try {
      const data = await request(`/api/v0/patent-library/${encodeURIComponent(sourceId)}`); const item = data.item;
      detail.hidden = false;
      detail.innerHTML = `<div class="section-heading"><h2>${escape(item.title)}</h2><span>${escape(item.patentType)} · ${escape(item.mappedLegalStatus)}</span></div><dl class="summary-grid"><div><dt>号码</dt><dd>申请 ${escape(item.applicationNumber)}<br>公开 ${escape(item.publicationNumber)}<br>授权 ${escape(item.grantPublicationNumber)}</dd></div><div><dt>申请人 / 发明人</dt><dd>${escape(item.applicants.join("、"))}<br>${escape(item.inventors.join("、"))}</dd></div><div><dt>日期</dt><dd>申请 ${escape(item.applicationDate)}<br>公开 ${escape(item.publicationDate)}<br>授权 ${escape(item.grantDate)}</dd></div><div><dt>分类</dt><dd>${escape(classifications(item))}</dd></div><div><dt>源状态</dt><dd>${escape(item.sourceLegalStatus)} · ${escape(item.statusDate)}</dd></div><div><dt>来源引用</dt><dd>${escape(item.sourceRef)}</dd></div></dl><h3>文献版本</h3><div class="version-list">${item.versions.map(version => `<button class="version-button" type="button" data-patent-library-document="${escape(version.versionId)}" data-source-id="${escape(sourceId)}">${escape(version.kind)} · ${escape(version.documentNumber)} · ${escape(version.documentDate)}</button>`).join("")}</div><div class="library-history-actions"><button class="secondary-button" type="button" data-patent-library-events="${escape(sourceId)}">查看历史事件</button></div><div id="patent-library-subresult"></div>`;
      detail.scrollIntoView({ behavior: "smooth", block: "nearest" });
    } catch (error) { showStatus(error.message, "warning", error.code); }
  }

  function section(label, value) {
    const stateLabels = { COMPLETE: "完整", PARTIAL: "部分", PARSE_FAILED: "解析失败", SOURCE_NOT_PROVIDED: "数据源未提供", NOT_APPLICABLE: "不适用" };
    return `<section><h3>${escape(label)} · ${escape(stateLabels[value.state] || value.state)}</h3>${value.message ? `<p class="limitation">${escape(value.message)}</p>` : ""}${value.texts.length ? `<ul class="data-list">${value.texts.map(text => `<li><strong>${escape(text.sourceLabel)}</strong> <small>${escape(text.systemLocator)}</small>${escape(text.text)}</li>`).join("")}</ul>` : `<p class="muted-value">原文引用：${escape(value.originalRef)}</p>`}</section>`;
  }
  function renderDocument(data) {
    const value = data.document; document.querySelector("#patent-library-subresult").innerHTML = `<div class="section-heading"><h2>${escape(value.kind)}</h2><span>${escape(value.versionId)}</span></div>${section("摘要", value.abstractSection)}${section("权利要求", value.claimsSection)}${section("说明书", value.descriptionSection)}${section("附图", value.figuresSection)}`;
  }
  function renderEvents(data) {
    const labels = { APPLICATION_FILED: "申请", PUBLICATION: "公开", GRANT: "授权", INVALIDATION_REQUEST: "无效宣告请求", INVALIDATION_DECISION: "无效宣告审查决定" };
    document.querySelector("#patent-library-subresult").innerHTML = `<div class="section-heading"><h2>历史事件</h2><span>${escape(data.events.state)}</span></div>${data.events.message ? `<p class="limitation">${escape(data.events.message)}</p>` : ""}${data.events.records.length ? `<ul class="data-list">${data.events.records.map(item => `<li><strong>${escape(labels[item.type] || item.type)}</strong> · ${escape(item.businessDate)}<br>${escape(item.result)}<small>${escape(item.documentVersionId)} · ${escape(item.sourceRef)}</small></li>`).join("")}</ul>` : '<p class="muted-value">查询成功无事件记录；不等同于法律上不存在其他事件。</p>'}`;
  }

  form.addEventListener("submit", event => { event.preventDefault(); page = 1; search(); });
  document.querySelector("#patent-library-reset").addEventListener("click", () => { form.reset(); page = 1; search(); });
  pages.addEventListener("click", event => { const next = Number(event.target.closest("[data-patent-library-page]")?.dataset.patentLibraryPage); if (next > 0) { page = next; search(); } });
  results.addEventListener("click", event => { const id = event.target.closest("[data-patent-library-detail]")?.dataset.patentLibraryDetail; if (id) openDetail(id); });
  detail.addEventListener("click", async event => {
    const documentButton = event.target.closest("[data-patent-library-document]"); const eventsButton = event.target.closest("[data-patent-library-events]");
    try {
      if (documentButton) renderDocument(await request(`/api/v0/patent-library/${encodeURIComponent(documentButton.dataset.sourceId)}/documents/${encodeURIComponent(documentButton.dataset.patentLibraryDocument)}`));
      if (eventsButton) renderEvents(await request(`/api/v0/patent-library/${encodeURIComponent(eventsButton.dataset.patentLibraryEvents)}/events`));
    } catch (error) { showStatus(error.message, "warning", error.code); }
  });
  document.querySelector('[data-workspace="patent-library"]').addEventListener("click", () => { if (!loaded) { loaded = true; search(); } });
})();
