import { expect, test } from "@playwright/test";

const draft = {
  agentId: "CONSULTATION-AGENT-001", name: "知识产权咨询助手", description: "内部开发草稿",
  scenarioIds: ["PATENT"], modelChain: ["first"], switchOn: ["TIMEOUT"],
  toolIds: ["PATENT_INFORMATION"], version: 1, status: "DEVELOPMENT_DRAFT",
  changeNote: "配置模型", updatedAt: "2026-09-20T04:00:00Z"
};

async function installRoutes(page) {
  const models = [
    { modelId: "first", displayName: "First model", provider: "test", modelName: "test-1", endpoint: "https://example.com/chat", enabled: true, priority: 1, dataChannel: "PUBLIC_LOCAL", credentialConfigured: true },
    { modelId: "second", displayName: "Second model", provider: "test", modelName: "test-2", endpoint: "https://example.com/chat", enabled: true, priority: 2, dataChannel: "PUBLIC_LOCAL", credentialConfigured: true }
  ];
  const requests = [];
  await page.route("**/api/v0/model-configurations**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === "GET") return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(models) });
    requests.push({ path, method: request.method(), body: request.postDataJSON?.() });
    if (path.endsWith("/order")) {
      const order = request.postDataJSON().modelIds;
      models.sort((left, right) => order.indexOf(left.modelId) - order.indexOf(right.modelId));
      models.forEach((model, index) => { model.priority = index + 1; });
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(models) });
    }
    if (path.endsWith("/test")) {
      const id = path.split("/").at(-2);
      const model = models.find(item => item.modelId === id);
      Object.assign(model, { lastTestStatus: "SUCCEEDED", lastTestedAt: "2026-09-20T04:01:00Z" });
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(model) });
    }
    const value = request.postDataJSON();
    const id = decodeURIComponent(path.split("/").at(-1));
    const existing = models.find(item => item.modelId === id);
    const response = { ...existing, ...value, modelId: id, credentialConfigured: true, dataChannel: "PUBLIC_LOCAL" };
    if (existing) Object.assign(existing, response); else models.push(response);
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(response) });
  });
  await page.route("**/api/v0/consultation-agent-draft**", route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/catalog")) return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      models: [{ modelId: "fallback-only", displayName: "Fallback-only model", provider: "environment", modelName: "fallback", configured: true, availability: "后端配置完整" }],
      scenarios: [{ id: "PATENT", label: "专利咨询" }], tools: [{ id: "PATENT_INFORMATION", label: "专利资料查询" }],
      switchableFailures: ["TIMEOUT", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE"], limitation: "仅使用本地公开数据。"
    }) });
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(draft) });
  });
  return requests;
}

test("model administration edits without secret echo and exposes test and ordering feedback", async ({ page }) => {
  const requests = await installRoutes(page);
  await page.goto("consultation/agent-configuration");
  await expect(page.getByRole("heading", { name: "已保存运行模型" })).toBeVisible();
  await expect(page.getByText("First model", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("Fallback-only model", { exact: true })).toHaveCount(0);
  await expect(page.getByText(/secret-value/i)).toHaveCount(0);

  await page.getByLabel("变更说明").first().fill("保存草稿反馈");
  await page.getByRole("button", { name: "保存草稿" }).click();
  await expect(page.getByText("草稿已保存，版本 1")).toBeVisible();

  await page.getByRole("button", { name: "编辑 First model" }).click();
  await expect(page.getByLabel("配置编号")).toBeDisabled();
  await expect(page.getByLabel("API Key")).toHaveValue("");
  await page.getByLabel("显示名称").fill("First model updated");
  await page.getByLabel("变更说明").last().fill("update display name");
  await page.getByRole("button", { name: "加密保存模型" }).click();
  await expect(page.getByText("模型配置已更新")).toBeVisible();
  expect(requests.find(item => item.path.endsWith("/first") && item.method === "PUT")?.body.apiKey).toBe("");

  await page.getByRole("button", { name: "测试 First model updated 连接" }).click();
  await expect(page.getByText("连接测试通过")).toBeVisible();
  await page.getByRole("button", { name: "下移 First model updated" }).click();
  await expect(page.getByText("调用顺序已更新")).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: "target/model-configuration-desktop.png", fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  expect(overflow).toBe(false);
  await expect(page.getByRole("button", { name: "停用 First model updated" })).toBeVisible();
  await page.screenshot({ path: "target/model-configuration-mobile.png", fullPage: true });
});
