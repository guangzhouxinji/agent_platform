import os,stat,subprocess,json
image='/owned/restore.ext4'
assert sorted(os.listdir('/owned'))==['restore.ext4']
s=os.lstat(image);assert stat.S_ISREG(s.st_mode) and s.st_size==3221225472 and s.st_uid==0 and stat.S_IMODE(s.st_mode)==0o600
assert os.path.isdir('/sys/devices/virtual/block/loop491041')
assert open('/sys/devices/virtual/block/loop491041/size').read().strip()=='0'
assert not os.path.exists('/sys/devices/virtual/block/loop491041/loop/backing_file')
headers=subprocess.check_output(['blkid','-p','-o','export',image],universal_newlines=True)
fields=dict(line.split('=',1) for line in headers.splitlines() if '=' in line)
assert fields['TYPE']=='ext4' and fields['LABEL']=='APSF_RESTORE'
listing=subprocess.check_output(['debugfs','-R','ls -p /',image],universal_newlines=True,stderr=subprocess.DEVNULL)
names=[line.split('/')[5] for line in listing.splitlines() if line.startswith('/')]
assert sorted(names)==['.','..','lost+found'], 'PARTIAL_FRESH_FILESYSTEM_NOT_EMPTY'
print(json.dumps({'format':'EXACT_READ_ONLY_PARTIAL_RESTORE_STORAGE/v1','passed':True,'imageBytes':s.st_size,'imageUid':s.st_uid,'imageMode':'0600','imageDevice':s.st_dev,'imageInode':s.st_ino,'filesystemUuid':fields['UUID'],'filesystemLabel':fields['LABEL'],'rootNames':names,'loopIndex':491041,'loopUnbound':True,'loopSectors':0,'nativeDatabaseExists':False,'actualWrites':0}))
