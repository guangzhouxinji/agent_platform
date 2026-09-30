import { chromium } from "playwright";

const [base, mode, username, password, ruleId, ruleVersion, reportId, reportVersion] = process.argv.slice(2);
if (!base || !["procedure", "conduct"].includes(mode) || !username || !password ||
    !ruleId || !ruleVersion || !reportId || !reportVersion)
  throw new Error("Expected base, mode, named login, password, rule ID/version and report ID/version");

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ ignoreHTTPSErrors: true });
const statuses = [];
let bearer;
page.on("request", request => {
  const path = new URL(request.url()).pathname;
  if (path.startsWith("/api/v0")) throw new Error("V0 request from V2 workface");
  if (path.startsWith("/v2/enforcement") && request.headers().authorization?.startsWith("Bearer "))
    bearer = request.headers().authorization;
});
page.on("response", response => {
  if (new URL(response.url()).pathname.startsWith("/v2/enforcement")) statuses.push(response.status());
});

try {
  await page.goto(`${base}/app/enforcement/${mode}`);
  await page.getByRole("button", { name: "登录" }).click();
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.getByRole("tab", { name: mode === "procedure" ? "规则与试算" : "行为规则" }).waitFor();

  await page.getByPlaceholder("规则 ID").fill(ruleId);
  await page.getByPlaceholder("版本").first().fill(ruleVersion);
  await page.locator(".enf-next-lookup").first().getByRole("button", { name: /读取/ }).click();
  await page.getByText(`${ruleId} v${ruleVersion}`, { exact: true }).waitFor();

  await page.getByRole("tab", { name: mode === "procedure" ? "案件逐项报告" : "案件辅助分析" }).click();
  await page.getByPlaceholder(mode === "procedure" ? "报告 ID" : "分析 ID").fill(reportId);
  await page.getByPlaceholder("版本").last().fill(reportVersion);
  await page.locator(".enf-next-lookup").last().getByRole("button", { name: /读取/ }).click();
  await page.getByText(`${reportId} v${reportVersion}`, { exact: true }).waitFor();

  if (!bearer || statuses.filter(status => status === 200).length < 2)
    throw new Error("Browser did not read two protected V2 resources");
  const direct = await page.evaluate(async ({ base, mode, reportId, reportVersion, bearer }) => {
    const path = mode === "procedure" ? "procedure-checks" : "conduct-analyses";
    const response = await fetch(`${base}/v2/enforcement/${path}/${encodeURIComponent(reportId)}/versions/${reportVersion}`,
      { headers: { Authorization: bearer } });
    return { status: response.status, body: await response.json() };
  }, { base, mode, reportId, reportVersion, bearer });
  if (direct.status !== 200 || direct.body.id !== reportId ||
      direct.body.version !== Number(reportVersion) || direct.body.ruleId !== ruleId)
    throw new Error("Browser and direct V2 report references disagree");
  if (mode === "procedure") {
    if (!direct.body.items?.length || !direct.body.executionState ||
        !await page.getByText(direct.body.executionState, { exact: true }).count())
      throw new Error("Procedure report states or item trace not rendered");
    for (const item of direct.body.items) {
      if (!await page.getByText(item.requirement, { exact: true }).count() ||
          !await page.getByText(item.state, { exact: true }).count())
        throw new Error("Procedure item differs from V2 API");
    }
  } else {
    if (!direct.body.elements?.length || !direct.body.facts?.length || !direct.body.outcome ||
        !await page.getByText(direct.body.outcome, { exact: true }).count())
      throw new Error("Conduct candidate or facts not rendered");
    for (const fact of direct.body.facts) {
      if (!await page.getByText(fact.ref, { exact: true }).count() ||
          !await page.getByText(fact.kind, { exact: true }).count())
        throw new Error("Conduct fact provenance differs from V2 API");
    }
  }
  await page.screenshot({ path: `frontend/target/enforcement-${mode}-v2-desktop.png`, fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: `frontend/target/enforcement-${mode}-v2-mobile.png`, fullPage: true });
  if (await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth))
    throw new Error(`${mode} workface overflows mobile viewport`);
  process.stdout.write(`ENF_${mode.toUpperCase()}_BROWSER_OK\n`);
  process.stdout.write(`ENF_${mode.toUpperCase()}_BROWSER_READY\n`);
  await new Promise(resolve => process.stdin.once("data", resolve));
  process.stdin.pause();
  await page.locator(".enf-next-lookup").last().getByRole("button", { name: /读取/ }).click();
  await page.getByText("当前授权不可用，请重新登录").waitFor();
  if (!statuses.includes(403) || await page.getByText(`${reportId} v${reportVersion}`, { exact: true }).count() ||
      !await page.getByText("当前没有产品会话").count())
    throw new Error(`${mode} browser retained protected report after withdrawal`);
  process.stdout.write(`ENF_${mode.toUpperCase()}_BROWSER_WITHDRAWAL_CLEARED\n`);
} catch (failure) {
  await page.screenshot({ path: `frontend/target/enforcement-${mode}-v2-failure.png` }).catch(() => {});
  throw failure;
} finally {
  await browser.close();
}
