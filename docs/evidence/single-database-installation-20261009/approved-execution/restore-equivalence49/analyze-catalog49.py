import json,hashlib,collections,datetime
from pathlib import Path
B=Path('docs/evidence/single-database-installation-20261009/approved-execution'); E=B/'restore-equivalence49'
raw=E/'installed-catalog-probe49.raw.json'; source=B/'native-parameters48/current-full-inventory48.json'
d=json.loads(raw.read_text(encoding='utf-8')); inv=json.loads(source.read_text(encoding='utf-8'))
assert all(d[k]['ok'] for k in ['viewDefinitions','indexExpressions','nativeIndexes','nativeObjects','nativeConstraints']) and 'connectionFailure' not in d
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
ni={r['INDEX_ID']:r for r in d['nativeIndexes']['rows']}; no={r['ID']:r for r in d['nativeObjects']['rows']}; nc=d['nativeConstraints']['rows']; owners=collections.defaultdict(list)
for c in nc:owners[c['INDEXID']].append(c)
assert len(ni)==986 and len(no)==2282
summary=[]; allchecks=[]
for s in inv['schemas']:
 name=s['schema']; cidx={(r['SCHEMA_NAME'],r['CONSTRAINT_NAME']):r for r in nc}; nidx={(r['SCHEMA_NAME'],r['INDEX_NAME']):r for r in ni.values()}
 tableNames={r['name'] for r in s['objects'] if r['type']=='TABLE'}; indexes={r['INDEX_NAME']:r for r in s['indexes']}
 native=[r for r in ni.values() if r['SCHEMA_NAME']==name]; columns=collections.defaultdict(list)
 for row in s['indexColumns']:columns[row['INDEX_NAME']].append(row)
 checks={
 'allConstraintCreationTimestampsMatch':all(c['LAST_CHANGE']==no[cidx[(name,c['CONSTRAINT_NAME'])]['ID']]['CRTDATE'] for c in s['constraints']),
 'nativeIndexNamesExactlyMatch':set(indexes)=={r['INDEX_NAME'] for r in native},
 'allGeneratedFlagsMatchNativeFlagBit0':all(indexes[r['INDEX_NAME']]['GENERATED']==('Y' if r['FLAG']&1 else 'N') for r in native),
 'allKeyCountsMatchCollectedIndexColumns':all(r['KEYNUM']==len(columns[r['INDEX_NAME']]) for r in native),
 'allNonemptyIndexPositionsContiguous':all(sorted(x['COLUMN_POSITION'] for x in rows)==list(range(1,len(rows)+1)) for rows in columns.values()),
 'allIndexColumnsReferenceExistingTableColumns':all(any(col['TABLE_NAME']==r['TABLE_NAME'] and col['COLUMN_NAME']==r['COLUMN_NAME'] for col in s['columns']) for r in s['indexColumns']),
 }
 allchecks.append(checks)
 hidden=[r for r in d['nativeObjects']['rows'] if r['SCHEMA_NAME']==name and ((r['SUBTYPE$']=='UTAB' and r['NAME'] not in tableNames) or (r['SUBTYPE$']=='TRIG' and r['INFO1']&32))]
 selfrefs=[c for c in nc if c['SCHEMA_NAME']==name and c['TYPE$']=='F' and c['INDEXID'] in ni and c['FINDEXID'] in ni and ni[c['INDEXID']]['TABLE_ID']==ni[c['FINDEXID']]['TABLE_ID']]
 summary.append({'schema':name,'checks':checks,'nativeIndexCount':len(native),'zeroKeyIndexes':sum(r['KEYNUM']==0 for r in native),'hiddenNativeObjects':hidden,'selfReferentialConstraints':selfrefs})
assert all(all(c.values()) for c in allchecks)
report={'format':'DM8_INSTALLED_CATALOG_SEMANTIC_EVIDENCE/v1','capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'responsibility':'Codex delegated technical analysis; not a human/business decision','databaseVersion':d['databaseVersion'],'probeRawSha256':sha(raw),'sourceInventoryFileSha256':sha(source),'sourceInventorySha256':inv['inventorySha256'],'sourcePackageSha256':'9480f1d388930195d32a9157690be441c5e1c1401fe6834345af007f42254c18','scope':'read-only point-in-time metadata evidence; not an authenticated addition to snapshot48','installedManual':{'containerPath':'/opt/dmdbms/doc/DM8系统管理员手册.pdf','sha256':sha(Path('tmp/pdfs/catalog49/admin.pdf')),'pdfCreationDate':'2025-11-14T08:29:02+08:00','pages':{'CRTDATE':{'physical':597,'printed':581},'XTYPE_FLAG_KEYNUM':{'physical':[603,604],'printed':[587,588]},'SYSCONS_links_actions':{'physical':606,'printed':590}},'visualCheckedPhysicalPages':[603,604],'wholeManualNotDelivered':True},'viewDefinitionSha256':{v['VIEW_NAME']:hashlib.sha256(v['TEXT'].encode()).hexdigest() for v in d['viewDefinitions']['rows']},'conclusions':{'lastChangeIsObjectCreationTimeInThisInstalledView':True,'constraintGeneratedIsLiteralNull':True,'funcidxStatusIsLiteralNullNotEvidenceOfNoExpressions':True,'expressionQuerySucceededCount':len(d['indexExpressions']['rows']),'nativeFunctionFlagCount':sum(bool(x['XTYPE']&2) for x in ni.values()),'generatedIndexes':954,'constraintOwnedUniqueIndexes':len([i for i in owners if i in ni]),'multiplyOwnedIndexes':len([i for i in owners if i in ni and len(owners[i])!=1]),'unownedSystemClusterIndexes':sum(x['FLAG']&1 and x['XTYPE']==0 and i not in owners for i,x in ni.items()),'manualIndexes':32,'constraintNameEqualsConsPlusId':sum(c['CONSTRAINT_NAME']=='CONS'+str(c['ID']) for c in nc),'prefixOrIdDoesNotProveUnnamedDeclaration':True,'snapshot48DoesNotContainTheseAdditionalCatalogCategories':True,'restoreExecuted':False,'normalizationApproved':False},'schemas':summary,'remainingTechnicalQuestions':['Bind minimal native/expressions/hidden-object evidence into authenticated source and destination inventory rather than retroactively treating this sidecar as snapshot48 contents.','Prove original unnamed constraint declarations (292 CONS+ID matches are not sufficient authority); reject explicit names or ambiguous mapping.','Define complete one-to-one self-reference internal-table/trigger graph including columns and trigger semantics; never drop SREF-prefixed objects.','Approve a bounded per-field comparison contract and executable positive/negative matrix; source drift/package digest remain raw and unchanged.']}
(E/'installed-catalog-semantics49.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'schemaChecksAllPassed':True,'nativeIndexes':len(ni),'nativeObjects':len(no),'constraintOwnedIndexes':report['conclusions']['constraintOwnedUniqueIndexes'],'sourceBinding':'supplemental only, not snapshot contents'},indent=2))
