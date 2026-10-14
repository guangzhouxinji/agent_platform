import { describe, expect, it } from 'vitest';
import { assertDraftWriteReceipt } from './enforcementDraftReceipt';
const sha='a'.repeat(64);
describe('exact document command receipts',()=>{
  it.each([['generate','GENERATE'],['candidates','REGENERATE'],['revisions','REVISE'],['adopt','ADOPT'],['submit-review','SUBMIT_REVIEW'],['reviews','REVIEW'],['exports','EXPORT']])('accepts the real %s controller contract',(suffix,operation)=>{
    const request={path:'/document-drafts/draft/'+suffix,bodyJson:'{}',key:'original'};
    const value={draftId:'draft',requestId:'original',revision:2,contentSha256:sha,operation,exportId:'export'};
    expect(()=>assertDraftWriteReceipt(request,value)).not.toThrow();
    expect(()=>assertDraftWriteReceipt(request,{...value,requestId:'different'})).toThrow();
    expect(()=>assertDraftWriteReceipt(request,{...value,operation:'UNKNOWN'})).toThrow();
  });
  it('refuses an export receipt without the retained artifact identifier',()=>{
    expect(()=>assertDraftWriteReceipt({path:'/document-drafts/draft/exports',bodyJson:'{}',key:'key'},
      {draftId:'draft',requestId:'key',revision:1,contentSha256:sha,operation:'EXPORT'})).toThrow();
  });
});
