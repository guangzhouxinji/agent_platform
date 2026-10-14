#!/usr/bin/python3
"""Owned loop provisioning probe; never scans, detaches, or mounts another loop."""
import ctypes, fcntl, os, stat, subprocess, sys, time
TASK = 'VER-AGENT-SINGLE-DB-PERSISTENT-01'
index = int(os.environ['OWNED_LOOP_INDEX'])
size = int(os.environ['OWNED_IMAGE_BYTES'])
assert 491027 <= index <= 491034
assert size in (64 * 1024 * 1024, 2 * 1024 * 1024 * 1024, 3 * 1024 * 1024 * 1024)
assert os.environ['TASK_OWNER'] == TASK
image = '/owned/' + TASK + '.ext4'
device = '/dev/owned-loop'
assert not os.path.exists('/sys/devices/virtual/block/loop' + str(index)), 'Refuse preexisting kernel loop'
resume = os.environ.get('OWNED_IMAGE_MODE') == 'resume'
assert (os.path.isfile(image) if resume else not os.path.exists(image)), 'Explicit fresh/resume image ownership required'
assert os.stat('/owned').st_uid == 0
if not resume:
    with open(image, 'xb') as out:
        out.truncate(size)
        out.flush()
        os.fsync(out.fileno())
    os.chmod(image, 0o600)
    subprocess.run(['mkfs.ext4', '-F', '-m', '0', '-L', 'VER_PERSIST_01', image], check=True)
else:
    assert os.stat(image).st_uid == 0 and stat.S_IMODE(os.stat(image).st_mode) == 0o600
    assert subprocess.check_output(['blkid', '-s', 'LABEL', '-o', 'value', image], text=True).strip() == 'VER_PERSIST_01'
assert os.stat(image).st_size == size
fd = os.open('/dev/loop-control', os.O_RDWR)
try:
    # LOOP_CTL_ADD for exactly one absent, recorded index; NEVER LOOP_CTL_GET_FREE/-f/-D.
    added = fcntl.ioctl(fd, 0x4C80, index)
    assert added == index
finally:
    os.close(fd)
os.mknod(device, stat.S_IFBLK | 0o600, os.makedev(7, index))
subprocess.run(['losetup', device, image], check=True)
actual = subprocess.check_output(['losetup', '-n', '-O', 'BACK-FILE', device], text=True).strip()
assert actual == image, actual
subprocess.run(['mount', '-t', 'ext4', '-o', 'nodev,nosuid,noexec', device, '/bounded'], check=True)
print('OWNED_MOUNT index=%s image_bytes=%s backing=%s' % (index, size, actual), flush=True)
subprocess.run(['findmnt', '-n', '-o', 'SOURCE,FSTYPE,OPTIONS', '/bounded'], check=True)
# A mounted filesystem retains its loop reference. Linux lazy detach marks AUTOCLEAR;
# final unmount releases the exact owned device. No global detach or host propagation.
subprocess.run(['losetup', '-d', device], check=True)
if not resume:
    with open('/bounded/owner', 'x') as marker:
        marker.write(TASK + '\n' + str(index) + '\n')
    os.chown('/bounded/owner', 1000, 1000)
    os.chown('/bounded', 1000, 1000)
else:
    assert open('/bounded/owner').readline().strip() == TASK
print('READY_FOR_EXACT_HELPER_PID_BIND', flush=True)
# Old setpriv/libcap-ng cannot address the newer kernel's complete capability set.
# Drop every actual kernel bounding capability via prctl, then uid/gid transition clears
# effective/permitted capabilities. No CAP_SETPCAP remains in the sleeper.
libc = ctypes.CDLL(None, use_errno=True)
last = int(open('/proc/sys/kernel/cap_last_cap').read())
for capability in range(last + 1):
    assert libc.prctl(24, capability, 0, 0, 0) == 0, ctypes.get_errno()
assert libc.prctl(38, 1, 0, 0, 0) == 0
os.setgroups([])
os.setgid(1000)
os.setuid(1000)
for line in open('/proc/self/status'):
    if line.startswith('Cap') or line.startswith('NoNewPrivs'):
        print(line.strip(), flush=True)
        if line.startswith('Cap'): assert int(line.split()[1], 16) == 0
os.execl('/bin/sleep', 'sleep', 'infinity')

