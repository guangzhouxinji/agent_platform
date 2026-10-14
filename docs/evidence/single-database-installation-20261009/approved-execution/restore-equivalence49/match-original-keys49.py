import json,re,hashlib,datetime
from pathlib import Path
B=Path('docs/evidence/single-database-installation-20261009/approved-execution');E=B/'restore-equivalence49'
c=json.loads((B/'primary-migration-config07.json').read_text(encoding='utf-8'));raw=json.loads((E/'installed-catalog-probe49.raw.json').read_text(encoding='utf-8'));inv=json.loads((B/'native-parameters48/current-full-inventory48.json').read_text(encoding='utf-8'))
# Evidence-only lexical extraction. No SQL execution and no product name normalization.
rx=re.compile(r"--[^\n]*|/\*[\s\S]*?\*/|'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|[A-Za-z_][A-Za-z_0-9$]*|\d+|[^\s]",re.M)
def toks(t):return [(m.group().upper(),m.start(),m.end()) for m in rx.finditer(t) if not m.group().startswith(('--','/*'))]
def clauses(ts,start):
 depth=1;begin=start
 for j in range(start,len(ts)):
  x=ts[j][0]
  if x=='(':depth+=1
  elif x==')':
   depth-=1
   if depth==0:
    yield ts[begin:j];return
  elif x==',' and depth==1:yield ts[begin:j];begin=j+1
 raise ValueError('UNCLOSED_CREATE_TABLE')
def ids(xs):
 if not xs or xs[0]!='(' or ')' not in xs:return None
 inside=xs[1:xs.index(')')]
 if not inside or any(not re.fullmatch('[A-Z][A-Z_0-9]*',v) for v in inside[::2]) or any(v!=',' for v in inside[1::2]):return None
 return inside[::2]
entries=[]; verified=[]
for m in c['manifest']['migrations']:
 p=Path(m['sqlPath']);b=p.read_bytes();assert hashlib.sha256(b).hexdigest()==m['sha256'], 'APPROVED_ORIGINAL_SHA_CHANGED'
 verified.append({'schema':m['schema'],'id':m['id'],'path':m['sqlPath'],'sha256':m['sha256']})
 text=b.decode('utf-8-sig');ts=toks(text)
 for i in range(len(ts)-3):
  if [x[0] for x in ts[i:i+2]]!=['CREATE','TABLE']:continue
  table=ts[i+2][0]
  if ts[i+3][0]!='(':raise ValueError('UNSUPPORTED_CREATE_TABLE_FORM')
  for cl in clauses(ts,i+4):
   xs=[x[0] for x in cl]
   if not xs or 'CONSTRAINT' in xs:continue
   for typ,pattern in [('P',['PRIMARY','KEY']),('U',['UNIQUE'])]:
    for k in range(len(xs)-len(pattern)+1):
     if xs[k:k+len(pattern)]!=pattern:continue
     cols=ids(xs[k+len(pattern):]) if k==0 else [xs[0]]
     if not cols:continue
     entries.append({'schema':m['schema'],'table':table,'type':typ,'columns':cols,'sqlPath':m['sqlPath'],'sqlSha256':m['sha256'],'line':text[:cl[0][1]].count('\n')+1,'clause':text[cl[0][1]:cl[-1][2]],'migrationId':m['id']})
results=[]
for n in raw['nativeConstraints']['rows']:
 if n['CONSTRAINT_NAME']!='CONS'+str(n['ID']) or n['TYPE$'] not in ['P','U']:continue
 s=next(x for x in inv['schemas'] if x['schema']==n['SCHEMA_NAME']);cc=next(x for x in s['constraints'] if x['CONSTRAINT_NAME']==n['CONSTRAINT_NAME']);cols=[x['COLUMN_NAME'] for x in sorted((x for x in s['constraintColumns'] if x['CONSTRAINT_NAME']==n['CONSTRAINT_NAME']),key=lambda x:x['POSITION'])]
 matches=[e for e in entries if e['schema']==s['schema'] and e['table']==cc['TABLE_NAME'] and e['type']==cc['CONSTRAINT_TYPE'] and e['columns']==cols]
 results.append({'schema':s['schema'],'constraint':n['CONSTRAINT_NAME'],'table':cc['TABLE_NAME'],'type':n['TYPE$'],'columns':cols,'matchingUnnamedOriginalDeclarations':matches})
report={'format':'APPROVED_ORIGINAL_UNNAMED_KEY_DECLARATION_CANDIDATES/v1','capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'lexical original CREATE TABLE P/U declaration and ordered-column match only; not full migration-state reconstruction or recovery normalization approval','allOriginalsShaVerified':len(verified),'originals':verified,'candidateCount':len(results),'uniqueDeclarationMatches':sum(len(x['matchingUnnamedOriginalDeclarations'])==1 for x in results),'unresolvedCount':sum(len(x['matchingUnnamedOriginalDeclarations'])!=1 for x in results),'remainingNonKeyCandidates':9,'limitations':['FK/CHECK unnamed declaration analysis not performed.','Drop/alter/recreate lifecycle not reconstructed; no permission to rename objects solely from this lexical evidence.','No recovery destination compared and no restore code modified.'],'candidates':results}
(E/'approved-original-unnamed-keys49.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:report[k] for k in ['allOriginalsShaVerified','candidateCount','uniqueDeclarationMatches','unresolvedCount','remainingNonKeyCandidates']},indent=2))
