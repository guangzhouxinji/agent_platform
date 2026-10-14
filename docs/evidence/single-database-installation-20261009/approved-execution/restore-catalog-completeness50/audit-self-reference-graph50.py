import json,re,hashlib,datetime,sys
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
B=Path('docs/evidence/single-database-installation-20261009/approved-execution');E=B/'restore-catalog-completeness50';d=json.loads((E/'installed-catalog-probe50.raw.json').read_text());p49=json.loads((B/'restore-equivalence49/installed-catalog-probe49.raw.json').read_text());inv=json.loads((B/'native-parameters48/current-full-inventory48.json').read_text());proof=json.loads((E/'controlled-origin-proof50.json').read_text())
idx={i['INDEX_ID']:i for i in p49['nativeIndexes']['rows']};objects={o['ID']:o for o in d['nativeObjects']['rows']};texts={t['ID']:t for t in d['hiddenTexts']['rows']};cols=d['hiddenColumns']['rows'];groups=[];consumed=set();usedText=set();usedCols=set()
# A quoted SQL literal is atomic; generated-name matches inside literals are not rewritten.
tok=re.compile(r"'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|[A-Za-z_][A-Za-z_0-9$]*|\d+|[^\s]")
for c in d['nativeConstraints']['rows']:
 if c['TYPE$']!='F' or c['INDEXID'] not in idx or c['FINDEXID'] not in idx or idx[c['INDEXID']]['TABLE_ID']!=idx[c['FINDEXID']]['TABLE_ID']:continue
 schema=c['SCHEMA_NAME'];cid=c['ID'];table=objects[c['TABLEID']]['NAME'];s=next(s for s in inv['schemas'] if s['schema']==schema);fkproof=json.loads((E/'native-foreign-key-coverage50.json').read_text());nativepairs=next(g['orderedColumns'] for g in fkproof['groups'] if g['schema']==schema and g['constraint']==c['CONSTRAINT_NAME']);fks=[{'KEY_SEQ':str(r['position']),'FKTABLE_NAME':r['table'],'PKTABLE_NAME':r['referencedTable'],'FKCOLUMN_NAME':r['column'],'PKCOLUMN_NAME':r['referencedColumn']} for r in nativepairs];assert fks and all(f['FKTABLE_NAME']==f['PKTABLE_NAME']==table for f in fks)
 tables={role:next(o for o in objects.values() if o['SCHEMA_NAME']==schema and o['NAME']==f'SREF_CON_TAB{cid}_{role}') for role in ['LEVEL','REFING','REFED']};triggers={role:next(o for o in objects.values() if o['SCHEMA_NAME']==schema and o['NAME']==f'SREF_CON_TRIG{cid}_{role}') for role in ['BS','AR','AS']};mapping={o['NAME']:('@TABLE_'+r) for r,o in tables.items()};mapping.update({o['NAME']:('@TRIGGER_'+r) for r,o in triggers.items()});nodes=[]
 for role,o in tables.items():
  assert o['TYPE$']=='SCHOBJ' and o['SUBTYPE$']=='UTAB' and o['PID']==-1 and o['INFO3']&0x100000000 and o['INFO3']&0x40 and not(o['INFO3']&0x10000) and o['VALID']=='Y';cc=sorted([x for x in cols if x['ID']==o['ID']],key=lambda x:x['COLID']);assert cc and [x['COLID'] for x in cc]==list(range(len(cc)))
  if role=='LEVEL':assert len(cc)==1 and cc[0]['NAME']=='N_LEVEL' and cc[0]['TYPE$']=='INTEGER'
  else:
   field='FKCOLUMN_NAME' if role=='REFING' else 'PKCOLUMN_NAME';assert [x['NAME'] for x in cc]==['COL'+str(i+1) for i in range(len(fks))]
   for i,x in enumerate(cc):
    orig=next(col for col in s['columns'] if col['TABLE_NAME']==table and col['COLUMN_NAME']==fks[i][field]);assert x['TYPE$']==orig['DATA_TYPE'] and x['LENGTH$']==orig['DATA_LENGTH'] and x['SCALE']==orig['DATA_SCALE']
  assert all(x['DEFVAL'] is None and x['NULLABLE$']=='Y' for x in cc);consumed.add(o['ID']);usedCols.update((x['ID'],x['COLID']) for x in cc)
  ixs=[i for i in idx.values() if i['TABLE_ID']==o['ID']];assert len(ixs)==1 and ixs[0]['XTYPE']==0 and ixs[0]['FLAG']==9 and ixs[0]['KEYNUM']==0
  nodes.append({'role':role,'kind':'TABLE','object':o,'columns':cc,'clusterIndex':ixs[0]})
 for role,o in triggers.items():
  assert o['TYPE$']=='SCHOBJ' and o['SUBTYPE$']=='TRIG' and o['INFO1']&32 and o['VALID']=='Y';t=texts[o['ID']];assert t['SEQNO']==0 and t['TXT'];tokens=[m.group() for m in tok.finditer(t['TXT'])];observed={v for v in tokens if v in mapping};assert o['NAME'] in observed and all(not(v.startswith("'") and any(n in v for n in mapping)) for v in tokens)
  refs=sorted(n for n in observed if n in {t['NAME'] for t in tables.values()});assert set(refs)=={t['NAME'] for t in tables.values()}-({tables['LEVEL']['NAME']} if role=='AR' else set())
  assert re.search(r'ON\s+"'+schema+r'"\."'+table+r'"',t['TXT'])
  normalized=[mapping.get(v,v) for v in tokens];consumed.add(o['ID']);usedText.add(o['ID']);nodes.append({'role':role,'kind':'TRIGGER','object':o,'textSha256':hashlib.sha256(t['TXT'].encode()).hexdigest(),'normalizedTokenSha256':hashlib.sha256(json.dumps(normalized,ensure_ascii=False,separators=(',',':')).encode()).hexdigest(),'generatedObjectReferences':refs,'tokenCount':len(tokens)})
 groups.append({'schema':schema,'constraint':c['CONSTRAINT_NAME'],'constraintId':cid,'table':table,'nativeConstraint':c,'orderedForeignKeys':fks,'nodes':nodes,'generatedNameMappingSupportedOnlyAfterNativeFlagsAndCompleteGraph':True})
hidden={o['ID'] for o in objects.values() if o['TYPE$']=='SCHOBJ' and ((o['SUBTYPE$']=='UTAB' and o['INFO3']&0x100000000) or (o['SUBTYPE$']=='TRIG' and o['INFO1']&32))};assert consumed==hidden and len(hidden)==24 and len(groups)==4 and usedText==set(texts) and usedCols=={(x['ID'],x['COLID']) for x in cols};assert d['hiddenDependencies']['ok'] and not d['hiddenDependencies']['rows'] and d['hiddenObjectInfos']['ok'] and not d['hiddenObjectInfos']['rows']
(E/'self-reference-graph50.json').write_text(json.dumps({'format':'DM8_SELF_REFERENCE_COMPLETE_DICTIONARY_GRAPH/v1','capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'databaseVersion':d['databaseVersion'],'sourceOriginProofFormat':proof['format'],'groupCount':4,'hiddenTableCount':12,'hiddenTriggerCount':12,'hiddenColumnCount':18,'allHiddenObjectsCoveredExactlyOnce':True,'allTextFragmentsCoveredExactlyOnce':True,'dependenciesAndObjectInfosSuccessfullyEmpty':True,'groups':groups,'scope':'actual source graph and all original trigger text hashes only; no target comparison or product normalization'},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('All 4 graphs / 24 objects / 18 columns / 12 complete trigger texts validated; no dropped metadata.')
