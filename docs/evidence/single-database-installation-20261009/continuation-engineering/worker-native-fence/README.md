# IMP-SINGLE-DB-NATIVE-FENCE-01 — worker delivery

This directory is implementation/test evidence, not a task-status ledger. The master session owns the plan, ADR and integration acceptance.

## Delivered code

Only these four implementation/test files were written:

- `scripts/lib/single-database-lifecycle-native.mjs`
- `scripts/lib/single-database-lifecycle-native.test.mjs`
- `scripts/lib/single-database-native-fence-container.mjs`
- `scripts/lib/single-database-native-fence-container.test.mjs`

The container transport is explicitly selected by `native.fence.transport = "OWNED_DOCKER_JDK"`. It never attempts host Java, DNS fallback, another URL or another target when inspection, alias validation, class digest or process startup fails. Omitting transport retains the old local source-launch v1 fixture contract; supplying `dockerFence` without the explicit selector is rejected.

The adapter continues to use the **original unchanged** `SingleDatabaseFence` Java implementation: one connection, autocommit disabled, exact six-schema table discovery, SHARE locks, repeat CHECK, same table-set SHA/count, and RELEASE/rollback. The new transport launches `SingleDatabaseFence` as a pinned compiled class, not a replacement Java implementation. No compilation was performed in this worker.

Shared resource validation is imported from `single-database-jdbc-container.mjs::validateOwnedJdbcToolResource`; that file was not edited. Tool and DB identities, exact owner/target labels, running state, non-root/read-only-rootfs limits, and the exact shared network remain mandatory. The fence additionally requires the exact JDBC alias to appear on that **DB's specified network**, rejects container JVM option/agent injection, and checks the named class SHA before startup and before each CHECK.

The native adapter still independently requires complete approved writer responsibility records, the exact deployed writer-container set, live `stoppedWriters` inspections and its exclusively created host maintenance marker. Neither a backup account nor the helper container substitutes for those obligations. Every held-lease CHECK revalidates original decision/configuration, current action/data/file rights, marker, live target and stopped writers. Failure aborts the protocol and removes the active export lease. Cleanup uses the original marker path even if the caller changes configuration.

v2 derives schema and account sets through the existing full deployment validator and `deploymentAccounts`, requires their exact match with `c.schemas`/`c.accounts` and the approved canonical deployment hash, and validates issuer/role/endpoint bindings. All fifteen domain accounts (including runtime/backup and domain-specific owners) and generic system accounts are rejected as the cross-six-schema fence identity. A separately approved maintenance identity is required; this patch grants it **no** permission.

## Required new configuration / integration handoff

Keep the existing native/fence credentials **as environment-variable references**, complete writer registry, current approval/data/file rights and host maintenance-lock configuration. Add:

| Field | Requirement |
|---|---|
| `native.fence.transport` | Exact string `OWNED_DOCKER_JDK` |
| `native.fence.sessionTimeoutSeconds` | Explicit approved whole-container-connection lifetime; integer 30–3600 seconds. Budget for acquisition, inventory, export, every CHECK and release, not just native export. No renewal/hidden 180-second snapshot cutoff. |
| `native.fence.dockerFence.containerId` | Exact 64-hex existing owned JDK container ID |
| `native.fence.dockerFence.targetId` | Exact `c.targetId`/deployment database container ID |
| `native.fence.dockerFence.networkId` | Exact shared Docker network ID |
| `native.fence.dockerFence.ownerTask` | Same exact deployment database owner task |
| `native.fence.dockerFence.java` | Absolute contained JDK executable path ending `/java`; no traversal |
| `native.fence.dockerFence.classFile` | Absolute contained path ending `/SingleDatabaseFence.class`; no traversal |
| `native.fence.dockerFence.classpath` | Absolute colon-separated paths; **first** entry must be the class file directory |
| `native.fence.dockerFence.classSha256` | Actual approved SHA-256 of that compiled class, not a source digest or placeholder |
| `native.fence.java` / `.classpath` | Must exactly equal the corresponding `dockerFence` fields |
| `approval.javaFenceClassSha256` | Must equal the approved `dockerFence.classSha256` |
| `approval.javaFenceSha256` | Fixed original source bytes SHA: `c4d2557a74f0002a2e9c4b8d10f2a126bb1f860584e1d5ce41bd5a4640d1b5fd` |

`native.fence.jdbcUrl`, `inventoryJdbc.url` and `deployment.database.jdbcUrl` must be the **same exact** `jdbc:dm://<owned-network-alias>:5236`; six v2 bindings must also use this endpoint. Keep the separate maintenance user/password reference, not one of the fifteen deployed accounts. Recompute the approved `nativeConfigurationSha256`, canonical `configurationSha256` and adapter SHA through the existing master-session approval process. This worker creates **no** execution approval or concrete real-resource configuration.

The already-owned tool must contain the approved JDK/driver/class, `sha256sum`, and `/usr/bin/timeout` supporting `--signal=TERM --kill-after=5s`. It must not inject `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` or `_JAVA_OPTIONS`. The exec argument array carries only the names `LIFECYCLE_FENCE_URL`, `LIFECYCLE_FENCE_USER`, `LIFECYCLE_FENCE_PASSWORD`; password values are passed through the subprocess environment, never argv or stdout. The existing per-protocol-request deadline remains 15 seconds. The independently configured container lifetime has a 5-second kill grace and fails closed on exhaustion; no retry/automatic extension.

## Tests and preserved machine output

Final command:

```text
node --test scripts/lib/single-database-lifecycle-native.test.mjs scripts/lib/single-database-native-fence-container.test.mjs
```

Final result: **125 tests / 125 pass / 0 fail / 0 skip / exit 0**. See `targeted-tests.result.json`, original stdout/stderr logs and recorded before/after SHA. Prior successful 118-test machine output is retained under `targeted-tests-118.*`; seven additional cases exercise explicit bounded whole-session timeout metadata. An earlier development run had one overly broad test assertion (it wrongly rejected the permitted environment-variable name); the assertion was corrected, not the production secrecy contract. Its terminal output was not exported as a machine log.

Coverage includes existing v1 fixtures; v2 full bindings/schema/account mismatch; runtime/backup/owner fence refusal; unchanged source SHA; exact resource ownership/network/alias/limits; compiled-class pin and classpath order; JVM injection refusal; one persistent process and fragmented responses; refusals/unsolicited data/changed SHA or count; failed CHECK/RELEASE; spawn/stream/EOF/timeout errors; metadata or ownership loss; original decision replacement; repeated current-rights checks; and no fallback. IO is injected fake Docker metadata/process streams except for one worker-owned local Node readline protocol process. No Java/JDBC/SQL runs in these tests.

## Unproven boundaries / not acceptance

- No real Docker command, resource creation/start/stop/exec, DB access, full build or `verify-all` was performed. No previously successful DM8 lock probe was repeated. Java source, shared validator/runtime/restore, plans/ADR/SQL/pom and requirements were not written.
- The user-supplied preflight was exit 1, only existing `TESTS_FAILING:project` (2266/20 environment errors), 47 requirement digests unchanged. This worker did not rerun or independently reinterpret it.
- Metadata/fake IO/Node protocol tests do **not** establish actual DM8 SHARE locking, effective maintenance-account grants, full stopping of real writers, native export, successful rollback after transport death, restore or complete single-database protection.
- Container JDK/driver/coreutils availability, original-source-to-approved-class build provenance, JDBC connectivity/SHARE semantics and the real session-timeout rollback remain for authorized integration evidence. Merely hashing a compiled class does not prove its build provenance.
- Read-only rootfs and repeated class digest checks do not prove immutable external mounts or eliminate filesystem TOCTOU. The approved real tool/classpath supply and mounted-file controls must remain trusted. This worker did not approve a mutable code mount.
- Killing the host Docker client is not claimed to prove immediate remote JDBC rollback. EOF handling by the unchanged Java class and the explicitly configured in-container lifetime bound the intended failure path; actual remote cleanup/lock release still needs separate authorized evidence.
- Expiry/rights checks remain fail-closed protocol/admission evidence, not legal/source-rights validation or business acceptance. The master session owns snapshot/restore 15-account compatibility and the material set.
