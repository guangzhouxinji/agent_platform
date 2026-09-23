import { expect, test } from "@playwright/test";

const envelope = data => ({
  requestId: "req-enforcement-source-e2e",
  resultType: "DEVELOPMENT_SUPPORT",
  data,
  limitations: ["NOT_OFFICIAL_DECISION"]
});

async function installRoutes(page) {
  const sources = [];
  const tasks = new Map();

  await page.route("**/api/v0/enforcement/source-configurations**", async route => {
    const request = route.request();
    const url = new URL(request.url());
    const segments = url.pathname.split("/").filter(Boolean);
    const sourceId = segments[4] ? decodeURIComponent(segments[4]) : undefined;
    const operation = segments[5];

    if (!sourceId && request.method() === "GET") {
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope(sources)) });
    }

    if (operation === "sync-tasks" && request.method() === "GET") {
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope(tasks.get(sourceId) ?? [])) });
    }

    if (operation === "connection-tests" && request.method() === "POST") {
      const source = sources.find(item => item.sourceId === sourceId);
      if (source) source.healthStatus = "HEALTHY";
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope({ result: "SUCCEEDED", checkedAt: "2026-09-20T05:42:00Z" })) });
    }

    if (operation === "sync-tasks" && request.method() === "POST") {
      const task = {
        taskId: "sync-task-001",
        triggerType: "MANUAL",
        status: "succeeded",
        processedCount: 2,
        importedCount: 2,
        failedCount: 0,
        resultCursor: "cursor-002",
        createdAt: "2026-09-20T05:43:00Z",
        completedAt: "2026-09-20T05:43:01Z",
        requestId: "req-sync-001",
        failures: []
      };
      tasks.set(sourceId, [task]);
      const source = sources.find(item => item.sourceId === sourceId);
      if (source) Object.assign(source, { lastSuccessCursor: task.resultCursor, lastSuccessAt: task.completedAt });
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope(task)) });
    }

    if (sourceId && request.method() === "PUT") {
      const value = request.postDataJSON();
      const source = {
        ...value,
        sourceId,
        organizationRef: "ORG-XINJI-DEMO",
        healthStatus: "NOT_TESTED",
        version: 1,
        updatedAt: "2026-09-20T05:41:00Z"
      };
      sources.push(source);
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope(source)) });
    }

    return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ code: "NOT_FOUND" }) });
  });
}

test("enforcement source administration covers empty, create, test, sync, and mobile layout", async ({ page }) => {
  await installRoutes(page);
  await page.goto("enforcement/sources");

  await expect(page.getByText("选择一个数据源查看详情")).toBeVisible();
  await page.getByRole("button", { name: "新增数据源" }).click();
  await page.getByLabel("数据源编号").fill("local-enforcement-feed");
  await page.getByLabel("数据源名称").fill("本地执法采集源");
  await page.getByLabel("负责人").fill("执法业务管理员");
  await page.getByRole("button", { name: "保存配置" }).click();

  await expect(page.getByText("数据源配置已创建")).toBeVisible();
  await expect(page.getByRole("heading", { name: "本地执法采集源", level: 4 })).toBeVisible();
  await expect(page.getByText("local://enforcement/development-feed")).toBeVisible();

  await page.getByRole("button", { name: "检查连接" }).click();
  await expect(page.getByText("连接检查通过")).toBeVisible();
  await expect(page.getByText("连接正常")).toBeVisible();

  await page.getByRole("button", { name: "立即同步" }).click();
  await expect(page.getByText("同步完成").first()).toBeVisible();
  await expect(page.getByText("2 条新增")).toBeVisible();
  await page.screenshot({ path: "target/enforcement-source-management-desktop.png", fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  expect(overflow).toBe(false);
  await expect(page.getByRole("button", { name: "新增数据源" })).toBeVisible();
  await expect(page.getByRole("button", { name: "立即同步" })).toBeVisible();
  await page.screenshot({ path: "target/enforcement-source-management-mobile.png", fullPage: true });
});
