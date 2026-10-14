import test from 'node:test';
import assert from 'node:assert/strict';
import {evaluateInventoryChecks} from './single-database-lifecycle-runtime.mjs';
const schema={objects:[{name:'T',type:'TABLE'},{name:'I_T',type:'INDEX'},{name:'S_T',type:'SEQUENCE'},{name:'V_T',type:'VIEW'}],columns:[{TABLE_NAME:'T',COLUMN_NAME:'ID',DATA_TYPE:'BIGINT',NULLABLE:'N'}],constraints:[{TABLE_NAME:'T',CONSTRAINT_NAME:'PK_T',CONSTRAINT_TYPE:'P'}],rows:[{table:'T',count:3}]};
test('actual type, column, nullability and constraint required rather than existing name alone',()=>{assert.equal(evaluateInventoryChecks(schema,[{kind:'object',name:'T',type:'TABLE',expected:'present'},{kind:'column',table:'T',name:'ID',dataType:'BIGINT',nullable:false,expected:'present'},{kind:'constraint',table:'T',name:'PK_T',type:'P',expected:'present'}],{post:true}),true);});
for(const [title,check] of [
 ['wrong object type',{kind:'object',name:'I_T',type:'TABLE',expected:'present'}],
 ['missing added column',{kind:'column',table:'T',name:'NEW_COLUMN',expected:'present'}],
 ['wrong column type',{kind:'column',table:'T',name:'ID',dataType:'VARCHAR',expected:'present'}],
 ['wrong nullable',{kind:'column',table:'T',name:'ID',nullable:true,expected:'present'}],
 ['wrong constraint type',{kind:'constraint',table:'T',name:'PK_T',type:'R',expected:'present'}],
 ['nonempty table rejected by empty precondition',{kind:'table-rows',table:'T',min:0,max:0,expected:'present'}],
 ['required nonempty data missing',{kind:'table-rows',table:'T',min:4,max:10,expected:'present'}]
])test(title,()=>assert.equal(evaluateInventoryChecks(schema,[check]),false));
test('actual nonempty range is preserved, not deleted to satisfy precheck',()=>assert.equal(evaluateInventoryChecks(schema,[{kind:'table-rows',table:'T',min:1,max:3,expected:'present'}]),true));
test('sequence and view types checked',()=>assert.equal(evaluateInventoryChecks(schema,[{kind:'object',name:'S_T',type:'SEQUENCE',expected:'present'},{kind:'object',name:'V_T',type:'VIEW',expected:'present'}]),true));
test('H2 port catalog field normalization remains explicit not proof of DM8',()=>{assert.equal(evaluateInventoryChecks({...schema,columns:[{TABLE_NAME:'T',COLUMN_NAME:'ID',DATA_TYPE:'BIGINT',IS_NULLABLE:'NO'}]},[{kind:'column',table:'T',name:'ID',nullable:false,expected:'present'}]),true);});
for(const [title,check] of [
 ['unknown condition',{kind:'sql',name:'T',expected:'present'}],
 ['unapproved condition field',{kind:'object',name:'T',expected:'present',sql:'DROP TABLE T'}],
 ['negative row bound',{kind:'table-rows',table:'T',min:-1,max:10,expected:'present'}],
 ['infinite row bound',{kind:'table-rows',table:'T',min:0,max:Infinity,expected:'present'}],
 ['unknown nullable contract',{kind:'column',table:'T',name:'ID',nullable:'false',expected:'present'}]
])test(title,()=>assert.throws(()=>evaluateInventoryChecks(schema,[check]),/INVALID/));
test('absence not allowed to claim successful final structure',()=>assert.throws(()=>evaluateInventoryChecks(schema,[{kind:'object',name:'MISSING',expected:'absent'}],{post:true}),/INVALID_STRUCTURAL_CHECK/));
test('row counts only explicit preconditions, not final business acceptance',()=>assert.throws(()=>evaluateInventoryChecks(schema,[{kind:'table-rows',table:'T',min:1,max:3,expected:'present'}],{post:true}),/INVALID_ROW_PRECONDITION/));

test('all declarations validated before DDL even if earlier desired object is absent',()=>assert.throws(()=>evaluateInventoryChecks(schema,[{kind:'object',name:'NEW',expected:'present'},{kind:'sql',name:'BAD',expected:'present'}],{post:true}),/INVALID_STRUCTURAL_CHECK/));
test('invalid column contract rejected even if column absent',()=>assert.throws(()=>evaluateInventoryChecks(schema,[{kind:'column',table:'T',name:'NEW',nullable:'yes',expected:'present'}]),/INVALID_NULLABILITY_CHECK/));

// Real orchestration through typed inventory checks; doubles remain NOT actual DM8 evidence.
import {mkdtemp,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {hash,canonical,inventoryDigest,executeLifecycle} from './single-database-lifecycle-runtime.mjs';
async function lifecycleFixture(){
 const schemas=['CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH'],accounts=schemas.flatMap(s=>[s,s+'_SVC']),targetId='a'.repeat(64);
 const live={format:'SINGLE_DATABASE_LIVE_INVENTORY/v1',targetId,actualJdbc:true,complete:true,errors:[],schemas:schemas.map(schema=>({schema,objects:[],columns:[],constraints:[],foreignKeys:[],indexes:[],sequences:[],views:[],triggers:[],dependencies:[],rows:[]})),accounts:accounts.map(name=>({name,exists:true,systemPrivileges:[],roleGrants:[],tableGrants:[],columnGrants:[],effectiveGrantSubjects:[name],effectiveGrants:[]}))};
 const m={schema:'CONAUTH',id:'V001',sqlPath:'db/identity-authority/V001__grant_watermark.sql',sha256:hash(await readFile('db/identity-authority/V001__grant_watermark.sql')),dependsOn:[],prechecks:[{kind:'object',name:'ID2_AUTHORITY_WATERMARK',expected:'absent'}],postchecks:[{kind:'object',name:'ID2_AUTHORITY_WATERMARK',type:'TABLE',expected:'present'},{kind:'column',table:'ID2_AUTHORITY_WATERMARK',name:'GENERATION',dataType:'BIGINT',nullable:false,expected:'present'}]};
 const manifest={targetId,schemas,accounts,mode:'empty',expectedInventorySha256:inventoryDigest(live),knownObjects:[],migrations:[m]};
 const approval={format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',targetId,action:'execute',writers:[{id:'unit',owner:'UNIT',responsibilitySource:'UNIT_PORT_ONLY'}],owner:'UNIT',source:'unit port only',approvedAt:'2026-10-07T00:00:00Z',expiresAt:'2099-01-01T00:00:00Z',currentMigration:false,connectionSwitch:false,deleteExisting:false,manifestSha256:hash(canonical(manifest))};
 const args={manifest,approval,workspaceRoot:process.cwd(),journalDir:await mkdtemp(path.join(tmpdir(),'single-typed-structure-')),inventory:async()=>structuredClone(live),acquireExecutionFence:async()=>({assertHeld:async()=>{},release:async()=>{}})};
 return {live,m,args,sign:()=>approval.manifestSha256=hash(canonical(manifest))};
}
test('executeLifecycle actually accepts and verifies typed final column shape',async()=>{const f=await lifecycleFixture();f.args.execute=async()=>{const s=f.live.schemas[1];s.objects.push({name:'ID2_AUTHORITY_WATERMARK',type:'TABLE'});s.columns.push({TABLE_NAME:'ID2_AUTHORITY_WATERMARK',COLUMN_NAME:'GENERATION',DATA_TYPE:'BIGINT',NULLABLE:'N'});};const receipt=await executeLifecycle(f.args);assert.equal(receipt.applied,1);});
test('existing table alone cannot hide missing column after exit zero',async()=>{const f=await lifecycleFixture();f.args.execute=async()=>f.live.schemas[1].objects.push({name:'ID2_AUTHORITY_WATERMARK',type:'TABLE'});await assert.rejects(executeLifecycle(f.args),/DDL_FAILED_PARTIAL_PRESERVED/);assert.ok(await readFile(path.join(f.args.journalDir,'failure-1.json')));});
test('unsupported later migration check rejected before any earlier DDL',async()=>{const f=await lifecycleFixture();f.m.postchecks.push({kind:'sql',name:'BAD',expected:'present'});f.sign();let calls=0;f.args.execute=async()=>{calls++;};await assert.rejects(executeLifecycle(f.args),/INVALID_STRUCTURAL_CHECK/);assert.equal(calls,0);});

import {original,splitForwardSql} from './single-database-lifecycle-runtime.mjs';
test('registered auxiliary original is exact existing source bytes, not guessed new DDL',async()=>{const bytes=await readFile('db/dm8/adjudication_source_authority.sql');const statements=await original(process.cwd(),{id:'A001',sqlPath:'db/dm8/adjudication_source_authority.sql',sha256:hash(bytes)});assert.equal(statements.filter(s=>/^INSERT/i.test(s)).length,1);assert.match(statements.find(s=>/^INSERT/i.test(s)),/VALUES\(1,0,'READY'\)/);});
test('ordinary forward parser still refuses the authority seed',()=>assert.throws(()=>splitForwardSql("INSERT INTO adj2_source_generation VALUES(1,0,'READY');"),/FORWARD_ONLY_SQL_REQUIRED/));
for(const sql of ["INSERT INTO adj2_source_generation VALUES(1,1,'READY')","INSERT INTO adj2_source_generation VALUES(1,0,'PENDING')","INSERT INTO id2_subject VALUES(1)","DELETE FROM adj2_source_generation","UPDATE adj2_source_generation SET state='READY'"])test('controlled seed does not open generic DML: '+sql,()=>assert.throws(()=>splitForwardSql(sql,{adjudicationAuthorityInitialization:true}),/FORWARD_ONLY_SQL_REQUIRED/));
test('auxiliary id cannot alias any other source SQL',async()=>await assert.rejects(original(process.cwd(),{id:'A001',sqlPath:'db/identity-authority/V001__grant_watermark.sql',sha256:'a'.repeat(64)}),/EXACT_ORIGINAL_SQL_REQUIRED/));
test('auxiliary digest conflict refused',async()=>await assert.rejects(original(process.cwd(),{id:'A001',sqlPath:'db/dm8/adjudication_source_authority.sql',sha256:'a'.repeat(64)}),/CONTROLLED_AUXILIARY_ORIGINAL_REQUIRED/));

async function auxiliaryFixture(){const f=await lifecycleFixture();Object.assign(f.m,{id:'A001',sqlPath:'db/dm8/adjudication_source_authority.sql',sha256:hash(await readFile('db/dm8/adjudication_source_authority.sql')),prechecks:['ADJ2_SOURCE_GENERATION','ADJ2_SOURCE_USE','ADJ2_SOURCE_EVENT'].map(name=>({kind:'object',name,expected:'absent'})),postchecks:['ADJ2_SOURCE_GENERATION','ADJ2_SOURCE_USE','ADJ2_SOURCE_EVENT'].map(name=>({kind:'object',name,type:'TABLE',expected:'present'}))});f.sign();return f;}
test('A001 empty initialization consumed once with exact initial seed; preserves original bytes',async()=>{const f=await auxiliaryFixture();let calls=0;f.args.execute=async(schema,statements,m)=>{calls++;assert.equal(m.id,'A001');assert.ok(statements.some(s=>/^INSERT/i.test(s)));const s=f.live.schemas[1];s.objects=['ADJ2_SOURCE_GENERATION','ADJ2_SOURCE_USE','ADJ2_SOURCE_EVENT'].map(name=>({name,type:'TABLE'}));s.rows=s.objects.map(t=>({table:t.name,count:t.name==='ADJ2_SOURCE_GENERATION'?1:0,contentSha256:t.name==='ADJ2_SOURCE_GENERATION'?hash(canonical([hash(canonical({GENERATION:'0',SINGLETON_KEY:'1',STATE:'READY'}))])):hash(canonical([]))}));};const r=await executeLifecycle(f.args);assert.equal(r.applied,1);const again=await executeLifecycle(f.args);assert.equal(again.skipped,1);assert.equal(calls,1);});
test('A001 tool zero without seed row preserves partial failure scene',async()=>{const f=await auxiliaryFixture();f.args.execute=async()=>{const s=f.live.schemas[1];s.objects=['ADJ2_SOURCE_GENERATION','ADJ2_SOURCE_USE','ADJ2_SOURCE_EVENT'].map(name=>({name,type:'TABLE'}));s.rows=s.objects.map(t=>({table:t.name,count:0,contentSha256:hash(canonical([]))}));};await assert.rejects(executeLifecycle(f.args),/DDL_FAILED_PARTIAL_PRESERVED/);});
test('A001 is not an existing environment initialization shortcut',async()=>{const f=await auxiliaryFixture();f.args.manifest.mode='existing';f.sign();let calls=0;f.args.execute=async()=>{calls++;};await assert.rejects(executeLifecycle(f.args),/AUXILIARY_INITIALIZATION_REJECTS_EXISTING/);assert.equal(calls,0);});
test('A001 missing exact three-table postconditions rejected before DDL',async()=>{const f=await auxiliaryFixture();f.m.postchecks.pop();f.sign();let calls=0;f.args.execute=async()=>{calls++;};await assert.rejects(executeLifecycle(f.args),/AUXILIARY_EXACT_TABLE_CHECKS_REQUIRED/);assert.equal(calls,0);});
