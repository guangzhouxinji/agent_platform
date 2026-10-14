#!/usr/bin/python3
"""No-capability DM8 entry for the owned bounded engineering filesystem."""
import os, re, subprocess, sys
assert os.getuid() == 1000
for line in open('/proc/self/status'):
    if line.startswith('Cap'): assert int(line.split()[1], 16) == 0
assert open('/opt/dmdbms/data/owner').readline().strip() == 'VER-AGENT-SINGLE-DB-PERSISTENT-01'
ini = '/opt/dmdbms/data/PERSIST01/dm.ini'
if not os.path.exists(ini):
    secrets = [os.environ['OWNED_SYSDBA_PASSWORD'], os.environ['OWNED_SYSAUDITOR_PASSWORD']]
    assert all(re.fullmatch('[A-Za-z0-9_]{32,48}', value) for value in secrets)
    result = subprocess.run(['/opt/dmdbms/bin/dminit', 'PATH=/opt/dmdbms/data', 'DB_NAME=PERSIST01',
        'INSTANCE_NAME=PERSIST01', 'SYSDBA_PWD=' + secrets[0], 'SYSAUDITOR_PWD=' + secrets[1],
        'PAGE_SIZE=32', 'EXTENT_SIZE=32', 'CHARSET=1', 'CASE_SENSITIVE=1', 'BLANK_PAD_MODE=1', 'LOG_SIZE=256'],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    output = result.stdout
    for value in secrets: output = output.replace(value, '[REDACTED]')
    print(output, flush=True)
    assert result.returncode == 0 and os.path.isfile(ini), 'Owned dminit failed'
    text = open(ini).read()
    for key, value in [('BUFFER', '128'), ('MEMORY_POOL', '128'), ('MEMORY_N_POOLS', '1')]:
        text, count = re.subn(r'(?m)^(\s*' + key + r'\s*=\s*)[^#\r\n]*', lambda m: m.group(1) + value + ' ', text)
        assert count == 1, 'Expected explicit memory parameter ' + key
    with open(ini, 'w') as out: out.write(text)
for name in ('OWNED_SYSDBA_PASSWORD', 'OWNED_SYSAUDITOR_PASSWORD'): os.environ.pop(name, None)
print('OWNED_DM8_READY_TO_START_UID1000_CAPS0', flush=True)
os.execl('/opt/dmdbms/bin/dmserver', 'dmserver', ini)
