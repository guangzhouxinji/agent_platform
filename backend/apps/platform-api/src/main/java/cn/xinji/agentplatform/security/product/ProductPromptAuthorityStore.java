package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.*;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Finite independent scalar primitives, not a maintainer admission or a cross-store coordinator. */
final class ProductPromptAuthorityStore {
    final ProductPromptJdbc jdbc;
    private final Clock clock;
    ProductPromptAuthorityStore(JdbcTemplate db,DataSourceTransactionManager manager,Clock clock){
        jdbc=new ProductPromptJdbc(db,manager);this.clock=Objects.requireNonNull(clock);
    }
    void insertWorkspacePending(Workspace workspace,String intentId,Instant intentUntil){
        jdbc.requireTransaction();uuid(intentId);time(intentUntil);current(workspace.validUntil());current(intentUntil);
        if(!"ACTIVE".equals(workspace.state())||intentUntil.isAfter(workspace.validUntil()))throw invalid();
        jdbc.db.update("INSERT INTO idp_workspace(workspace_id,organization,scope_version,state,policy_ref,policy_sha,registry_sha,valid_until,"
                        + "control_state,registry_intent_id,expected_registry_sha,intent_until) VALUES(?,?,?,?,?,?,?,?,'PENDING',?,?,?)",
                workspace.id(),workspace.organization(),workspace.scopeVersion(),workspace.state(),workspace.policyRef(),workspace.policySha(),
                workspace.registrySha(),ts(workspace.validUntil()),intentId,workspace.registrySha(),ts(intentUntil));
    }
    void completeWorkspace(Workspace workspace,String intentId){
        jdbc.requireTransaction();uuid(intentId);current(workspace.validUntil());
        if(jdbc.db.update("UPDATE idp_workspace SET control_state='READY',registry_intent_id=NULL WHERE workspace_id=? AND organization=? "
                        + "AND scope_version=? AND state=? AND policy_ref=? AND policy_sha=? AND registry_sha=? AND valid_until=? "
                        + "AND control_state='PENDING' AND registry_intent_id=? AND expected_registry_sha=? AND intent_until>?",
                workspace.id(),workspace.organization(),workspace.scopeVersion(),workspace.state(),workspace.policyRef(),workspace.policySha(),workspace.registrySha(),
                ts(workspace.validUntil()),intentId,workspace.registrySha(),ts(clock.instant()))!=1)throw conflict();
    }
    void requireWorkspace(Workspace expected){
        current(expected.validUntil());
        Long count=jdbc.db.queryForObject("SELECT COUNT(*) FROM idp_workspace WHERE workspace_id=? AND organization=? AND scope_version=? "
                        + "AND state='ACTIVE' AND policy_ref=? AND policy_sha=? AND registry_sha=? AND valid_until=? "
                        + "AND control_state='READY' AND registry_intent_id IS NULL AND expected_registry_sha=registry_sha",
                Long.class,expected.id(),expected.organization(),expected.scopeVersion(),expected.policyRef(),expected.policySha(),expected.registrySha(),ts(expected.validUntil()));
        if(count!=1||!"ACTIVE".equals(expected.state()))throw unavailable();
    }
    Control control(String promptId){
        uuid(promptId);
        var rows=jdbc.db.query("SELECT prompt_id,workspace_id,kind,generation,head_revision_id,scalar_sha,state,intent_id,operation,key_sha,request_sha,"
                        + "before_sha,expected_after_sha,target_generation,target_revision_id,intent_until,container_until,control_until,cleanup_request_id "
                        + "FROM idp_prompt_control WHERE prompt_id=?",
                (rs,n)->new Control(rs.getString(1),rs.getString(2),PromptRevision.Kind.valueOf(rs.getString(3)),rs.getLong(4),rs.getString(5),
                        rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)==null?null:Operation.valueOf(rs.getString(9)),rs.getString(10),
                        rs.getString(11),rs.getString(12),rs.getString(13),rs.getObject(14)==null?null:rs.getLong(14),rs.getString(15),
                        instant(rs,16),instant(rs,17),instant(rs,18),rs.getString(19)),promptId);
        if(rows.size()>1)throw unavailable();return rows.isEmpty()?null:rows.getFirst();
    }
    void reserve(Pending pending){
        jdbc.requireTransaction();current(pending.intentUntil());current(pending.controlUntil());
        var workspace=jdbc.db.query("SELECT workspace_id,organization,scope_version,state,policy_ref,policy_sha,registry_sha,valid_until "
                        + "FROM idp_workspace WHERE workspace_id=? AND control_state='READY'",
                (rs,n)->new Workspace(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),instant(rs,8)),pending.workspaceId());
        if(workspace.size()!=1)throw unavailable();requireWorkspace(workspace.getFirst());
        if(pending.controlUntil().isAfter(workspace.getFirst().validUntil()))throw invalid();
        if(pending.expectedGeneration()==0){
            jdbc.db.update("INSERT INTO idp_prompt_control(prompt_id,workspace_id,kind,generation,head_revision_id,scalar_sha,state,intent_id,operation,"
                            + "key_sha,request_sha,before_sha,expected_after_sha,target_generation,target_revision_id,intent_until,container_until,control_until,cleanup_request_id) "
                            + "VALUES(?,?,?,0,NULL,?,'PENDING',?,?,?,?,?,?,1,?,?,?,?,NULL)",pending.promptId(),pending.workspaceId(),pending.kind().name(),pending.beforeSha(),
                    pending.intentId(),pending.operation().name(),pending.keySha(),pending.requestSha(),pending.beforeSha(),pending.afterSha(),pending.targetRevisionId(),
                    ts(pending.intentUntil()),ts(pending.containerUntil()),ts(pending.controlUntil()));
        }else if(jdbc.db.update("UPDATE idp_prompt_control SET state='PENDING',intent_id=?,operation=?,key_sha=?,request_sha=?,before_sha=?,expected_after_sha=?,"
                        + "target_generation=?,target_revision_id=?,intent_until=?,container_until=?,control_until=? "
                        + "WHERE prompt_id=? AND workspace_id=? AND kind=? AND state='READY' AND intent_id IS NULL AND cleanup_request_id IS NULL AND generation=? AND head_revision_id=? AND scalar_sha=?",
                pending.intentId(),pending.operation().name(),pending.keySha(),pending.requestSha(),pending.beforeSha(),pending.afterSha(),pending.expectedGeneration()+1,
                pending.targetRevisionId(),ts(pending.intentUntil()),ts(pending.containerUntil()),ts(pending.controlUntil()),pending.promptId(),pending.workspaceId(),
                pending.kind().name(),pending.expectedGeneration(),pending.expectedRevisionId(),pending.beforeSha())!=1)throw conflict();
    }
    void complete(Pending pending,Head committed,Header header,Receipt receipt){
        jdbc.requireTransaction();current(pending.intentUntil());current(header.auditUntil());
        if(!committed.promptId().equals(pending.promptId())||!committed.workspaceId().equals(pending.workspaceId())||committed.kind()!=pending.kind()
                ||committed.generation()!=pending.expectedGeneration()+1||!committed.revisionId().equals(pending.targetRevisionId())
                ||!committed.scalarSha().equals(pending.afterSha())||!committed.containerUntil().equals(pending.containerUntil())
                ||!committed.controlUntil().equals(pending.controlUntil())||!header.promptId().equals(committed.promptId())
                ||!header.revisionId().equals(committed.revisionId())||header.number()!=committed.generation()
                ||!"PRESENT".equals(header.bodyState())||!receipt.promptId().equals(header.promptId())||!receipt.revisionId().equals(header.revisionId())
                ||!receipt.workspaceId().equals(committed.workspaceId())||receipt.operation()!=pending.operation()||receipt.generation()!=header.number()
                ||!receipt.keySha().equals(pending.keySha())||!receipt.requestSha().equals(pending.requestSha())
                ||!receipt.contentSha().equals(header.contentSha())||!receipt.submittedAt().equals(header.submittedAt())
                ||!receipt.auditUntil().equals(header.auditUntil()))throw conflict();
        try{
            jdbc.db.update("INSERT INTO idp_revision_witness(revision_id,prompt_id,revision_number,record_sha,content_sha,submitted_at,body_until,audit_until,body_state,"
                            + "result_sha,key_sha,request_sha,issuer,subject_id) VALUES(?,?,?,?,?,?,?,?,'PRESENT',?,?,?,?,?)",
                    header.revisionId(),header.promptId(),header.number(),header.recordSha(),header.contentSha(),ts(header.submittedAt()),ts(header.bodyUntil()),ts(header.auditUntil()),
                    receipt.resultSha(),receipt.keySha(),receipt.requestSha(),receipt.issuer(),receipt.subject());
            if(jdbc.db.update("UPDATE idp_prompt_control SET state='READY',generation=?,head_revision_id=?,scalar_sha=?,intent_id=NULL,operation=NULL,key_sha=NULL,request_sha=NULL,"
                            + "before_sha=NULL,expected_after_sha=NULL,target_generation=NULL,target_revision_id=NULL,intent_until=NULL "
                            + "WHERE prompt_id=? AND workspace_id=? AND kind=? AND state='PENDING' AND cleanup_request_id IS NULL AND intent_id=? AND generation=? "
                            + "AND scalar_sha=? AND before_sha=? AND expected_after_sha=? AND target_generation=? AND target_revision_id=? "
                            + "AND operation=? AND key_sha=? AND request_sha=? AND intent_until=? AND container_until=? AND control_until=?",
                    committed.generation(),committed.revisionId(),committed.scalarSha(),pending.promptId(),pending.workspaceId(),pending.kind().name(),pending.intentId(),
                    pending.expectedGeneration(),pending.beforeSha(),pending.beforeSha(),pending.afterSha(),committed.generation(),header.revisionId(),pending.operation().name(),
                    pending.keySha(),pending.requestSha(),ts(pending.intentUntil()),ts(pending.containerUntil()),ts(pending.controlUntil()))!=1)throw conflict();
        }catch(RuntimeException failed){jdbc.markRollbackOnly();throw failed;}
    }
    void requireReady(Workspace workspace,Head head,Header header){
        requireReady(workspace,head);current(header.bodyUntil());current(header.auditUntil());
        Long count=jdbc.db.queryForObject("SELECT COUNT(*) FROM idp_revision_witness WHERE revision_id=? AND prompt_id=? AND revision_number=? "
                        + "AND record_sha=? AND content_sha=? AND submitted_at=? AND body_until=? AND audit_until=? AND body_state='PRESENT'",
                Long.class,header.revisionId(),header.promptId(),header.number(),header.recordSha(),header.contentSha(),ts(header.submittedAt()),ts(header.bodyUntil()),ts(header.auditUntil()));
        if(count!=1||!"PRESENT".equals(header.bodyState()))throw unavailable();
        requireReady(workspace,head);
    }
    void requireReady(Workspace workspace,Head head){
        requireWorkspace(workspace);current(head.controlUntil());
        Control control=control(head.promptId());
        if(control==null||!"READY".equals(control.state())||control.cleanupRequestId()!=null||control.intentId()!=null||!control.workspaceId().equals(workspace.id())
                ||!head.workspaceId().equals(workspace.id())||control.kind()!=head.kind()||control.generation()!=head.generation()
                ||!Objects.equals(control.revisionId(),head.revisionId())||!control.scalarSha().equals(head.scalarSha())
                ||!control.containerUntil().equals(head.containerUntil())||!control.controlUntil().equals(head.controlUntil()))throw unavailable();
        if(!control.equals(control(head.promptId())))throw unavailable();
    }
    private void current(Instant until){if(until==null||!clock.instant().isBefore(until))throw unavailable();}
}
