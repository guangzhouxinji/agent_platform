package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.Lease;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Request;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionTemplate;

/** Owned actual Hikari/H2 and TLS input; primitives only, never runtime admission. */
final class ProductPromptBodyClearStoreFixture implements AutoCloseable {
    final ProductPromptRegisterFixture f;
    final ProductPromptBodyClearStore store;
    final ProductPromptBodyClearReconciliation reconciliation;
    final String promptId=UUID.randomUUID().toString();
    final List<PreparedWrite> versions=new ArrayList<>();
    final List<ProductPromptRegistrationLease> reopened=new ArrayList<>();
    ProductPromptBodyClearStoreFixture(Path root,ProductComparisonStartMtlsEngineeringSupport.Tls tls,int count)throws Exception{
        f=new ProductPromptRegisterFixture(root,tls);f.app.register(f.request,f.workspaceId,"owned-workspace-register");
        store=new ProductPromptBodyClearStore(f.store,f.control,f.mapper,f.clock);
        reconciliation=new ProductPromptBodyClearReconciliation(store,f.clock);
        for(int i=0;i<count;i++)append(30+i*10,1000+i*100);
        f.advance(Duration.ofSeconds(30));f.mainFault.clear();f.authorityFault.clear();
    }
    PreparedWrite append(long bodySeconds,long auditSeconds){
        long number=versions.size()+1L;String revision=UUID.randomUUID().toString();String body="Owned text "+number+" {{value}}";
        var schema=List.of(new cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition("value",
                cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition.Type.STRING,false,"owned",true,"Owned default",false));
        var ref=PromptRevision.referenceFor(promptId,revision,number,PromptRevision.Kind.SYSTEM,"Owned draft",body,
                Set.of(),Set.of(),"internal","zh",0,schema);
        var origin=new PromptRevision.Provenance("owned-component",f.subject,f.now,f.subject,f.now,
                "OWNED_COMPONENT_DRAFT",List.of(),"owned-input",f.target.policyRef());
        var value=new PromptRevision(ref,PromptRevision.Kind.SYSTEM,"Owned draft",body,Set.of(),Set.of(),"internal","zh",0,schema,origin);
        var operation=number==1?Operation.CREATE:Operation.SAVE;
        var write=new Write(f.workspaceId,value,number-1,operation,"owned-component",f.subject,hash("key-"+number),hash(body),
                f.now.plusSeconds(bodySeconds),f.now.plusSeconds(auditSeconds));
        PreparedWrite prepared=new TransactionTemplate(f.mainManager).execute(status->f.store.prepare(write));
        Head before=prepared.before(),after=prepared.after();
        var pending=new Pending(promptId,f.workspaceId,after.kind(),number-1,before==null?null:before.revisionId(),
                before==null?"0".repeat(64):before.scalarSha(),after.scalarSha(),revision,UUID.randomUUID().toString(),operation,
                write.keySha(),write.requestSha(),f.now.plusSeconds(300),after.containerUntil(),after.controlUntil());
        new TransactionTemplate(f.authorityManager).executeWithoutResult(status->f.control.reserve(pending));
        new TransactionTemplate(f.mainManager).executeWithoutResult(status->f.store.append(prepared));
        new TransactionTemplate(f.authorityManager).executeWithoutResult(status->f.control.complete(pending,after,prepared.header(),prepared.receipt()));
        versions.add(prepared);return prepared;
    }
    ProductPromptBodyClearApproval approval(int index)throws Exception{
        Header target=versions.get(index).header();var value=f.read(f.registerRef);
        value.put("format","PROMPT_BODY_CLEAR_APPROVAL/v1");value.put("operation","BODY_CLEAR");
        value.put("traceCoverage","MINIMAL_BODY_CLEAR_SCALARS_AND_ORIGINAL_KEY");value.put("promptId",promptId);
        value.put("revisionId",target.revisionId());value.put("revisionNumber",target.number());value.put("recordSha",target.recordSha());
        value.put("bodyUntil",target.bodyUntil());value.put("auditUntil",target.auditUntil());
        String ref="clear-"+target.revisionId()+".json",sha=f.write(ref,value);
        var files=new ProductSourceEvidenceFiles(f.root,1048576);
        var registry=new ProductPromptBindingRegistry(files,f.bindingsRef,ProductCapturedArchive.digest(Files.readAllBytes(f.root.resolve(f.bindingsRef))));
        return new ProductPromptBodyClearApproval(files,registry,List.of(new ProductPromptBodyClearApproval.Pin(f.workspaceId,promptId,
                target.revisionId(),ref,sha)),f.clock);
    }
    ProductPromptBodyClearApproval.Witness witness(int index)throws Exception{
        return approval(index).admit(f.workspaceId,promptId,versions.get(index).header().revisionId());
    }
    ProductPromptBodyClearApplication application(int index,boolean fresh)throws Exception{
        var owner=fresh?f.newLease():f.lease;if(fresh)reopened.add(owner);
        var current=new ProductPromptBodyClearStore(f.store,f.control,f.mapper,f.clock);
        return new ProductPromptBodyClearApplication(approval(index),owner,current,new ProductPromptBodyClearReconciliation(current,f.clock));
    }
    <T>T with(int index,Function<Lease,T> callback)throws Exception{return f.lease.withLease(f.request,witness(index),callback);}
    <T>T withFresh(int index,Function<Lease,T> callback)throws Exception{
        var owner=f.newLease();reopened.add(owner);return owner.withLease(f.request,witness(index),callback);
    }
    Request prepare(Lease lease,String key){return lease.main(lease.expiresAt(),()->store.prepare(lease,store.key(lease,key)));}
    Request reserve(Lease lease,Request request){return lease.authority(store.mutationUntil(lease,request),()->store.reserve(lease,request));}
    void clear(Lease lease,Request request){lease.main(store.mutationUntil(lease,request),()->{store.clear(lease,request);return null;});}
    ProductPromptBodyClearRecords.Receipt settle(Lease lease,Request request){
        var observed=reconciliation.observe(lease,request);var proof=observed.exactAfter();
        return lease.authority(proof.cutoff(),()->store.complete(lease,request,proof));
    }
    Request find(Lease lease,String key){return lease.authority(lease.expiresAt(),()->store.find(lease,store.key(lease,key)));}
    String state(){return f.independent.queryForObject("SELECT state FROM idp_prompt_control WHERE prompt_id=?",String.class,promptId);}
    String bodyState(int index){return f.db.queryForObject("SELECT body_state FROM conp_revision WHERE revision_id=?",String.class,versions.get(index).header().revisionId());}
    int count(boolean main,String table){return (main?f.db:f.independent).queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Override public void close(){reopened.forEach(ProductPromptRegistrationLease::close);f.close();}
    private static String hash(String value){return ProductComparisonJson.hash(value);}
}
