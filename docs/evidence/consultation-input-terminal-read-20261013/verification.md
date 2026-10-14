# Input terminal read verification

Scope: FIX-CON-INPUT-READ-TERMINAL-ADMISSION-01 under the existing product comparison and immutable Input design. Evidence only; task status remains in docs/08.

The fixture reuses ProductComparisonInputTest's actual H2 migrations, immutable Input, candidate projection and creation event. External actor/source authority and time remain controlled component ports. The final native Input reread triggers expiry, READ revocation or source unavailability; stable reads must preserve the original receipt and native rows. These checks do not prove DM8, deployed identity, source rights, model execution or business acceptance.

Required baseline: D:/AgentPlatformTempRecovery/consultation-takeover-pre-verify-20261013.log, natural exit 1; 2068 tests / 2066 pass / 0 fail / 2 existing skips, additional 30/30, 242/242 and 179/179. All 47 requirement files and 41 ledger rows agree. Existing TESTS_FAILING and TEST_EVIDENCE_STALE remain; independent checks completed.

Raw red, repaired and affected regression logs and XML are retained in this directory when executed. No deployed package, database, credentials, activation or source grant changes are performed.

Executed with JDK 21 and the existing Maven reactor:

```powershell
$env:JAVA_HOME='D:/software/jdk-21'
$env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=D:/daima/xinji/agent_platform/tmp'
mvn -f backend/pom.xml -pl apps/platform-api -am '-Dtest=ProductComparisonInputTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Before repair: red.log / red.xml, Maven exit 1, 16 tests / 3 failures / 0 errors / 0 skips. All three new expiry, READ-revocation and source-loss examples wrongly returned success after the final actual native Input read. The stable example and original 12 cases passed.

After repair: green.log / green.xml, Maven exit 0, 16 tests / 0 failures / 0 errors / 0 skips. The same three examples reject delivery. The original cases verify current and historical read, create/replay, changed current Feature rejection on consumption, authority drift, rollback, immutable closure, migration refusal and HTTP identity/error behavior. The stable terminal example preserves the exact receipt and one row in each of the four Input tables. Only ProductComparisonInputApplication and its existing test change; no Store/SQL/template/route/permission changes.

Terminal verification now follows the final native reread, and verification finishes by checking the current Task/required action/READ and the Input, Feature, preparation and loaded-material deadlines. This strengthens the shared create/consume verification without relaxing their current-pointer or CAS checks.

Required post-check raw log: post-governance.log. Read its natural exit and complete stage output before reporting governance results. Existing project test-health failures remain distinct from this component result.
