import { chromium } from "playwright";
import { readFile } from "node:fs/promises";

const [base] = process.argv.slice(2);
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ ignoreHTTPSErrors: true });
  const v2Requests = [];
  const v2Paths = [];
  let bearer;
  let previewHttp;
  page.on("request", request => {
    if (new URL(request.url()).pathname.startsWith("/api/v0"))
      throw new Error("V0 request from Evidence product workspace");
    if (new URL(request.url()).pathname.startsWith("/v2/enforcement") &&
        request.headers().authorization?.startsWith("Bearer "))
      bearer = request.headers().authorization;
    if (new URL(request.url()).pathname.startsWith("/v2/enforcement"))
      v2Paths.push(new URL(request.url()).pathname);
  });
  page.on("response", response => {
    if (new URL(response.url()).pathname.startsWith("/v2/enforcement"))
      v2Requests.push(response.status());
    if (new URL(response.url()).pathname.endsWith("/preview")) previewHttp = response;
  });
  await page.goto(`${base}/app/enforcement/evidence`);
  await page.getByRole("button", { name: "登录" }).click();
  await page.locator('input[name="username"]').fill("enf-evidence-operator");
  await page.locator('input[name="password"]').fill("unique-evidence-password-1");
  await page.locator('button[type="submit"]').click();
  await page.locator('input#id:visible').first().fill("RULE-001");
  await page.locator('input#revision:visible').fill("2");
  await page.getByRole("button", { name: "读取" }).click();
  await page.getByText("RULE-001 · v2").waitFor();
  await page.getByText("ACTIVE", { exact: true }).waitFor();
  await page.getByText("REVIEW-001", { exact: true }).waitFor();
  const manifest = JSON.parse(await readFile("data/enforcement/evidence-v2-acceptance-v1/manifest.json", "utf8"));
  await page.locator('input#caseRef:visible').first().fill("PROJECT-CASE-001");
  await page.locator('input#caseVersion:visible').first().fill("1");
  await page.getByLabel("锁定材料 ID|版本|SHA-256").first().fill(
    manifest.materials.map(item => `${item.ref}|${item.version}|${item.sha256}`).join("\n"));
  await page.getByRole("button", { name: "试跑" }).click();
  await page.getByText("CONFLICT", { exact: true }).waitFor();
  if (!v2Requests.includes(200)) throw new Error("Evidence V2 rule trial was not executed");
  await page.getByRole("tab", { name: "证据集合与关系" }).click();
  await page.locator('input#id:visible').first().fill("ORG-CASE-001");
  await page.locator('input#version:visible').fill("4");
  await page.getByRole("button", { name: "读取" }).click();
  await page.getByText("ORG-CASE-001 · v4").waitFor();
  await page.getByText("REVIEW_REQUIRED").waitFor();
  if (!v2Requests.includes(200)) throw new Error("Evidence V2 organization was not read");
  const firstLink = page.getByRole("button", { name: "预览" }).first();
  await firstLink.click();
  await page.getByRole("dialog").getByText("SHA-256：").waitFor({ timeout: 5000 }).catch(error => {
    throw new Error(`Preview not displayed: ${v2Paths.slice(-8).join(",")} / ` +
      `${error.message}`);
  });
  if (!previewHttp || previewHttp.status() !== 200)
    throw new Error("Evidence browser preview did not return 200");
  const preview = await previewHttp.json();
  const originalSha = await page.evaluate(async text => Array.from(new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))
  )).map(value => value.toString(16).padStart(2, "0")).join(""), preview.text);
  const previewSha = await page.getByRole("dialog").locator(".enf4-digest").innerText();
  if (originalSha !== previewSha || previewSha !== preview.sha256 ||
      !await page.getByRole("dialog").locator(".enf4-located").count())
    throw new Error("Evidence original preview did not match locked bytes and locator");
  await page.locator(".ant-drawer-close").click();
  if (!bearer) throw new Error("Evidence product token was not used");
  const endpoint = `${base}/v2/enforcement/evidence-organizations/ORG-CASE-001/versions/4`;
  const requestPage = async suffix => page.evaluate(async ({ endpoint, suffix, bearer }) => {
    const response = await fetch(endpoint + suffix, { headers: { Authorization: bearer } });
    return { status: response.status, body: response.ok ? await response.json() : null };
  }, { endpoint, suffix, bearer });
  const one = await requestPage("/links?matterId=ITEM_IDENTITY&pageSize=1");
  if (one.status !== 200 || one.body.items.length !== 1 || !one.body.nextCursor)
    throw new Error("Evidence first server page missing exact cursor");
  const two = await requestPage(`/links?matterId=ITEM_IDENTITY&pageSize=1&cursor=${encodeURIComponent(one.body.nextCursor)}`);
  if (two.status !== 200 || two.body.items.length !== 1 ||
      two.body.items[0].evidenceId === one.body.items[0].evidenceId)
    throw new Error("Evidence continuation duplicated or skipped locked relation");
  const invalidCursor = await requestPage("/links?matterId=ITEM_IDENTITY&pageSize=2&cursor=" +
    encodeURIComponent(one.body.nextCursor));
  const invalidLocator = await requestPage(`/materials/${encodeURIComponent(one.body.items[0].evidenceId)}` +
    "/preview?locator=line%209999");
  if (invalidCursor.status !== 409 || invalidLocator.status === 200)
    throw new Error("Evidence invalid cursor or original locator was accepted");
  process.stdout.write(`ENF_EVIDENCE_E04_BROWSER page1=1 page2=1 badCursor=${invalidCursor.status} badLocator=${invalidLocator.status} sha=${originalSha}\n`);
  await page.screenshot({ path: "frontend/target/enforcement-evidence-v2-desktop.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: "frontend/target/enforcement-evidence-v2-mobile.png", fullPage: true });
  if (await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth))
    throw new Error(`Evidence workspace overflows mobile viewport: ${JSON.stringify(await page.evaluate(() =>
      [...document.querySelectorAll("body *")].filter(element => element.getBoundingClientRect().right > innerWidth + 2)
        .slice(0, 12).map(element => [element.tagName, element.className, Math.round(element.getBoundingClientRect().right)])))}`);
  process.stdout.write("ENF_EVIDENCE_BROWSER_READY\n");
  await new Promise(resolve => process.stdin.once("data", resolve));
  process.stdin.pause();
  const withdrawnPreview = await requestPage(`/materials/${encodeURIComponent(preview.evidenceId)}` +
    `/preview?locator=${encodeURIComponent(preview.locator)}`);
  if (withdrawnPreview.status !== 403)
    throw new Error(`Withdrawn Evidence original preview remained available: ${withdrawnPreview.status}`);
  await page.getByRole("button", { name: "读取" }).click();
  await page.getByText("当前授权不可用，请重新登录").waitFor();
  if (await page.getByText("ORG-CASE-001 · v4").count() !== 0
      || await page.getByRole("button", { name: "登录" }).count() !== 1
      || !v2Requests.includes(403))
    throw new Error("Evidence browser retained withdrawn detail or authorization");
  process.stdout.write("ENF_EVIDENCE_E04_PREVIEW_WITHDRAWN status=403\n");
  process.stdout.write("ENF_EVIDENCE_BROWSER_WITHDRAWAL_CLEARED\n");
} finally {
  await browser.close();
}
