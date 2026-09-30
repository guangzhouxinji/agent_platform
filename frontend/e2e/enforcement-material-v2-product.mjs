import { chromium } from "playwright";

const [base, materialId] = process.argv.slice(2);
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  await page.goto(`${base}/app/enforcement/material`);
  await page.getByRole("button", { name: "登录" }).click();
  const username = page.locator('input[name="username"]');
  try {
    await username.waitFor({ timeout: 10000 });
  } catch (error) {
    process.stderr.write(`ENF_BROWSER_LOGIN_DIAGNOSTIC url=${page.url()} title=${await page.title()} `
      + `body=${(await page.locator("body").innerText()).slice(0, 400)}\n`);
    throw error;
  }
  await username.fill("enf-http-operator");
  await page.locator('input[name="password"]').fill("unique-strong-password-1");
  await page.locator('button[type="submit"]').click();
  await page.getByRole("tab", { name: "材料" }).waitFor();
  await page.getByRole("tab", { name: "材料" }).click();
  await page.locator("input#id:visible").fill(materialId);
  await page.getByRole("button", { name: "读取材料" }).click();
  await page.getByText(materialId, { exact: true }).waitFor();
  const popup = page.waitForEvent("popup");
  const previewResponse = page.waitForResponse(response => response.url().endsWith(`/materials/${materialId}/preview`));
  await page.getByRole("button", { name: "预览原件" }).click();
  if ((await previewResponse).status() !== 200) throw new Error("Original preview was denied before withdrawal");
  const original = await popup;
  await original.close();
  process.stdout.write("ENF_BROWSER_READY\n");
  await new Promise(resolve => process.stdin.once("data", resolve));
  process.stdin.pause();
  await page.getByRole("button", { name: "预览原件" }).click();
  await page.getByText("当前授权不可用，请重新登录").waitFor();
  if (await page.getByText(materialId, { exact: true }).count() !== 0)
    throw new Error("Withdrawn material remained visible");
  if (await page.getByRole("button", { name: "登录" }).count() !== 1)
    throw new Error("Browser retained an authorized session");
  process.stdout.write("ENF_BROWSER_WITHDRAWAL_CLEARED\n");
} finally {
  await browser.close();
}
