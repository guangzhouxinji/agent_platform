import {afterEach,beforeEach,vi} from 'vitest';
import {cleanup,fireEvent,screen} from '@testing-library/react';
import {humanRequestSha,type HumanRequestCommand,type HumanRequestView} from './humanRequest';
import type {Workspace,Selection} from './types';

/** Browser component transport fixtures only; no live identity, source, SQL or accepted business workflow. */
export const fetchMock=vi.fn<typeof fetch>();
export type Wire={url:string;method:string;key:string|null;body:HumanRequestCommand|undefined;authorization:string|null};
export let wires:Wire[]=[];
export function token(subject='member'){return 'e30.'+btoa(JSON.stringify({iss:'https://identity.example.org',sub:subject})).replace(/=/g,'')+'.signature';}
export function workspace():Workspace{
  const expiresAt=new Date(Date.now()+600_000).toISOString();
  return {task:{id:'task',version:7,state:'IN_PROGRESS',expiresAt},
    goal:{inputId:'input-7',taskVersion:7,queryObject:'Battery',messageText:'User goal',sourceIds:['source'],requestedFields:['TITLE'],expiresAt},
    sources:[{sourceId:'source',fields:['TITLE','ABSTRACT'],expiresAt}],result:null,retentionRef:'task-policy',retentionVersion:1,selectionRevision:0,dialogue:[]};
}
export function props(){return {token:token(),workspace:workspace(),selection:null as Selection|null,
  comparison:null as {comparisonId:string;inputSha:string}|null,
  report:null as {comparisonId:string;inputSha:string;reportId:string;expiresAt:string}|null,
  disabled:false,onLockChange:vi.fn(),onDenied:vi.fn()};}
export function selection(w:Workspace):Selection{return {selectionId:'selected',taskId:w.task.id,revision:1,taskVersion:7,
  resultSetId:'result',resultSetVersion:1,resultSha:'a'.repeat(64),actorSubject:'member',createdAt:new Date(Date.now()-1000).toISOString(),expiresAt:w.task.expiresAt,
  members:[{memberId:'member-1',ordinal:0,sourceId:'source',documentId:'CN123',documentVersion:'raw-v1',sourceVersion:'source-v1',rawSha:'b'.repeat(64),locatorRef:'capture/raw.json#title'}]};}
export function json(value:unknown,status=200){return new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});}
export async function view(wire:Wire,t=token()):Promise<HumanRequestView>{
  const command=wire.body!;const now=Date.now();
  return {kind:'REQUEST_RECORD',receipt:{taskId:'task',packageId:'package',packageRevision:1,manifestSha:'c'.repeat(64),eventSequence:1,
    requestSha:await humanRequestSha(t,'task',wire.key!,command),createdAt:new Date(now-1).toISOString(),auditUntil:new Date(now+300_000).toISOString()},
    body:{taskVersion:command.expectedTaskVersion,reason:command.reason,purpose:command.purpose,
      goal:{type:'GOAL',id:'goal-7',revision:1,sha:'d'.repeat(64)},message:{type:'USER_MESSAGE',id:'message-7',revision:1,sha:'e'.repeat(64)},
      input:{type:'SEARCH_INPUT',id:'input-7',revision:1,sha:'f'.repeat(64)},materials:command.materials,
      comparisonId:command.comparison?.comparisonId??null,reportIds:command.comparison?.reportIds??[],
      gaps:!command.comparison?['NO_COMPARISON_SELECTED','NO_REPORT_SELECTED']:command.comparison.reportIds.length?[]:['NO_REPORT_SELECTED'],
      bodyAccessUntil:new Date(now+60_000).toISOString()}};
}
export function submit(reason='Explain the cited technical issue'){
  fireEvent.change(screen.getByLabelText('请求理由'),{target:{value:reason}});
  fireEvent.click(screen.getByRole('button',{name:'提交普通辅助请求'}));
}
export function lookup(){fireEvent.click(screen.getByRole('button',{name:'查回原请求'}));}
export function deferred(){let resolve!:(value:Response)=>void;const promise=new Promise<Response>(done=>{resolve=done});return {promise,resolve};}
beforeEach(()=>{
  sessionStorage.clear();wires=[];fetchMock.mockReset();
  vi.stubGlobal('fetch',(url:RequestInfo|URL,init?:RequestInit)=>{
    const headers=new Headers(init?.headers);
    wires.push({url:String(url),method:init?.method??'GET',key:headers.get('Idempotency-Key'),body:init?.body===undefined?undefined:JSON.parse(String(init.body)),authorization:headers.get('Authorization')});
    return fetchMock(url,init);
  });
});
afterEach(()=>{cleanup();sessionStorage.clear();vi.restoreAllMocks();vi.unstubAllGlobals();vi.useRealTimers();});
