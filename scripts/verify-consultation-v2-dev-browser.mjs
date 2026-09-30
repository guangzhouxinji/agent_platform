import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { chromium } from "@playwright/test";

const backend = process.env.V2_BASE_URL;
const token = process.env.V2_HUMAN_TOKEN;
const receiverToken = process.env.V2_RECEIVER_TOKEN;
const manifestPath = process.env.V2_COMPARISON_MANIFEST;
const correctionManifestPath = process.env.V2_CORRECTION_MANIFEST;
assert(backend?.startsWith("http://127.0.0.1:") && token?.startsWith("Bearer ")
  && receiverToken?.startsWith("Bearer "));
assert(manifestPath && correctionManifestPath);
const originalManifest = readFileSync(manifestPath);
const originalCorrectionManifest = readFileSync(correctionManifestPath);

const port = await new Promise((resolvePort, reject) => {
  const socket = createServer();
  socket.once("error", reject);
  socket.listen(0, "127.0.0.1", () => {
    const selected = socket.address().port;
    socket.close(() => resolvePort(selected));
  });
});
const base = `http://127.0.0.1:${port}/app/consultation/v2-dev`;
const vite = spawn(process.execPath, [resolve("frontend/node_modules/vite/bin/vite.js"),
  "--host", "127.0.0.1", "--port", String(port), "--strictPort"], {
  cwd: resolve("frontend"), stdio: "ignore", env: {
    ...process.env, VITE_V2_PROXY_TARGET: backend, VITE_CONSULTATION_V2_DEV_WORKBENCH: "true"
  }
});
let browser;
try {
  let ready = false;
  for (let i = 0; i < 80; i++) {
    if (vite.exitCode !== null) throw new Error("Vite exited before readiness");
    try { ready = (await fetch(base)).ok; } catch { /* Starting. */ }
    if (ready) break;
    await new Promise((done) => setTimeout(done, 200));
  }
  assert(ready, "Vite development page did not become ready");
  browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 } });
  const calls = [];
  page.on("response", (response) => {
    if (response.url().includes("/v2/consultation/tasks")) calls.push(response);
  });
  await page.goto(base);
  await page.getByLabel("开发身份凭据").fill(token);
  await page.getByRole("button", { name: "创建合成任务" }).click();
  await page.getByRole("button", { name: "提交补充" }).waitFor();
  const taskId = new URL(page.url()).searchParams.get("task");
  assert.match(taskId, /^[0-9a-f-]{36}$/i);
  await page.getByRole("button", { name: "提交补充" }).click();
  await page.getByText("任务版本 2", { exact: true }).waitFor();
  await page.getByRole("button", { name: "生成合成候选" }).click();
  await page.getByText("2. P2").waitFor();
  await page.getByRole("button", { name: "选为焦点" }).nth(1).click();
  await page.getByText("集合", { exact: false }).first().waitFor();
  await page.getByRole("button", { name: "确认三项原文特征" }).click();
  await page.getByText("本输入已确认").waitFor();
  await page.getByRole("button", { name: "核对目标与权项" }).click();
  await page.getByText("尚未进行专业对应").waitFor();
  assert((await page.locator(".v2-dev-preview").innerText()).includes("P2"));
  assert(await page.getByRole("button", { name: "选择技术相似" }).isEnabled(),
    `Purpose unavailable: ${await page.getByRole("region", { name: "分析目的" }).innerText()}; `
      + `HTTP ${calls.filter((response) => response.url().includes("analysis-purposes"))
        .map((response) => response.status()).join(",")}`);
  await page.getByRole("button", { name: "选择技术相似" }).click();
  await page.getByText("技术相似已选择").waitFor();
  assert.equal(await page.getByText("尚未进行专业对应").count(), 0);
  await page.getByRole("button", { name: "请求人工帮助" }).click();
  await page.getByText("请求已保存，尚未受理").waitFor();
  assert.equal(await page.getByRole("button", { name: "请求人工帮助" }).count(), 0);
  await page.getByRole("button", { name: "断开" }).click();
  await page.getByLabel("开发身份凭据").fill(receiverToken);
  await page.getByRole("button", { name: "查看待领请求" }).click();
  await page.getByRole("button", { name: "领取", exact: true }).waitFor();
  assert.equal(await page.locator(".v2-dev-message").count(), 0);
  mkdirSync(resolve("target/playwright-artifacts"), { recursive: true });
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-inbox-desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Inbox mobile overflow");
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-inbox-mobile.png"), fullPage: true });
  await page.setViewportSize({ width: 1366, height: 900 });
  await page.getByRole("button", { name: "领取", exact: true }).click();
  await page.getByText("本次请求已领取").waitFor();
  assert.equal(await page.getByRole("button", { name: "领取", exact: true }).count(), 0);
  await page.reload();
  assert.equal(await page.getByLabel("开发身份凭据").inputValue(), "");
  await page.getByLabel("开发身份凭据").fill(receiverToken);
  await page.getByRole("button", { name: "查看待领请求" }).click();
  await page.getByRole("button", { name: "提交内部意见" }).waitFor();
  assert.equal(await page.locator(".v2-dev-message").count(), 0);
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-owned-desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Owned mobile overflow");
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-owned-mobile.png"), fullPage: true });
  await page.setViewportSize({ width: 1366, height: 900 });
  await page.getByRole("button", { name: "提交内部意见" }).click();
  await page.getByText("本次内部意见已提交").waitFor();
  await page.getByRole("button", { name: "断开" }).click();
  await page.getByLabel("开发身份凭据").fill(token);
  await page.getByRole("button", { name: "恢复任务" }).click();
  await page.getByText("请求已领取").waitFor();
  await page.getByText("内部意见已提交").waitFor();
  await page.getByText("技术相似已选择").waitFor();
  mkdirSync(resolve("target/playwright-artifacts"), { recursive: true });
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-dm8-desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Mobile overflow");
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-dm8-mobile.png"), fullPage: true });
  const withdrawn = originalManifest.toString().replace('"withdrawalVersion": 0', '"withdrawalVersion": 1');
  assert.notEqual(withdrawn, originalManifest.toString());
  writeFileSync(manifestPath, withdrawn);
  await page.getByRole("button", { name: "刷新" }).click();
  await page.getByText("来源目前不可用，未展示原文。").first().waitFor();
  assert.equal(await page.getByText("补充：该方案使用温度传感器、自动控制器和阀门。").count(), 0);
  writeFileSync(manifestPath, originalManifest);
  await page.getByRole("button", { name: "刷新" }).click();
  await page.getByText("本输入已确认").waitFor();
  await page.reload();
  assert.equal(await page.getByLabel("开发身份凭据").inputValue(), "");
  await page.getByLabel("开发身份凭据").fill("Bearer xj_human-a.invalid");
  await page.getByRole("button", { name: "恢复任务" }).click();
  await page.locator(".v2-dev-error").waitFor();
  assert([401, 403].includes(calls.at(-1)?.status()), "Invalid credential must be rejected by backend");
  assert.equal(await page.getByText("本输入已确认").count(), 0);
  await page.getByLabel("开发身份凭据").fill(token);
  await page.getByRole("button", { name: "恢复任务" }).click();
  await page.getByText("本输入已确认").waitFor();
  await page.getByText("请求已领取").waitFor();
  await page.getByRole("button", { name: "取消任务" }).click();
  await page.getByRole("button", { name: "确认取消" }).click();
  await page.getByText("已取消", { exact: true }).waitFor();
  await page.getByText("历史请求，已领取").waitFor();
  await page.getByText("历史输入的内部意见").waitFor();
  await page.getByRole("button", { name: "创建合成任务" }).click();
  await page.getByRole("button", { name: "请求人工帮助" }).click();
  await page.getByText("请求已保存，尚未受理").waitFor();
  const returnTask = new URL(page.url()).searchParams.get("task");
  await page.getByRole("button", { name: "断开" }).click();
  await page.getByLabel("开发身份凭据").fill(receiverToken);
  await page.getByRole("button", { name: "查看待领请求" }).click();
  await page.getByRole("button", { name: "领取", exact: true }).click();
  await page.getByRole("button", { name: "退回", exact: true }).waitFor();
  await page.getByRole("button", { name: "查看上下文" }).click();
  await page.getByLabel("已领包上下文").getByText("目标", { exact: false }).waitFor();
  await page.setViewportSize({ width: 1366, height: 900 });
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-context-desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Context mobile overflow");
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-context-mobile.png"), fullPage: true });
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-return-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "退回", exact: true }).click();
  await page.getByText("本次请求已退回").waitFor();
  await page.getByRole("button", { name: "断开" }).click();
  await page.getByLabel("开发身份凭据").fill(token);
  await page.getByRole("button", { name: "恢复任务" }).click();
  await page.getByText("已退回，等待原任务处理").waitFor();
  await page.setViewportSize({ width: 1366, height: 900 });
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-return-owner-desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Return owner mobile overflow");
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-return-owner-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "重新请求人工帮助" }).click();
  await page.getByText("请求序号 2", { exact: true }).waitFor();
  assert.equal(await page.getByText("已退回，等待原任务处理").count(), 0);
  await page.getByRole("button", { name: "创建合成任务" }).click();
  await page.getByRole("button", { name: "提交补充" }).click();
  await page.getByText("任务版本 2", { exact: true }).waitFor();
  await page.getByRole("button", { name: "生成合成候选" }).click();
  await page.getByText("2. P2").waitFor();
  await page.getByRole("button", { name: "选为焦点" }).nth(1).click();
  await page.getByRole("button", { name: "确认三项原文特征" }).click();
  await page.getByText("本输入已确认").waitFor();
  const correctionTask = new URL(page.url()).searchParams.get("task");
  await page.getByRole("button", { name: "更正 F3" }).click();
  await page.getByRole("button", { name: "确认更正" }).click();
  await page.getByText("任务版本 3", { exact: true }).waitFor();
  await page.getByText("旧结果与确认仅作为历史保留").waitFor();
  await page.getByRole("region", { name: "核查进度" }).getByText("历史", { exact: true }).first().waitFor();
  await page.getByRole("region", { name: "核查进度" }).getByText("更正后输入尚无获准新检索及确认。", { exact: false }).waitFor();
  assert.equal(await page.getByRole("button", { name: "生成合成候选" }).count(), 0);
  assert.equal(await page.getByRole("button", { name: "确认三项原文特征" }).count(), 0);
  await page.setViewportSize({ width: 1366, height: 900 });
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-correction-desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Correction mobile overflow");
  await page.screenshot({ path: resolve("target/playwright-artifacts/consultation-v2-correction-mobile.png"), fullPage: true });
  await page.reload();
  await page.getByLabel("开发身份凭据").fill(token);
  await page.getByRole("button", { name: "恢复任务" }).click();
  await page.getByText("任务版本 3", { exact: true }).waitFor();
  await page.getByText("纠正：补充里的阀门应为电磁阀，其他不变。").waitFor();
  const withdrawnCorrection = originalCorrectionManifest.toString()
    .replace('"withdrawalVersion": 0', '"withdrawalVersion": 1');
  assert.notEqual(withdrawnCorrection, originalCorrectionManifest.toString());
  writeFileSync(correctionManifestPath, withdrawnCorrection);
  await page.getByRole("button", { name: "刷新" }).click();
  await page.getByText("来源目前不可用，未展示原文。").waitFor();
  assert.equal(await page.getByRole("region", { name: "核查进度" }).count(), 0);
  assert.equal(await page.getByText("纠正：补充里的阀门应为电磁阀，其他不变。").count(), 0);
  assert.equal(await page.getByText("补充：该方案使用温度传感器、自动控制器和电磁阀。").count(), 0);
  writeFileSync(correctionManifestPath, originalCorrectionManifest);
  await page.getByRole("button", { name: "刷新" }).click();
  await page.getByText("任务版本 3", { exact: true }).waitFor();
  assert.match(correctionTask, /^[0-9a-f-]{36}$/i);
  assert.match(returnTask, /^[0-9a-f-]{36}$/i);
  assert(calls.length >= 13 && calls.every((response) => response.status() < 500),
    "Expected real V2 HTTP requests without server errors");
  console.log(`V2_BROWSER_TASK=${taskId}`);
  console.log(`V2_RETURN_TASK=${returnTask}`);
  console.log(`V2_CORRECTION_TASK=${correctionTask}`);
} finally {
  writeFileSync(manifestPath, originalManifest);
  writeFileSync(correctionManifestPath, originalCorrectionManifest);
  await browser?.close();
  vite.kill();
  await new Promise((done) => { if (vite.exitCode !== null) done(); else vite.once("exit", done); });
}
