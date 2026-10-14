package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real two-store COPY preparation and reconciliation, without an application authorization claim. */
final class ProductPromptCopyRequestFixture {
    record Material(PreparedWrite prepared,ProductPromptCreateRequest request) { }
    final ProductPromptCreateRequestFixture base;
    final ProductPromptCreateReconciliation reconciliation;
    final TransactionTemplate suspended;
    final Material source;

    ProductPromptCopyRequestFixture()throws Exception{
        base=new ProductPromptCreateRequestFixture();
        ProductPromptCreateRequestFixture.migrate(base.authoritySource,"db/identity-authority/V009__product_prompt_save_request.sql");
        ProductPromptCreateRequestFixture.migrate(base.authoritySource,"db/identity-authority/V010__product_prompt_copy_request.sql");
        reconciliation=new ProductPromptCreateReconciliation(base.store,base.controls,base.clock);
        suspended=new TransactionTemplate(base.mainManager);suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var initial=base.write("author",hash("source-key"),"Original source body");
        var write=new Write(initial.workspaceId(),initial.revision(),0,Operation.CREATE,initial.issuer(),initial.subject(),initial.keySha(),
                initial.requestSha(),base.now.plusSeconds(300),base.now.plusSeconds(900));
        var prepared=base.mainTx.execute(status->base.store.prepare(write));
        source=new Material(prepared,prepare(prepared,base.evidence,null));
        reserve(source);append(source);finish(source);
    }

    Material copy(String key){
        var original=source.prepared().write().revision();var header=source.prepared().header();
        var copySource=new ProductPromptCreateRequest.CopySource(base.workspace.id(),header.promptId(),header.revisionId(),header.number(),
                header.recordSha(),header.bodyUntil(),header.auditUntil());
        var evidence=copyEvidence(copySource);
        var origin=new PromptRevision.Provenance(evidence.generationSourceRef(),"copier",base.now,"copier",base.now,"COPY_DRAFT",List.of(),
                evidence.inputPermissionRef(),base.workspace.policyRef());
        var ref=PromptRevision.referenceFor(id(),id(),1,original.kind(),original.name(),original.body(),original.scenarioIds(),original.intentIds(),
                original.audienceRef(),original.language(),original.displayOrder(),original.variables());
        var revision=new PromptRevision(ref,original.kind(),original.name(),original.body(),original.scenarioIds(),original.intentIds(),
                original.audienceRef(),original.language(),original.displayOrder(),original.variables(),origin);
        var write=new Write(base.workspace.id(),revision,0,Operation.COPY,"issuer","copier",hash(key),hash("copy:"+key),header.bodyUntil(),header.auditUntil());
        var prepared=base.mainTx.execute(status->base.store.prepare(write));
        return new Material(prepared,prepare(prepared,evidence,copySource));
    }

    Material save(Material prior,String key){
        var old=prior.prepared().write().revision();var oldOrigin=old.provenance();
        var head=base.mainTx.execute(status->base.store.head(old.reference().promptId(),false));
        var ref=PromptRevision.referenceFor(head.promptId(),id(),head.generation()+1,old.kind(),old.name(),old.body(),old.scenarioIds(),old.intentIds(),
                old.audienceRef(),old.language(),old.displayOrder(),old.variables());
        var origin=new PromptRevision.Provenance(oldOrigin.generationSourceRef(),oldOrigin.createdBy(),oldOrigin.createdAt(),"editor",base.now,
                "SAVE_DRAFT",List.of(),oldOrigin.contentApprovalRef(),oldOrigin.retentionPolicyRef());
        var revision=new PromptRevision(ref,old.kind(),old.name(),old.body(),old.scenarioIds(),old.intentIds(),old.audienceRef(),old.language(),
                old.displayOrder(),old.variables(),origin);
        var write=new Write(base.workspace.id(),revision,head.generation(),Operation.SAVE,"issuer","editor",hash(key),hash("save:"+key),
                prior.prepared().header().bodyUntil(),prior.prepared().header().auditUntil());
        var prepared=base.mainTx.execute(status->base.store.prepare(write));
        return new Material(prepared,prepare(prepared,prior.request().evidence(),null));
    }

    ProductPromptCreateRequest prepare(PreparedWrite value,ProductPromptCreateRequest.Evidence evidence,ProductPromptCreateRequest.CopySource source){
        var r=value.receipt();var h=value.after();var before=value.before();
        var pending=new Pending(r.promptId(),r.workspaceId(),h.kind(),value.write().expectedGeneration(),before==null?null:before.revisionId(),
                before==null?"0".repeat(64):before.scalarSha(),h.scalarSha(),r.revisionId(),id(),r.operation(),r.keySha(),r.requestSha(),
                base.now.plusSeconds(60),h.containerUntil(),h.controlUntil());
        return base.requests.prepared(pending,value,evidence,source);
    }
    ProductPromptCreateRequest.Evidence copyEvidence(ProductPromptCreateRequest.CopySource source){
        var original=base.evidence;
        return new ProductPromptCreateRequest.Evidence(original.scopeVersion(),original.registrySha(),original.approvalRef(),original.approvalSha(),
                original.policyRef(),original.policySha(),"copy-permission",hash("copy-permission"),
                "PROMPT_REVISION:"+source.sourcePromptId()+":"+source.sourceRevisionId());
    }
    void reserve(Material value){base.reserve(value.request());}
    void append(Material value){base.mainTx.executeWithoutResult(status->base.store.append(value.prepared()));}
    ProductPromptCreateRequest find(Material value){return base.find(value.request().key());}
    ProductPromptCreateReconciliation.Observation observe(ProductPromptCreateRequest value){
        return base.mainTx.execute(status->reconciliation.observe(base.workspace,value));
    }
    Receipt finish(Material value){
        return base.mainTx.execute(status->{
            base.store.workspace(base.workspace.id(),true);
            var current=suspended.execute(outer->base.authorityTx.execute(inner->base.requests.find(value.request().key())));
            var proof=reconciliation.observe(base.workspace,current).exactAfter();
            return suspended.execute(outer->base.authorityTx.execute(inner->base.requests.complete(proof)));
        });
    }
    static String id(){return UUID.randomUUID().toString();}
    static String hash(String value){return ProductComparisonJson.hash(value);}
}
