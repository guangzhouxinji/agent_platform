import {webcrypto} from 'node:crypto';
import {useState} from 'react';
import {cleanup,fireEvent,render,screen} from '@testing-library/react';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import ComparisonInputWorkbench from './ComparisonInputWorkbench';
import ComparisonMatrixResults from './ComparisonMatrixResults';
import {workspace,selection,context,preparation,featureView,inputReceipt,digest} from './comparisonWorkbench.testFixtures';
import type {Preparation,FeatureView,InputReceipt} from './comparisonInputContracts';
const ok=(value:unknown)=>new Response(JSON.stringify(value),{status:200});
// Existing product components with engineering transport fixtures; no real model or authorization claim.
function Chain(){
 const [bound,setBound]=useState<{id:string;sha:string}|null>(null);
 return <><ComparisonInputWorkbench token="session" workspace={workspace} selection={selection} disabled={false} onDenied={()=>setBound(null)} onLockChange={()=>{}} onInput={(id,sha)=>setBound(id&&sha?{id,sha}:null)}/>
 <ComparisonMatrixResults token="session" taskId="task" disabled={false} onDenied={()=>setBound(null)} defaultComparisonId={bound?.id??''} defaultInputSha={bound?.sha??''}/></>;
}
beforeEach(()=>vi.stubGlobal('crypto',webcrypto));afterEach(()=>{cleanup();vi.unstubAllGlobals()});
it.each([false,true])('carries the confirmed Input SHA to the real result component (wrong SHA=%s)',async wrong=>{
 let p:Preparation|null=null,f:FeatureView|null=null,input:InputReceipt|null=null;
 const fetch=vi.fn(async(url:string,init:RequestInit)=>{
  const key=(init.headers as Record<string,string>)['Idempotency-Key'],body=init.body?JSON.parse(String(init.body)):null;
  if(url.endsWith('/context'))return ok(context);
  if(init.method==='POST'){
   if(url.endsWith('/prepare')){p=preparation(key);return ok(p)}
   if(url.endsWith('/confirm')){f=featureView(body,key);return ok(f)}
   input=inputReceipt(f!);return ok(input);
  }
  if(url.endsWith('/results'))return ok({taskId:'task',comparisonId:input!.comparisonId,inputSha:wrong?digest('wrong-input'):input!.inputSha,state:'IN_PROGRESS',confirmedFeatures:f!.revision.features.map(v=>({featureId:v.featureId,userText:v.userText})),candidates:input!.candidates.map(c=>({...c,attemptId:null,errorCode:null,executionState:null,reportIds:[]}))});
  if(url.includes('/preparations/'))return ok(p);if(url.includes('/features/'))return ok(f);return ok(input);
 });vi.stubGlobal('fetch',fetch);render(<Chain/>);
 fireEvent.click(screen.getByRole('button',{name:'读取当前对照上下文'}));await screen.findByLabelText(/明确使用当前已保存/);
 fireEvent.click(screen.getByLabelText(/明确使用当前已保存/));fireEvent.click(screen.getByLabelText(/候选 patent/));fireEvent.change(screen.getByLabelText('文献 patent 所选权项'),{target:{value:'1'}});fireEvent.click(screen.getByRole('button',{name:'准备确切对照原文'}));
 const source=await screen.findByLabelText('目标确切原文') as HTMLTextAreaElement;source.setSelectionRange(0,7);fireEvent.click(screen.getByRole('button',{name:'将选中原文加入特征'}));fireEvent.click(screen.getByLabelText(/我已逐项核对/));fireEvent.click(screen.getByRole('button',{name:'确认当前特征版'}));
 fireEvent.click(await screen.findByRole('button',{name:'创建不可变对照输入'}));await screen.findByText(/分析编号 comparison/);
 expect(screen.getByLabelText('分析编号')).toHaveValue('comparison');expect(screen.getByLabelText('分析编号')).toHaveAttribute('readonly');
 fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));
 if(wrong){await screen.findByRole('alert');expect(screen.queryByText(/尚未全部提交/)).not.toBeInTheDocument()}
 else await screen.findByText(/尚未全部提交/);
 expect(fetch.mock.calls.filter(([,init])=>init.method==='POST')).toHaveLength(3);
 expect(fetch.mock.calls.at(-1)![1].method).toBe('GET');expect(fetch.mock.calls.every(([url])=>!url.includes('executions')&&!url.includes('advance')&&!url.includes('model'))).toBe(true);
});
