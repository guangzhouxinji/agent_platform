package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Explicitly licensed project-authored inputs; genuine source maintenance HTTP, not authorization fixtures. */
final class ProductComparisonApprovedClaimsSource {
    final ProductComparisonRealSecurityEnvironment env;
    final String source = "approved-project-claims", task, subject;
    final Map<String,String> versions = new LinkedHashMap<>();
    final List<Map<String,Object>> mappings = new ArrayList<>();
    JsonNode admitted;
    ProductComparisonApprovedClaimsSource(ProductComparisonRealSecurityEnvironment env, String task, String subject) {
        this.env=env;this.task=task;this.subject=subject;
    }
    void register(Path approvalPath, Path workspace) throws Exception {
        JsonNode approval=env.json.readTree(Files.readAllBytes(approvalPath));
        Instant now=Instant.now();
        assertTrue(now.isAfter(Instant.parse(approval.path("validFrom").asText())));
        assertFalse(env.until.isAfter(Instant.parse(approval.path("validUntil").asText())));
        assertEquals(List.of("DISPLAY","COMPARISON"),env.json.convertValue(approval.path("allowedUses").path("CLAIMS"),List.class));
        Path inputRoot=workspace.resolve("data/consultation/dev-comparison-input-v1");
        byte[] manifest=Files.readAllBytes(inputRoot.resolve("manifest.json"));
        byte[] payload=Files.readAllBytes(inputRoot.resolve("input.json"));
        assertEquals(approval.path("input").path("manifestSha256").asText(),ProductCapturedArchive.digest(manifest));
        assertEquals(approval.path("input").path("payloadSha256").asText(),ProductCapturedArchive.digest(payload));
        JsonNode original=env.json.readTree(payload);
        String approved=file("source-owner-approval.json",Files.readAllBytes(approvalPath));
        String generated=now.toString(); List<Map<String,Object>> rows=new ArrayList<>(),raws=new ArrayList<>();
        for(JsonNode coordinate:approval.path("originalCoordinates")) {
            String pointer=coordinate.path("originalPointer").asText(),id=coordinate.path("recordId").asText();
            String text=original.at(pointer).asText();
            assertEquals(coordinate.path("originalTextUtf8Sha256").asText(),ProductCapturedArchive.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals("claim:1",coordinate.path("recordedLocator").asText());
            String ref=id+"-claims.json";
            String sha=file(ref,env.json.writeValueAsBytes(Map.of("claimsText","1. "+text)));
            String version="RAW_SHA256:"+sha;versions.put(id,version);
            rows.add(Map.of("sourceId",source,"sourceRecordId",id,"sourceVersion",version,"capturedAt",generated,
                    "sourceRef",ref,"detailRawFile",ref));
            raws.add(Map.of("type","DETAIL","file",ref,"sha256",sha));
            var token=ProductCapturedJsonFields.ranges(Files.readAllBytes(env.root.resolve(ref)),env.json).get("claimsText");
            mappings.add(Map.of("originalPointer",pointer,"originalTextSha",coordinate.path("originalTextUtf8Sha256").asText(),
                    "nativeRef",ref,"nativeSha",sha,"nativePointer","/claimsText","tokenStart",token[1],"tokenEnd",token[2],
                    "carrierPrefix","1. ","origin","PROJECT_AUTHORED_SYNTHETIC"));
        }
        assertEquals(2,rows.size());
        String records=file("claims-records.json",env.json.writeValueAsBytes(Map.of("datasetId","approved-claims","generatedAt",generated,"records",rows)));
        String capture=file("claims-manifest.json",env.json.writeValueAsBytes(Map.of("datasetId","approved-claims","generatedAt",generated,
                "source",Map.of("apiBaseUrl","urn:project-authored:DEV-CON-COMPARE-INPUT-01"),"recordsFile","claims-records.json",
                "recordsSha256",records,"recordCount",2,"rawResponses",raws)));
        String capability=file("claims-capability.json",env.json.writeValueAsBytes(Map.of("mappings",mappings,"origin","PROJECT_AUTHORED_SYNTHETIC",
                "professionalAssertion",false,"originalPayloadSha",approval.path("input").path("payloadSha256").asText())));
        var declaration=new ProductSourceCandidates.Declaration(source,"v1","approved-claims:"+generated,
                "urn:project-authored:DEV-CON-COMPARE-INPUT-01","current-project-user-source-owner-confirmation",
                "source-owner-approval.json",approved,Map.of("purpose",List.of("isolated-engineering")),List.of("CLAIMS"),
                Map.of("CLAIMS",List.of("DISPLAY","COMPARISON")),List.of(),true,false,false,false,true,now.minusSeconds(1),env.until);
        String base="/v2/consultation/source-contracts";
        env.post(env.maintainer,base+"/candidates",declaration,"claims-source",null,200);
        var verification=env.post(env.maintainer,base+"/candidates/"+source+"/v1/verify",
                Map.of("expectedVersion",1,"manifestRef","claims-manifest.json","manifestSha",capture),"claims-verify",null,200);
        String retention=ProductCapturedArchive.digest(Files.readAllBytes(env.root.resolve("engineering-retention.txt")));
        var decision=new ProductSourceAdmission.Decision("claims-decision-v1",source,"v1",verification.path("verificationId").asText(),env.organization,task,
                env.primary.queryForObject("SELECT scope_id FROM con3_data_scope WHERE task_id=?",String.class,task),
                "UNRESOLVED","source-owner-approval.json",approved,"claims-capability.json",capability,
                "owned-engineering-retention",1,retention,"current-project-user-source-owner-confirmation",env.until,
                Map.of("CLAIMS",List.of("DISPLAY","COMPARISON")));
        String decisionSha=file("claims-decision.json",env.json.writeValueAsBytes(decision));
        admitted=env.post(env.maintainer,base+"/"+source+"/v1/product-enable",
                Map.of("expectedVersion",1,"decisionRef","claims-decision.json","decisionSha",decisionSha),"claims-enable",null,200);
        assertEquals("PRODUCT_ENABLED",admitted.path("productStatus").asText());
        long taskVersion=env.primary.queryForObject("SELECT version FROM con3_task WHERE task_id=?",Long.class,task);
        var scope=env.post(env.maintainer,base+"/scopes/"+source+"/authorize",Map.of("taskId",task,"memberSubject",subject,
                "taskVersion",taskVersion,"sourceRowVersion",1,"expiresAt",env.until),"claims-display",null,200);
        assertTrue(scope.path("queryGrantId").isNull());
        assertFalse(scope.path("displayGrantId").asText().isBlank());
        assertEquals(scope,env.post(env.maintainer,base+"/scopes/"+source+"/authorize",Map.of("taskId",task,"memberSubject",subject,
                "taskVersion",taskVersion,"sourceRowVersion",1,"expiresAt",env.until),"claims-display",null,200));
        env.post(env.maintainer,base+"/scopes/"+source+"/comparison/authorize",
                new ProductComparisonScopeGrants.Input(task,subject,taskVersion,1,admitted.path("sourceEpoch").asLong(),
                        scope.path("scopeId").asText(),scope.path("scopeVersion").asLong(),Set.of("CLAIMS"),env.until,0),
                "claims-comparison",null,200);
        String output=System.getProperty("comparison.claims.evidence");
        if(output!=null)Files.writeString(Path.of(output+".source-"+task+".json"),env.json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("sourceReceipt",admitted,"scopeReceipt",scope,"taskId",task,"subject",subject,
                        "privateRoot",env.root.toString(),"retentionUntil",env.retentionUntil,"mappings",mappings)),StandardOpenOption.CREATE_NEW);
    }
    ProductComparisonContracts.DocumentRef document(String id) {
        return new ProductComparisonContracts.DocumentRef(source,id,versions.get(id),List.of(1),null,null,null,null);
    }
    String file(String name,byte[] bytes) throws Exception {
        Files.write(env.root.resolve(name),bytes,StandardOpenOption.CREATE_NEW);return ProductCapturedArchive.digest(bytes);
    }
}
