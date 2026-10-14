from pathlib import Path
from pypdf import PdfReader
import json,hashlib
root=Path(__file__).parent
records=[]
for name in ['installed-dm8-admin46.pdf','installed-dm8-export-import46.pdf']:
 p=root/name;r=PdfReader(p);hits=[]
 for num,page in enumerate(r.pages,1):
  text=page.extract_text() or ''
  terms=[t for t in ['LAST_CHANGE','DBA_CONSTRAINTS','DBA_INDEXES','SREF_CON','生成的约束','系统生成','GRANTS','CREATE_USER','SCHEMAS','STATISTICS','约束名'] if t in text]
  if terms:
   hits.append({'page':num,'terms':terms})
   (root/f'{p.stem}-page-{num:04}.txt').write_text(text,encoding='utf-8')
 records.append({'file':name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'pages':len(r.pages),'metadata':str(r.metadata),'hits':hits})
(root/'installed-manual-search46.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(records,ensure_ascii=False,indent=2))
