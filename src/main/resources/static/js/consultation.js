(() => {
  const REQUESTER = "v0-authorized-user";
  const outcomePresentation = {
    NEEDS_CLARIFICATION: ["需要补充信息", "warning"],
    OUT_OF_SCOPE: ["超出当前范围", "info"],
    ACCESS_DENIED: ["当前无权访问", "danger"],
    NO_RESULT: ["当前范围未返回结果", "info"],
    PARTIAL_RESULT: ["部分结果可用", "warning"],
    SOURCE_UNAVAILABLE: ["来源当前不可用", "danger"],
    TOOL_FAILURE: ["检索暂时失败", "danger"],
    ANSWER_READY: ["辅助回答已生成", "success"]
  };

  const conversation = document.querySelector("#conversation");
  const questionForm = document.querySelector("#question-form");
  const questionInput = document.querySelector("#question-input");
  const purposeInput = document.querySelector("#purpose-input");
  const supplementForm = document.querySelector("#supplement-form");
  const supplementInput = document.querySelector("#supplement-input");
  const sourceDialog = document.querySelector("#source-dialog");
  const sourceDetails = document.querySelector("#source-details");
  const toast = document.querySelector("#toast");
  const attempts = new Map();
  let consultationId = null;
  let busy = false;

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function attemptKey(action, content) {
    const fingerprint = `${action}|${content}`;
    if (!attempts.has(fingerprint)) attempts.set(fingerprint, crypto.randomUUID());
    return { fingerprint, key: attempts.get(fingerprint) };
  }

  function setBusy(value) {
    busy = value;
    document.querySelectorAll("form button").forEach(button => { button.disabled = value; });
    document.body.setAttribute("aria-busy", String(value));
  }

  function showToast(message) {
    toast.textContent = message;
    toast.hidden = false;
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => { toast.hidden = true; }, 4200);
  }

  function appendUserMessage(text, detail) {
    document.querySelector("#empty-state")?.remove();
    const message = element("article", "message user");
    message.append(element("p", "message-label", detail || "你的问题"));
    const body = element("div", "message-body");
    body.append(element("p", "", text));
    message.append(body);
    conversation.append(message);
  }

  function appendOutcome(data) {
    const [label, tone] = outcomePresentation[data.outcomeType] || ["处理结果", "info"];
    const message = element("article", `message system tone-${tone}`);
    message.dataset.outcome = data.outcomeType;
    message.append(element("p", "message-label", "咨询助手"));
    const body = element("div", "message-body");
    const heading = element("div", "outcome-heading");
    heading.append(element("h2", "", label), element("span", "outcome-badge", data.outcomeType));
    body.append(heading);

    if (data.content) body.append(element("p", "answer-content", data.content));
    if (Array.isArray(data.issues) && data.issues.length) {
      const list = element("ul", "issue-list");
      data.issues.forEach(issue => list.append(element("li", "", issue)));
      body.append(list);
    }
    if (Array.isArray(data.candidates) && data.candidates.length) {
      const list = element("div", "candidate-list");
      data.candidates.forEach(candidate => list.append(renderCandidate(candidate, data.sourceLinks || [])));
      body.append(list);
    }
    if (data.limitation) body.append(element("p", "limitation", data.limitation));
    if (data.answerVersion) body.append(element("p", "answer-meta", `回答版本 ${data.answerVersion}`));
    message.append(body);
    conversation.append(message);
    message.scrollIntoView({ behavior: "smooth", block: "nearest" });
    supplementForm.hidden = data.outcomeType !== "NEEDS_CLARIFICATION";
    if (!supplementForm.hidden) supplementInput.focus();
  }

  function renderCandidate(candidate, links) {
    const item = element("article", "candidate");
    const head = element("div", "candidate-head");
    head.append(element("span", "candidate-title", candidate.sourceId), element("span", "candidate-version", candidate.version));
    item.append(head, element("p", "", `匹配依据：${candidate.matchBasis}`), element("p", "", `定位：${candidate.location}`));
    const link = links.find(value => value.sourceId === candidate.sourceId);
    if (link) {
      const button = element("button", "source-button", "打开来源");
      button.type = "button";
      button.dataset.sourceLinkId = link.sourceLinkId;
      item.append(button);
    }
    return item;
  }

  async function request(path, options) {
    const response = await fetch(path, options);
    if (!response.ok) {
      const error = new Error(response.status === 409 ? "请求内容与已提交操作冲突，请修改后重试。" : "服务暂时无法完成请求，请稍后重试。");
      error.status = response.status;
      throw error;
    }
    return response.json();
  }

  questionForm.addEventListener("submit", async event => {
    event.preventDefault();
    if (busy || !questionInput.value.trim()) return;
    if (consultationId) resetWorkspace();
    const question = questionInput.value.trim();
    const purpose = purposeInput.value;
    const attempt = attemptKey("question", `${question}|${purpose}`);
    appendUserMessage(question, purpose === "TECHNICAL_REFERENCE" ? "你的问题 · 技术参考" : "你的问题");
    setBusy(true);
    try {
      const data = await request("/api/v0/consultations", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-V0-Requester": REQUESTER, "Idempotency-Key": attempt.key },
        body: JSON.stringify({ question, purpose })
      });
      consultationId = data.consultationId;
      attempts.delete(attempt.fingerprint);
      appendOutcome(data);
    } catch (error) {
      showToast(error.message);
    } finally {
      setBusy(false);
    }
  });

  supplementForm.addEventListener("submit", async event => {
    event.preventDefault();
    if (busy || !consultationId || !supplementInput.value.trim()) return;
    const content = supplementInput.value.trim();
    const attempt = attemptKey(`supplement|${consultationId}`, content);
    appendUserMessage(content, "补充信息");
    setBusy(true);
    try {
      const data = await request(`/api/v0/consultations/${encodeURIComponent(consultationId)}/inputs`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-V0-Requester": REQUESTER, "Idempotency-Key": attempt.key },
        body: JSON.stringify({ content })
      });
      attempts.delete(attempt.fingerprint);
      supplementInput.value = "";
      appendOutcome(data);
    } catch (error) {
      showToast(error.message);
    } finally {
      setBusy(false);
    }
  });

  conversation.addEventListener("click", async event => {
    const button = event.target.closest("[data-source-link-id]");
    if (!button || !consultationId || busy) return;
    setBusy(true);
    try {
      const source = await request(`/api/v0/consultations/${encodeURIComponent(consultationId)}/source-links/${encodeURIComponent(button.dataset.sourceLinkId)}`, {
        headers: { "X-V0-Requester": REQUESTER }
      });
      sourceDetails.replaceChildren();
      [["来源标识", source.sourceId], ["版本", source.version], ["位置", source.location]].forEach(([term, value]) => {
        sourceDetails.append(element("dt", "", term), element("dd", "", value));
      });
      sourceDialog.showModal();
    } catch (error) {
      showToast(error.status === 404 ? "该来源当前不可用，未展示缓存内容。" : error.message);
    } finally {
      setBusy(false);
    }
  });

  function resetWorkspace() {
    consultationId = null;
    attempts.clear();
    supplementForm.hidden = true;
    supplementInput.value = "";
    questionInput.value = "";
    purposeInput.value = "";
    conversation.replaceChildren();
    const nextEmpty = element("div", "empty-state");
    nextEmpty.id = "empty-state";
    nextEmpty.append(element("div", "empty-symbol", "检"), element("h2", "", "开始一次专利技术检索"), element("p", "", "提交技术问题后，结果会在这里按来源和版本展示。"));
    conversation.append(nextEmpty);
    questionInput.focus();
  }

  document.querySelector("#new-consultation").addEventListener("click", resetWorkspace);
  document.querySelector("#close-source").addEventListener("click", () => sourceDialog.close());
})();
