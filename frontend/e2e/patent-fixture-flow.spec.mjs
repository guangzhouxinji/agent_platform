import { expect, test } from "@playwright/test";

async function create(page, withFeature = true) {
  await page.goto("consultation/patent-fixture");
  await page.getByLabel("方案描述").fill("温度控制装置记录读数并驱动开关");
  if (withFeature) await page.getByLabel("关键技术特征（每行一项，可稍后补充）").fill("测温单元");
  await page.getByRole("button", { name: "创建任务" }).click();
  await expect(page.getByText("任务版本")).toBeVisible();
}

test("source, selection, comparison and correction stay in one task", async ({ page }) => {
  await create(page);
  await page.getByRole("textbox", { name: "样例检索词" }).fill("测温");
  await page.getByRole("button", { name: "检索", exact: true }).click();
  await expect(page.getByText("样例：多点传感组件")).toBeVisible();
  await page.getByRole("button", { name: "核对样例出处" }).first().click();
  await expect(page.getByText("样例片段：多个传感单元向处理模块发送测量值。")).toBeVisible();
  await page.locator(".ant-drawer-close").click();
  await page.getByRole("button", { name: "选择此版本" }).first().click();
  await expect(page.getByText("已选择")).toBeVisible();
  await page.getByRole("button", { name: "对选中文献生成待核对表" }).click();
  await expect(page.getByRole("cell", { name: "未知，待专业核对" })).toBeVisible();
  await page.getByRole("textbox", { name: "修订测温单元" }).fill("多点测温");
  await page.getByRole("button", { name: /修.*订/ }).click();
  await expect(page.getByText("原候选已失效，请重新检索。")).toBeVisible();
  await expect(page.getByRole("button", { name: "对选中文献生成待核对表" })).toBeDisabled();
  await page.getByRole("textbox", { name: "样例检索词" }).fill("多点测温");
  await page.getByRole("button", { name: "检索", exact: true }).click();
  await expect(page.getByRole("button", { name: "选择此版本" })).toHaveCount(2);
  await page.getByRole("button", { name: "准备人工交接" }).click();
  await expect(page.getByText("人工交接：未接通，不能宣称已受理")).toBeVisible();
  await page.screenshot({ path: "target/patent-fixture-desktop.png", fullPage: true });
});

test("missing features, no result, failure and mobile width", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await create(page, false);
  await expect(page.getByText("缺少关键特征，补充后才能检索。")).toBeVisible();
  await page.getByRole("textbox", { name: "补充关键特征" }).fill("控制单元");
  await page.getByRole("button", { name: "补充特征" }).click();
  await page.getByRole("textbox", { name: "样例检索词" }).fill("fixture:no-result");
  await page.getByRole("button", { name: "检索", exact: true }).click();
  await expect(page.getByText("最近检索：没有样例结果")).toBeVisible();
  await page.getByRole("button", { name: "以无证据结束" }).click();
  await expect(page.getByText("证据不足")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false);
  await page.screenshot({ path: "target/patent-fixture-mobile.png", fullPage: true });
});

test("source failure is visible without invented candidates", async ({ page }) => {
  await create(page);
  await page.getByRole("textbox", { name: "样例检索词" }).fill("fixture:failure");
  await page.getByRole("button", { name: "检索", exact: true }).click();
  await expect(page.getByText(/最近检索：.*故障/)).toBeVisible();
  await expect(page.getByRole("button", { name: "选择此版本" })).toHaveCount(0);
});

test("stale task version exposes a refresh action", async ({ page }) => {
  await create(page);
  await page.route("**/patent-fixture/*/searches", (route) => {
    const body = route.request().postDataJSON();
    route.continue({ postData: JSON.stringify({ ...body, expectedTaskVersion: 0 }) });
  });
  await page.getByRole("textbox", { name: "样例检索词" }).fill("测温");
  await page.getByRole("button", { name: "检索", exact: true }).click();
  await expect(page.getByText("任务已发生变化，请刷新任务后再操作。")).toBeVisible();
  await page.getByRole("button", { name: "刷新任务" }).click();
  await expect(page.getByText("任务已发生变化，请刷新任务后再操作。")).toHaveCount(0);
});
