from pathlib import Path
p=Path('scripts/lib/single-database-native-fence-container.mjs');s=p.read_text(encoding='utf-8');old="  if(!Object.values(db.NetworkSettings?.Networks||{}).some(n=>n.NetworkID===p.networkId&&(n.Aliases||[]).includes(alias)))fail('EXACT_CONTAINER_FENCE_ALIAS_REQUIRED');"
new="""  if(!Object.values(db.NetworkSettings?.Networks||{}).some(n=>{
    // Current inspect metadata is authoritative; never mask malformed DNSNames with old Aliases.
    const names=Object.hasOwn(n,'DNSNames')?n.DNSNames:n.Aliases;
    return n.NetworkID===p.networkId&&Array.isArray(names)&&names.length>0&&
      names.every(name=>typeof name==='string'&&name.length>0)&&names.includes(alias);
  }))fail('EXACT_CONTAINER_FENCE_ALIAS_REQUIRED');"""
assert s.count(old)==1;p.write_text(s.replace(old,new),encoding='utf-8')
p=Path('scripts/lib/single-database-native-fence-container.test.mjs')
with p.open('a',encoding='utf-8') as f:
 f.write('''

test('actual Docker DNSNames with null Aliases binds only the exact approved network and JDBC alias',()=>{
 const f=fixture();f.db.NetworkSettings.Networks.owned.Aliases=null;
 f.db.NetworkSettings.Networks.owned.DNSNames=['owned-dm','aaaaaaaaaaaa'];
 assert.equal(containerFenceInvocation(f.c,f).command,'docker');
});
for(const [name,names] of [['null',null],['empty',[]],['string','owned-dm'],['wrong',['other-dm']],['substring',['owned-dm-extra']],['mixed',['owned-dm',123]],['blank',['owned-dm','']]])
 test(`authoritative DNSNames ${name} refuses despite matching legacy Aliases`,()=>{
  const f=fixture();f.db.NetworkSettings.Networks.owned.DNSNames=names;
  assert.throws(()=>containerFenceInvocation(f.c,f),/EXACT_CONTAINER_FENCE_ALIAS_REQUIRED/);
 });
test('matching DNSNames on a different network and global container Name do not authorize fence alias',()=>{
 const f=fixture();f.db.Name='/owned-dm';f.db.NetworkSettings.Networks.owned={NetworkID:network,Aliases:null,DNSNames:['other-dm']};
 f.db.NetworkSettings.Networks.foreign={NetworkID:'f'.repeat(64),DNSNames:['owned-dm']};
 assert.throws(()=>containerFenceInvocation(f.c,f),/EXACT_CONTAINER_FENCE_ALIAS_REQUIRED/);
});
test('old inspect without DNSNames retains exact Aliases binding',()=>{
 const f=fixture();assert.equal(Object.hasOwn(f.db.NetworkSettings.Networks.owned,'DNSNames'),false);
 assert.equal(containerFenceInvocation(f.c,f).command,'docker');
});
''')
