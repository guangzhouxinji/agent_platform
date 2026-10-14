package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static cn.xinji.agentplatform.security.product.ProductGrantRecoveryEvidenceTestSupport.ISSUER;
import cn.xinji.agentplatform.security.product.ProductGrantRecoveryEvidenceTestSupport.Fixture;
import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductHumanSourceRecoveryEntryTest {
    @TempDir Path root;

    @Test void sourceEntryVerifiesRealNonemptyWaitingPacketAndAuthenticatedPolicyFiles() throws Exception {
        String raw="controlled component policy evidence for the source-only entry";
        Files.writeString(root.resolve("policy"),raw);
        String sha=ProductComparisonJson.hash(raw);
        var human=new OrdinaryHumanRetention.Policy("policy",3,sha);
        var task=new ProductRetentionPolicy("policy",1,sha,Duration.ofHours(1));
        var facts=new ProductRetentionPolicy("policy",1,sha,Duration.ofHours(1));
        var fixture=new ProductHumanWaitingRecoveryFixture(new ProductHumanRequestApplicationFixture(human,task,facts));
        fixture.setup(root);
        var base=fixture.request.base;
        // This entry also executes the existing complete Task recovery reader.
        ProductHumanTaskFactsFixture.migrate(base.source,"dm8/V043__product_task_cancel_epoch.sql",
                "dm8/V045__product_task_execution_fence.sql","dm8/V047__product_task_worker_delegation.sql",
                "dm8/V048__product_task_execution_reconciliation.sql","dm8/V077__product_execution_attempt_history.sql");
        var packet=fixture.packet(false);
        var connections=new ProductTaskRecoveryCheck.Connections(base.source.getURL(),"SA","","PUBLIC",
                ((org.h2.jdbcx.JdbcDataSource)base.authority.getDataSource()).getURL(),"SA","","PUBLIC",
                "https://identity.example.org",root,1024*1024);
        var before=base.db.queryForMap("SELECT COUNT(*) AS n FROM con6_human_package");
        var result=ProductTaskRecoveryCheck.verifyHumanSource(connections,human,task,facts,fixture.until(),base.clock);
        assertThat(result.packages()).isEqualTo(1);
        assertThat(result.scopes()).isEqualTo(1);
        assertThat(result.businessActivation()).isFalse();
        assertThat(result.closureSha()).matches("[a-f0-9]{64}");
        assertThat(base.db.queryForMap("SELECT COUNT(*) AS n FROM con6_human_package")).isEqualTo(before);
        assertThat(base.db.queryForObject("SELECT manifest_sha FROM con6_human_package",String.class))
                .isEqualTo(packet.body().manifestSha());
    }

    @Test void sourceEntryAuthenticatesPoliciesAndRefusesPendingAuthorityWithoutRepair() throws Exception {
        var f = new Fixture(); f.pending();
        String history = f.history(); var before = f.rows();
        String raw = "controlled component policy evidence";
        Files.writeString(root.resolve("policy"), raw);
        String sha = ProductComparisonJson.hash(raw);
        var human = new OrdinaryHumanRetention.Policy("policy", 3, sha);
        var task = new ProductRetentionPolicy("policy", 1, sha, Duration.ofHours(1));
        var connections = new ProductTaskRecoveryCheck.Connections(f.primarySource.getURL(), "sa", "", "PUBLIC",
                f.authoritySource.getURL(), "sa", "", "PUBLIC", ISSUER, root, 1024);
        var clock = Clock.fixed(Instant.parse("2026-10-13T08:00:00Z"), ZoneOffset.UTC);
        assertThatThrownBy(() -> ProductTaskRecoveryCheck.verifyHumanSource(connections, human, task, task,
                clock.instant().plusSeconds(30), clock)).isInstanceOf(ProductGrantRecoveryEvidence.Refused.class);
        assertThat(f.history()).isEqualTo(history); f.assertUnwritten(before);

        Files.writeString(root.resolve("policy"), "changed bytes");
        assertThatThrownBy(() -> ProductTaskRecoveryCheck.verifyHumanSource(connections, human, task, task,
                clock.instant().plusSeconds(30), clock)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Source evidence digest changed");
        assertThat(f.history()).isEqualTo(history); f.assertUnwritten(before);
    }
}
