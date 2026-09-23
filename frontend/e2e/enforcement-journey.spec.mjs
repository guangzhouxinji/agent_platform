import { expect, test } from "@playwright/test";

test("enforcement journey exposes only real stage entries and remains usable on mobile", async ({ page }) => {
  await page.goto("enforcement");

  await expect(page.getByRole("heading", { name: "执法工作台", level: 1 })).toBeVisible();
  await expect(page.getByRole("heading", { name: "发现问题", level: 2 })).toBeVisible();
  await expect(page.getByRole("heading", { name: "核实线索", level: 2 })).toBeVisible();
  await expect(page.getByRole("heading", { name: "形成材料", level: 2 })).toBeVisible();
  await expect(page.getByRole("heading", { name: "人工处置 / 移交", level: 2 })).toBeVisible();
  await expect(page.getByText("当前不可用")).toBeVisible();
  await expect(page.getByRole("button", { name: /立案|处罚|签发/ })).toHaveCount(0);

  await expect(page.getByRole("link", { name: /进入线索发现/ })).toHaveAttribute("href", "/app/enforcement/discovery");
  await expect(page.getByRole("link", { name: /进入线索解析/ })).toHaveAttribute("href", "/app/enforcement/parsing");
  await expect(page.getByRole("link", { name: /进入证据材料/ })).toHaveAttribute("href", "/app/enforcement/evidence");
  await page.getByRole("link", { name: /进入线索发现/ }).click();
  await expect(page).toHaveURL(/\/app\/enforcement\/discovery$/);

  await page.goto("enforcement");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)).toBe(false);
  await expect(page.getByRole("link", { name: /进入证据材料/ })).toBeVisible();
  await page.screenshot({ path: "target/enforcement-journey-mobile.png", fullPage: true });
});
