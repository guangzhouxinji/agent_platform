package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Signed component identity and real main/independent SQL; maintenance admission is controlled test input. */
final class ProductHumanRequestApplicationFixture {
    final ProductHumanRequestLocksTest lockFixture = new ProductHumanRequestLocksTest();
    final ProductHumanTaskTransactionTest base = lockFixture.base;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ProductHumanComparisonReferences comparisons = mock(ProductHumanComparisonReferences.class);
    final OrdinaryHumanRetention.Policy policy;
    final ProductRetentionPolicy taskPolicy;
    final ProductRetentionPolicy factsPolicy;
    ProductHumanRequestApplicationFixture() {
        this(new OrdinaryHumanRetention.Policy("human-v3",3,"e".repeat(64)),
                new ProductRetentionPolicy("approved-policy",1,"a".repeat(64),Duration.ofHours(1)),
                new ProductRetentionPolicy("goal-policy",1,"a".repeat(64),Duration.ofHours(1)));
    }
    ProductHumanRequestApplicationFixture(OrdinaryHumanRetention.Policy humanPolicy,
            ProductRetentionPolicy taskPolicy,ProductRetentionPolicy factsPolicy) {
        this.policy=humanPolicy;this.taskPolicy=taskPolicy;this.factsPolicy=factsPolicy;
    }
    ProductHumanCleanupScope cleanup;
    ProductSourceEvidenceFiles files;
    ProductHumanRequestApplication application;
    final ProductHumanRequestApplication.Command command = new ProductHumanRequestApplication.Command(
            7, null, List.of(), "Please help clarify the ordinary consultation", "ORDINARY_ASSISTANCE");

    void setup(Path root, boolean initialize) throws Exception {
        setup(root, initialize, "capture-1", "a".repeat(64), "a".repeat(64));
    }

    void setup(Path root, boolean initialize, String captureVersion, String manifestSha, String recordsSha) throws Exception {
        setup(root, initialize, captureVersion, manifestSha, recordsSha, true);
    }

    void setup(Path root, boolean initialize, String captureVersion, String manifestSha, String recordsSha, boolean allowSave) throws Exception {
        base.clock.now = base.clock.now.truncatedTo(ChronoUnit.MILLIS);
        lockFixture.setupCapture(captureVersion, "capture/manifest.json", manifestSha, recordsSha, allowSave);
        // Controlled fixture policy inputs, before any human scope or request exists.
        base.db.update("UPDATE con3_task SET retention_ref=?,retention_version=?,retention_evidence_sha=?",
                taskPolicy.reference(),taskPolicy.version(),taskPolicy.evidenceSha256());
        for(String table:List.of("con3_goal_revision","con3_message"))
            base.db.update("UPDATE "+table+" SET retention_ref=?,retention_version=?,retention_evidence_sha=?",
                    factsPolicy.reference(),factsPolicy.version(),factsPolicy.evidenceSha256());
        migrate(base.source, "dm8/V110__product_human_cleanup_scope.sql");
        migrate(base.authority.getDataSource(), "identity-authority/V006__consultation_human_cleanup_scope.sql");
        cleanup = spy(new ProductHumanCleanupScope(base.db, base.authority, base.manager, mapper, base.clock,
                new ProductHumanCleanupScope.InitializationAdmission() {
                    public void requireNewTaskWithoutHistory(String task, String organization,
                            ProductHumanCleanupScope.Policy p, String operator, Instant now) {
                        if (!task.equals(base.task.id()) || !organization.equals(base.task.organization())
                                || !operator.equals("component-maintainer")) throw new IllegalStateException("Not admitted by fixture");
                    }
                    public void requireCurrent(Instant now) {
                        if (!now.isBefore(base.task.expiresAt())) throw new IllegalStateException("Fixture admission expired");
                    }
                }));
        if (initialize) cleanup.initialize(base.task.id(), new ProductHumanCleanupScope.Policy(
                policy.reference(), policy.version(), policy.evidenceSha256()), "component-maintainer",
                base.task.expiresAt(), base.clock.instant(), base.task.expiresAt());
        var facts = new ProductHumanTaskFacts(base.db, base.access, base.decoder,
                factsPolicy, mapper, base.clock);
        files = spy(new ProductSourceEvidenceFiles(root, 1024 * 1024));
        var materials = new ProductHumanMaterialReferences(new ProductCollectedPatentTitles(files, mapper));
        application = new ProductHumanRequestApplication(base.db, base.authority.getDataSource(), base.transaction,
                lockFixture.locks, facts, materials, comparisons, cleanup,
                new ProductHumanRequestStore(base.db, mapper, base.clock), base.decoder, new ProductComparisonJson(mapper),
                policy, taskPolicy, base.clock);
        clearInvocations(comparisons, files, cleanup);
    }

    ProductHumanRequestApplication.Result request() {
        return application.request(base.token, base.task.id(), "human-original-key", command);
    }
}
