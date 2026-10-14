import {readFile, access} from 'node:fs/promises';
import {resolve, sep} from 'node:path';
import {hash, canonical} from './single-database-lifecycle-runtime.mjs';
import {validateInheritedEnvironment,attachFreshRbc,adminConsumerUsesNamespace,ADMIN_TESTS} from '../../docs/evidence/enforcement-regression-repair-20261007/safe-full-environment-model.mjs';
const fail=c=>{throw new Error(c);};
export const requiredRegressionBindings=c=>[...new Set(c.consumers.flatMap(v=>Object.values(v.requirements)))].sort();
export function assertRegressionEnvironment(contract,env) {
 validateInheritedEnvironment(env);
 if(Object.keys(env).some(k=>k.startsWith('DM8_JDBC_')&&env[k]))fail('GENERIC_DM8_ENVIRONMENT_REFUSED');
 if(requiredRegressionBindings(contract).some(k=>typeof env[k]!=='string'||!env[k]))fail('REGRESSION_BINDINGS_MISSING');
 const tuples=[['AGENT_ENF_RULE_ENF_ACCEPTANCE_BUSINESS',contract.retainedReadonlyTuple],['AGENT_JOBPACK_JOB_PACK_DM8',contract.ordinaryJobPackTuple],['ENF_ACCEPTANCE_BUSINESS',contract.ordinaryJobPackTuple],['AGENT_ENF_ENGINEERING_ADMIN',{url:contract.legacyEngineeringTarget.jdbcUrl,user:'SYSDBA'}]];
 for(const [prefix,t] of tuples)if(env[prefix+'_URL']!==t.url||env[prefix+'_USER']!==t.user)fail('REGRESSION_NAMESPACE_TUPLE_MISMATCH');
 return {bindingCount:requiredRegressionBindings(contract).length,secretsReported:false};
}
// Metadata only: rights are checked BEFORE the historical wrapper may open actor/material/credentials.
export function validateRegressionLaunch(c,r,now=Date.now()) {
 const a=r?.approval;
 if(!a||a.approved!==true||a.taskId!==c.taskId||!a.source||!a.owner||!(Date.parse(a.startsAt)<=now&&Date.parse(a.expiresAt)>now))fail('CURRENT_EXACT_REGRESSION_DECISION_REQUIRED');
 if(a.engineeringTargetId!==c.legacyEngineeringTarget.containerId||!a.readonlySourceUse||a.allowStartStop!==false||a.allowCreate!==false)fail('EXACT_EXISTING_REGRESSION_SCOPE_REQUIRED');
 if(r.format!=='SINGLE_DATABASE_REGRESSION_LAUNCH/v1'||!r.inspectionFile||!r.rbcManifestFile||!r.outputRoot||!r.reportSuffix||!/^[A-Za-z0-9-]+$/.test(r.reportSuffix))fail('EXACT_REGRESSION_INPUTS_REQUIRED');
 if(canonical(a.inputs)!==canonical({inspectionSha256:r.inspectionSha256,rbcManifestSha256:r.rbcManifestSha256,preparationSha256:r.preparationSha256,outputRoot:r.outputRoot,reportSuffix:r.reportSuffix}))fail('APPROVED_REGRESSION_INPUT_BINDING_REQUIRED');
 const p=r.inspection?.plan,m=r.manifest;
 if(!p?.preparedRbc||!m||m.containerId!==a.rbcTargetId||m.runId!==a.runId||m.containerId===c.legacyEngineeringTarget.containerId||p.preparedRbc.containerId!==m.containerId||p.preparedRbc.runId!==m.runId||p.preparedRbc.schema!==m.schema||p.preparedRbc.fileRoot!==m.fileRoot)fail('EXACT_UNCONSUMED_RBC_REQUIRED');
 if(!/^[a-f0-9]{64}$/.test(m.containerId)||r.consumed!==false||r.outputExists!==false)fail('FRESH_RBC_AND_OUTPUT_REQUIRED');
 if(!r.preparation)fail('ACTUAL_UNCONSUMED_EMPTY_RBC_PREPARATION_REQUIRED');
 try {attachFreshRbc(p,m,r.preparation,r.rbcManifestFile);}catch{fail('ACTUAL_UNCONSUMED_EMPTY_RBC_PREPARATION_REQUIRED');}
 const env={...p.environment,...m.environment};
 if(Object.keys({...p.environment,...m.environment}).some(k=>/PASSWORD|^DM8_JDBC_/.test(k)))fail('PLAINTEXT_OR_GENERIC_PLAN_BINDING_REFUSED');
 if(env.JOB_PACK_DM8_URL!==`jdbc:dm://127.0.0.1:${m.port}`||env.JOB_PACK_DM8_USER!==m.schema||env.ENF_TEST_RUN_ID!==m.runId||env.ENF_TEST_CONTAINER!==m.container||env.ENF_TEST_VOLUME!==m.volume||env.ENF_TEST_DB_SCHEMA!==m.schema||env.ENF_TEST_FILE_ROOT!==m.fileRoot)fail('RBC_ENVIRONMENT_MISMATCH');
 const secrets=p.credentialBindings?.flatMap(b=>b.variables)||[];
 const names=requiredRegressionBindings(c);
 if(names.some(k=>!env[k]&&!secrets.includes(k)))fail('REGRESSION_BINDINGS_MISSING');
 assertRegressionEnvironment(c,{...env,...Object.fromEntries(secrets.map(k=>[k,'managed-reference-only']))});
 const files=names.filter(k=>!/_URL$|_USER$|_PASSWORD$|^ENF_TEST_|_OUTPUT$/.test(k)).map(k=>env[k]);
 files.push(p.retainedActor?.file);
 if(files.some(file=>!file||!a.sourceRights?.some(s=>s.path===file&&s.owner&&s.purpose&&s.licenseReference&&/^[a-f0-9]{64}$/.test(s.sha256||'')&&Date.parse(s.useUntil)>now)))fail('CURRENT_EXACT_INPUT_RIGHTS_REQUIRED');
 return {ready:true,taskId:c.taskId,bindingNames:names,actualExecution:false,scope:c.scope};
}
export async function preflightRegressionLaunch(root,requestPath) {
 const json=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
 const c=await json(resolve(root,'deployment/single-database/regression-launch-contract.json'));
 const r=await json(requestPath);
 // Reject absent/expired approval before opening even historical inspection metadata.
 validateDecisionOnly(c,r);
 const sources=[...c.consumers.map(v=>({path:v.sourcePath,sha256:v.sha256})),...c.helperSources,c.wrapper,c.model];
 for(const s of sources)if(hash(await readFile(resolve(root,s.path)))!==s.sha256)fail('REGRESSION_CONSUMER_OR_WRAPPER_SHA_DRIFT');
 for(const p of ADMIN_TESTS)if(!adminConsumerUsesNamespace(await readFile(resolve(root,p),'utf8')))fail('ACTUAL_ADMIN_NAMESPACE_DRIFT');
 const owned=resolve(root,'docs/evidence/enforcement-regression-repair-20261007')+sep;
 for(const p of [r.inspectionFile,r.rbcManifestFile,r.preparationFile])if(!resolve(p).startsWith(owned))fail('EXISTING_WRAPPER_OWNED_METADATA_REQUIRED');
 for(const [p,sha] of [[r.inspectionFile,r.inspectionSha256],[r.rbcManifestFile,r.rbcManifestSha256],[r.preparationFile,r.preparationSha256]])if(hash(await readFile(p))!==sha)fail('REGRESSION_METADATA_SHA_MISMATCH');
 r.inspection=await json(r.inspectionFile);r.manifest=await json(r.rbcManifestFile);r.preparation=await json(r.preparationFile);
 const exists=async p=>{try{await access(p);return true;}catch(e){if(e.code==='ENOENT')return false;throw e;}};
 r.outputExists=await exists(r.outputRoot);r.consumed=await exists(resolve(r.manifest.fileRoot,'.enf-rbc-run-started'));
 return validateRegressionLaunch(c,r);
}
function validateDecisionOnly(c,r) {
 const a=r?.approval;
 if(!a||a.approved!==true||a.taskId!==c.taskId||!a.owner||!a.source||!(Date.parse(a.startsAt)<=Date.now()&&Date.parse(a.expiresAt)>Date.now()))fail('CURRENT_EXACT_REGRESSION_DECISION_REQUIRED');
}
