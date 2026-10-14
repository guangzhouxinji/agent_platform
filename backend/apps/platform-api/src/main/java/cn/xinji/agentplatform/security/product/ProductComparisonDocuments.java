package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentClaimGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Authorized exact original, whether selected or directly specified. No fallback to title/abstract. */
final class ProductComparisonDocuments {
    record Authority(ProductSourceAvailability.Active source, ProductPatentDocumentAccess.Authority display,
                     ProductPatentDocumentAccess.Authority comparison, Instant expiresAt) { }
    record Loaded(DocumentRef reference, ProductPatentClaimOriginal original, MaterialDocument material, Authority authority) { }
    private final ProductSourceAvailability sources;
    private final ProductCollectedPatentTitles originals;
    private final ProductSourceEvidenceFiles files;
    private final ProductPatentDocumentAccess access;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final Clock clock;
    ProductComparisonDocuments(ProductSourceAvailability sources,
            ProductCollectedPatentTitles originals, ProductSourceEvidenceFiles files, ProductPatentDocumentAccess access,
            JdbcTemplate db, ObjectMapper mapper, Clock clock) {
        this.sources=sources;this.originals=originals;this.files=files;
        this.access=access;this.db=db;this.mapper=mapper;this.clock=clock;
    }
    Loaded read(String taskId,DocumentRef ref,Jwt jwt,boolean historical) {
        return readExact(taskId,ref,historical,source->access.require(taskId,source,jwt),
                source->access.requireComparison(taskId,source,jwt),false,null);
    }
    Loaded readDelegated(String taskId,DocumentRef ref,ProductComparisonWorkerTaskAuthority.Authorized actor) {
        return readExact(taskId,ref,false,source->access.requireDelegatedDisplay(taskId,source,actor),
                source->access.requireDelegatedComparison(taskId,source,actor),false,null);
    }
    /** Current source inventory only: no retrieval result or invented selected claim. */
    List<Loaded> catalog(String taskId,String sourceId,Jwt jwt) {
        var active=sources.require(sourceId,"CLAIMS","DISPLAY");
        if(!active.equals(sources.require(sourceId,"CLAIMS","COMPARISON"))) throw conflict();
        var display=access.require(taskId,active,jwt);var compare=access.requireComparison(taskId,active,jwt);
        var evidence=originals.loadFieldEvidence(active,ProductPatentSearchFilters.none(),Set.of("CLAIMS"));
        if(evidence.size()>4000) throw new IllegalArgumentException("COMPARISON_DOCUMENT_BUDGET_EXCEEDED");
        var result=new ArrayList<Loaded>();
        for(var row:evidence) {
            var doc=row.document();
            var ref=new DocumentRef(sourceId,doc.documentId(),doc.documentVersion(),List.of(),null,null,null,null);
            result.add(readExact(taskId,ref,false,s->access.require(taskId,s,jwt),
                    s->access.requireComparison(taskId,s,jwt),true,List.of(row)));
        }
        if(!active.equals(sources.require(sourceId,"CLAIMS","DISPLAY"))
                ||!active.equals(sources.require(sourceId,"CLAIMS","COMPARISON"))
                ||!display.equals(access.require(taskId,active,jwt))
                ||!compare.equals(access.requireComparison(taskId,active,jwt))) throw conflict();
        return List.copyOf(result);
    }
    private Loaded readExact(String taskId,DocumentRef ref,boolean historical,
            java.util.function.Function<ProductSourceAvailability.Active,ProductPatentDocumentAccess.Authority> displayAccess,
            java.util.function.Function<ProductSourceAvailability.Active,ProductPatentDocumentAccess.Authority> comparisonAccess,boolean catalog,
            List<cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence> inventory) {
        validate(ref,catalog);
        var active=sources.require(ref.sourceId(),"CLAIMS","DISPLAY");
        if (!active.equals(sources.require(ref.sourceId(),"CLAIMS","COMPARISON"))) throw conflict();
        var display=displayAccess.apply(active); var comparison=comparisonAccess.apply(active);
        var matches=(inventory==null?originals.loadFieldEvidence(active,ProductPatentSearchFilters.none(),Set.of("CLAIMS")):inventory).stream()
                .filter(row->row.document().sourceId().equals(ref.sourceId()) && row.document().documentId().equals(ref.documentId())
                        && row.document().documentVersion().equals(ref.documentVersion())).toList();
        if (matches.size()!=1) throw conflict();
        var doc=matches.getFirst().document(); String locator=matches.getFirst().fieldLocators().get("CLAIMS");
        if (locator==null || !locator.contains("#json-pointer=")) throw new IllegalStateException("Original claims locator unavailable");
        Instant expiry=deadline(active,display,comparison);
        if (ref.selectionId()!=null && !historical) {
            // Trace the persisted choice without requiring unrelated QUERY permission to read claims.
            var chosen=db.query("SELECT s.expires_at FROM con4_selection_revision s "
                    + "JOIN con4_selection_member m ON m.selection_id=s.selection_id AND m.result_set_id=s.result_set_id "
                    + "JOIN con4_result_set r ON r.result_set_id=s.result_set_id AND r.result_sha=s.result_sha "
                    + "JOIN con4_search_attempt attempt ON attempt.attempt_id=r.attempt_id AND attempt.task_id=s.task_id "
                    + "JOIN con4_result_member rm ON rm.result_set_id=m.result_set_id AND rm.member_id=m.member_id "
                    + "AND rm.raw_sha=m.raw_sha AND rm.document_version=m.document_version "
                    + "AND rm.source_id=m.source_id AND rm.document_id=m.document_id AND rm.source_version=m.source_version "
                    + "AND rm.locator_ref=m.locator_ref "
                    + "WHERE s.task_id=? AND s.selection_id=? AND s.revision=? AND s.result_set_id=? "
                    + "AND m.member_id=? AND m.source_id=? AND m.document_id=? AND m.document_version=? "
                    + "AND m.raw_sha=? AND m.source_version=?",(rs,row)->rs.getTimestamp(1).toInstant(),taskId,
                    ref.selectionId(),ref.selectionRevision(),ref.resultSetId(),ref.memberId(),ref.sourceId(),ref.documentId(),
                    ref.documentVersion(),doc.rawSha(),doc.sourceVersion());
            if (chosen.size()!=1 || !chosen.getFirst().isAfter(clock.instant())) throw conflict();
            if (chosen.getFirst().isBefore(expiry)) expiry=chosen.getFirst();
        }
        // Reject an absent/mismatched selection before following its historical dependency.
        if(ref.resultSetId()!=null) new ProductHistoricalResultIsolation(db).require(ref.resultSetId());
        String rawRef=locator.substring(0,locator.indexOf('#'));
        String pointer=locator.substring(locator.indexOf("#json-pointer=")+14).split("&",2)[0];
        String field=switch(pointer) {case "/claimsText"->"claimsText"; case "/claims"->"claims";
            default->throw new IllegalStateException("Unsupported original claims pointer");};
        var original=new ProductPatentClaimOriginal(rawRef,files.verified(rawRef,doc.rawSha()),doc.rawSha(),doc.documentVersion(),field,mapper);
        List<Branch> branches=new ArrayList<>(); String gap="";
        List<Integer> selected=ref.claimNumbers();
        try {
            var graph=PatentClaimGraph.parse(original);
            if(catalog) selected=graph.claims().stream().map(PatentClaimGraph.Claim::number).sorted().toList();
            for (int number:selected.stream().sorted().toList()) {
                for (var branch:graph.branches(number,4000)) {
                    if (branches.size()>=4000) throw new IllegalArgumentException("CLAIM_BRANCH_BUDGET_EXCEEDED");
                    branches.add(new Branch(number,branch.inheritedClaims().stream().map(claim->
                            new Claim(claim.number(),claim.alternatives(),Span.of(claim.original()))).toList()));
                }
            }
        } catch (IllegalArgumentException problem) {
            if (problem.getMessage()==null || !problem.getMessage().startsWith("CLAIM_")) throw problem;
            gap=problem.getMessage(); branches.clear();
        }
        if (!active.equals(sources.requireFields(ref.sourceId(),Set.of("CLAIMS"),"DISPLAY"))
                || !active.equals(sources.requireFields(ref.sourceId(),Set.of("CLAIMS"),"COMPARISON"))
                || !display.equals(displayAccess.apply(active)) || !comparison.equals(comparisonAccess.apply(active)))
            throw conflict();
        String key=ProductComparisonJson.hash(ref.sourceId()+"\n"+ref.documentId()+"\n"+ref.documentVersion());
        var material=new MaterialDocument(key,ref.sourceId(),ref.documentId(),ref.documentVersion(),doc.sourceVersion(),doc.rawSha(),
                rawRef,pointer,List.copyOf(branches),gap);
        var exact=catalog?new DocumentRef(ref.sourceId(),ref.documentId(),ref.documentVersion(),List.copyOf(selected),null,null,null,null):ref;
        return new Loaded(exact,original,material,new Authority(active,display,comparison,expiry));
    }
    private Instant deadline(ProductSourceAvailability.Active source,ProductPatentDocumentAccess.Authority display,
            ProductPatentDocumentAccess.Authority compare) {
        var values=db.query("SELECT s.expires_at,d.expires_at,g.expires_at,k.valid_until,a.valid_until,fd.valid_until,fc.valid_until "
                + "FROM con4_task_source_scope s JOIN id2_grant d ON d.grant_id=? JOIN id2_grant g ON g.grant_id=? "
                + "JOIN con4_source_contract k ON k.source_id=s.source_id AND k.contract_version=s.contract_version "
                + "JOIN con4_source_admission a ON a.decision_id=s.decision_id "
                + "JOIN con4_source_field_use fd ON fd.decision_id=a.decision_id AND fd.field_code='CLAIMS' AND fd.use_code='DISPLAY' "
                + "JOIN con4_source_field_use fc ON fc.decision_id=a.decision_id AND fc.field_code='CLAIMS' AND fc.use_code='COMPARISON' "
                + "WHERE s.scope_id=? AND s.scope_version=? AND s.source_id=? AND s.decision_id=?",
                (rs,row)->{Instant earliest=rs.getTimestamp(1).toInstant(); for(int i=2;i<=7;i++) {
                    Instant next=rs.getTimestamp(i).toInstant(); if(next.isBefore(earliest)) earliest=next;} return earliest;},
                display.grantId(),compare.grantId(),compare.scopeId(),compare.scopeVersion(),source.sourceId(),source.decisionId());
        if(values.size()!=1 || !values.getFirst().isAfter(clock.instant())) throw conflict();
        return values.getFirst();
    }
    static void validate(DocumentRef ref) {validate(ref,false);}
    private static void validate(DocumentRef ref,boolean catalog) {
        if(ref==null || blank(ref.sourceId()) || ref.sourceId().length()>200 || blank(ref.documentId())
                || ref.documentId().length()>300 || blank(ref.documentVersion()) || ref.documentVersion().length()>200
                || ref.claimNumbers()==null || (!catalog && ref.claimNumbers().isEmpty()) || ref.claimNumbers().size()>4000
                || ref.claimNumbers().stream().anyMatch(n->n==null || n<1)
                || new HashSet<>(ref.claimNumbers()).size()!=ref.claimNumbers().size()) throw invalid();
        boolean any=ref.selectionId()!=null || ref.memberId()!=null || ref.resultSetId()!=null || ref.selectionRevision()!=null;
        if(any && (blank(ref.selectionId()) || blank(ref.memberId()) || blank(ref.resultSetId())
                || ref.selectionRevision()==null || ref.selectionRevision()<1)) throw invalid();
    }
    private static boolean blank(String s) { return s==null || s.isBlank(); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"EXACT_CLAIMS_REFERENCE_REQUIRED"); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"COMPARISON_ORIGINAL_OR_AUTHORITY_CHANGED"); }
}


