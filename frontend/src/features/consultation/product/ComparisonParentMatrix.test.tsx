import {webcrypto} from 'node:crypto';
import {act,cleanup,fireEvent,render,screen} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import {matrixDigest,type MatrixReport} from './comparisonMatrix';
import type {MatrixLineage} from './comparisonLineage';
import {bindParentMatrix,type ParentMatrix} from './comparisonParentContracts';
import ComparisonParentMatrix from './ComparisonParentMatrix';
const hash=(c:string)=>c.repeat(64);
async function fixture(){
 vi.stubGlobal('crypto',webcrypto);
 const candidate=hash('b'),parentSha=hash('c'),expiry=new Date(Date.now()+60000).toISOString();
 const row={candidateId:candidate,featureId:'F1',claimBranch:[1],relation:'UNKNOWN',sameAspects:[],differences:[],reasoning:'父版分析理由',uncertainty:'原资料缺口',analyzedRange:[],citations:[]};
 const result:MatrixReport['result']={taskId:'task',comparisonId:'parent',candidateId:candidate,inputSha:parentSha,attemptId:'attempt',token:1,reportId:'report',resultVersion:1,methodVersion:'PATENT_FEATURE_MATRIX_V1',workerSubject:'worker',configurationVersion:'old-config',approvalSha:hash('d'),responseSha:hash('e'),createdAt:new Date(Date.now()-60000).toISOString(),expiresAt:expiry,rows:[row]};
 const originalJson=' \n'+JSON.stringify(result)+'\n';
 const bytes=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(originalJson)),reportSha=Array.from(new Uint8Array(bytes)).map(v=>v.toString(16).padStart(2,'0')).join('');
 const report:MatrixReport={result,reportSha,receipt:{taskId:'task',comparisonId:'parent',candidateId:candidate,inputSha:parentSha,attemptId:'attempt',token:1,reportId:'report',reportSha,resultVersion:1,status:'COMMITTED'}};
 const key={candidateId:candidate,claimBranch:[1],featureId:'F1'};
 const ancestry=[{comparisonId:'parent',inputSha:parentSha,previousComparisonId:null}];
 const lineage:MatrixLineage={taskId:'task',comparisonId:'current',inputSha:hash('a'),previousComparisonId:'parent',parentInputSha:parentSha,configurationVersion:'new-config',configurationSha:hash('f'),expiresAt:expiry,expectedRows:[key],invalidated:[],recompute:[],carried:[{key,comparisonId:'parent',inputSha:parentSha,reportId:'report',reportSha,rowSha:await matrixDigest(row)}],ancestry};
 const value:ParentMatrix={taskId:'task',comparisonId:'current',inputSha:hash('a'),previousComparisonId:'parent',parentInputSha:parentSha,expiresAt:expiry,confirmedFeatures:[{featureId:'F1',userText:'父版确认特征'}],report,originalJson,sourceComparisonId:'parent',sourceInputSha:parentSha,ancestry};
 return {lineage,value,ref:lineage.carried[0]};
}
const response=(value:unknown,status=200)=>({ok:status===200,status,json:async()=>value}) as Response;
const start=()=>fireEvent.click(screen.getByRole('button',{name:'查看沿用原矩阵 1'}));
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();vi.useRealTimers()});
it('preserves noncanonical original bytes and authenticated parent feature text',async()=>{const f=await fixture();expect(await bindParentMatrix(f.lineage,f.ref,f.value)).toEqual(f.value)});
it('displays a grandparent original with separate direct-parent coordinates',async()=>{
 const f=await fixture();f.lineage.previousComparisonId=f.value.previousComparisonId='middle';f.lineage.parentInputSha=f.value.parentInputSha=hash('d');
 const ancestry=[{comparisonId:'middle',inputSha:hash('d'),previousComparisonId:'parent'},{comparisonId:'parent',inputSha:hash('c'),previousComparisonId:null}];f.lineage.ancestry=ancestry;f.value.ancestry=ancestry;
 const fetch=vi.fn().mockResolvedValue(response(f.value));vi.stubGlobal('fetch',fetch);render(<ComparisonParentMatrix token="session" lineage={f.lineage} disabled={false} onDenied={()=>{}}/>);start();await screen.findByText('父版分析理由');expect(fetch).toHaveBeenCalledTimes(1);expect(f.value.report.result.comparisonId).toBe('parent');
});
it.each(['source','source-sha','ancestry'])('refuses ancestral %s rebinding',async kind=>{const f=await fixture();if(kind==='source')f.value.sourceComparisonId='other';else if(kind==='source-sha')f.value.sourceInputSha=hash('f');else f.value.ancestry=[];await expect(bindParentMatrix(f.lineage,f.ref,f.value)).rejects.toThrow()});
it('takes earlier lineage metadata expiry without requiring it for independent parent reading',async()=>{const f=await fixture();f.lineage.expiresAt=new Date(Date.now()+10000).toISOString();const result=await bindParentMatrix(f.lineage,f.ref,f.value);expect(result.expiresAt).toBe(f.lineage.expiresAt);expect(result.originalJson).toBe(f.value.originalJson)});
it.each(['task','input','parent','parent-sha','report-sha','bytes','result','row','feature','receipt','expiry','extended','reference'])('rejects mismatched %s',async kind=>{
 const f=await fixture(),v=f.value;
 switch(kind){case 'task':v.taskId='foreign';break;case 'input':v.inputSha=hash('e');break;case 'parent':v.previousComparisonId='other';break;case 'parent-sha':v.parentInputSha=hash('f');break;case 'report-sha':v.report.reportSha=hash('e');break;case 'bytes':v.originalJson=v.originalJson.trim();break;case 'result':v.report.result.rows[0].reasoning='changed';break;case 'row':f.ref.rowSha=hash('f');break;case 'feature':v.confirmedFeatures=[{featureId:'other',userText:'other'}];break;case 'receipt':v.report.receipt.status='RUNNING';break;case 'expiry':v.expiresAt=new Date(0).toISOString();break;case 'extended':v.expiresAt=new Date(Date.now()+120000).toISOString();break;case 'reference':f.ref={...f.ref,reportId:'other'};break;}
 await expect(bindParentMatrix(f.lineage,f.ref,v)).rejects.toThrow();
});
it('one explicit GET displays parent reference without a new current submission, close clears it',async()=>{
 const f=await fixture(),fetch=vi.fn().mockResolvedValue(response(f.value));vi.stubGlobal('fetch',fetch);
 render(<ComparisonParentMatrix token="session" lineage={f.lineage} disabled={false} onDenied={()=>{}}/>);expect(fetch).not.toHaveBeenCalled();start();await screen.findByText('父版分析理由');
 expect(fetch).toHaveBeenCalledTimes(1);expect(fetch.mock.calls[0][0]).toBe('/v2/consultation/tasks/task/patent-comparisons/current/parent-reports/report');expect(fetch.mock.calls[0][1]).toMatchObject({method:'GET',cache:'no-store'});
 expect(screen.getByRole('heading',{name:'历史原生矩阵参考 1'})).toBeInTheDocument();expect(screen.queryByText('已提交')).not.toBeInTheDocument();fireEvent.click(screen.getByRole('button',{name:'关闭父矩阵'}));expect(screen.queryByText('父版分析理由')).not.toBeInTheDocument();
});
it.each([401,403,404,409,503])('clears failed %s response and only propagates authority denial',async status=>{
 const f=await fixture(),onDenied=vi.fn();vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response({},status)));
 render(<ComparisonParentMatrix token="session" lineage={f.lineage} disabled={false} onDenied={onDenied}/>);start();await screen.findByRole('alert');expect(screen.queryByText('父版分析理由')).not.toBeInTheDocument();expect(onDenied).toHaveBeenCalledTimes([401,403,404].includes(status)?1:0);
});
it.each(['token','input','disabled','unmount'])('does not display late response after %s',async kind=>{
 const f=await fixture();let resolve!:(v:Response)=>void;vi.stubGlobal('fetch',vi.fn().mockReturnValue(new Promise<Response>(yes=>{resolve=yes})));
 const p={token:'session',lineage:f.lineage,disabled:false,onDenied:vi.fn()},ui=render(<ComparisonParentMatrix {...p}/>);start();
 if(kind==='unmount')ui.unmount();else ui.rerender(<ComparisonParentMatrix {...p} {...(kind==='token'?{token:'other'}:kind==='disabled'?{disabled:true}:{lineage:{...f.lineage,inputSha:hash('f')}})}/>);
 await act(async()=>{resolve(response(f.value))});expect(screen.queryByText('父版分析理由')).not.toBeInTheDocument();expect(p.onDenied).not.toHaveBeenCalled();
});
it('clears parent content at the earliest response deadline',async()=>{
 const f=await fixture();f.value.expiresAt=new Date(Date.now()+1000).toISOString();vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response(f.value)));
 render(<ComparisonParentMatrix token="session" lineage={f.lineage} disabled={false} onDenied={()=>{}}/>);start();await screen.findByText('父版分析理由');await act(async()=>{await new Promise(resolve=>setTimeout(resolve,1100))});expect(screen.queryByText('父版分析理由')).not.toBeInTheDocument();
});
