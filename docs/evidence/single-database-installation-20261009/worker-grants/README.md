# A worker grant handoff — technical receipt, not a task/status ledger

This directory preserves the worker's own raw Node/governance output. The initial
`source-review.json` is a preserved over-approximation used during review, **not**
the final application bean graph or a grant input. Consume the reviewed
`deployment/single-database/application-capabilities.json` and its pinned receipt.
The main installation profile owns migration instances, ordering, SQL and all
installed tables; this worker does not choose or rewrite them.

## Fixed Java request / response

Owner port: `jdbcRequest(ownerJdbc[step.schema], objectGrantRequest(step))`.
One JSON request per object/privilege, with exactly these request fields:

```json
{"action":"grant-object","schema":"CONPROD","object":"CON5_PREPARATION","objectType":"TABLE","grantee":"CONPROD_SVC","privilege":"SELECT","grantOption":false}
```

`privilege` is one of SELECT, INSERT, UPDATE, DELETE, REFERENCES. Schema/object/
grantee are reviewed uppercase identifiers; TABLE only. No SQL, ANY, role, DDL,
RESOURCE or grant-option input. Actual Java connection user must equal schema
owner, and Java validates the owned table before DCL. Runtime REFERENCES uses
the real FK parent evidence; cross-schema REFERENCES goes to the same-domain
referencing schema owner. Owner initialization is not runtime DML.

Actual managed Java success acknowledgement (now implemented by main session):

```json
{"actualJdbc":true,"executedSteps":1,"grantOption":false}
```

This acknowledgement alone is not success: coordinator checks fresh actual
inventory before and after, exact permission delta, no additional system/role/
column/effective grants, and unchanged non-grant objects/rows. Inherited PUBLIC
business/system permissions are currently fail-closed, not silently whitelisted.
Actual DM8 catalog/public visibility still requires controlled runtime preflight.

## Grant-only compiler input

`compileObjectGrantManifest({deployment, capabilities, installedTables,
initialInventorySha256, installationEvidence})` consumes the main profile's
already chosen `installedTables`. It does not compile another install list.
`installationEvidence` may explicitly map `schema.object` to source SHA/line
references. With the current profile it uses each installed table's existing
`sourcePath` / `sourceSha256` if an explicit map is absent. Unknown installed
business tables get no inferred runtime privilege; every installed authority
table gets only SELECT for its bound independent backup account.

## CLI configuration (references only; no plaintext credentials)

Run `node scripts/grant-single-database-objects.mjs CONFIG.json NEW_RECEIPT.json`.
The config contains:

- `targetId`, `deployment`, `manifest`, exact `schemas` / `accounts`;
- `workspaceRoot`, exclusive `journalDir`, `maintenanceLock`;
- `inventoryJdbc` (existing managed SYSDBA read-only inventory port), six
  `ownerJdbc[schema]` ports (user equals owner schema), Java/mainClass/classpath,
  exact shared DM8 URL and distinct managed password environment references;
- `jdbcSourcePath`, `jdbcClassPath`, and
  `executionFenceAdapter` pointing to the new object-grants fence;
- `currentDecisionPath` and exact action approval `grant-object` with live
  approvedAt/expiresAt, owner/source, no currentMigration/connectionSwitch/
  deleteExisting, original-decision SHA, protected-config SHA, manifest SHA,
  JDBC source/class SHA, coordinator SHA, fence-adapter SHA;
- existing complete writerRegistry evidence, explicit maintenance exclusivity,
  newEnvironmentOnly, and exact stopped CONTAINER / MAINTENANCE writers.
  If applications have never started, applicationsNeverStarted must be true.

The protected config is the canonical JSON config without `approval`. The
original decision bytes hash to `currentDecisionEvidenceSha256`; its parsed
object equals approval without that self-referential digest. Source evidence
paths remain beneath workspaceRoot and are re-hashed before each DCL.

START is fsync'd before DCL; FAILURE saves a new raw inventory scene and makes
no rollback claim. An unmatched START or FAILURE **cannot auto-retry**. A new
explicitly approved reconciliation/manifest is required. Completed re-entry
skips only after journal-chain and exact inventory validation. The output receipt
is exclusively reserved before any DB action; existing receipts are not replaced.

## Configuration boundaries

- `startupRequired` is the explicit current-enabled subset; `configurationFields`
  has exact formal configuration-class refs and `enableWhen`. Optional branches
  retain approved defaults and are not a demand to fill lexical scanner output.
- Consultation: no external model key/approval needed for prepare, confirmed
  Input, single-candidate HTTPS/mTLS handoff and original-key reads. Start still
  needs actual worker subject, origin, stores, managed passwords, lease and
  configuration-version. Delivered tables remain installed. Local semantic and
  its unconditional reranker are distinct from external model budget.
- Enforcement: materials/rules/templates/drafts/version review retained; no
  signature or official closure. Optional current-* connections default to the
  existing business pool, not a silently revived separate database.
- Adjudication: implemented material organization/03B decisions retained; exact
  case/run/report-version handoff, not 03C. Enabled beans still require approved
  policy, managed provider credential and independent model/decision key roots;
  this catalog authorizes no new model call. External report-policy-file remains
  optional because the released classpath policy is the approved default.

No database or product process was started; no full build was run by this worker.
Raw logs and the receipt distinguish Node components from actual DM8/three-app
startup, restore or business acceptance. Task/status remains in main docs/08.
