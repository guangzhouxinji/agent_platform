package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;

/** Mechanical SQL/watermark C only, not a source approval, real actor, or DM8 request I. */
class ProductPatentComparisonAccessTest {
    private final Instant now = Instant.parse("2026-10-04T12:00:00Z");
    private final String issuer = "https://identity.example.invalid";
    private final ProductGrantWatermark watermark = mock(ProductGrantWatermark.class);
    private final Jwt jwt = Jwt.withTokenValue("mechanical-C-only").header("alg","RS256")
            .issuer(issuer).subject("member-C").build();
    private final ProductSourceAvailability.Active source = new ProductSourceAvailability.Active(
            "source-C","contract-C","capture-C","decision-C",7,9,"original-C","a".repeat(64),"b".repeat(64));
    private JdbcTemplate db;
    private ProductPatentDocumentAccess access;

    @BeforeEach void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:comparison-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE con4_task_source_scope(scope_id VARCHAR,scope_version BIGINT,task_version BIGINT,"
                + "task_id VARCHAR,source_id VARCHAR,organization_id VARCHAR,contract_version VARCHAR,decision_id VARCHAR,"
                + "source_epoch BIGINT,source_row_version BIGINT,status VARCHAR,expires_at TIMESTAMP)");
        db.execute("CREATE TABLE con4_task_source_current(task_id VARCHAR,source_id VARCHAR,scope_id VARCHAR,scope_version BIGINT,status VARCHAR)");
        db.execute("CREATE TABLE con3_task(task_id VARCHAR,organization_id VARCHAR,version BIGINT,expires_at TIMESTAMP)");
        db.execute("CREATE TABLE id2_grant(resource_id VARCHAR,resource_version BIGINT,data_scope VARCHAR,grant_id VARCHAR,"
                + "grant_generation BIGINT,issuer VARCHAR,subject_id VARCHAR,organization VARCHAR,task_scope VARCHAR,"
                + "domain_code VARCHAR,resource_type VARCHAR,action_code VARCHAR,purpose VARCHAR,status VARCHAR,expires_at TIMESTAMP)");
        db.execute("CREATE TABLE id2_current_epoch(current_grant_id VARCHAR,authorization_epoch BIGINT)");
        Timestamp until = Timestamp.from(now.plusSeconds(300));
        db.update("INSERT INTO con4_task_source_scope VALUES('scope-C',2,3,'task-C','source-C','org-C','contract-C',"
                + "'decision-C',7,9,'ACTIVE',?)",until);
        db.update("INSERT INTO con4_task_source_current VALUES('task-C','source-C','scope-C',2,'ACTIVE')");
        db.update("INSERT INTO con3_task VALUES('task-C','org-C',3,?)",until);
        db.update("INSERT INTO id2_grant VALUES('scope-C',2,'scope-C:2','grant-C',4,?,'member-C','org-C','task-C',"
                + "'CONSULTATION','SOURCE_SCOPE','COMPARE','COMPARISON','ACTIVE',?)",issuer,until);
        db.update("INSERT INTO id2_current_epoch VALUES('grant-C',4)");
        when(watermark.currentGeneration()).thenReturn(11L);
        access = new ProductPatentDocumentAccess(db,watermark,Clock.fixed(now,ZoneOffset.UTC),issuer);
    }
    @Test void exactComparisonWorksAndDisplayCannotReplaceIt() {
        assertThat(access.requireComparison("task-C",source,jwt).grantId()).isEqualTo("grant-C");
        assertThatThrownBy(() -> access.require("task-C",source,jwt)).hasMessage("Current source DISPLAY grant unavailable");
        db.update("UPDATE id2_grant SET action_code='DISPLAY',purpose='SOURCE_DISPLAY'");
        assertThat(access.require("task-C",source,jwt).grantId()).isEqualTo("grant-C");
        assertThatThrownBy(() -> access.requireComparison("task-C",source,jwt)).hasMessage("Current source COMPARE grant unavailable");
        db.update("UPDATE id2_grant SET action_code='QUERY',purpose='SOURCE_QUERY'");
        denied("task-C");
    }
    @Test void wrongTaskVersionScopeAndRevocationReject() {
        denied("other-task-C");
        db.update("UPDATE con3_task SET version=4"); denied("task-C");
        db.update("UPDATE con3_task SET version=3");
        db.update("UPDATE id2_grant SET data_scope='scope-C:1'"); denied("task-C");
        db.update("UPDATE id2_grant SET data_scope='scope-C:2',status='REVOKED'"); denied("task-C");
    }
    @Test void expirySourceEpochAndOldGrantGenerationReject() {
        db.update("UPDATE id2_grant SET expires_at=?",Timestamp.from(now)); denied("task-C");
        db.update("UPDATE id2_grant SET expires_at=?",Timestamp.from(now.plusSeconds(300)));
        db.update("UPDATE con4_task_source_scope SET source_epoch=6"); denied("task-C");
        db.update("UPDATE con4_task_source_scope SET source_epoch=7");
        db.update("UPDATE id2_current_epoch SET authorization_epoch=5"); denied("task-C");
    }
    @Test void independentWatermarkChangeAndWrongIssuerReject() {
        doThrow(new IllegalStateException("Grant authority changed" )).when(watermark).assertUnchanged(11);
        assertThatThrownBy(() -> access.requireComparison("task-C",source,jwt)).hasMessage("Grant authority changed");
        Jwt wrong = Jwt.withTokenValue("mechanical-C-only").header("alg","RS256").issuer("https://other.invalid").subject("member-C").build();
        assertThatThrownBy(() -> access.requireComparison("task-C",source,wrong)).hasMessage("Source display identity unavailable");
        assertThatThrownBy(() -> access.requireComparison("task-C",source,null)).hasMessage("Source display identity unavailable");
    }
    private ProductComparisonWorkerTaskAuthority.Authorized actor(String task,long version,String human) {
        return new ProductComparisonWorkerTaskAuthority.Authorized("worker",human,task,version,11,now.plusSeconds(300));
    }
    @Test void delegatedComparisonUsesExactHumanAndNeverTurnsQueryIntoComparison() {
        var actor=actor("task-C",3,"member-C");
        assertThat(access.requireDelegatedComparison("task-C",source,actor).grantId()).isEqualTo("grant-C");
        assertThatThrownBy(()->access.requireDelegatedDisplay("task-C",source,actor)).hasMessageContaining("DISPLAY");
        db.update("UPDATE id2_grant SET action_code='QUERY',purpose='SOURCE_QUERY'");
        assertThatThrownBy(()->access.requireDelegatedComparison("task-C",source,actor)).hasMessageContaining("COMPARE");
    }
    @Test void delegatedSourceRejectsDifferentHumanTaskVersionAndExpiredAuthority() {
        assertThatThrownBy(()->access.requireDelegatedComparison("task-C",source,actor("task-C",3,"other"))).hasMessageContaining("COMPARE");
        assertThatThrownBy(()->access.requireDelegatedComparison("task-C",source,actor("task-C",4,"member-C"))).hasMessageContaining("Task changed");
        assertThatThrownBy(()->access.requireDelegatedComparison("task-C",source,actor("other",3,"member-C"))).hasMessageContaining("identity");
        var expired=new ProductComparisonWorkerTaskAuthority.Authorized("worker","member-C","task-C",3,11,now);
        assertThatThrownBy(()->access.requireDelegatedComparison("task-C",source,expired)).hasMessageContaining("identity");
    }
    @Test void delegatedSourceCannotReadAfterIndependentGrantWatermarkChanges() {
        doThrow(new IllegalStateException("Grant authority changed")).when(watermark).assertUnchanged(11);
        assertThatThrownBy(()->access.requireDelegatedComparison("task-C",source,actor("task-C",3,"member-C")))
                .hasMessage("Grant authority changed");
    }
    private void denied(String task) {
        assertThatThrownBy(() -> access.requireComparison(task,source,jwt)).hasMessage("Current source COMPARE grant unavailable");
    }
    @Test void modelInputIsIndependentFromComparisonAndStillRequiresCurrentDelegatingHumanScope() {
        var actor=new ProductComparisonWorkerTaskAuthority.Authorized("worker-C","member-C","task-C",3,11,now.plusSeconds(200));
        assertThatThrownBy(()->access.requireDelegatedModelInput("task-C",source,actor)).hasMessageContaining("MODEL_INPUT");
        db.update("UPDATE id2_grant SET purpose='MODEL_INPUT'");
        assertThat(access.requireDelegatedModelInput("task-C",source,actor).grantId()).isEqualTo("grant-C");
        assertThatThrownBy(()->access.requireDelegatedComparison("task-C",source,actor)).hasMessage("Current source COMPARE grant unavailable");
        db.update("UPDATE id2_current_epoch SET authorization_epoch=5");
        assertThatThrownBy(()->access.requireDelegatedModelInput("task-C",source,actor)).isInstanceOf(IllegalStateException.class);
    }
}
