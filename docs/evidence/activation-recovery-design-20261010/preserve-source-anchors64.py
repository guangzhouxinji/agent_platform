from pathlib import Path
import hashlib,json,datetime
r=Path.cwd(); e=r/'docs/evidence/activation-recovery-design-20261010'; record=[]
for name in ['ProductSubjects.java','ProductIdentityController.java']:
 p=r/'backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product'/name; current=p.read_bytes(); (e/(name+'.before-anchor64')).write_bytes(current); s=current.decode('utf-8')
 original=(e/(name+'.before-implementation62')).read_bytes(); old=original.decode('utf-8'); idx=old.rfind('}'); prefix=old[:idx]
 if name=='ProductSubjects.java':
  records=s[s.index('    public record Recovery('):s.index('    private record Subject(')]
  methods=s[s.index('    public Recovery recoverActivation('):s.index('    public void activate(')]
  extra=records+'\n'+methods.replace('DataIntegrityViolationException','org.springframework.dao.DataIntegrityViolationException')
 else:
  extra=s[s.index('    @PostMapping("/subjects/{id}/activation-recovery")'):s.index('    @PostMapping("/subjects/{id}/activate")')]
  for a,b in [('Map<String, Object>','java.util.Map<String, Object>'),('new LinkedHashMap<>','new java.util.LinkedHashMap<>'),('HttpServletResponse response','jakarta.servlet.http.HttpServletResponse response'),('JsonNode input','com.fasterxml.jackson.databind.JsonNode input'),('JsonParser.Feature','com.fasterxml.jackson.core.JsonParser.Feature'),('DeserializationFeature.FAIL','com.fasterxml.jackson.databind.DeserializationFeature.FAIL')]: extra=extra.replace(a,b)
 result=(prefix+'\n'+extra+old[idx:]).encode('utf-8'); assert result.startswith(original[:len(prefix.encode('utf-8'))]); p.write_bytes(result)
 record.append({'path':str(p),'oldSourceSha256':hashlib.sha256(original).hexdigest(),'newSourceSha256':hashlib.sha256(result).hexdigest(),'preservedOriginalPrefixBytes':len(prefix.encode('utf-8')),'originalPrefixExact':True,'addedMembersOnlyAtClassEnd':True})
p=r/'deployment/single-database/application-capabilities.json'; raw=p.read_bytes(); (e/'application-capabilities.json.before-anchor64').write_bytes(raw); cap=json.loads(raw.decode('utf-8')); changes=[]; anchor=[]
new={a['path']:a['newSourceSha256'] for a in record}
def visit(o,at='$'):
 if isinstance(o,dict):
  path=o.get('path')
  if path and str(r/path) in new:
   source=r/path; line=o['line']; original=(e/(source.name+'.before-implementation62')).read_text(encoding='utf-8').splitlines(); now=source.read_text(encoding='utf-8').splitlines(); assert original[line-1]==now[line-1]
   anchor.append({'path':path,'line':line,'originalLineEqualsCurrent':True,'lineContent':original[line-1]})
   changes.append({'jsonPath':at+'.sha256','path':path,'old':o['sha256'],'new':new[str(source)]})
  for k,v in o.items():visit(v,at+'.'+k)
 elif isinstance(o,list):
  for i,v in enumerate(o):visit(v,at+f'[{i}]')
visit(cap); assert len(changes)==len(anchor)==24; text=raw.decode('utf-8')
for path in set(c['path'] for c in changes):
 group=[c for c in changes if c['path']==path]; assert len({c['old'] for c in group})==1; assert text.count(group[0]['old'])==len(group); text=text.replace(group[0]['old'],group[0]['new'])
after=json.loads(text)
def scrub(o):
 if isinstance(o,dict):
  if o.get('path') and str(r/o['path']) in new: o['sha256']='KNOWN_SOURCE_DIGEST'
  for v in o.values():scrub(v)
 elif isinstance(o,list):
  for v in o:scrub(v)
scrub(cap); check=json.loads(text); scrub(check); assert cap==check; p.write_bytes(text.encode('utf-8'))
profile=r/'deployment/single-database/installation-profile.json'; profileRaw=profile.read_bytes(); (e/'installation-profile.json.before-anchor64').write_bytes(profileRaw); obj=json.loads(profileRaw); beforeCap=hashlib.sha256(raw).hexdigest(); afterCap=hashlib.sha256(p.read_bytes()).hexdigest(); assert obj['capabilitiesSha256']==beforeCap; oldToken='"capabilitiesSha256": "'+beforeCap+'"'; profileText=profileRaw.decode('utf-8'); assert profileText.count(oldToken)==1; newProfile=profileText.replace(oldToken,'"capabilitiesSha256": "'+afterCap+'"',1); expected=dict(obj); expected['capabilitiesSha256']=afterCap; assert json.loads(newProfile)==expected; profile.write_bytes(newProfile.encode('utf-8'))
proof={'taskId':'IMP-CON-NORMAL-ACTIVATION-RECOVERY-01','at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'originalPrefixes':record,'anchors':anchor,'sourceShaChanges':changes,'capOtherFieldsIncludingLinesAndPrivilegesIdentical':True,'sourceProfileOnlyCapabilitiesAggregateShaChanged':True,'beforeCapSha256':beforeCap,'afterCapSha256':afterCap,'primaryRuntimeOrSealedProfileEdited':False,'databaseOrPermissionOperations':0}
(e/'source-anchor-integration64.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8'); print('ORIGINAL_PREFIX_BYTES_AND24_LINE_ANCHORS_IDENTICAL; NEW_MEMBERS_APPEND_ONLY')
