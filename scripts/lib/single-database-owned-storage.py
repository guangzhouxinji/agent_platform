"""Exact new single-database storage bootstrap; reuses the approved fixed-image/owned-loop method.
Not a database recovery or general storage service. Existing images are never formatted here.
"""
import ctypes, fcntl, json, mmap, os, stat, subprocess, errno
TASK='IMP-SINGLE-DB-FINAL-BASELINE-01'
role=os.environ['SINGLE_DB_STORAGE_ROLE']
assert role in ('primary','restore') and os.environ['SINGLE_DB_STORAGE_TASK']==TASK
index={'primary':491040,'restore':491041}[role]
size=3*1024**3
image='/owned/'+role+'.ext4'; device='/dev/owned-loop'; label={'primary':'APSF_PRIMARY','restore':'APSF_RESTORE'}[role]
assert not os.path.exists('/sys/devices/virtual/block/loop'+str(index)), 'EXACT_NEW_LOOP_ALREADY_EXISTS'
assert not os.path.lexists(image), 'EXISTING_IMAGE_MUST_NOT_BE_FORMATTED'
assert os.stat('/owned').st_uid==0
with open(image,'xb') as out: out.truncate(size);out.flush();os.fsync(out.fileno())
os.chmod(image,0o600)
subprocess.run(['mkfs.ext4','-F','-m','0','-L',label,image],check=True,stdout=subprocess.DEVNULL)
assert os.stat(image).st_size==size and stat.S_IMODE(os.stat(image).st_mode)==0o600
fd=os.open('/dev/loop-control',os.O_RDWR)
try: assert fcntl.ioctl(fd,0x4C80,index)==index
finally: os.close(fd)
os.mknod(device,stat.S_IFBLK|0o600,os.makedev(7,index))
subprocess.run(['losetup',device,image],check=True)
assert subprocess.check_output(['losetup','-n','-O','BACK-FILE',device],text=True).strip()==image
# No AUTOCLEAR/detach: the fixed consumer device must survive bootstrap-helper exit.
assert open('/sys/devices/virtual/block/loop'+str(index)+'/loop/autoclear').read().strip()=='0'
subprocess.run(['mount','-t','ext4','-o','nodev,nosuid,noexec',device,'/bounded'],check=True)
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
