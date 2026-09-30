import { chromium } from "playwright";

const [base] = process.argv.slice(2);
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ ignoreHTTPSErrors: true });
  let bearer;
  const statuses = [];
  page.on("request", request => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith("/api/v0")) throw new Error("V0 request from HANDOFF workspace");
    if (path.startsWith("/v2/enforcement") && request.headers().authorization?.startsWith("Bearer "))
      bearer = request.headers().authorization;
  });
  page.on("response", response => {
    if (new URL(response.url()).pathname.startsWith("/v2/enforcement")) statuses.push(response.status());
  });
  await page.goto(`${base}/app/enforcement/handoff`);
  await page.getByRole("button", { name: "登录" }).click();
  await page.locator('input[name="username"]').fill("named-handoff-receiver");
  await page.locator('input[name="password"]').fill("unique-handoff-receiver-1");
  await page.locator('button[type="submit"]').click();
  await page.getByText("HANDOFF-HTTP-REQ", { exact: true }).first().waitFor();
  await page.getByRole("button", { name: "HANDOFF-HTTP-UI" }).click();
  await page.getByText("HANDOFF-HTTP-UI v1").waitFor();
  await page.locator(".enf5-detail > button").click();
  await page.getByText("HANDOFF-HTTP-UI v2", { exact: true }).waitFor();
  await page.locator(".enf5-detail textarea").nth(0).fill("Review of original time conflict");
  await page.locator(".enf5-detail textarea").nth(1).fill("A and B originals differ");
  await page.locator(".enf5-detail form").first().locator('button[type="submit"]').click();
  await page.getByText("HANDOFF-HTTP-UI v3", { exact: true }).waitFor();
  await page.getByText("Review of original time conflict").waitFor();
  await page.getByRole("button", { name: "HANDOFF-HTTP-REQ" }).click();
  await page.getByText("Human review required").waitFor();
  await page.getByPlaceholder("包 ID").fill("HANDOFF-HTTP-PACK");
  await page.getByPlaceholder("版本").fill("1");
  await page.locator(".enf5-lookup button").click();
  await page.getByText("EVENT_TIME:CONFLICT").waitFor();
  if (!bearer) throw new Error("HANDOFF product token was not used");
  const direct = await page.evaluate(async ({ base, bearer }) => {
    const response = await fetch(`${base}/v2/enforcement/human-requests/HANDOFF-HTTP-REQ`,
      { headers: { Authorization: bearer } });
    return { status: response.status, body: await response.json() };
  }, { base, bearer });
  if (direct.status !== 200 || direct.body.state !== "RETURNED" || direct.body.packageVersion !== 1)
    throw new Error("HANDOFF browser and direct V2 API disagree");
  await page.screenshot({ path: "frontend/target/enforcement-handoff-v2-desktop.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: "frontend/target/enforcement-handoff-v2-mobile.png", fullPage: true });
  if (await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth))
    throw new Error("HANDOFF workspace overflows mobile viewport");
  process.stdout.write("ENF_HANDOFF_BROWSER_READY\n");
  await new Promise(resolve => process.stdin.once("data", resolve));
  process.stdin.pause();
  await page.locator(".enf5-lookup button").click();
  await page.getByText("当前授权不可用，请重新登录").waitFor();
  if (await page.getByText("Human review required").count() ||
      await page.getByText("EVENT_TIME:CONFLICT").count() ||
      await page.getByRole("button", { name: "登录" }).count() !== 1 || !statuses.includes(403))
    throw new Error("HANDOFF browser retained withdrawn protected content");
  process.stdout.write("ENF_HANDOFF_BROWSER_WITHDRAWAL_CLEARED\n");
} catch (failure) {
  await browser.contexts()[0]?.pages()[0]?.screenshot({ path: "frontend/target/enforcement-handoff-v2-failure.png" }).catch(() => {});
  throw failure;
} finally {
  await browser.close();
}
