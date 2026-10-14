package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** SQL boundary tests only; product integration evidence uses real issuer, TLS routes and DM8 separately. */
class ProductSourceScopeRebindingTest {
    private JdbcTemplate db;
    private final Instant until=Instant.parse("2026-10-04T00:30:00Z");
    private ProductSourceScopeGrants.Input input() {
        return new ProductSourceScopeGrants.Input("task","member","source",3,21,until,"request",1);
    }
    @BeforeEach void database() {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE con3_task(task_id VARCHAR,organization_id VARCHAR,version BIGINT)");
        db.execute("CREATE TABLE con4_task_source_scope(scope_id VARCHAR,scope_version BIGINT,task_id VARCHAR,"
                + "organization_id VARCHAR,source_id VARCHAR,task_version BIGINT,source_epoch BIGINT,"
                + "source_row_version BIGINT,contract_version VARCHAR,decision_id VARCHAR,status VARCHAR,expires_at TIMESTAMP)");
        db.execute("CREATE TABLE con4_task_source_current(scope_id VARCHAR,scope_version BIGINT,status VARCHAR)");
        db.execute("CREATE TABLE con4_source_current(source_id VARCHAR,source_epoch BIGINT,row_version BIGINT,"
                + "decision_id VARCHAR,contract_version VARCHAR,status VARCHAR)");
        db.execute("CREATE TABLE id2_grant(grant_id VARCHAR,issuer VARCHAR,subject_id VARCHAR,organization VARCHAR,"
                + "domain_code VARCHAR,task_scope VARCHAR,resource_type VARCHAR,resource_id VARCHAR,resource_version BIGINT,"
                + "data_scope VARCHAR,action_code VARCHAR,purpose VARCHAR,status VARCHAR,expires_at TIMESTAMP,grant_generation BIGINT)");
        db.execute("CREATE TABLE id2_current_epoch(current_grant_id VARCHAR,authorization_epoch BIGINT)");
        db.execute("CREATE TABLE con4_source_field_use(decision_id VARCHAR,field_code VARCHAR,use_code VARCHAR,valid_until TIMESTAMP)");
        db.update("INSERT INTO con3_task VALUES('task','org',3)");
        db.update("INSERT INTO con4_task_source_scope VALUES('scope',1,'task','org','source',2,21,21,'contract','decision','ACTIVE',?)",Timestamp.from(until));
        db.update("INSERT INTO con4_task_source_current VALUES('scope',1,'ACTIVE')");
        db.update("INSERT INTO con4_source_current VALUES('source',21,21,'decision','contract','ACTIVE')");
        grant("query","member","QUERY","SOURCE_QUERY");grant("display","member","DISPLAY","SOURCE_DISPLAY");
        db.update("INSERT INTO con4_source_field_use VALUES('decision','TITLE','DISPLAY',?)",Timestamp.from(until));
    }
    private void grant(String id,String member,String action,String purpose) {
        db.update("INSERT INTO id2_grant VALUES(?,'issuer',?,'org','CONSULTATION','task','SOURCE_SCOPE','scope',1,"
                + "'scope:1',?,?,'ACTIVE',?,1)",id,member,action,purpose,Timestamp.from(until));
        db.update("INSERT INTO id2_current_epoch VALUES(?,1)",id);
    }
    private void check() {ProductSourceScopeRebinding.requireExisting(db,input(),"query","org");}
    @Test void sameSourceMemberPurposeAndUnextendedExpiryPass() {assertDoesNotThrow(this::check);}
    @Test void revocationBetweenSnapshotAndTransactionCannotBeRevived() {
        db.update("UPDATE id2_grant SET status='REVOKED' WHERE grant_id='query'");
        assertThrows(IllegalStateException.class,this::check);
    }
    @Test void obsoleteCurrentEpochRejects() {
        db.update("UPDATE id2_current_epoch SET authorization_epoch=2 WHERE current_grant_id='query'");
        assertThrows(IllegalStateException.class,this::check);
    }
    @Test void withdrawnSourceAndChangedVersionReject() {
        db.update("UPDATE con4_source_current SET source_epoch=22");
        assertThrows(IllegalStateException.class,this::check);
    }
    @Test void displayRevocationIsNotImplicitlyRestored() {
        db.update("UPDATE id2_grant SET status='REVOKED' WHERE grant_id='display'");
        assertThrows(IllegalStateException.class,this::check);
    }
    @Test void sharedScopeCannotSilentlyRevokeOtherMember() {
        grant("other-query","other-member","QUERY","SOURCE_QUERY");
        assertThrows(IllegalStateException.class,this::check);
    }
    @Test void noExpiryExtensionOrSkippedBusinessVersion() {
        var extension=new ProductSourceScopeGrants.Input("task","member","source",3,21,until.plusSeconds(1),"request",1);
        assertThrows(IllegalStateException.class,()->ProductSourceScopeRebinding.requireExisting(db,extension,"query","org"));
        db.update("UPDATE con4_task_source_scope SET task_version=1");
        assertThrows(IllegalStateException.class,this::check);
    }
    @Test void confirmedTaskChangedRejectsUnderRowLock() {
        db.update("UPDATE con3_task SET version=4");assertThrows(IllegalStateException.class,this::check);
    }
}
