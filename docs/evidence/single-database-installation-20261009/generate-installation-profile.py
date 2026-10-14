import json,re,hashlib
from pathlib import Path
review=json.loads(Path('deployment/single-database/installation-sources.json').read_text(encoding='utf-8'))
cat=json.loads(Path('docs/evidence/single-database-installation-20261009/installation-reviewed02.json').read_text(encoding='utf-8'))
facts={x['path']:x for x in cat['migrations']+cat['auxiliarySql']}
registry=json.loads(Path('deployment/single-database/forward-originals.json').read_text(encoding='utf-8'));rpaths={r['sqlPath'] for r in registry['originals']};rsha=hashlib.sha256(Path('deployment/single-database/forward-originals.json').read_bytes()).hexdigest()
bindings=[{'domain':d,'business':{'schema':s,'ownerUser':s,'runtimeUser':s+'_SVC'},'authority':{'schema':a,'ownerUser':a,'runtimeUser':a+'_SVC','backupUser':a+'_BAK'}} for d,s,a in [('consultation','CONPROD','CONAUTH'),('enforcement','ENFPROD','ENFAUTH'),('adjudication','ADJPROD','ADJAUTH')]]
selected=[(e,b) for e in review['entries'] if e['decision']=='selected' for b in e['bindings']]
selected.sort(key=lambda x:(x[1]['schema'],999 if x[0]['version']=='A001' else int(x[0]['version'][1:])))
known={};final={};last={};migrations=[];installed=[];origins={}
def key(c):return ':'.join(str(c.get(v,'')) for v in ['kind','table','name'])
def unique(xs):return list({json.dumps(x,sort_keys=True):x for x in xs}.values())
def items(body):
 out=[];b='';level=0;quote=False
 for c in body:
  if c=="'":quote=not quote
  if not quote:
   if c=='(':level+=1
   if c==')':level-=1
   if c==',' and level==0:out.append(b.strip());b='';continue
  b+=c
 if b.strip():out.append(b.strip())
 return out
for e,b in selected:
 schema=b['schema'];text=Path(e['path']).read_text(encoding='utf-8');text=re.sub(r'--[^\n]*','',text);f=facts[e['path']];pre=[];post=[];changes=[];final.setdefault(schema,{})
 for o in f['objects']:
  if o['kind'] in ['TABLE','INDEX','SEQUENCE','VIEW']:
   c={'kind':'object','name':o['name'],'type':o['kind'],'expected':'present'};post.append(c);pre.append({**c,'expected':'absent'});known[(schema,o['name'],o['kind'])]={'schema':schema,'name':o['name'],'type':o['kind']}
   if o['kind']=='TABLE':installed.append({'schema':schema,'object':o['name'],'objectType':'TABLE','sourcePath':e['path'],'sourceSha256':e['sha256']})
  if o['kind']=='CONSTRAINT' and o['name']:
   kind=re.search(r'CONSTRAINT\s+'+o['name']+r'\s+(PRIMARY|UNIQUE|FOREIGN|CHECK)\b',text,re.I)
   post.append({'kind':'constraint','table':o['table'],'name':o['name'],'expected':'present',**({'type':{'PRIMARY':'P','UNIQUE':'U','FOREIGN':'R','CHECK':'C'}[kind[1].upper()]} if kind else {})})
 for mt in re.finditer(r'CREATE\s+TABLE\s+(\w+)\s*\(',text,re.I):
  start=mt.end();depth=1;n=start
  while depth and n<len(text):
   if text[n]=='(':depth+=1
   if text[n]==')':depth-=1
   n+=1
  if depth:raise Exception(('unbalanced',e['path']))
  for col in items(text[start:n-1]):
   if re.match(r'^(CONSTRAINT|PRIMARY|UNIQUE|CHECK|FOREIGN)\b',col,re.I):continue
   m=re.match(r'^(\w+)\s+(VARCHAR|CHAR|BIGINT|INTEGER|INT|SMALLINT|TIMESTAMP|CLOB|BLOB|BOOLEAN|DOUBLE|DECIMAL|NUMERIC|DATE|BINARY|VARBINARY)\b',col,re.I)
   if not m:raise Exception(('unreviewed column',e['path'],col[:100]))
   size=re.match(r'\w+\s+(?:VARCHAR|CHAR|BINARY|VARBINARY)\s*\(\s*(\d+)\s*\)',col,re.I)
   post.append({'kind':'column','table':mt[1].upper(),'name':m[1].upper(),'expected':'present',**({'size':int(size[1])} if size else {})})
 for a in re.finditer(r'ALTER\s+TABLE\s+(\w+)\s+(ADD|DROP|MODIFY)\s+([^;]+);',text,re.I):
  table,op,tail=a[1].upper(),a[2].upper(),a[3].strip();pre.append({'kind':'object','name':table,'type':'TABLE','expected':'present'})
  if op=='DROP':
   name=re.fullmatch(r'CONSTRAINT\s+(\w+)',tail,re.I)
   if not name:raise Exception(('unsafe drop',e['path'],tail))
   changes.append({'kind':'constraint','table':table,'name':name[1].upper(),'expected':'absent'})
  elif op=='ADD' and not re.match(r'CONSTRAINT\b',tail,re.I):
   m=re.match(r'(\w+)\s+(\w+)',tail);c={'kind':'column','table':table,'name':m[1].upper(),'expected':'present'};post.append(c);pre.append({**c,'expected':'absent'})
  elif op=='MODIFY':
   m=re.match(r'(\w+)\s+(\w+)',tail);c={'kind':'column','table':table,'name':m[1].upper(),'expected':'present'};post.append(c);pre.append(c)
 for c in changes:
  last_drop=max(m.start() for m in re.finditer(r'\bDROP\s+CONSTRAINT\s+'+c['name']+r'\b',text,re.I));adds=list(re.finditer(r'\bADD\s+CONSTRAINT\s+'+c['name']+r'\b',text,re.I))
  if not adds or adds[-1].start()<last_drop:post=[p for p in post if key(p)!=key(c)];final[schema][key(c)]=c;origins[(schema,key(c))]=schema+':'+e['version']
 for c in post:final[schema][key(c)]=c;origins[(schema,key(c))]=schema+':'+e['version']
 current={o['name'] for o in f['objects'] if o['kind']=='TABLE'}
 for ref in f['references']:
  if ref['context']=='REFERENCES' and ref['name'] not in current:pre.append({'kind':'object','name':ref['name'],'type':'TABLE','expected':'present'})
 m={'id':e['version'],'schema':schema,'domain':b['domain'],'sqlPath':e['path'],'sha256':e['sha256'],'dependsOn':[last[schema]] if schema in last else [],'prechecks':unique(pre),'postchecks':unique(post),'postcheckSupersededBy':[]}
 if e['path'] in rpaths:m['forwardOriginalsSha256']=rsha
 migrations.append(m);last[schema]=schema+':'+e['version']
for m in migrations:
 for c in m['postchecks']:
  origin=origins[(m['schema'],key(c))]
  if final[m['schema']][key(c)]['expected']=='absent' and origin!=m['schema']+':'+m['id']:m['postcheckSupersededBy'].append({'check':c,'by':origin})
profile={'format':'SINGLE_DATABASE_INSTALLATION_PROFILE/v1','taskId':'IMP-SINGLE-DB-FINAL-BASELINE-01','deploymentVersion':'SINGLE_DM8_DEPLOYMENT/v2','bindings':bindings,'sourceReviewPath':'deployment/single-database/installation-sources.json','sourceReviewSha256':hashlib.sha256(Path('deployment/single-database/installation-sources.json').read_bytes()).hexdigest(),'forwardOriginalsPath':'deployment/single-database/forward-originals.json','forwardOriginalsSha256':rsha,'capabilitiesPath':'deployment/single-database/application-capabilities.json','crossSchemaDependencies':[],'migrations':migrations,'knownObjects':list(known.values()),'installedTables':installed,'finalChecks':{k:list(v.values()) for k,v in final.items()},'requiredManagedInputs':['exact-live-deployment','current-action-approval','managed-account-password-references','three-independent-issuer-and-signing-master-keys','normal-identity-role-certificates-and-bootstrap-manifests','per-domain-tls-key-and-trust-references','approved-retention-policy-and-source-use-references','necessary-file-map-with-sha-and-rights'],'executionAuthorization':False}
profile['capabilitiesSha256']=hashlib.sha256(Path(profile['capabilitiesPath']).read_bytes()).hexdigest()
profile['applicationSettingsTemplatePath']='deployment/single-database/application-settings.template.json'
profile['applicationSettingsTemplateSha256']=hashlib.sha256(Path(profile['applicationSettingsTemplatePath']).read_bytes()).hexdigest()
Path('deployment/single-database/installation-profile.json').write_text(json.dumps(profile,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('migration instances',len(migrations),'tables',len(installed),'objects',len(known),'checks',sum(len(x) for x in profile['finalChecks'].values()))
