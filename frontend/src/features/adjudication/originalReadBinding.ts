import { digest } from "./api";
import { CaseBindingError } from "./caseReceiptBinding";
import type { Original, Source } from "./contract";

const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === "object" && !Array.isArray(value);
const text = (value: unknown): value is string => typeof value === "string" && value.trim().length > 0;
const kinds = new Set(["PUBLIC", "DEIDENTIFIED", "USER_PROVIDED", "PROJECT_AUTHORED"]);
function fail(detail: string): never { throw new CaseBindingError("read", "案件原件" + detail); }
function source(value: unknown): Source {
 if (!record(value) || !text(value.id) || !text(value.version) || !text(value.locator) || typeof value.sha256 !== "string" || !/^[a-f0-9]{64}$/.test(value.sha256) || typeof value.kind !== "string" || !kinds.has(value.kind)) fail("来源坐标不合法");
 return { id: value.id, version: value.version, locator: value.locator, sha256: value.sha256, kind: value.kind as Source["kind"] };
}
const coordinates = (value: Source) => JSON.stringify([value.id, value.version, value.locator, value.sha256, value.kind]);

// originals contains the case's historical CaseInput sources, not just the selected snapshot's source.
// This only checks read integrity; current authorization and source-use decisions remain server-owned.
export async function bindOriginalRead(currentSource: unknown, value: unknown): Promise<Original[]> {
 const current = coordinates(source(currentSource));
 if (!Array.isArray(value) || value.length === 0) fail("列表为空或不可读");
 const seen = new Set<string>();
 const originals = value.map(item => {
  if (!record(item)) fail("记录不合法");
  const boundSource = source(item.source), key = coordinates(boundSource);
  if (seen.has(key)) fail("来源坐标重复");
  seen.add(key);
  if (!text(item.originalText)) fail("正文为空或不可读");
  return { source: boundSource, originalText: item.originalText };
 });
 if (!seen.has(current)) fail("缺少当前案件输入的确切来源");
 for (const original of originals) {
  // Hash the unmodified UTF-8 original, including whitespace and line endings.
  if (await digest(original.originalText) !== original.source.sha256) fail("正文摘要与来源不一致");
 }
 return originals;
}
