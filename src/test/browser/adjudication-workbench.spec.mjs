import { expect, test } from "@playwright/test";

async function openAdjudication(page, material = "V0-SYN-ADJ-MAT-001") {
  await page.goto("/");
  await page.getByRole("button", { name: "裁决验证" }).click();
  const workspace = page.locator("#adjudication-workspace");
  await workspace.getByLabel("合成材料").selectOption(material);
  await workspace.getByRole("button", { name: "创建裁决工作项" }).click();
  await expect(workspace.getByText("裁决工作项已创建")).toBeVisible();
  return workspace;
}

async function prepareDraft(workspace) {
  await workspace.getByRole("button", { name: "检查材料" }).click();
  await expect(workspace.getByText("材料检查已完成")).toBeVisible();
  await workspace.getByRole("button", { name: "生成法规候选" }).click();
  await expect(workspace.getByText("法规候选已生成")).toBeVisible();
  await workspace.getByRole("button", { name: "选择法规引用" }).click();
  await expect(workspace.getByText("法规引用已留痕")).toBeVisible();
  await workspace.getByRole("button", { name: "生成合成草稿" }).click();
  await expect(workspace.getByText("合成草稿已生成")).toBeVisible();
}

test("completes the synthetic adjudication workbench with traceable sources", async ({ page }) => {
  const workspace = await openAdjudication(page);
  await prepareDraft(workspace);
  await expect(workspace.locator("#adjudication-materials")).toContainText("待核实技术特征");
  await expect(workspace.locator("#adjudication-candidates")).toContainText("V0-SYN-ADJ-LAW-001");
  await expect(workspace.locator("#adjudication-drafts")).toContainText("NOT_OFFICIAL_DECISION");
  await expect(workspace.locator("#adjudication-drafts")).toContainText("合成草稿");
  await expect(workspace.locator("#adjudication-history .version-button")).toHaveCount(4);

  await workspace.locator("[data-adjudication-source]").first().click();
  await expect(page.getByRole("dialog")).toContainText("V0-SYN-ADJ-CASE-001");
  await page.getByRole("button", { name: "关闭来源详情" }).click();
  await workspace.locator("#adjudication-history .version-button").last().click();
  await expect(page.getByRole("dialog")).toContainText("draftId");
  await page.screenshot({ path: "target/playwright-artifacts/adjudication-desktop.png", fullPage: true });
});

test("keeps incomplete and conflicting material visible and blocks confirmation", async ({ page }) => {
  const workspace = await openAdjudication(page, "V0-SYN-ADJ-MAT-002");
  await workspace.getByRole("button", { name: "检查材料" }).click();
  await workspace.getByRole("button", { name: "生成合成草稿" }).click();
  await expect(workspace.locator("#adjudication-materials")).toContainText("专利比对材料");
  await expect(workspace.locator("#adjudication-materials")).toContainText("发生日期存在两种记载");
  await workspace.getByRole("button", { name: "确认测试复核" }).click();
  await expect(workspace.getByText("材料不足或存在冲突，不能确认")).toBeVisible();
  await expect(workspace.getByText("MATERIALS_INSUFFICIENT")).toBeVisible();
});

test("revises a draft, records test review and reads exact history", async ({ page }) => {
  const workspace = await openAdjudication(page);
  await prepareDraft(workspace);
  await workspace.getByLabel("草稿修订内容").fill("浏览器人工合成修订内容");
  await workspace.getByRole("button", { name: "修订草稿" }).click();
  await expect(workspace.getByText("草稿修订版本已保存")).toBeVisible();
  await expect(workspace.locator("#adjudication-drafts")).toContainText("浏览器人工合成修订内容");
  await expect(workspace.locator("#adjudication-history")).toContainText("合成草稿 v1");
  await expect(workspace.locator("#adjudication-history")).toContainText("合成草稿 v2");
  await workspace.getByRole("button", { name: "确认测试复核" }).click();
  await expect(workspace.getByText("测试复核已留痕")).toBeVisible();
  await expect(workspace.locator("#adjudication-reviews")).toContainText("CONFIRM");
  await expect(workspace.locator("#adjudication-reviews")).toContainText("v0-adjudication-reviewer");
});

test("shows authorization failure and remains usable on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "裁决验证" }).click();
  const workspace = page.locator("#adjudication-workspace");
  await workspace.getByLabel("裁决身份").selectOption("unknown");
  await workspace.getByRole("button", { name: "创建裁决工作项" }).click();
  await expect(workspace.getByText("当前身份无权执行该操作")).toBeVisible();
  await expect(workspace.getByText("UNAUTHORIZED")).toBeVisible();
  await expect(workspace.getByText("SYNTHETIC_V0")).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await page.screenshot({ path: "target/playwright-artifacts/adjudication-mobile.png", fullPage: true });
});

test("loads an expanded case and only its version-linked materials", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "裁决验证" }).click();
  const workspace = page.locator("#adjudication-workspace");
  const caseSelect = workspace.getByLabel("合成案件");
  await expect(caseSelect.locator("option")).toHaveCount(20);
  await caseSelect.selectOption("V0-SYN-ADJ-CASE-020");
  await expect(workspace.getByLabel("合成材料").locator("option")).toHaveCount(4);
  await workspace.getByLabel("合成材料").selectOption("V0-SYN-ADJ-MAT-080");
  await workspace.getByRole("button", { name: "创建裁决工作项" }).click();
  await expect(workspace.locator("#adjudication-summary")).toContainText("V0-SYN-ADJ-CASE-020 · v2");
  await expect(workspace.locator("#adjudication-summary")).toContainText("V0-SYN-ADJ-MAT-080");
  await workspace.getByRole("button", { name: "检查材料" }).click();
  await expect(workspace.locator("#adjudication-materials")).toContainText("工业视觉检测设备");
});
