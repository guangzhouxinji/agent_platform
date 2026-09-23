import { expect, test } from "@playwright/test";
import { installDevelopmentSourceRoutes } from "./development-source-fixture.mjs";

const routes = [
  ["consultation", "知识产权咨询"],
  ["consultation/intents", "咨询意图测试"],
  ["consultation/agent-configuration", "咨询智能体配置草稿"],
  ["search/patents", "专利相似度检索"],
  ["compare/patents", "专利技术对照"],
  ["search/trademarks", "商标相似度检索"],
  ["search/cases", "相似案例检索"],
  ["compare/trademarks", "商标多维对照"],
  ["enforcement", "执法辅助工作台"],
  ["adjudication", "裁决辅助工作台"]
];

test("all remaining routes render as native React workbenches", async ({ page }) => {
  for (const [path, title] of routes) {
    await page.goto(path);
    await expect(page.getByRole("heading", { name: title })).toBeVisible();
    await expect(page.locator("iframe")).toHaveCount(0);
    await expect(page.getByText("正在迁移到新前端")).toHaveCount(0);
  }
});

test("patent search completes with a traceable task result", async ({ page }) => {
  await page.route("**/api/v0/patent-search-tasks/preparations", route => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ inputMode: "TECHNICAL_SOLUTION", input: "水性粘结剂 双区烘干 在线测厚", purpose: "TECHNICAL_REFERENCE", scope: {}, suggestedFeatures: ["水性粘结剂", "双区烘干", "在线测厚"], modelProvider: "deepseek", modelName: "deepseek-chat", traceId: "feature-trace", limitation: "必须人工确认" })
  }));
  await page.route("**/api/v0/patent-search-tasks", route => route.fulfill({
    status: route.request().method() === "POST" ? 202 : 200,
    contentType: "application/json",
    body: route.request().method() === "POST" ? JSON.stringify({
      taskId: "patent-search-task-1", status: "SUCCEEDED", attempt: 1, dataMode: "PUBLIC_LOCAL",
      scoringVersion: "PUBLIC-LOCAL-TEXT-V1", inputSnapshot: { input: "水性粘结剂 双区烘干 在线测厚" },
      actualScope: "本地公开专利快照", totalCandidates: 1, limitation: "公开资料候选，需人工核对。", traceId: "patent-search-task-1",
      candidates: [{ sourceId: "CN-TEST-1", publicationNumber: "CNTEST1A", title: "水性粘结剂双区烘干方法", version: "fixture-v1", relevanceLabel: "较高词项相关", matchingExcerpt: "水性粘结剂经双区烘干处理", matchedFeatures: ["水性粘结剂", "双区烘干"], location: "标题/摘要" }]
    }) : "[]"
  }));
  await page.goto("search/patents");
  await page.getByRole("button", { name: "提取特征" }).click();
  await expect(page.getByText(/已提取 3 项检索特征/)).toBeVisible();
  await page.getByPlaceholder("用逗号分隔检索特征").fill("水性粘结剂，双区烘干");
  await page.getByRole("button", { name: "开始检索" }).click();
  await expect(page.locator(".search-result-stage").getByText("已完成", { exact: true })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole("heading", { name: "候选记录" })).toBeVisible();
  await expect(page.locator(".candidate-card").first()).toBeVisible();
});

test("public-local results disclose missing claims and block comparison", async ({ page }) => {
  await page.route("**/api/v0/patent-search-tasks/preparations", route => route.fulfill({
    status: 200, contentType: "application/json",
    body: JSON.stringify({ inputMode: "TECHNICAL_SOLUTION", input: "双区烘干", purpose: "TECHNICAL_REFERENCE", scope: {}, suggestedFeatures: ["双区烘干"], modelProvider: "deepseek", modelName: "deepseek-chat", traceId: "feature-trace", limitation: "必须人工确认" })
  }));
  await page.route("**/api/v0/patent-search-tasks", route => route.fulfill({
    status: 200, contentType: "application/json",
    body: route.request().method() === "GET" ? "[]" : JSON.stringify({
      taskId: "public-local-task", status: "SUCCEEDED", attempt: 1, dataMode: "PUBLIC_LOCAL",
      scoringVersion: "PUBLIC-LOCAL-TEXT-V1", inputSnapshot: { input: "双区烘干" },
      actualScope: "本地公开专利快照 174 件", limitation: "未检索全文或权利要求", traceId: "public-local-task",
      candidates: [{ sourceId: "CN-1", publicationNumber: "CN1A", title: "双区烘干方法", version: "v1", relevanceLabel: "中(未标定)", matchingExcerpt: "摘要中的双区烘干", matchedFeatures: ["双区烘干"], location: "标题/摘要" }]
    })
  }));
  await page.goto("search/patents");
  await page.getByRole("button", { name: "提取特征" }).click();
  await page.getByRole("button", { name: "开始检索" }).click();
  await expect(page.getByText("权利要求未提供")).toBeVisible();
  await expect(page.getByRole("button", { name: "暂无法进入对比" })).toBeDisabled();
  await expect(page.getByRole("link", { name: "查看资料库" })).toHaveAttribute("href", "/app/libraries/patents");
});

test("consultation completes against the current backend", async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto("consultation");
  await page.getByLabel("咨询问题").fill("CPC Y02E 光伏 电池");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.locator(".reply-message")).toBeVisible({ timeout: 90_000 });
});

test("development question guidance only fills the consultation input", async ({ page }) => {
  let consultationPosts = 0;
  await page.route("**/api/v0/consultation-guides**", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
    version: "LOCAL-GUIDANCE-V1", status: "DEVELOPMENT_DRAFT", limitation: "开发期固定草稿，不自动执行。",
    guides: [{ guideId: "LG-CASE-REFERENCE-01", title: "查找相似案例", question: "查找商标侵权二审中涉及近似标识判断的可参考案例", scenarioId: "CASE_REFERENCE", relatedQuestionIds: ["CASE_REFERENCE"], status: "DEVELOPMENT_DRAFT", version: "LOCAL-GUIDANCE-V1" }]
  }) }));
  await page.route("**/api/v0/consultations", route => { consultationPosts++; return route.continue(); });
  await page.goto("consultation");
  await page.getByText("问题示例", { exact: true }).click();
  await page.getByRole("button", { name: /查找相似案例/ }).click();
  await expect(page.getByLabel("咨询问题")).toHaveValue("查找商标侵权二审中涉及近似标识判断的可参考案例");
  expect(consultationPosts).toBe(0);
});

test("case search confirms keywords and shows traceable source evidence", async ({ page }) => {
  const task = {
    taskId: "CS-browser-1", status: "SUCCEEDED", dataMode: "PUBLIC_LOCAL", sourceVersion: "public-local-case-v1",
    rankingVersion: "PUBLIC-LOCAL-CASE-TERM-RANK-V1", actualScope: "本地公开案例快照 100 条；按确认范围和关键词检索",
    totalCandidates: 1, limitation: "固定词项相关度，不是专业案情相似度或裁判结果预测。", traceId: "case-trace-1",
    createdAt: "2026-09-17T12:00:00Z", inputSnapshot: { question: "商标侵权二审有哪些可参考案例", confirmedKeywords: ["商标", "侵权"] },
    candidates: [{ sourceId: "6071", sourceVersion: "public-v1", caseNumber: "6071", docketNumber: "（2022）最高法知民终2527号",
      title: "商标侵权纠纷二审案例", caseType: "民事案件", intellectualPropertyType: "商标", institution: "最高人民法院",
      procedure: "二审", decisionDate: "2023-06-01", outcomeExcerpt: "对争议行为及证据进行审查。", relevanceScore: 65,
      relevanceLabel: "较高词项相关", matchedKeywords: ["商标", "侵权"], rankingBasis: ["标题命中：商标", "裁判原文命中：侵权"],
      evidence: { text: "法院围绕涉案商标使用行为是否构成侵权进行审查。", sourceLocation: "裁判原文第12段", systemLocator: "P12", state: "SOURCE_TEXT" } }]
  };
  await page.route("**/api/v0/case-search-tasks**", async route => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/preparations")) return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      question: task.inputSnapshot.question, suggestedKeywords: ["商标", "侵权"], requiresClarification: false, issues: [],
      limitation: "关键词仅来自本次输入中的可见词项，请确认。"
    }) });
    return route.fulfill({ status: route.request().method() === "POST" ? 202 : 200, contentType: "application/json", body: route.request().method() === "POST" ? JSON.stringify(task) : "[]" });
  });

  await page.goto("search/cases");
  await page.getByRole("button", { name: "提取要素" }).click();
  await expect(page.getByText("确认检索关键词", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "开始检索" }).click();
  await expect(page.getByRole("heading", { name: "候选案例" })).toBeVisible();
  await expect(page.getByText("法院围绕涉案商标使用行为是否构成侵权进行审查。")).toBeVisible();
  await expect(page.getByText("标题命中：商标")).toBeVisible();
  await expect(page.getByRole("link", { name: "在案例库核对" })).toHaveAttribute("href", "/app/libraries/cases?caseNumber=6071");
});

test("intent test shows multiple intents, context sources and route suggestions", async ({ page }) => {
  await page.route("**/api/v0/consultation-intents/parse", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
    status: "MULTI_INTENT", parserVersion: "LOCAL-INTENT-V1", question: "查询这个专利的状态；同时找相关侵权案例", primaryIntentId: "PATENT_INFORMATION",
    intents: [
      { intentId: "PATENT_INFORMATION", label: "专利信息查询", category: "PATENT", order: 1, matchedSignals: ["专利", "查询", "状态"],
        slots: [{ name: "patentNumber", value: "CN116234567A", sourceRound: 1, sourceType: "USER_EXPLICIT" }], missingSlots: [], plannedRoute: "/libraries/patents", routeState: "AVAILABLE", reason: "专利对象包含查询诉求或专利号形态" },
      { intentId: "CASE_REFERENCE", label: "案例参考", category: "CASE", order: 2, matchedSignals: ["案例", "侵权"],
        slots: [{ name: "patentNumber", value: "CN116234567A", sourceRound: 1, sourceType: "USER_EXPLICIT" }], missingSlots: [], plannedRoute: "/search/cases", routeState: "AVAILABLE", reason: "问题明确请求案例或裁判参考" }
    ], clarificationQuestions: [], limitation: "结果是路由建议，不执行工具且不代表识别准确率。", traceId: "intent-trace", parsedAt: "2026-09-17T12:00:00Z"
  }) }));
  await page.goto("consultation/intents");
  await page.getByRole("button", { name: "解析咨询意图" }).click();
  await expect(page.getByText("识别到多个诉求")).toBeVisible();
  await expect(page.getByRole("heading", { name: "1. 专利信息查询" })).toBeVisible();
  await expect(page.getByText("上下文第 1 轮").first()).toBeVisible();
  await expect(page.getByRole("link", { name: "进入建议工作台" }).first()).toHaveAttribute("href", "/app/libraries/patents");
  await expect(page.getByText("不代表识别准确率", { exact: false })).toBeVisible();
});

test("agent draft keeps model order, hides secrets and reports formal blockers", async ({ page }) => {
  const draft = { agentId: "CONSULTATION-AGENT-001", name: "知识产权咨询助手", description: "内部开发草稿",
    scenarioIds: ["PATENT", "TRADEMARK"], modelChain: ["deepseek:deepseek-chat", "deepseek:deepseek-reasoner"],
    switchOn: ["TIMEOUT", "RATE_LIMITED"], toolIds: ["PATENT_INFORMATION", "TRADEMARK_INFORMATION"],
    version: 1, status: "DEVELOPMENT_DRAFT", changeNote: "配置主备模型", updatedAt: "2026-09-17T12:00:00Z" };
  let savedBody;
  let applyDraftRequest;
  let runningModels = [
    { modelId: "deepseek:deepseek-reasoner", displayName: "DeepSeek Reasoner", provider: "deepseek", modelName: "deepseek-reasoner", endpoint: "https://example.test/chat", enabled: true, priority: 1, dataChannel: "PUBLIC_LOCAL", credentialConfigured: true },
    { modelId: "deepseek:deepseek-chat", displayName: "DeepSeek Chat", provider: "deepseek", modelName: "deepseek-chat", endpoint: "https://example.test/chat", enabled: true, priority: 2, dataChannel: "PUBLIC_LOCAL", credentialConfigured: true }
  ];
  await page.route("**/api/v0/model-configurations**", route => {
    if (route.request().method() === "POST" && route.request().url().endsWith("/apply-consultation-agent-draft")) {
      applyDraftRequest = { method: route.request().method(), url: route.request().url() };
      runningModels = [runningModels[1], runningModels[0]].map((model, index) => ({ ...model, priority: index + 1 }));
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(runningModels) });
    }
    if (route.request().method() === "PUT" && route.request().url().endsWith("/order")) {
      runningModels = [runningModels[1], runningModels[0]].map((model, index) => ({ ...model, priority: index + 1 }));
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(runningModels) });
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(runningModels) });
  });
  await page.route("**/api/v0/consultation-agent-draft**", async route => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/catalog")) return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      models: [
        { modelId: "deepseek:deepseek-chat", provider: "deepseek", modelName: "deepseek-chat", displayName: "DeepSeek Chat", enabled: true, configured: true, dataChannel: "APPROVED_BACKEND_REFERENCE", availability: "后端配置完整" },
        { modelId: "deepseek:deepseek-reasoner", provider: "deepseek", modelName: "deepseek-reasoner", displayName: "DeepSeek Reasoner", enabled: true, configured: true, dataChannel: "APPROVED_BACKEND_REFERENCE", availability: "后端配置完整" },
        { modelId: "qwen:qwen-max", provider: "qwen", modelName: "qwen-max", displayName: "Qwen Max", enabled: true, configured: false, dataChannel: "APPROVED_BACKEND_REFERENCE", availability: "服务端密钥尚未配置" }],
      scenarios: [{ id: "PATENT", label: "专利咨询" }, { id: "TRADEMARK", label: "商标咨询" }],
      tools: [{ id: "PATENT_INFORMATION", label: "专利资料查询" }, { id: "TRADEMARK_INFORMATION", label: "商标资料查询" }],
      switchableFailures: ["TIMEOUT", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE"], limitation: "不返回端点或密钥。"
    }) });
    if (url.pathname.endsWith("/validate")) return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ publishReady: false, draftVersion: 2, issues: [{ code: "FORMAL_DEPENDENCIES_PENDING", message: "正式提示词、意图、对话流版本及发布权限尚未确认" }], limitation: "不发布", validatedAt: "2026-09-17T12:01:00Z" }) });
    if (route.request().method() === "PUT") { savedBody = route.request().postDataJSON(); return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ ...draft, ...savedBody, version: 2 }) }); }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(draft) });
  });
  await page.goto("consultation/agent-configuration");
  await expect(page.getByText("DeepSeek Chat", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("DeepSeek Reasoner", { exact: true }).first()).toBeVisible();
  await expect(page.getByText(/test-secret/i)).toHaveCount(0);
  await page.getByRole("button", { name: "新增模型" }).click();
  const modelDialog = page.getByRole("dialog", { name: "新增模型" });
  await expect(modelDialog.getByLabel("配置编号")).toBeVisible();
  await expect(modelDialog.getByLabel("API Key")).toBeVisible();
  await modelDialog.getByRole("button", { name: "取消" }).click();
  await expect(modelDialog).toHaveCount(0);
  await page.getByRole("button", { name: "保存草稿" }).click();
  await expect.poll(() => savedBody?.modelChain).toEqual(["deepseek:deepseek-chat", "deepseek:deepseek-reasoner"]);
  await expect(page.getByText("待显式应用")).toBeVisible();
  await page.getByRole("button", { name: "应用模型顺序" }).click();
  await expect.poll(() => applyDraftRequest?.method).toBe("POST");
  expect(applyDraftRequest.url).toMatch(/\/model-configurations\/apply-consultation-agent-draft$/);
  await expect(page.getByText("已应用草稿模型链到实际运行顺序")).toBeVisible();
  await expect(page.getByText("已应用到运行服务")).toBeVisible();
  await page.getByRole("button", { name: "校验当前草稿" }).click();
  await expect(page.getByText("仍不能正式发布")).toBeVisible();
  await expect(page.getByText("正式提示词、意图、对话流版本及发布权限尚未确认")).toBeVisible();
});

test("comparison workbenches honor public-data capability boundaries", async ({ page }) => {
  await page.route("**/api/v0/patent-comparisons/preparations", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ dataMode: "PUBLIC_LOCAL", sourceVersion: "fixture-v1", ruleVersion: "fixture-v1", suggestedFeatures: [] }) }));
  await page.route("**/api/v0/trademark-comparisons/preparations", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ dataMode: "PUBLIC_LOCAL", sourceVersion: "fixture-v1", ruleVersion: "fixture-v1", suggestedCategories: [], suggestedGoodsServices: [], selectedDimensions: ["TEXT"] }) }));
  await page.goto("compare/patents");
  await page.getByRole("button", { name: "准备对照" }).click();
  await expect(page.getByText("资料不完整，无法创建对照任务", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "确认并创建任务" })).toHaveCount(0);

  await page.goto("compare/trademarks");
  await page.getByRole("button", { name: "准备对照" }).click();
  await expect(page.getByText("资料不完整，无法创建对照任务", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "确认并创建任务" })).toHaveCount(0);
});

test("enforcement and adjudication expose development workflow entry points", async ({ page }) => {
  await installDevelopmentSourceRoutes(page);
  await page.goto("enforcement");
  await expect(page.getByRole("button", { name: "创建工作项" })).toBeEnabled();
  await expect(page.getByText("宁江瑞衡新材料有限公司", { exact: false })).toBeVisible();

  await page.goto("adjudication");
  await expect(page.getByRole("button", { name: "创建裁决工作项" })).toBeEnabled();
  await expect(page.getByText("V0-SYN-ADJ-CASE-001", { exact: false }).first()).toBeVisible();
});

test("remaining workbenches do not overflow a 390px viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const [path, title] of routes) {
    await page.goto(path);
    await expect(page.getByRole("heading", { name: title })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth), path).toBeLessThanOrEqual(0);
  }
});
