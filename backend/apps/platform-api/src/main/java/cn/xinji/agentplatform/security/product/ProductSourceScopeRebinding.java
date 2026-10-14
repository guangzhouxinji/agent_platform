package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** A non-expanding continuation check performed inside the independent grant write fence. */
final class ProductSourceScopeRebinding {
    private final JdbcTemplate db;
    private final ProductSourceScopeGrants scopes;
    private final ProductMaintenanceProof maintenance;
    ProductSourceScopeRebinding(JdbcTemplate db,ProductSourceScopeGrants scopes,ProductMaintenanceProof maintenance) {
        this.db=db; this.scopes=scopes; this.maintenance=maintenance;
    }
    ProductSourceScopeGrants.Receipt rebind(ProductSourceScopeGrants.Input input,String previousGrantId,
            HttpServletRequest request) {
        String actor=maintenance.require(request,ProductMaintenanceProof.Role.SOURCES);
        if(!actor.equals(maintenance.require(request,ProductMaintenanceProof.Role.GRANTS)))
            throw new SecurityException("Maintenance principal changed");
        String organization=maintenance.requireOrganization(request,ProductMaintenanceProof.Role.SOURCES);
        if(input==null||input.expectedScopeVersion()<1||previousGrantId==null||previousGrantId.isBlank())
            throw new IllegalArgumentException("Exact previous source grant required");
        return scopes.authorizeVerified(input,actor,organization,
                ()->requireExisting(db,input,previousGrantId,organization),previousGrantId);
    }
    static void requireExisting(JdbcTemplate db,ProductSourceScopeGrants.Input input,String grant,String organization) {
        var version=db.query("SELECT version FROM con3_task WHERE task_id=? AND organization_id=? FOR UPDATE",
                (rs,n)->rs.getLong(1),input.taskId(),organization);
        if(version.size()!=1||version.getFirst()!=input.taskVersion())
            throw new IllegalStateException("Confirmed Task changed during source binding");
        record Prior(String scope,String issuer,String decision) { }
        List<Prior> prior=db.query("SELECT s.scope_id,g.issuer,s.decision_id FROM con4_task_source_scope s "
                + "JOIN con4_task_source_current c ON c.scope_id=s.scope_id AND c.scope_version=s.scope_version "
                + "JOIN con4_source_current source ON source.source_id=s.source_id AND source.source_epoch=s.source_epoch "
                + "AND source.row_version=s.source_row_version AND source.decision_id=s.decision_id "
                + "AND source.contract_version=s.contract_version AND source.status='ACTIVE' "
                + "JOIN id2_grant g ON g.resource_id=s.scope_id AND g.resource_version=s.scope_version "
                + "JOIN id2_current_epoch e ON e.current_grant_id=g.grant_id AND e.authorization_epoch=g.grant_generation "
                + "WHERE s.task_id=? AND s.organization_id=? AND s.source_id=? AND s.scope_version=? "
                + "AND s.task_version=? AND s.source_row_version=? AND s.status='ACTIVE' AND c.status='ACTIVE' "
                + "AND s.expires_at>=? AND g.grant_id=? AND g.subject_id=? AND g.organization=? "
                + "AND g.domain_code='CONSULTATION' AND g.task_scope=? AND g.resource_type='SOURCE_SCOPE' "
                + "AND g.data_scope=s.scope_id || ':' || s.scope_version AND g.action_code='QUERY' "
                + "AND g.purpose='SOURCE_QUERY' AND g.status='ACTIVE' AND g.expires_at>=?",
                (rs,n)->new Prior(rs.getString(1),rs.getString(2),rs.getString(3)),input.taskId(),organization,
                input.sourceId(),input.expectedScopeVersion(),input.taskVersion()-1,input.sourceRowVersion(),
                Timestamp.from(input.expiresAt()),grant,input.memberSubject(),organization,input.taskId(),
                Timestamp.from(input.expiresAt()));
        if(prior.size()!=1)throw new IllegalStateException("Previous query grant changed, revoked or expired");
        var p=prior.getFirst();
        Integer other=db.queryForObject("SELECT COUNT(*) FROM id2_grant g JOIN id2_current_epoch e "
                + "ON e.current_grant_id=g.grant_id AND e.authorization_epoch=g.grant_generation "
                + "WHERE g.resource_type='SOURCE_SCOPE' AND g.resource_id=? AND g.resource_version=? "
                + "AND g.status='ACTIVE' AND (g.subject_id<>? OR g.issuer<>?)",Integer.class,
                p.scope(),input.expectedScopeVersion(),input.memberSubject(),p.issuer());
        if(other==null||other!=0)throw new IllegalStateException("Shared source scope needs explicit maintenance");
        Integer displays=db.queryForObject("SELECT COUNT(*) FROM con4_source_field_use WHERE decision_id=? "
                + "AND use_code='DISPLAY' AND valid_until>=?",Integer.class,p.decision(),Timestamp.from(input.expiresAt()));
        if(displays!=null&&displays>0){
            Integer existing=db.queryForObject("SELECT COUNT(*) FROM id2_grant g JOIN id2_current_epoch e "
                    + "ON e.current_grant_id=g.grant_id AND e.authorization_epoch=g.grant_generation "
                    + "WHERE g.issuer=? AND g.subject_id=? AND g.organization=? AND g.task_scope=? "
                    + "AND g.resource_type='SOURCE_SCOPE' AND g.resource_id=? AND g.resource_version=? "
                    + "AND g.action_code='DISPLAY' AND g.purpose='SOURCE_DISPLAY' AND g.status='ACTIVE' AND g.expires_at>=?",
                    Integer.class,p.issuer(),input.memberSubject(),organization,input.taskId(),p.scope(),
                    input.expectedScopeVersion(),Timestamp.from(input.expiresAt()));
            if(existing==null||existing!=1)throw new IllegalStateException("Continuation cannot create or revive display rights");
        }
    }
}
