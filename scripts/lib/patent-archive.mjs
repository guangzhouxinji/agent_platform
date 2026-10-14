import { createHash } from 'node:crypto';
import { gunzipSync, constants } from 'node:zlib';

export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const decoder = new TextDecoder('utf-8', { fatal: true });
const text = bytes => decoder.decode(bytes).replace(/\0.*$/s, '');

function octal(bytes) {
  const value = text(bytes).trim();
  if (!/^[0-7]+$/.test(value)) throw new Error('Invalid tar octal number');
  const result = parseInt(value, 8);
  if (!Number.isSafeInteger(result)) throw new Error('Unsafe tar size');
  return result;
}

export function archiveMembers(prefix, { limit = 20, maxExpandedBytes = 128 * 1024 * 1024 } = {}) {
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new Error('Invalid record limit');
  const expanded = gunzipSync(prefix, { finishFlush: constants.Z_SYNC_FLUSH, maxOutputLength: maxExpandedBytes });
  const members = [];
  let offset = 0;
  while (offset + 512 <= expanded.length && members.length < limit) {
    const header = expanded.subarray(offset, offset + 512);
    if (header.every(byte => byte === 0)) break;
    const checksum = [...header].reduce((sum, byte, i) => sum + (i >= 148 && i < 156 ? 32 : byte), 0);
    if (checksum !== octal(header.subarray(148, 156))) throw new Error('Tar header checksum mismatch');
    const name = text(header.subarray(0, 100));
    const prefixName = text(header.subarray(345, 500));
    const path = [prefixName, name].filter(Boolean).join('/');
    if (!path || path.startsWith('/') || /[\\:\x00-\x1f]/.test(path) || path.split('/').includes('..')) {
      throw new Error('Unsafe archive member path');
    }
    const size = octal(header.subarray(124, 136));
    const type = text(header.subarray(156, 157));
    if (!['', '0', '5'].includes(type)) throw new Error(`Unsupported tar member type: ${type}`);
    if (type === '5' && size !== 0) throw new Error('Directory member has nonzero size');
    const bodyOffset = offset + 512;
    if (bodyOffset + size > expanded.length) break; // Partial member never becomes a record.
    if (type !== '5' && path.endsWith('.json')) {
      const bytes = Buffer.from(expanded.subarray(bodyOffset, bodyOffset + size));
      const record = JSON.parse(decoder.decode(bytes));
      if (!record || Array.isArray(record) || typeof record !== 'object') throw new Error('Invalid patent JSON');
      members.push({ path, tarHeaderOffset: offset, tarContentOffset: bodyOffset, bytes, record });
    }
    offset = bodyOffset + Math.ceil(size / 512) * 512;
  }
  if (!members.length) throw new Error('No complete JSON members in captured prefix');
  return members;
}

// The raw member is retained: these locators describe original fields, not normalized product text.
export function fieldLocators(bytes, record) {
  const raw = decoder.decode(bytes);
  return Object.keys(record).map(key => {
    const encodedKey = JSON.stringify(key);
    const pattern = new RegExp(`(?:^|[,{])\\s*(${encodedKey.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})\\s*:`, 'g');
    const matches = [...raw.matchAll(pattern)];
    if (matches.length !== 1) throw new Error(`Ambiguous JSON field locator: ${key}`);
    const match = matches[0];
    const charOffset = match.index + match[0].indexOf(encodedKey);
    const utf8Start = Buffer.byteLength(raw.slice(0, charOffset));
    let valueStart = match.index + match[0].length;
    while (/\s/.test(raw[valueStart] ?? '') && valueStart < raw.length) valueStart++;
    let valueEnd = valueStart;
    let depth = 0;
    let quoted = false;
    let escaped = false;
    for (; valueEnd < raw.length; valueEnd++) {
      const char = raw[valueEnd];
      if (quoted) {
        if (escaped) escaped = false;
        else if (char === '\\') escaped = true;
        else if (char === '"') quoted = false;
      } else if (char === '"') quoted = true;
      else if (char === '[' || char === '{') depth++;
      else if (char === ']' || char === '}') {
        if (depth === 0) break;
        depth--;
      } else if (char === ',' && depth === 0) break;
    }
    while (/\s/.test(raw[valueEnd - 1] ?? '') && valueEnd > valueStart) valueEnd--;
    if (JSON.stringify(JSON.parse(raw.slice(valueStart, valueEnd))) !== JSON.stringify(record[key])) {
      throw new Error(`JSON value locator mismatch: ${key}`);
    }
    return { field: key, jsonPointer: `/${key.replaceAll('~', '~0').replaceAll('/', '~1')}`,
      keyUtf8Start: utf8Start, keyUtf8EndExclusive: utf8Start + Buffer.byteLength(encodedKey),
      valueUtf8Start: Buffer.byteLength(raw.slice(0, valueStart)),
      valueUtf8EndExclusive: Buffer.byteLength(raw.slice(0, valueEnd)),
      rawSha256: sha256(bytes), encoding: 'UTF-8' };
  });
}
