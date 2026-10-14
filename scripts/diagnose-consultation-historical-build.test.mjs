import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { diagnose, classifyBuilds, writeReport } from './diagnose-consultation-historical-build.mjs';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const scope={source:'source',field:'TITLE',modelVersion:'model@revision/L2',contract:'contract',decision:'decision',epoch:19,attemptTime:'2026-10-03 21:00:00.000000'};
const build={source:'source',field:'TITLE',model:'model@revision/L2',contract:'contract',decision:'decision',epoch:19,completed:'2026-10-03 20:00:00.000000',sha:'a'.repeat(64),count:40};
const classify=(change={},rows=[build])=>classifyBuilds({...scope,...change},rows);

test('real allowed inputs reproduce three result x TITLE/CLAIMS, without business writes',()=>{
    const report=diagnose(root);
    assert.equal(report.combinationCount,6);
    assert.equal(report.checkedByOriginalQuery,26);
    assert.equal(report.originalEqualsRestored,true);
    assert.equal(report.writesToBusiness,0);
    assert.deepEqual(report.combinations.map(x=>x.epoch).sort(),[19,19,19,19,21,21]);
    for(const row of report.combinations) {
        assert.equal(row.classification,'EXACT_ADMISSION_BUILD_OMISSION');
        assert.equal(row.exactBuildCount,0);
        assert.deepEqual(row.acceptedDistinct,[]);
        assert.equal(row.vectorDigestRecomputed,false);
        assert.equal(row.attemptTime,null);
        assert.ok(row.otherAdmissionBuilds.length>0);
        assert.ok(row.otherAdmissionBuilds.every(x=>x.epoch<=17));
    }
});
test('repeated offline diagnosis is deterministic with unchanged input hashes',()=>assert.deepEqual(diagnose(root),diagnose(root)));
test('historical source identity is bound to immutable prior index and both manifests, not current source SHA',()=>{
    const r=diagnose(root);
    assert.equal(r.historicalCodeIdentity.length,4);
    assert.ok(r.historicalCodeIdentity.every(x=>x.matchesHistoricalAnchor));
    assert.ok(r.historicalCodeIdentity.every(x=>x.historicalSha256===x.recordedSha256));
    assert.ok(r.historicalCodeIdentity.every(x=>x.currentCodeChanged===(x.currentSha256!==x.historicalSha256)));
    assert.equal(r.findings.compatibilityDefect,'HISTORICAL_WRITE_READ_CONTRACT_GAP_SOURCE_IDENTITY_SUPPORTED');
    assert.ok(r.originalReferenceIndex.every(x=>x.handling==='OFFLINE_REFERENCE_ONLY_NO_BODY_COPY'));
    assert.ok(r.originalReferenceIndex.every(x=>!/(goal|protect|credential)/i.test(x.path)));
});
test('complete exact metadata is not claimed as vector/authorization verification',()=>{
    const r=classify(); assert.equal(r.classification,'METADATA_CLOSURE_ONLY');
    assert.equal(r.provesVectorBytes,false); assert.equal(r.provesAuthorization,false);
});
for(const [name,patch] of [ ['epoch',{epoch:17}], ['contract',{contract:'previous-contract'}], ['decision',{decision:'previous-decision'}] ]) {
    test(`counterexample: same field/model/digest but different ${name} cannot close admission`,()=>{
        const r=classify({},[{...build,...patch}]);
        assert.equal(r.classification,'EXACT_ADMISSION_BUILD_OMISSION');
        assert.equal(r.exactBuildCount,0);assert.deepEqual(r.acceptedDistinct,[]);
    });
}
for(const [name,patch] of [ ['source',{source:'other-source'}], ['field',{field:'CLAIMS'}], ['model',{model:'other-revision'}] ]) {
    test(`counterexample: different ${name} is missing evidence, not compatible reuse`,()=>{
        assert.equal(classify({},[{...build,...patch}]).classification,'FIELD_MODEL_BUILD_EVIDENCE_MISSING');
    });
}
test('counterexample: exact receipt created after attempt is not historical proof',()=>{
    assert.equal(classify({},[{...build,completed:'2026-10-03 22:00:00.000000'}]).classification,'BUILD_COMPLETED_AFTER_ATTEMPT');
});
test('SQL completion boundary is inclusive',()=>assert.equal(classify({},[{...build,completed:scope.attemptTime}]).classification,'METADATA_CLOSURE_ONLY'));
test('counterexample: absent attempt time cannot prove temporal closure',()=>assert.equal(classify({attemptTime:null}).classification,'ATTEMPT_TIME_UNPROVEN'));
test('counterexample: distinct build digests remain ambiguous',()=>assert.equal(classify({},[build,{...build,sha:'b'.repeat(64)}]).classification,'AMBIGUOUS_BUILD_DIGEST_COUNT'));
test('counterexample: same digest but distinct counts remain ambiguous',()=>assert.equal(classify({},[build,{...build,count:20}]).classification,'AMBIGUOUS_BUILD_DIGEST_COUNT'));
test('SQL SELECT DISTINCT digest/count semantics permit duplicated identical receipts',()=>assert.equal(classify({},[build,{...build}]).acceptedDistinct.length,1));
test('counterexample: an old admission receipt alongside an exact receipt is not selected',()=>{
    const r=classify({},[build,{...build,epoch:17,sha:'b'.repeat(64)}]);
    assert.deepEqual(r.acceptedDistinct,[{sha:build.sha,count:40}]);assert.equal(r.otherAdmissionBuilds.length,1);
});
test('counterexample: an after-attempt conflicting receipt does not contaminate historical DISTINCT selection',()=>assert.equal(classify({},[build,{...build,completed:'2026-10-03 22:00:00.000000',sha:'b'.repeat(64)}]).classification,'METADATA_CLOSURE_ONLY'));
test('output cannot overwrite original input or escape the authorized evidence directory',()=>{
    for(const p of ['docs/evidence/consultation-approved-release-20261007/preflight/historical-build-witnesses.json','scripts/forbidden.json','docs/evidence/consultation-historical-build-20261007/../forbidden.json','docs/evidence/consultation-historical-build-20261007/subdir/forbidden.json'])
        assert.throws(()=>writeReport({},resolve(root,p),root),/OUTPUT_OUTSIDE_ALLOWED_EVIDENCE/);
});
test('existing machine diagnosis is not overwritten',()=>{
    const p=resolve(root,'docs/evidence/consultation-historical-build-20261007/diagnosis.json');
    const before=readFileSync(p);
    assert.throws(()=>writeReport({},p,root),/REFUSE_OVERWRITE_ORIGINAL/);
    assert.deepEqual(readFileSync(p),before);
});

test('counterexample: missing admission tuple is invalid, never wildcard matching',()=>{
    assert.throws(()=>classify({decision:undefined}),/INVALID_SCOPE_METADATA/);
    assert.throws(()=>classify({epoch:undefined}),/INVALID_SCOPE_METADATA/);
});
test('counterexample: timestamp format mismatch cannot silently use lexicographic comparison',()=>{
    assert.throws(()=>classify({attemptTime:'2026-10-03T21:00:00Z'}),/UNSUPPORTED_TIMESTAMP_FORMAT/);
    assert.throws(()=>classify({},[{...build,completed:null}]),/UNSUPPORTED_TIMESTAMP_FORMAT/);
});
test('counterexample: missing digest or zero document count cannot become build proof',()=>{
    assert.throws(()=>classify({},[{...build,sha:null}]),/INVALID_BUILD_METADATA/);
    assert.throws(()=>classify({},[{...build,count:0}]),/INVALID_BUILD_METADATA/);
});
function alteredReader(suffix, change) {
    return path=>{
        const bytes=readFileSync(path);
        if(!path.replaceAll('\\','/').endsWith(suffix)) return bytes;
        const object=JSON.parse(bytes);change(object);return Buffer.from(JSON.stringify(object));
    };
}
test('counterexample: original/restored divergence fails closed using memory-only mutation',()=>{
    assert.throws(()=>diagnose(root,{readBytes:alteredReader('historical-build-witnesses.json',x=>x.restored.historicalBuilds.pop())}),/ORIGINAL_RESTORED_DIVERGENCE/);
});
test('counterexample: differing closure and witness observations fail closed',()=>{
    assert.throws(()=>diagnose(root,{readBytes:alteredReader('field-build-closure-diagnosis.json',x=>{x.original.checked=27;x.restored.checked=27;})}),/INPUT_ISSUE_DIVERGENCE/);
});
test('counterexample: wrong result-attempt association fails closed',()=>{
    assert.throws(()=>diagnose(root,{readBytes:alteredReader('patent-search-result-v701.json',x=>x.attemptId='wrong-attempt')}),/HISTORICAL_INPUT_SHA_MISMATCH/);
});
test('counterexample: an invented exact admission build contradicts original no-match observations',()=>{
    assert.throws(()=>diagnose(root,{readBytes:alteredReader('historical-build-witnesses.json',x=>{
        for(const k of ['original','restored']) {
            const row=x[k].historicalBuilds.find(b=>b.source==='PATENTSVIEW_ZENODO_2024' && b.field==='TITLE');
            x[k].historicalBuilds.push({...row,contract:'patentsview-capture-contract-698',decision:'115a6760-90a8-4453-94e7-382d36e3c048',epoch:19});
        }
    })}),/HISTORICAL_INPUT_SHA_MISMATCH/);
});
test('counterexample: changed input during diagnosis fails final fingerprint guard',()=>{
    const counts=new Map();
    assert.throws(()=>diagnose(root,{readBytes:path=>{
        const n=(counts.get(path)||0)+1;counts.set(path,n);const bytes=readFileSync(path);
        return n===2 && path.endsWith('historical-build-witnesses.json') ? Buffer.concat([bytes,Buffer.from(' ')]) : bytes;
    }}),/INPUT_CHANGED_DURING_DIAGNOSIS/);
});

test('historical manifests cannot be rewritten to pretend a new current source was historical',()=>{
    assert.throws(()=>diagnose(root,{readBytes:alteredReader('model-v695-build-inputs.json',x=>{
        x.files.find(f=>f.path.endsWith('/ProductSemanticIndex.java')).sha256='b'.repeat(64);
    })}),/HISTORICAL_CODE_WITNESS_MISMATCH/);
});
test('immutable prior diagnosis cannot be rewritten along with manifest witnesses',()=>{
    assert.throws(()=>diagnose(root,{readBytes:alteredReader('diagnosis-validated.json',x=>{
        x.historicalCodeIdentity[0].recordedSha256='b'.repeat(64);
    })}),/IMMUTABLE_HISTORICAL_ANCHOR_SHA_MISMATCH/);
});
test('consistent tampering of original and restored historical witnesses still fails integrity pins',()=>{
    assert.throws(()=>diagnose(root,{readBytes:alteredReader('historical-build-witnesses.json',x=>{
        for(const k of ['original','restored']) x[k].historicalBuilds[0].sha='b'.repeat(64);
    })}),/HISTORICAL_INPUT_SHA_MISMATCH/);
});
test('current source evolution does not invalidate historical source identity',()=>{
    const report=diagnose(root,{readBytes:path=>{
        const bytes=readFileSync(path);
        return path.endsWith('ProductSemanticIndex.java') ? Buffer.concat([bytes,Buffer.from('\n// current source evolution, not historical source\n')]) : bytes;
    }});
    assert.equal(report.currentCodeChanged,true);
    assert.ok(report.historicalCodeIdentity.every(x=>x.matchesHistoricalAnchor));
    assert.ok(report.historicalCodeIdentity.filter(x=>x.path.endsWith('/ProductSemanticIndex.java')).every(x=>!x.matchesCurrent && x.currentCodeChanged));
    assert.ok(report.combinations.every(x=>x.classification==='EXACT_ADMISSION_BUILD_OMISSION'));
});
test('current fixed source is separated from unchanged historical missing receipts',()=>{
    const report=diagnose(root);
    assert.equal(report.currentCodeChanged,true);
    assert.equal(report.currentCodeAssessment.currentExactBuildGuardImplemented,true);
    assert.equal(report.currentCodeAssessment.conclusion,'CURRENT_EXACT_BUILD_GUARD_PRESENT_IN_SOURCE');
    assert.equal(report.findings.compatibilityDefect,'HISTORICAL_WRITE_READ_CONTRACT_GAP_SOURCE_IDENTITY_SUPPORTED');
    assert.ok(report.combinations.every(x=>x.exactBuildCount===0));
});
test('counterexample: removal of current validation guard is not reported as current fixed code',()=>{
    const report=diagnose(root,{readBytes:path=>{
        const bytes=readFileSync(path);
        return path.endsWith('ProductSemanticIndex.java') ? Buffer.from(bytes.toString('utf8').replace('requireBuild(current,field,version,material,vectors);','/* removed validation guard */')) : bytes;
    }});
    assert.equal(report.currentCodeAssessment.currentExactBuildGuardImplemented,false);
    assert.equal(report.currentCodeAssessment.conclusion,'CURRENT_EXACT_BUILD_GUARD_NOT_ESTABLISHED');
    assert.ok(report.historicalCodeIdentity.every(x=>x.matchesHistoricalAnchor));
});

