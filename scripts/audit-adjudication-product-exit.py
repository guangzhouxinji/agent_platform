"""Independent exit evidence audit. No product state or master-plan status is written."""
import argparse, hashlib, json, xml.etree.ElementTree as ET
from pathlib import Path
EXPECTED={'CaseWorkConcurrencyTest':1,'CaseWorkDm8ApiIT':1,'CaseWorkHttpsLifecycleTest':1,'CaseWorkHttpsNegativesTest':14,'CaseWorkProductContractTest':3,'CaseWorkRecoveryFailuresTest':2,'CaseWorkspaceBrowserIT':1}
def audit(root, require_report_editor=False):
    load=lambda name:json.loads((root/name).read_text(encoding='utf-8-sig'))
    verification=load('verification.json'); browser=load('browser-result.json'); restart=load('service-restarted.json'); http=load('http-records.json')
    assert verification['passed'] and verification['isolatedSchemasRemoved'] and verification['publicLawProvenancePersisted']
    for field in ['noNetworkMocks','realPkceLogin','serviceRestartRecoveredSameCase','freshPkceAfterRestart','authorAndIndependentReviewer','normalLifecycle','returnResubmit','materialAndClaimHistory','conflictAndStaleRefusal','missingOpposingUncertainVisible','historicalReadOnly','sourceRevokedClearsContent','mobileNoPageOverflow']:
        assert browser[field] is True, field
    assert not browser['pageErrors'] and browser['httpCount']==len(http)
    assert restart['actualSpringServiceRestart'] and restart['caseBytesUnchanged']
    assert restart==verification['sameCaseServiceRestart']
    assert verification['caseId']==restart['caseId'] and verification['headVersion']==restart['headVersion']==22
    for table in ['adj2_case_revision','adj2_case_audit','adj2_case_receipt']:
        assert verification[table+'Rows']==22
    for table,rows in restart['persistedDigests'].items():
        assert rows
        for row in rows:
            parts=row.split(':');assert len(parts[-1])==64
            if table=='adj2_case_work': assert parts[:2]==[verification['caseId'],str(verification['headVersion'])]
            else: assert parts[-1]==parts[-2]
    required={'REPORT_INCOMPLETE','INDEPENDENT_REVIEW_REQUIRED','REPORT_NOT_DRAFT','REPORT_NOT_SUBMITTED','WORK_VERSION_CONFLICT','REPORT_INPUT_OR_HEAD_STALE','CASE_ACCESS_DENIED'}
    observed={x.get('body',{}).get('code') for x in http if x['status']>=400}
    assert required <= observed, required-observed
    if require_report_editor:
        for field in ['editorOriginalPrefilled','editorChapterDraftsPreserved','editorNavigationCancelPreserves','editorBackendRejectionPreserves','editorRevocationPurgesDraft']:
            assert browser[field] is True, field
        assert any(x['method']=='POST' and x['path'].endswith('/reports/4/revisions') and x['status']==409 for x in http)
        assert any(x['method']=='POST' and x['path'].endswith('/reports/5/revisions') and x['status']==403 for x in http)
        for name in ['report-editor-draft.png','report-editor-rejection.png']: assert (root/name).stat().st_size>1000
    suites=[]
    for name,count in EXPECTED.items():
        path=root/('TEST-cn.xinji.agentplatform.adjudication.casework.'+name+'.xml')
        suite=ET.parse(path).getroot()
        assert int(suite.attrib['tests'])==count and all(int(suite.attrib[x])==0 for x in ['errors','failures','skipped']),name
        suites.append({'name':name,'tests':count,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'methods':[x.attrib['name'] for x in suite.findall('testcase')]})
    screenshots=['original-citation','feature-correspondence','returned-report','version-conflict','source-withdrawal','recovered-reviewed-history','recovered-current-history','mobile-history']
    for name in screenshots:assert (root/(name+'.png')).stat().st_size>1000
    return {'auditNature':'SEPARATE_READ_ONLY_ENGINEERING_EXIT_EVIDENCE_REVIEW_NOT_PROFESSIONAL_ACCEPTANCE','passed':True,'tests':sum(EXPECTED.values()),'httpResponses':len(http),'caseHead':22,'suites':suites,'artifactSha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.iterdir()) if p.is_file() and p.suffix in ['.json','.png']}}
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('evidence',type=Path);parser.add_argument('--require-report-editor',action='store_true');args=parser.parse_args()
    print(json.dumps(audit(args.evidence,args.require_report_editor),ensure_ascii=False,indent=2))

