package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Human result consumption needs current READ and original source purposes, not current model execution rights. */
final class ProductComparisonMatrixReader {
    record Candidate(String candidateId,String status,String attemptId,String errorCode,String executionState,List<String> reportIds) { }
    record Results(String taskId,String comparisonId,String inputSha,String state,List<ProductComparisonContracts.Feature> confirmedFeatures,List<Candidate> candidates) { }
    private final ProductTaskDirectory tasks;private final ProductConsultationAccess access;private final ProductComparisonInputStore inputs;
    private final ProductComparisonDocuments documents;private final ProductComparisonTargetReader targets;private final ProductSourceAvailability sources;
    private final ProductComparisonMatrixStore store;private final ProductComparisonMatrixCalculation calculation;private final ProductComparisonJson json;
    private final JdbcTemplate db;private final Clock clock;
    ProductComparisonMatrixReader(ProductTaskDirectory tasks,ProductConsultationAccess access,ProductComparisonInputStore inputs,ProductComparisonDocuments documents,
            ProductComparisonTargetReader targets,ProductSourceAvailability sources,ProductComparisonMatrixStore store,ProductComparisonMatrixCalculation calculation,
            ProductComparisonJson json,JdbcTemplate db,Clock clock){this.tasks=tasks;this.access=access;this.inputs=inputs;this.documents=documents;this.targets=targets;
        this.sources=sources;this.store=store;this.calculation=calculation;this.json=json;this.db=db;this.clock=clock;}
    void requireBinding(javax.sql.DataSource main) {
        if(db.getDataSource()!=main)throw new IllegalArgumentException("Matching matrix storage required");
    }
    Results results(String task,String comparison,Jwt jwt) {
        var input=require(task,comparison,jwt);var e=input.input();var candidates=new ArrayList<Candidate>();
        var originalsBefore=new ArrayList<ProductComparisonWorkerMaterials.Loaded>();
        for(var candidate:e.candidates()) {
            originalsBefore.add(originals(input,candidate.candidateId(),jwt));
            var progress=input.candidateProgress().stream().filter(p->p.candidateId().equals(candidate.candidateId())).findFirst().orElseThrow(ProductComparisonMatrixStore::corrupt);
            var slots=db.query("SELECT l.attempt_id,l.status,a.error_code FROM con5_candidate_lease l JOIN con5_candidate_attempt a ON a.attempt_id=l.attempt_id AND a.fencing_token=l.fencing_token "
                    +"WHERE l.comparison_id=? AND l.candidate_id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},comparison,candidate.candidateId());
            var ids=db.queryForList("SELECT report_id FROM con5_matrix_report WHERE task_id=? AND comparison_id=? AND candidate_id=? ORDER BY created_at",String.class,task,comparison,candidate.candidateId());
            candidates.add(new Candidate(candidate.candidateId(),progress.status(),slots.isEmpty()?null:slots.getFirst()[0],slots.isEmpty()?null:slots.getFirst()[2],slots.isEmpty()?null:slots.getFirst()[1],ids));
        }
        var after=require(task,comparison,jwt);
        if(!input.equals(after))throw ProductComparisonMatrixStore.corrupt();
        for(var before:originalsBefore){var now=originals(after,before.candidate().material().key(),jwt);
            if(!before.candidate().material().equals(now.candidate().material())||!before.target().text().equals(now.target().text()))throw ProductComparisonMatrixStore.corrupt();}
        String state=candidates.stream().allMatch(c->"COMMITTED".equals(c.status()))?"COMMITTED":candidates.stream().anyMatch(c->"COMMITTED".equals(c.status()))?"PARTIAL":"IN_PROGRESS";
        if(!input.equals(require(task,comparison,jwt)))throw ProductComparisonMatrixStore.corrupt();return new Results(task,comparison,input.inputSha(),state,e.confirmedFeatures().revision().features(),candidates);
    }
    ProductComparisonMatrixResults.Report report(String task,String comparison,String report,Jwt jwt) {
        return checkedReport(task,comparison,report,jwt,() -> require(task,comparison,jwt));
    }
    /** Internal original-coordinate consumption; historical input validation retains feature/material/source checks. */
    ProductComparisonMatrixResults.Report historicalReport(String task,String comparison,String report,Jwt jwt,
            ProductComparisonInputApplication historicalInputs) {
        Objects.requireNonNull(historicalInputs);
        var authority=tasks.find(task).orElseThrow(ProductComparisonMatrixStore::corrupt);
        var result=checkedReport(task,comparison,report,jwt,() -> historicalInputs.read(task,comparison,jwt));
        if(!authority.equals(tasks.find(task).orElseThrow(ProductComparisonMatrixStore::corrupt)))throw ProductComparisonMatrixStore.corrupt();
        return result;
    }
    private ProductComparisonMatrixResults.Report checkedReport(String task,String comparison,String report,Jwt jwt,
            java.util.function.Supplier<ProductComparisonInputs.View> checkedInput) {
        var input=checkedInput.get();var refs=db.queryForList("SELECT candidate_id FROM con5_matrix_report WHERE task_id=? AND comparison_id=? AND report_id=?",String.class,task,comparison,report);
        if(refs.size()!=1)throw ProductComparisonMatrixStore.corrupt();var loaded=originals(input,refs.getFirst(),jwt);
        var result=store.read(task,comparison,report);var value=result.result();
        if(!input.inputSha().equals(value.inputSha())||!refs.getFirst().equals(value.candidateId())
                ||!calculation.validate(loaded,store.rawResponse(value.attemptId())).rows().equals(value.rows()))throw ProductComparisonMatrixStore.corrupt();
        var after=originals(checkedInput.get(),refs.getFirst(),jwt);
        if(!loaded.candidate().material().equals(after.candidate().material())||!input.equals(after.input())
                ||!loaded.target().text().equals(after.target().text()))throw ProductComparisonMatrixStore.corrupt();
        if(!input.equals(checkedInput.get())||!value.expiresAt().isAfter(clock.instant()))throw ProductComparisonMatrixStore.corrupt();return result;
    }
    private ProductComparisonWorkerMaterials.Loaded originals(ProductComparisonInputs.View input,String candidate,Jwt jwt) {
        var e=input.input();var prep=json.stored(e.currentPreparationSnapshot(),com.fasterxml.jackson.databind.JsonNode.class);
        var command=json.stored(json.canonical(prep.get("command")),ProductComparisonContracts.Prepare.class);
        var refs=command.candidates().stream().filter(r->ProductComparisonJson.hash(r.sourceId()+"\n"+r.documentId()+"\n"+r.documentVersion()).equals(candidate)).toList();
        if(refs.size()!=1)throw ProductComparisonMatrixStore.corrupt();var doc=documents.read(e.taskId(),refs.getFirst(),jwt,true);
        if(!doc.authority().source().equals(sources.require(doc.reference().sourceId(),"CLAIMS","SAVE")))throw ProductComparisonMatrixStore.corrupt();
        ProductComparisonDocuments.Loaded targetPatent=null;cn.xinji.agentplatform.consultation.patentcomparison.OriginalClaimText target;
        if(command.target().messageId()!=null)target=targets.readUserScheme(e.taskId(),command.target().messageId(),tasks.find(e.taskId()).orElseThrow(ProductComparisonMatrixStore::corrupt).version(),jwt).original();
        else {targetPatent=documents.read(e.taskId(),command.target().patent(),jwt,true);target=targetPatent.original();
            if(!targetPatent.authority().source().equals(sources.require(targetPatent.reference().sourceId(),"CLAIMS","SAVE")))throw ProductComparisonMatrixStore.corrupt();}
        return new ProductComparisonWorkerMaterials.Loaded(input,target,targetPatent,doc,null,e.expiresAt());
    }
    private ProductComparisonInputs.View require(String task,String comparison,Jwt jwt) {
        var current=tasks.find(task).orElseThrow(ProductComparisonMatrixStore::corrupt);
        if(jwt==null||!current.expiresAt().isAfter(clock.instant())||!access.permits(jwt,new ProductConsultationAccess.ResourceFacts(current.organization(),task,current.dataScope(),"TASK",task,current.authorizationVersion()),ProductAccessPolicy.Action.READ))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        var input=inputs.read(task,comparison);if(!input.input().expiresAt().isAfter(clock.instant())||input.input().taskVersion()!=current.version())throw ProductComparisonMatrixStore.corrupt();return input;
    }
}
