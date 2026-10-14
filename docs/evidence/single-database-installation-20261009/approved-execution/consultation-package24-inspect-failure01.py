import json,hashlib,zipfile,pathlib,xml.etree.ElementTree as ET,shutil,datetime,io,re
root=pathlib.Path.cwd(); base=root/'docs/evidence/single-database-installation-20261009/approved-execution'; out=base/'consultation-package24'; read=lambda p:json.loads(pathlib.Path(p).read_text(encoding='utf-8-sig')); digest=lambda b:hashlib.sha256(b).hexdigest(); sha=lambda p:digest(pathlib.Path(p).read_bytes()); ref=lambda p:{'path':str(p.resolve()),'sha256':sha(p)}
s=read(out/'source-snapshot.json'); build=pathlib.Path(s['exclusiveBuildRoot']); outputs=read(base/'final-entry-reactor22-compiled-output-inventory.json'); cp=read(base/'final-entry-reactor22-assembly-classpath.json'); frontend=read(out/'frontend-manifest.json'); pkg=read(base/'consultation-package24-package-result.json'); assert pkg['exitCode']==0
jar=build/'backend/apps/platform-api/target/agent-platform-consultation-product-20261009-24.jar'; checks=0
for f in s['sources']:
 assert sha(root/f['path'])==f['sha256']==sha(build/f['path']); checks+=2
for f in s['preserve']:
 assert sha(f['path'])==f['sha256'],f['path']; checks+=1
for m in outputs['modules']:
 for f in m['files']:
  assert sha(pathlib.Path(m['output'])/f['path'])==f['sha256']; checks+=1
ns={'m':'http://maven.apache.org/POM/4.0.0'}; module_archives=[]; external=[]
with zipfile.ZipFile(jar) as z:
 names=z.namelist(); assert len(names)==len(set(names)); manifest=z.read('META-INF/MANIFEST.MF').decode(); manifest=re.sub(r'\r?\n ', '',manifest); assert 'Start-Class: cn.xinji.agentplatform.security.product.ProductConsultationApplication' in manifest; assert 'org/springframework/boot/loader/launch/PropertiesLauncher.class' in names
 html=[n for n in names if n.startswith('BOOT-INF/classes/static/') and n.endswith('.html')]; assert html==['BOOT-INF/classes/static/app/consultation.html'],html; checks+=4
 api=next(m for m in outputs['modules'] if m['module']=='apps/platform-api'); expected={f['path'] for f in api['files'] if f['path'].endswith('.class')}; actual={n[len('BOOT-INF/classes/'):] for n in names if n.startswith('BOOT-INF/classes/') and n.endswith('.class')}; assert actual==expected; checks+=1
 for f in api['files']:
  assert digest(z.read('BOOT-INF/classes/'+f['path']))==f['sha256'],f['path']; checks+=1
 for f in frontend['files']:
  assert digest(z.read('BOOT-INF/classes/static/app/'+f['path']))==f['sha256']==sha(build/'frontend/dist-consultation'/f['path']); checks+=2
 staged=build/'backend/apps/platform-api/target/consultation-product-classes/static/app/consultation-product-assets.json'; assert z.read('BOOT-INF/classes/static/app/consultation-product-assets.json')==staged.read_bytes(); checks+=1
 js='\n'.join(z.read('BOOT-INF/classes/static/app/'+f['path']).decode() for f in frontend['files'] if f['path'].endswith('.js')); assert s['clientBinding']['clientId'] in js; assert 'code_challenge_method' in js and 'code_verifier' in js; checks+=2
 frontend_sources=[]
 for f in frontend['files']:
  if f['path'].endswith('.map'):
   sourcemap=json.loads(z.read('BOOT-INF/classes/static/app/'+f['path'])); frontend_sources.extend(x for x in sourcemap['sources'] if '/src/' in x)
 assert any(x.endswith('/src/consultation.tsx') for x in frontend_sources); assert not any(x.endswith('/src/main.tsx') or x.endswith('/src/enforcement.tsx') or x.endswith('/src/adjudication.tsx') for x in frontend_sources); checks+=2
 fresh_names=set()
 for m in s['inheritedCompiledModules']:
  if m['module']=='apps/platform-api':continue
  pom=ET.parse(build/'backend'/m['module']/'pom.xml').getroot(); aid=pom.find('m:artifactId',ns).text; p=build/'backend'/m['module']/'target'/(aid+'-0.0.1-SNAPSHOT.jar'); entry='BOOT-INF/lib/'+p.name; assert digest(z.read(entry))==sha(p),entry; fresh_names.add(entry); checks+=1
  inventory=next(o for o in outputs['modules'] if o['module']==m['module'])
  with zipfile.ZipFile(p) as module_zip:
   for f in inventory['files']:
    assert digest(module_zip.read(f['path']))==f['sha256']; checks+=1
   got={n for n in module_zip.namelist() if n.endswith('.class')}; wanted={f['path'] for f in inventory['files'] if f['path'].endswith('.class')};assert got==wanted;checks+=1
  module_archives.append({'module':m['module'],'archive':ref(p),'packagedEntry':entry,'matchesCurrent22ClassInventory':True})
 dependency_map={pathlib.Path(d['path']).name:d for d in cp['dependencies']}; repository=pathlib.Path('D:/software/m2/repository')
 for entry in sorted(n for n in names if n.startswith('BOOT-INF/lib/') and n.endswith('.jar') and n not in fresh_names):
  name=pathlib.Path(entry).name; dependency=dependency_map.get(name)
  if dependency is None:
   assert name=='spring-boot-jarmode-tools-3.3.5.jar',('UNRESOLVED_RUNTIME_DEPENDENCY',entry)
   p=repository/'org/springframework/boot/spring-boot-jarmode-tools/3.3.5'/name; dependency=ref(p); origin='CURRENT_BOOT_REPACKAGE_PLUGIN_TOOL'
  else:origin='CURRENT22_RESOLVED_EXTERNAL_DEPENDENCY'
  assert sha(dependency['path'])==dependency['sha256']==digest(z.read(entry)); checks+=1
  external.append({'entry':entry,'source':dependency,'basis':origin})
 result={'format':'CURRENT_CONSULTATION_PRODUCT_ACTUAL_ARTIFACT_INSPECTION/v1','capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'jar':ref(jar),'jarBytes':jar.stat().st_size,'sourceSnapshot':ref(out/'source-snapshot.json'),'sourceFileCount':s['sourceFileCount'],'sourceDrift':[],'inherited22OutputDrift':[],'originalAndPinnedReferenceDrift':[],'checks':checks,'reactorSuccessCount':13,'compileRepeated':False,'testsExecuted':False,'manifest':manifest,'packagedHtml':html,'packagedApiClassCount':len(actual),'freshProjectDependencies':module_archives,'externalRuntimeDependencies':external,'externalRuntimeDependencyCount':len(external),'frontendManifest':frontend,'frontendProjectSources':frontend_sources,'clientBinding':{**s['clientBinding'],'literalPresentInActualJs':True,'normalPkceFieldsPreserved':True,'registrationExecuted':False},'isEngineeringArtifact':True,'isFinalRelease':False,'loginVerified':False,'identityBootstrapExecuted':False,'applicationsStarted':False,'databaseConnections':0,'modelCalls':0}
 target=out/jar.name; assert not target.exists();shutil.copyfile(jar,target); assert sha(target)==result['jar']['sha256'];result['preservedEvidenceJar']=ref(target)
 frontend_out=out/'frontend';shutil.copytree(build/'frontend/dist-consultation',frontend_out); result['frontendOutput']=str(frontend_out.resolve())
 with (out/'artifact-inspection.json').open('x',encoding='utf-8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
 print(json.dumps({'jar':result['preservedEvidenceJar'],'jarBytes':result['jarBytes'],'checks':checks,'apiClasses':len(actual),'freshProjectJars':len(module_archives),'runtimeDependencyJars':len(external),'html':html,'clientRegistered':False},ensure_ascii=False,indent=2))
