package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import java.math.BigDecimal;
import java.util.*;

/** Explicitly owned test permission bytes; never a runtime licence or deployment binding. */
final class ProductPromptInputPermissionFixture {
    final ProductPromptWorkspaceAuthorizationFixture f;
    final String sourceId=id(),permissionRef="input-permission.json",directoryRef="input-permission-directory.json";
    final ProductPromptCreateCommand command;
    final Map<String,Object> permission=new TreeMap<>();
    String permissionSha,directorySha;
    ProductPromptInputPermissionFixture(ProductPromptWorkspaceAuthorizationFixture f)throws Exception{
        this.f=f;
        command=new ProductPromptCreateCommand(0,PromptRevision.Kind.SYSTEM,"Owned draft"," Ratio {{ratio}} ",
                List.of("z","a"),List.of("second","first"),"internal","zh-CN",0,
                List.of(new VariableDefinition("ratio",VariableDefinition.Type.DECIMAL,false,"owned-ratio",true,new BigDecimal("1.00"),false)),sourceId);
        permission.putAll(f.base(f.workspaceId,f.organization));var binding=f.bindings.get(f.workspaceId);
        permission.put("format","PROMPT_INPUT_SAVE_PERMISSION/v1");permission.put("permissionId",id());
        permission.put("issuer",ProductPromptWorkspaceAuthorizationFixture.ISSUER);permission.put("subject","member");
        permission.put("operation","CREATE");permission.put("contentCoverage","PROJECT_AUTHORED_FULL_DRAFT");
        permission.put("traceCoverage","MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY");permission.put("contentSha256",command.contentSha());
        permission.put("generationSourceRef","source:项目原稿/版本 1");permission.put("bodyUntil",f.now.plusSeconds(600));
        permission.put("auditUntil",f.now.plusSeconds(1200));permission.put("validUntil",f.now.plusSeconds(1500));
        permission.put("policyRef",binding.policyRef());permission.put("policySha",binding.policySha());install();
    }
    void install()throws Exception{
        permissionSha=f.write(permissionRef,permission);
        directorySha=f.write(directoryRef,Map.of("format","PROMPT_INPUT_PERMISSION_BINDINGS/v1","bindings",List.of(
                Map.of("sourceId",sourceId,"permissionRef",permissionRef,"permissionSha",permissionSha))));
    }
    ProductPromptInputPermission adapter(){return new ProductPromptInputPermission(new ProductSourceEvidenceFiles(f.root,1048576),directoryRef,directorySha,f.clock,ProductPromptWorkspaceAuthorizationFixture.ISSUER);}
    ProductPromptCreateRequest prepared(ProductPromptWorkspaceAuthorization.Lease lease){
        var permission=adapter();var witness=permission.forWrite(lease,f.workspaceId,command);var evidence=witness.evidence();
        var provenance=new PromptRevision.Provenance(evidence.generationSourceRef(),lease.subject(),f.now,lease.subject(),f.now,
                "CREATE_DRAFT",List.of(),evidence.inputPermissionRef(),evidence.policyRef());
        var revision=command.revision(id(),id(),provenance);
        var write=new Write(f.workspaceId,revision,0,Operation.CREATE,ProductPromptWorkspaceAuthorizationFixture.ISSUER,lease.subject(),
                ProductComparisonJson.hash("original-key"),command.requestSha(f.workspaceId),witness.bodyLimit(),witness.auditLimit());
        var prepared=f.store.prepare(write);
        var pending=new Pending(prepared.after().promptId(),f.workspaceId,command.kind(),0,null,"0".repeat(64),prepared.after().scalarSha(),
                prepared.after().revisionId(),id(),Operation.CREATE,write.keySha(),write.requestSha(),f.now.plusSeconds(300),write.bodyUntil(),write.auditUntil());
        return new ProductPromptCreateRequestStore(f.control,f.mapper,f.clock).prepared(pending,prepared,evidence);
    }
}
