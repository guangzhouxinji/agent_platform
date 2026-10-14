package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonPreparationLoader.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

/** Final prepare/explicit-confirm transaction, with fresh authority and originals on every request. */
final class ProductComparisonPreparation {
    private final ProductComparisonPreparationLoader loader;
    private final ProductComparisonStore store;
    private final TransactionTemplate transactions;
    private final ProductComparisonJson json;
    private final Clock clock;
    private final int maxBytes;
    ProductComparisonPreparation(ProductComparisonPreparationLoader loader,ProductComparisonStore store,
            TransactionTemplate transactions,ProductComparisonJson json,Clock clock,int maxBytes) {
        this.loader=loader;this.store=store;this.transactions=transactions;this.json=json;this.clock=clock;this.maxBytes=maxBytes;
    }
    PreparationView prepare(String taskId,Prepare command,Jwt jwt) {
        // Legacy internal callers, never an unkeyed HTTP path.
        return prepareInternal(taskId,command,null,jwt);
    }
    PreparationView prepare(String taskId,Prepare command,String key,Jwt jwt) {
        if(key==null||!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))throw invalid("PREPARATION_REQUEST_KEY_REQUIRED");
        return prepareInternal(taskId,command,key,jwt);
    }
    private PreparationView prepareInternal(String taskId,Prepare command,String key,Jwt jwt) {
        loader.require(taskId,jwt,ProductAccessPolicy.Action.READ);
        return transactions.execute(status->{
            store.lockTask(taskId);var loaded=loader.load(taskId,command,jwt,false);
            String issuer=jwt.getIssuer().toString(),subject=jwt.getSubject();
            String sha=key==null?null:json.sha(Map.of("taskId",taskId,"issuer",issuer,"subject",subject,"requestId",key,"command",command));
            var replay=key==null?null:store.preparationRequest(taskId,issuer,subject,key);
            String id;
            if(replay!=null){if(!sha.equals(replay.sha()))throw conflict();id=replay.id();
                var prior=store.preparation(taskId,id,clock.instant());if(!prior.sha().equals(loaded.prepareSha())||!prior.command().equals(command))throw conflict();}
            else {id=UUID.randomUUID().toString();store.savePreparation(id,loaded,issuer,subject,now());
                if(key!=null)store.savePrepareRequest(taskId,issuer,subject,key,sha,id);}
            same(loaded,loader.load(taskId,command,jwt,false));store.preparation(taskId,id,clock.instant());
            return view(id,loaded,key,sha);
        });
    }
    PreparationView readPreparation(String taskId,String id,Jwt jwt) {
        loader.require(taskId,jwt,ProductAccessPolicy.Action.READ);
        var prior=store.preparation(taskId,id,clock.instant());var loaded=loader.load(taskId,prior.command(),jwt,false);
        if(!prior.sha().equals(loaded.prepareSha()))throw conflict();var request=store.requestForPreparation(taskId,id);
        if(request==null)throw conflict();same(loaded,loader.load(taskId,prior.command(),jwt,false));
        return view(id,loaded,request.key(),request.sha());
    }
    private PreparationView view(String id,Loaded loaded,String key,String sha) {
        return new PreparationView(id,loaded.prepareSha(),loaded.task().version(),loaded.command().purpose(),
                Span.of(loaded.target().evidence(0,loaded.target().text().length())),loaded.target().text(),
                loaded.candidates().stream().map(d->new DocumentView(d.material(),d.original().text())).toList(),
                loaded.targetReady(),List.of(),loaded.expiresAt(),loaded.task().id(),key,sha);
    }
    FeatureView confirm(String taskId,Confirm command,String requestId,Jwt jwt) {
        if(command==null || command.expectedTaskVersion()<1 || blank(command.preparationId())
                || command.prepareSha()==null || !command.prepareSha().matches("[a-f0-9]{64}")
                || command.previousFeatureRevision()<0 || requestId==null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw invalid("CONFIRMATION_INPUT_INVALID");
        loader.require(taskId,jwt,ProductAccessPolicy.Action.WRITE);
        String issuer=jwt.getIssuer().toString(),subject=jwt.getSubject();
        String requestSha=json.sha(Map.of("taskId",taskId,"issuer",issuer,"subject",subject,"requestId",requestId,"command",command));
        return transactions.execute(status->{
            store.lockTask(taskId); var task=loader.require(taskId,jwt,ProductAccessPolicy.Action.WRITE);
            var prepared=store.preparation(taskId,command.preparationId(),clock.instant());
            if(task.version()!=command.expectedTaskVersion() || prepared.taskVersion()!=task.version()
                    || !prepared.sha().equals(command.prepareSha())) throw conflict();
            var loaded=loader.load(taskId,prepared.command(),jwt,false);
            if(!loaded.prepareSha().equals(prepared.sha())) throw conflict();
            var features=ProductComparisonFeatureValidation.features(command,loaded,maxBytes);
            var replay=store.replay(taskId,issuer,subject,requestId,requestSha);
            if(replay!=null) {
                same(loaded,loader.load(taskId,prepared.command(),jwt,false));
                return replay;
            }
            var before=store.current(taskId);
            if(command.previousFeatureRevision()!=(before==null?0:before.number())) throw conflict();
            var revision=new Revision(UUID.randomUUID().toString(),taskId,command.previousFeatureRevision()+1,
                    before==null?null:before.revisionId(),prepared.id(),prepared.sha(),features.getFirst().original().originalSha(),
                    issuer,subject,requestId,requestSha,command.userStatement()==null?"":command.userStatement(),now(),
                    prepared.expiresAt(),features);
            var result=store.save(revision,before);
            loader.require(taskId,jwt,ProductAccessPolicy.Action.WRITE);
            same(loaded,loader.load(taskId,prepared.command(),jwt,false));
            return result;
        });
    }
    FeatureView read(String taskId,String revisionId,Jwt jwt) {
        loader.require(taskId,jwt,ProductAccessPolicy.Action.READ);
        var view=store.revision(taskId,revisionId);
        if(!view.revision().expiresAt().isAfter(clock.instant())) throw conflict();
        var prepared=store.preparation(taskId,view.revision().preparationId(),clock.instant());
        var current=loader.load(taskId,prepared.command(),jwt,true);
        if(!current.materialSha().equals(prepared.materialSha())) throw conflict();
        for(var feature:view.revision().features()) {
            if(!feature.original().equals(Span.of(current.target().evidence(feature.characterStart(),feature.characterEnd())))) throw conflict();
        }
        loader.require(taskId,jwt,ProductAccessPolicy.Action.READ);
        var after=loader.load(taskId,prepared.command(),jwt,true);same(current,after);
        if(!view.equals(store.revision(taskId,revisionId))) throw conflict();
        return view;
    }
    private void same(Loaded before,Loaded after) {
        if(!before.prepareSha().equals(after.prepareSha())) throw conflict();
    }
    private Instant now() {return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
}
