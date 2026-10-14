import type { Selection } from './types';
import {Button,Input} from 'antd';

export function SavedSelectionAccess({resultId,selectionId,busy,onResultChange,onSelectionChange,onRead}:{
  resultId:string;selectionId:string;busy:boolean;
  onResultChange:(value:string)=>void;onSelectionChange:(value:string)=>void;onRead:()=>void;
}){
  return <section className="consultation-panel" aria-labelledby="selection-read-heading"><h2 id="selection-read-heading">读取已保存选择</h2>
    <label htmlFor="selection-result-id">选择所属结果集编号</label><Input id="selection-result-id" value={resultId} disabled={busy} onChange={e=>onResultChange(e.target.value)}/>
    <label htmlFor="selection-id">选择编号</label><Input id="selection-id" value={selectionId} disabled={busy} onChange={e=>onSelectionChange(e.target.value)}/>
    <Button disabled={busy||!selectionId.trim()||!resultId.trim()} onClick={onRead}>读取已保存文献</Button>
    <p>仅按已知编号读取当前任务的保存记录，不列举其他任务；每次读取重新核验权限、来源和期限。</p>
  </section>;
}

export default function SavedSelection({ value }: { value: Selection }) {
  return <section className="consultation-panel" aria-labelledby="saved-selection-heading">
    <h2 id="saved-selection-heading">已保存的文献选择</h2>
    <p role="status">服务器已确认选择版本 {value.revision}。选择是用户记录，不是专业采信或法律结论。</p>
    <dl><dt>选择编号</dt><dd>{value.selectionId}</dd><dt>所属任务</dt><dd>{value.taskId} · 保存时任务版本 {value.taskVersion}</dd>
      <dt>结果集编号</dt><dd>{value.resultSetId}</dd><dt>绑定结果摘要</dt><dd>{value.resultSha}</dd>
      <dt>保存时间</dt><dd>{value.createdAt}</dd><dt>记录期限</dt><dd>{value.expiresAt}（现时权限仍由每次服务器读取核验）</dd></dl>
    <p>可记录结果集编号和选择编号，之后使用“读取已保存选择”核验。历史选择不会自动勾选当前检索候选。</p>
    <ul className="consultation-result-list">{value.members.map(member => <li key={member.memberId}>
      <strong>{member.documentId}</strong><dl><dt>文献版本</dt><dd>{member.documentVersion}</dd>
        <dt>来源及版本</dt><dd>{member.sourceId} · {member.sourceVersion}</dd>
        <dt>原始数据摘要</dt><dd>{member.rawSha}</dd><dt>原文定位</dt><dd>{member.locatorRef}</dd></dl>
    </li>)}</ul>
  </section>;
}
