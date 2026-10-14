import ctypes,fcntl,json,mmap,os,stat,subprocess,errno
TASK='IMP-SINGLE-DB-FINAL-BASELINE-01';role='restore';index=491041;size=3221225472
image='/owned/restore.ext4';device='/dev/owned-loop'
assert os.environ['SINGLE_DB_STORAGE_TASK']==TASK
assert sorted(os.listdir('/owned'))==['restore.ext4']
s=os.lstat(image);assert stat.S_ISREG(s.st_mode) and s.st_size==size and s.st_uid==0 and stat.S_IMODE(s.st_mode)==0o600 and s.st_dev==2096 and s.st_ino==154723
assert subprocess.check_output(['blkid','-p','-s','UUID','-o','value',image],universal_newlines=True).strip()=='a36112b7-8e05-468e-a42a-1404e3d09364'
assert subprocess.check_output(['blkid','-p','-s','LABEL','-o','value',image],universal_newlines=True).strip()=='APSF_RESTORE'
assert open('/sys/devices/virtual/block/loop491041/size').read().strip()=='0'
assert not os.path.exists('/sys/devices/virtual/block/loop491041/loop/backing_file')
os.mknod(device,stat.S_IFBLK|0o600,os.makedev(7,index))
subprocess.run(['losetup',device,image],check=True)
assert subprocess.check_output(['losetup','-n','-O','BACK-FILE',device],universal_newlines=True).strip()==image
# No AUTOCLEAR/detach: the fixed consumer device must survive bootstrap-helper exit.
assert open('/sys/devices/virtual/block/loop'+str(index)+'/loop/autoclear').read().strip()=='0'
subprocess.run(['mount','-t','ext4','-o','nodev,nosuid,noexec',device,'/bounded'],check=True)
assert sorted(os.listdir('/bounded'))==['lost+found'], 'UNEXPECTED_EXISTING_PARTIAL_FILESYSTEM'
probe='/bounded/.single-final-initial-quota-probe';written=0;overflow=None
# O_DIRECT keeps this initial empty-filesystem overflow proof within the 128MiB helper cap.
fd=os.open(probe,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_DIRECT,0o600);buffer=mmap.mmap(-1,1024*1024)
try:
    try:
        for _ in range(4096): written+=os.write(fd,buffer)
        os.fsync(fd)
    except OSError as e:
        overflow=e.errno
        if e.errno!=errno.ENOSPC: raise
finally: os.close(fd);buffer.close()
assert overflow==errno.ENOSPC, 'REAL_HARD_LIMIT_NOT_PROVEN'
# Only this exact newly created pre-database probe is removed; no DB exists yet.
assert os.path.isfile(probe) and os.stat(probe).st_uid==0
os.unlink(probe)
for directory,uid in [('db',1000),('logs',1000),('files/consultation',10001),('files/enforcement',10002),('files/adjudication',10003)]:
    p='/bounded/'+directory;os.makedirs(p,exist_ok=False);os.chmod(p,0o700);os.chown(p,uid,uid)
with open('/bounded/.single-final-owner','x') as f: f.write(TASK+'\n'+role+'\n'+str(index)+'\n')
os.chown('/bounded/db',0,0)
with open('/bounded/db/.single-final-owner','x') as f: f.write(TASK+'\n'+role+'\n'+str(index)+'\n')
os.chmod('/bounded/db/.single-final-owner',0o400);os.chown('/bounded/db/.single-final-owner',1000,1000)
os.chown('/bounded/db',1000,1000)
s=os.statvfs('/bounded');uuid=subprocess.check_output(['blkid','-s','UUID','-o','value',device],text=True).strip()
assert s.f_blocks*s.f_frsize<=size
print(json.dumps({'format':'SINGLE_DATABASE_OWNED_STORAGE_PROOF/v1','taskId':TASK,'role':role,'loopIndex':index,'imageBytes':size,'imageOwnerUid':0,'imageMode':'0600','filesystem':'ext4','filesystemUuid':uuid,'capacityBytes':s.f_blocks*s.f_frsize,'actualOverflowError':'ENOSPC','probeWrittenBytes':written,'autoclear':False,'helperLifetimeRequired':False,'databaseInitialized':False,'oldResourceMutated':False}),flush=True)
subprocess.run(['umount','/bounded'],check=True)
"""No inverse/DROP/loop detach on failure; the actual partially prepared target remains inspectable."""
