import { createHash } from "node:crypto";
import { createReadStream, createWriteStream, existsSync } from "node:fs";
import { mkdir, rename, rm } from "node:fs/promises";
import { join, resolve } from "node:path";
import { Readable } from "node:stream";
import { finished } from "node:stream/promises";

const revision = "e8f8c211226b894fcb81acc59f3b34ba3efd5f42";
const repository = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2";
const artifacts = [
  { name: "model_quint8_avx2.onnx", path: "onnx/model_quint8_avx2.onnx", sha256: "98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f" },
  { name: "tokenizer.json", path: "tokenizer.json", sha256: "2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8" },
];
const root = resolve(process.argv[2] ?? join(process.env.LOCALAPPDATA ?? ".", "AgentPlatform", "models", "patent-embedding-v1"));

await mkdir(root, { recursive: true });
for (const artifact of artifacts) {
  const target = join(root, artifact.name);
  if (existsSync(target) && await digest(target) === artifact.sha256) continue;
  const temporary = `${target}.partial`;
  await rm(temporary, { force: true });
  const url = `https://huggingface.co/${repository}/resolve/${revision}/${artifact.path}`;
  const response = await fetch(url, { redirect: "follow" });
  if (!response.ok || !response.body) throw new Error(`Model download failed: ${artifact.name} HTTP ${response.status}`);
  await finished(Readable.fromWeb(response.body).pipe(createWriteStream(temporary, { flags: "wx" })));
  const actual = await digest(temporary);
  if (actual !== artifact.sha256) {
    await rm(temporary, { force: true });
    throw new Error(`Model digest mismatch: ${artifact.name} ${actual}`);
  }
  await rename(temporary, target);
}

console.log(JSON.stringify({ root, repository, revision, artifacts }, null, 2));

async function digest(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}
