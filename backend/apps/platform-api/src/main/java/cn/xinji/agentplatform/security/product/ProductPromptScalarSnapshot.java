package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.ts;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Immutable scalar projection. It never reads a body or proves authorization or a committed cleanup. */
final class ProductPromptScalarSnapshot {
    private final ProductComparisonJson json;
    private final List<List<String>> heads,revisions,events,receipts;
    private ProductPromptScalarSnapshot(ProductComparisonJson json,List<List<String>> heads,List<List<String>> revisions,
                                        List<List<String>> events,List<List<String>> receipts) {
        this.json=json;this.heads=copy(heads);this.revisions=copy(revisions);this.events=copy(events);this.receipts=copy(receipts);
    }
    static ProductPromptScalarSnapshot read(ProductPromptJdbc jdbc,ProductComparisonJson json,Clock clock,String promptId) {
        jdbc.requireTransaction();uuid(promptId);
        for(String table:List.of("conp_revision","conp_event","conp_receipt"))
            if(jdbc.db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE prompt_id=? AND audit_until<=?",
                    Long.class,promptId,ts(clock.instant()))!=0)throw unavailable();
        var heads=rows(jdbc,"SELECT prompt_id,workspace_id,kind,head_revision_id,generation,container_until,control_until FROM conp_prompt WHERE prompt_id=?",promptId);
        var revisions=rows(jdbc,"SELECT revision_id,prompt_id,revision_number,content_sha,record_sha,submitted_at,body_until,audit_until,body_state "
                +"FROM conp_revision WHERE prompt_id=? ORDER BY revision_number,revision_id",promptId);
        var events=rows(jdbc,"SELECT event_id,prompt_id,revision_id,generation,operation,issuer,subject_id,submitted_at,request_sha,result_sha,audit_until "
                +"FROM conp_event WHERE prompt_id=? ORDER BY generation,event_id",promptId);
        var receipts=rows(jdbc,"SELECT receipt_id,workspace_id,issuer,subject_id,operation,key_sha,request_sha,prompt_id,revision_id,generation,content_sha,result_sha,submitted_at,audit_until "
                +"FROM conp_receipt WHERE prompt_id=? ORDER BY generation,receipt_id",promptId);
        return new ProductPromptScalarSnapshot(json,heads,revisions,events,receipts);
    }
    String sha(){return json.sha(List.of("PROMPT_SCALAR_CLOSURE_V1",heads,revisions,events,receipts));}
    Instant closureScopeUntil(Workspace workspace) {
        if(heads.size()!=1||!heads.getFirst().get(1).equals(workspace.id())||revisions.isEmpty())throw unavailable();
        Instant until=earlier(workspace.validUntil(),Instant.parse(heads.getFirst().get(6)));
        for(var row:revisions)until=earlier(until,Instant.parse(row.get(7)));
        for(var row:events)until=earlier(until,Instant.parse(row.get(10)));
        for(var row:receipts)until=earlier(until,Instant.parse(row.get(13)));
        return until;
    }
    ProductPromptScalarSnapshot clear(Header originalPresent){return project(originalPresent,false);}
    ProductPromptScalarSnapshot restorePresent(Header originalPresent){return project(originalPresent,true);}
    private ProductPromptScalarSnapshot project(Header original,boolean restore) {
        if(original==null||!"PRESENT".equals(original.bodyState())||heads.size()!=1
                ||!heads.getFirst().getFirst().equals(original.promptId()))throw unavailable();
        var present=scalars(original.revisionId(),original.promptId(),original.number(),original.contentSha(),original.recordSha(),
                original.submittedAt(),original.bodyUntil(),original.auditUntil(),"PRESENT");
        var cleared=new ArrayList<>(present);cleared.set(8,"CLEARED");
        List<String> expected=restore?cleared:present,replacement=restore?present:cleared;
        var projected=new ArrayList<>(revisions);int matches=0;
        for(int i=0;i<projected.size();i++)if(projected.get(i).getFirst().equals(original.revisionId())) {
            if(!projected.get(i).equals(expected))throw unavailable();projected.set(i,replacement);matches++;
        }
        if(matches!=1)throw unavailable();return new ProductPromptScalarSnapshot(json,heads,projected,events,receipts);
    }
    ProductPromptScalarSnapshot append(PreparedWrite prepared) {
        Head h=prepared.after();Header v=prepared.header();Receipt r=prepared.receipt();
        var nextHeads=List.of(scalars(h.promptId(),h.workspaceId(),h.kind(),h.revisionId(),h.generation(),h.containerUntil(),h.controlUntil()));
        var nextRevisions=new ArrayList<>(revisions);var nextEvents=new ArrayList<>(events);var nextReceipts=new ArrayList<>(receipts);
        nextRevisions.add(scalars(v.revisionId(),v.promptId(),v.number(),v.contentSha(),v.recordSha(),v.submittedAt(),v.bodyUntil(),v.auditUntil(),v.bodyState()));
        nextEvents.add(scalars(r.id(),r.promptId(),r.revisionId(),r.generation(),r.operation(),r.issuer(),r.subject(),r.submittedAt(),r.requestSha(),r.resultSha(),r.auditUntil()));
        nextReceipts.add(scalars(r.id(),r.workspaceId(),r.issuer(),r.subject(),r.operation(),r.keySha(),r.requestSha(),r.promptId(),r.revisionId(),r.generation(),r.contentSha(),r.resultSha(),r.submittedAt(),r.auditUntil()));
        return new ProductPromptScalarSnapshot(json,nextHeads,nextRevisions,nextEvents,nextReceipts);
    }
    private static List<List<String>> rows(ProductPromptJdbc jdbc,String sql,String promptId) {
        return jdbc.db.query(sql,(rs,n)->{
            Object[] values=new Object[rs.getMetaData().getColumnCount()];
            for(int i=0;i<values.length;i++)values[i]=rs.getObject(i+1);return scalars(values);
        },promptId);
    }
    private static List<String> scalars(Object...values) {
        return java.util.Arrays.stream(values).map(value->value instanceof java.sql.Timestamp stamp
                ?stamp.toInstant().toString():String.valueOf(value)).toList();
    }
    private static List<List<String>> copy(List<List<String>> values){return values.stream().map(List::copyOf).toList();}
    private static Instant earlier(Instant one,Instant two){return one.isBefore(two)?one:two;}
}
