import { expect, test } from "@playwright/test";

test("browses explicitly labelled synthetic center data across domains", async ({ page }) => {
  await page.goto("/synthetic-center.html");
  await expect(page.getByText("SYNTHETIC_V0")).toBeVisible();
  await expect(page.locator("#content")).toContainText("水性粘结剂电极涂布方法");
  await expect(page.locator("#page")).toHaveText("1 / 6");
  await page.getByLabel("搜索当前数据").fill("仓储移动机器人");
  await expect(page.locator("#content")).toContainText("仓储移动机器人调度系统");
  await page.getByRole("button", { name: "执法线索" }).click();
  await expect(page.locator("#content")).toContainText("V0-SYN-CLUE-001");
  await page.getByRole("button", { name: "裁决材料" }).click();
  await expect(page.locator("#content")).toContainText("V0-SYN-ADJ-MAT-001");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
});
