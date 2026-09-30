import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = "data/enforcement/material-dev-product-contract-v1";
const digest = (value) => createHash("sha256").update(value).digest("hex");
const fail = (code) => { throw new Error(code); };

function verify(manifest, observations, scenarios, collection, intent, observationBytes, materialBytes, collectionBytes, intentBytes) {
  if (manifest.schemaVersion !== "enf-material-dev-manifest-v1" ||
      observations.schemaVersion !== "enf-material-dev-input-v1" ||
      scenarios.schemaVersion !== "enf-material-dev-scenarios-v1" ||
      collection.schemaVersion !== "enf-material-dev-collection-input-v1" ||
      intent.schemaVersion !== "enf-material-dev-grant-intent-v1" ||
      manifest.batchId !== observations.batchId || manifest.batchId !== scenarios.batchId ||
      manifest.batchId !== collection.batchId || manifest.batchId !== intent.batchId ||
      manifest.origin !== "PROJECT_AUTHORED_FICTIONAL" ||
      scenarios.status !== "INPUT_EXPECTATIONS_NOT_HUMAN_ACTIONS" ||
      collection.status !== "INPUT_ONLY_NOT_VERIFIED") fail("PACKAGE_SCOPE");
  if (manifest.observations.file !== "observations.json" ||
      manifest.material.file !== "original-M-01.txt" ||
      manifest.collectionInput.file !== "collection-input.json" ||
      manifest.authorizationIntent.file !== "grant-intent.json" ||
      digest(observationBytes) !== manifest.observations.sha256 ||
      digest(materialBytes) !== manifest.material.sha256 ||
      digest(collectionBytes) !== manifest.collectionInput.sha256 ||
      digest(intentBytes) !== manifest.authorizationIntent.sha256 ||
      materialBytes.length !== manifest.material.byteLength) fail("FILE_DIGEST");
  if (!materialBytes.toString("utf8").includes("不是现案证据")) fail("MATERIAL_SCOPE");

  const requiredObservationUses = ["QUERY", "SAVE", "INDEX", "DISPLAY"];
  const requiredMaterialUses = ["SAVE", "PREVIEW", "DOWNLOAD"];
  for (const [entry, required] of [[manifest.observations, requiredObservationUses], [manifest.material, requiredMaterialUses]]) {
    if (required.some((use) => !entry.allowedUses.includes(use)) ||
        entry.allowedUses.some((use) => entry.deniedUses.includes(use)) ||
        ["MODEL", "SHARE", "OFFICIAL_DECISION"].some((use) => !entry.deniedUses.includes(use))) fail("USE_SCOPE");
  }
  if (observations.records.length !== 2 || manifest.observations.records.length !== 2) fail("RECORD_COUNT");
  const versions = new Set();
  for (const [index, record] of observations.records.entries()) {
    const declaration = manifest.observations.records[index];
    const key = `${record.sourceId}/${record.recordId}@${record.version}`;
    if (versions.has(key) || record.sourceId !== "PROJECT-DEV-OBSERVATION" ||
        record.recordId !== declaration.recordId || record.version !== declaration.version ||
        digest(JSON.stringify(record)) !== declaration.sha256 ||
        record.materialRef !== null || record.legalAssessment !== null ||
        record.actorMentions.length !== 2) fail("RECORD_CONTRACT");
    versions.add(key);
    const spans = [...record.actorMentions.map((mention, i) => [mention.literal, declaration.actorUtf8Spans[i]]),
      [record.materialMention, declaration.materialMentionUtf8Span]];
    for (const [literal, [start, end]] of spans) {
      const first = record.sourceText.indexOf(literal);
      if (first < 0 || record.sourceText.indexOf(literal, first + literal.length) >= 0 ||
          Buffer.byteLength(record.sourceText.slice(0, first)) !== start ||
          Buffer.byteLength(record.sourceText.slice(0, first + literal.length)) !== end) fail("SOURCE_LOCATOR");
    }
  }
  if (scenarios.scenarios.length !== 6 ||
      new Set(scenarios.scenarios.map((value) => value.id)).size !== 6 ||
      !scenarios.scenarios.some((value) => value.id === "MENTION_ONLY" && value.file === null) ||
      !scenarios.scenarios.some((value) => value.id === "WITHDRAWN_BEFORE_RETURN") ||
      !scenarios.scenarios.some((value) => value.id === "CROSS_SCOPE") ||
      !scenarios.scenarios.some((value) => value.id === "SOURCE_VERSION_DRIFT") ||
      !scenarios.scenarios.some((value) => value.id === "PAGE_PARTIAL_FAILURE")) fail("SCENARIOS");
  const target = collection.target;
  if (target.file !== manifest.material.file || target.observation !== manifest.material.relatedObservation ||
      target.derivedFrom !== null || target.collectorAction !== "COLLECT" ||
      target.uploaderAction !== "UPLOAD" || target.verifierAction !== "VERIFY" ||
      [target.collectorSubject, target.uploaderSubject, target.verifierSubject,
        target.collectionTime, target.verificationTime, target.verificationResult].some((value) => value !== null) ||
      collection.negativeInputs.length !== 6 ||
      new Set(collection.negativeInputs.map((value) => value.id)).size !== 6) fail("COLLECTION_INPUT");
  if (intent.status !== "NON_EXECUTABLE_REGISTRATION_INTENT" ||
      [intent.issuerSubject, intent.organization, intent.maintainerCertificateRef].some((value) => value !== null) ||
      intent.humanGrants.length !== 3 || intent.serviceGrants.length !== 1 || intent.jobGrants.length !== 1 ||
      intent.humanGrants[0].uses.some((use) => !manifest.observations.allowedUses.includes(use)) ||
      intent.humanGrants[1].uses.some((use) => !manifest.material.allowedUses.includes(use)) ||
      intent.serviceGrants.some((grant) => grant.actions.some((action) => intent.forbiddenDelegations.includes(action))) ||
      ["NO_CREDENTIAL", "CROSS_RESOURCE", "WRONG_USE", "REVOKED", "STATE_UNAVAILABLE", "SERVICE_AS_HUMAN", "OLD_BACKUP_EPOCH"].some((value) => !intent.requiredRejections.includes(value))) fail("AUTH_INTENT");
}

const observationBytes = readFileSync(join(root, "observations.json"));
const materialBytes = readFileSync(join(root, "original-M-01.txt"));
const manifest = JSON.parse(readFileSync(join(root, "manifest.json"), "utf8"));
const observations = JSON.parse(observationBytes);
const scenarios = JSON.parse(readFileSync(join(root, "collection-scenarios.json"), "utf8"));
const collectionBytes = readFileSync(join(root, "collection-input.json"));
const collection = JSON.parse(collectionBytes);
const intentBytes = readFileSync(join(root, "grant-intent.json"));
const intent = JSON.parse(intentBytes);
verify(manifest, observations, scenarios, collection, intent, observationBytes, materialBytes, collectionBytes, intentBytes);

if (process.argv.includes("--self-test")) {
  const rejection = (code, ...args) => {
    try { verify(...args); } catch (error) { if (error.message === code) return; }
    fail(`SELF_TEST_${code}`);
  };
  const copy = (value) => structuredClone(value);
  rejection("FILE_DIGEST", manifest, observations, scenarios, collection, intent, observationBytes, Buffer.concat([materialBytes, Buffer.from("x")]), collectionBytes, intentBytes);
  const excessiveUse = copy(manifest);
  excessiveUse.material.allowedUses.push("SHARE");
  rejection("USE_SCOPE", excessiveUse, observations, scenarios, collection, intent, observationBytes, materialBytes, collectionBytes, intentBytes);
  const autoEvidence = copy(observations);
  autoEvidence.records[0].materialRef = "made-up-ref";
  rejection("RECORD_CONTRACT", manifest, autoEvidence, scenarios, collection, intent, observationBytes, materialBytes, collectionBytes, intentBytes);
  const missingScenario = copy(scenarios);
  missingScenario.scenarios.pop();
  rejection("SCENARIOS", manifest, observations, missingScenario, collection, intent, observationBytes, materialBytes, collectionBytes, intentBytes);
  const fakeVerification = copy(collection);
  fakeVerification.target.verificationResult = "VERIFIED";
  rejection("COLLECTION_INPUT", manifest, observations, scenarios, fakeVerification, intent, observationBytes, materialBytes, collectionBytes, intentBytes);
  const fakeGrant = copy(intent);
  fakeGrant.issuerSubject = "invented-user";
  rejection("AUTH_INTENT", manifest, observations, scenarios, collection, fakeGrant, observationBytes, materialBytes, collectionBytes, intentBytes);
  console.log("Enforcement new development input: 1 positive, 6 negative checks passed; no business rule executed");
} else {
  console.log("Enforcement new development input verified: 2 observation versions, 1 actual text file, 6 input scenarios");
}
