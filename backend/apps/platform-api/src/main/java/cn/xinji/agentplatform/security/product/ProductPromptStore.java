package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.*;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.TemplateRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Internal main-store primitives. No method authenticates an actor or installs a workspace automatically. */
final class ProductPromptStore {
    private static final String ZERO = "0".repeat(64);
    final ProductPromptJdbc jdbc;
    private final ProductComparisonJson json;
    private final Clock clock;
    ProductPromptStore(JdbcTemplate db, DataSourceTransactionManager manager, ObjectMapper mapper, Clock clock) {
        jdbc = new ProductPromptJdbc(db, manager); this.json = new ProductComparisonJson(mapper.copy()
                .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true))
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        this.clock = Objects.requireNonNull(clock);
    }
    void insertWorkspace(Workspace workspace) {
        jdbc.requireTransaction(); requireWorkspace(workspace);
        jdbc.db.update("INSERT INTO conp_workspace(workspace_id,organization,scope_version,state,policy_ref,policy_sha,registry_sha,valid_until) "
                        + "VALUES(?,?,?,?,?,?,?,?)", workspace.id(),workspace.organization(),workspace.scopeVersion(),workspace.state(),
                workspace.policyRef(),workspace.policySha(),workspace.registrySha(),ts(workspace.validUntil()));
    }
    Workspace workspace(String id, boolean locking) {
        jdbc.requireTransaction(); uuid(id);
        Workspace result = one(jdbc.db.query("SELECT workspace_id,organization,scope_version,state,policy_ref,policy_sha,registry_sha,valid_until "
                        + "FROM conp_workspace WHERE workspace_id=?"+(locking?" FOR UPDATE":""),
                (rs,n)->new Workspace(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5),
                        rs.getString(6),rs.getString(7),instant(rs,8)),id));
        requireWorkspace(result); return result;
    }
    Head head(String id, boolean locking) {
        jdbc.requireTransaction(); uuid(id);
        var rows = jdbc.db.query("SELECT prompt_id,workspace_id,kind,head_revision_id,generation,scalar_sha,container_until,control_until "
                        + "FROM conp_prompt WHERE prompt_id=?"+(locking?" FOR UPDATE":""),
                (rs,n)->new Head(rs.getString(1),rs.getString(2),PromptRevision.Kind.valueOf(rs.getString(3)),rs.getString(4),
                        rs.getLong(5),rs.getString(6),instant(rs,7),instant(rs,8)),id);
        if(rows.size()>1)throw unavailable(); return rows.isEmpty()?null:rows.getFirst();
    }
    Receipt receipt(String workspace, String issuer, String subject, Operation operation, String keySha) {
        jdbc.requireTransaction(); uuid(workspace); text(issuer); text(subject); sha(keySha);
        var deadlines=jdbc.db.query("SELECT audit_until FROM conp_receipt WHERE workspace_id=? AND issuer=? AND subject_id=? "
                        + "AND operation=? AND key_sha=?",(rs,n)->instant(rs,1),workspace,issuer,subject,operation.name(),keySha);
        if(deadlines.size()>1)throw unavailable();
        if(deadlines.isEmpty())return null;
        requireAudit(deadlines.getFirst());
        var rows = jdbc.db.query("SELECT receipt_id,workspace_id,issuer,subject_id,operation,key_sha,request_sha,prompt_id,revision_id,generation,"
                        + "content_sha,result_sha,submitted_at,audit_until FROM conp_receipt "
                        + "WHERE workspace_id=? AND issuer=? AND subject_id=? AND operation=? AND key_sha=? AND audit_until>?",
                RECEIPT,workspace,issuer,subject,operation.name(),keySha,ts(clock.instant()));
        if(rows.size()!=1)throw unavailable();
        Receipt result=rows.getFirst(); requireAudit(result.auditUntil()); return result;
    }
    Receipt append(Write write) {
        jdbc.requireTransaction();
        try {
            workspace(write.workspaceId(),true);
            Receipt existing=receipt(write.workspaceId(),write.issuer(),write.subject(),write.operation(),write.keySha());
            if(existing!=null){if(!existing.requestSha().equals(write.requestSha()))throw conflict();return existing;}
            return appendWithin(prepare(write));
        }
        catch (RuntimeException failed) { jdbc.markRollbackOnly(); throw failed; }
    }
    Receipt append(PreparedWrite prepared) {
        jdbc.requireTransaction();
        try { return appendWithin(prepared); }
        catch (RuntimeException failed) { jdbc.markRollbackOnly(); throw failed; }
    }
    PreparedWrite prepare(Write write) {
        return prepare(write, UUID.randomUUID().toString());
    }
    PreparedWrite prepare(Write write, String receiptId) {
        uuid(receiptId);
        jdbc.requireTransaction(); Workspace workspace=workspace(write.workspaceId(),true);
        if(receipt(write.workspaceId(),write.issuer(),write.subject(),write.operation(),write.keySha())!=null)throw conflict();
        var revision=write.revision(); var ref=revision.reference();
        new TemplateRenderer().validateSyntax(revision.body());
        Instant at=revision.provenance().modifiedAt();
        if(at.isAfter(clock.instant())||!clock.instant().isBefore(write.bodyUntil())
                ||write.auditUntil().isAfter(workspace.validUntil())
                ||!workspace.policyRef().equals(revision.provenance().retentionPolicyRef()))throw unavailable();
        Head previous=head(ref.promptId(),true);
        if(previous==null&&write.expectedGeneration()!=0||previous!=null&&(previous.generation()!=write.expectedGeneration()
                ||!previous.workspaceId().equals(workspace.id())||previous.kind()!=revision.kind()))throw conflict();
        if(previous!=null)requireClosure(previous);
        Instant container=previous==null?write.bodyUntil():maximum(previous.containerUntil(),write.bodyUntil());
        Instant control=previous==null?write.auditUntil():maximum(previous.controlUntil(),write.auditUntil());
        String id=receiptId;
        String resultSha=json.sha(List.of(id,workspace.id(),write.issuer(),write.subject(),write.operation(),write.keySha(),write.requestSha(),
                ref.promptId(),ref.revisionId(),ref.version(),ref.contentSha256(),at,write.auditUntil()));
        Receipt result=new Receipt(id,workspace.id(),write.issuer(),write.subject(),write.operation(),write.keySha(),write.requestSha(),
                ref.promptId(),ref.revisionId(),ref.version(),ref.contentSha256(),resultSha,at,write.auditUntil());
        Header header=new Header(ref.revisionId(),ref.promptId(),ref.version(),ref.contentSha256(),json.sha(revision),at,write.bodyUntil(),write.auditUntil(),"PRESENT");
        Head target=new Head(ref.promptId(),workspace.id(),revision.kind(),ref.revisionId(),ref.version(),ZERO,container,control);
        PreparedWrite draft=new PreparedWrite(write,previous,target,header,result);
        Head after=new Head(target.promptId(),target.workspaceId(),target.kind(),target.revisionId(),target.generation(),scalarSha(ref.promptId(),draft),container,control);
        return new PreparedWrite(write,previous,after,header,result);
    }
    private Receipt appendWithin(PreparedWrite prepared) {
        Write write=prepared.write();Workspace workspace=workspace(write.workspaceId(),true);
        var revision=write.revision();var ref=revision.reference();
        Head previous=head(ref.promptId(),true);
        if(!Objects.equals(previous,prepared.before()))throw conflict();
        if(previous!=null)requireClosure(previous);
        if(receipt(write.workspaceId(),write.issuer(),write.subject(),write.operation(),write.keySha())!=null)throw conflict();
        Instant at=revision.provenance().modifiedAt(),container=prepared.after().containerUntil(),control=prepared.after().controlUntil();
        String encoded=json.canonical(revision),recordSha=json.sha(revision);
        Receipt result=prepared.receipt();String id=result.id(),resultSha=result.resultSha();
        if(!prepared.header().recordSha().equals(recordSha)||!prepared.after().scalarSha().equals(scalarSha(ref.promptId(),prepared)))throw unavailable();
        if(previous==null)jdbc.db.update("INSERT INTO conp_prompt(prompt_id,workspace_id,kind,head_revision_id,generation,scalar_sha,container_until,control_until) "
                        + "VALUES(?,?,?,?,?,?,?,?)",ref.promptId(),workspace.id(),revision.kind().name(),ref.revisionId(),ref.version(),ZERO,ts(container),ts(control));
        else if(jdbc.db.update("UPDATE conp_prompt SET head_revision_id=?,generation=?,scalar_sha=?,container_until=?,control_until=? "
                        + "WHERE prompt_id=? AND workspace_id=? AND generation=? AND head_revision_id=? AND scalar_sha=?",
                ref.revisionId(),ref.version(),ZERO,ts(container),ts(control),ref.promptId(),workspace.id(),previous.generation(),
                previous.revisionId(),previous.scalarSha())!=1)throw conflict();
        jdbc.db.update("INSERT INTO conp_revision(revision_id,prompt_id,revision_number,revision_json,content_sha,record_sha,submitted_at,body_until,audit_until,body_state) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,'PRESENT')",ref.revisionId(),ref.promptId(),ref.version(),encoded,ref.contentSha256(),recordSha,
                ts(at),ts(write.bodyUntil()),ts(write.auditUntil()));
        jdbc.db.update("INSERT INTO conp_event(event_id,prompt_id,revision_id,generation,operation,issuer,subject_id,submitted_at,request_sha,result_sha,audit_until) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,ref.promptId(),ref.revisionId(),ref.version(),write.operation().name(),write.issuer(),write.subject(),
                ts(at),write.requestSha(),resultSha,ts(write.auditUntil()));
        jdbc.db.update("INSERT INTO conp_receipt(receipt_id,workspace_id,issuer,subject_id,operation,key_sha,request_sha,prompt_id,revision_id,generation,"
                        + "content_sha,result_sha,submitted_at,audit_until) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,workspace.id(),write.issuer(),write.subject(),
                write.operation().name(),write.keySha(),write.requestSha(),ref.promptId(),ref.revisionId(),ref.version(),ref.contentSha256(),resultSha,
                ts(at),ts(write.auditUntil()));
        String closure=closureSha(ref.promptId());
        if(!closure.equals(prepared.after().scalarSha()))throw unavailable();
        if(jdbc.db.update("UPDATE conp_prompt SET scalar_sha=? WHERE prompt_id=? AND generation=? AND scalar_sha=?",
                closure,ref.promptId(),ref.version(),ZERO)!=1)throw conflict();
        requireWorkspace(workspace); requireAudit(write.auditUntil());
        if(!clock.instant().isBefore(write.bodyUntil()))throw unavailable();
        requireClosure(head(ref.promptId(),false)); return result;
    }
    Header header(String promptId,String revisionId) {
        jdbc.requireTransaction();uuid(promptId);uuid(revisionId);
        return one(jdbc.db.query("SELECT revision_id,prompt_id,revision_number,content_sha,record_sha,submitted_at,body_until,audit_until,body_state "
                + "FROM conp_revision WHERE prompt_id=? AND revision_id=?",HEADER,promptId,revisionId));
    }
    PromptRevision readRevision(String promptId,String revisionId,ProductPromptAuthorityStore authority) {
        jdbc.requireTransaction();jdbc.requireSeparate(authority.jdbc);
        Head expected=head(promptId,true);if(expected==null)throw unavailable();
        Workspace workspace=workspace(expected.workspaceId(),false);
        Header selected=header(promptId,revisionId);requireBody(selected);
        requireClosure(expected);authority.requireReady(workspace,expected,selected);
        var bodies=jdbc.db.query("SELECT revision_json FROM conp_revision WHERE prompt_id=? AND revision_id=? AND body_state='PRESENT' AND body_until>?",
                (rs,n)->{requireBody(selected);return rs.getString(1);},promptId,revisionId,ts(clock.instant()));
        String raw=one(bodies);if(raw==null)throw unavailable();
        var original=json.stored(raw,com.fasterxml.jackson.databind.JsonNode.class);
        requireOriginalSet(original,"scenarioIds");requireOriginalSet(original,"intentIds");
        if(!json.sha(original).equals(selected.recordSha()))throw unavailable();
        PromptRevision result=json.stored(raw,PromptRevision.class);
        var comparable=((com.fasterxml.jackson.databind.node.ObjectNode)original).deepCopy();
        var scenarios=comparable.putArray("scenarioIds");result.scenarioIds().forEach(scenarios::add);
        var intents=comparable.putArray("intentIds");result.intentIds().forEach(intents::add);
        if(!json.canonical(comparable).equals(json.canonical(result)))throw unavailable();
        if(!result.reference().promptId().equals(promptId)||!result.reference().revisionId().equals(revisionId)
                ||result.reference().version()!=selected.number()||!result.reference().contentSha256().equals(selected.contentSha())
                ||result.kind()!=expected.kind()
                ||!result.provenance().modifiedAt().equals(selected.submittedAt()))throw unavailable();
        if(!expected.equals(head(promptId,false))||!selected.equals(header(promptId,revisionId)))throw unavailable();
        requireWorkspace(workspace);requireBody(selected);requireClosure(expected);authority.requireReady(workspace,expected,selected);return result;
    }
    Page history(String promptId,int page,int size,ProductPromptAuthorityStore authority) {
        jdbc.requireTransaction();uuid(promptId);jdbc.requireSeparate(authority.jdbc);
        if(page<1||!List.of(10,20,50).contains(size))throw invalid();
        Head expected=head(promptId,true);if(expected==null)throw unavailable();
        Workspace workspace=workspace(expected.workspaceId(),false);requireClosure(expected);authority.requireReady(workspace,expected);
        long offset=Math.multiplyExact((long)page-1,size);
        long total=jdbc.db.queryForObject("SELECT COUNT(*) FROM conp_revision WHERE prompt_id=? AND body_until>? AND body_state='PRESENT'",
                Long.class,promptId,ts(clock.instant()));
        var rows=jdbc.db.query("SELECT revision_id,prompt_id,revision_number,content_sha,record_sha,submitted_at,body_until,audit_until,body_state "
                        + "FROM conp_revision WHERE prompt_id=? AND body_until>? AND body_state='PRESENT' "
                        + "ORDER BY submitted_at DESC,revision_id ASC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                HEADER,promptId,ts(clock.instant()),offset,size);
        rows.forEach(row->{requireBody(row);authority.requireReady(workspace,expected,row);});
        requireWorkspace(workspace);requireClosure(expected);authority.requireReady(workspace,expected);return new Page(rows,total);
    }
    String closureSha(String promptId) {
        return scalarSha(promptId,null);
    }
    private String scalarSha(String promptId,PreparedWrite prepared) {
        var snapshot=ProductPromptScalarSnapshot.read(jdbc,json,clock,promptId);
        return (prepared==null?snapshot:snapshot.append(prepared)).sha();
    }
    ProductPromptScalarSnapshot scalarSnapshot(Head expected) {
        jdbc.requireTransaction();Objects.requireNonNull(expected);
        if(!expected.equals(head(expected.promptId(),true)))throw unavailable();requireClosure(expected);
        var snapshot=ProductPromptScalarSnapshot.read(jdbc,json,clock,expected.promptId());
        if(!snapshot.sha().equals(expected.scalarSha()))throw unavailable();return snapshot;
    }
    void requireClosure(Head head) {
        if(head==null||!clock.instant().isBefore(head.controlUntil())||!head.scalarSha().equals(closureSha(head.promptId())))throw unavailable();
        var headers=jdbc.db.query("SELECT revision_id,prompt_id,revision_number,content_sha,record_sha,submitted_at,body_until,audit_until,body_state "
                + "FROM conp_revision WHERE prompt_id=? ORDER BY revision_number",HEADER,head.promptId());
        if(headers.size()!=head.generation()||headers.isEmpty()||!headers.getLast().revisionId().equals(head.revisionId()))throw unavailable();
        for(int i=0;i<headers.size();i++)if(headers.get(i).number()!=i+1L)throw unavailable();
        Long events=jdbc.db.queryForObject("SELECT COUNT(*) FROM conp_event WHERE prompt_id=?",Long.class,head.promptId());
        Long receipts=jdbc.db.queryForObject("SELECT COUNT(*) FROM conp_receipt WHERE prompt_id=?",Long.class,head.promptId());
        Long matched=jdbc.db.queryForObject("SELECT COUNT(*) FROM conp_event e JOIN conp_receipt r ON r.receipt_id=e.event_id "
                + "JOIN conp_revision v ON v.revision_id=e.revision_id WHERE e.prompt_id=? AND r.prompt_id=e.prompt_id AND v.prompt_id=e.prompt_id "
                + "AND r.workspace_id=? AND r.revision_id=e.revision_id AND r.generation=e.generation AND v.revision_number=e.generation "
                + "AND r.operation=e.operation AND r.issuer=e.issuer AND r.subject_id=e.subject_id AND r.request_sha=e.request_sha "
                + "AND r.result_sha=e.result_sha AND r.content_sha=v.content_sha AND r.submitted_at=e.submitted_at "
                + "AND r.submitted_at=v.submitted_at AND r.audit_until=e.audit_until AND r.audit_until=v.audit_until",
                Long.class,head.promptId(),head.workspaceId());
        if(events!=head.generation()||receipts!=head.generation()||matched!=head.generation())throw unavailable();
    }
    private void requireWorkspace(Workspace workspace){if(!"ACTIVE".equals(workspace.state())||!clock.instant().isBefore(workspace.validUntil()))throw unavailable();}
    private static void requireOriginalSet(com.fasterxml.jackson.databind.JsonNode original,String field){
        if(original==null||!original.isObject())throw unavailable();
        var values=original.get(field);if(values==null||!values.isArray())throw unavailable();
        var distinct=new java.util.HashSet<String>();
        for(var value:values)if(!value.isTextual()||!distinct.add(value.textValue()))throw unavailable();
    }
    private void requireAudit(Instant until){if(!clock.instant().isBefore(until))throw unavailable();}
    private void requireBody(Header header){requireAudit(header.auditUntil());if(!"PRESENT".equals(header.bodyState())||!clock.instant().isBefore(header.bodyUntil()))throw unavailable();}
    private static Instant maximum(Instant a,Instant b){return a.isAfter(b)?a:b;}
    private static <T>T one(List<T> rows){if(rows.size()!=1)throw unavailable();return rows.getFirst();}
    static final RowMapper<Header> HEADER=(rs,n)->new Header(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5),
            instant(rs,6),instant(rs,7),instant(rs,8),rs.getString(9));
    static final RowMapper<Receipt> RECEIPT=(rs,n)->new Receipt(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),Operation.valueOf(rs.getString(5)),
            rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getLong(10),rs.getString(11),rs.getString(12),instant(rs,13),instant(rs,14));
}
