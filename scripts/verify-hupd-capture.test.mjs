import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, cp, writeFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { fieldLocators } from './lib/patent-archive.mjs';

const capture = resolve('data/patents/hupd-real-candidate-20261002-v2');
const verify = output => spawnSync(process.execPath, ['scripts/collect-hupd-patents.mjs', `--output=${output}`, '--verify-only'], { encoding: 'utf8' });

test('20 actual collected patents have distinct raw publication IDs and exact text field locations', async () => {
  const manifest = JSON.parse(await readFile(join(capture, 'manifest.json')));
  assert.equal(manifest.records.length, 20);
  const publications = new Set();
  for (const item of manifest.records) {
    const bytes = await readFile(join(capture, item.file));
    const record = JSON.parse(bytes.toString('utf8'));
    assert.match(record.publication_number, /^US\d+[A-Z]\d-\d{8}$/);
    assert.ok(!publications.has(record.publication_number));
    publications.add(record.publication_number);
    for (const field of ['title', 'abstract', 'claims', 'full_description']) {
      assert.equal(typeof record[field], 'string');
      assert.ok(record[field].trim().length > 0);
      const locator = fieldLocators(bytes, record).find(l => l.field === field);
      assert.equal(JSON.parse(bytes.subarray(locator.valueUtf8Start, locator.valueUtf8EndExclusive).toString('utf8')), record[field]);
    }
  }
  const result = verify(capture);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).productEnabled, false);
});

async function changedCapture(change) {
  const parent = await mkdtemp(join(tmpdir(), 'agent-platform-hupd-integrity-'));
  const copy = join(parent, 'capture');
  try {
    await cp(capture, copy, { recursive: true, errorOnExist: true });
    await change(copy);
    return verify(copy);
  } finally {
    const absoluteParent = resolve(parent);
    assert.ok(absoluteParent.startsWith(resolve(tmpdir()) + '\\') || absoluteParent.startsWith(resolve(tmpdir()) + '/'));
    assert.ok(absoluteParent.includes('agent-platform-hupd-integrity-'));
    await rm(absoluteParent, { recursive: true });
  }
}

test('Collected original mutation is rejected rather than becoming a product record', async () => {
  const result = await changedCapture(async copy => {
    const file = join(copy, 'raw/member-0001.json');
    await writeFile(file, Buffer.concat([await readFile(file), Buffer.from(' ')]));
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Raw evidence drift/);
});

test('Candidate cannot be mislabeled product-enabled or given an altered field locator', async () => {
  for (const change of [m => { m.productEnabled = true; }, m => { m.records[0].locators[0].valueUtf8Start++; }]) {
    const result = await changedCapture(async copy => {
      const file = join(copy, 'manifest.json');
      const manifest = JSON.parse(await readFile(file));
      change(manifest);
      await writeFile(file, JSON.stringify(manifest));
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Capture boundary drift|Field locator drift/);
  }
});

test('Collector rejects existing output without overwriting original evidence', async () => {
  const result = spawnSync(process.execPath, ['scripts/collect-hupd-patents.mjs', `--output=${capture}`], { encoding: 'utf8' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /EEXIST/);
  assert.equal(verify(capture).status, 0);
});
