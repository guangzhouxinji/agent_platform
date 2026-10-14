"""Offline receipt/bytecode evidence only. No service imports, network or credentials."""
import hashlib,json,pathlib,re,struct,zipfile,xml.etree.ElementTree as ET,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[4]
OUT=pathlib.Path(__file__).resolve().parent
REPORT=ROOT/'docs/evidence/consultation-comparison-release-20261007/full-backend'
MANIFEST=ROOT/'docs/evidence/consultation-comparison-release-20261007/package/agent-platform-consultation-comparison-20261007-125036-manifest.json'
VERSIONS=['V092','V098','V099','V100','V101']
def sha(p):
 h=hashlib.sha256()
 with pathlib.Path(p).open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
 return h.hexdigest()
def write(name,v): (OUT/name).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def bound(p,expected):
 actual=sha(p)
 if actual!=expected: raise ValueError('SHA_MISMATCH: '+str(p))
 return {'path':str(pathlib.Path(p).resolve()),'sha256':actual,'bytes':pathlib.Path(p).stat().st_size}
def cp_utf8(b):
 if b[:4]!=b'\xca\xfe\xba\xbe': raise ValueError('NOT_CLASS')
 n=struct.unpack_from('>H',b,8)[0];pos=10;i=1;strings=[]
 while i<n:
  tag=b[pos];pos+=1
  if tag==1:
   size=struct.unpack_from('>H',b,pos)[0];pos+=2
   strings.append(b[pos:pos+size].decode('utf-8',errors='replace'));pos+=size
  elif tag in (3,4,9,10,11,12,17,18): pos+=4
  elif tag in (5,6):pos+=8;i+=1
  elif tag in (7,8,16,19,20):pos+=2
  elif tag==15:pos+=3
  else:raise ValueError('UNKNOWN_CLASS_TAG')
  i+=1
 return strings
REASONS={
'DocumentRuleAttemptRuntimeDm8Test':'执法文书规则浏览器执行尝试/制品/审计对账；缺ENF业务库入口，尚未进入SQL或断言。与咨询五迁移无表依赖；不证明通用制品/审计恢复。',
'DocumentRuleDependencyRuntimeDm8Test':'执法模板依赖变更版本与两次尝试对账；缺结果原件路径，尚未读取结果。不是咨询schema错误，但该执法依赖变更恢复证据仍缺。',
'DocumentRuleFrozenNegativeRuntimeDm8Test':'执法冻结负向场景原件缺失，尚未连接DM8/读取规则。此场景的禁止伪造/禁止发布尚未验证；不能用计算禁用替代失败关闭证据。',
'DocumentRuleNegativeRuntimeDm8Test':'执法规则路径/类型负例需真实产品库对账，缺ENF入口；不涉及con3/con4/con5迁移，但其制品无副作用结论未得到验证。',
'DocumentRuleRecoveryRuntimeDm8Test':'执法重启恢复基线原件缺失，尚未开展账本对账；不是咨询DDL失败，但不能从它推导旧Jar回退或咨询恢复成功。',
'DocumentTemplateDm8IntegrationTest':'执法文书模板V074的隔离DM8事务/原件/失败语义测试，在缺DM8_URL时提前失败；V074不在咨询五迁移内。共享驱动真实事务语义未由该测试证明。',
'EnforcementFollowupDm8MigrationTest':'执法V074/V075在隔离DM8执行，缺DM8_URL前置；不执行V092/V098-V101。真实DM8迁移能力仍无此项验证，H2不能补足。',
'JobPackDm8IntegrationTest':'执法任务包持久化/验证/单次触发，缺专属JOB_PACK_DM8_URL，未进DM8。非咨询schema依赖；共享事务及单次副作用不能据此声称通过。',
'RealBusinessCollectionDm8Test':'执法真实业务采集任务包/无误触发，缺专属JOB_PACK_DM8_URL，未进DM8。与咨询五迁移对象无交集；业务采集验收仍失败。',
'LegalReferenceDm8IntegrationTest':'执法法规检索绑定/复核/回执V075，缺DM8_URL提前失败；非咨询schema依赖。真实DM8/CLOB/事务与历史保持仍不能继承该项证据。',
'EnforcementDocumentRuleFacilityFixtureTest':'执法attempt设施前置缺ENF入口，未读ENF_DOCUMENT_RULE_MIGRATION_FILE也未执行DDL；外部迁移文件实际版本/内容未知，不能断言其与A五迁移绝无交集。固定A发布不得复用此未核外部DDL；该测试后置仅检查两设施表存在，不足以证明无业务行变更。'}
def errors_from_reports(summary,base):
 rows=[]
 for report in summary['reports']:
  if not report['errors']:continue
  p=base/report['file'];binding=bound(p,report['sha256']);tree=ET.parse(p)
  found=0
  for t in tree.getroot().findall('testcase'):
   e=t.find('error')
   if e is None:continue
   found+=1;cls=t.attrib['classname'];short=cls.rsplit('.',1)[-1]
   if short not in REASONS:raise ValueError('UNCLASSIFIED_ERROR')
   msg=e.attrib.get('message','')
   if not ('required' in msg or 'must be injected' in msg):raise ValueError('NON_ENVIRONMENT_ERROR')
   source=ROOT/'backend/apps/platform-api/src/test/java'/pathlib.Path(*cls.split('.')).with_suffix('.java')
   if not source.is_file():raise ValueError('MISSING_TEST_SOURCE')
   text=source.read_text(encoding='utf-8');key=msg.split()[0]
   snapshot=json.loads((ROOT/'docs/evidence/three-domain-release-readiness-20261007/package-source-before.json').read_text(encoding='utf-8'))
   recorded=next((x['sha256'] for x in snapshot if x['path']==source.relative_to(ROOT).as_posix()),None)
   if recorded is None or recorded!=sha(source):raise ValueError('TEST_SOURCE_SNAPSHOT_MISMATCH')
   if key not in text:raise ValueError('ENV_KEY_NOT_IN_SOURCE')
   scenario=None
   if short=='DocumentRuleFrozenNegativeRuntimeDm8Test':
    choices=re.search(r'@ValueSource\(strings=\{([^}]+)\}',text)
    names=re.findall(r'"([^"]+)"',choices.group(1));scenario=names[int(re.search(r'\[(\d+)\]',t.attrib['name']).group(1))-1]
   sql_tables=sorted(set(re.findall(r'\b(?:FROM|INTO|UPDATE|JOIN)\s+((?:enf|con|id)\w+)',text,re.I)))
   rows.append({'number':len(rows)+1,'class':cls,'test':t.attrib['name'],'scenario':scenario,'original':binding,'errorType':e.attrib.get('type'),'message':msg,'source':{'path':str(source),'sha256':sha(source)},'tablesSeenInTestSource':sql_tables,'failedBeforeBusinessAssertions':True,'classification':('UNKNOWN_EXTERNAL_MIGRATION_INPUT__FAILED_BEFORE_EXECUTION' if short=='EnforcementDocumentRuleFacilityFixtureTest' else 'NO_DIRECT_A_DDL_DEPENDENCY__ENVIRONMENT_EVIDENCE_GAP_REMAINS'),'reason':REASONS[short]+((' 场景='+scenario+'；该项负向业务断言仍未执行。') if scenario else ''),'stackLocation':re.findall(r'\bat '+re.escape(cls)+r'[^\r\n]*',e.text or '')[:2],'releaseCondition':'主会话独立证明咨询固定包启动、真实DM8前置/约束、当前授权及读取；不得宣称全后端通过；不得继承执法恢复或共享DM8语义结论'})
  if found!=report['errors']:raise ValueError('ERROR_COUNT_MISMATCH')
 if len(rows)!=summary['totals']['errors']:raise ValueError('ERROR_COUNT_MISMATCH')
 return rows


def derive_auxiliary(newjar,oldjar):
 # Extract only the fixed startup schema guard and its exact JDBC libraries.
 bindings=[]
 with zipfile.ZipFile(newjar) as z:
  for n in z.namelist():
   guard=n.startswith('BOOT-INF/classes/cn/xinji/agentplatform/security/product/ProductComparisonSchemaGuard') and n.endswith('.class')
   lib=n.startswith('BOOT-INF/lib/') and any(n.split('/')[-1].startswith(x+'-') for x in ['spring-jdbc','spring-core','spring-beans','spring-tx','spring-jcl'])
   if not (guard or lib):continue
   p=OUT/'new-guard'/(n.removeprefix('BOOT-INF/') if guard else 'lib/'+n.split('/')[-1]);p.parent.mkdir(parents=True,exist_ok=True);b=z.read(n);p.write_bytes(b)
   bindings.append({'entry':n,'sha256':hashlib.sha256(b).hexdigest(),'extracted':str(p)})
 write('fixed-guard-extraction.json',bindings)
 metadata_path=ROOT/'docs/evidence/consultation-approved-release-20261007/preflight/current-metadata.json'
 if not metadata_path.is_file():metadata_path=ROOT/'docs/evidence/three-domain-release-readiness-20261007/metadata/current-metadata.json'
 metadata=json.loads(metadata_path.read_text(encoding='utf-8'))
 write('query-metadata-binding.json',{'path':str(metadata_path),'sha256':sha(metadata_path),'observedAt':metadata['observedAt'],'businessColumns':len(metadata['business']['columns']),'authorityColumns':len(metadata['authority']['columns']),'scope':'Offline schema shape assistance only, not protection, migration provenance or current authority'})
 for side,file in [('business','metadata-columns.tsv'),('authority','authority-columns.tsv')]:
  rows=[]
  for col in metadata[side]['columns']:
   t=col['DATA_TYPE'];size=col['DATA_LENGTH'];precision=col['DATA_PRECISION'];scale=col['DATA_SCALE']
   if t in ('VARCHAR','CHAR','VARCHAR2'):t=('VARCHAR' if t=='VARCHAR2' else t)+'('+str(size)+')'
   elif t in ('NUMBER','DECIMAL','NUMERIC') and precision:t='DECIMAL('+str(precision)+','+str(scale or 0)+')'
   if t=='BINARY':t+='('+str(size)+')'
   rows.append('\t'.join([col['TABLE_NAME'],col['COLUMN_NAME'],t]))
  (OUT/file).write_text('\n'.join(rows)+'\n',encoding='utf-8')
 oldlibs=[]
 with zipfile.ZipFile(oldjar) as z:
  for n in z.namelist():
   if n.startswith('BOOT-INF/lib/') and any(n.split('/')[-1].startswith(x+'-') for x in ['spring-jdbc','spring-core','spring-beans','spring-tx','spring-jcl','spring-security-core','spring-security-oauth2-core','spring-security-oauth2-jose','agent-platform-consultation']):
    p=OUT/'old-auth/lib'/n.split('/')[-1];p.parent.mkdir(parents=True,exist_ok=True);b=z.read(n);p.write_bytes(b);oldlibs.append({'entry':n,'sha256':hashlib.sha256(b).hexdigest(),'extracted':str(p)})
 write('old-auth-library-binding.json',oldlibs)
 interfaces=[]
 with zipfile.ZipFile(oldjar) as z:
  for short in ['ProductConsultationTaskController','ProductComparisonPreparationController','ProductComparisonInputController','ProductPatentSearchController','ProductConsultationTaskController$TaskView']:
   entry='BOOT-INF/classes/cn/xinji/agentplatform/security/product/'+short+'.class';b=z.read(entry)
   p=OUT/'old-interface'/('classes/'+entry.removeprefix('BOOT-INF/classes/'));p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
   args=['D:/software/jdk-21/bin/javap.exe','-p',str(p)]
   result=subprocess.run(args,capture_output=True,text=True,encoding='utf-8',errors='replace',check=True)
   annotations=subprocess.run(['D:/software/jdk-21/bin/javap.exe','-v',str(p)],capture_output=True,text=True,encoding='utf-8',errors='replace',check=True).stdout
   # Keep only mapping/conditional annotations, never all constants or request/model data.
   kept=re.findall(r'(?:org\.springframework\.web\.bind\.annotation\.(?:GetMapping|RequestMapping)|org\.springframework\.boot\.autoconfigure\.condition\.ConditionalOnProperty)\(\s*.*?\n\s*\)',annotations,re.S)
   interfaces.append({'class':entry,'classSha256':hashlib.sha256(b).hexdigest(),'declarations':result.stdout.splitlines(),'readMappingAnnotations':kept})
 write('old-jar-read-interface-binding.json',{'scope':'Exact old-Jar javap declarations/mapping annotations only, not endpoint execution or authorization','interfaces':interfaces})
 # Calculation switch annotations from actual fixed new classes, not current source inference.
 config=[]
 with zipfile.ZipFile(newjar) as z:
  for short in ['ProductComparisonMatrixController','ProductComparisonMatrixConfiguration','ProductComparisonSchemaConfiguration']:
   entry='BOOT-INF/classes/cn/xinji/agentplatform/security/product/'+short+'.class';b=z.read(entry);strings=cp_utf8(b)
   config.append({'class':entry,'sha256':hashlib.sha256(b).hexdigest(),'propertyConstants':[x for x in strings if x.startswith('product.') or x.startswith('${product.')],'conditionAnnotationPresent':'Lorg/springframework/boot/autoconfigure/condition/ConditionalOnProperty;' in strings,'scope':'static constants only; does not prove actual deployment values or absence of calls'})
 write('fixed-calculation-switch-binding.json',config)

def main():
 m=json.loads(MANIFEST.read_text());receipt_path=ROOT/'docs/evidence/three-domain-release-readiness-20261007/engineering-receipt.json';receipt=json.loads(receipt_path.read_text())
 new=m['artifact']['artifact'];request=receipt['proposedOperationsRequest']
 if new!=request['newArtifact']:raise ValueError('REQUEST_ARTIFACT_MISMATCH')
 old=ROOT/'backend/apps/platform-api/target/agent-platform-consultation-browser-20261006.jar'
 binding={'task':'VER-CON-COMPARISON-RELEASE-COMPAT-01','scope':'OFFLINE_BYTES_ONLY_NOT_OPERATIONAL_APPROVAL','manifest':{'path':str(MANIFEST),'sha256':sha(MANIFEST)},'receipt':{'path':str(receipt_path),'sha256':sha(receipt_path)},'newJar':bound(ROOT/new['path'],new['sha256']),'oldJar':bound(old,receipt['targetMetadata']['configuredAndActuallyMountedOldJarSha']),'forward':[],'baseline':[],'reverse':[],'readOnlyScripts':[],'metadataSnapshot':{'path':str(ROOT/'docs/evidence/three-domain-release-readiness-20261007/metadata/current-metadata.json'),'sha256':sha(ROOT/'docs/evidence/three-domain-release-readiness-20261007/metadata/current-metadata.json')},'inheritance':{'path':str(ROOT/'docs/evidence/three-domain-release-readiness-20261007/backend-input-inheritance.json'),'sha256':sha(ROOT/'docs/evidence/three-domain-release-readiness-20261007/backend-input-inheritance.json')}}
 for entry in m['migrations']['forward']:
  v=pathlib.Path(entry['path']).name[:4]
  binding['forward' if v in VERSIONS else 'baseline'].append(bound(ROOT/entry['path'],entry['sha256']))
 # V088 is existing capture-identity shape, not an approved replay on live DM8.
 v88=next((ROOT/'db/dm8').glob('V088*'));binding['baseline'].append({'path':str(v88),'sha256':sha(v88),'bytes':v88.stat().st_size})
 for entry in m['migrations']['reverse']:
  if pathlib.Path(entry['path']).name[:4] in [v.replace('V','U') for v in VERSIONS]:binding['reverse'].append(bound(ROOT/entry['path'],entry['sha256']))
 u92=next((ROOT/'db/dm8').glob('U092*'));binding['reverse'].append({'path':str(u92),'sha256':sha(u92),'bytes':u92.stat().st_size,'binding':'ANCILLARY_REVERSE_NOT_LISTED_IN_FIXED_MANIFEST_NOT_APPROVED_TO_EXECUTE'})
 for rel in ['scripts/start-consultation-container.ps1','scripts/lib/consultation-comparison-deployment.ps1','scripts/verify-consultation-comparison-release.mjs','scripts/lib/consultation-comparison-release.mjs']:
  p=ROOT/rel;binding['readOnlyScripts'].append({'path':str(p),'sha256':sha(p)})
 # Pin all selected manifest assets/class entries against actual archive bytes.
 assets=[]
 with zipfile.ZipFile(ROOT/new['path']) as z:
  for entry in m['artifact']['classes']+m['artifact']['assets']:
   actual=hashlib.sha256(z.read(entry['path'])).hexdigest()
   if actual!=entry['sha256']:raise ValueError('JAR_ENTRY_MISMATCH')
   assets.append({'entry':entry['path'],'sha256':actual})
 binding['pinnedJarEntries']=assets
 write('input-binding.json',binding)
 summary_path=REPORT/'full-backend-round1-result.json';summary=json.loads(summary_path.read_text())
 impact_rows=errors_from_reports(summary,REPORT)
 write('backend20-impact.json',{'summary':{'path':str(summary_path),'sha256':sha(summary_path),'totals':summary['totals'],'reactorCompleted':summary['reactorCompleted']},'errors':impact_rows,'overall':'20原始错误保留；11测试类均在缺环境/原件前置失败，未观察到咨询DDL执行失败；第20项外部迁移文件未知，其余按现有测试范围有条件无直接A表依赖，不等同发布准入或环境验证通过'})
 keys=sorted(set(e['message'].split()[0] for e in impact_rows));hits={k:[] for k in keys}
 packed_tests=[]
 with zipfile.ZipFile(ROOT/new['path']) as z:
  names=set(z.namelist())
  for cls in sorted(set(e['class'] for e in impact_rows)):
   packed_tests.append({'testClass':cls,'presentInFixedNewJar':'BOOT-INF/classes/'+cls.replace('.','/')+'.class' in names})
  for name in names:
   if not name.startswith('BOOT-INF/classes/') or not name.endswith('.class'):continue
   for value in cp_utf8(z.read(name)):
    for key in keys:
     if key in value:hits[key].append(name)
 write('backend20-fixed-runtime-binding.json',{'newJar':binding['newJar'],'testClasses':packed_tests,'missingEnvironmentKeyLiteralOccurrences':hits,'scope':'Static fixed package application-class scan only: tests not packaged and missing env literals absent. Does NOT prove whole startup/deployment or dynamically constructed configuration behavior.'})
 queries=[];fragments=[];classes=0
 with zipfile.ZipFile(old) as z:
  for name in z.namelist():
   if not name.startswith('BOOT-INF/classes/cn/xinji/agentplatform/') or not name.endswith('.class'):continue
   b=z.read(name);classes+=1
   for s in cp_utf8(b):
    if not re.search(r'\bselect\b',s,re.I) or not re.search(r'\b(?:con3_|con4_|con5_|id2_)',s,re.I):continue
    # Exclude model text; only complete JDBC SELECT constants or SQL concat recipes.
    if not re.match(r'^\s*SELECT\b',s,re.I):continue
    entry={'class':name,'classSha256':hashlib.sha256(b).hexdigest(),'sql':s,'sqlSha256':hashlib.sha256(s.encode()).hexdigest()}
    if '\x01' in s or '\x02' in s or not re.search(r'\bFROM\s+\w+',s,re.I):fragments.append(entry);continue
    queries.append(entry)
 # Duplicates across classes retained for class provenance, not count inflation.
 (OUT/'old-jar-selects.sql.json').write_text(json.dumps(queries,ensure_ascii=False,indent=2),encoding='utf-8')
 (OUT/'old-jar-selects.txt').write_text('\n'.join(q['sql'].replace('\r',' ').replace('\n',' ') for q in queries)+'\n',encoding='utf-8')
 write('old-jar-read-compatibility.json',{'jar':binding['oldJar'],'applicationClassesScanned':classes,'completeSelectConstants':len(queries),'distinctSelectConstants':len(set(q['sql'] for q in queries)),'queriesEvidence':'old-jar-selects.sql.json','dynamicOrFragmentSql':fragments,'scope':'实际旧Jar常量池SQL形状；不是完整读取方法执行，动态SQL及参数/数据映射/权限/HTTP读取未证明；业务依赖Jar常量未扫描','rollbackWriteCompatibility':'NOT_PROVED','realRollback':'NOT_PROVED'})
 (OUT/'baseline-paths.txt').write_text('\n'.join(x['path'] for x in binding['baseline'])+'\n',encoding='utf-8')
 (OUT/'forward-paths.txt').write_text('\n'.join(x['path'] for x in binding['forward'])+'\n',encoding='utf-8')
 (OUT/'reverse-paths.txt').write_text('\n'.join(x['path'] for x in binding['reverse'])+'\n',encoding='utf-8')
 derive_auxiliary(ROOT/new['path'],old)
 preflight=ROOT/'docs/evidence/consultation-approved-release-20261007/preflight'
 context=[]
 for name in ['fixed-target-protection.json','current-metadata.json']:
  p=preflight/name
  if p.is_file():context.append({'path':str(p),'sha256':sha(p)})
 write('current-readonly-context.json',{'originals':context,'source':'Human supplemental instruction plus existing read-only main-session receipts','historicalAuditUntil':'2026-10-03T06:53:33+08:00','historicalAuditExpired':True,'currentProtectionVerifiedByThisTask':False,'nativeExportOrHistoricalDumpIsCurrentProtection':False,'newBackupDDLStopDeployPerformedByThisTask':False})
 write('tool-binding.json',{'python':'stdlib only','java':{'path':'D:/software/jdk-21/bin/java.exe','sha256':sha('D:/software/jdk-21/bin/java.exe')},'h2':{'path':'D:/software/m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar','sha256':sha('D:/software/m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar')}})
 print('Bound fixed Jars, 5 forwards/reverses, 20 original errors; extracted',len(queries),'old-Jar SELECT constants')
if __name__=='__main__':main()
