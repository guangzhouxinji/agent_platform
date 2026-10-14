import os,stat,json
p='/host-dev/agent-platform-single-final-restore-loop'
assert not os.path.lexists(p),'EXACT_STABLE_DEVICE_ALREADY_EXISTS'
assert open('/sys/devices/virtual/block/loop491041/loop/backing_file').read().strip()=='/restore.ext4'
assert open('/sys/devices/virtual/block/loop491041/loop/autoclear').read().strip()=='0'
os.mknod(p,stat.S_IFBLK|0o600,os.makedev(7,491041))
s=os.lstat(p);assert stat.S_ISBLK(s.st_mode) and s.st_rdev==os.makedev(7,491041) and s.st_uid==0
print(json.dumps({'passed':True,'role':'restore','device':'/dev/agent-platform-single-final-restore-loop','major':7,'minor':491041,'primaryDeviceTouched':False}))
