package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import cn.xinji.agentplatform.consultation.patentcomparison.OriginalClaimText;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;

/** One exact candidate's current originals, internal only; not a lease or model disclosure approval. */
final class ProductComparisonWorkerMaterials {
    record Loaded(ProductComparisonInputs.View input,OriginalClaimText target,
                  ProductComparisonDocuments.Loaded targetPatent,ProductComparisonDocuments.Loaded candidate,
                  ProductComparisonWorkerTaskAuthority.Authorized actor,Instant expiresAt) { }
    private final ProductComparisonWorkerTaskAuthority authority;
    private final ProductComparisonWorkerIntentAccess intents;
    private final ProductComparisonWorkerReadAuthority reads;
    private final ProductComparisonInputStore inputs;
    private final ProductComparisonTargetReader targets;
    private final ProductComparisonDocuments documents;
    private final ProductComparisonJson json;
    private final Clock clock;
    ProductComparisonWorkerMaterials(ProductComparisonWorkerTaskAuthority authority,ProductComparisonWorkerIntentAccess intents,
            ProductComparisonWorkerReadAuthority reads,ProductComparisonInputStore inputs,ProductComparisonTargetReader targets,
            ProductComparisonDocuments documents,ProductComparisonJson json,Clock clock) {
        this.authority=authority;this.intents=intents;this.reads=reads;this.inputs=inputs;this.targets=targets;
        this.documents=documents;this.json=json;this.clock=clock;
    }
    Loaded consume(HttpServletRequest request,String task,String comparison,String candidate,String inputSha,String delegation) {
        var actor=authority.require(request,task,delegation);
        var intent=intents.require(request,task,comparison,candidate,inputSha,delegation);
        var read=reads.require(actor);
        var view=inputs.read(task,comparison);
        if (!view.inputSha().equals(inputSha) || view.input().taskVersion()!=actor.taskVersion()) throw conflict();
        var e=view.input();JsonNode snapshot=json.stored(e.currentPreparationSnapshot(),JsonNode.class);
        var command=json.stored(json.canonical(snapshot.path("command")),Prepare.class);
        var refs=command.candidates().stream().filter(ref->candidate.equals(key(ref))).toList();
        if (refs.size()!=1) throw conflict();
        ProductComparisonDocuments.Loaded patent=null;OriginalClaimText target;
        Instant expiry=min(min(read.expiresAt(),intent.expiresAt()),e.expiresAt());
        if (command.target().messageId()!=null) {
            var scheme=targets.readDelegatedUserScheme(task,command.target().messageId(),actor.taskVersion(),actor,reads);
            target=scheme.original();expiry=min(expiry,scheme.expiresAt());
        } else {
            patent=documents.readDelegated(task,command.target().patent(),actor);
            target=patent.original();expiry=min(expiry,patent.authority().expiresAt());
            exactMaterial(snapshot.path("material").path("targetPatent"),patent.material());
        }
        if (!json.canonical(snapshot.path("material").path("target"))
                .equals(json.canonical(Span.of(target.evidence(0,target.text().length()))))) throw conflict();
        var loaded=documents.readDelegated(task,refs.getFirst(),actor);
        JsonNode expected=null;
        for(var row:snapshot.path("material").path("candidates")) if(candidate.equals(row.path("key").asText())) {
            if(expected!=null) throw conflict();expected=row;
        }
        exactMaterial(expected,loaded.material());expiry=min(expiry,loaded.authority().expiresAt());
        // A source or input change between reading target and candidate must not escape as a current bundle.
        var reloaded=documents.readDelegated(task,refs.getFirst(),actor);
        if (!sameDocument(loaded,reloaded)) throw conflict();
        if (patent!=null) {
            if (!sameDocument(patent,documents.readDelegated(task,command.target().patent(),actor))) throw conflict();
        } else {
            var after=targets.readDelegatedUserScheme(task,command.target().messageId(),actor.taskVersion(),actor,reads);
            if (!Span.of(after.original().evidence(0,after.original().text().length()))
                    .equals(Span.of(target.evidence(0,target.text().length())))) throw conflict();
        }
        if (!read.equals(reads.require(actor)) || !view.equals(inputs.read(task,comparison))
                || !intent.equals(intents.require(request,task,comparison,candidate,inputSha,delegation))) throw conflict();
        authority.recheck(request,task,delegation,actor);
        if (!expiry.isAfter(clock.instant())) throw conflict();
        return new Loaded(view,target,patent,loaded,actor,expiry);
    }
    /** Equality of freshly re-read facts, never Java identity of original parsing instances. */
    static boolean sameCurrent(Loaded before,Loaded after) {
        return before!=null && after!=null && before.input().equals(after.input())
                && before.actor().equals(after.actor()) && before.expiresAt().equals(after.expiresAt())
                && before.target().text().equals(after.target().text())
                && Span.of(before.target().evidence(0,before.target().text().length())).equals(
                        Span.of(after.target().evidence(0,after.target().text().length())))
                && (before.targetPatent()==null ? after.targetPatent()==null : after.targetPatent()!=null
                        && sameDocument(before.targetPatent(),after.targetPatent()))
                && sameDocument(before.candidate(),after.candidate());
    }
    private static boolean sameDocument(ProductComparisonDocuments.Loaded before,ProductComparisonDocuments.Loaded after) {
        return before.reference().equals(after.reference()) && before.material().equals(after.material())
                && before.authority().equals(after.authority());
    }
    private void exactMaterial(JsonNode expected,MaterialDocument actual) {
        if (expected==null || !json.canonical(expected).equals(json.canonical(actual))) throw conflict();
    }
    private static String key(DocumentRef ref) {
        return ProductComparisonJson.hash(ref.sourceId()+"\n"+ref.documentId()+"\n"+ref.documentVersion());
    }
    private static Instant min(Instant a,Instant b) {return a.isBefore(b)?a:b;}
    private static IllegalStateException conflict() {return new IllegalStateException("Current comparison originals or input changed");}
}
