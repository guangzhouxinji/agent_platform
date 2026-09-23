import { expect, test } from "@playwright/test";

const common = {
  dataMode: "PUBLIC_LOCAL",
  sourceVersion: "library-test-v1",
  limitation: "开发期合成记录与公开测试样本，不代表真实中心记录或业务验收。",
  fetchedAt: "2026-09-17T00:00:00Z",
  requestId: "react-library-test"
};

const patent = {
  sourceId: "pat-1", sourceRecordId: "CN117076952A", sourceVersion: "public-v1", applicationNumber: "CN20231001",
  publicationNumber: "CN117076952A", title: "面向知识产权文本的智能检索方法", patentType: "INVENTION",
  applicants: ["示例科技有限公司"], inventors: ["张某"], applicationDate: "2023-01-02", publicationDate: "2023-10-01",
  ipcClassifications: ["G06F16/33"], cpcClassifications: [], sourceLegalStatus: "SOURCE_NOT_PROVIDED",
  sourceRef: "https://example.test/patent", dataMode: "PUBLIC_LOCAL", versions: [{ versionId: "v1", documentNumber: "CN117076952A" }]
};

const trademark = {
  sourceId: "tm-1", sourceRecordId: "50002442", sourceVersion: "public-v1", registrationNumber: "50002442", name: "LEAKONOMICS",
  applicants: ["Example Labs"], classes: [{ code: "35", name: "广告销售", goodsServices: ["商业管理"], similarGroups: [] }],
  registrationDate: "2024-02-01", sourceStatus: "published", displayStatus: "公报事件：published",
  sourceRef: "https://example.test/trademark", dataMode: "PUBLIC_LOCAL"
};

const caseRecord = {
  sourceId: "case-1", sourceRecordId: "6071", sourceVersion: "public-v1", caseNumber: "6071", docketNumber: "（2022）最高法知民终2527号",
  title: "专利权权属纠纷案", caseType: "CIVIL", intellectualPropertyType: "PATENT", institution: "最高人民法院知识产权法庭",
  procedure: "SECOND_INSTANCE", decisionDate: "2023-04-12", outcomeExcerpt: "公开裁判测试摘录",
  sourceRef: "https://example.test/case", dataMode: "PUBLIC_LOCAL", documents: [{ versionId: "judgment-v1", state: "COMPLETE", ocrState: "NOT_APPLICABLE" }], annotations: []
};

async function mockLibraries(page) {
  await page.route("**/api/v0/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    let body;
    if (path === "/api/v0/patent-library") body = { ...common, page: Number(url.searchParams.get("page") || 1), pageSize: Number(url.searchParams.get("pageSize") || 20), total: 1, items: [patent] };
    else if (path === "/api/v0/patent-library/CN117076952A") body = { ...common, item: patent };
    else if (path.endsWith("/documents/v1")) body = { ...common, document: { versionId: "v1", abstractSection: { state: "PARTIAL", texts: [{ text: "可核验摘要" }] }, claimsSection: { state: "SOURCE_NOT_PROVIDED", message: "权利要求未采集" } } };
    else if (path === "/api/v0/trademark-library") body = { ...common, page: 1, pageSize: 20, total: 1, items: [trademark] };
    else if (path === "/api/v0/trademark-library/50002442") body = { ...common, item: trademark, historyCapabilities: { changes: "SOURCE_UNAVAILABLE", oppositions: "SOURCE_UNAVAILABLE", invalidations: "SOURCE_UNAVAILABLE" } };
    else if (path.endsWith("/history/oppositions")) body = { ...common, history: { type: "oppositions", state: "SOURCE_UNAVAILABLE", records: [], error: "来源未采集" } };
    else if (path === "/api/v0/case-library") body = { ...common, page: 1, pageSize: 20, total: 1, items: [caseRecord] };
    else if (path === "/api/v0/case-library/6071") body = { ...common, item: caseRecord };
    else if (path.endsWith("/published-annotation")) body = { ...common, annotation: { state: "NO_PUBLISHED_ANNOTATION", annotation: null, message: "没有已发布标注" } };
    else return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ code: "NOT_FOUND", message: "not mocked" }) });
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });
}

test.beforeEach(async ({ page }) => mockLibraries(page));

test("patent library stores filters in the URL and opens source-aware detail", async ({ page }) => {
  await page.goto("libraries/patents");
  await expect(page.getByRole("heading", { name: "专利资料库" })).toBeVisible();
  await page.getByLabel("公开 / 公告号").fill("CN117076952A");
  await page.getByRole("button", { name: "查询" }).click();
  await expect(page).toHaveURL(/publicationNumber=CN117076952A/);
  await expect(page.locator(".library-limitation")).toHaveCount(0);
  await page.getByRole("button", { name: "查看详情" }).click();
  await expect(page.getByText("来源状态")).toBeVisible();
  await expect(page.locator(".detail-limitation")).toHaveCount(0);
  const width = await page.locator(".detail-drawer").evaluate((element) => element.getBoundingClientRect().width);
  expect(width).toBeGreaterThanOrEqual(840);
  expect(width).toBeLessThanOrEqual(870);
  await page.getByRole("button", { name: "CN117076952A" }).click();
  await expect(page.locator(".extension-panel")).toContainText("权利要求未采集");
});

test("trademark library keeps unavailable history explicit", async ({ page }) => {
  await page.goto("libraries/trademarks?name=LEAKONOMICS&page=1&pageSize=20");
  await expect(page.getByText("LEAKONOMICS").first()).toBeVisible();
  await page.getByRole("button", { name: "查看详情" }).click();
  await page.getByRole("button", { name: /异议信息/ }).click();
  await expect(page.locator(".extension-panel")).toContainText("来源不可用");
  await expect(page.locator(".extension-panel")).toContainText("来源未采集");
});

test("case library uses mobile cards and a full-width detail drawer", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("libraries/cases");
  await expect(page.locator(".record-card")).toHaveCount(1);
  await page.getByRole("button", { name: "查看详情" }).click();
  await expect(page.locator(".ant-drawer").getByText("最高人民法院知识产权法庭")).toBeVisible();
  await expect(page.locator(".detail-drawer .ant-drawer-close")).toBeVisible();
  await page.getByRole("button", { name: "发布标注" }).click();
  await expect(page.locator(".extension-panel")).toContainText("NO_PUBLISHED_ANNOTATION");
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
});

test("library failure stays actionable and exposes the backend trace", async ({ page }) => {
  await page.route("**/api/v0/patent-library?**", (route) => route.fulfill({
    status: 503,
    contentType: "application/json",
    body: JSON.stringify({ code: "SOURCE_UNAVAILABLE", message: "资料源暂不可用", requestId: "trace-503" })
  }));
  await page.goto("libraries/patents");
  await expect(page.getByText("资料源暂不可用")).toBeVisible();
  await expect(page.getByText(/SOURCE_UNAVAILABLE.*trace-503/)).toBeVisible();
  await expect(page.getByRole("button", { name: "重试" })).toBeVisible();
});
