import test from 'node:test';
import assert from 'node:assert/strict';
import { gzipSync } from 'node:zlib';
import { archiveMembers, fieldLocators, sha256 } from './patent-archive.mjs';

// Parser-only constructed bytes; never collected patent input or product/benchmark evidence.
function tarMember(name, body, type = '0') {
  const header = Buffer.alloc(512);
  header.write(name, 0);
  header.write('0000644\0', 100);
  header.write('0000000\0', 108);
  header.write('0000000\0', 116);
  header.write(body.length.toString(8).padStart(11, '0') + '\0', 124);
  header.write('00000000000\0', 136);
  header.fill(32, 148, 156);
  header.write(type, 156);
  header.write('ustar\0', 257);
  const sum = [...header].reduce((a, b) => a + b, 0);
  header.write(sum.toString(8).padStart(6, '0') + '\0 ', 148);
  return Buffer.concat([header, body, Buffer.alloc((512 - body.length % 512) % 512)]);
}
const valid = Buffer.from(JSON.stringify({ title: '真实 Unicode "text"', claims: '1. claim' }));

test('Complete member retains exact bytes, path, offset and SHA', () => {
  const [member] = archiveMembers(gzipSync(tarMember('2016/real.json', valid)));
  assert.deepEqual(member.bytes, valid);
  assert.equal(member.path, '2016/real.json');
  assert.equal(member.tarContentOffset, 512);
  assert.equal(sha256(member.bytes), sha256(valid));
});
test('Incomplete trailing member is excluded', () => {
  const bytes = Buffer.concat([tarMember('first.json', valid), tarMember('partial.json', valid)]);
  assert.equal(archiveMembers(gzipSync(bytes.subarray(0, 1024 + 520))).length, 1);
});
test('Bounded gzip prefix accepts complete members without pretending full archive CRC verified', () => {
  const compressed = gzipSync(tarMember('record.json', valid));
  assert.equal(archiveMembers(compressed.subarray(0, compressed.length - 8)).length, 1);
});
test('Checksum corruption rejects', () => {
  const bytes = tarMember('record.json', valid);
  bytes[0] ^= 1;
  assert.throws(() => archiveMembers(gzipSync(bytes)), /checksum/);
});
test('Traversal, absolute, backslash and drive paths reject', () => {
  for (const path of ['../x.json', '/x.json', 'dir/../x.json', 'C:/x.json', 'dir\\x.json']) {
    assert.throws(() => archiveMembers(gzipSync(tarMember(path, valid))), /Unsafe/);
  }
});
test('Link entries and invalid JSON reject', () => {
  assert.throws(() => archiveMembers(gzipSync(tarMember('link.json', valid, '2'))), /Unsupported/);
  assert.throws(() => archiveMembers(gzipSync(tarMember('broken.json', Buffer.from('{')))));
});
test('Invalid UTF-8 and decompression expansion limit reject', () => {
  assert.throws(() => archiveMembers(gzipSync(tarMember('invalid.json', Buffer.from([255])))));
  assert.throws(() => archiveMembers(gzipSync(tarMember('record.json', valid)), { maxExpandedBytes: 10 }));
});
test('Exact field value byte locators include Unicode, escaping and nested values', () => {
  const original = { title: '中英文 "phrase"', claims: '1. a\\b', array: [{ nested: 'value' }], empty: null };
  const bytes = Buffer.from(JSON.stringify(original, null, 2));
  for (const locator of fieldLocators(bytes, original)) {
    assert.deepEqual(JSON.parse(bytes.subarray(locator.valueUtf8Start, locator.valueUtf8EndExclusive).toString('utf8')), original[locator.field]);
  }
});
test('Duplicate or mismatched field locations reject', () => {
  assert.throws(() => fieldLocators(Buffer.from('{"title":"a","title":"b"}'), { title: 'b' }), /Ambiguous/);
  assert.throws(() => fieldLocators(valid, { title: 'wrong' }), /mismatch/);
});
