import { writeFile } from "node:fs/promises";

const [mode, output] = process.argv.slice(2);
const endpoint = "http://127.0.0.1:9223";
const productUrl = "https://127.0.0.1:5174/app/enforcement/job-pack";

async function target() {
  const targets = await fetch(`${endpoint}/json/list`).then(response => response.json());
  let page = targets.find(item => item.type === "page" && item.url?.startsWith("https://127.0.0.1:5174"));
  if (!page) {
    page = await fetch(`${endpoint}/json/new?${productUrl}`, { method: "PUT" }).then(response => response.json());
  }
  return page;
}

function connection(url) {
  const socket = new WebSocket(url);
  const pending = new Map();
  let sequence = 0;
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    const callback = pending.get(message.id);
    if (callback) {
      pending.delete(message.id);
      callback(message);
    }
  });
  const ready = new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", reject, { once: true });
  });
  return {
    async send(method, params = {}) {
      await ready;
      return new Promise(resolve => {
        const id = ++sequence;
        pending.set(id, resolve);
        socket.send(JSON.stringify({ id, method, params }));
      });
    },
    close() { socket.close(); }
  };
}

async function evaluate(cdp, expression) {
  const response = await cdp.send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (response.error || response.result?.exceptionDetails) throw new Error("Browser evaluation failed");
  return response.result?.result?.value;
}

async function waitFor(cdp, expression, timeout = 15_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await evaluate(cdp, expression)) return;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error("Browser state did not become ready");
}

const page = await target();
const cdp = connection(page.webSocketDebuggerUrl);
try {
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");
  await cdp.send("Page.bringToFront");
  if (mode === "prepare-login") {
    await cdp.send("Page.navigate", { url: productUrl });
    await waitFor(cdp, "document.readyState === 'complete' || document.readyState === 'interactive'");
    await evaluate(cdp, `(async () => {
      const encode = bytes => btoa(String.fromCharCode(...bytes)).replace(/\\+/g, "-").replace(/\\//g, "_").replace(/=+$/, "");
      const verifier = encode(crypto.getRandomValues(new Uint8Array(32)));
      const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
      const state = encode(crypto.getRandomValues(new Uint8Array(16)));
      sessionStorage.setItem("enf-v2-job-pack-pkce", JSON.stringify({ verifier, state }));
      const params = new URLSearchParams({ response_type: "code",
        client_id: "enforcement-job-pack-human-20260929", redirect_uri: location.origin + "/app/enforcement/job-pack",
        scope: "openid", state, code_challenge: encode(digest), code_challenge_method: "S256" });
      location.assign("/oauth2/authorize?" + params);
      return true;
    })()`);
    await waitFor(cdp, "Boolean(document.querySelector('input[name=username]'))");
  } else if (mode === "resume-session") {
    await cdp.send("Page.navigate", { url: productUrl });
    await waitFor(cdp, "document.readyState === 'complete' || document.readyState === 'interactive'");
    await evaluate(cdp, `(() => {
      const button = [...document.querySelectorAll("button")].find(item => item.textContent.trim() === "\u767b\u5f55");
      if (!button) return false;
      button.click();
      return true;
    })()`);
    await waitFor(cdp, `document.querySelector('input[name=username]') || document.body.innerText.includes("\u5f53\u524d\u767b\u5f55\u8eab\u4efd")`);
  } else if (mode === "inspect") {
    // Read-only by design. Input values and browser storage are deliberately excluded.
  } else if (mode === "claim-target") {
    const taskId = "ENF-RBC-CNIPA-GUIDANCE-CASE-1";
    await evaluate(cdp, `(() => {
      const row = [...document.querySelectorAll("li")].find(item => item.innerText.includes("${taskId}"));
      const input = row?.querySelector("input");
      if (!input) throw new Error("Target organization input unavailable");
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
      setter.call(input, "ORG-ENF-ACCEPTANCE");
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
      return true;
    })()`);
    await waitFor(cdp, `(() => {
      const row = [...document.querySelectorAll("li")].find(item => item.innerText.includes("${taskId}"));
      const button = [...(row?.querySelectorAll("button") ?? [])].find(item => item.textContent.trim() === "\u9886\u53d6\u4efb\u52a1");
      return Boolean(button && !button.disabled);
    })()`);
    await evaluate(cdp, `(() => {
      const row = [...document.querySelectorAll("li")].find(item => item.innerText.includes("${taskId}"));
      const button = [...row.querySelectorAll("button")].find(item => item.textContent.trim() === "\u9886\u53d6\u4efb\u52a1");
      button.click();
      return true;
    })()`);
    await waitFor(cdp, "document.body.innerText.includes('ENF-JOB-CNIPA-GUIDANCE-CASE-1') && document.body.innerText.includes('\u771f\u5b9e\u8eab\u4efd\u5c97\u4f4d')");
  } else if (mode === "screenshot") {
    if (!output) throw new Error("Screenshot output path is required");
    const captured = await cdp.send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
    await writeFile(output, Buffer.from(captured.result.data, "base64"));
  } else {
    throw new Error(`Unsupported browser action: ${mode}`);
  }
  const state = await evaluate(cdp, "({ url: location.href, title: document.title, text: document.body?.innerText?.slice(0, 4000) ?? '', buttons: [...document.querySelectorAll('button')].map(item => item.textContent.trim()).filter(Boolean), inputs: [...document.querySelectorAll('input')].map(item => ({ name: item.name, type: item.type })) })");
  process.stdout.write(JSON.stringify(state, null, 2));
} finally {
  cdp.close();
}
