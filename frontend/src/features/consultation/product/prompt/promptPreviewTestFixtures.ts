import {revision as readRevision,workspaceId,promptId,revisionId} from './promptTestFixtures';
import type {PromptRevisionView} from './promptApi';
import type {PromptPreviewDiagnostic,PromptPreviewResult} from './promptPreviewApi';
export {workspaceId,promptId,revisionId};
export const sourceId='00000000-0000-4000-8000-000000000040';
export function previewRevision():PromptRevisionView{return {...readRevision(),body:'amount={{amount}} total={{total}} flag={{flag}} list={{list}} text={{text}}',variables:[
  {name:'amount',type:'INTEGER',required:true,sourceRef:'approved',allowDefault:false,defaultValue:null,allowEmpty:false},
  {name:'total',type:'DECIMAL',required:false,sourceRef:'approved',allowDefault:true,defaultValue:'0.0001',allowEmpty:false},
  {name:'flag',type:'BOOLEAN',required:false,sourceRef:'approved',allowDefault:false,defaultValue:null,allowEmpty:false},
  {name:'list',type:'STRING_LIST',required:false,sourceRef:'approved',allowDefault:false,defaultValue:null,allowEmpty:true},
  {name:'text',type:'STRING',required:false,sourceRef:'approved',allowDefault:false,defaultValue:null,allowEmpty:true},
]};}
export function previewResult():PromptPreviewResult{return {status:'OK',mode:'TEST',workspaceId,promptId,revisionId,revisionNumber:'1',contentSha:'a'.repeat(64),schemaSha:'b'.repeat(64),policySha:'c'.repeat(64),expiresAt:new Date(Date.now()+60000).toISOString(),segments:[
  {kind:'LITERAL',text:'amount='},{kind:'DATA',name:'amount',type:'INTEGER',value:'90071992547409931234567890',sourceEvidenceId:'TEST_SAMPLE:amount'},
  {kind:'LITERAL',text:' total='},{kind:'DATA',name:'total',type:'DECIMAL',value:'9007199254740993123.00100',sourceEvidenceId:'TEST_PERMISSION:default'},
]};}
export function previewDiagnostic():PromptPreviewDiagnostic{return {code:'PROMPT_PREVIEW_INVALID_VALUES',expiresAt:new Date(Date.now()+60000).toISOString(),issues:[{code:'MISSING_VARIABLE',variable:'amount'}]};}
export const previewResponse=(value:unknown,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});
