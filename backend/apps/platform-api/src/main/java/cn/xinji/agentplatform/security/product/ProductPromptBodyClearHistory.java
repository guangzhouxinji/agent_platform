package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.*;

import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Request;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Business-history authentication only. Never used from a single-resource maintenance main phase. */
final class ProductPromptBodyClearHistory {
    private record History(List<Request> chain,Request target,String bodyState) { }
    private record Envelope(String id,Instant until,long length) { }
    private final ProductPromptAuthorityStore authority;
    private final Clock clock;
    private final ProductPromptBodyClearCodec codec=new ProductPromptBodyClearCodec(
            new ProductComparisonJson(new ObjectMapper().findAndRegisterModules()));
    ProductPromptBodyClearHistory(ProductPromptAuthorityStore authority,Clock clock) {
        this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);
    }
    void requireReadyAuthority(ProductPromptCreateRequest original,Control control) {
        authority.jdbc.requireTransaction();inspect(original,control);
    }
    void requireReadyMain(ProductPromptStore main,Workspace workspace,ProductPromptCreateRequest original,Head head) {
        main.jdbc.requireTransaction();authority.jdbc.requireNoManagedTransaction();authority.requireReady(workspace,head);
        Control control=authority.control(head.promptId());History history=inspect(original,control);
        var snapshot=main.scalarSnapshot(head);Instant scope=snapshot.closureScopeUntil(workspace);current(scope);
        Header currentHeader=main.header(head.promptId(),original.header().revisionId());
        Header expected="PRESENT".equals(history.bodyState())?original.header():cleared(original.header());
        if(!expected.equals(currentHeader))throw unavailable();nativeState(main,currentHeader);
        if(history.target()!=null)requireMainProof(main,workspace,head,scope,history.target());
        var reverse=history.chain().reversed();
        for(Request link:reverse) {
            requireMainProof(main,workspace,head,scope,link);
            if(!snapshot.sha().equals(link.afterSha()))throw unavailable();
            snapshot=snapshot.restorePresent(link.target());
            if(!snapshot.sha().equals(link.before().scalarSha()))throw unavailable();
        }
        if(head.generation()==original.receipt().generation()&&!snapshot.sha().equals(original.pending().afterSha()))throw unavailable();
        if(!control.equals(authority.control(head.promptId())))throw unavailable();authority.requireReady(workspace,head);
    }
    void requireOriginCleared(ProductPromptStore main,Workspace workspace,Head before,Header first,Receipt original,
                              Control authenticated,Pending ownPending) {
        main.jdbc.requireTransaction();authority.jdbc.requireNoManagedTransaction();
        if(authenticated==null||authenticated.cleanupRequestId()!=null||!authenticated.equals(authority.control(before.promptId()))
                ||!authenticated.promptId().equals(before.promptId())||!authenticated.workspaceId().equals(workspace.id())
                ||authenticated.kind()!=before.kind()||authenticated.generation()!=before.generation()
                ||!Objects.equals(authenticated.revisionId(),before.revisionId())||!authenticated.scalarSha().equals(before.scalarSha()))throw unavailable();
        if(ownPending==null)authority.requireReady(workspace,before);
        else requireOwnPending(authenticated,before,ownPending);
        if(!"CLEARED".equals(first.bodyState()))throw unavailable();
        Header originalHeader=present(first);witness(originalHeader,original,"CLEARED");
        var rows=requests("revision_id=?",first.revisionId());if(rows.size()!=1)throw unavailable();Request request=rows.getFirst();
        Instant scope=main.scalarSnapshot(before).closureScopeUntil(workspace);current(scope);
        target(request,before,originalHeader,original);requireMainProof(main,workspace,before,scope,request);
        if(!authenticated.equals(authority.control(before.promptId())))throw unavailable();
    }
    private History inspect(ProductPromptCreateRequest original,Control control) {
        ready(control);current(original.header().auditUntil());
        Head current=head(control);
        if(!"READY".equals(original.state())||!current.promptId().equals(original.pending().promptId())
                ||!current.workspaceId().equals(original.key().workspaceId())||current.kind()!=original.pending().kind()
                ||current.generation()<original.receipt().generation())throw unavailable();
        var targetRows=requests("revision_id=?",original.header().revisionId());
        String state=witnessState(original.header());Request target=null;
        if("CLEARED".equals(state)) {
            if(targetRows.size()!=1)throw unavailable();target=targetRows.getFirst();target(target,current,original.header(),original.receipt());
        }else if(!"PRESENT".equals(state)||!targetRows.isEmpty())throw unavailable();
        witness(original.header(),original.receipt(),state);
        var ordered=new ArrayList<Request>();
        if(current.generation()==original.receipt().generation()) {
            sameHead(original.after(),current);
            var links=requests("prompt_id=? AND generation=?",current.promptId(),current.generation());
            var byBefore=new HashMap<String,Request>();var afters=new HashSet<String>();var revisions=new HashSet<String>();
            for(Request link:links) {
                context(link,current);sameHead(link.before(),current);witness(link.target(),link.originalReceipt(),"CLEARED");
                if(byBefore.put(link.before().scalarSha(),link)!=null||!afters.add(link.afterSha())
                        ||!revisions.add(link.target().revisionId()))throw unavailable();
            }
            String at=original.pending().afterSha();var seen=new HashSet<String>();seen.add(at);
            for(int i=0;i<links.size();i++) {
                Request link=byBefore.get(at);if(link==null||!seen.add(link.afterSha()))throw unavailable();ordered.add(link);at=link.afterSha();
            }
            if(!at.equals(current.scalarSha())||target!=null&&target.before().generation()==current.generation()&&!ordered.contains(target))throw unavailable();
        }
        return new History(List.copyOf(ordered),target,state);
    }
    private void target(Request request,Head current,Header original,Receipt receipt) {
        context(request,current);
        if(!request.target().equals(original)||!request.originalReceipt().equals(receipt))throw unavailable();
    }
    private void context(Request request,Head current) {
        current(request.receiptUntil());
        if(!"READY".equals(request.state())||!request.before().promptId().equals(current.promptId())
                ||!request.before().workspaceId().equals(current.workspaceId())||request.before().kind()!=current.kind()
                ||request.before().generation()>current.generation())throw unavailable();
        if(request.before().generation()==current.generation())sameHead(request.before(),current);
    }
    private void requireMainProof(ProductPromptStore main,Workspace workspace,Head head,Instant scope,Request request) {
        context(request,head);current(request.closureScopeUntil());
        if(!workspace.equals(request.workspace())||scope.isAfter(request.closureScopeUntil())
                ||head.generation()==request.before().generation()&&!scope.equals(request.closureScopeUntil())
                ||!main.header(head.promptId(),request.target().revisionId()).equals(request.afterHeader()))throw unavailable();
        nativeState(main,request.afterHeader());
        var envelopes=envelopes(main.jdbc,"conp_prompt_cleanup_receipt","request_id=? OR revision_id=?",request.requestId(),request.target().revisionId());
        if(envelopes.size()!=1)throw unavailable();var envelope=envelopes.getFirst();
        var values=main.jdbc.db.query("SELECT "+ProductPromptBodyClearCodec.COLUMNS+" FROM conp_prompt_cleanup_receipt WHERE request_id=? AND receipt_until>? AND LENGTH(manifest_json)<=16384",
                (rs,n)->codec.readReceipt(rs),envelope.id(),ts(clock.instant()));
        if(values.size()!=1||!values.getFirst().manifest().equals(request.manifest())||!envelope.until().equals(request.receiptUntil()))throw unavailable();
        var original=request.originalReceipt();
        if(!original.equals(main.receipt(original.workspaceId(),original.issuer(),original.subject(),original.operation(),original.keySha())))throw unavailable();
        witness(request.target(),original,"CLEARED");current(request.receiptUntil());
    }
    private List<Request> requests(String where,Object...args) {
        var envelopes=envelopes(authority.jdbc,"idp_prompt_cleanup_request",where,args);var result=new ArrayList<Request>();
        for(Envelope envelope:envelopes) {
            var rows=authority.jdbc.db.query("SELECT "+ProductPromptBodyClearCodec.REQUEST_COLUMNS+" FROM idp_prompt_cleanup_request WHERE request_id=? AND receipt_until>? AND LENGTH(manifest_json)<=16384",
                    (rs,n)->codec.readRequest(rs),envelope.id(),ts(clock.instant()));
            if(rows.size()!=1||!envelope.until().equals(rows.getFirst().receiptUntil()))throw unavailable();
            current(rows.getFirst().receiptUntil());result.add(rows.getFirst());
        }
        return List.copyOf(result);
    }
    private List<Envelope> envelopes(ProductPromptJdbc jdbc,String table,String where,Object...args) {
        var rows=jdbc.db.query("SELECT request_id,receipt_until,LENGTH(manifest_json) FROM "+table+" WHERE "+where,
                (rs,n)->new Envelope(rs.getString(1),instant(rs,2),rs.getObject(3)==null?-1:rs.getLong(3)),args);
        var ids=new HashSet<String>();
        for(Envelope row:rows) {current(row.until());if(!ids.add(row.id())||row.length()<1||row.length()>16384)throw unavailable();}
        return rows;
    }
    private String witnessState(Header header) {
        var rows=authority.jdbc.db.query("SELECT audit_until,body_state FROM idp_revision_witness WHERE revision_id=? OR (prompt_id=? AND revision_number=?)",
                (rs,n)->{Instant until=instant(rs,1);current(until);if(!until.equals(header.auditUntil()))throw unavailable();return rs.getString(2);},
                header.revisionId(),header.promptId(),header.number());
        if(rows.size()!=1)throw unavailable();return rows.getFirst();
    }
    private void witness(Header header,Receipt receipt,String state) {
        if(!state.equals(witnessState(header)))throw unavailable();
        Long count=authority.jdbc.db.queryForObject("SELECT COUNT(*) FROM idp_revision_witness WHERE revision_id=? AND prompt_id=? AND revision_number=? "
                        +"AND record_sha=? AND content_sha=? AND submitted_at=? AND body_until=? AND audit_until=? AND body_state=? "
                        +"AND result_sha=? AND key_sha=? AND request_sha=? AND issuer=? AND subject_id=?",
                Long.class,header.revisionId(),header.promptId(),header.number(),header.recordSha(),header.contentSha(),ts(header.submittedAt()),
                ts(header.bodyUntil()),ts(header.auditUntil()),state,receipt.resultSha(),receipt.keySha(),receipt.requestSha(),receipt.issuer(),receipt.subject());
        if(count!=1)throw unavailable();
    }
    private static void nativeState(ProductPromptStore main,Header header) {
        String predicate=switch(header.bodyState()){case "PRESENT"->"IS NOT NULL";case "CLEARED"->"IS NULL";default->throw unavailable();};
        Long count=main.jdbc.db.queryForObject("SELECT COUNT(*) FROM conp_revision WHERE prompt_id=? AND revision_id=? AND body_state=? AND revision_json "+predicate,
                Long.class,header.promptId(),header.revisionId(),header.bodyState());if(count!=1)throw unavailable();
    }
    private void requireOwnPending(Control c,Head before,Pending p) {
        if(p.operation()!=Operation.SAVE||!"PENDING".equals(c.state())||!p.promptId().equals(before.promptId())||!p.workspaceId().equals(before.workspaceId())
                ||p.kind()!=before.kind()||p.expectedGeneration()!=before.generation()||!Objects.equals(p.expectedRevisionId(),before.revisionId())
                ||!p.beforeSha().equals(before.scalarSha())||p.containerUntil().isBefore(before.containerUntil())||p.controlUntil().isBefore(before.controlUntil())
                ||!Objects.equals(c.intentId(),p.intentId())||c.operation()!=p.operation()||!Objects.equals(c.keySha(),p.keySha())
                ||!Objects.equals(c.requestSha(),p.requestSha())||!Objects.equals(c.beforeSha(),p.beforeSha())||!Objects.equals(c.afterSha(),p.afterSha())
                ||!Objects.equals(c.targetGeneration(),p.expectedGeneration()+1)||!Objects.equals(c.targetRevisionId(),p.targetRevisionId())
                ||!Objects.equals(c.intentUntil(),p.intentUntil())||!c.containerUntil().equals(p.containerUntil())||!c.controlUntil().equals(p.controlUntil()))throw unavailable();
        current(p.intentUntil());current(before.controlUntil());
    }
    private void ready(Control c) {
        if(c==null||!"READY".equals(c.state())||c.cleanupRequestId()!=null||c.intentId()!=null||c.operation()!=null||c.keySha()!=null
                ||c.requestSha()!=null||c.beforeSha()!=null||c.afterSha()!=null||c.targetGeneration()!=null||c.targetRevisionId()!=null||c.intentUntil()!=null)throw unavailable();
        current(c.controlUntil());
    }
    private static void sameHead(Head one,Head two) {
        if(!one.promptId().equals(two.promptId())||!one.workspaceId().equals(two.workspaceId())||one.kind()!=two.kind()
                ||one.generation()!=two.generation()||!Objects.equals(one.revisionId(),two.revisionId())
                ||!one.containerUntil().equals(two.containerUntil())||!one.controlUntil().equals(two.controlUntil()))throw unavailable();
    }
    private static Head head(Control c){return new Head(c.promptId(),c.workspaceId(),c.kind(),c.revisionId(),c.generation(),c.scalarSha(),c.containerUntil(),c.controlUntil());}
    private static Header cleared(Header h){return new Header(h.revisionId(),h.promptId(),h.number(),h.contentSha(),h.recordSha(),h.submittedAt(),h.bodyUntil(),h.auditUntil(),"CLEARED");}
    private static Header present(Header h){return new Header(h.revisionId(),h.promptId(),h.number(),h.contentSha(),h.recordSha(),h.submittedAt(),h.bodyUntil(),h.auditUntil(),"PRESENT");}
    private void current(Instant until){if(until==null||!clock.instant().isBefore(until))throw unavailable();}
}
