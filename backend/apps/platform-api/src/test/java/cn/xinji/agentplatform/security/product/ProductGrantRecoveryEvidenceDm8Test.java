package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;

/** Four NEW read-only admission persistence checks, not crash replay or authorized Grant repair.
 * Uses the fixture's explicit CON_SHARED_FAULT_DM8_* opt-in, owned schemas, and restricted reader pools.
 * Absent environment aborts honestly in SharedDm8FaultFixture; never substitutes H2 or credentials. */
@Execution(ExecutionMode.SAME_THREAD)
class ProductGrantRecoveryEvidenceDm8Test {
    private static final String ISSUER = SharedDm8FaultFixture.ISSUER;
    private static final String INTENT = "owned-grant-admission-intent";

    @Test void readyMatchingGrantHistorySurvivesPoolCloseAndReconnectWithoutMutation() throws Exception {
        persisted("READY_CURRENT", false, false);
    }

    @Test void pendingUnchangedHistoryRemainsUnresolvedAcrossPoolCloseAndReconnect() throws Exception {
        persisted("PENDING_HISTORY_UNCHANGED", true, false);
    }

    @Test void pendingChangedHistoryRemainsUnresolvedAcrossPoolCloseAndReconnect() throws Exception {
        persisted("PENDING_HISTORY_CHANGED", true, true);
    }

    @Test void readyHistoryMismatchIsStillRefusedAfterPoolCloseAndReconnect() throws Exception {
        persisted("READY_HISTORY_MISMATCH", false, true);
    }

    @Test void actualOfflineCliEmitsPendingDiagnosticAndExitsNonzeroWithoutMutation() throws Exception {
        var f = new SharedDm8FaultFixture();
        java.nio.file.Path files = java.nio.file.Files.createTempDirectory("owned-grant-cli-");
        Process child = null;
        try {
            String originalHistory = history(f.pools.primary);
            f.admin.update("UPDATE " + f.authSchema + ".id2_authority_watermark"
                    + " SET generation=7,state='PENDING',intent_id=?,history_sha=? WHERE issuer=?",
                    INTENT, originalHistory, ISSUER);
            Snapshot baseline = snapshot(f);
            var command = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                    "-Xmx128m", "-cp", System.getProperty("java.class.path"), ProductTaskRecoveryCheck.class.getName());
            var env = command.environment();
            for (String key : java.util.List.of("DM8_JDBC_URL", "DM8_JDBC_USER", "DM8_JDBC_PASSWORD",
                    "CON_SHARED_FAULT_DM8_URL", "CON_SHARED_FAULT_DM8_USER", "CON_SHARED_FAULT_DM8_PASSWORD",
                    "SYSDBA_PWD", "SYSAUDITOR_PWD", "SYSSSO_PWD")) env.remove(key);
            env.put("PRODUCT_TASK_RECOVERY_DB_URL", f.url);
            env.put("PRODUCT_TASK_RECOVERY_DB_USER", f.mainUser);
            env.put("PRODUCT_TASK_RECOVERY_DB_PASSWORD", f.secret);
            env.put("PRODUCT_TASK_RECOVERY_DB_SCHEMA", f.mainSchema);
            env.put("PRODUCT_TASK_RECOVERY_AUTHORITY_DB_URL", f.url);
            env.put("PRODUCT_TASK_RECOVERY_AUTHORITY_USER", f.authUser);
            env.put("PRODUCT_TASK_RECOVERY_AUTHORITY_PASSWORD", f.secret);
            env.put("PRODUCT_TASK_RECOVERY_AUTHORITY_SCHEMA", f.authSchema);
            env.put("PRODUCT_TASK_RECOVERY_AUTHORITY_TOPOLOGY", "shared-instance");
            env.put("PRODUCT_TASK_RECOVERY_ISSUER", ISSUER);
            env.put("PRODUCT_TASK_RECOVERY_EVIDENCE_ROOT", files.toString());
            env.put("PRODUCT_TASK_RECOVERY_EVIDENCE_MAX_BYTES", "1024");
            command.redirectOutput(files.resolve("stdout.json").toFile());
            command.redirectError(files.resolve("stderr.log").toFile());
            child = command.start();
            assertThat(child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isNotZero();
            String output = java.nio.file.Files.readString(files.resolve("stdout.json"));
            String errors = java.nio.file.Files.readString(files.resolve("stderr.log"));
            assertThat(output).doesNotContain(f.secret);
            assertThat(errors).doesNotContain(f.secret);
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output);
            assertThat(json.path("format").asText()).isEqualTo("GRANT_RECOVERY_ADMISSION/v1");
            assertThat(json.path("code").asText()).isEqualTo("PENDING_HISTORY_UNCHANGED");
            assertThat(json.path("generation").asLong()).isEqualTo(7);
            assertThat(json.path("intentId").asText()).isEqualTo(INTENT);
            assertThat(json.path("recordedHistorySha").asText()).isEqualTo(originalHistory);
            assertThat(json.path("observedHistorySha").asText()).isEqualTo(originalHistory);
            assertThat(json.path("repairAuthorized").asBoolean()).isFalse();
            assertThat(json.path("mutationPerformed").asBoolean()).isFalse();
            assertThat(snapshot(f)).isEqualTo(baseline);
            System.out.println("OWNED_GRANT_CLI_RAW " + files + " exit=" + child.exitValue()
                    + " authoritySha=" + baseline.authoritySha() + " historySha=" + baseline.historySha());
            System.out.println("OWNED_GRANT_CLI_DIAGNOSTIC " + output.strip());
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            f.pools.close();
            System.out.println("OWNED_GRANT_ADMISSION_CONTEXT_RETAINED " + f.mainSchema + ","
                    + f.authSchema + "," + f.mainUser + "," + f.authUser);
        }
    }
    private static void persisted(String code, boolean pending, boolean changed) throws Exception {
        var f = new SharedDm8FaultFixture();
        try {
            String originalHistory = history(f.pools.primary);
            // Administrator engineering SETUP touches only this fixture's fresh owned schemas.
            // It does not implement or invoke a repair, and never releases a PENDING intent.
            assertThat(f.admin.update("UPDATE " + f.authSchema + ".id2_authority_watermark"
                    + " SET generation=7,state=?,intent_id=?,history_sha=? WHERE issuer=?",
                    pending ? "PENDING" : "READY", pending ? INTENT : null, originalHistory, ISSUER)).isEqualTo(1);
            if (changed) {
                assertThat(f.admin.update("INSERT INTO " + f.mainSchema + ".id2_subject_event"
                        + "(event_id,issuer,subject_id,operation,before_version,after_version,actor_ref,occurred_at)"
                        + " VALUES(?,?,'owned-member','DISABLE',1,2,'owned-engineer',CURRENT_TIMESTAMP)",
                        UUID.randomUUID().toString(), ISSUER)).isEqualTo(1);
                assertThat(f.admin.update("UPDATE " + f.mainSchema + ".id2_subject SET status='DISABLED',"
                        + "credential_version=2,disabled_at=CURRENT_TIMESTAMP WHERE issuer=? AND subject_id='owned-member'",
                        ISSUER)).isEqualTo(1);
            }
            Snapshot baseline = snapshot(f);
            assertThat(baseline.historySha().equals(originalHistory)).isEqualTo(!changed);
            var first = diagnose(f, code);
            assertThat(snapshot(f)).isEqualTo(baseline);
            f.reconnect(); // Existing pools are actually closed; fresh restricted JDBC pools are opened.
            var reconnected = diagnose(f, code);
            assertThat(reconnected).isEqualTo(first);
            assertThat(snapshot(f)).isEqualTo(baseline);
            assertThat(reconnected.generation()).isEqualTo(7L);
            assertThat(reconnected.intentId()).isEqualTo(pending ? INTENT : null);
            assertThat(reconnected.recordedHistorySha()).isEqualTo(originalHistory);
            assertThat(reconnected.observedHistorySha()).isEqualTo(baseline.historySha());
        } finally {
            // Intentionally retain success AND failure contexts; fixture cleanup is not invoked.
            try {
                if (f.pools != null) f.pools.close();
            } finally {
                System.out.println("OWNED_GRANT_ADMISSION_CONTEXT_RETAINED_INTENTIONALLY"
                        + " mainSchema=" + f.mainSchema + " authSchema=" + f.authSchema
                        + " mainUser=" + f.mainUser + " authUser=" + f.authUser);
            }
        }
    }

    private static ProductGrantRecoveryEvidence.Evidence diagnose(SharedDm8FaultFixture f, String code) {
        var primary = new ProductGrantRecoveryEvidenceTestSupport.ReadAudit(f.pools.primary.getDataSource());
        var authority = new ProductGrantRecoveryEvidenceTestSupport.ReadAudit(f.pools.authority.getDataSource());
        var reader = new ProductGrantRecoveryEvidence(new JdbcTemplate(primary), new JdbcTemplate(authority), ISSUER);
        ProductGrantRecoveryEvidence.Evidence result;
        if ("READY_CURRENT".equals(code)) {
            result = reader.requireReady();
            ProductGrantRecoveryEvidenceTestSupport.assertEvidence(result, code, true);
        } else {
            result = ProductGrantRecoveryEvidenceTestSupport.assertRefusal(reader, code, true);
        }
        assertThat(result.issuer()).isEqualTo(ISSUER);
        assertThat(primary.historyReads.get()).isEqualTo("READY_CURRENT".equals(code) ? 2 : 4);
        assertThat(authority.authorityReads.get()).isEqualTo("READY_CURRENT".equals(code) ? 3 : 6);
        assertThat(primary.writeAttempts.get()).isZero();
        assertThat(authority.writeAttempts.get()).isZero();
        return result;
    }

    private record Snapshot(Map<String, Object> authorityRow, String authoritySha, String historySha) { }

    private static Snapshot snapshot(SharedDm8FaultFixture f) throws Exception {
        Map<String, Object> row = f.pools.authority.queryForMap("SELECT issuer,generation,state,intent_id,history_sha"
                + " FROM id2_authority_watermark WHERE issuer=?", ISSUER);
        // Deterministic framed SHA of the exact authority fields; no identity/grant body is printed.
        var digest = MessageDigest.getInstance("SHA-256");
        for (String field : new String[]{"ISSUER", "GENERATION", "STATE", "INTENT_ID", "HISTORY_SHA"}) {
            Object value = row.get(field);
            String text = value == null ? null : value.toString();
            digest.update((text == null ? "-1:" : text.length() + ":" + text).getBytes(StandardCharsets.UTF_8));
        }
        return new Snapshot(row, HexFormat.of().formatHex(digest.digest()), history(f.pools.primary));
    }

    private static String history(JdbcTemplate primary) {
        var tables = new ProductObservedTables(primary);
        boolean workload = tables.present("ID2_WORKLOAD");
        return ProductGrantHistoryBatch.digest(primary, ISSUER, workload,
                workload && tables.present("CON3_WORKER_DELEGATION"));
    }
}
