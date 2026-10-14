from pathlib import Path
import hashlib,json
E=Path(__file__).resolve().parent;R=E.parents[2];checks=[]
def ck(n,b):
 checks.append({'name':n,'passed':bool(b)})
 if not b:raise AssertionError(n)
def sha(b):return hashlib.sha256(b).hexdigest()
inv=json.loads((E/'material-inventory.json').read_text(encoding='utf-8'))
old=R/'data/adjudication/task-first-loop-input-v1'
ck('01 manifest not modified',sha((old/'manifest.json').read_bytes())==inv['existing01Package']['manifestSha256'])
ck('01 records digest unchanged and recorded',sha((old/'records.json').read_bytes())==inv['existing01Package']['recordsSha256']==inv['existing01Package']['recordedRecordsSha256'])
ck('01 MODEL prohibition unchanged','EXTERNAL_MODEL_USE' in json.loads((old/'manifest.json').read_text(encoding='utf-8'))['licenseAndPurpose']['notAllowed'])
for x in inv['existing01Package']['materials']:ck(x['side']+' 01 exact source SHA',x['contentSha256']==x['sourceSha256'])
p=E/'proposed-material-package';m=json.loads((p/'manifest.json').read_text(encoding='utf-8'))
ck('one material per party',len(m['files'])==2 and {x['providerSide'] for x in m['files']}=={'APPLICANT','RESPONDENT'})
ck('MODEL authorization pending',m['externalModelUse']=='NOT_APPROVED_DO_NOT_SEND' and m['approvalEvidence'] is None)
ck('no runtime source or grant',m['runtimeSourceAdmission']=='NOT_REGISTERED' and m['identityGrant']=='NOT_ISSUED')
ck('substitute not case/gold',not m['realCase'] and not m['humanGoldBaselineEstablished'])
ck('offline purpose only',m['permittedCurrentPurpose']==['OFFLINE_PREPARATION_REVIEW'])
for x in m['files']:
 b=(p/x['path']).read_bytes();t=b.decode('utf-8')
 ck(x['path']+' SHA/size',sha(b)==x['sha256'] and len(b)==x['bytes'])
 ck(x['path']+' no MODEL approval',not x['modelUseApproved'])
 q=t.splitlines()[1];a=len(t[:t.index(q)].encode('utf-16-le'))//2;z=a+len(q.encode('utf-16-le'))//2
 ck(x['path']+' UTF16 excerpt roundtrip',t.encode('utf-16-le')[2*a:2*z].decode('utf-16-le')==q)
p=E/'public-provider-review';m=json.loads((p/'manifest.json').read_text(encoding='utf-8'))
ck('public documents not model call',m['noModelCall'] and m['purpose']=='PUBLIC_PROVIDER_DOCUMENT_REVIEW_ONLY')
for x in m['sources']:
 b=(p/(x['id']+'.html')).read_bytes();t=(p/(x['id']+'.txt')).read_bytes()
 ck(x['id']+' HTTP/raw SHA/size',x['status']==200 and sha(b)==x['artifactSha256'] and len(b)==x['artifactBytes'])
 ck(x['id']+' actual text SHA/size',sha(t)==x['textSha256'] and len(t)==x['textBytes'])
 ck(x['id']+' LF text correction explained',sha(t.decode('utf-8').replace('\r\n','\n').encode())==x['logicalLfTextSha256'])
ck('Flash conservative price arithmetic',round(2*100000/1000000+8*20000/1000000,2)==0.36)
ck('Pro conservative price arithmetic',round(9*100000/1000000+27*20000/1000000,2)==1.44)
out={'scope':'OFFLINE_PREPARATION_ONLY_NOT_PRODUCT_OR_REGRESSION_TEST','date':'2026-10-03','checks':checks,'passed':len(checks),'failed':0,'modelCalled':False,'businessCodeImplemented':False,'manualValueValidation':False}
(E/'preparation-integrity.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8');print('Offline preparation integrity:',len(checks),'passed; model/product/regression NOT validated')
