"""Read-only exact existing primary loop/image proof. Never formats, writes or fills storage."""
import os,stat,fcntl,struct,json,uuid,time
IMAGE='/image/primary.ext4'; DEVICE='/dev/owned-loop'; ROOT='/bounded'; INDEX=491040
size=3221225472
s=os.lstat(IMAGE)
assert stat.S_ISREG(s.st_mode) and s.st_uid==0 and s.st_gid==0 and stat.S_IMODE(s.st_mode)==0o600 and s.st_size==size
r=os.stat(DEVICE); assert stat.S_ISBLK(r.st_mode) and r.st_rdev==os.makedev(7,INDEX)
fd=os.open(DEVICE,os.O_RDONLY)
try: info=struct.unpack('=QQQQQIIII64s64s32sQQ',fcntl.ioctl(fd,0x4c05,bytes(232)))
finally: os.close(fd)
dev,inode,rdev,offset,limit,num,encrypt,keylen,flags=info[:9]
assert dev==s.st_dev and inode==s.st_ino and num==INDEX and offset==0 and limit==0 and encrypt==0 and keylen==0 and not flags&4
base='/sys/devices/virtual/block/loop%d/'%INDEX
backing=open(base+'loop/backing_file').read().strip();autoclear=open(base+'loop/autoclear').read().strip();sectors=int(open(base+'size').read())
assert backing=='/primary.ext4' and autoclear=='0' and sectors*512==size
with open(IMAGE,'rb') as f: f.seek(1024);sb=f.read(1024)
assert sb[56:58]==b'\x53\xef'
fsuuid=str(uuid.UUID(bytes=sb[104:120]));assert fsuuid=='29678a4e-4538-4341-88cb-4718a7c1f5d8'
root=os.stat(ROOT);assert root.st_dev==r.st_rdev
fs=os.statvfs(ROOT);capacity=fs.f_blocks*fs.f_frsize;assert capacity==3094126592 and capacity<=size
marker=open(ROOT+'/.single-final-owner').read();assert marker=='IMP-SINGLE-DB-FINAL-BASELINE-01\nprimary\n491040\n'
files={}
for name,uid in [('consultation',10001),('enforcement',10002),('adjudication',10003)]:
 d=os.lstat(ROOT+'/files/'+name);assert stat.S_ISDIR(d.st_mode) and d.st_uid==uid and d.st_gid==uid and stat.S_IMODE(d.st_mode)==0o700
 files[name]={'subpath':'files/'+name,'uid':d.st_uid,'gid':d.st_gid,'mode':'0700'}
print(json.dumps({'format':'ACTUAL_OWNED_IMAGE_LOOP_PROOF/v1','capturedAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'readOnly':True,'executedWrites':0,'imagePath':'/primary.ext4','imageBytes':s.st_size,'imageUid':s.st_uid,'imageGid':s.st_gid,'imageMode':'0600','imageStatDevice':s.st_dev,'imageInode':s.st_ino,'loopBackingDevice':dev,'loopBackingInode':inode,'loopIndex':num,'blockDevice':r.st_rdev,'mountedFilesystemDevice':root.st_dev,'loopOffset':offset,'loopSizeLimit':limit,'kernelBackingFile':backing,'kernelSectors':sectors,'autoclear':False,'filesystemUuid':fsuuid,'capacityBytes':capacity,'ownerMarker':marker,'directories':files},sort_keys=True),flush=True)
