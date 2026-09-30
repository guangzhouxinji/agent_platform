import { readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const MAX_BYTES = 64 * 1024;
const FAMILIES = new Set(["DISCOVERY", "EVIDENCE", "PROCEDURE", "CONDUCT", "DOCUMENT", "PENALTY"]);
const SHAPES = {
  scope: "object", basisRefs: "array", inputs: "array", condition: "object",
  exceptions: "array", output: "object", missingPolicy: "object", examples: "array",
  author: "string", reviewer: "string", changeReason: "string",
};
const KEYS = new Set(["schemaVersion", "ruleId", "family", "revision", "status",
  "validFrom", "validTo", ...Object.keys(SHAPES)]);

function error(code, path = "$") {
  throw new Error(`${code} ${path}`);
}

// JSON.parse accepts duplicate keys. Walk its already-valid syntax to reject overwritten values.
function rejectDuplicateKeys(source) {
  let pos = 0;
  const space = () => { while (/\s/.test(source[pos] ?? "")) pos++; };
  const string = () => {
    const start = pos++;
    while (pos < source.length) {
      if (source[pos] === "\\") { pos += 2; continue; }
      if (source[pos++] === '"') return JSON.parse(source.slice(start, pos));
    }
    error("INVALID_JSON");
  };
  const value = (path, depth) => {
    if (depth > 100) error("TOO_DEEP", path);
    space();
    if (source[pos] === "{") {
      pos++; space();
      const seen = new Set();
      while (source[pos] !== "}") {
        const key = string();
        const child = `${path}.${key}`;
        if (seen.has(key)) error("DUPLICATE_KEY");
        seen.add(key);
        space(); pos++; // colon; syntax has already been checked by JSON.parse
        value(child, depth + 1);
        space();
        if (source[pos] !== ",") break;
        pos++; space();
      }
      pos++;
    } else if (source[pos] === "[") {
      pos++; space();
      let index = 0;
      while (source[pos] !== "]") {
        value(`${path}[${index++}]`, depth + 1);
        space();
        if (source[pos] !== ",") break;
        pos++; space();
      }
      pos++;
    } else if (source[pos] === '"') {
      string();
    } else {
      while (pos < source.length && !/[\s,}\]]/.test(source[pos])) pos++;
    }
  };
  value("$", 0);
}

function date(value, path) {
  if (value === null) return null;
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) error("INVALID_DATE", path);
  const [year, month, day] = value.split("-").map(Number);
  const timestamp = Date.UTC(year, month - 1, day);
  const actual = new Date(timestamp);
  if (year < 100 || actual.getUTCFullYear() !== year || actual.getUTCMonth() + 1 !== month
    || actual.getUTCDate() !== day) error("INVALID_DATE", path);
  return timestamp;
}

export function validateDraft(bytes) {
  if (bytes.length > MAX_BYTES) error("FILE_TOO_LARGE");
  let source;
  try { source = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes); }
  catch { error("INVALID_UTF8"); }
  let draft;
  try { draft = JSON.parse(source); }
  catch { error("INVALID_JSON"); }
  rejectDuplicateKeys(source);
  if (draft === null || typeof draft !== "object" || Array.isArray(draft)) error("INVALID_ROOT");
  for (const key of Object.keys(draft)) if (!KEYS.has(key)) error("UNKNOWN_FIELD");
  for (const key of KEYS) if (!Object.hasOwn(draft, key)) error("MISSING_FIELD", `$.${key}`);
  if (draft.schemaVersion !== 1) error("INVALID_SCHEMA", "$.schemaVersion");
  if (typeof draft.ruleId !== "string" || !draft.ruleId.trim()) error("INVALID_ID", "$.ruleId");
  if (!FAMILIES.has(draft.family)) error("INVALID_FAMILY", "$.family");
  if (!Number.isSafeInteger(draft.revision) || draft.revision < 1) error("INVALID_REVISION", "$.revision");
  if (draft.status !== "DRAFT") error("DRAFT_ONLY", "$.status");
  for (const [key, shape] of Object.entries(SHAPES)) {
    const item = draft[key];
    if (item === null) continue;
    const valid = shape === "array" ? Array.isArray(item)
      : shape === "object" ? typeof item === "object" && !Array.isArray(item)
        : typeof item === "string" && item.trim().length > 0;
    if (!valid) error("INVALID_SHAPE", `$.${key}`);
  }
  const from = date(draft.validFrom, "$.validFrom");
  const to = date(draft.validTo, "$.validTo");
  if (from !== null && to !== null && from > to) error("INVALID_DATE_RANGE", "$.validTo");
}

function selfTest() {
  const draft = Object.fromEntries([...KEYS].map(key => [key, null]));
  Object.assign(draft, { schemaVersion: 1, ruleId: "ENF-RULE-V1-DISCOVERY-01",
    family: "DISCOVERY", revision: 1, status: "DRAFT" });
  const encode = value => Buffer.from(JSON.stringify(value));
  const rejects = (label, bytes, expected) => {
    try { validateDraft(bytes); error("EXPECTED_REJECTION", label); }
    catch (failure) { if (!failure.message.startsWith(expected)) throw failure; }
  };
  validateDraft(encode(draft));
  rejects("missing", encode({ ...draft, scope: undefined }), "MISSING_FIELD");
  rejects("unknown", encode({ ...draft, extra: 1 }), "UNKNOWN_FIELD");
  rejects("duplicate", Buffer.from(JSON.stringify(draft).replace('"revision":1', '"revision":1,"revision":2')), "DUPLICATE_KEY");
  rejects("nested duplicate", Buffer.from(JSON.stringify({ ...draft, scope: { a: 1 } }).replace('"a":1', '"a":1,"a":2')), "DUPLICATE_KEY");
  rejects("utf8", Buffer.from([0xff]), "INVALID_UTF8");
  rejects("size", Buffer.alloc(MAX_BYTES + 1), "FILE_TOO_LARGE");
  rejects("date", encode({ ...draft, validFrom: "2026-02-30" }), "INVALID_DATE");
  rejects("range", encode({ ...draft, validFrom: "2026-02-28", validTo: "2026-02-27" }), "INVALID_DATE_RANGE");
  rejects("state", encode({ ...draft, status: "PUBLISHED" }), "DRAFT_ONLY");
  rejects("shape", encode({ ...draft, inputs: "field" }), "INVALID_SHAPE");
  rejects("root", encode([draft]), "INVALID_ROOT");
  console.log("Enforcement offline draft structure: 1 positive, 11 negative checks passed; no rule executed");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.length === 3 && process.argv[2] === "--self-test") {
    selfTest();
  } else if (process.argv.length === 3) {
    try {
      const path = process.argv[2];
      if (statSync(path).size > MAX_BYTES) error("FILE_TOO_LARGE");
      validateDraft(readFileSync(path));
      console.log("STRUCTURE_OK DRAFT_ONLY; no source, legal or publication check performed");
    } catch (failure) {
      console.error(failure.message.match(/^[A-Z_]+(?: \$[^\s]*)?$/)?.[0] ?? "FILE_OR_INPUT_ERROR");
      process.exitCode = 1;
    }
  } else {
    console.error("Usage: node scripts/verify-enforcement-rule-draft.mjs <draft.json>|--self-test");
    process.exitCode = 1;
  }
}
