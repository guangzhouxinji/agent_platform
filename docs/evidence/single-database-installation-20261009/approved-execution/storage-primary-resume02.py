import os, stat, subprocess, json, errno, mmap
assert os.environ['SINGLE_DB_STORAGE_TASK']=='IMP-SINGLE-DB-FINAL-BASELINE-01'
assert open('/sys/devices/virtual/block/loop491040/loop/backing_file').read().strip()=='/primary.ext4'
assert open('/sys/devices/virtual/block/loop491040/loop/autoclear').read().strip()=='0'
assert os.stat('/owned/primary.ext4').st_size==3*1024**3
assert stat.S_IMODE(os.stat('/owned/primary.ext4').st_mode)==0o600
os.mknod('/dev/owned-loop',stat.S_IFBLK|0o600,os.makedev(7,491040))
subprocess.run(['mount','-t','ext4','-o','nodev,nosuid,noexec','/dev/owned-loop','/bounded'],check=True)
assert sorted(os.listdir('/bounded'))==['db','lost+found'], 'UNEXPECTED_PARTIAL_STORAGE'
assert os.stat('/bounded/db').st_uid==1000 and not os.listdir('/bounded/db')
os.chown('/bounded/db',0,0);os.chmod('/bounded/db',0o700);os.chown('/bounded/db',1000,1000)
probe='/bounded/.single-final-resume-quota-probe'; written=0;overflow=None
fd=os.open(probe,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_DIRECT,0o600);buffer=mmap.mmap(-1,1024*1024)
try:
    try:
        for _ in range(4096): written+=os.write(fd,buffer)
        os.fsync(fd)
    except OSError as e:
        overflow=e.errno
        if e.errno!=errno.ENOSPC: raise
finally: os.close(fd);buffer.close()
assert overflow==errno.ENOSPC
os.unlink(probe)
for directory,uid in [('logs',1000),('files/consultation',10001),('files/enforcement',10002),('files/adjudication',10003)]:
    p='/bounded/'+directory;os.makedirs(p,exist_ok=False);os.chmod(p,0o700);os.chown(p,uid,uid)
with open('/bounded/.single-final-owner','x') as f:f.write('IMP-SINGLE-DB-FINAL-BASELINE-01\nprimary\n491040\n')
# DM8 entry validates its own subpath marker; it never mounts the sibling application directories.
with open('/bounded/db/.single-final-owner','x') as f:f.write('IMP-SINGLE-DB-FINAL-BASELINE-01\nprimary\n491040\n')
os.chmod('/bounded/db/.single-final-owner',0o400);os.chown('/bounded/db/.single-final-owner',1000,1000)
s=os.statvfs('/bounded');uuid=subprocess.check_output(['blkid','-s','UUID','-o','value','/dev/owned-loop'],text=True).strip()
print(json.dumps({'format':'SINGLE_DATABASE_OWNED_STORAGE_PROOF/v1','taskId':'IMP-SINGLE-DB-FINAL-BASELINE-01','role':'primary','loopIndex':491040,'imageBytes':3*1024**3,'filesystemUuid':uuid,'capacityBytes':s.f_blocks*s.f_frsize,'actualOverflowError':'ENOSPC','probeWrittenBytes':written,'autoclear':False,'resumedExactPartialStorage':True,'databaseInitialized':False,'oldResourceMutated':False}),flush=True)
subprocess.run(['umount','/bounded'],check=True)
