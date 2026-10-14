import type {MatrixReport,MatrixSummary} from './comparisonMatrix';
const labels:Record<string,string>={CORRESPONDING:'对应',PARTIAL:'部分对应',NOT_FOUND_IN_ANALYZED_RANGE:'已核范围内未找到',UNKNOWN:'无法判断'};
export default function ComparisonMatrixReport({report,features,parent=false}:{report:MatrixReport;features:MatrixSummary['confirmedFeatures'];parent?:boolean}){
 return <article><h3>{parent?'历史原生矩阵参考':'确切矩阵版本'} {report.result.resultVersion}</h3><details><summary>结果版本与追溯信息</summary><p style={{overflowWrap:'anywhere'}}>分析 {report.result.comparisonId} · 输入SHA：{report.result.inputSha} · 结果SHA：{report.reportSha}</p></details>
  {report.result.rows.map((row,i)=><section key={i}><h4>{features.find(f=>f.featureId===row.featureId)?.userText??`特征 ${i+1}`} · 权项分支 {row.claimBranch.join(' → ')} · {labels[row.relation]}</h4><p>{row.reasoning}</p><p>相同点：{row.sameAspects.join('；')} · 差异：{row.differences.join('；')}</p>{row.uncertainty&&<p>限制：{row.uncertainty}</p>}
   <h5>确切原件引用</h5>{row.citations.map((c,j)=><blockquote key={j}><p>{c.text}</p><p>文献版本 {c.documentVersion}</p><details><summary>引用原件定位</summary><p style={{overflowWrap:'anywhere'}}>{c.originalRef} · {c.jsonPointer} · 字节[{c.byteStart},{c.byteEnd}) · SHA {c.spanSha}</p></details></blockquote>)}
   <details><summary>实际分析范围</summary>{row.analyzedRange.map((r,j)=><p key={j}>{r.documentVersion} · {r.jsonPointer} · 字节[{r.byteStart},{r.byteEnd})</p>)}</details></section>)}
 </article>;
}
