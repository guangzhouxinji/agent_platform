#!/usr/bin/python3
"""Fresh approved logical-import target only. Never a primary initializer or re-entry."""
import datetime, json, os, re, stat, subprocess, sys, hashlib
TASK = 'IMP-SINGLE-DB-FINAL-BASELINE-01'
TARGET = 'agent-platform-single-final-restore-20261009'
SOURCE = 'f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f'
PACKAGE = '92be34d7501e03ffa4799530fd4d77ddcda4bb7c1d51c4b9f2f047b83f2057b2'
MARKER = TASK + '\nrestore\n491041\n'
BASE = '/opt/dmdbms/data'
INI = BASE + '/SINGLE01/dm.ini'
def require(condition, code):
    if not condition: raise ValueError(code)
def initialize_arguments(request, role, name, marker, names, now):
    require(role == 'restore' and name == TARGET, 'EXACT_RESTORE_ROLE_REQUIRED')
    require(marker == MARKER and names == ['.single-final-owner'], 'FRESH_OWNED_RESTORE_REQUIRED')
    require(isinstance(request, dict) and set(request) == {'taskId','packageSha256','sourceId','sourcePidZero','administratorPassword','auditorPassword'}, 'EXACT_INIT_REQUEST_REQUIRED')
    require(request['taskId'] == TASK and request['packageSha256'] == PACKAGE and request['sourceId'] == SOURCE and request['sourcePidZero'] is True, 'EXACT_SOURCE_STOP_AND_PACKAGE_REQUIRED')
    require(now < datetime.datetime.fromisoformat('2026-10-10T17:10:00+08:00') and now >= datetime.datetime.fromisoformat('2026-10-08T19:00:00+00:00'), 'RESTORE_INIT_WINDOW_CLOSED')
    secrets = [request['administratorPassword'],request['auditorPassword']]
    require(all(isinstance(s,str) and re.fullmatch('[A-Za-z0-9_]{32,48}',s) for s in secrets) and secrets[0] != secrets[1], 'MANAGED_DISTINCT_INIT_SECRETS_REQUIRED')
    return ['/opt/dmdbms/bin/dminit','PATH='+BASE,'DB_NAME=SINGLE01','INSTANCE_NAME=SINGLE01','SYSDBA_PWD='+secrets[0],'SYSAUDITOR_PWD='+secrets[1],'PAGE_SIZE=32','EXTENT_SIZE=32','CHARSET=1','CASE_SENSITIVE=1','BLANK_PAD_MODE=1','LOG_SIZE=256']
CONTINUATION_SHA55 = 'de9b2d73e1406ca59388a115c371e6813651e3866be074ddcd04e2e3375f71b6'
def existing_start_admission(request, now):
    base={'mode','taskId','packageSha256','sourceId','sourcePidZero','restartProofSha256'}
    require(isinstance(request,dict), 'EXPLICIT_COMPLETED_INIT_START_REQUIRED')
    if request.get('mode') == 'START_EXISTING_IMPORTED_ACTR86':
        require(set(request) == base | {'continuationApprovalSha256','targetNeverImported','runId','importJournalSha256','scopeApprovalSha256'}, 'EXPLICIT_IMPORTED_START_REQUIRED')
        require(request['taskId']==TASK and request['sourceId']==SOURCE and request['sourcePidZero'] is True and request['restartProofSha256']=='4c9c608cb9728540c73bdcb3964c1dcc6c7d86821dd90a00a47486526052d718', 'EXACT_COMPLETED_INIT_PROOF_REQUIRED')
        require(request['targetNeverImported'] is False and request['continuationApprovalSha256']==CONTINUATION_SHA55 and request['importJournalSha256']=='97bc275b613ce8a801d5ea64715c194a3e0244ea940f1f2a9fb65e0a9b3324e4' and request['scopeApprovalSha256']=='f0c07ab3cc90059055d7190886f1909a63b7edf30db5c86f455551fff857b4d0', 'EXACT_IMPORTED_SCOPE_REQUIRED')
        require(isinstance(request['runId'],str) and re.fullmatch('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}',request['runId']), 'EXACT_IMPORTED_RUN_REQUIRED')
        require(isinstance(request['packageSha256'],str) and re.fullmatch('[a-f0-9]{64}',request['packageSha256']) and request['packageSha256'] not in [PACKAGE,'9480f1d388930195d32a9157690be441c5e1c1401fe6834345af007f42254c18'], 'FRESH_CURRENT_SOURCE_PACKAGE_REQUIRED')
        require(datetime.datetime.fromisoformat('2026-10-10T18:28:00.915863+08:00') <= now < datetime.datetime.fromisoformat('2026-10-13T18:00:00+08:00'), 'CONTINUATION_RESTORE_WINDOW_CLOSED')
        return ['/opt/dmdbms/bin/dmserver','dmserver',INI]
    continuation=request.get('mode') == 'START_EXISTING_CONTINUATION_RESTORE55'
    require(set(request) == (base | {'continuationApprovalSha256','targetNeverImported'} if continuation else base), 'EXPLICIT_COMPLETED_INIT_START_REQUIRED')
    require(request['taskId']==TASK and request['sourceId']==SOURCE and request['sourcePidZero'] is True and request['restartProofSha256']=='4c9c608cb9728540c73bdcb3964c1dcc6c7d86821dd90a00a47486526052d718', 'EXACT_COMPLETED_INIT_PROOF_REQUIRED')
    if continuation:
        require(request['continuationApprovalSha256']==CONTINUATION_SHA55 and request['targetNeverImported'] is True, 'VERIFIED_CONTINUATION_EMPTY_TARGET_REQUIRED')
        require(isinstance(request['packageSha256'],str) and re.fullmatch('[a-f0-9]{64}',request['packageSha256']) and request['packageSha256'] not in [PACKAGE,'9480f1d388930195d32a9157690be441c5e1c1401fe6834345af007f42254c18'], 'FRESH_CURRENT_SOURCE_PACKAGE_REQUIRED')
        require(datetime.datetime.fromisoformat('2026-10-10T18:28:00.915863+08:00') <= now < datetime.datetime.fromisoformat('2026-10-13T18:00:00+08:00'), 'CONTINUATION_RESTORE_WINDOW_CLOSED')
    else:
        require(request['mode']=='START_EXISTING_APPROVED_RESTORE' and request['packageSha256']==PACKAGE, 'EXACT_COMPLETED_INIT_PROOF_REQUIRED')
        require(now < datetime.datetime.fromisoformat('2026-10-10T17:30:00+08:00'), 'EXISTING_RESTORE_START_WINDOW_CLOSED')
    return ['/opt/dmdbms/bin/dmserver','dmserver',INI]

def parse_start_request(raw):
    require(len(raw) <= 4096, 'BOUNDED_INIT_REQUEST_REQUIRED')
    require(bool(raw), 'RESTORE_START_INPUT_EMPTY')
    try:
        return json.loads(raw)
    except (ValueError, UnicodeError):
        raise ValueError('RESTORE_START_INPUT_INVALID_JSON') from None

def main():
    require(os.getuid() == 1000, 'NONROOT_RESTORE_REQUIRED')
    for line in open('/proc/self/status'):
        if line.startswith('Cap'): require(int(line.split()[1],16) == 0, 'ZERO_CAPABILITIES_REQUIRED')
    for path,mode in [(BASE,0o700),(BASE+'/.single-final-owner',0o400)]:
        s=os.lstat(path);require(s.st_uid == 1000 and stat.S_IMODE(s.st_mode)==mode and not stat.S_ISLNK(s.st_mode), 'EXACT_OWNER_PERMISSIONS_REQUIRED')
    raw=sys.stdin.buffer.readline(4097)
    request=parse_start_request(raw)
    if os.path.isfile(INI):
        require(os.environ.get('SINGLE_DB_TARGET_ROLE') == 'restore' and os.environ.get('SINGLE_DB_TARGET_NAME') == TARGET, 'EXACT_RESTORE_ROLE_REQUIRED')
        require(open(BASE+'/.single-final-owner').read() == MARKER, 'EXACT_RESTORE_MARKER_REQUIRED')
        start_args=existing_start_admission(request,datetime.datetime.now(datetime.timezone.utc))
        proof=BASE+'/.single-final-restore-completed-init54d.json';p=os.lstat(proof)
        require(stat.S_ISREG(p.st_mode) and p.st_uid==1000 and stat.S_IMODE(p.st_mode)==0o400, 'EXACT_COMPLETED_INIT_PROOF_REQUIRED')
        require(hashlib.sha256(open(proof,'rb').read()).hexdigest() == '4c9c608cb9728540c73bdcb3964c1dcc6c7d86821dd90a00a47486526052d718' and hashlib.sha256(open(INI,'rb').read()).hexdigest() == '23e291877f1c9d24b59a5ad4eada61a9d6db7f6e0df2b4be375897d9d0a29bcf', 'COMPLETED_INIT_BYTES_CHANGED')
        a=json.load(open(BASE+'/.single-final-restore-init-attempt'));require(a['taskId']==TASK and a['sourceId']==SOURCE and a['packageSha256']==PACKAGE and a['automaticRetryAllowed'] is False, 'ORIGINAL_INIT_ATTEMPT_REQUIRED')
        print('SINGLE_FINAL_EXISTING_RESTORE_START_NO_DMINIT',flush=True)
        if request.get('mode') == 'START_EXISTING_IMPORTED_ACTR86':
            print('SINGLE_FINAL_IMPORTED_ACTR86_START_'+request['runId'],flush=True)
        os.execl(*start_args)
    args=initialize_arguments(request,os.environ.get('SINGLE_DB_TARGET_ROLE'),os.environ.get('SINGLE_DB_TARGET_NAME'),open(BASE+'/.single-final-owner').read(),sorted(os.listdir(BASE)),datetime.datetime.now(datetime.timezone.utc))
    with open(BASE+'/.single-final-restore-init-attempt','x') as f:
        f.write(json.dumps({'taskId':TASK,'packageSha256':PACKAGE,'sourceId':request['sourceId'],'automaticRetryAllowed':False})+'\n');f.flush();os.fsync(f.fileno())
    secrets=[request['administratorPassword'],request['auditorPassword']]
    result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=120)
    output=result.stdout
    for secret in secrets: output=output.replace(secret,'[REDACTED]')
    print(output,flush=True)
    require(result.returncode==0 and os.path.isfile(INI), 'DMINIT_FAILED_PARTIAL_PRESERVED')
    text=open(INI).read()
    for key,value in [('BUFFER','128'),('MEMORY_POOL','128'),('MEMORY_N_POOLS','1')]:
        text,count=re.subn(r'(?m)^(\s*'+key+r'\s*=\s*)[^#\r\n]*',lambda m:m.group(1)+value+' ',text);require(count==1,'EXACT_MEMORY_SETTING_REQUIRED')
    with open(INI,'w') as f: f.write(text);f.flush();os.fsync(f.fileno())
    del request,secrets,args,output,result,raw
    print('SINGLE_FINAL_EMPTY_LOGICAL_RESTORE_START_UID1000_CAPS0',flush=True)
    os.execl('/opt/dmdbms/bin/dmserver','dmserver',INI)
if __name__=='__main__':
    try: main()
    except Exception as e:
        code=str(e) if isinstance(e,ValueError) and re.fullmatch('[A-Z0-9_]+',str(e)) else 'RESTORE_INIT_FAILED_PARTIAL_PRESERVED'
        print(code,file=sys.stderr,flush=True);sys.exit(1)
