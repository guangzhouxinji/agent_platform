import { expect, test } from "@playwright/test";

const patentIntent = {
  status: "RESOLVED", parserVersion: "LOCAL-INTENT-V1", question: "查询水性粘结剂双区烘干相关专利", primaryIntentId: "PATENT_SIMILARITY_SEARCH",
  intents: [{ intentId: "PATENT_SIMILARITY_SEARCH", label: "专利相似度检索", category: "PATENT", order: 1,
    matchedSignals: ["专利", "查询"], slots: [], missingSlots: [], plannedRoute: "/search/patents", routeState: "AVAILABLE", reason: "问题包含技术方案和专利检索诉求" }],
  clarificationQuestions: [], limitation: "结果是开发期路由建议，不代表识别准确率。", traceId: "intent-patent-flow", parsedAt: "2026-09-18T04:00:00Z"
};

async function mockIntent(page, result = patentIntent) {
  await page.route("**/api/v0/consultation-intents/parse", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(result) }));
}

async function startFlow(page, question) {
  await page.goto("consultation/flow");
  if (question) await page.getByLabel("咨询问题").fill(question);
  await page.getByRole("button", { name: "识别意图并建立流程" }).click();
  await expect(page.getByRole("heading", { name: "建议执行能力" })).toBeVisible();
}

test("patent consultation flow preserves context and records only real task results", async ({ page }) => {
  await mockIntent(page);
  await page.route("**/api/v0/patent-search-tasks**", route => {
    if (route.request().url().endsWith("/preparations")) return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      inputMode: "TECHNICAL_SOLUTION", input: patentIntent.question, purpose: "TECHNICAL_REFERENCE", scope: {}, suggestedFeatures: ["水性粘结剂", "双区烘干"], modelProvider: "deepseek", modelName: "deepseek-chat", traceId: "prepare-flow", limitation: "必须人工确认"
    }) });
    return route.fulfill({ status: 200, contentType: "application/json", body: route.request().method() === "GET" ? "[]" : JSON.stringify({
      taskId: "PS-FLOW-001", status: "SUCCEEDED", attempt: 1, dataMode: "PUBLIC_LOCAL", actualScope: "本地公开专利快照", totalCandidates: 1,
      scoringVersion: "PUBLIC-LOCAL-TEXT-V1", traceId: "search-flow", inputSnapshot: { input: patentIntent.question },
      candidates: [{ sourceId: "CN-FLOW-1", publicationNumber: "CN100000001A", title: "水性粘结剂双区烘干方法", version: "v1", relevanceLabel: "较高", matchingExcerpt: "摘要命中", matchedFeatures: ["双区烘干"], location: "标题/摘要" }]
    }) });
  });
  await page.route("**/api/v0/consultation-guides**", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ version: "LOCAL-GUIDANCE-V1", status: "DEVELOPMENT_DRAFT", limitation: "开发草稿", guides: [] }) }));
  await page.route("**/api/v0/consultations", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
    consultationId: "CON-FLOW-001", answerId: "ANS-FLOW-001", answerVersion: 1, outcomeType: "ANSWER_READY", issues: [],
    candidates: [{ sourceId: "CN-FLOW-1", version: "v1", location: "标题/摘要", matchBasis: "命中双区烘干" }], sourceLinks: [], content: "已形成带来源的开发期辅助回答。", limitation: "不构成法律结论。"
  }) }));

  await startFlow(page, patentIntent.question);
  await expect(page.getByText("已完成 0 个专业步骤")).toBeVisible();
  await page.getByRole("link", { name: "进入专利检索" }).click();
  await expect(page.getByLabel("检索内容")).toHaveValue(patentIntent.question);
  await page.getByRole("button", { name: "提取特征" }).click();
  await page.getByRole("button", { name: "开始检索" }).click();
  await expect(page.getByText("PS-FLOW-001", { exact: false })).toBeVisible();
  await page.getByRole("link", { name: "返回流程调试" }).click();
  await expect(page.getByText("任务已完成")).toBeVisible();
  await expect(page.getByText("已完成 1 个专业步骤")).toBeVisible();

  await page.getByRole("link", { name: "继续形成咨询答复" }).click();
  await expect(page.getByLabel("咨询问题")).toHaveValue(patentIntent.question);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.getByText("已形成带来源的开发期辅助回答。" )).toBeVisible();
  await page.getByRole("link", { name: "返回流程调试" }).click();
  await page.reload();
  await expect(page.getByText("已完成 2 个专业步骤")).toBeVisible();
  await page.getByRole("button", { name: "开始新的咨询" }).click();
  await expect(page.getByText("等待建立咨询流程")).toBeVisible();
  await expect(page.getByText("已完成 2 个专业步骤")).toHaveCount(0);
});

test("multiple intents route to trademark and case workbenches with confirmed fields", async ({ page }) => {
  const result = {
    status: "MULTI_INTENT", parserVersion: "LOCAL-INTENT-V1", question: "检索清源商标并找侵权案例", primaryIntentId: "TRADEMARK_SIMILARITY_SEARCH",
    intents: [
      { intentId: "TRADEMARK_SIMILARITY_SEARCH", label: "商标相似度检索", category: "TRADEMARK", order: 1, matchedSignals: ["商标", "检索"], slots: [{ name: "trademarkName", value: "清源", sourceRound: 0, sourceType: "USER_EXPLICIT" }, { name: "category", value: "3", sourceRound: 0, sourceType: "USER_EXPLICIT" }], missingSlots: [], plannedRoute: "/search/trademarks", routeState: "AVAILABLE", reason: "识别到商标检索" },
      { intentId: "CASE_REFERENCE", label: "案例参考", category: "CASE", order: 2, matchedSignals: ["案例", "侵权"], slots: [], missingSlots: [], plannedRoute: "/search/cases", routeState: "AVAILABLE", reason: "识别到案例参考" }
    ], clarificationQuestions: [], limitation: "多意图需要分别执行。", traceId: "intent-multi-flow", parsedAt: "2026-09-18T04:00:00Z"
  };
  await mockIntent(page, result);
  await startFlow(page, result.question);
  await expect(page.getByRole("link", { name: "进入商标检索" })).toBeVisible();
  await expect(page.getByRole("link", { name: "进入案例检索" })).toBeVisible();
  await expect(page.getByRole("button", { name: "当前意图无适用统一答复" })).toBeDisabled();

  await page.getByRole("link", { name: "进入商标检索" }).click();
  await expect(page.getByLabel("商标名称")).toHaveValue("清源");
  await expect(page.getByLabel("类别")).toHaveValue("3");
  await page.getByRole("link", { name: "返回流程调试" }).click();
  await page.getByRole("link", { name: "进入案例检索" }).click();
  await expect(page.getByLabel("检索问题")).toHaveValue(result.question);
});

test("clarification remains visible and mobile flow has no horizontal overflow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockIntent(page, {
    status: "NEEDS_CLARIFICATION", parserVersion: "LOCAL-INTENT-V1", question: "帮我看看这个", primaryIntentId: undefined, intents: [],
    clarificationQuestions: ["请说明要查询的是专利、商标还是案例"], limitation: "信息不足时不自动调用工具。", traceId: "intent-clarify-flow", parsedAt: "2026-09-18T04:00:00Z"
  });
  await page.goto("consultation/flow");
  await page.getByLabel("咨询问题").fill("帮我看看这个");
  await page.getByRole("button", { name: "识别意图并建立流程" }).click();
  await expect(page.getByText("请说明要查询的是专利、商标还是案例")).toBeVisible();
  await expect(page.getByRole("button", { name: "当前意图无适用统一答复" })).toBeDisabled();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
});

test("default intake is executable and a missing case branch does not hide the patent route", async ({ page }) => {
  const result = {
    status: "NEEDS_CLARIFICATION", parserVersion: "LOCAL-INTENT-V1", question: "请检索水性粘结剂双区烘干技术方案，用于技术参考，并找可参考案例",
    intents: [
      { intentId: "PATENT_SIMILAR_SEARCH", label: "专利相似检索", category: "PATENT", order: 1, matchedSignals: ["技术方案", "检索"], slots: [], missingSlots: [], plannedRoute: "/search/patents", routeState: "AVAILABLE", reason: "专利检索输入完整" },
      { intentId: "CASE_REFERENCE", label: "案例参考", category: "CASE", order: 2, matchedSignals: ["案例"], slots: [], missingSlots: ["specificIssueOrFacts"], routeState: "NOT_READY", reason: "缺少争议事实" }
    ],
    clarificationQuestions: ["案例参考还需要补充：具体争议或关键事实"], limitation: "开发期路由建议。", traceId: "intent-partial-ready", parsedAt: "2026-09-18T04:00:00Z"
  };
  await mockIntent(page, result);
  await page.goto("consultation/flow");
  await expect(page.getByLabel("咨询问题")).toHaveValue("请检索水性粘结剂双区烘干技术方案，用于技术参考");
  await page.getByRole("button", { name: "专利检索" }).click();
  await expect(page.getByLabel("咨询问题")).toHaveValue("请检索水性粘结剂双区烘干技术方案，用于技术参考");
  await page.getByLabel("咨询问题").fill(result.question);
  await page.getByRole("button", { name: "识别意图并建立流程" }).click();
  await expect(page.getByRole("link", { name: "进入专利检索" })).toBeVisible();
  await expect(page.getByRole("button", { name: "补充信息后重新识别" })).toBeVisible();
});

test("failed workbench task remains visible but does not advance flow progress", async ({ page }) => {
  await mockIntent(page);
  await page.route("**/api/v0/patent-search-tasks**", route => {
    if (route.request().url().endsWith("/preparations")) return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ suggestedFeatures: ["双区烘干"], limitation: "必须人工确认" }) });
    return route.fulfill({ status: 200, contentType: "application/json", body: route.request().method() === "GET" ? "[]" : JSON.stringify({
      taskId: "PS-FLOW-FAILED", status: "FAILED", attempt: 1, dataMode: "PUBLIC_LOCAL", actualScope: "本地公开专利快照", totalCandidates: 0,
      scoringVersion: "PUBLIC-LOCAL-TEXT-V1", traceId: "failed-flow", errorCode: "TOOL_FAILURE", inputSnapshot: { input: patentIntent.question }, candidates: []
    }) });
  });
  await startFlow(page, patentIntent.question);
  await page.getByRole("link", { name: "进入专利检索" }).click();
  await page.getByRole("button", { name: "提取特征" }).click();
  await page.getByRole("button", { name: "开始检索" }).click();
  await expect(page.getByText("处理失败", { exact: true }).first()).toBeVisible();
  await page.getByRole("link", { name: "返回流程调试" }).click();
  await expect(page.getByText("任务状态：FAILED")).toBeVisible();
  await expect(page.getByText("已完成 0 个专业步骤")).toBeVisible();
});
