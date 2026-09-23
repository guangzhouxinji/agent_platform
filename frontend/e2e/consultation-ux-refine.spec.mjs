import { expect, test } from "@playwright/test";

const routeResult = (overrides = {}) => ({
  intentId: "PATENT_SIMILAR_SEARCH", label: "专利相似检索", category: "PATENT", status: "ANSWER_READY",
  taskId: "PS-1", summary: "已根据当前输入整理专利候选。", actualScope: "本地公开专利", limitation: "不构成法律结论。",
  nextAction: "可选择候选继续技术特征对照", nextActionHref: "/app/compare/patents", candidates: [], sources: [], ...overrides
});

const answer = (overrides = {}) => ({
  consultationId: "DC-1", status: "ANSWER_READY", parserVersion: "LOCAL-INTENT-V1", primaryIntentId: "PATENT_SIMILAR_SEARCH",
  clarificationQuestions: [], results: [routeResult()], content: "已识别 1 项诉求，完成 1 项。",
  limitation: "统一入口只编排现有本地开发能力。", ...overrides
});

async function mockDaily(page, handler = () => answer()) {
  const calls = [];
  await page.route("**/api/v0/consultations/daily", async (route) => {
    const body = JSON.parse(route.request().postData() || "{}");
    calls.push(body);
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(handler(body, calls.length)) });
  });
  return calls;
}

test("single chat entry presents routed candidates and next action", async ({ page }) => {
  const calls = await mockDaily(page, () => answer({ results: [routeResult({ candidates: [{ sourceId: "CN-1", version: "v1", title: "双区烘干方法", identifier: "CN1A", location: "权利要求1", matchBasis: "命中双区烘干" }] })] }));
  await page.goto("consultation");
  await page.getByLabel("咨询问题").fill("检索双区烘干相关专利，用于技术参考");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByText("已识别 1 项诉求，完成 1 项。")).toBeVisible();
  await expect(page.getByText("查看 1 条候选")).toBeVisible();
  await expect(page.getByRole("link", { name: "继续处理" })).toHaveAttribute("href", "/app/compare/patents");
  expect(calls[0]).toEqual({ question: "检索双区烘干相关专利，用于技术参考", context: [] });
});

test("clarification stays in the conversation without calling a separate workbench", async ({ page }) => {
  await mockDaily(page, () => answer({ status: "NEEDS_CLARIFICATION", clarificationQuestions: ["商标相似检索还需要补充：商标名称、类别或商品/服务"], results: [routeResult({ intentId: "TRADEMARK_SIMILAR_SEARCH", label: "商标相似检索", status: "NEEDS_CLARIFICATION", taskId: "", summary: "还需要补充商标名称和商品服务。", candidates: [], sources: [], nextAction: "补充信息后重新发送", nextActionHref: "" })], content: "已识别 1 项诉求，其中 1 项需要补充信息。" }));
  await page.goto("consultation");
  await page.getByLabel("咨询问题").fill("帮我做商标近似检索");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByText("需要补充", { exact: true })).toBeVisible();
  await expect(page.getByText("还需要补充商标名称和商品服务。")).toBeVisible();
  await expect(page.getByLabel("咨询问题")).toBeVisible();
});

test("later messages carry prior user questions as explicit context", async ({ page }) => {
  const calls = await mockDaily(page, (_body, count) => answer({ content: count === 1 ? "第一项结果已完成。" : "已结合上一轮问题继续处理。" }));
  await page.goto("consultation");
  await page.getByLabel("咨询问题").fill("检索涂布工艺专利，用于技术参考");
  await page.getByRole("button", { name: "发送" }).click();
  await page.getByLabel("咨询问题").fill("再补充双区烘干条件");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByText("已结合上一轮问题继续处理。")).toBeVisible();
  expect(calls[1].context).toEqual([{ round: 1, content: "检索涂布工艺专利，用于技术参考" }]);
  await expect(page.locator(".user-message")).toHaveCount(2);
});

test("a new consultation clears previous result states", async ({ page }) => {
  await mockDaily(page, () => answer({ status: "TOOL_FAILURE", results: [routeResult({ status: "TOOL_FAILURE", summary: "检索工具当前不可用。" })], content: "本次工具执行失败。" }));
  await page.goto("consultation");
  await page.getByLabel("咨询问题").fill("查询历史专利资料");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByText("检索工具当前不可用。")).toBeVisible();
  await page.getByRole("button", { name: "新建咨询" }).click();
  await expect(page.getByText("检索工具当前不可用。")).toHaveCount(0);
  await expect(page.getByText("从一条问题开始")).toBeVisible();
});

test("patent source is rechecked when opened", async ({ page }) => {
  let sourceRequested = false;
  await mockDaily(page, () => answer({ results: [routeResult({ sources: [{ sourceId: "CN-1", version: "v1", title: "双区烘干方法", location: "权利要求1", sourceHref: "/api/v0/consultations/C-1/source-links/L-1" }] })] }));
  await page.route("**/api/v0/consultations/C-1/source-links/L-1", async (route) => { sourceRequested = true; await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ sourceId: "CN-1", version: "v1", location: "权利要求1" }) }); });
  await page.goto("consultation");
  await page.getByLabel("咨询问题").fill("检索双区烘干专利，用于技术参考");
  await page.getByRole("button", { name: "发送" }).click();
  await page.getByText("查看 1 个来源").click();
  await page.getByRole("button", { name: /双区烘干方法/ }).click();
  await expect(page.getByText("权利要求1", { exact: true })).toBeVisible();
  expect(sourceRequested).toBe(true);
});

test("question guidance only fills the compact composer", async ({ page }) => {
  await page.route("**/api/v0/consultation-guides**", async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ guides: [{ guideId: "g1", title: "案例参考", question: "商标侵权二审有哪些可参考案例" }] }) }));
  await page.goto("consultation");
  await expect(page.getByLabel("咨询问题")).toHaveAttribute("rows", "2");
  await page.getByRole("button", { name: "问题示例" }).click();
  await page.getByRole("button", { name: /案例参考/ }).click();
  await expect(page.getByLabel("咨询问题")).toHaveValue("商标侵权二审有哪些可参考案例");
  await expect(page.locator(".consultation-messages")).toHaveCSS("overflow-y", "auto");
});

test("composer stays fully visible at the bottom of the consultation workspace", async ({ page }) => {
  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    await page.setViewportSize(viewport);
    await page.goto("consultation");
    const bounds = await page.locator(".consultation-composer").boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds.y + bounds.height).toBeLessThanOrEqual(viewport.height);
    await expect(page.getByLabel("咨询问题")).toBeVisible();
  }
});

test("multi-route reply has no horizontal overflow on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockDaily(page, () => answer({ status: "PARTIAL_RESULT", results: [routeResult(), routeResult({ intentId: "CASE_REFERENCE", label: "案例参考", status: "NEEDS_CLARIFICATION", summary: "需要补充具体争议事实。", taskId: "", nextActionHref: "", candidates: [] })] }));
  await page.goto("consultation");
  await page.getByLabel("咨询问题").fill("检索相关专利并找案例");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.locator(".daily-route-result")).toHaveCount(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
});

for (const viewport of [{ name: "desktop", width: 1440, height: 900 }, { name: "mobile", width: 390, height: 844 }]) {
  test(`composer stays docked and visible with independently scrolling messages on ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const candidates = Array.from({ length: 36 }, (_, index) => ({
      sourceId: `CN-${index + 1}`, version: "v1", title: `较长候选专利标题 ${index + 1}`,
      identifier: `CN${index + 1}A`, location: "权利要求1", matchBasis: "命中水性粘结剂、制备工艺及应用场景"
    }));
    await mockDaily(page, () => answer({ results: [routeResult({ candidates })] }));
    await page.goto("consultation");
    await page.getByLabel("咨询问题").fill("水性粘结剂及其应用");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("查看 36 条候选")).toBeVisible();
    await page.getByText("查看 36 条候选").click();
    await expect(page.locator(".reply-evidence .candidate-card").first()).toBeVisible();
    await page.waitForTimeout(350);

    const layout = await page.evaluate(() => {
      const composer = document.querySelector(".consultation-composer").getBoundingClientRect();
      const messages = document.querySelector(".consultation-messages");
      return {
        composerTop: composer.top,
        composerBottom: composer.bottom,
        viewportHeight: window.innerHeight,
        messagesBottom: messages.getBoundingClientRect().bottom,
        messagesClientHeight: messages.clientHeight,
        messagesScrollHeight: messages.scrollHeight,
        horizontalOverflow: document.documentElement.scrollWidth - window.innerWidth
      };
    });
    expect(layout.composerTop).toBeGreaterThanOrEqual(0);
    expect(layout.composerBottom).toBeLessThanOrEqual(layout.viewportHeight);
    expect(layout.messagesBottom).toBeLessThanOrEqual(layout.composerTop);
    expect(layout.messagesScrollHeight).toBeGreaterThan(layout.messagesClientHeight);
    expect(layout.horizontalOverflow).toBeLessThanOrEqual(0);
    await page.getByLabel("咨询问题").focus();
    await expect(page.getByLabel("咨询问题")).toBeFocused();
  });
}
