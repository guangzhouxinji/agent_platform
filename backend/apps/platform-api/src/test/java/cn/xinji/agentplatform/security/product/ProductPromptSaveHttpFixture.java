package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;
import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Component-owned existing draft, real stores and explicit SAVE permission for production assembly tests. */
final class ProductPromptSaveHttpFixture {
    final ProductPromptWorkspaceAuthorizationFixture f;
    final ProductPromptInputPermissionFixture input;
    final ProductPromptSaveCommand command;
    final Receipt initial;
    final Map<String,Object> properties=new HashMap<>();
    ProductPromptSaveHttpFixture(Path root,boolean upgrade)throws Exception{
        f=new ProductPromptWorkspaceAuthorizationFixture(root);f.grant(ProductPromptWorkspaceEvidence.Action.SAVE);
        migrate(f.authoritySource,"identity-authority/V008__product_prompt_create_request.sql");
        if(upgrade)migrate(f.authoritySource,"identity-authority/V009__product_prompt_save_request.sql");
        input=new ProductPromptInputPermissionFixture(f);var c=input.command;
        var revision=c.revision(id(),id(),new PromptRevision.Provenance("owned-first","original-creator",f.now,"original-creator",f.now,
                "CREATE_DRAFT",List.of(),null,f.bindings.get(f.workspaceId).policyRef()));
        var write=new Write(f.workspaceId,revision,0,Operation.CREATE,ProductPromptWorkspaceAuthorizationFixture.ISSUER,"original-creator",
                ProductComparisonJson.hash("first-key"),c.requestSha(f.workspaceId),f.now.plusSeconds(600),f.now.plusSeconds(1200));
        var prepared=f.mainTx.execute(status->f.store.prepare(write));
        var pending=new Pending(prepared.after().promptId(),f.workspaceId,c.kind(),0,null,"0".repeat(64),prepared.after().scalarSha(),
                prepared.header().revisionId(),id(),Operation.CREATE,write.keySha(),write.requestSha(),f.now.plusSeconds(300),write.bodyUntil(),write.auditUntil());
        f.authorityTx.executeWithoutResult(status->f.control.reserve(pending));
        initial=f.mainTx.execute(status->f.store.append(prepared));
        f.authorityTx.executeWithoutResult(status->f.control.complete(pending,prepared.after(),prepared.header(),initial));
        input.permission.put("operation","SAVE");input.install();
        command=new ProductPromptSaveCommand(1,initial.revisionId(),c.kind(),c.name(),c.body(),c.scenarioIds(),c.intentIds(),c.audienceRef(),c.language(),c.displayOrder(),c.variables(),c.sourceId());
        String sha=f.write("save-bindings.json",Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.copyOf(f.bindings.values())));
        properties.put("product.consultation.prompt.enabled","true");properties.put("product.consultation.prompt.save-enabled","true");
        properties.put("product.identity.enabled","true");properties.put("product.identity.issuer",ProductPromptWorkspaceAuthorizationFixture.ISSUER);
        properties.put("product.identity.audience",ProductPromptWorkspaceAuthorizationFixture.AUDIENCE);properties.put("product.identity.master-key",f.masterKeyBase64);
        properties.put("product.identity.token-verification-window","PT5M");properties.put("product.consultation.prompt.evidence-root",root.toString());
        properties.put("product.consultation.prompt.bindings-ref","save-bindings.json");properties.put("product.consultation.prompt.bindings-sha256",sha);
        properties.put("product.consultation.prompt.input-permissions-ref",input.directoryRef);properties.put("product.consultation.prompt.input-permissions-sha256",input.directorySha);
    }
}
