package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.*;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Internal authority-transaction primitive. It neither authorizes callers nor completes a main write. */
final class ProductPromptCreateRequestStore {
    private final ProductPromptAuthorityStore authority;
    private final ProductComparisonJson json;
    private final Clock clock;
    ProductPromptCreateRequestStore(ProductPromptAuthorityStore authority,ObjectMapper mapper,Clock clock){
        this.authority=Objects.requireNonNull(authority);
        this.json=new ProductComparisonJson(mapper.copy()
                .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true))
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        this.clock=Objects.requireNonNull(clock);
    }
    ProductPromptCreateRequest prepared(Pending pending,PreparedWrite prepared,ProductPromptCreateRequest.Evidence evidence){
        return ProductPromptCreateRequest.prepared(pending,prepared,evidence,json);
    }
    ProductPromptCreateRequest prepared(Pending pending,PreparedWrite prepared,ProductPromptCreateRequest.Evidence evidence,
                                       ProductPromptCreateRequest.CopySource copySource){
        return ProductPromptCreateRequest.prepared(pending,prepared,evidence,copySource,json);
    }
    void requirePrepared(ProductPromptCreateRequest request,PreparedWrite prepared){
        request.requireDigest(json);request.requirePrepared(prepared);
        if(!request.header().recordSha().equals(json.sha(prepared.write().revision())))throw unavailable();
    }
    ProductPromptCreateRequest find(ProductPromptCreateRequest.Key key){
        authority.jdbc.requireTransaction();Objects.requireNonNull(key);
        Object[] coordinates={key.workspaceId(),key.issuer(),key.subject(),key.operation().name(),key.keySha()};
        String where=" WHERE workspace_id=? AND issuer=? AND subject_id=? AND operation=? AND key_sha=?";
        var deadlines=authority.jdbc.db.query("SELECT audit_until FROM idp_prompt_request"+where,
                (rs,n)->instant(rs,1),coordinates);
        if(deadlines.size()>1)throw unavailable();if(deadlines.isEmpty())return null;
        current(deadlines.getFirst());
        String copyColumns=key.operation()==Operation.COPY
                ?",source_workspace_id,source_prompt_id,source_revision_id,source_revision_number,source_record_sha,source_body_until,source_audit_until":"";
        var rows=authority.jdbc.db.query("SELECT request_id,workspace_id,issuer,subject_id,operation,key_sha,prompt_id,kind,target_revision_id,"
                        +"expected_generation,expected_revision_id,target_generation,receipt_id,prepared_format,request_format,request_sha,"
                        +"content_sha,record_sha,result_sha,before_sha,expected_after_sha,prepared_sha,prepared_at,body_until,audit_until,"
                        +"intent_until,container_until,control_until,scope_version,registry_sha,approval_ref,approval_sha,policy_ref,policy_sha,"
                        +"input_permission_ref,input_permission_sha,generation_source_ref,state"+copyColumns+" FROM idp_prompt_request"+where+" AND audit_until>?",
                (rs,n)->read(rs),key.workspaceId(),key.issuer(),key.subject(),key.operation().name(),key.keySha(),ts(clock.instant()));
        if(rows.size()!=1)throw unavailable();var result=rows.getFirst();
        current(result.header().auditUntil());if(!deadlines.getFirst().equals(result.header().auditUntil())||!key.equals(result.key()))throw unavailable();
        result.requireDigest(json);
        if("PENDING".equals(result.state()))requireControl(result);
        else requireReady(result,null);
        current(result.header().auditUntil());return result;
    }
    Receipt complete(ProductPromptCreateReconciliation.ExactAfter proof){
        authority.jdbc.requireTransaction();
        try{
            var verified=Objects.requireNonNull(proof).consume(authority);var original=verified.request();
            current(original.header().auditUntil());current(verified.head().controlUntil());
            var key=original.key();
            var deadlines=authority.jdbc.db.query("SELECT audit_until FROM idp_prompt_request WHERE workspace_id=? AND issuer=? "
                            +"AND subject_id=? AND operation=? AND key_sha=? FOR UPDATE",
                    (rs,n)->instant(rs,1),key.workspaceId(),key.issuer(),key.subject(),key.operation().name(),key.keySha());
            if(deadlines.size()!=1)throw unavailable();current(deadlines.getFirst());
            var controls=authority.jdbc.db.query("SELECT prompt_id FROM idp_prompt_control WHERE prompt_id=? FOR UPDATE",
                    (rs,n)->rs.getString(1),original.pending().promptId());
            if(controls.size()!=1)throw unavailable();
            var request=find(key);if(request==null||!sameMaterial(original,request))throw unavailable();
            requireWorkspace(request);authority.requireWorkspace(verified.workspace());
            if("READY".equals(request.state())){requireReady(request,verified.head());return request.receipt();}
            if(!verified.head().equals(request.after()))throw unavailable();
            insertOrRequireWitness(request);
            var p=request.pending();var h=verified.head();
            var args=new ArrayList<Object>(List.of(h.generation(),h.revisionId(),h.scalarSha(),p.promptId(),p.workspaceId(),p.kind().name(),p.intentId(),p.expectedGeneration()));
            if(p.expectedRevisionId()!=null)args.add(p.expectedRevisionId());
            args.addAll(List.of(p.beforeSha(),p.beforeSha(),p.afterSha(),h.generation(),p.targetRevisionId(),p.operation().name(),p.keySha(),p.requestSha(),ts(p.intentUntil()),ts(p.containerUntil()),ts(p.controlUntil())));
            if(authority.jdbc.db.update("UPDATE idp_prompt_control SET state='READY',generation=?,head_revision_id=?,scalar_sha=?,intent_id=NULL,operation=NULL,"
                            +"key_sha=NULL,request_sha=NULL,before_sha=NULL,expected_after_sha=NULL,target_generation=NULL,target_revision_id=NULL,intent_until=NULL "
                            +"WHERE prompt_id=? AND workspace_id=? AND kind=? AND state='PENDING' AND cleanup_request_id IS NULL AND intent_id=? AND generation=? AND "
                            +(p.expectedRevisionId()==null?"head_revision_id IS NULL ":"head_revision_id=? ")
                            +"AND scalar_sha=? AND before_sha=? AND expected_after_sha=? AND target_generation=? AND target_revision_id=? "
                            +"AND operation=? AND key_sha=? AND request_sha=? AND intent_until=? AND container_until=? AND control_until=?",args.toArray())!=1)throw conflict();
            if(authority.jdbc.db.update("UPDATE idp_prompt_request SET state='READY' WHERE request_id=? AND state='PENDING' AND prepared_sha=? AND audit_until>?",
                    p.intentId(),request.preparedSha(),ts(clock.instant()))!=1)throw conflict();
            current(request.header().auditUntil());current(h.controlUntil());
            var ready=find(key);requireReady(ready,h);return ready.receipt();
        }catch(RuntimeException failure){authority.jdbc.markRollbackOnly();throw failure;}
    }
    ProductPromptCreateRequest reserve(ProductPromptCreateRequest request){
        authority.jdbc.requireTransaction();
        try{
            Objects.requireNonNull(request);request.requireDigest(json);
            if(!"PENDING".equals(request.state()))throw invalid();
            current(request.header().auditUntil());current(request.header().bodyUntil());current(request.pending().intentUntil());
            if(request.header().submittedAt().isAfter(clock.instant()))throw invalid();
            requireWorkspace(request);
            var existing=find(request.key());
            if(existing!=null){
                if(!existing.receipt().requestSha().equals(request.receipt().requestSha()))throw conflict();
                if(!existing.equals(request))throw conflict();
                requireControl(existing);return existing;
            }
            authority.reserve(request.pending());
            var columns=request.columns();
            authority.jdbc.db.update("INSERT INTO idp_prompt_request("+String.join(",",columns.keySet())+") VALUES("
                            +String.join(",",Collections.nCopies(columns.size(),"?"))+")",
                    columns.values().stream().map(value->value instanceof Instant at?ts(at):value).toArray());
            requireControl(request);current(request.header().auditUntil());current(request.header().bodyUntil());current(request.pending().intentUntil());
            return request;
        }catch(RuntimeException failed){authority.jdbc.markRollbackOnly();throw failed;}
    }
    private void requireWorkspace(ProductPromptCreateRequest request){
        var e=request.evidence();
        Long count=authority.jdbc.db.queryForObject("SELECT COUNT(*) FROM idp_workspace WHERE workspace_id=? AND scope_version=? "
                        +"AND state='ACTIVE' AND control_state='READY' AND registry_intent_id IS NULL AND registry_sha=? "
                        +"AND expected_registry_sha=registry_sha AND policy_ref=? AND policy_sha=? AND valid_until>=? AND valid_until>?",
                Long.class,request.pending().workspaceId(),e.scopeVersion(),e.registrySha(),e.policyRef(),e.policySha(),
                ts(request.header().auditUntil()),ts(clock.instant()));
        if(count!=1)throw unavailable();
    }
    private void requireControl(ProductPromptCreateRequest request){
        if(!request.matchesPending(authority.control(request.pending().promptId())))throw unavailable();
    }
    private boolean sameMaterial(ProductPromptCreateRequest one,ProductPromptCreateRequest two){
        var left=one.columns();var right=two.columns();left.remove("state");right.remove("state");return left.equals(right);
    }
    private void requireReady(ProductPromptCreateRequest request,Head observed){
        if(request==null||!"READY".equals(request.state()))throw unavailable();
        var p=request.pending();var c=authority.control(p.promptId());
        if(c==null||!"READY".equals(c.state())||c.cleanupRequestId()!=null||c.intentId()!=null||!c.workspaceId().equals(p.workspaceId())||c.kind()!=p.kind()
                ||c.generation()<request.receipt().generation()||c.revisionId()==null||c.controlUntil().isBefore(request.header().auditUntil())
                ||c.operation()!=null||c.keySha()!=null||c.requestSha()!=null||c.beforeSha()!=null||c.afterSha()!=null
                ||c.targetGeneration()!=null||c.targetRevisionId()!=null||c.intentUntil()!=null)throw unavailable();
        current(c.controlUntil());
        if(c.generation()==request.receipt().generation()&&(!c.revisionId().equals(p.targetRevisionId())
                ||!c.containerUntil().equals(p.containerUntil())||!c.controlUntil().equals(p.controlUntil())))throw unavailable();
        if(observed!=null&&(c.generation()!=observed.generation()||!c.revisionId().equals(observed.revisionId())
                ||!c.scalarSha().equals(observed.scalarSha())||!c.containerUntil().equals(observed.containerUntil())
                ||!c.controlUntil().equals(observed.controlUntil())))throw unavailable();
        new ProductPromptBodyClearHistory(authority,clock).requireReadyAuthority(request,c);
    }
    private void insertOrRequireWitness(ProductPromptCreateRequest request){
        var h=request.header();var r=request.receipt();
        var rows=authority.jdbc.db.query("SELECT audit_until FROM idp_revision_witness WHERE revision_id=? OR (prompt_id=? AND revision_number=?)",
                (rs,n)->instant(rs,1),h.revisionId(),h.promptId(),h.number());
        if(!rows.isEmpty()){for(var deadline:rows)current(deadline);if(rows.size()!=1)throw unavailable();requireWitness(request);return;}
        authority.jdbc.db.update("INSERT INTO idp_revision_witness(revision_id,prompt_id,revision_number,record_sha,content_sha,submitted_at,body_until,audit_until,"
                        +"body_state,result_sha,key_sha,request_sha,issuer,subject_id) VALUES(?,?,?,?,?,?,?,?,'PRESENT',?,?,?,?,?)",
                h.revisionId(),h.promptId(),h.number(),h.recordSha(),h.contentSha(),ts(h.submittedAt()),ts(h.bodyUntil()),ts(h.auditUntil()),
                r.resultSha(),r.keySha(),r.requestSha(),r.issuer(),r.subject());
    }
    private void requireWitness(ProductPromptCreateRequest request){
        var h=request.header();var r=request.receipt();current(h.auditUntil());
        Long count=authority.jdbc.db.queryForObject("SELECT COUNT(*) FROM idp_revision_witness WHERE revision_id=? AND prompt_id=? AND revision_number=? "
                        +"AND record_sha=? AND content_sha=? AND submitted_at=? AND body_until=? AND audit_until=? AND body_state='PRESENT' "
                        +"AND result_sha=? AND key_sha=? AND request_sha=? AND issuer=? AND subject_id=?",
                Long.class,h.revisionId(),h.promptId(),h.number(),h.recordSha(),h.contentSha(),ts(h.submittedAt()),ts(h.bodyUntil()),ts(h.auditUntil()),
                r.resultSha(),r.keySha(),r.requestSha(),r.issuer(),r.subject());
        if(count!=1)throw unavailable();
    }
    private ProductPromptCreateRequest read(ResultSet rs)throws SQLException{
        try{
            var p=new Pending(rs.getString("prompt_id"),rs.getString("workspace_id"),PromptRevision.Kind.valueOf(rs.getString("kind")),
                    rs.getLong("expected_generation"),rs.getString("expected_revision_id"),rs.getString("before_sha"),rs.getString("expected_after_sha"),
                    rs.getString("target_revision_id"),rs.getString("request_id"),Operation.valueOf(rs.getString("operation")),rs.getString("key_sha"),
                    rs.getString("request_sha"),at(rs,"intent_until"),at(rs,"container_until"),at(rs,"control_until"));
            var r=new Receipt(rs.getString("receipt_id"),p.workspaceId(),rs.getString("issuer"),rs.getString("subject_id"),p.operation(),p.keySha(),
                    p.requestSha(),p.promptId(),p.targetRevisionId(),rs.getLong("target_generation"),rs.getString("content_sha"),rs.getString("result_sha"),
                    at(rs,"prepared_at"),at(rs,"audit_until"));
            var h=new Header(p.targetRevisionId(),p.promptId(),r.generation(),r.contentSha(),rs.getString("record_sha"),r.submittedAt(),
                    at(rs,"body_until"),r.auditUntil(),"PRESENT");
            var e=new ProductPromptCreateRequest.Evidence(rs.getLong("scope_version"),rs.getString("registry_sha"),rs.getString("approval_ref"),
                    rs.getString("approval_sha"),rs.getString("policy_ref"),rs.getString("policy_sha"),rs.getString("input_permission_ref"),
                    rs.getString("input_permission_sha"),rs.getString("generation_source_ref"));
            ProductPromptCreateRequest.CopySource source=null;
            if(p.operation()==Operation.COPY)source=new ProductPromptCreateRequest.CopySource(rs.getString("source_workspace_id"),
                    rs.getString("source_prompt_id"),rs.getString("source_revision_id"),rs.getLong("source_revision_number"),
                    rs.getString("source_record_sha"),at(rs,"source_body_until"),at(rs,"source_audit_until"));
            return new ProductPromptCreateRequest(p,r,h,e,rs.getString("prepared_format"),rs.getString("request_format"),rs.getString("state"),rs.getString("prepared_sha"),source);
        }catch(RuntimeException corrupt){throw unavailable();}
    }
    private static Instant at(ResultSet rs,String column)throws SQLException{
        var value=rs.getTimestamp(column);return value==null?null:value.toInstant();
    }
    private void current(Instant until){if(until==null||!clock.instant().isBefore(until))throw unavailable();}
}
