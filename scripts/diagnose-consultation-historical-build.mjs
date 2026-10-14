import { readFileSync, writeFileSync, realpathSync, existsSync } from 'node:fs';
import { resolve, relative, dirname, isAbsolute } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

// Offline metadata only. Never opens a Goal, protected package, source body, DB or credential.
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = 'docs/evidence/consultation-historical-build-20261007';
const PREFLIGHT = 'docs/evidence/consultation-approved-release-20261007/preflight';
const HISTORY = 'docs/evidence/consultation-search-20261002';
// Immutable prior diagnostic anchor; current source is intentionally allowed to evolve.
const HISTORICAL_ANCHOR = `${OUT}/diagnosis-validated.json`;
const HISTORICAL_ANCHOR_SHA = '814821e60e30569ab291866ba1b026d8d29055e8b22acf419c3ec0618049974b';
const JAVA = 'backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const canonical = x => JSON.stringify(Array.isArray(x) ? x.map(y => JSON.parse(canonical(y))) : x && typeof x === 'object' ? Object.fromEntries(Object.keys(x).sort().map(k => [k, JSON.parse(canonical(x[k]))])) : x);
const identity = x => [x.attempt, x.result, x.source, x.field, x.modelVersion].join('\0');
const same = (a,b) => canonical(a) === canonical(b);

export function classifyBuilds(scope, builds) {
    const stamp = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{6}$/;
    if(!['source','field','modelVersion','contract','decision'].every(k=>typeof scope[k]==='string' && scope[k].length>0)
        || !Number.isSafeInteger(scope.epoch) || scope.epoch<1 || !Array.isArray(builds)) throw new Error('INVALID_SCOPE_METADATA');
    if(scope.attemptTime!=null && !stamp.test(scope.attemptTime)) throw new Error('UNSUPPORTED_TIMESTAMP_FORMAT');
    for(const b of builds) {
        if(!['source','field','model','contract','decision'].every(k=>typeof b[k]==='string' && b[k].length>0)
            || !Number.isSafeInteger(b.epoch) || b.epoch<1 || !Number.isSafeInteger(b.count) || b.count<1
            || !/^[a-f0-9]{64}$/.test(b.sha)) throw new Error('INVALID_BUILD_METADATA');
        if(!stamp.test(b.completed)) throw new Error('UNSUPPORTED_TIMESTAMP_FORMAT');
    }
    const fieldModels = builds.filter(b => b.source === scope.source && b.field === scope.field && b.model === scope.modelVersion);
    const exact = fieldModels.filter(b => b.contract === scope.contract && b.decision === scope.decision && b.epoch === scope.epoch);
    const timed = scope.attemptTime ? exact.filter(b => b.completed && b.completed <= scope.attemptTime) : [];
    const distinct = [...new Map(timed.map(b => [[b.sha,b.count].join('\0'), {sha:b.sha,count:b.count}])).values()];
    let classification = exact.length === 0 ? (fieldModels.length ? 'EXACT_ADMISSION_BUILD_OMISSION' : 'FIELD_MODEL_BUILD_EVIDENCE_MISSING')
        : !scope.attemptTime ? 'ATTEMPT_TIME_UNPROVEN'
        : distinct.length === 0 ? 'BUILD_COMPLETED_AFTER_ATTEMPT'
        : distinct.length > 1 ? 'AMBIGUOUS_BUILD_DIGEST_COUNT' : 'METADATA_CLOSURE_ONLY';
    return {classification, exactBuildCount:exact.length, acceptedDistinct:distinct,
        otherAdmissionBuilds:fieldModels.filter(b => !exact.includes(b)).map(b => ({contract:b.contract,decision:b.decision,epoch:b.epoch,completed:b.completed,sha:b.sha,count:b.count})),
        provesVectorBytes:false, provesAuthorization:false};
}

export function diagnose(root = ROOT, {readBytes = readFileSync} = {}) {
    const index=[];
    function read(path, json=false) {
        const bytes=readBytes(resolve(root,path));
        index.push({path,sha256:sha(bytes),bytes:bytes.length,handling:'OFFLINE_REFERENCE_ONLY_NO_BODY_COPY'});
        return json ? JSON.parse(bytes) : bytes.toString('utf8');
    }
    const anchorBytes=readBytes(resolve(root,HISTORICAL_ANCHOR));
    if(sha(anchorBytes)!==HISTORICAL_ANCHOR_SHA) throw new Error('IMMUTABLE_HISTORICAL_ANCHOR_SHA_MISMATCH');
    index.push({path:HISTORICAL_ANCHOR,sha256:HISTORICAL_ANCHOR_SHA,bytes:anchorBytes.length,handling:'OFFLINE_REFERENCE_ONLY_NO_BODY_COPY'});
    const anchor=JSON.parse(anchorBytes);
    const frozenIndex=new Map(anchor.originalReferenceIndex.map(x=>[x.path,x]));
    if(frozenIndex.size!==anchor.originalReferenceIndex.length) throw new Error('HISTORICAL_INDEX_AMBIGUOUS');
    const closure=read(`${PREFLIGHT}/field-build-closure-diagnosis.json`,true);
    const witnesses=read(`${PREFLIGHT}/historical-build-witnesses.json`,true);
    if(closure.writes!==0 || witnesses.writes!==0) throw new Error('INPUT_NOT_READ_ONLY');
    if(!same(witnesses.original,witnesses.restored) || !same(closure.original,closure.restored)) throw new Error('ORIGINAL_RESTORED_DIVERGENCE');
    if(closure.original.checked!==witnesses.original.checked || !same([...closure.original.issues].sort((a,b)=>identity(a).localeCompare(identity(b))), [...witnesses.original.issues].sort((a,b)=>identity(a).localeCompare(identity(b))))) throw new Error('INPUT_ISSUE_DIVERGENCE');
    const receipts=new Map();
    for(const version of ['701','705','731']) {
        const path=`${HISTORY}/patent-search-result-v${version}.json`, receipt=read(path,true);
        if(receipt.status!=='SUCCEEDED' || receipts.has(receipt.resultSetId)) throw new Error('INVALID_RESULT_RECEIPT');
        receipts.set(receipt.resultSetId,{path,receipt});
    }
    const decisions=new Map();
    for(const version of ['698','729']) {
        const path=`${HISTORY}/source-patentsview-decision-v${version}.json`, d=read(path,true);
        decisions.set(d.contractVersion,{path,decisionVersion:d.decisionVersion,sourceId:d.sourceId,
            rightsEvidenceStatus:d.rightsEvidenceStatus,validUntil:d.validUntil});
    }
    const paths=[`${JAVA}/ProductSemanticIndex.java`,`${JAVA}/ProductPatentSearchSourceBatch.java`,`${JAVA}/ProductSemanticVectors.java`,`${JAVA}/ProductPatentFieldRecoveryModels.java`,`${JAVA}/ProductSemanticIndexController.java`,
        'db/dm8/V067__product_search_semantic_index.sql','db/dm8/V070__product_semantic_vector_snapshot_binding.sql','db/dm8/V084__product_search_field_evidence.sql'];
    const code=new Map(paths.map(path=>[path,read(path)]));
    const matches=[];
    for(const version of ['695','734']) {
        const path=`${HISTORY}/model-v${version}-build-inputs.json`, manifest=read(path,true);
        for(const target of paths.slice(0,2)) {
            const entries=manifest.files.filter(f=>f.path===target);
            if(entries.length!==1) throw new Error('HISTORICAL_CODE_IDENTITY_MISSING');
            const historical=anchor.historicalCodeIdentity.filter(x=>x.manifest===path && x.path===target);
            const frozen=frozenIndex.get(target);
            if(historical.length!==1 || !frozen || entries[0].sha256!==frozen.sha256
                || historical[0].recordedSha256!==frozen.sha256 || historical[0].currentSha256!==frozen.sha256)
                throw new Error('HISTORICAL_CODE_WITNESS_MISMATCH');
            const currentSha256=sha(Buffer.from(code.get(target)));
            matches.push({manifest:path,capturedAt:manifest.capturedAt,baseline:manifest.baseline,path:target,
                recordedSha256:entries[0].sha256,historicalSha256:frozen.sha256,currentSha256,
                matchesHistoricalAnchor:true,currentCodeChanged:currentSha256!==frozen.sha256,
                matchesCurrent:entries[0].sha256===currentSha256,
                scope:'IMMUTABLE_HISTORICAL_SOURCE_IDENTITY_SEPARATE_FROM_CURRENT_NOT_DEPLOYED_BINARY_PROOF'});
        }
    }
    const anchors=[
        [`${JAVA}/ProductSemanticIndexController.java`,'index.buildField'],
        [`${JAVA}/ProductSemanticIndex.java`,'Receipt buildField('],
        [`${JAVA}/ProductSemanticIndex.java`,'void validateField('],
        [`${JAVA}/ProductSemanticIndex.java`,'INSERT INTO con4_vector_build'],
        [`${JAVA}/ProductPatentSearchSourceBatch.java`,'else index.validateField'],
        [`${JAVA}/ProductSemanticVectors.java`,'WHERE b.source_id=? AND b.source_version=?'],
        [`${JAVA}/ProductPatentFieldRecoveryModels.java`,'JOIN con4_search_source'],
        [`${JAVA}/ProductPatentFieldRecoveryModels.java`,'AND b.field_name=? AND b.model_version=?'],
        ['db/dm8/V067__product_search_semantic_index.sql','CREATE TABLE con4_vector_build'],
        ['db/dm8/V070__product_semantic_vector_snapshot_binding.sql','CREATE TABLE con4_document_vector_binding'],
        ['db/dm8/V084__product_search_field_evidence.sql','CREATE TABLE con4_search_field_scope']
    ].map(([path,needle])=>{const line=code.get(path).split(/\r?\n/).findIndex(l=>l.includes(needle))+1;if(!line)throw new Error(`SOURCE_ANCHOR_MISSING:${path}:${needle}`);return{path,line,needle};});
    // Historical metadata/manifests are immutable; current code files are not.
    for(const entry of index) if(entry.path.startsWith(PREFLIGHT+'/') || entry.path.startsWith(HISTORY+'/')) {
        const frozen=frozenIndex.get(entry.path);
        if(!frozen || frozen.sha256!==entry.sha256 || frozen.bytes!==entry.bytes) throw new Error('HISTORICAL_INPUT_SHA_MISMATCH');
    }
    const semantic=code.get(JAVA+'/ProductSemanticIndex.java');
    const guardChecks={
        validationCall:semantic.includes('requireBuild(current,field,version,material,vectors);'),
        searchCall:semantic.includes('requireBuild(active,field,modelVersion,documents,vectors);'),
        exactTupleQuery:semantic.includes('WHERE source_id=? AND contract_version=? AND decision_id=? AND source_epoch=? ')
            && semantic.includes('AND field_name=? AND model_version=? AND completed_at<=?'),
        exactTupleBindings:semantic.includes('active.contractVersion(),active.decisionId(),active.epoch(),field,version,'),
        distinctCardinalityAndCount:semantic.includes('SELECT DISTINCT build_sha,document_count')
            && semantic.includes('builds.size()!=1') && semantic.includes('builds.getFirst().count()!=documents.size()'),
        digestRecomputed:semantic.includes('ProductPatentSearchEvidenceCodec.digestValues(documents.stream()')
            && semantic.includes('ProductVectorCodec.sha(ProductVectorCodec.encode(')
            && semantic.includes('if (!sha.equals(builds.getFirst().sha()))')
    };
    const currentExactBuildGuardImplemented=Object.values(guardChecks).every(Boolean);
    const issues=witnesses.original.issues;
    if(issues.length!==6 || new Set(issues.map(identity)).size!==6 || new Set(issues.map(x=>x.result)).size!==3) throw new Error('EXPECTED_THREE_RESULTS_SIX_COMBINATIONS');
    const combinations=issues.map(issue=>{
        const entry=receipts.get(issue.result);
        if(!entry || entry.receipt.attemptId!==issue.attempt) throw new Error('RESULT_ATTEMPT_MISMATCH');
        const sources=entry.receipt.sourceOutcomes.filter(s=>s.sourceId===issue.source && s.status==='SUCCEEDED');
        if(sources.length!==1 || !['TITLE','CLAIMS'].includes(issue.field)) throw new Error('RESULT_SOURCE_FIELD_MISMATCH');
        const s=sources[0], d=decisions.get(s.contractVersion);
        if(!d || d.sourceId!==s.sourceId) throw new Error('DECISION_CONTRACT_MISMATCH');
        const scope={...issue,contract:s.contractVersion,decision:s.decisionId,epoch:s.sourceEpoch};
        const verdict=classifyBuilds(scope,witnesses.original.historicalBuilds);
        if(!same(issue.allMatchingBuilds,[]) || !same(issue.acceptedBuilds,[]) || verdict.exactBuildCount!==0) throw new Error('CLOSURE_QUERY_CONTRADICTION');
        return {result:issue.result,attempt:issue.attempt,source:issue.source,field:issue.field,modelVersion:issue.modelVersion,
            contract:scope.contract,decision:scope.decision,epoch:scope.epoch,receiptPath:entry.path,decisionReference:d,
            ...verdict,attemptTime:null,vectorDigestRecomputed:false};
    }).sort((a,b)=>identity(a).localeCompare(identity(b)));
    for(const result of receipts.keys()) if(combinations.filter(x=>x.result===result).map(x=>x.field).sort().join(',')!=='CLAIMS,TITLE') throw new Error('INCOMPLETE_FIELD_PAIR');
    for(const entry of index) if(sha(readBytes(resolve(root,entry.path)))!==entry.sha256) throw new Error('INPUT_CHANGED_DURING_DIAGNOSIS');
    return {taskId:'FIX-CON-HISTORICAL-BUILD-DIAGNOSIS-01',inheritedScope:'VER-AGENT-SINGLE-DB-PERSISTENT-01',
        purpose:'REPRODUCIBLE_OFFLINE_HISTORICAL_BUILD_METADATA_DIAGNOSIS',writesToBusiness:0,
        originalEqualsRestored:true,checkedByOriginalQuery:closure.original.checked,combinationCount:combinations.length,
        combinations,sourceAnchors:anchors,historicalCodeIdentity:matches,originalReferenceIndex:index,
        historicalAnchor:{path:HISTORICAL_ANCHOR,sha256:HISTORICAL_ANCHOR_SHA},
        currentCodeChanged:matches.some(x=>x.currentCodeChanged),
        currentCodeAssessment:{currentExactBuildGuardImplemented,guardChecks,
            conclusion:currentExactBuildGuardImplemented?'CURRENT_EXACT_BUILD_GUARD_PRESENT_IN_SOURCE':'CURRENT_EXACT_BUILD_GUARD_NOT_ESTABLISHED',
            evidenceScope:'CURRENT_SOURCE_STRUCTURE_ONLY_NOT_PRODUCT_TEST_DEPLOYMENT_OR_HISTORICAL_REPAIR'},
        findings:{observed:'Six exact admission/field/model build receipts are absent in both captured original and restored metadata; historical matching field/model builds exist only under earlier admission tuples.',
            compatibilityDefect:'HISTORICAL_WRITE_READ_CONTRACT_GAP_SOURCE_IDENTITY_SUPPORTED',
            mechanism:'Historical source identity recorded by the immutable prior diagnostic and both manifests supports the historical gap: buildField persists the admission tuple even on vector reuse; historical search validated source-version/vector binding without requiring an admission build receipt; recovery demands contract+decision+epoch+field+model and completion before attempt. Current guard is assessed separately; its presence does not backfill old receipts.',
            omissionBoundary:'Missing admission receipts are proven within supplied observations, not proven never written/deleted elsewhere. Equal older build digests do not authorize reuse or backfill.',
            migrationBoundary:'V067 defines admission-bound builds, V070 defines source-version vector bindings, V084 defines field-result scope. Migration application history/version and deployed JAR SHA are not supplied.'},
        unresolved:['Exact attempt timestamps and immutable vector bytes/digest recomputation are unavailable in allowed inputs.',
            'Deployed binary identity, applied migration history and actual historical build request/response logs are not proven by source manifests.',
            'Why exact build receipts were omitted (no request, failed request, later deletion, other writer) remains unproven.',
            'No current authorization, source rights, professional acceptance or recovery repair is established.'],
        handoff:{soleBusinessEditor:'MAIN_SESSION',files:[`${JAVA}/ProductSemanticIndex.java`,`${JAVA}/ProductPatentSearchSourceBatch.java`,`${JAVA}/ProductSemanticVectors.java`,`${JAVA}/ProductPatentFieldRecoveryModels.java`],
            recommendation:currentExactBuildGuardImplemented?'Current exact admission build guard is present in source; main session retains ownership of product verification/deployment. Historical missing receipts remain unresolved. Do not loosen recovery tuple/time checks or synthesize old receipts.':'Current guard is not established by this source check; main session owns any product fix. Do not loosen recovery tuple/time checks, synthesize old build receipts or treat earlier epochs as authority.'}};
}

export function writeReport(report, output, root=ROOT) {
    const base=realpathSync(resolve(root,OUT)), target=resolve(output), rel=relative(base,target);
    if(!rel || rel.startsWith('..') || isAbsolute(rel) || dirname(target)!==base || !target.endsWith('.json')) throw new Error('OUTPUT_OUTSIDE_ALLOWED_EVIDENCE');
    if(existsSync(target)) throw new Error('REFUSE_OVERWRITE_ORIGINAL');
    writeFileSync(target,JSON.stringify(report,null,2)+'\n',{flag:'wx'});
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
    try {
        if(process.argv.length!==2 && !(process.argv.length===4 && process.argv[2]==='--output')) throw new Error('USAGE: node scripts/diagnose-consultation-historical-build.mjs [--output NEW_JSON_IN_ALLOWED_EVIDENCE]');
        const report=diagnose();
        if(process.argv.length===4) writeReport(report,process.argv[3]);
        else process.stdout.write(JSON.stringify(report,null,2)+'\n');
    } catch(error) { console.error(error.message); process.exitCode=1; }
}

