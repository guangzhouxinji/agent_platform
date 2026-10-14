package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.runtime.ModelGateway;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.*;

/** Authenticated historical evidence only; neither a new-computation configuration nor a carry plan. */
final class ProductComparisonParentEvidenceReader {
    record Ancestor(String comparisonId, String inputSha, String previousComparisonId) { }
    record ParentReport(Snapshot snapshot, SourceReport original, List<Snapshot> path) {
        ParentReport { path=List.copyOf(path); if(path.isEmpty() || !snapshot.equals(path.getFirst()))throw changed(); }
        ParentReport(Snapshot snapshot,SourceReport original) { this(snapshot,original,List.of(snapshot)); }
    }
    record Evidence(ProductComparisonInputs.View current, ProductComparisonInputs.View parent, List<ParentReport> reports,
            List<ProductComparisonInputs.View> history) {
        Evidence { reports = List.copyOf(reports); history=List.copyOf(history); }
        Evidence(ProductComparisonInputs.View current,ProductComparisonInputs.View parent,List<ParentReport> reports) {
            this(current,parent,reports,List.of(parent));
        }
        List<Ancestor> ancestry() { return history.stream().map(v -> new Ancestor(v.input().comparisonId(),v.inputSha(),v.input().previousComparisonId())).toList(); }
    }
    record Checked<T>(T value, Instant expiresAt, Runnable recheck) { }
    record ParentMatrix(String taskId, String comparisonId, String inputSha, String previousComparisonId, String parentInputSha,
            Instant expiresAt, List<ProductComparisonContracts.Feature> confirmedFeatures,
            ProductComparisonMatrixResults.Report report, String originalJson, String sourceComparisonId, String sourceInputSha,
            List<Ancestor> ancestry) { }
    interface Projection<T> {
        Checked<T> apply(Evidence evidence, ProductComparisonPreparationLoader.Loaded materials, Instant expiresAt);
    }
    private record Original(String raw, String sha) { }
    private record Materials(ProductComparisonPreparationLoader.Loaded loaded, Map<String,Instant> saveDeadlines) {
        Materials { saveDeadlines=Map.copyOf(saveDeadlines); }
        boolean expired(Instant now) { return !loaded.expiresAt().isAfter(now) || saveDeadlines.values().stream().anyMatch(t -> !t.isAfter(now)); }
        @Override public boolean equals(Object value) {
            if(this==value)return true;
            return value instanceof Materials other && saveDeadlines.equals(other.saveDeadlines()) && ProductComparisonMaterialIdentity.same(loaded,other.loaded());
        }
        @Override public int hashCode() { return Objects.hash(loaded.prepareSha(),saveDeadlines); }

    }
    private record Node(ProductComparisonInputs.View input,Materials materials,List<String> ids,List<ParentReport> reports,
            Map<String,ProductComparisonMatrixResults.Report> nativeReports,Map<String,Original> originals,
            Map<String,ProductComparisonLineageStore.Material> storedMaterials) { }
    private record Common(Snapshot snapshot,Instant expiresAt,List<ProductComparisonModelAdmission.ConfigurationEvidence> evidence) { }
    private final ProductComparisonModelAdmission metadata;
    private final ProductComparisonPreparationLoader loader;
    private final ProductComparisonInputApplication inputs;
    private final ProductComparisonInputStore inputStore;
    private final ProductComparisonMatrixStore matrices;
    private final ProductComparisonLineageStore lineage;
    private final ProductSourceAvailability sources;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ProductComparisonJson json;
    private final ProductComparisonMatrixCalculation calculation;
    private final ProductComparisonLineageFacts facts;
    private final Clock clock;

    ProductComparisonParentEvidenceReader(ProductComparisonPreparationLoader loader, ProductComparisonInputApplication inputs,
            ProductComparisonInputStore inputStore, ProductComparisonMatrixStore matrices, ProductComparisonLineageStore lineage,
            ProductSourceAvailability sources, JdbcTemplate db, TransactionTemplate tx, ProductComparisonJson json,
            ProductComparisonMatrixCalculation calculation, ProductComparisonLineageFacts facts, Clock clock) {
        this(loader,inputs,inputStore,matrices,lineage,sources,db,tx,json,calculation,facts,clock,null);
    }
    ProductComparisonParentEvidenceReader(ProductComparisonPreparationLoader loader, ProductComparisonInputApplication inputs,
            ProductComparisonInputStore inputStore, ProductComparisonMatrixStore matrices, ProductComparisonLineageStore lineage,
            ProductSourceAvailability sources, JdbcTemplate db, TransactionTemplate tx, ProductComparisonJson json,
            ProductComparisonMatrixCalculation calculation, ProductComparisonLineageFacts facts, Clock clock,ProductComparisonModelAdmission metadata) {
        this.loader=loader; this.inputs=inputs; this.inputStore=inputStore; this.matrices=matrices; this.lineage=lineage;
        this.sources=sources; this.db=db; this.tx=tx; this.json=json; this.calculation=calculation; this.facts=facts; this.clock=clock;
        this.metadata=metadata;
    }

    void requireBinding(javax.sql.DataSource main) {
        if(db.getDataSource()!=main || !(tx.getTransactionManager() instanceof org.springframework.jdbc.datasource.DataSourceTransactionManager manager)
                || manager.getDataSource()!=main || tx.getPropagationBehavior()!=org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED)
            throw new IllegalArgumentException("Matching parent evidence transaction required");
    }

    Evidence read(String task, String comparison, Jwt jwt) {
        return project(task,comparison,jwt,(e,m,until) -> new Checked<>(e,until,() -> { }));
    }

    ParentMatrix report(String task, String comparison, String reportId, Jwt jwt) {
        return report(task,comparison,reportId,jwt,false);
    }

    /** Original human-package coordinates may outlive the selected comparison pointer. */
    ParentMatrix historicalReport(String task, String comparison, String reportId, Jwt jwt) {
        return report(task,comparison,reportId,jwt,true);
    }

    private ParentMatrix report(String task, String comparison, String reportId, Jwt jwt, boolean historical) {
        return project(task,comparison,jwt,(e,m,until) -> {
            var matches=e.reports().stream().filter(r -> r.original().reportId().equals(reportId)).toList();
            if (matches.size()!=1) throw changed();
            var proof=matches.getFirst().original();
            var source=e.history().stream().filter(v -> v.input().comparisonId().equals(proof.comparisonId()) && v.inputSha().equals(proof.inputSha())).findFirst().orElseThrow(ProductComparisonParentEvidenceReader::changed);
            var report=matrices.read(task,proof.comparisonId(),reportId);
            var original=original(task,proof.comparisonId(),reportId,report);
            if (!proof.reportSha().equals(original.sha()) || !proof.candidateId().equals(report.result().candidateId())) throw changed();
            var value=new ParentMatrix(task,comparison,e.current().inputSha(),e.parent().input().comparisonId(),e.parent().inputSha(),
                    until,source.input().confirmedFeatures().revision().features(),report,original.raw(),proof.comparisonId(),proof.inputSha(),e.ancestry());
            return new Checked<>(value,until,() -> { });
        },historical);
    }

    /** Fixed original-object deadlines; current grants only authorize this consumption. */
    Instant retainedUntil(ParentMatrix parent, Jwt jwt) {
        var current=inputs.read(parent.taskId(),parent.comparisonId(),jwt);
        if(!parent.inputSha().equals(current.inputSha()) || !Objects.equals(parent.previousComparisonId(),current.input().previousComparisonId()))throw changed();
        Instant until=min(current.input().expiresAt(),parent.report().result().expiresAt());
        String next=current.input().previousComparisonId();
        boolean sourceFound=false;
        for(var ancestor:parent.ancestry()) {
            if(!ancestor.comparisonId().equals(next))throw changed();
            var input=inputs.read(parent.taskId(),ancestor.comparisonId(),jwt);
            if(!ancestor.inputSha().equals(input.inputSha()) || !Objects.equals(ancestor.previousComparisonId(),input.input().previousComparisonId()))throw changed();
            if(ancestor.comparisonId().equals(parent.sourceComparisonId())) {
                if(!ancestor.inputSha().equals(parent.sourceInputSha()) || !ancestor.inputSha().equals(parent.report().result().inputSha()))throw changed();
                sourceFound=true;
            }
            until=min(until,input.input().expiresAt()); next=ancestor.previousComparisonId();
        }
        if(!sourceFound || !parent.expiresAt().isAfter(clock.instant()) || !until.isAfter(clock.instant()))throw changed();
        return until;
    }

    <T> T project(String task, String comparison, Jwt jwt, Projection<T> projection) {
        return project(task,comparison,jwt,projection,false);
    }

    private <T> T project(String task, String comparison, Jwt jwt, Projection<T> projection, boolean historical) {
        loader.require(task,jwt,ProductAccessPolicy.Action.READ);
        return tx.execute(status -> {
            lock(task);
            var authority=loader.require(task,jwt,ProductAccessPolicy.Action.READ);
            var current=inputs.read(task,comparison,jwt);
            if (!task.equals(current.input().taskId()) || !comparison.equals(current.input().comparisonId())
                    || !historical && (current.input().taskVersion()!=authority.version() || !comparison.equals(inputStore.current(task)))) throw changed();
            String parentId=current.input().previousComparisonId();
            if (parentId==null || parentId.equals(comparison)) throw changed();
            var currentMaterials=materials(current,jwt);
            List<Node> nodes=new ArrayList<>();
            Map<String,ParentReport> nearest=new LinkedHashMap<>();
            Map<String,Integer> origins=new HashMap<>();
            Set<String> idsSeen=new HashSet<>(Set.of(comparison)),shasSeen=new HashSet<>(Set.of(current.inputSha()));
            Set<String> missing=new HashSet<>();current.input().candidates().forEach(c -> missing.add(c.candidateId()));
            String next=parentId;
            while(next!=null) {
                if(nodes.size()>=63 || !idsSeen.add(next))throw changed();
                var node=node(task,next,jwt);
                if(!shasSeen.add(node.input().inputSha()))throw changed();
                int index=nodes.size();nodes.add(node);
                for(var report:node.reports()) {
                    String candidate=report.original().candidateId();
                    if(index==0 || missing.contains(candidate)) {
                        if(nearest.putIfAbsent(candidate,report)==null)origins.put(candidate,index);
                        missing.remove(candidate);
                    }
                }
                next=node.input().input().previousComparisonId();
                if(next!=null && idsSeen.contains(next))throw changed();
                if(missing.isEmpty())break;
            }
            var parent=nodes.getFirst().input();
            Map<Integer,Common> common=new HashMap<>();
            List<ParentReport> reports=new ArrayList<>();
            for(var entry:nearest.entrySet()) {
                var original=entry.getValue();int origin=origins.get(entry.getKey());
                List<Snapshot> path=new ArrayList<>(List.of(original.snapshot()));
                for(int i=origin-1;i>=0;i--) {
                    final int index=i;
                    path.add(common.computeIfAbsent(i,k -> common(nodes.get(index))).snapshot());
                }
                reports.add(new ParentReport(original.snapshot(),original.original(),path));
            }
            Instant until=current.input().expiresAt();
            until=min(until,currentMaterials.loaded().expiresAt());
            for(var deadline:currentMaterials.saveDeadlines().values())until=min(until,deadline);
            for(var node:nodes) {
                until=min(until,node.input().input().expiresAt());until=min(until,node.materials().loaded().expiresAt());
                for(var deadline:node.materials().saveDeadlines().values())until=min(until,deadline);
                for(var report:node.nativeReports().values())until=min(until,report.result().expiresAt());
            }
            for(var value:common.values())until=min(until,value.expiresAt());
            var projected=projection.apply(new Evidence(current,parent,reports,nodes.stream().map(Node::input).toList()),currentMaterials.loaded(),until);
            for(int i=0;i<nodes.size();i++) {
                var original=nodes.get(i);var checked=node(task,original.input().input().comparisonId(),jwt);
                if(!original.equals(checked) || common.containsKey(i) && !common.get(i).equals(common(checked)))throw changed();
            }
            if (!current.equals(inputs.read(task,comparison,jwt)) || !currentMaterials.equals(materials(current,jwt))) throw changed();
            if (!historical && !comparison.equals(inputStore.current(task))
                    || !authority.equals(loader.require(task,jwt,ProductAccessPolicy.Action.READ))) throw changed();
            projected.recheck().run();
            if (!until.isAfter(clock.instant()) || !projected.expiresAt().isAfter(clock.instant())) throw changed();
            return projected.value();
        });
    }

    private Node node(String task,String parentId,Jwt jwt) {
            var parent=inputs.read(task,parentId,jwt);
            if (!task.equals(parent.input().taskId()) || !parentId.equals(parent.input().comparisonId())) throw changed();
            var parentMaterials=materials(parent,jwt);
            var ids=List.copyOf(matrices.ids(task,parentId));
            if (ids.size()>20 || new HashSet<>(ids).size()!=ids.size()) throw changed();
            Set<String> candidates=new HashSet<>();
            List<ParentReport> reports=new ArrayList<>();
            Map<String,ProductComparisonMatrixResults.Report> nativeReports=new LinkedHashMap<>();
            Map<String,Original> originals=new LinkedHashMap<>();
            Map<String,ProductComparisonLineageStore.Material> storedMaterials=new LinkedHashMap<>();
            for (String id:ids) {
                var report=matrices.read(task,parentId,id); var value=report.result();
                var matches=parentMaterials.loaded().candidates().stream().filter(c -> c.material().key().equals(value.candidateId())).toList();
                if (!parent.inputSha().equals(value.inputSha()) || matches.size()!=1 || !candidates.add(value.candidateId())) throw changed();
                var loaded=new ProductComparisonWorkerMaterials.Loaded(parent,parentMaterials.loaded().target(),parentMaterials.loaded().targetPatent(),
                        matches.getFirst(),null,parent.input().expiresAt());
                var material=lineage.material(task,value.attemptId());
                var configuration=json.stored(material.configurationJson(),ModelGateway.Configuration.class);
                var captured=facts.capture(loaded,configuration,material.requestSha());
                if (!captured.equals(lineage.capture(material))
                        || !calculation.validate(loaded,matrices.rawResponse(value.attemptId())).rows().equals(value.rows())) throw changed();
                var original=original(task,parentId,id,report);
                var proof=lineage.read(original.raw(),original.sha());
                if (!proof.comparisonId().equals(parentId) || !proof.inputSha().equals(parent.inputSha())
                        || !proof.candidateId().equals(value.candidateId()) || !proof.reportId().equals(id)
                        || !proof.reportSha().equals(report.reportSha())) throw changed();
                reports.add(new ParentReport(captured.snapshot(),proof));
                nativeReports.put(id,report); originals.put(id,original);
                storedMaterials.put(id,material);
            }
            for (var progress:parent.candidateProgress())
                if ("COMMITTED".equals(progress.status()) && !candidates.contains(progress.candidateId())) throw changed();
            return new Node(parent,parentMaterials,ids,List.copyOf(reports),Map.copyOf(nativeReports),Map.copyOf(originals),Map.copyOf(storedMaterials));
    }

    private Common common(Node node) {
        if(metadata==null || node.storedMaterials().isEmpty())throw changed();
        List<ProductComparisonModelAdmission.ConfigurationEvidence> evidence=new ArrayList<>();
        ModelGateway.Configuration config=null;Instant until=node.input().input().expiresAt();
        for(String id:node.ids()) {
            var value=metadata.configurationEvidence(node.storedMaterials().get(id),node.input());
            if(config!=null && !config.equals(value.configuration()))throw changed();
            config=value.configuration();evidence.add(value);until=min(until,value.expiresAt());
        }
        Snapshot snapshot=null;
        for(var candidate:node.materials().loaded().candidates()) {
            var loaded=new ProductComparisonWorkerMaterials.Loaded(node.input(),node.materials().loaded().target(),node.materials().loaded().targetPatent(),candidate,null,until);
            var value=facts.capture(loaded,config,ProductComparisonJson.hash(calculation.request(loaded,config))).snapshot();
            if(snapshot!=null && !snapshot.equals(value))throw changed();
            snapshot=value;
        }
        if(snapshot==null)throw changed();
        return new Common(snapshot,until,List.copyOf(evidence));
    }

    private static Instant min(Instant left,Instant right) { return left.isBefore(right)?left:right; }

    private Materials materials(ProductComparisonInputs.View input, Jwt jwt) {
        var snapshot=json.stored(input.input().currentPreparationSnapshot(),com.fasterxml.jackson.databind.JsonNode.class);
        var command=json.stored(json.canonical(snapshot.get("command")),ProductComparisonContracts.Prepare.class);
        var loaded=loader.load(input.input().taskId(),command,jwt,true);
        if (!input.input().materialSha().equals(loaded.materialSha()) || !loaded.expiresAt().isAfter(clock.instant())) throw changed();
        List<ProductComparisonDocuments.Loaded> documents=new ArrayList<>(loaded.candidates());
        if (loaded.targetPatent()!=null) documents.add(loaded.targetPatent());
        Map<String,Instant> deadlines=new HashMap<>();
        for (var document:documents) {
            if (!document.authority().source().equals(sources.require(document.reference().sourceId(),"CLAIMS","SAVE"))) throw changed();
            deadlines.put(document.reference().sourceId(),sources.useUntil(document.reference().sourceId(),"CLAIMS","SAVE"));
        }
        return new Materials(loaded,deadlines);
    }

    private Original original(String task, String comparison, String id, ProductComparisonMatrixResults.Report report) {
        var rows=db.queryForList("SELECT package_json,report_sha FROM con5_matrix_report WHERE task_id=? AND comparison_id=? AND report_id=?",task,comparison,id);
        if (rows.size()!=1) throw changed();
        String raw=(String)rows.getFirst().get("package_json"), sha=(String)rows.getFirst().get("report_sha");
        if (raw==null || !ProductComparisonJson.hash(raw).equals(sha) || !report.reportSha().equals(sha)
                || !report.result().equals(json.stored(raw,ProductComparisonMatrixResults.Package.class))) throw changed();
        return new Original(raw,sha);
    }

    private void lock(String task) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || db.getDataSource()==null
                || !TransactionSynchronizationManager.hasResource(db.getDataSource())) throw new IllegalStateException("LINEAGE_OWNING_TRANSACTION_REQUIRED");
        if (db.queryForList("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE",String.class,task).size()!=1) throw changed();
    }
    private static IllegalStateException changed() { return new IllegalStateException("LINEAGE_PARENT_OR_CURRENT_CHANGED"); }
}
