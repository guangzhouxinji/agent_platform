package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Exact existing Grant coordinates; no task grant, missing epoch or action is manufactured. */
final class ProductPromptWorkspaceGrants {
    record Coordinate(Workspace workspace,Action operation) {
        String scope(){return "PROMPT_WORKSPACE:"+workspace.id()+":"+workspace.scopeVersion();}
        String resource(){return workspace.id()+":"+operation.name();}
        ProductAccessPolicy.Action action(){return switch(operation){
            case CREATE,COPY->ProductAccessPolicy.Action.CREATE;
            case SAVE->ProductAccessPolicy.Action.WRITE;
            case READ,PREVIEW->ProductAccessPolicy.Action.READ;
        };}
    }
    record Epoch(long authorization,long source,String grantId) { }
    record Grant(long version,long generation,Instant until,String status,long scopeVersion,String taskScope,String dataScope,
                 String action,String purpose) { }
    record Witness(Coordinate coordinate,Epoch epoch,Grant grant) { }
    private final JdbcTemplate db;
    private final ProductConsultationAccess access;
    private final Clock clock;
    ProductPromptWorkspaceGrants(JdbcTemplate db,ProductConsultationAccess access,Clock clock){
        this.db=db;this.access=access;this.clock=clock;
    }
    Witness current(Jwt jwt,Coordinate coordinate,boolean locking){
        String issuer=jwt.getIssuer().toString();
        var epochs=db.query("SELECT authorization_epoch,source_epoch,current_grant_id FROM id2_current_epoch "
                        +"WHERE issuer=? AND subject_id=? AND organization=? AND domain_code='CONSULTATION' "
                        +"AND resource_type='PROMPT_WORKSPACE' AND resource_id=? AND action_code=? AND purpose='TASK_WORK'"
                        +(locking?" FOR UPDATE":""),(rs,n)->new Epoch(rs.getLong(1),rs.getLong(2),rs.getString(3)),
                issuer,jwt.getSubject(),coordinate.workspace().organization(),coordinate.resource(),coordinate.action().name());
        if(epochs.size()!=1||epochs.getFirst().grantId()==null)throw denied();
        Epoch epoch=epochs.getFirst();
        var rows=db.query("SELECT grant_version,grant_generation,expires_at,status,resource_version,task_scope,data_scope,action_code,purpose "
                        +"FROM id2_grant WHERE grant_id=? AND issuer=? AND subject_id=? AND organization=? "
                        +"AND domain_code='CONSULTATION' AND resource_type='PROMPT_WORKSPACE' AND resource_id=?",
                (rs,n)->new Grant(rs.getLong(1),rs.getLong(2),rs.getTimestamp(3).toInstant(),rs.getString(4),rs.getLong(5),rs.getString(6),
                        rs.getString(7),rs.getString(8),rs.getString(9)),epoch.grantId(),issuer,jwt.getSubject(),
                coordinate.workspace().organization(),coordinate.resource());
        if(rows.size()!=1)throw denied();
        Grant grant=rows.getFirst();
        var facts=new ProductConsultationAccess.ResourceFacts(coordinate.workspace().organization(),coordinate.scope(),coordinate.scope(),
                "PROMPT_WORKSPACE",coordinate.resource(),coordinate.workspace().scopeVersion());
        if(!"ACTIVE".equals(grant.status())||!clock.instant().isBefore(grant.until())||grant.generation()!=epoch.authorization()
                ||grant.scopeVersion()!=coordinate.workspace().scopeVersion()||!grant.taskScope().equals(coordinate.scope())
                ||!grant.dataScope().equals(coordinate.scope())||!grant.action().equals(coordinate.action().name())
                ||!"TASK_WORK".equals(grant.purpose())||!access.permits(jwt,facts,coordinate.action()))throw denied();
        return new Witness(coordinate,epoch,grant);
    }
}
