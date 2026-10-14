import errno, json, os
path = '/bounded/owned-enospc-probe-round2'
limit = 3 * 1024 * 1024 * 1024
stats = os.statvfs('/bounded')
written = 0
failure = None
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
inode = os.fstat(fd).st_ino
try:
    block = b'\0' * (1024 * 1024)
    while written <= limit:
        try:
            written += os.write(fd, block)
            if written % (8 * 1024 * 1024) == 0:
                os.fsync(fd)
                os.posix_fadvise(fd, 0, written, os.POSIX_FADV_DONTNEED)
        except OSError as error:
            if error.errno != errno.ENOSPC: raise
            failure = 'ENOSPC'
            break
    if not failure: raise RuntimeError('No hard limit within approved 3GiB bound')
    try: os.fsync(fd)
    except OSError as error:
        if error.errno != errno.ENOSPC: raise
    print(json.dumps({'task':'VER-AGENT-SINGLE-DB-PERSISTENT-01','filesystemCapacityBytes':stats.f_blocks*stats.f_frsize,
       'overflowError':failure,'overflowWriteExit':1,'actualProbeBytes':os.fstat(fd).st_size,'consumerUid':os.getuid()}), flush=True)
finally:
    os.close(fd)
    # This process exclusively created this scratch file; never touch any other file/resource.
    assert os.stat(path).st_ino == inode
    os.unlink(path)
    os.sync()
print('OWNED_SCRATCH_REMOVED_FILESYSTEM_READY', flush=True)

