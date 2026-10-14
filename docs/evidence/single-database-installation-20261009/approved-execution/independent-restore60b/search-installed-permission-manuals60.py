from pathlib import Path
from pypdf import PdfReader
import json,hashlib,datetime
b=Path('docs/evidence/single-database-installation-20261009/approved-execution');o=b/'independent-restore60b';records=[]
for name,remote in [('installed-dm8-sql-language60.pdf','/opt/dmdbms/doc/DM8_SQL\u8bed\u8a00\u4f7f\u7528\u624b\u518c.pdf'),('installed-dm8-security60.pdf','/opt/dmdbms/doc/DM8\u5b89\u5168\u7ba1\u7406.pdf')]:
 p=o/name;r=PdfReader(p);hits=[]
 for n,page in enumerate(r.pages,1):
  text=page.extract_text() or ''
  if 'GRANTOR' in text or (('REVOKE' in text or '\u6536\u56de' in text or '\u64a4\u9500' in text) and ('\u6743\u9650' in text or 'GRANT' in text)):
   file=f'{p.stem}-permission-page-{n:04}.txt';(o/file).write_text(text,encoding='utf-8');hits.append(dict(page=n,extractedText=file,sha256=hashlib.sha256(text.encode()).hexdigest()))
 records.append(dict(file=str(p),containerId='f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f',containerPath=remote,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),pages=len(r.pages),metadata=str(r.metadata),hits=hits))
(o/'installed-permission-manual-source60.json').write_text(json.dumps(dict(at=datetime.datetime.now(datetime.timezone.utc).isoformat(),responsibleParty='Codex',scope='Read-only original installed manuals for grantor D; source DB/schema/data/permissions unchanged, no external data or tools installed',records=records,GRANTOREquivalenceConfirmed=False,targetRestarted=False,DCLPerformed=False),ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps([dict(file=Path(r['file']).name,pages=r['pages'],hitPages=[h['page'] for h in r['hits']]) for r in records]))
