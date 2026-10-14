package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.*;
import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.security.oauth2.jwt.*;

/** Real dual-store application inputs; approvals are owned synthetic component data only. */
final class ProductPromptCopyApplicationFixture {
    final ProductPromptCreateApplicationTest.Fixture f;
    final String target=id();
    final Receipt source;
    ProductPromptBindingRegistry registry;
    final List<Map<String,String>> copyPermissions=new ArrayList<>(),savePermissions=new ArrayList<>();
    final Map<String,Map<String,Object>> permissionValues=new HashMap<>();
    String copyDirectorySha,saveDirectorySha;
    final ProductPromptCopyCommand command;

    ProductPromptCopyApplicationFixture(Path root)throws Exception{
        f=new ProductPromptCreateApplicationTest.Fixture(root);
        migrate(f.base.authoritySource,"identity-authority/V009__product_prompt_save_request.sql","identity-authority/V010__product_prompt_copy_request.sql");
        source=f.app().request(f.base.token,f.base.workspaceId,"source-create",f.input.command);
        f.base.addWorkspace(target,f.base.organization);f.base.grant(Action.READ);f.base.grant(target,Action.COPY);
        refreshRegistry();command=permitCopy(source,target);
    }
    void refreshRegistry()throws Exception{
        String ref="copy-app-workspace-bindings.json";
        String sha=f.base.write(ref,Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.copyOf(f.base.bindings.values())));
        registry=new ProductPromptBindingRegistry(new ProductSourceEvidenceFiles(f.base.root,1048576),ref,sha);
    }
    ProductPromptWorkspaceAuthorization authorization(){return new ProductPromptWorkspaceAuthorization(f.store,f.control,f.mainManager,
            registry.evidence(),f.watermark,f.access,f.decoder,ISSUER,f.base.clock);}
    ProductPromptCopyApplication app(){
        var input=new ProductPromptCopyInputPermission(new ProductSourceEvidenceFiles(f.base.root,1048576),"copy-app-inputs.json",copyDirectorySha,f.base.clock,ISSUER);
        return new ProductPromptCopyApplication(f.store,f.control,f.requests,authorization(),input,registry,f.mainManager,f.authorityManager,f.base.clock);
    }
    ProductPromptCopyCommand permitCopy(Receipt from,String destination)throws Exception{
        String inputId=id(),ref="copy-app-input-"+inputId+".json";
        var command=new ProductPromptCopyCommand(0,from.revisionId(),from.contentSha(),destination,inputId);
        var values=new TreeMap<String,Object>(f.input.permission);
        var src=f.base.bindings.get(from.workspaceId());var dst=f.base.bindings.get(destination);
        Map<String,Object> header=f.base.db.queryForMap("SELECT record_sha,body_until,audit_until FROM conp_revision WHERE revision_id=?",from.revisionId());
        values.put("format","PROMPT_INPUT_COPY_PERMISSION/v1");values.put("permissionId",id());values.put("operation","COPY");
        values.put("workspaceId",destination);values.put("organization",dst.organization());values.put("policyRef",dst.policyRef());values.put("policySha",dst.policySha());
        values.put("contentCoverage","EXACT_PROMPT_REVISION_COPY");values.put("contentSha256",from.contentSha());
        values.put("generationSourceRef","PROMPT_REVISION:"+from.promptId()+":"+from.revisionId());
        values.put("sourceWorkspaceId",from.workspaceId());values.put("sourcePromptId",from.promptId());values.put("sourceRevisionId",from.revisionId());
        values.put("sourceRevisionNumber",from.generation());values.put("sourceRecordSha",header.get("RECORD_SHA"));
        values.put("sourceContentSha256",from.contentSha());values.put("sourceBodyUntil",((Timestamp)header.get("BODY_UNTIL")).toInstant());
        values.put("sourceAuditUntil",((Timestamp)header.get("AUDIT_UNTIL")).toInstant());values.put("sourceScopeVersion",src.scopeVersion());
        values.put("sourceRegistrySha",src.approvalSha());values.put("sourcePolicyRef",src.policyRef());values.put("sourcePolicySha",src.policySha());
        String sha=f.base.write(ref,values);permissionValues.put(inputId,values);
        copyPermissions.add(Map.of("sourceId",inputId,"permissionRef",ref,"permissionSha",sha));
        copyDirectorySha=f.base.write("copy-app-inputs.json",Map.of("format","PROMPT_INPUT_PERMISSION_BINDINGS/v1","bindings",copyPermissions));
        return command;
    }
    ProductPromptSaveCommand permitSave(Receipt base,String subject,String body)throws Exception{
        var c=f.input.command;String input=id(),ref="copy-app-save-"+input+".json";
        var command=new ProductPromptSaveCommand(base.generation(),base.revisionId(),c.kind(),"Saved copy",body,c.scenarioIds(),c.intentIds(),
                c.audienceRef(),c.language(),c.displayOrder(),c.variables(),input);
        var values=new TreeMap<String,Object>(f.input.permission);var binding=f.base.bindings.get(base.workspaceId());
        values.put("permissionId",id());values.put("operation","SAVE");values.put("subject",subject);values.put("workspaceId",base.workspaceId());
        values.put("organization",binding.organization());values.put("policyRef",binding.policyRef());values.put("policySha",binding.policySha());
        values.put("contentSha256",command.contentSha());values.put("generationSourceRef","source:new-draft/"+input);
        values.put("validFrom",f.base.now.minusSeconds(1));values.put("validUntil",f.base.now.plusSeconds(1500));
        values.put("bodyUntil",f.base.now.plusSeconds(600));values.put("auditUntil",f.base.now.plusSeconds(1200));
        String sha=f.base.write(ref,values);savePermissions.add(Map.of("sourceId",input,"permissionRef",ref,"permissionSha",sha));
        saveDirectorySha=f.base.write("copy-app-save-inputs.json",Map.of("format","PROMPT_INPUT_PERMISSION_BINDINGS/v1","bindings",savePermissions));
        return command;
    }
    ProductPromptSaveApplication save(){
        var input=new ProductPromptInputPermission(new ProductSourceEvidenceFiles(f.base.root,1048576),"copy-app-save-inputs.json",saveDirectorySha,f.base.clock,ISSUER);
        return new ProductPromptSaveApplication(f.store,f.control,f.requests,authorization(),input,registry,f.mainManager,f.authorityManager,f.base.clock);
    }
    Receipt copy(String key){return app().request(f.base.token,source.workspaceId(),source.promptId(),key,command);}
    Receipt lookup(String key){return app().lookup(f.base.token,source.workspaceId(),target,key);}
    String writer(String subject){
        String sid=id(),jti=id(),kid=f.base.db.queryForObject("SELECT active_kid FROM id2_key_control WHERE issuer=?",String.class,ISSUER);
        f.base.db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) VALUES(?,?,?,'encoded','ACTIVE',1,CURRENT_TIMESTAMP)",ISSUER,subject,subject);
        String token=new NimbusJwtEncoder(f.base.keys).encode(JwtEncoderParameters.from(JwtClaimsSet.builder().issuer(ISSUER).subject(subject).audience(List.of(AUDIENCE))
                .issuedAt(f.base.now).notBefore(f.base.now.minusSeconds(1)).expiresAt(f.base.now.plusSeconds(3600)).id(jti).claim("sid",sid).build())).getTokenValue();
        f.base.db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)",sid,jti,ISSUER,subject,"registered",kid,Timestamp.from(f.base.now),Timestamp.from(f.base.now.plusSeconds(3600)),"ACTIVE",null);
        String scope="PROMPT_WORKSPACE:"+target+":1";
        f.base.watermark.change(()->f.base.grants.issue(new ProductGrants.Scope(ISSUER,subject,f.base.organization,"CONSULTATION",scope,scope,"PROMPT_WORKSPACE",target+":SAVE",1,"WRITE","TASK_WORK"),
                f.base.now.plusSeconds(1700),0,"component-maintainer",id(),ProductComparisonJson.hash(id())));
        return token;
    }
    Map<String,Object> prepared(){return f.base.independent.queryForMap("SELECT * FROM idp_prompt_request WHERE operation='COPY'");}
    void pendingSource()throws Exception{
        f.base.grant(Action.SAVE);var next=permitSave(source,"member","Pending source {{ratio}}");
        f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->save().request(f.base.token,source.promptId(),"source-pending",next)).isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThat(f.mainSource.faultTriggered).isTrue();
    }
}
