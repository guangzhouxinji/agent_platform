import { describe, expect, it } from 'vitest';
import { assertSelection, type SelectionWrite } from './selection';
import type { Selection } from './types';
const fixture=():Selection=>({selectionId:'saved',taskId:'task',revision:1,taskVersion:2,resultSetId:'result',resultSetVersion:1,resultSha:'b'.repeat(64),actorSubject:'member',createdAt:'2026-10-06T10:00:00Z',expiresAt:'2027-01-01T00:00:00Z',members:[{memberId:'member',ordinal:0,sourceId:'source',documentId:'patent',documentVersion:'1',sourceVersion:'1',rawSha:'a'.repeat(64),locatorRef:'json-pointer=/title'}]});
const write:SelectionWrite={taskId:'task',resultSetId:'result',key:'original',body:{expectedRevision:0,resultSha:'b'.repeat(64),memberIds:['member']}};
describe('selection receipt binding; engineering fixtures, not formal acceptance',()=>{
 it('accepts persisted selection and explicitly displayed older task version',()=>{expect(assertSelection(fixture(),'task','result','saved')).toEqual(fixture());const old=fixture();old.taskVersion=1;expect(assertSelection(old,'task','result','saved').taskVersion).toBe(1);});
 it('checks exact save result, revision and member set',()=>expect(assertSelection(fixture(),'task','result',undefined,write)).toEqual(fixture()));
 const changes:Record<string,(v:Selection)=>void>={wrongTask:v=>{v.taskId='other'},wrongResult:v=>{v.resultSetId='other'},wrongSelection:v=>{v.selectionId='other'},badSha:v=>{v.resultSha='bad'},duplicate:v=>{v.members.push(v.members[0])},badRaw:v=>{v.members[0].rawSha='bad'},noLocator:v=>{v.members[0].locatorRef=''},missing:v=>{v.members=[]},badVersion:v=>{v.taskVersion=0},badTime:v=>{v.expiresAt='bad'}};
 for(const [name,edit]of Object.entries(changes))it(`rejects ${name}`,()=>{const value=fixture();edit(value);expect(()=>assertSelection(value,'task','result','saved')).toThrow();});
 it('rejects acknowledged writes with changed revision/SHA/member',()=>{for(const edit of[(v:Selection)=>{v.revision=2},(v:Selection)=>{v.resultSha='c'.repeat(64)},(v:Selection)=>{v.members[0].memberId='other'}]){const value=fixture();edit(value);expect(()=>assertSelection(value,'task','result',undefined,write)).toThrow();}});
});
