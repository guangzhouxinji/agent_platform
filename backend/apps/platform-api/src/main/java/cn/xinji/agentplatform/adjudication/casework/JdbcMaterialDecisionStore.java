package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.Decision;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;

/** Same primary connection as case mutation. No model run insert or budget mutation. */
public final class JdbcMaterialDecisionStore {
    public record Binding(String versionId,String caseId,String runId,String candidateId,long organizationVersion,
            String baseVersionId,long workVersion,long inputVersion,String identity,String actor,String requestKey,
            String requestSha,String policyKey,String sourceSha,String inputSha,String action,Instant createdAt,Instant expiresAt) { }
    private record Row(Binding binding,String encrypted,String sha) { }
    private volatile boolean retentionHealthy=true;
    private final JdbcTemplate db; private final MaterialDecisionVault vault; private final CaseWorkCodec codec; private final Clock clock;
    public JdbcMaterialDecisionStore(JdbcTemplate db,MaterialDecisionVault vault,CaseWorkCodec codec,Clock clock){this.db=db;this.vault=vault;this.codec=codec;this.clock=clock;}
    public long lockHead(String caseId) {
        healthy();transaction();
        if(db.queryForObject("SELECT COUNT(*) FROM adj3_material_decision_head WHERE case_id=?",Integer.class,caseId)==0)
            db.update("INSERT INTO adj3_material_decision_head(case_id,organization_version) VALUES(?,0)",caseId);
        return db.queryForObject("SELECT organization_version FROM adj3_material_decision_head WHERE case_id=? FOR UPDATE",Long.class,caseId);
    }
    public long head(String caseId){var rows=db.query("SELECT organization_version FROM adj3_material_decision_head WHERE case_id=?",(r,n)->r.getLong(1),caseId);return rows.isEmpty()?0:rows.getFirst();}
    public Map<String,String> candidateHeads(String caseId){var out=new HashMap<String,String>();db.query("SELECT run_id,candidate_id,version_id FROM adj3_material_decision_version WHERE case_id=? ORDER BY organization_version",rs->{out.put(rs.getString(1)+":"+rs.getString(2),rs.getString(3));},caseId);return Map.copyOf(out);}
    public String requestVersion(String caseId,String identity,String key,String sha){var rows=db.query("SELECT version_id,request_sha FROM adj3_material_decision_version WHERE case_id=? AND actor_identity=? AND request_key=?",(r,n)->new String[]{r.getString(1),r.getString(2)},caseId,identity,key);if(rows.isEmpty())return null;if(!sha.equals(rows.getFirst()[1]))throw new IllegalArgumentException("MATERIAL_DECISION_REQUEST_CONFLICT");return rows.getFirst()[0];}
    public void save(Decision d,String identity,String key,String requestSha,String policyKey,String inputSha){
        transaction();if(!TransactionSynchronizationManager.hasResource(Objects.requireNonNull(db.getDataSource())))throw new IllegalStateException("MATERIAL_DECISION_PRIMARY_TRANSACTION_REQUIRED");
        var b=new Binding(d.versionId(),d.caseId(),d.runId(),d.candidateId(),d.organizationVersion(),d.baseVersionId(),d.workVersion(),d.inputVersion(),identity,d.actor(),key,requestSha,policyKey,d.source().sha256(),inputSha,d.action().name(),d.createdAt(),d.expiresAt());
        String encrypted=vault.seal(d.versionId(),d.expiresAt(),codec.encode(b),codec.encode(d));
        // Seal owns/cleans partial files. Register only after success: a UUID collision must not delete an old key.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status==STATUS_ROLLED_BACK)vault.discardUncommitted(d.versionId());}});
        db.update("INSERT INTO adj3_material_decision_version(version_id,case_id,run_id,candidate_id,organization_version,base_version_id,first_marker,work_version,input_version,actor_identity,actor_ref,request_key,request_sha,policy_key,source_sha,input_sha,action_code,created_at,expires_at,payload,payload_sha) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            d.versionId(),d.caseId(),d.runId(),d.candidateId(),d.organizationVersion(),d.baseVersionId(),d.baseVersionId()==null?"FIRST":null,d.workVersion(),d.inputVersion(),identity,d.actor(),key,requestSha,policyKey,d.source().sha256(),inputSha,d.action().name(),Timestamp.from(d.createdAt()),Timestamp.from(d.expiresAt()),encrypted,sha256(encrypted));
        if(db.update("UPDATE adj3_material_decision_head SET organization_version=? WHERE case_id=? AND organization_version=?",d.organizationVersion(),d.caseId(),d.organizationVersion()-1)!=1)throw new IllegalArgumentException("MATERIAL_DECISION_VERSION_CONFLICT");
    }
    public Binding binding(String caseId,String id){return row(caseId,id).binding();}
    public Decision load(String caseId,String id){healthy();var row=row(caseId,id);var b=row.binding();if(!b.expiresAt().isAfter(clock.instant()))throw new SecurityException("MATERIAL_DECISION_EXPIRED");if(b.createdAt().isAfter(clock.instant()))throw new SecurityException("MATERIAL_DECISION_CLOCK_INVALID");
        if(!sha256(row.encrypted()).equals(row.sha()))throw new IllegalStateException("MATERIAL_DECISION_INTEGRITY_FAILED");
        var d=codec.decode(vault.open(id,b.expiresAt(),codec.encode(b),row.encrypted()),Decision.class);
        if(!d.versionId().equals(b.versionId())||!d.caseId().equals(b.caseId())||!d.runId().equals(b.runId())||!d.candidateId().equals(b.candidateId())||!Objects.equals(d.baseVersionId(),b.baseVersionId())||d.organizationVersion()!=b.organizationVersion()||d.workVersion()!=b.workVersion()||d.inputVersion()!=b.inputVersion()||!d.actor().equals(b.actor())||!d.source().sha256().equals(b.sourceSha())||!d.action().name().equals(b.action())||!d.createdAt().equals(b.createdAt())||!d.expiresAt().equals(b.expiresAt()))throw new IllegalStateException("MATERIAL_DECISION_INTEGRITY_FAILED");return d;
    }
    public List<String> page(String caseId,long after,int limit){healthy();if(after<0||limit<1||limit>50)throw new IllegalArgumentException("MATERIAL_DECISION_PAGE_INVALID");return db.query("SELECT version_id FROM adj3_material_decision_version WHERE case_id=? AND organization_version>? ORDER BY organization_version FETCH FIRST "+limit+" ROWS ONLY",(r,n)->r.getString(1),caseId,after);}
    public void purgeExpired(org.springframework.transaction.support.TransactionTemplate tx){retentionHealthy=false;tx.executeWithoutResult(status->purgeExpired());retentionHealthy=true;}
    private void purgeExpired(){transaction();vault.purgeExpired();var ids=db.query("SELECT version_id FROM adj3_material_decision_version WHERE expires_at<=? ORDER BY organization_version DESC",(r,n)->r.getString(1),Timestamp.from(clock.instant()));for(var id:ids)db.update("DELETE FROM adj3_material_decision_version WHERE version_id=?",id);}
    private Row row(String caseId,String id){var rows=db.query("SELECT * FROM adj3_material_decision_version WHERE case_id=? AND version_id=?",(r,n)->new Row(new Binding(r.getString("version_id"),r.getString("case_id"),r.getString("run_id"),r.getString("candidate_id"),r.getLong("organization_version"),r.getString("base_version_id"),r.getLong("work_version"),r.getLong("input_version"),r.getString("actor_identity"),r.getString("actor_ref"),r.getString("request_key"),r.getString("request_sha"),r.getString("policy_key"),r.getString("source_sha"),r.getString("input_sha"),r.getString("action_code"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant()),r.getString("payload"),r.getString("payload_sha")),caseId,id);if(rows.size()!=1)throw new IllegalArgumentException("MATERIAL_DECISION_NOT_FOUND");return rows.getFirst();}
    private void healthy(){if(!retentionHealthy)throw new IllegalStateException("MATERIAL_DECISION_RETENTION_UNHEALTHY");}
    private void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("MATERIAL_DECISION_TRANSACTION_REQUIRED");if(!TransactionSynchronizationManager.hasResource(Objects.requireNonNull(db.getDataSource())))throw new IllegalStateException("MATERIAL_DECISION_PRIMARY_TRANSACTION_REQUIRED");}
}
