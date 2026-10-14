package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;
import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.context.support.AbstractApplicationContext;

/** Independent HTTP fixture; all licence and identity bytes are component-owned inputs. */
final class ProductPromptCopyHttpFixture {
    final ProductPromptCreateApplicationTest.Fixture tracked;
    final ProductPromptWorkspaceAuthorizationFixture f;
    final String target=id(),copyRef="http-copy-permission.json",directoryRef="http-copy-inputs.json";
    final Receipt source;
    final ProductPromptCopyCommand command;
    final Map<String,Object> properties=new HashMap<>(),permission=new TreeMap<>();
    ProductPromptCopyHttpFixture(Path root)throws Exception{
        tracked=new ProductPromptCreateApplicationTest.Fixture(root);f=tracked.base;
        migrate(f.authoritySource,"identity-authority/V009__product_prompt_save_request.sql","identity-authority/V010__product_prompt_copy_request.sql");
        source=tracked.app().request(f.token,f.workspaceId,"http-source",tracked.input.command);
        f.revoke(Action.CREATE);f.grant(Action.READ);f.addWorkspace(target,f.organization);f.grant(target,Action.COPY);
        command=new ProductPromptCopyCommand(0,source.revisionId(),source.contentSha(),target,id());
        permission.putAll(tracked.input.permission);var src=f.bindings.get(f.workspaceId);var dst=f.bindings.get(target);
        var header=f.db.queryForMap("SELECT record_sha,body_until,audit_until FROM conp_revision WHERE revision_id=?",source.revisionId());
        permission.put("format","PROMPT_INPUT_COPY_PERMISSION/v1");permission.put("permissionId",id());permission.put("operation","COPY");
        permission.put("workspaceId",target);permission.put("policyRef",dst.policyRef());permission.put("policySha",dst.policySha());
        permission.put("contentCoverage","EXACT_PROMPT_REVISION_COPY");permission.put("contentSha256",source.contentSha());
        permission.put("generationSourceRef","PROMPT_REVISION:"+source.promptId()+":"+source.revisionId());
        permission.put("sourceWorkspaceId",f.workspaceId);permission.put("sourcePromptId",source.promptId());permission.put("sourceRevisionId",source.revisionId());
        permission.put("sourceRevisionNumber",source.generation());permission.put("sourceRecordSha",header.get("RECORD_SHA"));
        permission.put("sourceContentSha256",source.contentSha());permission.put("sourceBodyUntil",((Timestamp)header.get("BODY_UNTIL")).toInstant());
        permission.put("sourceAuditUntil",((Timestamp)header.get("AUDIT_UNTIL")).toInstant());permission.put("sourceScopeVersion",src.scopeVersion());
        permission.put("sourceRegistrySha",src.approvalSha());permission.put("sourcePolicyRef",src.policyRef());permission.put("sourcePolicySha",src.policySha());
        String permissionSha=f.write(copyRef,permission);
        String directorySha=f.write(directoryRef,Map.of("format","PROMPT_INPUT_PERMISSION_BINDINGS/v1","bindings",List.of(
                Map.of("sourceId",command.sourceId(),"permissionRef",copyRef,"permissionSha",permissionSha))));
        String bindingsRef="http-copy-workspaces.json",bindingsSha=f.write(bindingsRef,
                Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.copyOf(f.bindings.values())));
        properties.put("product.consultation.prompt.enabled","true");properties.put("product.consultation.prompt.copy-enabled","true");
        properties.put("product.identity.enabled","true");properties.put("product.identity.issuer",ISSUER);properties.put("product.identity.audience",AUDIENCE);
        properties.put("product.identity.master-key",f.masterKeyBase64);properties.put("product.identity.token-verification-window","PT5M");
        properties.put("product.consultation.prompt.evidence-root",root.toString());properties.put("product.consultation.prompt.bindings-ref",bindingsRef);
        properties.put("product.consultation.prompt.bindings-sha256",bindingsSha);properties.put("product.consultation.prompt.copy-input-permissions-ref",directoryRef);
        properties.put("product.consultation.prompt.copy-input-permissions-sha256",directorySha);
    }
    void register(AbstractApplicationContext context){
        context.addBeanFactoryPostProcessor(factory->{
            factory.registerSingleton("fixture",f);factory.registerSingleton("db",tracked.main);
            factory.registerSingleton("manager",tracked.mainManager);factory.registerSingleton("watermark",tracked.watermark);
        });
    }
    Map<String,Object> prepared(){return f.independent.queryForMap("SELECT * FROM idp_prompt_request WHERE operation='COPY'");}
    void pendingSource(){
        f.independent.update("UPDATE idp_prompt_control SET state='PENDING',intent_id=?,operation='SAVE',key_sha=?,request_sha=?,before_sha=scalar_sha,"
                +"expected_after_sha=?,target_generation=2,target_revision_id=?,intent_until=? WHERE prompt_id=?",
                id(),"a".repeat(64),"b".repeat(64),"c".repeat(64),id(),Timestamp.from(f.now.plusSeconds(300)),source.promptId());
    }
    void legacyInput(){
        properties.put("product.consultation.prompt.input-permissions-ref",tracked.input.directoryRef);
        properties.put("product.consultation.prompt.input-permissions-sha256",tracked.input.directorySha);
    }
    void revokeTargetCopy(){
        var receipt=f.receipts.get(target+":COPY");
        f.watermark.change(()->f.grants.revoke(receipt.grantId(),1,"component-maintainer",id(),ProductComparisonJson.hash(id())));
    }
    void refreshBindings()throws Exception{
        String sha=f.write("http-copy-workspaces.json",Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.copyOf(f.bindings.values())));
        properties.put("product.consultation.prompt.bindings-sha256",sha);
    }
}
