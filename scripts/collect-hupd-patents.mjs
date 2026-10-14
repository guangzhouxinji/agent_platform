import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { archiveMembers, fieldLocators, sha256 } from './lib/patent-archive.mjs';

const revision = 'f570a84b03663180b6034c1f7f4c15864f94385e';
const archivePath = 'data/sample-jan-2016.tar.gz';
const publishedArchiveSha256 = 'c8db7a2e730f04a95946847d39a74b4a9ea53b0f4fd357bb8789abd77684e37f';
const archiveSize = 387636489;
const argument = name => process.argv.find(a => a.startsWith(`--${name}=`))?.slice(name.length + 3);
const outputArgument = argument('output');
if (!outputArgument) throw new Error('Required: --output=<new candidate capture directory>');
const output = resolve(outputArgument);
const identifiersOf = record => Object.fromEntries(Object.entries(record)
  .filter(([key]) => /(?:publication|application|patent).*number|^patent_id$/.test(key)));

async function boundedFetch(url, maxBytes, headers = {}) {
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(120000) });
  if (!response.ok) throw new Error(`HTTP ${response.status} for stable source URL ${url}`);
  if (headers.Range && (response.status !== 206 || response.headers.get('content-range') !== `bytes 0-${maxBytes - 1}/${archiveSize}`)) {
    await response.body.cancel();
    throw new Error('Archive range response not exact');
  }
  const chunks = [];
  let length = 0;
  for await (const chunk of response.body) {
    length += chunk.length;
    if (length > maxBytes) throw new Error('Response exceeded byte limit');
    chunks.push(chunk);
  }
  if (headers.Range && length !== maxBytes) throw new Error('Truncated HTTP range response');
  return { bytes: Buffer.concat(chunks), url, collectedAt: new Date().toISOString(),
    status: response.status, contentRange: response.headers.get('content-range'),
    contentType: response.headers.get('content-type'), sha256: sha256(Buffer.concat(chunks)) };
}

async function saveResponse(name, response) {
  await writeFile(join(output, 'raw', name), response.bytes, { flag: 'wx' });
  const { bytes, ...metadata } = response;
  return { ...metadata, file: `raw/${name}`, bytes: bytes.length };
}

async function verifyCapture() {
  const manifest = JSON.parse(await readFile(join(output, 'manifest.json')));
  if (manifest.sourceId !== 'HUPD_PUBLIC_ARCHIVE' || manifest.status !== 'REAL_CANDIDATE'
      || manifest.sourceVersion !== revision || manifest.publishedArchiveSha256 !== publishedArchiveSha256
      || manifest.archiveSize !== archiveSize || manifest.archivePath !== archivePath
      || manifest.fullArchiveSha256Verified !== false || manifest.productEnabled !== false
      || manifest.rightsEvidenceStatus !== 'CONFLICTING_PUBLIC_LICENSE_STATEMENTS'
      || manifest.sampling.queryResponse !== false || manifest.sampling.professionalQrels !== false) throw new Error('Capture boundary drift');
  for (const item of [...manifest.responses, ...manifest.records]) {
    const path = resolve(output, item.file);
    if (!path.startsWith(output + '/') && !path.startsWith(output + '\\')) throw new Error('Manifest path escape');
    const bytes = await readFile(path);
    if (sha256(bytes) !== item.sha256 || bytes.length !== item.bytes) throw new Error(`Raw evidence drift: ${item.file}`);
    if (item.locators) {
      const record = JSON.parse(bytes.toString('utf8'));
      const actual = fieldLocators(bytes, record);
      if (JSON.stringify(actual) !== JSON.stringify(item.locators)) throw new Error('Field locator drift');
      if (JSON.stringify(Object.keys(record)) !== JSON.stringify(item.fields)
          || JSON.stringify(identifiersOf(record)) !== JSON.stringify(item.identifiers)
          || item.documentVersion !== `RAW_SHA256:${sha256(bytes)}`) throw new Error('Record identity or version drift');
    }
  }
  const metadata = JSON.parse(await readFile(join(output, 'raw', 'hosted-metadata.json')));
  const author = JSON.parse(await readFile(join(output, 'raw', 'author-commit.json')));
  const authorReadme = await readFile(join(output, 'raw', 'author-README.md'), 'utf8');
  const hostedReadme = await readFile(join(output, 'raw', 'hosted-README.md'), 'utf8');
  if (metadata.sha !== revision || author.sha !== manifest.licenseEvidence.authorRevision
      || JSON.stringify(metadata.cardData?.license) !== JSON.stringify(manifest.licenseEvidence.hostedDataStatement)) throw new Error('License evidence binding drift');
  if (!authorReadme.includes('Creative Commons Attribution 4.0 International License')
      || !hostedReadme.includes('Attribution-NonCommercial-ShareAlike 4.0 International')) throw new Error('Recorded public license statements changed');
  const prefix = await readFile(join(output, 'raw', 'archive-prefix.tar.gz'));
  if (prefix.length !== manifest.sampling.prefixBytes) throw new Error('Prefix range length drift');
  const members = archiveMembers(prefix, { limit: manifest.records.length });
  for (const [i, member] of members.entries()) {
    const saved = manifest.records[i];
    if (member.path !== saved.archiveMember || sha256(member.bytes) !== saved.sha256
        || member.tarContentOffset !== saved.tarContentOffset) throw new Error('Archive member binding drift');
  }
  console.log(JSON.stringify({ verified: true, records: manifest.records.length, status: manifest.status,
    productEnabled: false, fullArchiveSha256Verified: false,
    licenseStatements: ['AUTHOR_CC_BY_4', 'HOSTED_METADATA_CC_BY_SA_4', 'HOSTED_README_CC_BY_NC_SA_4'] }));
}

if (process.argv.includes('--verify-only')) {
  await verifyCapture();
} else {
  const limit = Number(argument('limit') ?? '20');
  const prefixBytes = Number(argument('prefix-bytes') ?? String(4 * 1024 * 1024));
  if (!Number.isInteger(prefixBytes) || prefixBytes < 512 || prefixBytes > 32 * 1024 * 1024) throw new Error('Invalid bounded range');
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new Error('Invalid bounded record count');
  // Reject reuse even after a partial failed capture; never overwrite an original.
  await mkdir(output, { recursive: false });
  await mkdir(join(output, 'raw'));
  const responses = [];
  const save = async (name, url, max = 2 * 1024 * 1024, headers = {}) => {
    const response = await boundedFetch(url, max, headers);
    responses.push(await saveResponse(name, response));
    return response.bytes;
  };
  const authorCommit = JSON.parse(await save('author-commit.json', 'https://api.github.com/repos/suzgunmirac/hupd/commits/main'));
  if (!/^[a-f0-9]{40}$/.test(authorCommit.sha)) throw new Error('Invalid author revision');
  await save('author-README.md', `https://raw.githubusercontent.com/suzgunmirac/hupd/${authorCommit.sha}/README.md`);
  await save('author-code-LICENSE', `https://raw.githubusercontent.com/suzgunmirac/hupd/${authorCommit.sha}/LICENSE`);
  const hosted = JSON.parse(await save('hosted-metadata.json', 'https://huggingface.co/api/datasets/HUPD/hupd'));
  if (hosted.sha !== revision || hosted.gated !== false || hosted.private !== false) throw new Error('Hosted revision or access changed');
  const hostedReadme = await save('hosted-README.md', `https://huggingface.co/datasets/HUPD/hupd/resolve/${revision}/README.md`);
  if (!hostedReadme.toString('utf8').includes('Attribution-NonCommercial-ShareAlike 4.0 International')) throw new Error('Hosted README license statement changed');
  const tree = JSON.parse(await save('archive-tree.json', `https://huggingface.co/api/datasets/HUPD/hupd/tree/${revision}/data?recursive=false&expand=true`));
  const entry = tree.find(item => item.path === archivePath);
  if (entry?.size !== archiveSize || entry?.lfs?.oid !== publishedArchiveSha256) throw new Error('Published archive identity drift');
  const originalUrl = `https://huggingface.co/datasets/HUPD/hupd/resolve/${revision}/${archivePath}`;
  const prefix = await save('archive-prefix.tar.gz', originalUrl, prefixBytes, { Range: `bytes=0-${prefixBytes - 1}` });
  const members = archiveMembers(prefix, { limit });
  const records = [];
  for (const [index, member] of members.entries()) {
    const file = `raw/member-${String(index + 1).padStart(4, '0')}.json`;
    await writeFile(join(output, file), member.bytes, { flag: 'wx' });
    const identifiers = identifiersOf(member.record);
    records.push({ file, sha256: sha256(member.bytes), bytes: member.bytes.length, archiveMember: member.path,
      tarHeaderOffset: member.tarHeaderOffset, tarContentOffset: member.tarContentOffset, identifiers,
      documentVersion: `RAW_SHA256:${sha256(member.bytes)}`, fields: Object.keys(member.record),
      locators: fieldLocators(member.bytes, member.record) });
  }
  const manifest = { schemaVersion: 'HUPD_REAL_CAPTURE/v1', sourceId: 'HUPD_PUBLIC_ARCHIVE',
    sourceVersion: revision, originalSource: 'HUPD / USPTO-derived published archive', originalUrl,
    collectedAt: new Date().toISOString(), status: 'REAL_CANDIDATE', rightsEvidenceStatus: 'CONFLICTING_PUBLIC_LICENSE_STATEMENTS',
    productEnabled: false, legalStatusInference: false, sourceWithdrawalStatus: 'NOT_ASSERTED',
    licenseEvidence: { authorRevision: authorCommit.sha, authorDataStatement: 'CC BY 4.0',
      hostedDataStatement: hosted.cardData?.license, hostedReadmeDataStatement: 'CC BY-NC-SA 4.0',
      codeLicense: 'MIT_NOT_DATA_LICENSE', decision: 'NO_PRODUCT_ADMISSION' },
    archivePath, archiveSize, publishedArchiveSha256, fullArchiveSha256Verified: false,
    sampling: { method: 'FIRST_COMPLETE_JSON_MEMBERS_IN_HTTP_RANGE', requestedLimit: limit, prefixBytes,
      representative: false, professionalQrels: false, queryResponse: false }, responses, records };
  await writeFile(join(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n', { flag: 'wx' });
  await verifyCapture();
}
