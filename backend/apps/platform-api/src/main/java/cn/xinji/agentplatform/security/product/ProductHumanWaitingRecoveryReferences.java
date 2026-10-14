package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentClaimGraph;
import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconstructs referenced native objects without impersonating their historical author. */
final class ProductHumanWaitingRecoveryReferences {
    record Verified(OrdinaryHumanRetention.Bounds bounds,String closureSha,Instant executionUntil,Instant referencedSaveUntil) { }
    private record CurrentScope(String sha,Instant executionUntil) { }
    private final JdbcTemplate db;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final ProductComparisonJson json;
    private final ProductSourceEvidenceFiles files;
    private final ProductSourceAvailability sources;
    private final ProductCollectedPatentTitles originals;
    private final ProductHumanWaitingRecoveryFacts facts;
    ProductHumanWaitingRecoveryReferences(JdbcTemplate main,JdbcTemplate authority,ObjectMapper mapper,Clock clock,
            ProductRetentionPolicy taskPolicy,ProductRetentionPolicy factsPolicy,ProductSourceEvidenceFiles files) {
        this.db=main;this.clock=clock;this.mapper=mapper;this.json=new ProductComparisonJson(mapper);this.files=files;
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(main.getDataSource())));
        this.sources=new ProductSourceAvailability(main,new ProductSourceWatermark(main,authority,tx),clock);
        this.originals=new ProductCollectedPatentTitles(files,mapper);
        this.facts=new ProductHumanWaitingRecoveryFacts(main,json,clock,taskPolicy,factsPolicy);
    }
    Verified verify(ProductHumanRequestStore.Manifest m) {
        var fact=facts.verify(m);Instant task=fact.taskUntil(),use=m.references().facts().expiresAt(),save=use;
        Instant audit=min(task,m.references().cleanup().validUntil());
        var uses=new TreeMap<String,ProductHumanRequestLocks.SourceUse>();
        for(var material:m.references().materials()) merge(uses,new ProductHumanRequestLocks.SourceUse(
                material.source().source().sourceId(),material.fieldLocators().keySet(),Set.of()));
        var comparisons=new ProductHumanWaitingRecoveryComparisons(db,json,clock);
        var closure=comparisons.discover(m);
        closure.documents().forEach(d->merge(uses,new ProductHumanRequestLocks.SourceUse(d.sourceId(),Set.of("CLAIMS"),Set.of("CLAIMS"))));
        var pins=new TreeMap<String,ProductHumanRequestReferences.SourcePin>();
        for(var pin:m.references().sources())if(pins.put(pin.use().sourceId(),pin)!=null)throw unavailable();
        if(!uses.keySet().equals(pins.keySet()))throw unavailable();
        var active=new TreeMap<String,ProductSourceAvailability.Active>();
        var scopes=new TreeMap<String,CurrentScope>();
        var rights=new TreeMap<String,Map<String,Instant>>();
        for(var entry:uses.entrySet()) {
            var selected=entry.getValue();var pin=pins.get(entry.getKey());
            if(!selected.equals(pin.use()))throw unavailable();
            historicalScope(m,pin.source(),pin.scopeId(),pin.scopeVersion());
            var source=sources.requireFields(selected.sourceId(),selected.fields(),"DISPLAY");
            if(!source.equals(pin.source())||!source.equals(sources.requireFields(selected.sourceId(),selected.fields(),"SAVE")))throw unavailable();
            if(!selected.comparisonFields().isEmpty()&&!source.equals(sources.requireFields(selected.sourceId(),selected.comparisonFields(),"COMPARISON")))throw unavailable();
            scopes.put(source.sourceId(),scope(m,source));
            var deadlines=rights(selected);rights.put(source.sourceId(),deadlines);
            for(String field:selected.fields()) {
                use=min(use,deadlines.get("DISPLAY:"+field));save=min(save,deadlines.get("SAVE:"+field));
                audit=min(audit,deadlines.get("SAVE:"+field));
            }
            for(String field:selected.comparisonFields())use=min(use,deadlines.get("COMPARISON:"+field));
            active.put(entry.getKey(),source);
        }
        for(var material:m.references().materials()) {
            var source=active.get(material.source().source().sourceId());
            if(!source.equals(material.source().source()))throw unavailable();
            historicalScope(m,source,material.source().scopeId(),material.source().scopeVersion());
            var pin=pins.get(source.sourceId());
            if(!pin.scopeId().equals(material.source().scopeId())||pin.scopeVersion()!=material.source().scopeVersion()
                    ||!material.source().validUntil().isAfter(m.createdAt()))throw unavailable();
            var evidence=originals.loadFieldEvidence(source,ProductPatentSearchFilters.none(),material.fieldLocators().keySet()).stream()
                    .filter(e->e.document().documentId().equals(material.documentId())&&e.document().documentVersion().equals(material.documentVersion())).toList();
            if(evidence.size()!=1)throw unavailable();var item=evidence.getFirst();
            var locators=new TreeMap<String,String>();material.fieldLocators().keySet().forEach(f->locators.put(f,item.fieldLocators().get(f)));
            if(!material.rawSha().equals(item.document().rawSha())||!source.sourceVersion().equals(item.document().sourceVersion())||!locators.equals(material.fieldLocators()))throw unavailable();
        }
        comparisons.verifyMaterials(closure,(ref,expected)->claim(ref,expected,active.get(ref.sourceId())));
        // File reads are external to the SQL fence. Recheck the complete current source
        // snapshot after the last real original-byte read, including the second inspection.
        for(var selected:uses.values()) {
            var expected=active.get(selected.sourceId());
            if(!expected.equals(sources.requireFields(selected.sourceId(),selected.fields(),"DISPLAY"))
                    ||!expected.equals(sources.requireFields(selected.sourceId(),selected.fields(),"SAVE"))
                    ||!selected.comparisonFields().isEmpty()&&!expected.equals(sources.requireFields(selected.sourceId(),selected.comparisonFields(),"COMPARISON"))
                    ||!scopes.get(selected.sourceId()).equals(scope(m,expected))
                    ||!rights.get(selected.sourceId()).equals(rights(selected)))throw unavailable();
        }
        if(closure.expiresAt()!=null){use=min(use,closure.expiresAt());save=min(save,closure.expiresAt());}
        Instant cleanup=min(task,m.references().cleanup().validUntil());
        Instant executionUntil=min(task,use,save,cleanup);
        Instant referencedSaveUntil=closure.referencedSaveUntil()==null?min(task,save):min(task,save,closure.referencedSaveUntil());
        executionUntil=min(executionUntil,referencedSaveUntil);
        for(var currentScope:scopes.values())executionUntil=min(executionUntil,currentScope.executionUntil());
        if(!executionUntil.isAfter(clock.instant()))throw unavailable();
        var bounds=new OrdinaryHumanRetention.Bounds(task,task,use,save,audit,min(task,save));
        return new Verified(bounds,json.sha(Map.of("facts",fact.closureSha(),"references",m.references(),"comparisons",closure,"currentSources",active,"currentScopes",scopes,"currentRights",rights,"bounds",bounds)),executionUntil,referencedSaveUntil);
    }
    private Map<String,Instant> rights(ProductHumanRequestLocks.SourceUse selected) {
        var result=new TreeMap<String,Instant>();
        for(String field:selected.fields()) {
            result.put("DISPLAY:"+field,sources.useUntil(selected.sourceId(),field,"DISPLAY"));
            result.put("SAVE:"+field,sources.useUntil(selected.sourceId(),field,"SAVE"));
        }
        for(String field:selected.comparisonFields())result.put("COMPARISON:"+field,sources.useUntil(selected.sourceId(),field,"COMPARISON"));
        return Map.copyOf(result);
    }
    private ProductPatentClaimOriginal claim(DocumentRef ref,MaterialDocument expected,ProductSourceAvailability.Active source) {
        if(ref.resultSetId()!=null)new ProductHistoricalResultIsolation(db).require(ref.resultSetId());
        var matches=originals.loadFieldEvidence(source,ProductPatentSearchFilters.none(),Set.of("CLAIMS")).stream()
                .filter(e->e.document().documentId().equals(ref.documentId())&&e.document().documentVersion().equals(ref.documentVersion())).toList();
        if(matches.size()!=1)throw unavailable();var evidence=matches.getFirst();var document=evidence.document();
        String locator=evidence.fieldLocators().get("CLAIMS");int marker=locator==null?-1:locator.indexOf("#json-pointer=");
        if(marker<0)throw unavailable();String originalRef=locator.substring(0,marker),pointer=locator.substring(marker+14).split("&",2)[0];
        if(!Set.of("/claims","/claimsText").contains(pointer))throw unavailable();
        var original=new ProductPatentClaimOriginal(originalRef,files.verified(originalRef,document.rawSha()),document.rawSha(),document.documentVersion(),pointer.substring(1),mapper);
        var branches=new ArrayList<Branch>();String gap="";
        try {var graph=PatentClaimGraph.parse(original);
            for(int number:new TreeSet<>(ref.claimNumbers()))for(var branch:graph.branches(number,4000))
                {if(branches.size()>=4000)throw new IllegalArgumentException("CLAIM_BRANCH_BUDGET_EXCEEDED");branches.add(new Branch(number,branch.inheritedClaims().stream().map(c->new Claim(c.number(),c.alternatives(),Span.of(c.original()))).toList()));}
        } catch(IllegalArgumentException invalid) {if(invalid.getMessage()==null||!invalid.getMessage().startsWith("CLAIM_"))throw invalid;gap=invalid.getMessage();branches.clear();}
        var actual=new MaterialDocument(ProductComparisonJson.hash(ref.sourceId()+"\n"+ref.documentId()+"\n"+ref.documentVersion()),ref.sourceId(),ref.documentId(),ref.documentVersion(),document.sourceVersion(),document.rawSha(),originalRef,pointer,List.copyOf(branches),gap);
        if(!actual.equals(expected))throw unavailable();return original;
    }
    private CurrentScope scope(ProductHumanRequestStore.Manifest m,ProductSourceAvailability.Active source) {
        var rows=db.query("SELECT s.organization_id,s.task_version,s.contract_version,s.decision_id,s.source_epoch,s.source_row_version,s.status,c.status,s.expires_at,t.version,s.scope_id,s.scope_version,c.row_version "
                +"FROM con4_task_source_current c JOIN con4_task_source_scope s ON s.scope_id=c.scope_id AND s.scope_version=c.scope_version AND s.task_id=c.task_id AND s.source_id=c.source_id "
                +"JOIN con3_task t ON t.task_id=c.task_id WHERE c.task_id=? AND c.source_id=?",(rs,n)->{
            var timestamp=rs.getTimestamp(9);if(timestamp==null||!timestamp.toInstant().isAfter(clock.instant()))throw unavailable();
            if(!m.organization().equals(rs.getString(1))||rs.getLong(2)!=rs.getLong(10)||!source.contractVersion().equals(rs.getString(3))
                    ||!source.decisionId().equals(rs.getString(4))||source.epoch()!=rs.getLong(5)||source.rowVersion()!=rs.getLong(6)
                    ||!"ACTIVE".equals(rs.getString(7))||!"ACTIVE".equals(rs.getString(8)))throw unavailable();return new CurrentScope(json.sha(List.of(rs.getString(11),rs.getLong(12),rs.getLong(13),rs.getLong(10),timestamp.toInstant())),timestamp.toInstant());
        },m.taskId(),source.sourceId());if(rows.size()!=1)throw unavailable();return rows.getFirst();
    }
    private void historicalScope(ProductHumanRequestStore.Manifest m,ProductSourceAvailability.Active source,String id,long version) {
        var rows=db.query("SELECT organization_id,contract_version,decision_id,source_epoch,source_row_version,expires_at FROM con4_task_source_scope WHERE task_id=? AND source_id=? AND scope_id=? AND scope_version=?",(rs,n)->
                m.organization().equals(rs.getString(1))&&source.contractVersion().equals(rs.getString(2))&&source.decisionId().equals(rs.getString(3))
                &&source.epoch()==rs.getLong(4)&&source.rowVersion()==rs.getLong(5)&&rs.getTimestamp(6)!=null&&rs.getTimestamp(6).toInstant().isAfter(m.createdAt()),m.taskId(),source.sourceId(),id,version);
        if(rows.size()!=1||!rows.getFirst())throw unavailable();
    }
    private static void merge(Map<String,ProductHumanRequestLocks.SourceUse> uses,ProductHumanRequestLocks.SourceUse use) {
        uses.merge(use.sourceId(),use,(a,b)->{var fields=new TreeSet<>(a.fields());fields.addAll(b.fields());var comparison=new TreeSet<>(a.comparisonFields());comparison.addAll(b.comparisonFields());return new ProductHumanRequestLocks.SourceUse(a.sourceId(),fields,comparison);});
    }
    private static Instant min(Instant... values){return Arrays.stream(values).min(Instant::compareTo).orElseThrow();}
    private static IllegalStateException unavailable(){return new IllegalStateException("Recovered human reference closure unavailable");}
}
