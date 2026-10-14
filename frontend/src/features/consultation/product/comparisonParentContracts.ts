import {bindMatrixReport,matrixDigest,type MatrixReport,type MatrixSummary} from './comparisonMatrix';
import {lineageKey,validAncestry,type Ancestor,type CarryReference,type MatrixLineage} from './comparisonLineage';
export type ParentMatrix={taskId:string;comparisonId:string;inputSha:string;previousComparisonId:string;parentInputSha:string;expiresAt:string;confirmedFeatures:MatrixSummary['confirmedFeatures'];report:MatrixReport;originalJson:string;sourceComparisonId:string;sourceInputSha:string;ancestry:Ancestor[]};
const object=(v:unknown):v is Record<string,unknown>=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const fail=():never=>{throw new Error('父矩阵与沿用引用不一致，未展示该响应。')};
export async function bindParentMatrix(lineage:MatrixLineage,reference:CarryReference,value:unknown):Promise<ParentMatrix>{
 if(!lineage.carried.some(r=>JSON.stringify(r)===JSON.stringify(reference))||!object(value)||value.taskId!==lineage.taskId||value.comparisonId!==lineage.comparisonId||value.inputSha!==lineage.inputSha||value.previousComparisonId!==lineage.previousComparisonId||value.parentInputSha!==lineage.parentInputSha||typeof value.expiresAt!=='string'||!Number.isFinite(Date.parse(value.expiresAt))||typeof value.originalJson!=='string'||!value.originalJson.length||!object(value.report)||!object(value.report.result)||!Array.isArray(value.confirmedFeatures))return fail();
 const report=value.report as MatrixReport;
 if(!validAncestry(value.ancestry,lineage.comparisonId,lineage.inputSha,lineage.previousComparisonId,lineage.parentInputSha)||JSON.stringify(value.ancestry)!==JSON.stringify(lineage.ancestry)||value.sourceComparisonId!==reference.comparisonId||value.sourceInputSha!==reference.inputSha||!lineage.ancestry.some(a=>a.comparisonId===reference.comparisonId&&a.inputSha===reference.inputSha))return fail();
 const summary:MatrixSummary={taskId:lineage.taskId,comparisonId:reference.comparisonId,inputSha:reference.inputSha,state:'COMMITTED',confirmedFeatures:value.confirmedFeatures as MatrixSummary['confirmedFeatures'],candidates:[{candidateId:reference.key.candidateId,status:'COMMITTED',attemptId:report.result.attemptId,errorCode:null,executionState:'COMMITTED',reportIds:[reference.reportId]}]};
 await bindMatrixReport(summary,reference.key.candidateId,reference.reportId,report,value.originalJson);
 if(report.reportSha!==reference.reportSha||Date.parse(value.expiresAt)>Date.parse(report.result.expiresAt))return fail();
 for(const ref of lineage.carried.filter(r=>r.reportId===reference.reportId)){
  const rows=report.result.rows.filter(r=>lineageKey(r)===lineageKey(ref.key));
  if(rows.length!==1||await matrixDigest(rows[0])!==ref.rowSha)return fail();
 }
 if(Date.parse(value.expiresAt)<=Date.now()||Date.parse(lineage.expiresAt)<=Date.now())return fail();
 return {...value,expiresAt:new Date(Math.min(Date.parse(value.expiresAt),Date.parse(lineage.expiresAt))).toISOString()} as ParentMatrix;
}
