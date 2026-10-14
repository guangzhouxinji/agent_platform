from pathlib import Path
import zipfile,json,hashlib,datetime,subprocess,shutil
r=Path.cwd();e=r/'docs/evidence/consultation-human-ordinary-20261013/candidate01'
b=json.loads((e/'inputs-before.json').read_text());stability=json.loads((e/'inputs-stability.json').read_text());assert not stability['changed']
j=r/'backend/apps/platform-api/target/agent-platform-consultation-human-request-20261013-01.jar'
with zipfile.ZipFile(j) as z:
 names=z.namelist();assert len(names)==len(set(names))
 m=z.read('META-INF/MANIFEST.MF').decode();assert 'JarLauncher' in m;assert 'Start-Class: cn.xinji.agentplatform.AgentPlatformApplication' in m
 (e/'MANIFEST.MF').write_text(m)
 base='BOOT-INF/classes/static/app/'; assets=json.loads(z.read(base+'consultation-product-assets.json'));app=[n[len(base):] for n in names if n.startswith(base) and not n.endswith('/')]
 assert sorted(app)==sorted([x['path'] for x in assets['files']]+['consultation-product-assets.json'])
 assert [x for x in app if x.endswith('.html')]==['consultation.html']
 for x in assets['files']:assert hashlib.sha256(z.read(base+x['path'])).hexdigest()==x['sha256']
 assert not any(n.startswith('BOOT-INF/classes/db/') for n in names)
 classes=['ProductHumanRequestApplication','ProductHumanRequestController','ProductHumanRequestConfiguration','ProductHumanTaskCreation','ProductHumanRequestStore','ProductHumanCleanupScope','ProductHumanWaitingRecoveryReferences','ProductMaintenanceProof','ProductIdentityController','ProductSubjects']
 out=e/'bytecode'; rows=[]
 for c in classes+['AgentPlatformApplication']:
  package='cn/xinji/agentplatform/'+('' if c=='AgentPlatformApplication' else 'security/product/')
  rel=package+c+'.class';raw=z.read('BOOT-INF/classes/'+rel);p=out/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(raw)
  v=subprocess.run([r'D:\software\jdk-21\bin\javap.exe','-p','-v','-classpath',str(out),(package+c).replace('/','.')],capture_output=True);assert v.returncode==0
  (e/(c+'.javap.raw')).write_bytes(v.stdout)
  v.stdout=v.stdout.decode('gb18030')
  if c=='AgentPlatformApplication':assert '--product-consultation' in v.stdout and 'ProductConsultationApplication' in v.stdout
  if c=='ProductIdentityController':assert 'recoverActivation' in v.stdout
  if c=='ProductHumanRequestController':assert 'PostMapping' in v.stdout and 'human-requests' in v.stdout
  if c=='ProductMaintenanceProof':assert 'withRetentionProof' in v.stdout
  (e/(c+'.javap.txt')).write_text(v.stdout,encoding='utf-8');rows.append({'class':c,'sha256':hashlib.sha256(raw).hexdigest()})
 (e/'assets.json').write_text(json.dumps(assets,indent=2))
side=[]
for path in ['db/dm8/V107__product_activation_recovery.sql','db/dm8/U107__product_activation_recovery.sql','db/dm8/V109__product_human_request.sql','db/dm8/V110__product_human_cleanup_scope.sql','db/dm8/V111__product_human_body_access_deadline.sql','db/identity-authority/V006__consultation_human_cleanup_scope.sql','deployment/single-database/installation-profile.json','deployment/single-database/application-capabilities.json']:
 src=r/path;dst=e/'sidecars'/path;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dst);side.append({'source':path,'sha256':hashlib.sha256(dst.read_bytes()).hexdigest()})
receipt={'taskId':'REL-CON-START-SUCCESSOR-BUNDLE-01','kind':'OFFLINE_CANDIDATE_NOT_DEPLOYMENT','at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'jar':{'path':str(j),'bytes':j.stat().st_size,'sha256':hashlib.sha256(j.read_bytes()).hexdigest()},'inputFilesChecked':len(b['files']),'inputStability':stability,'bootManifest':True,'consultationAssetsExact':True,'classesJavapVerified':rows,'sqlAutoInstallation':False,'sidecars':side,'testsExecutedByBuild':False,'deployed':False,'realFlowVerified':False,'businessAccepted':False}
(e/'candidate-receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8');print(json.dumps({'jar':receipt['jar'],'inputs':len(b['files']),'classes':len(rows),'assets':len(assets['files'])}))
