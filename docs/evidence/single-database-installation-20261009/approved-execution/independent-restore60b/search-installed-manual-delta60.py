from pathlib import Path
from pypdf import PdfReader
import hashlib,json,datetime
b=Path('docs/evidence/single-database-installation-20261009/approved-execution');out=b/'independent-restore60b';records=[]
terms=['GRANTOR','授权者','授权人','INFO4','INFO5','CRTDATE','SYSOBJECTS']
for name in ['installed-dm8-admin46.pdf','installed-dm8-export-import46.pdf']:
 p=b/'mainline-execution46'/name;r=PdfReader(p);hits=[]
 for n,page in enumerate(r.pages,1):
  text=page.extract_text() or '';matches=[t for t in terms if t in text]
  if matches:
   file=f'{p.stem}-delta-page-{n:04}.txt';text.encode('utf-8');(out/file).write_text(text,encoding='utf-8');hits.append(dict(page=n,terms=matches,extractedText=file,sha256=hashlib.sha256(text.encode()).hexdigest()))
 records.append(dict(file=str(p),sha256=hashlib.sha256(p.read_bytes()).hexdigest(),pages=len(r.pages),metadata=str(r.metadata),hits=hits))
(out/'installed-manual-delta-source60.json').write_text(json.dumps(dict(at=datetime.datetime.now(datetime.timezone.utc).isoformat(),responsibleParty='Codex',scope='Read-only installed official manual text search for actual restore delta D, not a confirmed semantics conclusion',records=records,permissionEquivalenceAccepted=False,newTransformsAdmitted=False),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps([dict(file=Path(r['file']).name,pages=r['pages'],hits=r['hits']) for r in records],ensure_ascii=False))
