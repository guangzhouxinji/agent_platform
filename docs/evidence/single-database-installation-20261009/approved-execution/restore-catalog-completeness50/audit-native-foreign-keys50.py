import json,datetime,hashlib,sys
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
B=Path('docs/evidence/single-database-installation-20261009/approved-execution');E=B/'restore-catalog-completeness50'
d=json.loads((E/'installed-catalog-probe50.raw.json').read_text());p=json.loads((B/'restore-equivalence49/installed-catalog-probe49.raw.json').read_text());v=json.loads((B/'native-parameters48/current-full-inventory48.json').read_text());idx={i['INDEX_ID']:i for i in p['nativeIndexes']['rows']};par={c['INDEXID']:c for c in d['nativeConstraints']['rows'] if c['TYPE$'] in ['P','U']};objects={o['ID']:o for o in d['nativeObjects']['rows']};ics={(s['schema'],i['INDEX_NAME']):sorted([r for r in s['indexColumns'] if r['INDEX_NAME']==i['INDEX_NAME']],key=lambda r:r['COLUMN_POSITION']) for s in v['schemas'] for i in s['indexes']};com={(s['schema'],c['CONSTRAINT_NAME']):c for s in v['schemas'] for c in s['constraints']};groups=[];pairs=[];missing=[]
for c in d['nativeConstraints']['rows']:
 if c['TYPE$']!='F':continue
 own=idx[c['INDEXID']];ref=idx[c['FINDEXID']];parent=par[c['FINDEXID']];a=ics[(own['SCHEMA_NAME'],own['INDEX_NAME'])];b=ics[(ref['SCHEMA_NAME'],ref['INDEX_NAME'])];assert len(a)==len(b)==own['KEYNUM']==ref['KEYNUM'] and a and c['FACTION']=='  '
 cc=com[(c['SCHEMA_NAME'],c['CONSTRAINT_NAME'])];assert cc['R_OWNER']==parent['SCHEMA_NAME'] and cc['R_CONSTRAINT_NAME']==parent['CONSTRAINT_NAME'] and cc['INDEX_NAME']==ref['INDEX_NAME'] and cc['DELETE_RULE']=='NO ACTION'
 actual=[f for s in v['schemas'] if s['schema']==c['SCHEMA_NAME'] for f in s['foreignKeys'] if f['FK_NAME']==c['CONSTRAINT_NAME']];rows=[]
 for pos,(aa,bb) in enumerate(zip(a,b),1):
  r={'schema':c['SCHEMA_NAME'],'constraint':c['CONSTRAINT_NAME'],'table':objects[c['TABLEID']]['NAME'],'column':aa['COLUMN_NAME'],'position':pos,'referencedSchema':ref['SCHEMA_NAME'],'referencedTable':ref['TABLE_NAME'],'referencedConstraint':parent['CONSTRAINT_NAME'],'referencedColumn':bb['COLUMN_NAME'],'referencedConstraintType':parent['TYPE$'],'nativeAction':c['FACTION']};rows.append(r);pairs.append(r)
 if actual:
  assert len(actual)==len(rows) and parent['TYPE$']=='P'
  for r in rows:
   matches=[f for f in actual if int(f['KEY_SEQ'])==r['position']];assert len(matches)==1;f=matches[0];assert f['FKTABLE_SCHEM']==r['schema'] and f['FKTABLE_NAME']==r['table'] and f['FKCOLUMN_NAME']==r['column'] and f['PKTABLE_SCHEM']==r['referencedSchema'] and f['PKTABLE_NAME']==r['referencedTable'] and f['PKCOLUMN_NAME']==r['referencedColumn'] and f['PK_NAME']==r['referencedConstraint'] and f['UPDATE_RULE']==f['DELETE_RULE']=='3'
 else:
  assert parent['TYPE$']=='U';missing.append({'schema':c['SCHEMA_NAME'],'constraint':c['CONSTRAINT_NAME'],'table':objects[c['TABLEID']]['NAME'],'referencedConstraintType':'U','columnCount':len(rows)})
 groups.append({'schema':c['SCHEMA_NAME'],'constraint':c['CONSTRAINT_NAME'],'nativeConstraintId':c['ID'],'orderedColumns':rows,'jdbcPresent':bool(actual)})
assert len(groups)==281 and len(missing)==62
r={'format':'DM8_NATIVE_FK_COMPLETE_ORDINAL_EVIDENCE/v1','capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'databaseVersion':'8.1.4.170','nativeFkCount':281,'nativeOrderedColumnPairs':len(pairs),'jdbcDistinctFkCount':219,'jdbcMissingFkCount':62,'allMissingReferenceUniqueConstraint':True,'allPresentReferencePrimaryConstraint':True,'nativeActionsAllTwoSpaces':True,'allPresentJdbcRowsMatchedNativeAndCompatibilityCatalogs':True,'scope':'observed installed driver/catalog coverage only; no claim about all versions or professional correctness','missing':missing,'groups':groups,'allOrderedPairs':pairs}
(E/'native-foreign-key-coverage50.json').write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Native FK complete:',len(groups),'constraints,',len(pairs),'ordered pairs; JDBC misses',len(missing),'UNIQUE-target FKs.')
