(() => {
  const ACTOR = "v0-authorized-user";
  const form = document.querySelector("#trademark-library-form");
  const status = document.querySelector("#trademark-library-status");
  const summary = document.querySelector("#trademark-library-summary");
  const results = document.querySelector("#trademark-library-results");
  const pages = document.querySelector("#trademark-library-pages");
  const detail = document.querySelector("#trademark-library-detail");
  let page = 1;
  let requestSequence = 0;
  let loaded = false;

  const escape = value => String(value ?? "未提供").replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
  const split = value => value.split(/[，,]/).map(item => item.trim()).filter(Boolean);
  const headers = () => ({ "X-V0-Actor": ACTOR, "X-Request-Id": crypto.randomUUID() });

  async function request(path) {
    const response = await fetch(path, { headers: headers() });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) { const error = new Error(body.message || `请求失败 (${response.status})`); error.code = body.code; throw error; }
    return body;
  }

  function setBusy(value) {
    form.querySelectorAll("button,input,select").forEach(control => { control.disabled = value; });
    form.setAttribute("aria-busy", String(value));
  }

  function showStatus(message, tone = "info", code = "") {
    status.hidden = false;
    status.className = `result-status tone-${tone}`;
    status.innerHTML = `<strong>${escape(message)}</strong>${code ? `<span>${escape(code)}</span>` : ""}`;
  }

  function queryString() {
    const params = new URLSearchParams({ page: String(page), pageSize: document.querySelector("#library-page-size").value });
    const fields = {
      registrationNumber: document.querySelector("#library-registration").value.trim(),
      name: document.querySelector("#library-name").value.trim(),
      applicant: document.querySelector("#library-applicant").value.trim(),
      registeredFrom: document.querySelector("#library-date-from").value,
      registeredTo: document.querySelector("#library-date-to").value
    };
    Object.entries(fields).forEach(([key, value]) => { if (value) params.set(key, value); });
    split(document.querySelector("#library-category").value).forEach(value => params.append("category", value));
    const selectedStatus = document.querySelector("#library-status").value; if (selectedStatus) params.append("status", selectedStatus);
    return params;
  }

  function renderPage(data) {
    summary.hidden = false;
    summary.innerHTML = `<div class="section-heading"><h2>查询结果</h2><span>${data.total} 条 · 第 ${data.page} 页</span></div><p class="synthetic-warning">${escape(data.limitation)}</p><dl class="summary-grid"><div><dt>数据模式</dt><dd>${escape(data.dataMode)}</dd></div><div><dt>来源版本</dt><dd>${escape(data.sourceVersion)}</dd></div><div><dt>缓存</dt><dd>${escape(data.cacheState)}</dd></div><div><dt>获取时间</dt><dd>${escape(data.fetchedAt)}</dd></div></dl>`;
    results.hidden = false;
    results.innerHTML = data.items.length ? `<div class="library-table"><div class="library-row header"><span>注册号</span><span>商标</span><span>注册人</span><span>类别 / 状态</span><span>操作</span></div>${data.items.map(item => `<div class="library-row"><span>${escape(item.registrationNumber)}</span><span><strong>${escape(item.name)}</strong><small>${escape(item.sourceRecordId)}</small></span><span>${escape(item.applicants.join("、"))}</span><span>${escape(item.classes.map(value => value.code).join("、"))} 类<br>${escape(item.displayStatus)}</span><span><button class="inline-link" type="button" data-library-detail="${escape(item.sourceRecordId)}">查看详情</button></span></div>`).join("")}</div>` : '<p class="muted-value">在当前合成数据范围和查询条件下未检索到商标。</p>';
    const totalPages = Math.max(1, Math.ceil(data.total / data.pageSize));
    pages.hidden = false;
    pages.innerHTML = `<button class="secondary-button" type="button" data-library-page="${data.page - 1}" ${data.page === 1 ? "disabled" : ""}>上一页</button><span>${data.page} / ${totalPages}</span><button class="secondary-button" type="button" data-library-page="${data.page + 1}" ${data.page >= totalPages ? "disabled" : ""}>下一页</button>`;
  }

  async function search() {
    const sequence = ++requestSequence;
    setBusy(true);
    try {
      const data = await request(`/api/v0/trademark-library?${queryString()}`);
      if (sequence !== requestSequence) return;
      renderPage(data); detail.hidden = true; showStatus("商标库查询完成", "success", data.requestId);
    } catch (error) {
      if (sequence === requestSequence) showStatus(error.message, "warning", error.code);
    } finally { if (sequence === requestSequence) setBusy(false); }
  }

  function renderHistory(container, label, data) {
    const stateLabels = { HAS_RECORDS: "有记录", NO_RECORDS: "查询成功无记录", SOURCE_UNAVAILABLE: "数据源未提供或暂不可用", QUERY_FAILED: "查询失败" };
    container.innerHTML = `<h3>${escape(label)} · ${escape(stateLabels[data.history.state] || data.history.state)}</h3>${data.history.error ? `<p class="limitation">${escape(data.history.error)}</p>` : ""}${data.history.records.length ? `<ul class="data-list">${data.history.records.map(item => `<li><strong>${escape(item.matter)}</strong> · ${escape(item.status)} · ${escape(item.businessDate)}<br><small>${escape(item.result)} · ${escape(item.sourceRef)}</small></li>`).join("")}</ul>` : '<p class="muted-value">该状态不等同于不存在异议或无效风险。</p>'}`;
  }

  async function openDetail(sourceId) {
    try {
      const data = await request(`/api/v0/trademark-library/${encodeURIComponent(sourceId)}`);
      const item = data.item;
      detail.hidden = false;
      detail.innerHTML = `<div class="section-heading"><h2>${escape(item.name)}</h2><span>${escape(item.registrationNumber)} · ${escape(item.displayStatus)}</span></div><dl class="summary-grid"><div><dt>稳定来源</dt><dd>${escape(item.sourceId)} / ${escape(item.sourceRecordId)} / ${escape(item.sourceVersion)}</dd></div><div><dt>注册人</dt><dd>${escape(item.applicants.join("、"))}</dd></div><div><dt>注册地址</dt><dd>${escape(item.applicantAddress)}</dd></div><div><dt>注册 / 有效期</dt><dd>${escape(item.registrationDate)} / ${escape(item.validUntil)}</dd></div><div><dt>源状态</dt><dd>${escape(item.sourceStatus)}</dd></div><div><dt>来源引用</dt><dd>${escape(item.sourceRef)}</dd></div></dl><h3>商品与服务</h3>${item.classes.map(value => `<p><strong>第 ${escape(value.code)} 类 · ${escape(value.name)}</strong><br>${escape(value.goodsServices.join("、"))}<br><small>类似群：${escape(value.similarGroups.join("、"))}</small></p>`).join("")}<div class="library-history-actions"><button type="button" class="secondary-button" data-library-history="changes" data-source-id="${escape(sourceId)}">变更记录</button><button type="button" class="secondary-button" data-library-history="oppositions" data-source-id="${escape(sourceId)}">异议信息</button><button type="button" class="secondary-button" data-library-history="invalidations" data-source-id="${escape(sourceId)}">无效信息</button></div><div id="trademark-library-history-result"></div>`;
      detail.scrollIntoView({ behavior: "smooth", block: "nearest" });
    } catch (error) { showStatus(error.message, "warning", error.code); }
  }

  form.addEventListener("submit", event => { event.preventDefault(); page = 1; search(); });
  document.querySelector("#trademark-library-reset").addEventListener("click", () => { form.reset(); page = 1; search(); });
  pages.addEventListener("click", event => { const next = Number(event.target.closest("[data-library-page]")?.dataset.libraryPage); if (next > 0) { page = next; search(); } });
  results.addEventListener("click", event => { const id = event.target.closest("[data-library-detail]")?.dataset.libraryDetail; if (id) openDetail(id); });
  detail.addEventListener("click", async event => {
    const button = event.target.closest("[data-library-history]"); if (!button) return;
    try { const data = await request(`/api/v0/trademark-library/${encodeURIComponent(button.dataset.sourceId)}/history/${button.dataset.libraryHistory}`); renderHistory(document.querySelector("#trademark-library-history-result"), button.textContent, data); }
    catch (error) { showStatus(error.message, "warning", error.code); }
  });
  document.querySelector('[data-workspace="trademark-library"]').addEventListener("click", () => { if (!loaded) { loaded = true; search(); } });
})();
