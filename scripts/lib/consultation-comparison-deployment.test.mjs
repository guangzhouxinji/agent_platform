import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

// Only temporary local fixtures and a real PowerShell interpreter. No real Docker/DB/network.
const root = resolve(import.meta.dirname, '../..');
const helper = join(root, 'scripts/lib/consultation-comparison-deployment.ps1');
const launcher = join(root, 'scripts/start-consultation-container.ps1');
const work = mkdtempSync(join(tmpdir(), 'comparison-preflight-'));
const secret = 'SENTINEL_PRIVATE_CREDENTIAL_NEVER_PRINT';
const quote = value => "'" + value.replaceAll("'", "''") + "'";
const pwsh = process.env.PWSH_PATH || 'pwsh';
const jar = join(work, 'release.jar');
const store = join(work, 'tls.p12');
writeFileSync(jar, 'temporary release fixture');
writeFileSync(store, 'mount existence fixture, not a certificate');
const base = {
  schema: 'CONSULTATION_CONTAINER/v1', image: 'eclipse-temurin@sha256:' + 'a'.repeat(64),
  jar, jarSha: createHash('sha256').update(readFileSync(jar)).digest('hex'), appName: 'product-consultation',
  evidenceRoot: '/mnt/docker-desktop-disk/agent-platform-product-files/test-evidence',
  evidenceManifest: '/mnt/docker-desktop-disk/agent-platform-product-files/test-manifest.sha256',
  evidenceManifestSha: 'b'.repeat(64), network: 'agent-platform-consultation-product-test',
  databases: [{ name: 'original-database', id: 'exact-original-id' }],
  mounts: [{ source: store, target: '/product/server.p12' }, { source: store, target: '/product/trust.p12' }],
  settings: {
    PRODUCT_SOURCE_EVIDENCE_ROOT: '/product/evidence', PRODUCT_CONSULTATION_COMPARISON_ENABLED: 'true',
    PRODUCT_IDENTITY_ENABLED: 'true', PRODUCT_SOURCE_ENGINE_ENABLED: 'true',
    PRODUCT_CONSULTATION_COMPARISON_APPROVAL_REF: 'approvals/model-v1.json',
    PRODUCT_CONSULTATION_COMPARISON_APPROVAL_SHA256: 'A'.repeat(64),
    PRODUCT_CONSULTATION_MODEL_API_KEY: secret, SERVER_SSL_ENABLED: 'true', SERVER_SSL_CLIENT_AUTH: 'want',
    SERVER_SSL_KEY_STORE: 'file:/product/server.p12', SERVER_SSL_TRUST_STORE: '/product/trust.p12',
    SERVER_SSL_KEY_STORE_PASSWORD: secret, SERVER_SSL_TRUST_STORE_PASSWORD: secret,
  }, secretFiles: {},
};
function run(config, mode = 'helper', stub = null) {
  const file = join(work, 'configuration.json');
  writeFileSync(file, JSON.stringify(config));
  const command = `$ErrorActionPreference='Stop'
function docker { throw 'MOCK_DOCKER_BOUNDARY' }
${stub ? dockerStub(config, stub) : ''}
try {
  if (${quote(mode)} -eq 'helper') {
    . ${quote(helper)}
    $config=Get-Content -LiteralPath ${quote(file)} -Raw|ConvertFrom-Json -AsHashtable
    $before=$config|ConvertTo-Json -Depth 20 -Compress
    $environment=@(Get-ChildItem Env:|Sort-Object Name|ForEach-Object { $_.Name+'='+$_.Value }) -join "\n"
    $result=Get-ConsultationDeploymentSettings -Config $config
    if(($config|ConvertTo-Json -Depth 20 -Compress) -cne $before){throw 'CONFIG_MUTATED'}
    if((@(Get-ChildItem Env:|Sort-Object Name|ForEach-Object { $_.Name+'='+$_.Value }) -join "\n") -cne $environment){throw 'ENV_MUTATED'}
    if($result -isnot [System.Collections.IDictionary]){throw 'INVALID_RETURN'}
    $result.Clear()
    Write-Output 'HELPER_OK'
  } else {
    & ${quote(launcher)} -Configuration ${quote(file)} -ValidateOnly:(${quote(mode)} -in @('validate','both')) -OfflineOnly:(${quote(mode)} -in @('offline','both'))
  }
} catch { Write-Output ('REJECTED: '+$_.Exception.Message); exit 1 }
finally { if($mockCalls){Write-Output ('MOCK_CALLS_JSON='+($mockCalls.ToArray()|ConvertTo-Json -Compress -AsArray))} }
`;
  const result = spawnSync(pwsh, ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(command, 'utf16le').toString('base64')], { encoding: 'utf8', timeout: 20000 });
  assert.ifError(result.error);
  const output = result.stdout + result.stderr;
  assert.ok(!output.includes(secret), 'credential must never appear in output');
  const trace = output.match(/^MOCK_CALLS_JSON=(.*)$/m);
  return { status: result.status, output, calls: trace ? JSON.parse(trace[1]) : [] };
}
function reject(config, modes = ['helper']) {
  for (const mode of modes) {
    const result = run(config, mode);
    assert.equal(result.status, 1, `${mode}: ${result.output}`);
    assert.ok(!result.output.includes('MOCK_DOCKER_BOUNDARY'), 'preflight must precede every Docker call');
    assert.match(result.output, /Offline deployment configuration invalid/);
  }
}
const copy = () => structuredClone(base);

test.after(() => rmSync(work, { recursive: true, force: true }));
test('real helper is silent, leaves configuration/environment unchanged; OfflineOnly is offline; ValidateOnly and deployment share preflight', () => {
  assert.equal(run(base).status, 0);
  const validated = run(base, 'offline');
  assert.equal(validated.status, 0, validated.output);
  assert.match(validated.output, /offline_deployment_configuration=verified/);
  assert.doesNotMatch(validated.output, /MOCK_DOCKER|readonly=verified/);
  const checked = run(base, 'validate');
  assert.equal(checked.status, 1);
  assert.match(checked.output, /MOCK_DOCKER_BOUNDARY/);
  const deployed = run(base, 'deploy');
  assert.equal(deployed.status, 1);
  assert.match(deployed.output, /MOCK_DOCKER_BOUNDARY/);
});
test('missing model credential rejects helper, ValidateOnly and deployment before Docker', () => {
  const c = copy(); delete c.settings.PRODUCT_CONSULTATION_MODEL_API_KEY;
  reject(c, ['helper', 'offline', 'validate', 'deploy']);
});
for (const key of ['PRODUCT_IDENTITY_ENABLED', 'PRODUCT_SOURCE_ENGINE_ENABLED', 'SERVER_SSL_ENABLED']) {
  for (const value of [undefined, 'false', 'yes', ' true ', secret]) {
    test(`required enabled flag ${key}: ${value === secret ? 'private invalid value' : String(value)}`, () => {
      const c = copy(); c.settings[key] = value; reject(c);
    });
  }
}
for (const value of ['', 'yes', 'true\n', secret]) {
  test(`invalid calculation flag: ${value === secret ? 'private invalid value' : JSON.stringify(value)}`, () => {
    const c = copy(); c.settings.PRODUCT_CONSULTATION_COMPARISON_ENABLED = value; reject(c);
  });
}
for (const value of [undefined, '', '/absolute.json', '../escape.json', 'a/../b', 'https://host/x', 'a\\b', 'a%2fb', 'a?b', 'a..b', 'a'.repeat(501), 'a\n']) {
  test(`invalid approval relative reference: ${JSON.stringify(value)}`, () => {
    const c = copy(); c.settings.PRODUCT_CONSULTATION_COMPARISON_APPROVAL_REF = value; reject(c);
  });
}
for (const value of [undefined, '', 'a'.repeat(63), 'a'.repeat(65), 'g'.repeat(64), 'a'.repeat(64) + '\n']) {
  test(`invalid approval SHA: ${String(value)?.length}`, () => {
    const c = copy(); c.settings.PRODUCT_CONSULTATION_COMPARISON_APPROVAL_SHA256 = value; reject(c);
  });
}
for (const key of ['PRODUCT_CONSULTATION_MODEL_API_KEY', 'SERVER_SSL_KEY_STORE_PASSWORD', 'SERVER_SSL_TRUST_STORE_PASSWORD']) {
  test(`empty credential rejects: ${key}`, () => {
    const c = copy(); c.settings[key] = ' \t\n'; reject(c);
  });
}
for (const value of [undefined, 'none', '', 'want\n']) {
  test(`invalid mTLS mode: ${JSON.stringify(value)}`, () => {
    const c = copy(); c.settings.SERVER_SSL_CLIENT_AUTH = value; reject(c);
  });
}
for (const key of ['SERVER_SSL_KEY_STORE', 'SERVER_SSL_TRUST_STORE']) {
  for (const value of [undefined, 'https://remote/store', 'file://remote/store', '/product/../store', '/product/missing.p12']) {
    test(`missing/unmanaged TLS store: ${key} ${String(value)}`, () => {
      const c = copy(); c.settings[key] = value; reject(c);
    });
  }
}
test('managed TLS mount source must exist and mount target must be unique', () => {
  const missing = copy(); missing.mounts[0].source = join(work, secret); reject(missing);
  const duplicate = copy(); duplicate.mounts.push(duplicate.mounts[0]); reject(duplicate);
});
test('schema, dictionary and fixed evidence root reject', () => {
  for (const change of [c => c.schema = 'other', c => c.settings = [], c => c.secretFiles = [], c => c.settings.PRODUCT_SOURCE_EVIDENCE_ROOT = '/other']) {
    const c = copy(); change(c); reject(c);
  }
});
test('calculation off/absent does not invent a Worker or approval requirement', () => {
  for (const flag of [undefined, 'false']) {
    const c = copy(); c.settings = { PRODUCT_SOURCE_EVIDENCE_ROOT: '/product/evidence', PRODUCT_CONSULTATION_COMPARISON_ENABLED: flag };
    assert.equal(run(c).status, 0);
    assert.equal(run(c, 'offline').status, 0);
  }
  const c = copy(); c.settings.SERVER_SSL_CLIENT_AUTH = 'need'; assert.equal(run(c).status, 0);
});
test('managed SecureString/PSCredential imports, ambiguity, empty/wrong/missing credentials; no value/path disclosure', () => {
  const secure = join(work, 'secure.xml'); const account = join(work, 'account.xml');
  const empty = join(work, 'empty.xml'); const wrong = join(work, 'wrong.xml');
  const generated = spawnSync(pwsh, ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(`
$s=ConvertTo-SecureString ${quote(secret)} -AsPlainText -Force
$s|Export-Clixml -LiteralPath ${quote(secure)}
[PSCredential]::new('fixture',$s)|Export-Clixml -LiteralPath ${quote(account)}
[Security.SecureString]::new()|Export-Clixml -LiteralPath ${quote(empty)}
${quote(secret)}|Export-Clixml -LiteralPath ${quote(wrong)}
`, 'utf16le').toString('base64')], { encoding: 'utf8' });
  assert.equal(generated.status, 0, generated.stderr);
  for (const file of [secure, account]) {
    const c = copy(); delete c.settings.PRODUCT_CONSULTATION_MODEL_API_KEY;
    c.secretFiles.PRODUCT_CONSULTATION_MODEL_API_KEY = file;
    assert.equal(run(c).status, 0); assert.equal(run(c, 'offline').status, 0);
  }
  for (const file of [empty, wrong, join(work, secret), '']) {
    const c = copy(); delete c.settings.PRODUCT_CONSULTATION_MODEL_API_KEY;
    c.secretFiles.PRODUCT_CONSULTATION_MODEL_API_KEY = file; reject(c);
  }
  const ambiguous = copy(); ambiguous.secretFiles.PRODUCT_CONSULTATION_MODEL_API_KEY = secure; reject(ambiguous);
});
test('launcher retains exact identities, network ownership, read-only, loopback and rollback guards', () => {
  const source = readFileSync(launcher, 'utf8');
  for (const guard of ['Existing database identity mismatch', 'Network ownership mismatch', 'Original database absent from managed network', 'Image must not create data volumes', 'Evidence manifest SHA mismatch', 'Actual mounted release SHA mismatch', 'Exact replacement identity required', "'--read-only'", "'10001:10001'", "'127.0.0.1:5443:5443'", "'[::1]:5443:5443'", "if($oldRenamed)", 'if($oldStopped)']) assert.ok(source.includes(guard), guard);
  assert.equal((source.match(/Get-ConsultationDeploymentSettings -Config/g) || []).length, 1);
  assert.ok(source.indexOf('Get-ConsultationDeploymentSettings -Config') < source.indexOf("@('inspect'"));
});

test('bad flag, ref, SHA and TLS configuration reject all three real launcher modes before Docker', () => {
  for (const [key, value] of [
    ['PRODUCT_CONSULTATION_COMPARISON_ENABLED', 'yes'], ['PRODUCT_SOURCE_ENGINE_ENABLED', 'false'],
    ['PRODUCT_CONSULTATION_COMPARISON_APPROVAL_REF', '../escape'],
    ['PRODUCT_CONSULTATION_COMPARISON_APPROVAL_SHA256', 'g'.repeat(64)],
    ['SERVER_SSL_CLIENT_AUTH', 'none'], ['SERVER_SSL_TRUST_STORE_PASSWORD', ' '],
  ]) {
    const c = copy(); c.settings[key] = value; reject(c, ['offline', 'validate', 'deploy']);
  }
});
test('existing scalar settings and standard absolute file URI remain supported', () => {
  const c = copy(); c.settings.SERVER_PORT = 5443;
  c.settings.PRODUCT_CONSULTATION_COMPARISON_ENABLED = true;
  c.settings.SERVER_SSL_KEY_STORE = 'file:///product/server.p12';
  assert.equal(run(c).status, 0); assert.equal(run(c, 'offline').status, 0);
});

test('launcher sanitizes malformed JSON and non-dictionary roots without credential disclosure', () => {
  for (const rootValue of [[secret], secret, null]) {
    for (const mode of ['offline', 'validate', 'deploy']) {
      const result = run(rootValue, mode);
      assert.equal(result.status, 1);
      assert.match(result.output, /Deployment configuration unavailable or invalid/);
      assert.doesNotMatch(result.output, /MOCK_DOCKER_BOUNDARY/);
    }
  }
});


// Mock only the existing Docker command surface, never a CLI, daemon, container or network.
function dockerStub(config, spec) {
  return `
$mockFixture=Get-Content -LiteralPath ${quote(join(work, 'configuration.json'))} -Raw|ConvertFrom-Json -AsHashtable
$mockSpec=${quote(JSON.stringify(spec))}|ConvertFrom-Json -AsHashtable
$mockCalls=[Collections.Generic.List[string]]::new()
function docker {
  $global:LASTEXITCODE=0
  switch($args[0]) {
    'inspect' {
      $mockCalls.Add('database')
      if($args.Count -ne 4 -or $args[2] -ne '{{.Id}}' -or $args[3] -ne $mockFixture.databases[0].name){throw 'INVALID_DATABASE_INSPECT'}
      if($mockSpec.fail -eq 'database'){return 'wrong-id'}
      return $mockFixture.databases[0].id
    }
    'network' {
      $mockCalls.Add('network')
      if($args[1] -ne 'inspect' -or $args[2] -ne $mockFixture.network){throw 'INVALID_NETWORK_INSPECT'}
      $members=@{}; $members[$mockFixture.databases[0].id]=@{}
      if($mockSpec.fail -eq 'membership'){$members=@{}}
      $role=if($mockSpec.fail -eq 'network'){'wrong-role'}else{'consultation-product'}
      return (@{Labels=@{'agent-platform.role'=$role}; Containers=$members}|ConvertTo-Json -Depth 5 -Compress)
    }
    'image' {
      $mockCalls.Add('image')
      if($args[1] -ne 'inspect' -or $args[3] -ne '{{json .Config.Volumes}}' -or $args[4] -ne $mockFixture.image){throw 'INVALID_IMAGE_INSPECT'}
      if($mockSpec.fail -eq 'image'){return '{}'}
      return 'null'
    }
    'run' {
      foreach($required in @('--rm','--read-only','--user','10001:10001','--network','none','--cap-drop','ALL')){
        if($args -notcontains $required){throw 'PROBE_SECURITY_WEAKENED'}
      }
      if($args -notcontains $mockFixture.image){throw 'PROBE_IMAGE_CHANGED'}
      if($args -contains ('type=bind,source='+$mockFixture.evidenceRoot+',target=/evidence,readonly')){
        $mockCalls.Add('evidence')
        if($args -notcontains ('type=bind,source='+$mockFixture.evidenceManifest+',target=/manifest,readonly') -or
           $args[-1] -notmatch 'test ! -w retention-v1.json' -or $args[-1] -notmatch 'sha256sum -c /manifest'){throw 'EVIDENCE_CHECK_WEAKENED'}
        $sha=if($mockSpec.fail -eq 'evidence'){'wrong-sha'}else{$mockFixture.evidenceManifestSha}
        return @(($sha+'  /manifest'),'complete_evidence_readonly=verified')
      }
      $jarSource=if($mockFixture.containerJar){$mockFixture.containerJar}else{$mockFixture.jar}
      if($args -contains ('type=bind,source='+$jarSource+',target=/release.jar,readonly')){
        $mockCalls.Add('jar')
        if($args[-1] -notmatch 'sha256sum /release.jar'){throw 'JAR_CHECK_WEAKENED'}
        $sha=if($mockSpec.fail -eq 'jar'){'wrong-sha'}else{$mockFixture.jarSha}
        return $sha+'  /release.jar'
      }
      if($mockFixture.runtimeRoot -and $args -contains ('type=bind,source='+$mockFixture.runtimeRoot+',target=/runtime,readonly')){
        $mockCalls.Add('runtime')
        if($args[-1] -notmatch 'test ! -w .' -or $args[-1] -notmatch 'sha256sum -c runtime-manifest.sha256'){throw 'RUNTIME_CHECK_WEAKENED'}
        $sha=if($mockSpec.fail -eq 'runtime'){'wrong-sha'}else{$mockFixture.runtimeManifestSha}
        return $sha+'  runtime-manifest.sha256'
      }
      throw 'UNEXPECTED_DOCKER_PROBE'
    }
    'ps' { $mockCalls.Add('deployment-boundary'); throw 'MOCK_DEPLOYMENT_BOUNDARY' }
    default { $mockCalls.Add('FORBIDDEN_MUTATION'); throw 'FORBIDDEN_DOCKER_MUTATION' }
  }
}
`;
}

const completeChecks = ['database', 'network', 'image', 'evidence', 'jar'];
test('ValidateOnly preserves all original Docker checks in order, without deployment operations', () => {
  const result = run(base, 'validate', {});
  assert.equal(result.status, 0, result.output);
  assert.deepEqual(result.calls, completeChecks);
  assert.match(result.output, /release\/database\/complete_evidence\/actual_mounted_release\/readonly=verified/);
  assert.doesNotMatch(result.output, /offline_deployment_configuration|MOCK_DEPLOYMENT_BOUNDARY/);
});
test('OfflineOnly returns after shared helper alone; no Docker or release claim', () => {
  const c=copy(); delete c.jar; delete c.jarSha; delete c.image; delete c.appName;
  const result=run(c, 'offline', {});
  assert.equal(result.status, 0, result.output);
  assert.deepEqual(result.calls, []);
  assert.match(result.output, /offline_deployment_configuration=verified/);
  assert.doesNotMatch(result.output, /readonly=verified/);
});
test('deployment retains full checks and advances only beyond ValidateOnly return', () => {
  const result=run(base, 'deploy', {});
  assert.equal(result.status, 1);
  assert.match(result.output, /MOCK_DEPLOYMENT_BOUNDARY/);
  assert.deepEqual(result.calls, [...completeChecks, 'deployment-boundary']);
});
test('both switches reject before configuration/helper/Docker and without secret disclosure', () => {
  const c=copy(); c.settings.PRODUCT_CONSULTATION_MODEL_API_KEY='';
  const result=run(c, 'both', {});
  assert.equal(result.status, 1);
  assert.match(result.output, /ValidateOnly and OfflineOnly are mutually exclusive/);
  assert.deepEqual(result.calls, []);
  assert.doesNotMatch(result.output, /configuration=verified|readonly=verified/);
});
for(const [failure, message, calls] of [
  ['database','Existing database identity mismatch',['database']],
  ['network','Network ownership mismatch',['database','network']],
  ['membership','Original database absent from managed network',['database','network']],
  ['image','Image must not create data volumes',['database','network','image']],
  ['evidence','Evidence manifest SHA mismatch',['database','network','image','evidence']],
  ['jar','Actual mounted release SHA mismatch',completeChecks],
]) {
  test('ValidateOnly still rejects original Docker guard: '+failure, () => {
    const result=run(base,'validate',{fail:failure});
    assert.equal(result.status,1,result.output);
    assert.ok(result.output.includes(message),result.output);
    assert.deepEqual(result.calls,calls);
    assert.doesNotMatch(result.output,/readonly=verified|MOCK_DEPLOYMENT_BOUNDARY/);
  });
}
test('ValidateOnly preserves optional private runtime manifest check before success', () => {
  const c=copy(); c.runtimeRoot='/mnt/docker-desktop-disk/agent-platform-product-files/runtime-consultation-test';
  c.runtimeManifestSha='c'.repeat(64); c.containerJar=c.runtimeRoot+'/platform-api.jar';
  for(const [i,mount] of c.mounts.entries())mount.containerSource=c.runtimeRoot+'/store-'+i+'.p12';
  const ok=run(c,'validate',{});
  assert.equal(ok.status,0,ok.output); assert.deepEqual(ok.calls,[...completeChecks,'runtime']);
  const bad=run(c,'validate',{fail:'runtime'});
  assert.equal(bad.status,1); assert.match(bad.output,/Private runtime manifest SHA mismatch/);
  assert.deepEqual(bad.calls,[...completeChecks,'runtime']);
  assert.doesNotMatch(bad.output,/readonly=verified/);
});

test('failed release and protection backup never silently resume previous writer', () => {
  const start = readFileSync(launcher, 'utf8');
  const failure = start.slice(start.indexOf('} catch {', start.indexOf('oldWasRunning')));
  assert.ok(failure.includes('old-JAR write compatibility'));
  assert.ok(!failure.includes("@('start',$config.appName)"));
  const backup = readFileSync(join(root, 'scripts/backup-consultation-container.ps1'), 'utf8');
  assert.ok(backup.includes('$sealed=$false'));
  assert.ok(backup.includes('$stopped -and $sealed -and $ResumeWriterOnSuccess'));
  assert.ok(backup.includes('Writer remains stopped'));
});

test('explicit human start needs bounded managed service TLS and cannot silently inherit browser choices',()=>{
 const enabled=structuredClone(base);Object.assign(enabled.settings,{
  PRODUCT_CONSULTATION_COMPARISON_START_ENABLED:'true',
  PRODUCT_CONSULTATION_COMPARISON_START_WORKER_SUBJECT:'managed-comparison-service',
  PRODUCT_CONSULTATION_COMPARISON_START_WORKER_PASSWORD:secret,
  PRODUCT_CONSULTATION_COMPARISON_START_TRUSTSTORE_PASSWORD:secret,
  PRODUCT_CONSULTATION_COMPARISON_START_WORKER_KEYSTORE:'/product/server.p12',
  PRODUCT_CONSULTATION_COMPARISON_START_TRUSTSTORE:'/product/trust.p12',
  PRODUCT_CONSULTATION_COMPARISON_START_PRODUCT_ORIGIN:'https://127.0.0.1:5443',
  PRODUCT_CONSULTATION_COMPARISON_START_LEASE_MILLIS:'60000',
 });
 assert.equal(run(enabled).status,0);
 for(const key of ['PRODUCT_CONSULTATION_COMPARISON_START_WORKER_SUBJECT','PRODUCT_CONSULTATION_COMPARISON_START_WORKER_PASSWORD','PRODUCT_CONSULTATION_COMPARISON_START_TRUSTSTORE']){
  const missing=structuredClone(enabled);delete missing.settings[key];const result=run(missing);assert.equal(result.status,1);assert.ok(!result.output.includes(secret));
 }
 for(const [key,value] of [
  ['PRODUCT_CONSULTATION_COMPARISON_START_ENABLED','on'],
  ['PRODUCT_CONSULTATION_COMPARISON_ENABLED','false'],
  ['PRODUCT_CONSULTATION_COMPARISON_START_LEASE_MILLIS','0'],
  ['PRODUCT_CONSULTATION_COMPARISON_START_LEASE_MILLIS','90001'],
  ['PRODUCT_CONSULTATION_COMPARISON_START_PRODUCT_ORIGIN','http://127.0.0.1:5443'],
  ['PRODUCT_CONSULTATION_COMPARISON_START_PRODUCT_ORIGIN','https://untrusted@127.0.0.1:5443'],
  ['PRODUCT_CONSULTATION_COMPARISON_START_WORKER_KEYSTORE','/product/../worker.p12'],
 ]){const bad=structuredClone(enabled);bad.settings[key]=value;const result=run(bad);assert.equal(result.status,1,`${key} ${value}`);assert.ok(!result.output.includes(secret));}
});

// Explicit new-generation split; legacy absent-flag assertions above remain unchanged.
test('explicit handoff-only comparison does not require model credentials or approval',()=>{
 const c=copy();c.settings.PRODUCT_CONSULTATION_COMPARISON_CALCULATION_ENABLED='false';
 delete c.settings.PRODUCT_CONSULTATION_MODEL_API_KEY;delete c.settings.PRODUCT_CONSULTATION_COMPARISON_APPROVAL_REF;delete c.settings.PRODUCT_CONSULTATION_COMPARISON_APPROVAL_SHA256;
 assert.equal(run(c).status,0);assert.equal(run(c,'offline').status,0);
 for(const key of ['PRODUCT_IDENTITY_ENABLED','PRODUCT_SOURCE_ENGINE_ENABLED','SERVER_SSL_ENABLED','SERVER_SSL_KEY_STORE_PASSWORD']){const bad=structuredClone(c);delete bad.settings[key];reject(bad);}
 const compute=structuredClone(c);compute.settings.PRODUCT_CONSULTATION_COMPARISON_CALCULATION_ENABLED='true';reject(compute);
 const malformed=structuredClone(c);malformed.settings.PRODUCT_CONSULTATION_COMPARISON_CALCULATION_ENABLED='maybe';reject(malformed);
});
