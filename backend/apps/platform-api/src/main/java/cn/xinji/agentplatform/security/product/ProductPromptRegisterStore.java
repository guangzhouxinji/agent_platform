package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.*;
import static cn.xinji.agentplatform.security.product.ProductPromptRegisterRecords.*;
import cn.xinji.agentplatform.security.product.ProductPromptRegisterRecords.Receipt;
import cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.Lease;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact transaction primitives. No proof factory, external admission, retry or schema installer. */
final class ProductPromptRegisterStore {
    private static final String RECEIPT_COLUMNS="request_id,receipt_id,workspace_id,operation,subject_id,operator_organization,"
            +"maintainer_version,certificate_sha,key_sha,request_sha,workspace_sha,result_sha,prepared_at,receipt_until";
    private static final String REQUEST_COLUMNS=RECEIPT_COLUMNS+",organization,scope_version,workspace_state,policy_ref,policy_sha,"
            +"registry_sha,valid_until,primary_database_sha,authority_database_sha,approval_ref,approval_sha,approved_receipt_until,intent_until,prepared_sha,state";
    private static final String KEY_WHERE=" WHERE workspace_id=? AND subject_id=? AND certificate_sha=? AND operation='REGISTER' AND key_sha=?";
    private final ProductPromptStore main;
    private final ProductPromptAuthorityStore authority;
    private final ProductComparisonJson json;
    private final Clock clock;

    ProductPromptRegisterStore(ProductPromptStore main,ProductPromptAuthorityStore authority,ObjectMapper mapper,Clock clock){
        this.main=Objects.requireNonNull(main);this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);
        main.jdbc.requireSeparate(authority.jdbc);
        if(!(main.jdbc.db instanceof ProductPromptStatementJdbcTemplate)||!(authority.jdbc.db instanceof ProductPromptStatementJdbcTemplate))throw invalid();
        json=new ProductComparisonJson(mapper.copy().registerModule(new JavaTimeModule()));
    }
    Key key(Lease lease,String rawKey){return ProductPromptRegisterRecords.key(lease,rawKey);}
    String requestSha(Lease lease){return ProductPromptRegisterRecords.requestSha(lease,json);}
    Request prepare(Lease lease,String rawKey){
        lease.requireCurrent();var value=ProductPromptRegisterRecords.prepare(lease,rawKey,clock.instant().truncatedTo(ChronoUnit.MILLIS),json);
        requireApproved(lease,value);pending(value);return value;
    }
    Request find(Lease lease,Key key){
        requireAuthorityTransaction(lease);bound(lease,key);
        var deadlines=authority.jdbc.db.query("SELECT receipt_until FROM idp_workspace_register_request WHERE workspace_id=?",
                (rs,n)->instant(rs,1),key.workspaceId());
        if(deadlines.size()>1)throw unavailable();
        if(deadlines.isEmpty()){
            if(count(authority.jdbc.db,"SELECT COUNT(*) FROM idp_workspace WHERE workspace_id=?",key.workspaceId())!=0)throw unavailable();
            return null;
        }
        current(deadlines.getFirst());
        var rows=authority.jdbc.db.query("SELECT "+REQUEST_COLUMNS+" FROM idp_workspace_register_request"+KEY_WHERE+" AND receipt_until>?",
                (rs,n)->readRequest(rs),key.workspaceId(),key.subject(),key.certificateSha(),key.keySha(),ts(clock.instant()));
        current(deadlines.getFirst());
        if(rows.isEmpty())throw new ProductPromptRegisterConflict();if(rows.size()!=1)throw unavailable();
        var result=rows.getFirst();current(result.receiptUntil());
        if(!deadlines.getFirst().equals(result.receiptUntil())||!result.key().equals(key))throw unavailable();
        result.requireDigest(json);requireAuthorityWorkspace(result);current(result.receiptUntil());return result;
    }
    void requireMainAbsent(Lease lease,Key key){
        requireMainTransaction(lease);bound(lease,key);
        var workspaces=main.jdbc.db.query("SELECT valid_until FROM conp_workspace WHERE workspace_id=? FOR UPDATE",(rs,n)->instant(rs,1),key.workspaceId());
        var receipts=main.jdbc.db.query("SELECT receipt_until FROM conp_workspace_register_receipt WHERE workspace_id=? FOR UPDATE",(rs,n)->instant(rs,1),key.workspaceId());
        if(workspaces.size()>1||receipts.size()>1)throw unavailable();
        workspaces.forEach(this::current);receipts.forEach(this::current);
        if(!workspaces.isEmpty()||!receipts.isEmpty()){
            if(workspaces.isEmpty()||receipts.isEmpty())throw unavailable();throw new ProductPromptRegisterConflict();
        }
        organization(lease.approval().target());
    }
    Request reserve(Lease lease,Request request){
        requireAuthorityTransaction(lease);
        try{
            requireApproved(lease,request);pending(request);
            if(!"PENDING".equals(request.state()))throw invalid();
            var existing=find(lease,request.key());if(existing!=null)throw new ProductPromptRegisterConflict();
            authority.insertWorkspacePending(request.target(),request.requestId(),request.intentUntil());
            insertRow(authority.jdbc.db,"idp_workspace_register_request",request.columns());
            var stored=find(lease,request.key());if(!request.equals(stored))throw unavailable();
            pending(request);return stored;
        }catch(RuntimeException failure){authority.jdbc.markRollbackOnly();throw failure;}
    }
    void insert(Lease lease,Request request){
        requireMainTransaction(lease);
        try{
            requireApproved(lease,request);pending(request);
            if(!"PENDING".equals(request.state()))throw invalid();requireMainAbsent(lease,request.key());
            main.insertWorkspace(request.target());insertRow(main.jdbc.db,"conp_workspace_register_receipt",request.receipt().columns());
            if(observeMain(lease,request)!=ProductPromptRegisterReconciliation.State.EXACT_AFTER)throw unavailable();pending(request);
        }catch(RuntimeException failure){main.jdbc.markRollbackOnly();throw failure;}
    }
    Receipt complete(Lease lease,Request request,ProductPromptRegisterReconciliation.ExactAfter proof){
        requireAuthorityTransaction(lease);
        try{
            requireApproved(lease,request);Objects.requireNonNull(proof).consume(this,lease,request);
            var deadlines=authority.jdbc.db.query("SELECT receipt_until FROM idp_workspace_register_request WHERE workspace_id=? FOR UPDATE",
                    (rs,n)->instant(rs,1),request.target().id());
            if(deadlines.size()!=1)throw unavailable();current(deadlines.getFirst());
            var workspaceDeadlines=authority.jdbc.db.query("SELECT valid_until FROM idp_workspace WHERE workspace_id=? FOR UPDATE",
                    (rs,n)->instant(rs,1),request.target().id());
            if(workspaceDeadlines.size()!=1)throw unavailable();current(workspaceDeadlines.getFirst());
            var stored=find(lease,request.key());if(!request.equals(stored))throw unavailable();
            if("READY".equals(stored.state()))return stored.receipt();
            pending(stored);authority.completeWorkspace(stored.target(),stored.requestId());
            if(authority.jdbc.db.update("UPDATE idp_workspace_register_request SET state='READY' WHERE request_id=? AND receipt_id=? "
                            +"AND workspace_id=? AND prepared_sha=? AND state='PENDING' AND receipt_until>? AND intent_until>?",
                    stored.requestId(),stored.receiptId(),stored.target().id(),stored.preparedSha(),ts(clock.instant()),ts(clock.instant()))!=1)throw unavailable();
            var ready=find(lease,request.key());if(!stored.ready().equals(ready))throw unavailable();
            pending(stored);requireApproved(lease,ready);return ready.receipt();
        }catch(RuntimeException failure){authority.jdbc.markRollbackOnly();throw failure;}
    }
    ProductPromptRegisterReconciliation.State observeMain(Lease lease,Request request){
        requireMainTransaction(lease);requireApproved(lease,request);
        var workspaceDeadlines=main.jdbc.db.query("SELECT valid_until FROM conp_workspace WHERE workspace_id=? FOR UPDATE",
                (rs,n)->instant(rs,1),request.target().id());
        var receiptDeadlines=main.jdbc.db.query("SELECT receipt_until FROM conp_workspace_register_receipt WHERE workspace_id=? OR request_id=? OR receipt_id=?",
                (rs,n)->instant(rs,1),request.target().id(),request.requestId(),request.receiptId());
        if(workspaceDeadlines.size()>1||receiptDeadlines.size()>1)return ProductPromptRegisterReconciliation.State.UNKNOWN;
        workspaceDeadlines.forEach(this::current);receiptDeadlines.forEach(this::current);organization(request.target());
        if(workspaceDeadlines.isEmpty()&&receiptDeadlines.isEmpty())return "PENDING".equals(request.state())
                ?ProductPromptRegisterReconciliation.State.ALL_BEFORE:ProductPromptRegisterReconciliation.State.UNKNOWN;
        if(workspaceDeadlines.isEmpty()||receiptDeadlines.isEmpty())return ProductPromptRegisterReconciliation.State.UNKNOWN;
        var workspaces=main.jdbc.db.query("SELECT workspace_id,organization,scope_version,state,policy_ref,policy_sha,registry_sha,valid_until "
                        +"FROM conp_workspace WHERE workspace_id=? AND valid_until>?",(rs,n)->workspace(rs),request.target().id(),ts(clock.instant()));
        var receipts=main.jdbc.db.query("SELECT "+RECEIPT_COLUMNS+" FROM conp_workspace_register_receipt WHERE workspace_id=? AND receipt_until>?",
                (rs,n)->readReceipt(rs),request.target().id(),ts(clock.instant()));
        current(request.receiptUntil());
        if(workspaces.size()!=1||receipts.size()!=1||!request.target().equals(workspaces.getFirst())||!request.receipt().equals(receipts.getFirst()))
            return ProductPromptRegisterReconciliation.State.UNKNOWN;
        return ProductPromptRegisterReconciliation.State.EXACT_AFTER;
    }
    void requireApproved(Lease lease,Request request){
        lease.requireCurrent();var approved=lease.approval();approved.requireCurrent();request.requireDigest(json);
        if(!request.target().equals(approved.target())||!request.evidence().equals(approved.evidence())
                ||!approved.approvedReceiptUntil().equals(request.evidence().approvedReceiptUntil())
                ||request.receiptUntil().isAfter(approved.expiresAt())||request.preparedAt().isAfter(clock.instant()))throw unavailable();
        current(request.receiptUntil());current(request.target().validUntil());
    }
    void requireAuthorityTransaction(Lease lease){
        lease.requireAuthorityTransaction(authority.jdbc.source);authority.jdbc.requireTransaction();
        if(TransactionSynchronizationManager.hasResource(main.jdbc.source))throw unavailable();
    }
    private void requireMainTransaction(Lease lease){
        lease.requireMainTransaction(main.jdbc.source);main.jdbc.requireTransaction();
        if(TransactionSynchronizationManager.hasResource(authority.jdbc.source))throw unavailable();
    }
    private void bound(Lease lease,Key key){
        Objects.requireNonNull(key);var approved=lease.approval();var evidence=approved.evidence();
        if(!approved.target().id().equals(key.workspaceId())||!evidence.operatorSubject().equals(key.subject())
                ||!evidence.certificateSha().equals(key.certificateSha()))throw unavailable();
    }
    private void requireAuthorityWorkspace(Request request){
        var deadlines=authority.jdbc.db.query("SELECT valid_until FROM idp_workspace WHERE workspace_id=?",(rs,n)->instant(rs,1),request.target().id());
        if(deadlines.size()!=1)throw unavailable();current(deadlines.getFirst());
        var rows=authority.jdbc.db.query("SELECT workspace_id,organization,scope_version,state,policy_ref,policy_sha,registry_sha,valid_until,"
                        +"control_state,registry_intent_id,expected_registry_sha,intent_until FROM idp_workspace WHERE workspace_id=? AND valid_until>?",
                (rs,n)->new Independent(workspace(rs),rs.getString("control_state"),rs.getString("registry_intent_id"),
                        rs.getString("expected_registry_sha"),instant(rs,12)),request.target().id(),ts(clock.instant()));
        if(rows.size()!=1)throw unavailable();var value=rows.getFirst();
        if(!value.workspace().equals(request.target())||!value.state().equals(request.state())
                ||!Objects.equals(value.intent(),"PENDING".equals(request.state())?request.requestId():null)
                ||!value.registrySha().equals(request.target().registrySha())||!value.intentUntil().equals(request.intentUntil()))throw unavailable();
    }
    private void organization(Workspace target){
        if(main.jdbc.db.query("SELECT organization_id FROM con3_organization WHERE organization_id=? AND status='ACTIVE' FOR UPDATE",
                (rs,n)->rs.getString(1),target.organization()).size()!=1)throw unavailable();
    }
    private void pending(Request request){current(request.intentUntil());current(request.receiptUntil());}
    private void current(Instant until){if(until==null||!clock.instant().isBefore(until))throw unavailable();}
    private static Workspace workspace(ResultSet rs)throws SQLException{
        return new Workspace(rs.getString("workspace_id"),rs.getString("organization"),rs.getLong("scope_version"),
                rs.getString("state"),rs.getString("policy_ref"),rs.getString("policy_sha"),rs.getString("registry_sha"),rs.getTimestamp("valid_until").toInstant());
    }
    private static Receipt readReceipt(ResultSet rs)throws SQLException{
        if(!OPERATION.equals(rs.getString("operation")))throw unavailable();
        var key=new Key(rs.getString("workspace_id"),rs.getString("subject_id"),rs.getString("certificate_sha"),rs.getString("key_sha"));
        return new Receipt(rs.getString("request_id"),rs.getString("receipt_id"),key,rs.getString("operator_organization"),rs.getLong("maintainer_version"),
                rs.getString("request_sha"),rs.getString("workspace_sha"),rs.getString("result_sha"),rs.getTimestamp("prepared_at").toInstant(),rs.getTimestamp("receipt_until").toInstant());
    }
    private static Request readRequest(ResultSet rs)throws SQLException{
        var target=new Workspace(rs.getString("workspace_id"),rs.getString("organization"),rs.getLong("scope_version"),rs.getString("workspace_state"),
                rs.getString("policy_ref"),rs.getString("policy_sha"),rs.getString("registry_sha"),rs.getTimestamp("valid_until").toInstant());
        var evidence=new Evidence(rs.getString("subject_id"),rs.getString("operator_organization"),rs.getLong("maintainer_version"),rs.getString("certificate_sha"),
                rs.getString("primary_database_sha"),rs.getString("authority_database_sha"),rs.getString("approval_ref"),rs.getString("approval_sha"),rs.getTimestamp("approved_receipt_until").toInstant());
        return new Request(target,evidence,readReceipt(rs),rs.getTimestamp("intent_until").toInstant(),rs.getString("prepared_sha"),rs.getString("state"));
    }
    private static void insertRow(JdbcTemplate db,String table,Map<String,Object> columns){
        if(db.update("INSERT INTO "+table+"("+String.join(",",columns.keySet())+") VALUES("
                        +String.join(",",Collections.nCopies(columns.size(),"?"))+")",
                columns.values().stream().map(value->value instanceof Instant at?ts(at):value).toArray())!=1)throw unavailable();
    }
    private static long count(JdbcTemplate db,String query,Object...args){return db.queryForObject(query,Long.class,args);}
    private record Independent(Workspace workspace,String state,String intent,String registrySha,Instant intentUntil) { }
}
