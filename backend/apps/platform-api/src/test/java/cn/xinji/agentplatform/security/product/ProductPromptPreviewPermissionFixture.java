package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** Owned component TEST policy bytes only; no deployment approval, model or business-table writes. */
final class ProductPromptPreviewPermissionFixture {
    final ProductPromptWorkspaceAuthorizationFixture f;
    final String sourceId=id(),permissionRef="preview-permission-"+sourceId+".json",directoryRef="preview-directory-"+sourceId+".json";
    final Header header;
    final PromptRevision revision;
    final Map<String,Object> permission=new TreeMap<>();
    String permissionSha,directorySha;
    ProductPromptPreviewPermissionFixture(ProductPromptWorkspaceAuthorizationFixture f)throws Exception{this(f,ownedRevision(f));}
    private ProductPromptPreviewPermissionFixture(ProductPromptWorkspaceAuthorizationFixture f,PromptRevision revision)throws Exception{
        this(f,new Header(revision.reference().revisionId(),revision.reference().promptId(),revision.reference().version(),
                revision.reference().contentSha256(),ProductComparisonJson.hash("owned exact component record"),f.now,
                f.now.plusSeconds(600),f.now.plusSeconds(1200),"PRESENT"),revision);
    }
    ProductPromptPreviewPermissionFixture(ProductPromptWorkspaceAuthorizationFixture f,Header header,PromptRevision revision)throws Exception{
        this.f=f;this.header=header;this.revision=revision;var binding=f.bindings.get(f.workspaceId);
        permission.put("format","PROMPT_VARIABLE_TEST_PERMISSION/v1");permission.put("permissionId","preview-test:"+id());
        permission.putAll(provenance(f));permission.put("validUntil",f.now.plusSeconds(900));
        permission.put("issuer",ProductPromptWorkspaceAuthorizationFixture.ISSUER);permission.put("subject","member");
        permission.put("workspaceId",f.workspaceId);permission.put("organization",f.organization);permission.put("scopeVersion",1);
        permission.put("registrySha",binding.approvalSha());permission.put("policyRef",binding.policyRef());permission.put("policySha",binding.policySha());
        permission.put("promptId",header.promptId());permission.put("revisionId",header.revisionId());permission.put("revisionNumber",header.number());
        permission.put("recordSha",header.recordSha());permission.put("contentSha",header.contentSha());
        permission.put("schemaSha",ProductPromptPreviewValues.schemaSha(revision.variables()));
        permission.put("operation","PREVIEW");permission.put("mode","TEST");permission.put("inputMode","MANUAL_TEST_INPUT");
        permission.put("outputCoverage","REQUEST_MEMORY_ONLY");
        var sources=new ArrayList<Map<String,Object>>();
        for(var variable:revision.variables()){
            var source=provenance(f);source.put("name",variable.name());source.put("sourceRef",variable.sourceRef());
            source.put("evidenceId","sample-test:"+variable.name());source.put("purpose","TEST_SAMPLE");
            source.put("validUntil",f.now.plusSeconds(500));sources.add(source);
        }
        permission.put("variables",sources);install();
    }
    void install()throws Exception{permissionSha=f.write(permissionRef,permission);installDirectory();}
    void installBytes(byte[] bytes)throws Exception{
        Files.write(f.root.resolve(permissionRef),bytes);permissionSha=ProductCapturedArchive.digest(bytes);installDirectory();
    }
    void installDirectory()throws Exception{
        directorySha=f.write(directoryRef,Map.of("format","PROMPT_VARIABLE_TEST_BINDINGS/v1","bindings",List.of(binding())));
    }
    Map<String,String> binding(){return Map.of("sourceId",sourceId,"permissionRef",permissionRef,"permissionSha",permissionSha);}
    @SuppressWarnings("unchecked") Map<String,Object> variable(int index){return ((List<Map<String,Object>>)permission.get("variables")).get(index);}
    ProductPromptPreviewPermission adapter(){
        return new ProductPromptPreviewPermission(new ProductSourceEvidenceFiles(f.root,1048576),directoryRef,directorySha,f.clock,
                ProductPromptWorkspaceAuthorizationFixture.ISSUER);
    }
    ProductPromptPreviewPermission.Witness admit(ProductPromptWorkspaceAuthorization.Lease lease){
        return adapter().admit(lease,f.workspaceId,header,revision,sourceId);
    }
    static PromptRevision ownedRevision(ProductPromptWorkspaceAuthorizationFixture f){
        return revision(f,List.of(new VariableDefinition("ratio",VariableDefinition.Type.DECIMAL,false,"owned-ratio",true,new BigDecimal("1.00"),false)),"Ratio {{ratio}}");
    }
    static PromptRevision revision(ProductPromptWorkspaceAuthorizationFixture f,List<VariableDefinition> variables,String body){
        var ref=PromptRevision.referenceFor(id(),id(),1,PromptRevision.Kind.SYSTEM,"Owned TEST draft",body,Set.of(),Set.of(),"internal","zh-CN",0,variables);
        return new PromptRevision(ref,PromptRevision.Kind.SYSTEM,"Owned TEST draft",body,Set.of(),Set.of(),"internal","zh-CN",0,variables,
                new PromptRevision.Provenance("owned-component","member",f.now,"member",f.now,"CREATE_DRAFT",List.of(),null,f.bindings.get(f.workspaceId).policyRef()));
    }
    private static Map<String,Object> provenance(ProductPromptWorkspaceAuthorizationFixture f){
        var value=new TreeMap<String,Object>();value.put("source","Owned TEST component approval input only");
        value.put("decisionVersion","component-v1");value.put("responsibleParty","owned-component");
        value.put("decisionDate",LocalDate.now(ZoneOffset.UTC));value.put("status","ACTIVE");
        value.put("validFrom",f.now.minusSeconds(1));value.put("validUntil",f.now.plusSeconds(900));return value;
    }
}
