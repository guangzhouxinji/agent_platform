import os,stat,hashlib,json,sys
base='/data';assert open(base+'/.single-final-owner').read()=='IMP-SINGLE-DB-FINAL-BASELINE-01\nrestore\n491041\n'
assert hashlib.sha256(open(base+'/SINGLE01/dm.ini','rb').read()).hexdigest()=='23e291877f1c9d24b59a5ad4eada61a9d6db7f6e0df2b4be375897d9d0a29bcf'
b=sys.stdin.buffer.read(8193);assert len(b)<=8192 and hashlib.sha256(b).hexdigest()=='4c9c608cb9728540c73bdcb3964c1dcc6c7d86821dd90a00a47486526052d718'
p=base+'/.single-final-restore-completed-init54d.json'
with open(p,'xb') as f:f.write(b);f.flush();os.fsync(f.fileno())
os.chmod(p,0o400);assert os.stat(p).st_uid==1000
print(json.dumps({'passed':True,'proofSha256':'4c9c608cb9728540c73bdcb3964c1dcc6c7d86821dd90a00a47486526052d718','newInitializationPerformed':False,'sqlExecuted':False}))
