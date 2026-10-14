package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ProductTrademarkSourceProjectionAuthorityTest {
    @TempDir Path root;
    @Test void realCurrentCertificationAndRecheckNeverProjectOrReturnWriteContext() throws Exception {
        var f = new ProductTrademarkAuthorizationFixture(root);
        var scope = f.adapter.authenticate(f.request, "source", "contract-v1");
        f.adapter.recheck(f.request, scope);
        assertThat(scope.originals().originals()).hasSize(21);
        assertThat(scope.expiresAt()).isEqualTo(ProductTrademarkProjectionFixture.UNTIL);
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit WHERE role_code='SOURCES' AND decision='ALLOW'", Integer.class)).isEqualTo(2);
        assertThat(f.capture.db.queryForObject("SELECT COUNT(*) FROM con1tm_capture_request", Integer.class)).isZero();
        assertThatThrownBy(() -> f.adapter(List.of()).authenticate(f.request, "source", "contract-v1")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> f.adapter(List.of(f.pin, f.pin))).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"raw-off-page", "approval", "map", "nature", "policy", "no-index", "maintainer", "role", "task-version", "organization", "scope", "pending", "primary-old", "expired", "tls"})
    void eachCurrentBoundaryClosesExistingWitness(String kind) throws Exception {
        var f = new ProductTrademarkAuthorizationFixture(root);
        var scope = f.adapter.authenticate(f.request, "source", "contract-v1");
        switch (kind) {
            case "raw-off-page" -> f.file(f.input.originals().getLast().ref(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "approval" -> f.file("approval.json", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "map" -> f.file("map.txt", "changed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "nature" -> f.file(f.input.natureEvidence().ref(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "policy" -> f.file("backup.json", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "no-index" -> { f.db.update("DELETE FROM con4_source_field_use WHERE use_code='INDEX'"); f.refreshHistory(); }
            case "maintainer" -> f.authority.update("UPDATE id2_maintainer SET status='REVOKED',version=2");
            case "role" -> f.authority.update("UPDATE id2_maintainer SET allowed_roles='GRANTS'");
            case "task-version" -> f.db.update("UPDATE con3_task SET authorization_version=8");
            case "organization" -> f.db.update("UPDATE con3_organization SET status='DISABLED'");
            case "scope" -> f.db.update("DELETE FROM con3_data_scope");
            case "pending" -> f.authority.update("UPDATE id4_source_watermark SET state='PENDING',intent_id='pending'");
            case "primary-old" -> f.db.update("UPDATE con4_source_current SET source_epoch=2");
            case "expired" -> f.capture.clock.now = ProductTrademarkProjectionFixture.UNTIL;
            case "tls" -> f.request.setSecure(false);
            default -> throw new AssertionError(kind);
        }
        assertThatThrownBy(() -> f.adapter.recheck(f.request, scope)).isInstanceOf(RuntimeException.class);
        assertThat(f.capture.db.queryForObject("SELECT COUNT(*) FROM con1tm_capture_request", Integer.class)).isZero();
    }
    @Test void scopeCannotCrossOwnerThreadOrRequestIdentity() throws Exception {
        var f = new ProductTrademarkAuthorizationFixture(root);
        var scope = f.adapter.authenticate(f.request, "source", "contract-v1");
        assertThatThrownBy(() -> f.adapter(List.of(f.pin)).recheck(f.request, scope)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> f.adapter.recheck(new MockHttpServletRequest(), scope)).isInstanceOf(RuntimeException.class);
        var thrown = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> { try { f.adapter.recheck(f.request, scope); } catch (Throwable failure) { thrown.set(failure); } });
        thread.start(); thread.join(); assertThat(thrown.get()).isInstanceOf(RuntimeException.class);
    }
    @Test void ambientTransactionsAndSharedAuthorityAreRejectedBeforeCertification() throws Exception {
        var f = new ProductTrademarkAuthorizationFixture(root);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.primary));
        transaction.execute(status -> {
            assertThatThrownBy(() -> f.adapter.authenticate(f.request, "source", "contract-v1")).isInstanceOf(RuntimeException.class);
            return null;
        });
        assertThatThrownBy(() -> new ProductTrademarkSourceProjectionAuthority(f.db, f.db, f.files,
                f.capture.json, f.capture.clock, List.of(f.pin))).isInstanceOf(RuntimeException.class);
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit", Integer.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"purpose", "objects", "deadline", "approved-body", "duplicate", "trailing"})
    void pinnedSchemaStillRequiresExactPolicyAndApprovalScope(String kind) throws Exception {
        var f = new ProductTrademarkAuthorizationFixture(root);
        if (kind.equals("approved-body")) {
            f.approval.put("subject", "other");
            var bytes = f.capture.json.readTree(java.nio.file.Files.readAllBytes(root.resolve("capability.json")));
            ((com.fasterxml.jackson.databind.node.ObjectNode) bytes).set("projectionApproval", f.approval);
            f.file("capability.json", f.capture.json.writeValueAsBytes(bytes));
        } else if (kind.equals("duplicate") || kind.equals("trailing")) {
            f.file("capability.json", (kind.equals("duplicate") ? "{\"schema\":\"x\",\"schema\":\"y\"}" : "{} {}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else {
            var policy = (com.fasterxml.jackson.databind.node.ObjectNode) f.capture.json.readTree(java.nio.file.Files.readAllBytes(root.resolve("retention.json")));
            if (kind.equals("purpose")) policy.put("purpose", "BACKUP");
            if (kind.equals("objects")) policy.putArray("projectionObjects").add("RAW");
            if (kind.equals("deadline")) policy.put("until", ProductTrademarkProjectionFixture.UNTIL.plusSeconds(1).toString());
            f.file("retention.json", f.capture.json.writeValueAsBytes(policy));
            f.approval.put("retentionEvidenceSHA", f.hash("retention.json")); f.seal();
        }
        var pin = new ProductTrademarkProjectionApproval.Pin("source", "contract-v1", "capability.json", f.hash("capability.json"), "approval.json", f.hash("approval.json"), f.approval.path("approvedScopeSHA").asText());
        assertThatThrownBy(() -> new ProductTrademarkProjectionApproval(f.capture.json, f.files, pin)).isInstanceOf(RuntimeException.class);
    }
}
