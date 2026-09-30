import { createHash } from "node:crypto";
import { createReadStream, createWriteStream, existsSync } from "node:fs";
import { mkdir, rename, rm } from "node:fs/promises";
import { join, resolve } from "node:path";
import { Readable } from "node:stream";
import { finished } from "node:stream/promises";

const revision = "233902d25c440f23af6f7d6e94d2946bac0bee0a";
const repository = "cross-encoder/ms-marco-MiniLM-L-6-v2";
const artifacts = [
  { name: "model_quint8_avx2.onnx", path: "onnx/model_quint8_avx2.onnx", sha256: "c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9" },
  { name: "tokenizer.json", path: "tokenizer.json", sha256: "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66" },
];
const root = resolve(process.argv[2] ?? join(process.env.LOCALAPPDATA ?? ".", "AgentPlatform", "models", "patent-reranker-v1"));

await mkdir(root, { recursive: true });
for (const artifact of artifacts) {
  const target = join(root, artifact.name);
  if (existsSync(target) && await digest(target) === artifact.sha256) continue;
  const temporary = "`${target}.partial`";
  await rm(temporary, { force: true });
  const response = await fetch("`https://huggingface.co/${repository}/resolve/${revision}/${artifact.path}`",
    { redirect: "follow" });
  if (!response.ok || !response.body) throw new Error("`Model download failed: ${artifact.name} HTTP ${response.status}`");
  await finished(Readable.fromWeb(response.body).pipe(createWriteStream(temporary, { flags: "wx" })));
  const actual = await digest(temporary);
  if (actual !== artifact.sha256) {
    await rm(temporary, { force: true });
    throw new Error("`Model digest mismatch: ${artifact.name} ${actual}`");
  }
  await rename(temporary, target);
}
console.log(JSON.stringify({ root, repository, revision, artifacts }, null, 2));

async function digest(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}
