import { Button, Checkbox, Space, Tag, Typography } from "antd";
import type { Citation, ResultPage } from "./types";
import { sourceLink } from "./api";

export function Citations({citations}:{citations:Citation[]}){
  return <ol className="consultation-citations">{citations.map(c=><li key={c.memberId}>
    <strong>{c.documentId}</strong> <a href={sourceLink(c.sourceUrl)} target="_blank" rel="noopener noreferrer">原始来源</a>
    <details><summary>核查版本与精确定位</summary><dl>
      <dt>文献版本</dt><dd>{c.documentVersion}</dd><dt>来源版本</dt><dd>{c.sourceVersion}</dd>
      <dt>原始数据摘要</dt><dd>{c.rawSha}</dd><dt>原文定位</dt><dd>{c.locatorRef}</dd><dt>字段定位</dt><dd>{Object.entries(c.fieldLocators??{}).map(([field,locator])=><p key={field}>{field}：{locator}</p>)}</dd>
    </dl></details></li>)}</ol>;
}
export function Results({page,selected,onSelect,onNext,onExplain,onSave,busy}:{page:ResultPage;selected:string[];onSelect:(ids:string[])=>void;onNext:()=>void;onExplain:()=>void;onSave:()=>void;busy:boolean}){
  return <section aria-labelledby="results-heading" className="consultation-panel">
    <h2 id="results-heading">真实检索结果</h2>
    <Typography.Paragraph>候选 {page.candidateCount} 条{page.truncated?"（数量为下界）":""} · 当前显示 {page.members.length} 条</Typography.Paragraph>
    <Space wrap><Tag>{page.confidenceStatus==="LOW_CONFIDENCE"?"低置信度，需核查":page.confidenceStatus}</Tag><Tag>{page.status}</Tag></Space>
    <p className="consultation-muted">检索评分不代表法律概率或专业结论。模型说明只处理本轮第一页最多 20 条实际候选。</p>
    <ul className="consultation-result-list">{page.members.map(member=><li key={member.memberId}>
      <Checkbox disabled={busy} checked={selected.includes(member.memberId)} onChange={e=>onSelect(e.target.checked?[...selected,member.memberId]:selected.filter(id=>id!==member.memberId))}>
        <strong>{member.ordinal+1}. {member.documentId}</strong>
      </Checkbox><p>匹配字段：{member.matchFields.join("、")} · 命中词：{member.matchedTerms.length?member.matchedTerms.join("、"):"无词汇命中，仅有其他检索依据"}</p>
      <p className="consultation-muted">来源：{member.sourceId}</p>
    </li>)}</ul>
    <Space wrap><Button disabled={busy||!page.nextCursor} onClick={onNext}>下一页</Button>
      <Button disabled={busy} onClick={onExplain}>依据实际结果生成说明</Button>
      <Button type="primary" disabled={busy||!selected.length} onClick={onSave}>保存所选文献（{selected.length}）</Button></Space>
  </section>;
}
