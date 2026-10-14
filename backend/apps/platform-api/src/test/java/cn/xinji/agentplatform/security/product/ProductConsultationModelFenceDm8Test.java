package cn.xinji.agentplatform.security.product;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Same real dialogue/SQL tests on a disposable DM8 schema; provider/authority ports remain fixtures. */
class ProductConsultationModelFenceDm8Test {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path evidenceRoot;
    @FunctionalInterface interface Check { void run(ProductConsultationModelFixture fixture) throws Exception; }
    private void isolated(Check check) throws Exception { isolated(check,java.time.Instant.parse("2026-10-06T00:00:00Z")); }
    private void isolated(Check check,java.time.Instant now) throws Exception {
        String url=System.getenv("CON_MODEL_FENCE_DM8_URL"),user=System.getenv("CON_MODEL_FENCE_DM8_USER"),password=System.getenv("CON_MODEL_FENCE_DM8_PASSWORD");
        Assumptions.assumeTrue(url!=null && user!=null && password!=null,"Explicit isolated model fence DM8 environment required");
        String schema="CMF"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();
        String secret="T9a"+UUID.randomUUID().toString().replace("-","")+"Z";
        boolean created=false;
        try {
            try(var connection=DriverManager.getConnection(url,user,password);var stmt=connection.createStatement()) {
                stmt.execute("CREATE USER "+schema+" IDENTIFIED BY \""+secret+"\"");created=true;
                stmt.execute("GRANT RESOURCE TO "+schema);
            }
            check.run(new ProductConsultationModelFixture(new DriverManagerDataSource(url,schema,secret),now));
        } finally {
            if(created) try(var connection=DriverManager.getConnection(url,user,password);var stmt=connection.createStatement()) {
                stmt.execute("DROP USER "+schema+" CASCADE");
            }
        }
    }
    @Test void committedClaimBlocksUnknownOutcomeAfterAdapterRestart() throws Exception { isolated(f->f.unknownProviderOutcomeIsNotDispatchedAgainAfterServiceRestart()); }
    @Test void successfulExternalCallWithFailedDbSaveIsNotDispatchedAgain() throws Exception { isolated(f->f.successfulExternalCallThenFailedPersistenceMustNotCallProviderAgain()); }
    @Test void sameKeyConcurrentDispatchAndCompletedReplayAreSerializedByTaskLock() throws Exception { isolated(f->f.concurrentSameKeyIsRejectedBeforeSecondModelDispatch()); }
    @Test void currentAuthorityStillWinsAfterExternalCall() throws Exception { isolated(f->f.authorityRevokedDuringExternalCallCannotPersistOrReplayAnswer()); }
    @Test void recoveryBindsRetainedClaimToExactTurnAndPolicyOnDm8() throws Exception {
        isolated(f->{
            f.actualExplanationPersistsAndReplaysWithoutSendingRawTaskText();
            f.db.execute("ALTER TABLE con3_task ADD version BIGINT");f.db.execute("ALTER TABLE con3_task ADD expires_at TIMESTAMP");
            f.db.update("UPDATE con3_task SET version=3,expires_at=?",java.sql.Timestamp.from(f.now.plusSeconds(3600)));
            // EXPLANATION needs the same historical search task/version/result hash.
            f.db.execute("ALTER TABLE con4_search_attempt ADD task_version BIGINT");
            f.db.execute("ALTER TABLE con4_result_set ADD result_sha VARCHAR(64)");
            f.db.update("UPDATE con4_search_attempt SET task_version=3");f.db.update("UPDATE con4_result_set SET result_sha=?","a".repeat(64));
            java.nio.file.Files.writeString(evidenceRoot.resolve("policy.json"),"component-only retention original");
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(evidenceRoot.resolve("policy.json"))));
            for(String table:java.util.List.of("con3_model_turn","con3_model_invocation"))
                f.db.update("UPDATE "+table+" SET retention_ref='policy.json',retention_evidence_sha=?",hash);
            var verifier=new ProductModelInvocationRecoveryVerifier(f.db,evidenceRoot.toAbsolutePath(),10000);
            org.junit.jupiter.api.Assertions.assertEquals(1,verifier.verify());
            f.db.update("UPDATE con3_model_turn SET request_sha=?","c".repeat(64));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,verifier::verify);
        });
    }
    @Test void retainedTurnAndFailedCheckpointReplayOnDm8WithoutRepeatingModel() throws Exception { isolated(f->f.savedAnswerThenCheckpointFailureRecoversSameTurnWithoutSecondProviderCall()); }
    @Test void unresolvedModelOutcomeStopsDm8RuntimeInsteadOfReplanning() throws Exception { isolated(f->f.unknownModelOutcomeStopsRuntimeWithoutReplanOrSuccess()); }
    @Test void nanosecondClockReturnsExactPersistedTurnOnDm8() throws Exception { isolated(f->f.actualExplanationPersistsAndReplaysWithoutSendingRawTaskText(),java.time.Instant.parse("2026-10-06T00:00:00.123456789Z")); }
    @Test void realRuntimeRecoversCommittedAnswerFromDm8Checkpoint() throws Exception { isolated(f->f.realRuntimeAndSqlCheckpointResumeIntoPersistedAnswerAndEvaluation()); }
}
