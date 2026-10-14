import { useEffect, useState } from "react";
import type { ReportView } from "./contract";
import { sections } from "./contract";

type Draft = { content: string; reason: string };
type Props = {
 report: ReportView["report"];
 onDirty: (dirty: boolean) => void;
 save: (body: Record<string, unknown>) => Promise<boolean | void>;
};

/** In-memory human edits only. The existing API owns versions, permissions and review state. */
export default function ReportRevisionEditor({ report, onDirty, save }: Props) {
 const [sectionId, setSectionId] = useState(report.sections[0]?.id ?? "");
 const [drafts, setDrafts] = useState<Record<string, Draft>>({});
 const [saving, setSaving] = useState(false);
 const [failure, setFailure] = useState<string | null>(null);
 const section = report.sections.find(s => s.id === sectionId);
 const draft = drafts[sectionId] ?? { content: section?.content ?? "", reason: "" };
 const dirty = Object.entries(drafts).some(([id, value]) =>
  value.content !== report.sections.find(s => s.id === id)?.content || value.reason !== "");
 useEffect(() => { onDirty(dirty); return () => onDirty(false); }, [dirty, onDirty]);
 useEffect(() => {
  if (!dirty) return;
  const protect = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
  window.addEventListener("beforeunload", protect);
  return () => window.removeEventListener("beforeunload", protect);
 }, [dirty]);
 function edit(field: keyof Draft, value: string) {
  setDrafts(current => ({ ...current, [sectionId]: { ...draft, [field]: value } }));
  setFailure(null);
 }
 if (!section) return <p role="alert">报告没有可修订章节，请核查服务器返回的报告版本。</p>;
 return <form aria-label="报告修订" onSubmit={async event => {
  event.preventDefault();
  if (saving) return;
  const otherDirty = Object.entries(drafts).some(([id, value]) => id !== sectionId &&
   (value.content !== report.sections.find(s => s.id === id)?.content || value.reason !== ""));
  if (otherDirty && !window.confirm("本次只保存所选章节。保存成功后将进入新稿，其他章节未保存修订将被放弃。是否继续？")) return;
  setSaving(true); setFailure(null);
  try {
   const confirmed = await save({ reportSha256: report.contentSha256, sectionId,
    content: draft.content, reason: draft.reason });
   if (confirmed === true) { setDrafts({}); onDirty(false); }
   else setFailure("保存尚未确认，修订内容已保留。请核查服务器回执及上方错误，不会自动换版本或重试。");
  } catch {
   setFailure("保存失败，修订内容已保留。请核查请求结果后再操作。");
  } finally { setSaving(false); }
 }}>
  <h3>人工修订（生成新稿，不覆盖原稿）</h3>
  <p>基于报告 v{report.revision}，正文已载入所选章节。切换章节保留各自未保存内容；每次保存只提交所选章节。</p>
  <fieldset disabled={saving}>
   <label>修订章节<select aria-label="修订章节" name="sectionId" value={sectionId}
    onChange={event => setSectionId(event.target.value)}>
    {report.sections.map(s => <option key={s.id} value={s.id}>{sections[s.id] ?? s.id}</option>)}
   </select></label>
   <details><summary>本次修订的原章节正文</summary><div className="adj-long-text">{section.content}</div></details>
   <label>修订内容<textarea aria-label="修订内容" name="content" required rows={12}
    value={draft.content} onChange={event => edit("content", event.target.value)} /></label>
   <label>修订原因<textarea aria-label="修订原因" name="reason" required rows={4}
    value={draft.reason} onChange={event => edit("reason", event.target.value)} /></label>
   <p role="status">{dirty ? "有未保存修订，仅保留在当前工作台内存中。" : "尚无未保存修订。"}</p>
   {failure && <p role="alert">{failure}</p>}
   <button type="submit">{saving ? "正在保存人工修订…" : "保存人工修订新版本"}</button>
   <button type="button" disabled={!dirty} onClick={() => {
    if (window.confirm("放弃全部未保存修订并恢复原章节正文？")) { setDrafts({}); setFailure(null); }
   }}>放弃未保存修订</button>
  </fieldset>
 </form>;
}
