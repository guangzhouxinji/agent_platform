#!/usr/bin/python3
"""Exact final single-DM8 entry. No default credentials, fallback or destructive reinitialization."""
import os, re, subprocess, sys, json
assert os.getuid()==1000
for line in open('/proc/self/status'):
    if line.startswith('Cap'): assert int(line.split()[1],16)==0
role=os.environ['SINGLE_DB_TARGET_ROLE'];assert role in ('primary','restore')
index={'primary':491040,'restore':491041}[role]
assert open('/opt/dmdbms/data/.single-final-owner').read()=='IMP-SINGLE-DB-FINAL-BASELINE-01\n'+role+'\n'+str(index)+'\n'
ini='/opt/dmdbms/data/SINGLE01/dm.ini'
if not os.path.exists(ini):
    assert role=='primary', 'RESTORE_TARGET_REQUIRES_NATIVE_RESTORE_NOT_DMINIT'
    request=json.loads(sys.stdin.readline())
    names=sorted(os.listdir('/opt/dmdbms/data'))
    if names!=['.single-final-owner']:
        assert request.get('emptyDirectoryRecoveryEvidence')=='primary-partial-no-database-files03.json'
        assert names==['.single-final-owner','SINGLE01']
        assert sorted(os.listdir('/opt/dmdbms/data/SINGLE01'))==['bak'] and not os.listdir('/opt/dmdbms/data/SINGLE01/bak'), 'PARTIAL_DMINIT_REQUIRES_EXPLICIT_RECOVERY'
    secrets=[request['administratorPassword'],request['auditorPassword']]
    assert all(re.fullmatch('[A-Za-z0-9_]{32,48}',s) for s in secrets)
    result=subprocess.run(['/opt/dmdbms/bin/dminit','PATH=/opt/dmdbms/data','DB_NAME=SINGLE01','INSTANCE_NAME=SINGLE01','SYSDBA_PWD='+secrets[0],'SYSAUDITOR_PWD='+secrets[1],'PAGE_SIZE=32','EXTENT_SIZE=32','CHARSET=1','CASE_SENSITIVE=1','BLANK_PAD_MODE=1','LOG_SIZE=256'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    output=result.stdout
    for s in secrets:output=output.replace(s,'[REDACTED]')
    print(output,flush=True)
    assert result.returncode==0 and os.path.isfile(ini), 'DMINIT_FAILED_PARTIAL_PRESERVED'
    text=open(ini).read()
    for key,value in [('BUFFER','128'),('MEMORY_POOL','128'),('MEMORY_N_POOLS','1')]:
        text,count=re.subn(r'(?m)^(\s*'+key+r'\s*=\s*)[^#\r\n]*',lambda m:m.group(1)+value+' ',text);assert count==1
    with open(ini,'w') as out:out.write(text)
    del request,secrets,output,result
print('SINGLE_FINAL_DM8_START_UID1000_CAPS0_'+role,flush=True)
os.execl('/opt/dmdbms/bin/dmserver','dmserver',ini)
