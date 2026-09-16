import { expect, test } from "@playwright/test";

async function openEnforcement(page, clue = "V0-SYN-CLUE-001") {
  await page.goto("/");
  await page.getByRole("button", { name: "执法验证" }).click();
  await page.getByLabel("合成线索").selectOption(clue);
  await page.getByRole("button", { name: "创建工作项" }).click();
  await expect(page.getByText("工作项已创建")).toBeVisible();
}

test("processes evidence and exposes traceable synthetic analysis", async ({ page }) => {
  await openEnforcement(page);
  await page.getByRole("button", { name: "生成辅助分析" }).click();
  await expect(page.getByText("辅助分析已生成")).toBeVisible();
  await expect(page.locator("#enforcement-fields")).toContainText("合成投诉人甲");
  await expect(page.locator("#enforcement-evidence .record-row")).toHaveCount(3);
  await expect(page.locator("#enforcement-relations")).toContainText("候选关系");
  await expect(page.locator("#enforcement-relations")).toContainText("SUPPORT");
  await expect(page.locator("#enforcement-relations")).toContainText("OCCURRENCE");
  await expect(page.locator("#enforcement-analysis")).toContainText("NOT_OFFICIAL_DECISION");
  await expect(page.locator("#enforcement-analysis")).toContainText("CONFIRMED_FACT");
  await expect(page.locator("#enforcement-analysis")).toContainText("仅为流程事实");
  await expect(page.locator("#enforcement-history .version-button")).toHaveCount(5);

  await page.locator("#enforcement-history .version-button").last().click();
  await expect(page.getByRole("dialog")).toContainText("analysisResultId");
  await page.getByRole("button", { name: "关闭来源详情" }).click();
  await page.locator("#enforcement-fields [data-enforcement-source]").first().click();
  await expect(page.getByRole("dialog")).toContainText("V0-SYN-CLUE-001");
  await page.screenshot({ path: "target/playwright-artifacts/enforcement-desktop.png", fullPage: true });
});

test("shows conflict, missing material and unsupported evidence without false success", async ({ page }) => {
  await openEnforcement(page, "V0-SYN-CLUE-002");
  await page.getByLabel("不可解析音视频 · V0-SYN-EVD-007").check();
  await page.getByRole("button", { name: "生成辅助分析" }).click();
  await expect(page.getByText("材料不足，已保留可用结果")).toBeVisible();
  await expect(page.locator("#enforcement-fields")).toContainText("2026-08-03");
  await expect(page.locator("#enforcement-fields")).toContainText("2026-08-12");
  await expect(page.locator("#enforcement-relations")).toContainText("UNSUPPORTED_CONTENT");
  await expect(page.locator("#enforcement-analysis")).toContainText("MATERIALS_INSUFFICIENT");
});

test("records human revision and review while preserving exact versions", async ({ page }) => {
  await openEnforcement(page);
  await page.getByRole("button", { name: "生成辅助分析" }).click();
  await page.getByRole("button", { name: "修订线索分析" }).click();
  await expect(page.getByText("线索修订版本已保存")).toBeVisible();
  await expect(page.locator("#enforcement-fields")).toContainText("版本 2");
  await expect(page.locator("#enforcement-history")).toContainText("线索分析 v1");
  await expect(page.locator("#enforcement-history")).toContainText("线索分析 v2");

  await page.getByRole("button", { name: "确认辅助分析" }).click();
  await expect(page.getByText("人工审核已留痕")).toBeVisible();
  await expect(page.locator("#enforcement-analysis")).toContainText("CONFIRM");
  await expect(page.locator("#enforcement-analysis")).toContainText("v0-enforcement-reviewer");
});

test("renders authorization and version errors and remains usable on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "执法验证" }).click();
  await page.getByLabel("操作身份").selectOption("unknown");
  await page.getByRole("button", { name: "创建工作项" }).click();
  await expect(page.getByText("当前身份无权执行该操作")).toBeVisible();
  await expect(page.getByText("UNAUTHORIZED")).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await expect(page.locator("#enforcement-workspace").getByText("SYNTHETIC_V0")).toBeVisible();
  await page.screenshot({ path: "target/playwright-artifacts/enforcement-mobile.png", fullPage: true });
});

test("loads an expanded clue with its related evidence and source version", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "执法验证" }).click();
  const clue = page.getByLabel("合成线索");
  await expect(clue.locator("option")).toHaveCount(30);
  await clue.selectOption("V0-SYN-CLUE-006");
  await expect(page.locator("#evidence-options input")).toHaveCount(4);
  await page.getByRole("button", { name: "创建工作项" }).click();
  await expect(page.locator("#enforcement-summary")).toContainText("V0-SYN-CLUE-006 · v2");
  await page.getByRole("button", { name: "生成辅助分析" }).click();
  await expect(page.locator("#enforcement-fields")).toContainText("东港澄岳科技有限公司");
});
