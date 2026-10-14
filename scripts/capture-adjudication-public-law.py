"""Capture official statutory original; collecting is not granting case access or deciding applicability."""
import argparse,gzip,hashlib,json,re,sys,io
from datetime import datetime,timezone
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.parse import urljoin,urlparse
from html import unescape
from html.parser import HTMLParser
from pypdf import PdfReader
SHA=lambda b:hashlib.sha256(b).hexdigest()
PAGE='https://www.cnipa.gov.cn/art/2024/12/30/art_99_196969.html'
class Plain(HTMLParser):
 def __init__(self):super().__init__();self.parts=[];self.skip=0
 def handle_starttag(self,t,a):
  if t in ['script','style']:self.skip+=1
 def handle_endtag(self,t):
  if t in ['script','style']:self.skip=max(0,self.skip-1)
 def handle_data(self,s):
  if not self.skip and s.strip():self.parts.append(s.strip())
def fetch(url):
 if urlparse(url).scheme!='https' or urlparse(url).hostname!='www.cnipa.gov.cn':raise ValueError('Official HTTPS source required')
 with urlopen(Request(url,headers={'User-Agent':'AgentPlatform-LegalSourceCapture/1.0','Accept-Encoding':'identity'}),timeout=45) as r:
  if r.status!=200 or urlparse(r.url).hostname!='www.cnipa.gov.cn':raise ValueError('Official source response unavailable')
  raw=r.read(20_000_001)
  if len(raw)>20_000_000:raise ValueError('Source transport budget exceeded')
  body=gzip.decompress(raw) if raw[:2]==b'\x1f\x8b' else raw
  return body,{'url':url,'finalUrl':r.url,'status':r.status,'headers':dict(r.headers),'capturedAt':datetime.now(timezone.utc).isoformat(),'transportBytes':len(raw),'transportSha256':SHA(raw),'contentBytes':len(body),'contentSha256':SHA(body)}
def verify(root):
 m=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
 for f in m['artifacts']:
  p=(root/f['path']).resolve()
  if not p.is_relative_to(root.resolve()):raise ValueError('Source artifact escaped bundle')
  b=p.read_bytes()
  if len(b)!=f['bytes'] or SHA(b)!=f['sha256']:raise ValueError('Source integrity drift: '+f['path'])
 pdf=PdfReader(root/'original.pdf',strict=True);pages=[p.extract_text() or '' for p in pdf.pages]
 if pages!=m['pages']:raise ValueError('PDF/text extraction drift')
 packet=json.loads((root/'citation.json').read_text(encoding='utf-8'))
 text=packet['originalText'];prov=packet['provenance']
 if packet['source']['sha256']!=SHA(text.encode()) or prov['textSha256']!=packet['source']['sha256']:raise ValueError('Citation text drift')
 if prov['artifactSha256']!=SHA((root/'original.pdf').read_bytes()) or prov['manifestSha256']!=SHA((root/'manifest.json').read_bytes()):raise ValueError('Provenance drift')
 if text!=pages[packet['pageNumber']-1] or '第三十九条' not in text:raise ValueError('Original page binding missing')
 print(json.dumps({'verified':True,'manifestSha256':prov['manifestSha256'],'pages':len(pages),'artifacts':len(m['artifacts'])}))
def capture(root):
 if root.exists():raise ValueError('Refusing captured version overwrite')
 root.mkdir(parents=True)
 page,response=fetch(PAGE);(root/'publication.html').write_bytes(page)
 html=page.decode('utf-8');links=re.findall(r'href=["\']([^"\']+filename=[^"\']+\.pdf[^"\']*)',html)
 if len(links)!=1:raise ValueError('Unambiguous official PDF attachment required')
 pdf,download=fetch(urljoin(PAGE,unescape(links[0])))
 if not pdf.startswith(b'%PDF-'):raise ValueError('Official attachment is not PDF')
 (root/'original.pdf').write_bytes(pdf);reader=PdfReader(io.BytesIO(pdf),strict=True);pages=[p.extract_text() or '' for p in reader.pages]
 if not pages or any(not p.strip() for p in pages) or '专利纠纷行政裁决和调解办法' not in ''.join(pages):raise ValueError('Full readable statutory original required')
 (root/'text.txt').write_text('\n\n'.join(pages),encoding='utf-8')
 notice,noticeResponse=fetch('https://www.cnipa.gov.cn/art/2026/5/25/art_75_206532.html')
 n=Plain();n.feed(notice.decode('utf-8'))
 if not any('征求意见' in s for s in n.parts):raise ValueError('Consultation notice provenance changed')
 (root/'consultation-notice.html').write_bytes(notice)
 manifest={'schema':'ADJ_PUBLIC_LEGAL_CAPTURE/v1','sourceNature':'REAL_OFFICIAL_STATUTORY_DOCUMENT','publisher':'国家知识产权局','publicationVersion':'局令第81号/2024-12-30','capturedAt':response['capturedAt'],'purpose':'CASE_LEGAL_REFERENCE_AND_METHOD_REVIEW','rightsBasis':'公开发布的部门规章原文；限法条引用/内部办案参考，不包含第三方再编纂权利，不授个案访问权限','applicability':'CONFIRM_JURISDICTION_AND_RELEVANT_DATE_PER_CASE','latestGuideObservation':{'noticeUrl':noticeResponse['finalUrl'],'nature':'PUBLIC_CONSULTATION_NOT_EFFECTIVE_LAW'},'publication':response,'download':download,'noticeResponse':noticeResponse,'pages':pages,'artifacts':[]}
 for name in ['publication.html','original.pdf','text.txt','consultation-notice.html']:
  b=(root/name).read_bytes();manifest['artifacts'].append({'path':name,'bytes':len(b),'sha256':SHA(b)})
 (root/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
 text=pages[16];source={'id':'CNIPA-ORDER-81','version':'2024-12-30','locator':download['finalUrl']+'#page=17','sha256':SHA(text.encode()),'kind':'PUBLIC'}
 provenance={'publisher':manifest['publisher'],'url':PAGE,'capturedAt':response['capturedAt'],'publicationVersion':manifest['publicationVersion'],'rightsBasis':manifest['rightsBasis'],'purpose':manifest['purpose'],'artifactSha256':SHA(pdf),'artifactBytes':len(pdf),'textSha256':source['sha256'],'manifestSha256':SHA((root/'manifest.json').read_bytes())}
 (root/'citation.json').write_text(json.dumps({'pageNumber':17,'source':source,'originalText':text,'provenance':provenance},ensure_ascii=False,indent=2),encoding='utf-8');verify(root)
if __name__=='__main__':
 sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser();p.add_argument('mode',choices=['capture','verify']);p.add_argument('bundle',type=Path);a=p.parse_args();(capture if a.mode=='capture' else verify)(a.bundle)
