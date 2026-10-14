import { createHash, webcrypto } from "node:crypto";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { bindOriginalRead } from "./originalReadBinding";
import type { Original, Source } from "./contract";
const originalText = " 中文原文😀\r\nUTF-8 保留空格 ";
const sha = (text: string) => createHash("sha256").update(text, "utf8").digest("hex");
const current: Source = { id: "input", version: "v2", locator: "/case/input", sha256: sha(originalText), kind: "PROJECT_AUTHORED" };
const original = (): Original => ({ source: { ...current }, originalText });
beforeEach(() => { vi.stubGlobal("crypto", webcrypto); });
afterEach(() => { vi.unstubAllGlobals(); });
it("binds UTF-8 original and permits legal historical CaseInput sources", async () => {
 const historical: Original = { source: { ...current, id: "old", version: "v1", locator: "/old", kind: "DEIDENTIFIED", sha256: sha("历史原文") }, originalText: "历史原文" };
 expect(await bindOriginalRead(current, [historical, original()])).toEqual([historical, original()]);
});
it("permits distinct full coordinates sharing the body SHA", async () => {
 expect(await bindOriginalRead(current, [{ ...original(), source: { ...current, version: "v1" } }, original()])).toHaveLength(2);
});
it.each([null, {}, [], [null]])("rejects unreadable or empty list %j", async value => { await expect(bindOriginalRead(current, value)).rejects.toThrow("案件原件"); });
it.each(["id", "version", "locator", "sha256", "kind"] as const)("requires exact current source %s", async field => {
 const changes = { id: "other", version: "v1", locator: "/other", sha256: sha("other body"), kind: "PUBLIC" };
 const other = { ...original(), source: { ...current, [field]: changes[field] }, originalText: field === "sha256" ? "other body" : originalText };
 await expect(bindOriginalRead(current, [other])).rejects.toThrow("缺少当前案件输入的确切来源");
});
it.each(["id", "version", "locator", "sha256", "kind"] as const)("rejects malformed %s on current and history", async field => {
 const bad = { ...current, [field]: field === "kind" ? "UNKNOWN" : " " };
 await expect(bindOriginalRead(bad, [original()])).rejects.toThrow("来源坐标不合法");
 await expect(bindOriginalRead(current, [original(), { source: bad, originalText }])).rejects.toThrow("来源坐标不合法");
});
it("rejects duplicate full coordinates", async () => { await expect(bindOriginalRead(current, [original(), original()])).rejects.toThrow("来源坐标重复"); });
it.each([null, "", " \r\n", 1, "错误正文", originalText.trim(), originalText.replace("\r\n", "\n")])("rejects empty/drifted body %j", async body => { await expect(bindOriginalRead(current, [{ source: current, originalText: body }])).rejects.toThrow("案件原件"); });
it("valid current original cannot hide a corrupt history body", async () => { await expect(bindOriginalRead(current, [original(), { source: { ...current, version: "v1" }, originalText: "错历史" }])).rejects.toThrow("正文摘要"); });
it.each([null, [], { ...current, sha256: "A".repeat(64) }, { ...current, kind: null }, { ...current, id: 3 }])("rejects malformed source shape %j", async bad => {
 await expect(bindOriginalRead(current, [{ source: bad, originalText }])).rejects.toThrow("来源坐标不合法");
});
it("does not use delimiter-joined coordinates that can collide", async () => {
 const left={...current,id:"x|y",version:"z"},right={...current,id:"x",version:"y|z"};
 expect(await bindOriginalRead(left,[{source:left,originalText},{source:right,originalText}])).toHaveLength(2);
});
