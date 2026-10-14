import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import path from 'node:path';import {createHash} from 'node:crypto';import {spawnSync} from 'node:child_process';
const root=process.cwd(),e=path.join(root,'docs/evidence/single-database-installation-20261009/approved-execution/owned-image-binding26');
const library=path.resolve(process.env.SINGLE_DB_ADMISSION_TEST_LIBRARY??'scripts/lib/single-database-final-deployment.ps1');
const live=JSON.parse(fs.readFileSync(path.join(e,'actual-probe26.json'),'utf8').replace(/^\uFEFF/,''));
const volumes=JSON.parse(fs.readFileSync(path.join(e,'actual-probe26-volumes.json'),'utf8').replace(/^\uFEFF/,''));
const target='f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f';
const p={format:'SINGLE_DATABASE_OWNED_IMAGE_BINDING/v1',targetId:target,ownerTask:'IMP-SINGLE-DB-FINAL-BASELINE-01',imageVolume:volumes.find(v=>v.Name.endsWith('-image')),boundedVolume:volumes.find(v=>!v.Name.endsWith('-image'))};
let n=0;const q=s=>"'"+s.replaceAll("'","''")+"'";
function run(patch,extra=''){
 const c={p:structuredClone(p),live:structuredClone(live)};patch(c);const file=path.join(e,'synthetic-admission-'+n+++'.json');fs.writeFileSync(file,JSON.stringify(c));
 const code=`$ErrorActionPreference='Stop';. ${q(library)};function docker {throw 'REAL_DOCKER_FORBIDDEN'};function Get-FinalDeploymentUtc {[DateTimeOffset]::Parse(${q(live.capturedAt)}).AddMinutes(1)};$c=Read-FinalDeploymentJson ${q(file)};try{Assert-FinalOwnedImageRecord $c.p $c.live ${q(target)};${extra};'ALLOWED'}catch{'REFUSED '+$_.Exception.Message}`;
 const r=spawnSync('pwsh',['-NoProfile','-EncodedCommand',Buffer.from(code,'utf16le').toString('base64')],{encoding:'utf8',timeout:30000});assert.ifError(r.error);assert.equal(r.status,0,r.stderr);return r.stdout.trim();
}
test('read-only exact device/inode/UUID/owned-volume record accepted (synthetic harness, no Docker)',()=>assert.equal(run(()=>{}),'ALLOWED'));
for(const [name,mutate] of [
 ['target',c=>c.p.targetId='a'.repeat(64)],['owner task',c=>c.p.ownerTask='other'],['image volume',c=>c.p.imageVolume.Name='other'],['image role',c=>c.p.imageVolume.Labels.role='other'],['image owner',c=>c.p.imageVolume.Labels.task='other'],['image options',c=>c.p.imageVolume.Options={device:'foreign'}],['image mountpoint',c=>c.p.imageVolume.Mountpoint='/other'],['bounded volume',c=>c.p.boundedVolume.Name='other'],['device origin',c=>c.p.boundedVolume.Options.device='/dev/loop0'],['mount flags',c=>c.p.boundedVolume.Options.o='rw'],['filesystem type',c=>c.p.boundedVolume.Options.type='overlay'],
 ['image path',c=>c.live.imagePath='/other.ext4'],['kernel backing',c=>c.live.kernelBackingFile='/other.ext4'],['inode',c=>c.live.loopBackingInode++],['backing device',c=>c.live.loopBackingDevice++],['loop index',c=>c.live.loopIndex++],['offset',c=>c.live.loopOffset=1],['size limit',c=>c.live.loopSizeLimit=1],['mount device',c=>c.live.mountedFilesystemDevice++],['block device',c=>{c.live.blockDevice++;c.live.mountedFilesystemDevice++}],['kernel capacity',c=>c.live.kernelSectors++],['image capacity',c=>c.live.imageBytes++],['filesystem capacity',c=>c.live.capacityBytes++],['UUID',c=>c.live.filesystemUuid='12345678-1234-1234-1234-123456789abc'],['uid',c=>c.live.imageUid=1000],['gid',c=>c.live.imageGid=1000],['mode',c=>c.live.imageMode='0666'],['autoclear',c=>c.live.autoclear=true],['marker',c=>c.live.ownerMarker='other'],['writes',c=>c.live.executedWrites=1],['readonly',c=>c.live.readOnly=false],['missing domain directory',c=>delete c.live.directories.enforcement],['domain uid',c=>c.live.directories.consultation.uid=10002],['domain gid',c=>c.live.directories.consultation.gid=10002],['domain mode',c=>c.live.directories.consultation.mode='0755'],['domain subpath',c=>c.live.directories.consultation.subpath='files/enforcement'],['stale',c=>c.live.capturedAt='2026-10-08T19:00:00Z'],['future',c=>c.live.capturedAt='2026-10-10T09:00:00Z']
])test('fail closed: '+name,()=>assert.match(run(mutate),/^REFUSED /));
for(const [name,db] of [['device access',{Devices:[{}]}],['added capability',{CapAdd:['SYS_ADMIN']}],['missing no-new-privileges',{SecurityOpt:[]}]])test('consumer no-expansion denies '+name,()=>{
 const extra=`function Invoke-FinalDocker { ${q(JSON.stringify(p.imageVolume))} };$db=@{HostConfig=@{Devices=@();CapAdd=$null;SecurityOpt=@('no-new-privileges')}};${db.Devices?'$db.HostConfig.Devices=@(@{})':db.CapAdd?"$db.HostConfig.CapAdd=@('SYS_ADMIN')":"$db.HostConfig.SecurityOpt=@()"};Assert-FinalOwnedImageRuntime $c.p $db`;
 assert.match(run(()=>{},extra),/^REFUSED /);
});
test('no-expansion accepts exact image volume and device-free consumer',()=>assert.equal(run(()=>{},`function Invoke-FinalDocker { ${q(JSON.stringify(p.imageVolume))} };Assert-FinalOwnedImageRuntime $c.p @{HostConfig=@{Devices=@();CapAdd=$null;SecurityOpt=@('no-new-privileges')}}`),'ALLOWED'));

const storageBase=JSON.parse(fs.readFileSync(path.join(e,'actual-storage-fragment26.json'),'utf8')).database;
const proofBase=JSON.parse(fs.readFileSync(storageBase.proof.path,'utf8'));
const helperBase=JSON.parse(fs.readFileSync(proofBase.probeContainer.path,'utf8').replace(/^\uFEFF/,''));
const sha=b=>createHash('sha256').update(b).digest('hex');
function runFull(mutate){
 const c={storage:structuredClone(storageBase),proof:structuredClone(proofBase),helper:structuredClone(helperBase)};mutate(c);
 const put=(name,o)=>{const file=path.join(e,`synthetic-full-${n++}-${name}.json`),bytes=JSON.stringify(o);fs.writeFileSync(file,bytes);return {path:file,sha256:sha(bytes)}};
 c.proof.probeContainer=put('helper',c.helper);c.storage.proof=put('proof',c.proof);const file=put('storage',c.storage);
 const code=`$ErrorActionPreference='Stop';. ${q(library)};function docker {throw 'REAL_DOCKER_FORBIDDEN'};function Get-FinalDeploymentUtc {[DateTimeOffset]::Parse(${q(live.capturedAt)}).AddMinutes(1)};try{$null=Read-FinalOwnedImageBinding (Read-FinalDeploymentJson ${q(file.path)}) ${q(target)};'ALLOWED'}catch{'REFUSED '+$_.Exception.Message}`;
 const r=spawnSync('pwsh',['-NoProfile','-EncodedCommand',Buffer.from(code,'utf16le').toString('base64')],{encoding:'utf8',timeout:30000});assert.ifError(r.error);assert.equal(r.status,0,r.stderr);return r.stdout.trim();
}
test('full helper/history/reference binding accepted without Docker (synthetic harness)',()=>assert.equal(runFull(()=>{}),'ALLOWED'));
for(const[name,mutate]of[
 ['missing owned backing kind',c=>delete c.storage.backingKind],['fictional daemon backing path',c=>c.storage.backingFile='/mnt/docker-desktop-disk/fiction.ext4'],['foreign storage device',c=>c.storage.device='/dev/loop0'],
 ['helper privilege',c=>c.helper[0].HostConfig.Privileged=true],['helper writable root',c=>c.helper[0].HostConfig.ReadonlyRootfs=false],['helper caps',c=>c.helper[0].HostConfig.CapDrop=[]],['helper no-new-privileges',c=>c.helper[0].HostConfig.SecurityOpt=[]],['helper network',c=>c.helper[0].HostConfig.NetworkMode='bridge'],['helper device write',c=>c.helper[0].HostConfig.Devices[0].CgroupPermissions='rwm'],['helper device origin',c=>c.helper[0].HostConfig.Devices[0].PathOnHost='/dev/loop0'],['helper exit failure',c=>c.helper[0].State.ExitCode=1],['helper OOM',c=>c.helper[0].State.OOMKilled=true],['helper extra mount',c=>c.helper[0].Mounts.push({Type:'volume',Name:'foreign',Destination:'/secret',RW:true})],['helper writable image',c=>c.helper[0].Mounts.find(m=>m.Destination==='/image').RW=true],['helper foreign image mount',c=>c.helper[0].Mounts.find(m=>m.Destination==='/image').Name='foreign'],['helper probe source',c=>c.helper[0].Mounts.find(m=>m.Destination==='/proof.py').Source='/foreign.py'],['probe source hash',c=>c.proof.probeSource.sha256='f'.repeat(64)],['missing history',c=>delete c.proof.historicalEnospc.log02],['history hash',c=>c.proof.historicalEnospc.log02.sha256='f'.repeat(64)]
])test('full binding fails closed: '+name,()=>assert.match(runFull(mutate),/^REFUSED /));

const initActual=JSON.parse(fs.readFileSync(path.join(e,'application-initialization-receipt26.json'),'utf8'));
function runIdentity(mutate){
 const c=structuredClone(initActual);mutate(c);const file=path.join(e,`synthetic-identity-${n++}.json`);fs.writeFileSync(file,JSON.stringify(c));
 const code=`$ErrorActionPreference='Stop';. ${q(library)};function docker {throw 'REAL_DOCKER_FORBIDDEN'};try{Assert-FinalSelectedIdentityBootstrap (Read-FinalDeploymentJson ${q(file)}) 'consultation' ${q(target)} '69d8462924cd920bbda3d501b92b0b3f492f49844f1f3e7ca811f6dd7c217e1d';'ALLOWED'}catch{'REFUSED '+$_.Exception.Message}`;
 const r=spawnSync('pwsh',['-NoProfile','-EncodedCommand',Buffer.from(code,'utf16le').toString('base64')],{encoding:'utf8',timeout:30000});assert.ifError(r.error);assert.equal(r.status,0,r.stderr);return r.stdout.trim();
}
test('selected identity exact consultation evidence accepted (no actual execution)',()=>assert.equal(runIdentity(()=>{}),'ALLOWED'));
for(const[name,mutate]of[['missing identity',c=>delete c.identityInitialization],['global identity claim',c=>c.identityInitialization.globalIdentityInitialized=true],['foreign domain',c=>c.identityInitialization.initializedDomains=['enforcement']],['all-domain claim',c=>c.identityInitialization.initializedDomains.push('adjudication')],['missing reconciliation',c=>delete c.identityInitialization.evidence.consultation.reconciliation],['wrong execution hash',c=>c.identityInitialization.evidence.consultation.execution.sha256='f'.repeat(64)]])test('selected identity fails closed: '+name,()=>assert.match(runIdentity(mutate),/^REFUSED /));

// Register the admitted adjudication receipt guards in the existing governance entrypoint.
import './single-database-adjudication-identity-admission.test.mjs';

// D6.25 enforcement receipt guards; isolated harness never writes database state.
import './single-database-enforcement-identity-admission.test.mjs';
