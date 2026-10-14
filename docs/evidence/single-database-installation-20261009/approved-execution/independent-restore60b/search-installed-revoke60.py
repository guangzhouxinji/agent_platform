from pathlib import Path
from pypdf import PdfReader
import json,hashlib,datetime
b=Path('docs/evidence/single-database-installation-20261009/approved-execution');o=b/'independent-restore60b';p=b/'mainline-execution46/installed-dm8-admin46.pdf';r=PdfReader(p);hits=[]
for n,page in enumerate(r.pages,1):
 text=page.extract_text() or ''
 if ('REVOKE' in text or '\u64a4\u9500' in text or '\u6536\u56de' in text) and ('\u6743\u9650' in text or 'GRANT' in text):
  file=f'installed-dm8-admin46-permission-page-{n:04}.txt';(o/file).write_text(text,encoding='utf-8');hits.append(dict(page=n,extractedText=file,sha256=hashlib.sha256(text.encode()).hexdigest()))
(o/'installed-manual-revoke-source60.json').write_text(json.dumps(dict(at=datetime.datetime.now(datetime.timezone.utc).isoformat(),sourcePath=str(p),sourceSha256=hashlib.sha256(p.read_bytes()).hexdigest(),sourceMetadata=str(r.metadata),hits=hits,GRANTOREquivalenceConfirmed=False,scope='Installed manual evidence search only, no SQL execution or inference of revocation equivalence'),ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(hitPages=[h['page'] for h in hits])))
