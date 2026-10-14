import {lazy,Suspense} from 'react';
import EnforcementWorkspace from './EnforcementWorkspace';
import type {PageKey} from './contract';
const Job=lazy(()=>import('../workflows/EnforcementJobPackV2Page'));
const Templates=lazy(()=>import('../workflows/EnforcementDocumentTemplateV2Page'));
const Rules=lazy(()=>import('../workflows/EnforcementDocumentRuleV2Page'));
const Legal=lazy(()=>import('../workflows/EnforcementLegalReferenceV2Page'));
export default function EnforcementProductApp({enabledPages}:{enabledPages:readonly PageKey[]}){
 const path=window.location.pathname;
 if(['/','/app','/app/','/app/enforcement','/app/enforcement/','/app/enforcement.html'].includes(path))return <EnforcementWorkspace enabledPages={enabledPages}/>;
 const page=path.slice('/app/enforcement/'.length) as PageKey;
 if(!path.startsWith('/app/enforcement/')||!enabledPages.includes(page))return <p role="alert">此入口不可用。</p>;
 const views={'job-pack':<Job/>,'document-templates':<Templates/>,'document-rules':<Rules/>,'legal-references':<Legal/>};
 return <><a className="enforcement-entry-back" href="/app/enforcement">返回执法工作台</a><Suspense fallback={<p role="status">正在加载当前工作。</p>}>{views[page]}</Suspense></>;
}
