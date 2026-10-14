from pathlib import Path
import hashlib,json,zipfile,subprocess,datetime,shutil
r=Path(__file__).resolve().parents[4]
e=Path(__file__).resolve().parent
b=json.loads((e/'build-inputs65.json').read_text(encoding='utf-8'))
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for c in iter(lambda:f.read(1024*1024),b''):h.update(c)
 return h.hexdigest()
for category in ['oldTargetJars','inputs','backendMainSources','componentSources']:
 for x in b[category]: assert sha(Path(x['path']))==x['sha256'],(category,x['path'])
jar=r/'backend/apps/platform-api/target'/f"{b['finalName']}.jar"
assert jar.exists()
classes=e/'inspected-classes';classes.mkdir(exist_ok=False)
checks={};raw=[]
with zipfile.ZipFile(jar) as z:
 names=z.namelist(); assert len(names)==len(set(names))
 manifest=z.read('META-INF/MANIFEST.MF').decode();(e/'MANIFEST.MF').write_text(manifest,encoding='utf-8')
 assert 'Main-Class: org.springframework.boot.loader.launch.JarLauncher' in manifest
 assert 'Start-Class: cn.xinji.agentplatform.PlatformApiApplication' in manifest
 checks['springBootManifest']=True
 base='BOOT-INF/classes/static/app/'
 app=[n for n in names if n.startswith(base) and not n.endswith('/')]
 html=[n for n in app if n.endswith('.html')]
 assert html==[base+'consultation.html'],html
 am=json.loads(z.read(base+'consultation-product-assets.json'))
 for f in am['files']:assert hashlib.sha256(z.read(base+f['path'])).hexdigest()==f['sha256']
 assert sorted(n[len(base):] for n in app)==sorted([f['path'] for f in am['files']]+['consultation-product-assets.json'])
 (e/'consultation-product-assets.json').write_text(json.dumps(am,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 checks['consultationOnlyAssetsBoundToManifest']=True
 assert not any(n.startswith('BOOT-INF/classes/db/') for n in names),'unexpected bundled DB migrations'
 checks['sqlSidecarsNotAutoInstallation']=True
 prefix='BOOT-INF/classes/cn/xinji/agentplatform/security/product/'
 for n in names:
  if n.startswith(prefix) and n.endswith('.class') and n[len(prefix):].startswith(('ProductSubjects','ProductIdentityController')):
   rel=n[len('BOOT-INF/classes/'):]; dest=classes/rel; dest.parent.mkdir(parents=True,exist_ok=True); dest.write_bytes(z.read(n)); raw.append({'path':n,'sha256':sha(dest)})
 for cn in ['ProductSubjects','ProductIdentityController']:
  fq='cn.xinji.agentplatform.security.product.'+cn
  result=subprocess.run([r'D:\software\jdk-21\bin\javap.exe','-v','-p','-classpath',str(classes),fq],capture_output=True,text=True,encoding='utf-8')
  (e/(cn+'.javap.txt')).write_text(result.stdout+result.stderr,encoding='utf-8');assert result.returncode==0
  if cn=='ProductIdentityController':
   assert 'recoverActivation(' in result.stdout
   assert 'org.springframework.web.bind.annotation.PostMapping(' in result.stdout
   assert 'value=["/subjects/{id}/activation-recovery"]' in result.stdout
   assert 'value=["/v2/identity"]' in result.stdout
  else:
   assert 'recoverActivation(' in result.stdout
   assert 'id2_activation_recovery_receipt' in result.stdout and 'REISSUE' in result.stdout
 checks['realBytecodeRecoveryMethodsAndPostMapping']=True
sidecars=[]
for n in ['V107__product_activation_recovery.sql','U107__product_activation_recovery.sql']:
 src=r/'db/dm8'/n;dst=e/n;assert not dst.exists();shutil.copyfile(src,dst);assert sha(src)==sha(dst);sidecars.append({'path':str(dst),'sha256':sha(dst)})
fixed=subprocess.run(['docker','exec',b['fixed25']['id'],'sha256sum',b['fixed25']['path']],capture_output=True,text=True)
(e/'fixed25-after-build.sha256.txt').write_text(fixed.stdout+fixed.stderr,encoding='utf-8')
assert fixed.returncode==0 and fixed.stdout.split()[0]==b['fixed25']['sha256']
rec={'taskId':b['taskId'],'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'kind':'OFFLINE_NORMAL_PRODUCT_CANDIDATE_NOT_DEPLOYMENT','jar':{'path':str(jar),'sha256':sha(jar),'bytes':jar.stat().st_size},'checks':checks,'classes':raw,'sqlSidecars':sidecars,'preservedExistingJarCount':len(b['oldTargetJars']),'sourceInputsUnchanged':True,'component31':'inherited component64 source-bound receipt; no tests run by package','fixed25Sha256Unchanged':True,'databaseWrites':0,'deployments':0,'secretDeliveries':0,'acceptance':False}
(e/'candidate-receipt65.json').write_text(json.dumps(rec,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(rec,ensure_ascii=False,indent=2))
