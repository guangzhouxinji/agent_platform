package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductGrantRecoveryEvidenceTestSupport.ISSUER;
import static cn.xinji.agentplatform.security.product.ProductGrantRecoveryEvidenceTestSupport.INTENT;
import static cn.xinji.agentplatform.security.product.ProductGrantRecoveryEvidenceTestSupport.assertEvidence;
import static cn.xinji.agentplatform.security.product.ProductGrantRecoveryEvidenceTestSupport.assertRefusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.security.product.ProductGrantRecoveryEvidenceTestSupport.Fixture;
import cn.xinji.agentplatform.security.product.ProductGrantRecoveryEvidenceTestSupport.ReadAudit;
import java.nio.file.Path;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real JDBC mechanical admission checks; neither PENDING diagnosis authorizes Grant repair. */
class ProductGrantRecoveryEvidenceTest {
    @TempDir Path evidenceRoot;

    @Test void invalidIssuerIsRejectedBeforeAnyReadAndCannotLeakCredentialBearingUrls() throws Exception {
        var f = new Fixture();
        String[] invalid = {null, "", " ", "http://public.example", "relative/path", "https:///missing-host",
                "https://user:owned-secret@public.example", "https://public.example?token=owned-secret",
                "https://public.example#owned-secret", "https://public.example/invalid path", "https://public.example/" + "x".repeat(300)};
        for (String issuer : invalid) {
            assertThatThrownBy(() -> new ProductGrantRecoveryEvidence(new JdbcTemplate(f.primaryAudit),
                    new JdbcTemplate(f.authorityAudit), issuer))
                    .isInstanceOfSatisfying(IllegalArgumentException.class, failure -> {
                        assertThat(failure.getMessage()).isIn(
                                "Exact configured Grant recovery connections and issuer required",
                                "Public configured HTTPS Issuer required for recovery admission");
                        assertThat(failure.getMessage()).doesNotContain("owned-secret");
                        assertThat(failure.getCause()).isNull();
                    });
        }
        assertThat(f.primaryAudit.historyReads.get()).isZero();
        assertThat(f.authorityAudit.authorityReads.get()).isZero();
        f.assertNoWriteCalls();
    }

    @Test void readyReturnsOnlyMatchingCurrentHistoryAndPerformsThreeAuthorityAndTwoHistoryReads() throws Exception {
        var f = new Fixture();
        var before = f.rows();
        var result = f.reader().requireReady();
        assertEvidence(result, "READY_CURRENT", true);
        assertThat(result.issuer()).isEqualTo(ISSUER);
        assertThat(result.generation()).isEqualTo(7L);
        assertThat(result.intentId()).isNull();
        assertThat(result.recordedHistorySha()).isEqualTo(f.history());
        assertThat(result.observedHistorySha()).isEqualTo(f.history());
        assertThat(f.authorityAudit.authorityReads.get()).isEqualTo(3);
        assertThat(f.primaryAudit.historyReads.get()).isEqualTo(2);
        f.assertUnwritten(before);
    }

    @Test void unchangedPendingRemainsPendingAndIsNotS1Permission() throws Exception {
        var f = new Fixture();
        f.pending();
        var before = f.rows();
        assertStableRefusal(f, "PENDING_HISTORY_UNCHANGED");
        f.assertUnwritten(before);
    }

    @Test void changedPendingRemainsPendingAndDoesNotIdentifyACommittedIntentOrAuthorizeS2() throws Exception {
        var f = new Fixture();
        f.pending();
        String recorded = f.history();
        f.disableSubject();
        assertThat(f.history()).isNotEqualTo(recorded);
        var before = f.rows();
        assertStableRefusal(f, "PENDING_HISTORY_CHANGED");
        f.assertUnwritten(before);
    }

    @Test void readyMismatchCannotBeReturnedAsReady() throws Exception {
        var f = new Fixture();
        f.disableSubject();
        var before = f.rows();
        assertStableRefusal(f, "READY_HISTORY_MISMATCH");
        f.assertUnwritten(before);
    }

    @Test void missingIssuerDoesNotBorrowAnotherIssuersAuthority() throws Exception {
        var f = new Fixture();
        f.authority.update("UPDATE id2_authority_watermark SET issuer=?", ISSUER + "/other");
        var before = f.rows();
        var result = assertRefusal(f.reader(), "AUTHORITY_ABSENT", false);
        assertThat(result.generation()).isNull();
        assertThat(result.recordedHistorySha()).isNull();
        f.assertUnwritten(before);
    }

    @Test void duplicateIssuerInDamagedAuthorityIsInvalidNotAnArbitraryFirstRow() throws Exception {
        var f = new Fixture();
        f.relaxedAuthority();
        f.insertAuthority(7L, "READY", null, f.history());
        f.insertAuthority(8L, "READY", null, f.history());
        var before = f.rows();
        assertRefusal(f.reader(), "AUTHORITY_INVALID", false);
        f.assertUnwritten(before);
    }

    @Test void malformedAuthorityRowsAreRejectedEvenWhenHistoryOtherwiseMatches() throws Exception {
        // Deliberately damaged physical table: normal migration constraints forbid many of these rows.
        Object[][] invalid = {
                {null, "READY", null, "MATCH"}, {-1L, "READY", null, "MATCH"},
                {7L, "READY", "unexpected-intent", "MATCH"}, {7L, "PENDING", null, "MATCH"},
                {7L, "PENDING", " ", "MATCH"}, {7L, "UNKNOWN", null, "MATCH"},
                {7L, null, null, "MATCH"}, {7L, "READY", null, null},
                {7L, "READY", null, "short"}, {7L, "READY", null, "z".repeat(64)}
        };
        for (Object[] row : invalid) {
            var f = new Fixture();
            f.relaxedAuthority();
            String sha = "MATCH".equals(row[3]) ? f.history() : (String) row[3];
            f.insertAuthority((Long) row[0], (String) row[1], (String) row[2], sha);
            var before = f.rows();
            assertRefusal(f.reader(), "AUTHORITY_INVALID", false);
            assertThat(f.primaryAudit.historyReads.get()).isZero();
            f.assertUnwritten(before);
        }
    }

    @Test void missingAuthorityTableIsUnavailableAndDriverDetailsDoNotEscape() throws Exception {
        var f = new Fixture();
        f.authority.execute("ALTER TABLE id2_authority_watermark RENAME TO retained_authority_watermark");
        var result = assertRefusal(f.reader(), "AUTHORITY_UNAVAILABLE", false);
        assertThat(result.generation()).isNull();
        assertThat(f.primaryAudit.historyReads.get()).isZero();
        f.assertNoWriteCalls();
    }

    @Test void unreadableMandatoryHistoryDoesNotBecomeEmptyReadyHistory() throws Exception {
        var f = new Fixture();
        f.primary.execute("ALTER TABLE id2_grant_event RENAME TO retained_grant_event");
        var before = f.rows();
        var result = assertRefusal(f.reader(), "HISTORY_UNAVAILABLE", false);
        assertThat(result.observedHistorySha()).isNull();
        f.assertUnwritten(before);
    }

    @Test void actualRestrictedH2UserWithoutHistoryReadPermissionIsRefused() throws Exception {
        var f = new Fixture();
        f.primary.execute("CREATE USER RECOVERY_READER PASSWORD 'owned-test-only'");
        var restricted = new JdbcDataSource();
        restricted.setURL(f.primarySource.getURL().split(";")[0]);
        restricted.setUser("RECOVERY_READER");
        restricted.setPassword("owned-test-only");
        var audit = new ReadAudit(restricted);
        var before = f.rows();
        assertRefusal(new ProductGrantRecoveryEvidence(new JdbcTemplate(audit),
                new JdbcTemplate(f.authorityAudit), ISSUER), "HISTORY_UNAVAILABLE", false);
        assertThat(audit.writeAttempts.get()).isZero();
        f.assertUnwritten(before);
    }

    @Test void authorityChangeAtMiddleObservationIsRefused() throws Exception {
        authorityRace(2, false);
    }

    @Test void authorityChangeAtFinalObservationIsRefused() throws Exception {
        authorityRace(3, false);
    }

    @Test void authorityDisappearanceAfterFirstReadIsObservationChangedNotReadyOrAbsent() throws Exception {
        authorityRace(2, true);
    }

    private static void authorityRace(int observation, boolean disappear) throws Exception {
        var f = new Fixture();
        f.authorityAudit.beforeQuery = (sql, count) -> {
            if (sql.contains("ID2_AUTHORITY_WATERMARK") && count == observation) {
                if (disappear) f.authority.update("UPDATE id2_authority_watermark SET issuer=? WHERE issuer=?", ISSUER + "/retained", ISSUER);
                else f.authority.update("UPDATE id2_authority_watermark SET generation=8 WHERE issuer=?", ISSUER);
            }
        };
        var result = f.reader().inspect();
        assertEvidence(result, "OBSERVATION_CHANGED", false);
        assertThat(result.generation()).isEqualTo(7L);
        f.assertNoWriteCalls();
    }

    @Test void historyChangeBetweenTheTwoRealDigestsIsRefusedDespiteUnchangedAuthority() throws Exception {
        var f = new Fixture();
        var authorityBefore = f.rows();
        String original = f.history();
        f.primaryAudit.beforeQuery = (sql, count) -> {
            if (sql.contains("UNION ALL") && count == 2) f.disableSubject();
        };
        var result = f.reader().inspect();
        assertEvidence(result, "OBSERVATION_CHANGED", false);
        assertThat(result.recordedHistorySha()).isEqualTo(original);
        assertThat(result.observedHistorySha()).isEqualTo(f.history()).isNotEqualTo(original);
        assertThat(f.rows()).isEqualTo(authorityBefore);
        assertThat(f.authorityAudit.authorityReads.get()).isEqualTo(3);
        f.assertNoWriteCalls();
    }

    @Test void historyReadFailureOnSecondDigestCannotReturnFirstReadyObservation() throws Exception {
        var f = new Fixture();
        f.primaryAudit.beforeQuery = (sql, count) -> {
            if (sql.contains("UNION ALL") && count == 2) f.primary.execute("ALTER TABLE id2_grant_event RENAME TO retained_grant_event");
        };
        var result = f.reader().inspect();
        assertEvidence(result, "HISTORY_UNAVAILABLE", false);
        assertThat(result.observedHistorySha()).isNull();
        f.assertNoWriteCalls();
    }

    @Test void requireReadyAlsoRefusesAnActualMidObservationRaceRatherThanReturningEvidence() throws Exception {
        var f = new Fixture();
        f.authorityAudit.beforeQuery = (sql, count) -> {
            if (sql.contains("ID2_AUTHORITY_WATERMARK") && count == 2)
                f.authority.update("UPDATE id2_authority_watermark SET generation=8 WHERE issuer=?", ISSUER);
        };
        assertThatThrownBy(f.reader()::requireReady)
                .isInstanceOfSatisfying(ProductGrantRecoveryEvidence.Refused.class, refused -> {
                    assertEvidence(refused.evidence(), "OBSERVATION_CHANGED", false);
                    assertThat(refused.getCause()).isNull();
                    assertThat(refused.getMessage()).isEqualTo("GRANT_RECOVERY_ADMISSION:OBSERVATION_CHANGED");
                });
        f.assertNoWriteCalls();
    }

    @Test void existingProtectedTaskRecoveryEntryRefusesBothPendingHistoriesWithoutClearingThem() throws Exception {
        for (boolean changed : List.of(false, true)) {
            var f = new Fixture();
            f.pending();
            if (changed) f.disableSubject();
            String expected = changed ? "PENDING_HISTORY_CHANGED" : "PENDING_HISTORY_UNCHANGED";
            var before = f.rows();
            String historyBefore = f.history();
            var connections = new ProductTaskRecoveryCheck.Connections(
                    f.primarySource.getURL(), "sa", "", "PUBLIC",
                    f.authoritySource.getURL(), "sa", "", "PUBLIC", ISSUER, evidenceRoot, 1024);
            assertThatThrownBy(() -> ProductTaskRecoveryCheck.verify(connections))
                    .isInstanceOfSatisfying(ProductGrantRecoveryEvidence.Refused.class, refused -> {
                        assertEvidence(refused.evidence(), expected, true);
                        assertThat(refused.getCause()).isNull();
                    });
            assertThat(f.history()).isEqualTo(historyBefore);
            f.assertUnwritten(before);
        }
    }

    @Test void publicCliRejectsArgumentsBeforeEnvironmentOrDatabaseAccess() throws Exception {
        var f = new Fixture();
        f.pending();
        var before = f.rows();
        String history = f.history();
        assertThatThrownBy(() -> ProductTaskRecoveryCheck.main(new String[]{"unexpected-argument"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Recovery check takes no arguments");
        assertThat(f.history()).isEqualTo(history);
        f.assertUnwritten(before);
    }
    @Test void existingEntryKeepsSchemaAndIndependentTopologyValidationAheadOfAdmission() throws Exception {
        var f = new Fixture();
        f.pending();
        var badSchema = new ProductTaskRecoveryCheck.Connections(f.primarySource.getURL(), "sa", "", "bad;schema",
                f.authoritySource.getURL(), "sa", "", "PUBLIC", ISSUER, evidenceRoot, 1024);
        assertThatThrownBy(() -> ProductTaskRecoveryCheck.verify(badSchema))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid recovery schema");
        var sameUrl = new ProductTaskRecoveryCheck.Connections(f.primarySource.getURL(), "sa", "", "PUBLIC",
                f.primarySource.getURL(), "sa", "", "PUBLIC", ISSUER, evidenceRoot, 1024);
        assertThatThrownBy(() -> ProductTaskRecoveryCheck.verify(sameUrl))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Independent authority URL is required");
    }

    private static void assertStableRefusal(Fixture f, String code) {
        var result = assertRefusal(f.reader(), code, true);
        assertThat(result.generation()).isEqualTo(7L);
        assertThat(result.observedHistorySha()).isEqualTo(f.history());
        assertThat(result.recordedHistorySha()).isEqualTo(f.rows().getFirst().get("HISTORY_SHA"));
        if (code.startsWith("PENDING")) assertThat(result.intentId()).isEqualTo(INTENT);
    }

}
