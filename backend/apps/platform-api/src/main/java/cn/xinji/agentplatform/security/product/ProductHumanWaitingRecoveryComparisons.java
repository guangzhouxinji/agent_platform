package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import cn.xinji.agentplatform.consultation.patentcomparison.OriginalClaimText;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only native input, preparation, revision and selected-report closure. */
final class ProductHumanWaitingRecoveryComparisons {
    record Node(ProductComparisonInputs.View view,Prepare command,JsonNode material,Instant messageUntil) { }
    record Closure(List<Node> nodes,List<DocumentRef> documents,Instant expiresAt,Instant referencedSaveUntil) { }
    private final JdbcTemplate db;
    private final ProductComparisonJson json;
    private final Clock clock;
    ProductHumanWaitingRecoveryComparisons(JdbcTemplate db,ProductComparisonJson json,Clock clock){this.db=db;this.json=json;this.clock=clock;}
    Closure discover(ProductHumanRequestStore.Manifest m) {
        var selected=m.references().comparison();if(selected==null)return new Closure(List.of(),List.of(),null,null);
        var nodes=new ArrayList<Node>();var current=node(m.taskId(),selected.id(),m.createdAt());nodes.add(current);
        var input=current.view().input();var command=current.command();
        if(!current.view().inputSha().equals(selected.sha())||input.taskVersion()!=selected.taskVersion()
                ||!command.goalId().equals(selected.goalId())||command.goalRevision()!=selected.goalRevision()
                ||!command.inputId().equals(selected.searchInputId()))throw unavailable();
        var matrices=new ProductComparisonMatrixStore(guarded(input.expiresAt()),json,new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource()))),clock);
        var nearest=new HashMap<String,ProductComparisonMatrixResults.Report>();
        var ancestry=new ArrayList<ProductComparisonParentEvidenceReader.Ancestor>();
        Instant ancestorUntil=input.expiresAt();
        Instant referencedSaveUntil=input.expiresAt();
        if(selected.reports().stream().anyMatch(r->!r.comparisonId().equals(selected.id()))) {
            var seen=new HashSet<String>(Set.of(selected.id()));var shas=new HashSet<String>(Set.of(selected.sha()));
            var missing=new HashSet<String>();input.candidates().forEach(c->missing.add(c.candidateId()));
            String next=input.previousComparisonId();
            while(next!=null) {
                if(nodes.size()>=64||!seen.add(next))throw unavailable();var parent=node(m.taskId(),next,m.createdAt());nodes.add(parent);
                if(!shas.add(parent.view().inputSha()))throw unavailable();var p=parent.view().input();
                ancestry.add(new ProductComparisonParentEvidenceReader.Ancestor(next,parent.view().inputSha(),p.previousComparisonId()));
                ancestorUntil=min(ancestorUntil,p.expiresAt());
                referencedSaveUntil=min(referencedSaveUntil,p.expiresAt());
                var nativeIds=matrices.ids(m.taskId(),next);var reportedCandidates=new HashSet<String>();
                if(nativeIds.size()>20||new HashSet<>(nativeIds).size()!=nativeIds.size())throw unavailable();
                for(String id:nativeIds) {
                    var report=report(m.taskId(),next,id,matrices,m.createdAt());
                    referencedSaveUntil=min(referencedSaveUntil,report.result().expiresAt());
                    if(!report.result().inputSha().equals(parent.view().inputSha())||!reportedCandidates.add(report.result().candidateId())
                            ||p.candidates().stream().filter(c->c.candidateId().equals(report.result().candidateId())).count()!=1)throw unavailable();
                    if(nodes.size()==2||missing.contains(report.result().candidateId()))nearest.putIfAbsent(report.result().candidateId(),report);
                    missing.remove(report.result().candidateId());
                }
                next=p.previousComparisonId();if(next!=null&&seen.contains(next))throw unavailable();if(missing.isEmpty())break;
            }
            if(ancestry.isEmpty())throw unavailable();
        }
        Instant until=input.expiresAt();
        for(var selectedReport:selected.reports()) {
            var report=report(m.taskId(),selectedReport.comparisonId(),selectedReport.reportId(),matrices,m.createdAt());
            var value=report.result();boolean local=selectedReport.comparisonId().equals(selected.id());
            referencedSaveUntil=min(referencedSaveUntil,value.expiresAt());
            Instant retained=min(input.expiresAt(),value.expiresAt());
            if(local) {if(!selectedReport.ancestry().isEmpty()||!value.inputSha().equals(current.view().inputSha()))throw unavailable();}
            else {if(!report.equals(nearest.get(value.candidateId()))||!selectedReport.ancestry().equals(ancestry))throw unavailable();retained=min(retained,ancestorUntil);}
            if(!selectedReport.sha().equals(report.reportSha())||selectedReport.revision()!=value.resultVersion()
                    ||!selectedReport.candidateId().equals(value.candidateId())||!selectedReport.inputSha().equals(value.inputSha())
                    ||!selectedReport.expiresAt().equals(retained))throw unavailable();until=min(until,retained);
        }
        if(!selected.expiresAt().equals(until))throw unavailable();
        var documents=new ArrayList<DocumentRef>();for(var n:nodes){if(n.command().target().patent()!=null)documents.add(n.command().target().patent());documents.addAll(n.command().candidates());}
        if(!referencedSaveUntil.isAfter(clock.instant()))throw unavailable();
        return new Closure(List.copyOf(nodes),List.copyOf(documents),until,referencedSaveUntil);
    }
    private Node node(String task,String id,Instant created) {
        Instant inputUntil=deadline("con5_comparison_input","comparison_id",task,id);
        var view=new ProductComparisonInputStore(guarded(inputUntil),json).read(task,id);var e=view.input();
        if(e.createdAt().isAfter(created))throw unavailable();
        Instant featureUntil=deadline("con5_feature_revision","revision_id",task,e.command().featureRevisionId());
        if(inputUntil.isAfter(featureUntil))throw unavailable();
        var store=new ProductComparisonStore(guarded(min(inputUntil,featureUntil)),json);var features=store.revision(task,e.command().featureRevisionId());var revision=features.revision();
        if(!features.equals(e.confirmedFeatures())||revision.revisionNumber()!=e.command().featureRevisionNumber()
                ||!features.packageSha().equals(e.command().packageSha())||!revision.preparationId().equals(e.preparationId())
                ||revision.confirmedAt().isAfter(e.createdAt()))throw unavailable();
        Instant preparationUntil=deadline("con5_preparation","preparation_id",task,e.preparationId());
        if(inputUntil.isAfter(preparationUntil))throw unavailable();
        var documentDeadlines=db.query("SELECT expires_at FROM con5_prepared_document WHERE preparation_id=?",(rs,n)->{
            var value=rs.getTimestamp(1);if(value==null||!value.toInstant().isAfter(clock.instant()))throw unavailable();return value.toInstant();},e.preparationId());
        if(documentDeadlines.stream().anyMatch(inputUntil::isAfter))throw unavailable();
        var preparation=store.preparation(task,e.preparationId(),clock.instant());
        if(!revision.prepareSha().equals(preparation.sha())||!e.materialSha().equals(preparation.materialSha()))throw unavailable();
        var receipt=store.requestForPreparation(task,e.preparationId());if(receipt==null)throw unavailable();
        var receiptChecks=db.query("SELECT p.actor_issuer,p.actor_subject,r.actor_issuer,r.actor_subject,p.created_at FROM con5_preparation p JOIN con5_preparation_request r ON r.task_id=p.task_id AND r.preparation_id=p.preparation_id WHERE p.task_id=? AND p.preparation_id=?",(rs,n)->
                Objects.equals(rs.getString(1),rs.getString(3))&&Objects.equals(rs.getString(2),rs.getString(4))
                &&!rs.getTimestamp(5).toInstant().isAfter(revision.confirmedAt())
                &&receipt.sha().equals(json.sha(Map.of("taskId",task,"issuer",rs.getString(1),"subject",rs.getString(2),"requestId",receipt.key(),"command",preparation.command()))),task,e.preparationId());
        if(receiptChecks.size()!=1||!receiptChecks.getFirst())throw unavailable();
        var snapshot=json.stored(e.currentPreparationSnapshot(),JsonNode.class);
        var command=json.stored(snapshot.path("command").toString(),Prepare.class);
        if(!task.equals(snapshot.path("taskId").asText())||e.taskVersion()!=snapshot.path("taskVersion").asLong()
                ||command.expectedTaskVersion()!=e.taskVersion()||!json.sha(snapshot.path("material")).equals(e.materialSha())
                ||!command.equals(preparation.command()))throw unavailable();
        if(documentDeadlines.size()!=command.candidates().size()+(command.target().patent()==null?0:1))throw unavailable();
        var candidates=snapshot.path("material").path("candidates");if(candidates.size()!=e.candidates().size())throw unavailable();
        for(int i=0;i<candidates.size();i++)if(!candidates.get(i).path("key").asText().equals(e.candidates().get(i).candidateId())||e.candidates().get(i).ordinal()!=i)throw unavailable();
        Instant messageUntil=command.target().messageId()==null?null:deadline("con3_message","message_id",task,command.target().messageId());
        if(messageUntil!=null&&inputUntil.isAfter(messageUntil))throw unavailable();
        return new Node(view,command,snapshot.path("material"),messageUntil);
    }
    void verifyMaterials(Closure closure,BiFunction<DocumentRef,MaterialDocument,ProductPatentClaimOriginal> originals) {
        if(closure.referencedSaveUntil()!=null&&!closure.referencedSaveUntil().isAfter(clock.instant()))throw unavailable();
        for(var node:closure.nodes()) {
            var command=node.command();var material=node.material();OriginalClaimText target;
            if(command.target().patent()!=null)target=originals.apply(command.target().patent(),json.stored(material.path("targetPatent").toString(),MaterialDocument.class));
            else {
                Instant messageUntil=deadline("con3_message","message_id",node.view().input().taskId(),command.target().messageId());
                if(!messageUntil.equals(node.messageUntil())||node.view().input().expiresAt().isAfter(messageUntil))throw unavailable();
                var values=guarded(messageUntil).query("SELECT raw_text,content_sha,received_at FROM con3_message WHERE task_id=? AND message_id=?",(rs,n)->{
                    if(rs.getTimestamp(3).toInstant().isAfter(node.view().input().createdAt()))throw unavailable();
                    String text=rs.getString(1);return new ProductTechnicalSchemeOriginal(node.view().input().taskId(),command.target().messageId(),text,rs.getString(2),Math.max(1,text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
                },node.view().input().taskId(),command.target().messageId());if(values.size()!=1)throw unavailable();target=values.getFirst();
            }
            if(!Span.of(target.evidence(0,target.text().length())).equals(json.stored(material.path("target").toString(),Span.class)))throw unavailable();
            for(var feature:node.view().input().confirmedFeatures().revision().features())
                if(!Span.of(target.evidence(feature.characterStart(),feature.characterEnd())).equals(feature.original()))throw unavailable();
            for(int i=0;i<command.candidates().size();i++)originals.apply(command.candidates().get(i),json.stored(material.path("candidates").get(i).toString(),MaterialDocument.class));
        }
        if(closure.referencedSaveUntil()!=null&&!closure.referencedSaveUntil().isAfter(clock.instant()))throw unavailable();
    }
    private ProductComparisonMatrixResults.Report report(String task,String comparison,String id,ProductComparisonMatrixStore store,Instant created) {
        Instant reportUntil=deadline("con5_matrix_report","report_id",task,id);
        var checked=new ProductComparisonMatrixStore(guarded(reportUntil),json,new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource()))),clock);
        var report=checked.read(task,comparison,id);
        if(report.result().createdAt().isAfter(created))throw unavailable();return report;
    }
    private Instant deadline(String table,String column,String task,String id) {
        var rows=db.query("SELECT expires_at FROM "+table+" WHERE task_id=? AND "+column+"=?",(rs,n)->rs.getTimestamp(1),task,id);
        if(rows.size()!=1||rows.getFirst()==null||!rows.getFirst().toInstant().isAfter(clock.instant()))throw unavailable();
        return rows.getFirst().toInstant();
    }
    /** Check every body access against both its native row cutoff and its containing object. */
    private JdbcTemplate guarded(Instant containingUntil) {
        return new JdbcTemplate(Objects.requireNonNull(db.getDataSource())) {
            @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                return db.query(sql,(rs,n)->{
                    Instant rowUntil=containingUntil;
                    var metadata=rs.getMetaData();
                    for(int i=1;i<=metadata.getColumnCount();i++)if("expires_at".equalsIgnoreCase(metadata.getColumnLabel(i))) {
                        var timestamp=rs.getTimestamp(i);if(timestamp==null)throw unavailable();rowUntil=min(rowUntil,timestamp.toInstant());
                    }
                    final Instant cutoff=rowUntil;
                    if(!cutoff.isAfter(clock.instant()))throw unavailable();
                    var checked=(java.sql.ResultSet)java.lang.reflect.Proxy.newProxyInstance(java.sql.ResultSet.class.getClassLoader(),new Class<?>[]{java.sql.ResultSet.class},(proxy,method,arguments)->{
                        if((method.getName().equals("getString")||method.getName().equals("getClob")||method.getName().equals("getCharacterStream"))&&!cutoff.isAfter(clock.instant()))throw unavailable();
                        try{return method.invoke(rs,arguments);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    });
                    return mapper.mapRow(checked,n);
                },args);
            }
        };
    }
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static IllegalStateException unavailable(){return new IllegalStateException("Recovered comparison reference closure unavailable");}
}
