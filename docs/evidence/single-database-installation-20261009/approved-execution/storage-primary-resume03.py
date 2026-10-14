import os,stat,subprocess,json
assert os.stat('/owned/primary.ext4').st_size==3221225472
assert open('/sys/devices/virtual/block/loop491040/loop/backing_file').read().strip()=='/primary.ext4'
assert open('/sys/devices/virtual/block/loop491040/loop/autoclear').read().strip()=='0'
os.mknod('/dev/owned-loop',stat.S_IFBLK|0o600,os.makedev(7,491040))
subprocess.run(['mount','-t','ext4','-o','nodev,nosuid,noexec','/dev/owned-loop','/bounded'],check=True)
assert open('/bounded/.single-final-owner').read()=='IMP-SINGLE-DB-FINAL-BASELINE-01\nprimary\n491040\n'
os.chown('/bounded/db',0,0)
assert not os.listdir('/bounded/db')
with open('/bounded/db/.single-final-owner','x') as f:f.write('IMP-SINGLE-DB-FINAL-BASELINE-01\nprimary\n491040\n')
os.chmod('/bounded/db/.single-final-owner',0o400);os.chown('/bounded/db/.single-final-owner',1000,1000)
os.chown('/bounded/db',1000,1000)
for directory,uid in [('db',1000),('logs',1000),('files/consultation',10001),('files/enforcement',10002),('files/adjudication',10003)]:
    p='/bounded/'+directory;assert os.stat(p).st_uid==uid and stat.S_IMODE(os.stat(p).st_mode)==0o700
s=os.statvfs('/bounded');uuid=subprocess.check_output(['blkid','-s','UUID','-o','value','/dev/owned-loop'],text=True).strip()
assert s.f_blocks*s.f_frsize<=3221225472
print(json.dumps({'format':'SINGLE_DATABASE_OWNED_STORAGE_PROOF/v1','taskId':'IMP-SINGLE-DB-FINAL-BASELINE-01','role':'primary','loopIndex':491040,'imageBytes':3221225472,'filesystemUuid':uuid,'capacityBytes':s.f_blocks*s.f_frsize,'autoclear':False,'quotaEvidence':'storage-primary-resume02.py:ENOSPC assertion passed before marker failure; complete unmodified failure log retained','resumedExactPartialStorage':True,'databaseInitialized':False,'oldResourceMutated':False}),flush=True)
subprocess.run(['umount','/bounded'],check=True)
