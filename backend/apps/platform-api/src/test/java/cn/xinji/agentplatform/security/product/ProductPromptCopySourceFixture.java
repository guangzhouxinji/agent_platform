package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Owned exact source and COPY permission inputs for source/permission component tests only. */
final class ProductPromptCopySourceFixture {
    final ProductPromptWorkspaceAuthorizationFixture f;
    final String targetWorkspace=id(),sourceId=id(),permissionRef="copy-input.json",directoryRef="copy-input-directory.json";
    final PromptRevision revision;
    final Header header;
    final Head head;
    final ProductPromptCopyCommand command;
    final Map<String,Object> permission=new TreeMap<>();
    String permissionSha,directorySha;
    ProductPromptCopySourceFixture(Path root)throws Exception{
        f=new ProductPromptWorkspaceAuthorizationFixture(root);f.addWorkspace(targetWorkspace,f.organization);
        f.grant(Action.READ);f.grant(targetWorkspace,Action.COPY);
        var c=new ProductPromptInputPermissionFixture(f).command;
        revision=c.revision(id(),id(),new PromptRevision.Provenance("owned-source","source-author",f.now,"source-author",f.now,
                "CREATE_DRAFT",List.of(),null,f.bindings.get(f.workspaceId).policyRef()));
        var write=new Write(f.workspaceId,revision,0,Operation.CREATE,ProductPromptWorkspaceAuthorizationFixture.ISSUER,"source-author",
                ProductComparisonJson.hash("source-key"),c.requestSha(f.workspaceId),f.now.plusSeconds(600),f.now.plusSeconds(1200));
        var prepared=f.mainTx.execute(status->f.store.prepare(write));head=prepared.after();header=prepared.header();
        var pending=new Pending(head.promptId(),f.workspaceId,revision.kind(),0,null,"0".repeat(64),head.scalarSha(),header.revisionId(),id(),Operation.CREATE,
                write.keySha(),write.requestSha(),f.now.plusSeconds(300),write.bodyUntil(),write.auditUntil());
        f.authorityTx.executeWithoutResult(status->f.control.reserve(pending));
        var receipt=f.mainTx.execute(status->f.store.append(prepared));
        f.authorityTx.executeWithoutResult(status->f.control.complete(pending,head,header,receipt));
        command=new ProductPromptCopyCommand(0,header.revisionId(),header.contentSha(),targetWorkspace,sourceId);
        permission.putAll(f.base(targetWorkspace,f.organization));var target=f.bindings.get(targetWorkspace);var source=f.bindings.get(f.workspaceId);
        permission.put("format","PROMPT_INPUT_COPY_PERMISSION/v1");permission.put("permissionId",id());
        permission.put("issuer",ProductPromptWorkspaceAuthorizationFixture.ISSUER);permission.put("subject","member");
        permission.put("operation","COPY");permission.put("contentCoverage","EXACT_PROMPT_REVISION_COPY");
        permission.put("traceCoverage","MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY");permission.put("contentSha256",header.contentSha());
        permission.put("generationSourceRef","PROMPT_REVISION:"+head.promptId()+":"+header.revisionId());
        permission.put("bodyUntil",f.now.plusSeconds(1000));permission.put("auditUntil",f.now.plusSeconds(1400));permission.put("validUntil",f.now.plusSeconds(1500));
        permission.put("policyRef",target.policyRef());permission.put("policySha",target.policySha());
        permission.put("sourceWorkspaceId",f.workspaceId);permission.put("sourcePromptId",head.promptId());permission.put("sourceRevisionId",header.revisionId());
        permission.put("sourceRevisionNumber",header.number());permission.put("sourceRecordSha",header.recordSha());permission.put("sourceContentSha256",header.contentSha());
        permission.put("sourceBodyUntil",header.bodyUntil());permission.put("sourceAuditUntil",header.auditUntil());
        permission.put("sourceScopeVersion",source.scopeVersion());permission.put("sourceRegistrySha",source.approvalSha());
        permission.put("sourcePolicyRef",source.policyRef());permission.put("sourcePolicySha",source.policySha());install();
    }
    ProductPromptCopySourceSnapshot snapshots(){return new ProductPromptCopySourceSnapshot(f.store,f.control,f.clock);}
    ProductPromptCopySourceSnapshot.Snapshot snapshot(ProductPromptWorkspaceAuthorization.Lease lease){
        return snapshots().load(lease,f.workspaceId,head.promptId(),header.revisionId(),header.contentSha(),id());
    }
    ProductPromptCopyInputPermission adapter(){
        return new ProductPromptCopyInputPermission(new ProductSourceEvidenceFiles(f.root,1048576),directoryRef,directorySha,f.clock,ProductPromptWorkspaceAuthorizationFixture.ISSUER);
    }
    void install()throws Exception{
        permissionSha=f.write(permissionRef,permission);
        directorySha=f.write(directoryRef,Map.of("format","PROMPT_INPUT_PERMISSION_BINDINGS/v1","bindings",List.of(
                Map.of("sourceId",sourceId,"permissionRef",permissionRef,"permissionSha",permissionSha))));
    }
    ProductPromptCreateRequest prepared(ProductPromptWorkspaceAuthorization.Lease lease){
        String target=id(),revisionId=id();
        var snapshot=snapshots().load(lease,f.workspaceId,head.promptId(),header.revisionId(),header.contentSha(),target);
        var witness=adapter().forWrite(lease,command,snapshot);var evidence=witness.evidence();var source=snapshot.revision();
        var value=new PromptRevision(new PromptRevision.Reference(target,revisionId,1,header.contentSha()),source.kind(),source.name(),source.body(),
                source.scenarioIds(),source.intentIds(),source.audienceRef(),source.language(),source.displayOrder(),source.variables(),
                new PromptRevision.Provenance(evidence.generationSourceRef(),lease.subject(),f.now,lease.subject(),f.now,"COPY_DRAFT",List.of(),
                        evidence.inputPermissionRef(),evidence.policyRef()));
        var write=new Write(targetWorkspace,value,0,Operation.COPY,lease.issuer(),lease.subject(),ProductComparisonJson.hash("copy-original"),
                command.requestSha(f.workspaceId,head.promptId()),witness.bodyLimit(),witness.auditLimit());
        var prepared=f.store.prepare(write);
        var pending=new Pending(target,targetWorkspace,value.kind(),0,null,"0".repeat(64),prepared.after().scalarSha(),revisionId,id(),Operation.COPY,
                write.keySha(),write.requestSha(),f.now.plusSeconds(300),write.bodyUntil(),write.auditUntil());
        return new ProductPromptCreateRequestStore(f.control,f.mapper,f.clock).prepared(pending,prepared,evidence,snapshot.source());
    }
    void sourcePending(){
        f.independent.update("UPDATE idp_prompt_control SET state='PENDING',intent_id=?,operation='SAVE',key_sha=?,request_sha=?,before_sha=scalar_sha,"
                +"expected_after_sha=?,target_generation=generation+1,target_revision_id=?,intent_until=? WHERE prompt_id=?",id(),
                "a".repeat(64),"b".repeat(64),"c".repeat(64),id(),java.sql.Timestamp.from(f.now.plusSeconds(100)),head.promptId());
    }
    void advanceSource(){
        var old=revision;String revisionId=id();
        var ref=PromptRevision.referenceFor(head.promptId(),revisionId,2,old.kind(),old.name(),old.body()+" updated",old.scenarioIds(),old.intentIds(),
                old.audienceRef(),old.language(),old.displayOrder(),old.variables());
        var value=new PromptRevision(ref,old.kind(),old.name(),old.body()+" updated",old.scenarioIds(),old.intentIds(),old.audienceRef(),old.language(),old.displayOrder(),
                old.variables(),new PromptRevision.Provenance("owned-source-next","source-author",old.provenance().createdAt(),"source-author",f.now,
                "SAVE_DRAFT",List.of(),null,old.provenance().retentionPolicyRef()));
        var write=new Write(f.workspaceId,value,1,Operation.SAVE,ProductPromptWorkspaceAuthorizationFixture.ISSUER,"source-author",
                ProductComparisonJson.hash("source-next-key"),ProductComparisonJson.hash("source-next-request"),f.now.plusSeconds(650),f.now.plusSeconds(1250));
        var prepared=f.mainTx.execute(status->f.store.prepare(write));
        var pending=new Pending(head.promptId(),f.workspaceId,old.kind(),1,head.revisionId(),head.scalarSha(),prepared.after().scalarSha(),revisionId,id(),Operation.SAVE,
                write.keySha(),write.requestSha(),f.now.plusSeconds(300),prepared.after().containerUntil(),prepared.after().controlUntil());
        f.authorityTx.executeWithoutResult(status->f.control.reserve(pending));
        f.mainTx.executeWithoutResult(status->f.store.append(prepared));
        f.authorityTx.executeWithoutResult(status->f.control.complete(pending,prepared.after(),prepared.header(),prepared.receipt()));
    }
}
