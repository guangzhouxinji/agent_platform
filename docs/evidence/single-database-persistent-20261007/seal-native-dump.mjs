import {createCipheriv, createDecipheriv, createHash, randomBytes} from 'node:crypto';
import {execFile, spawn} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile, writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const exec = promisify(execFile);
const task = 'VER-AGENT-SINGLE-DB-PERSISTENT-01';
const root = path.dirname(fileURLToPath(import.meta.url));
const magic = Buffer.from('XJDM8GCM1');
const aad = Buffer.from(task + ':native-export:v1');
const key = Buffer.from(process.env.SINGLE_DB_PERSISTENT_BACKUP_KEY ?? '', 'base64');
if (key.length !== 32) throw new Error('Independent managed AES-256 key required');
const [action, name] = process.argv.slice(2);
const file = path.join(root, 'native-full.dmp.gcm');
const sha = data => createHash('sha256').update(data).digest('hex');
const max = 128 * 1024 * 1024;
async function ownedContainer() {
  if (!/^agent-platform-single-persistent-(primary|restore)-dm8(-02)?-20261007$/.test(name ?? '')) throw new Error('Exact owned container name required');
  const result = await exec('docker', ['inspect', name], {maxBuffer: 2 * 1024 * 1024});
  const container = JSON.parse(result.stdout)[0];
  if (container.Config.Labels.task !== task || !container.State.Running || container.Config.User !== '1000:1000' ||
      container.HostConfig.Privileged || !container.HostConfig.CapDrop.includes('ALL')) throw new Error('Owned unprivileged target required');
  return container.Id;
}
function decrypt(sealed) {
  if (sealed.length < 37 || sealed.length > max + 37 || !sealed.subarray(0, 9).equals(magic)) throw new Error('Invalid sealed export');
  const decipher = createDecipheriv('aes-256-gcm', key, sealed.subarray(9,21));
  decipher.setAAD(aad); decipher.setAuthTag(sealed.subarray(21,37));
  return Buffer.concat([decipher.update(sealed.subarray(37)), decipher.final()]);
}
if (action === 'seal') {
  const containerId = await ownedContainer();
  const {stdout: raw} = await exec('docker', ['exec', '--user', '1000:1000', name, 'cat', '/opt/dmdbms/data/native-full.dmp'], {encoding:'buffer', maxBuffer:max});
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, nonce); cipher.setAAD(aad);
  const encrypted = Buffer.concat([cipher.update(raw), cipher.final()]);
  const sealed = Buffer.concat([magic, nonce, cipher.getAuthTag(), encrypted]);
  await writeFile(file, sealed, {flag:'wx', mode:0o600});
  console.log(JSON.stringify({task, action, cipher:'AES-256-GCM', sourceContainer:containerId, nativeBytes:raw.length,
    nativeSha256:sha(raw), sealedBytes:sealed.length, sealedSha256:sha(sealed), plaintextStoredOnHost:false}));
  raw.fill(0);
} else if (action === 'verify' || action === 'restore' || action === 'tamper-test') {
  const sealed = await readFile(file);
  if (action === 'tamper-test') {
    const tampered = Buffer.from(sealed); tampered[tampered.length-1] ^= 1;
    let rejected = false;
    try { decrypt(tampered); } catch { rejected = true; }
    if (!rejected) throw new Error('Modified native export was accepted');
    console.log(JSON.stringify({task, action, authenticationFailureRejected:true, targetContacted:false}));
  } else {
    // Authentication completes before any plaintext is written or target contacted.
    const raw = decrypt(sealed);
    const nativeSha256 = sha(raw);
    let targetContainer;
    if (action === 'restore') {
      targetContainer = await ownedContainer();
      if (!name.startsWith('agent-platform-single-persistent-restore-')) throw new Error('Never restore into source');
      const child = spawn('docker', ['exec', '-i', '--user', '1000:1000', name, '/bin/sh', '-c',
        'umask 077; set -C; cat > /opt/dmdbms/data/native-full.dmp'], {stdio:['pipe','ignore','pipe']});
      let error = ''; child.stderr.on('data', data => error += data);
      const done = new Promise((resolve,reject) => {
        child.on('error',reject); child.on('close', code => code === 0 ? resolve() : reject(new Error('Owned dump delivery failed: '+error)));
      });
      child.stdin.end(raw); await done;
    }
    console.log(JSON.stringify({task, action, authenticationVerified:true, nativeBytes:raw.length,nativeSha256,
      sealedSha256:sha(sealed), targetContainer, plaintextStoredOnHost:false}));
    raw.fill(0);
  }
} else throw new Error('Only seal/verify/restore/tamper-test actions are allowed');
key.fill(0);
