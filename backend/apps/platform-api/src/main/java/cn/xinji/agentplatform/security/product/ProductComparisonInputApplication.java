package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonInputs.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonPreparationLoader.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorized immutable comparison input, not a submitted analysis or delegated Worker. */
final class ProductComparisonInputApplication {
    private record Bound(ProductComparisonContracts.FeatureView features,ProductComparisonStore.Preparation preparation,Loaded materials) { }
    private final ProductComparisonPreparationLoader loader;
    private final ProductComparisonStore features;
    private final ProductComparisonInputStore inputs;
    private final TransactionTemplate transactions;
    private final ProductComparisonJson json;
    private final Clock clock;
    ProductComparisonInputApplication(ProductComparisonPreparationLoader loader,ProductComparisonStore features,
            ProductComparisonInputStore inputs,TransactionTemplate transactions,ProductComparisonJson json,Clock clock) {
        this.loader=loader;this.features=features;this.inputs=inputs;this.transactions=transactions;this.json=json;this.clock=clock;
    }
    View create(String task,Create command,String requestId,Jwt jwt) {
        validate(command,requestId);loader.require(task,jwt,ProductAccessPolicy.Action.COMPARE);
        String issuer=jwt.getIssuer().toString(),subject=jwt.getSubject();
        String requestSha=json.sha(Map.of("taskId",task,"issuer",issuer,"subject",subject,"requestId",requestId,"command",command));
        return transactions.execute(status->{
            features.lockTask(task);var bound=bind(task,command,jwt,false);
            var replay=inputs.replay(task,issuer,subject,requestId,requestSha);
            if(replay!=null) {verify(task,replay,jwt,false);return replay;}
            if(!Objects.equals(command.previousComparisonId(),inputs.current(task))) throw conflict();
            var candidateSlots=bound.materials().documents().stream().filter(d->"CANDIDATE".equals(d.role()))
                    .map(d->new Candidate(d.key(),d.ordinal())).toList();
            Instant expiry=min(bound.features().revision().expiresAt(),min(bound.preparation().expiresAt(),bound.materials().expiresAt()));
            var envelope=new Envelope(UUID.randomUUID().toString(),task,bound.materials().task().version(),command.previousComparisonId(),command,
                    bound.features(),bound.preparation().id(),bound.materials().materialSha(),bound.materials().snapshotJson(),issuer,subject,
                    requestId,requestSha,clock.instant().truncatedTo(ChronoUnit.MILLIS),expiry,candidateSlots);
            var view=inputs.save(envelope);var after=bind(task,command,jwt,false);
            if(!bound.materials().prepareSha().equals(after.materials().prepareSha()) || !bound.features().equals(after.features())) throw conflict();
            verify(task,view,jwt,false);return view;
        });
    }
    View read(String task,String id,Jwt jwt) {
        loader.require(task,jwt,ProductAccessPolicy.Action.READ);var view=inputs.read(task,id);
        verify(task,view,jwt,true);if(!view.equals(inputs.read(task,id))) throw conflict();
        verify(task,view,jwt,true);return view;
    }
    View consume(String task,String id,Jwt jwt) {
        loader.require(task,jwt,ProductAccessPolicy.Action.COMPARE);var view=inputs.read(task,id);
        if(!id.equals(inputs.current(task))) throw conflict();verify(task,view,jwt,false);return view;
    }
    private Bound bind(String task,Create c,Jwt jwt,boolean historical) {
        var directoryTask=loader.require(task,jwt,historical?ProductAccessPolicy.Action.READ:ProductAccessPolicy.Action.COMPARE);
        loader.require(task,jwt,ProductAccessPolicy.Action.READ);
        if(!historical && directoryTask.version()!=c.expectedTaskVersion()) throw conflict();
        var view=features.revision(task,c.featureRevisionId());var revision=view.revision();
        if(!view.packageSha().equals(c.packageSha()) || revision.revisionNumber()!=c.featureRevisionNumber()
                || !revision.expiresAt().isAfter(clock.instant())) throw conflict();
        if(!historical) {
            var current=features.current(task);
            if(current==null || !current.revisionId().equals(c.featureRevisionId()) || current.number()!=c.featureRevisionNumber()) throw conflict();
        }
        var preparation=features.preparation(task,revision.preparationId(),clock.instant());
        if(!preparation.sha().equals(revision.prepareSha())) throw ProductComparisonInputStore.corrupt();
        var materials=loader.load(task,preparation.command(),jwt,historical);
        if(!materials.materialSha().equals(preparation.materialSha()) || !materials.targetReady()) throw conflict();
        for(var feature:revision.features()) if(!feature.original().equals(ProductComparisonContracts.Span.of(
                materials.target().evidence(feature.characterStart(),feature.characterEnd())))) throw conflict();
        return new Bound(view,preparation,materials);
    }
    private void verify(String task,View view,Jwt jwt,boolean historical) {
        var e=view.input();if(!e.expiresAt().isAfter(clock.instant())) throw conflict();var bound=bind(task,e.command(),jwt,historical);
        if(!bound.features().equals(e.confirmedFeatures()) || !bound.preparation().id().equals(e.preparationId())
                || !bound.materials().materialSha().equals(e.materialSha())) throw conflict();
        if(!historical && !ProductComparisonJson.hash(e.currentPreparationSnapshot()).equals(bound.materials().prepareSha())) throw conflict();
        var snapshot=json.stored(e.currentPreparationSnapshot(),com.fasterxml.jackson.databind.JsonNode.class);
        if(!json.sha(snapshot.get("material")).equals(e.materialSha()) || !task.equals(snapshot.path("taskId").asText())
                || snapshot.path("taskVersion").asLong()!=e.taskVersion()
                || !json.canonical(snapshot.get("command")).equals(json.canonical(bound.preparation().command()))) throw ProductComparisonInputStore.corrupt();
        var candidates=bound.materials().documents().stream().filter(d->"CANDIDATE".equals(d.role())).map(d->new Candidate(d.key(),d.ordinal())).toList();
        if(!candidates.equals(e.candidates())) throw conflict();
        var after=bind(task,e.command(),jwt,historical);
        if(!bound.materials().prepareSha().equals(after.materials().prepareSha()) || !bound.features().equals(after.features())) throw conflict();
        var authority=loader.require(task,jwt,historical?ProductAccessPolicy.Action.READ:ProductAccessPolicy.Action.COMPARE);
        if(!historical)loader.require(task,jwt,ProductAccessPolicy.Action.READ);
        if(!authority.equals(after.materials().task()) || !e.expiresAt().isAfter(clock.instant())
                || !after.features().revision().expiresAt().isAfter(clock.instant())
                || !after.preparation().expiresAt().isAfter(clock.instant())
                || !after.materials().expiresAt().isAfter(clock.instant())) throw conflict();
    }
    private static void validate(Create c,String key) {
        if(c==null || c.expectedTaskVersion()<1 || blank(c.featureRevisionId()) || c.featureRevisionNumber()<1
                || c.packageSha()==null || !c.packageSha().matches("[a-f0-9]{64}")
                || !"PATENT_FEATURE_MATRIX_V1".equals(c.methodVersion()) || key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}")
                || (c.previousComparisonId()!=null && blank(c.previousComparisonId()))) throw invalid("COMPARISON_INPUT_INVALID");
    }
}
