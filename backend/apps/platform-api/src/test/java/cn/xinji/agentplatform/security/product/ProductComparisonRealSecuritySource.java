package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Owned original metadata only, not a patent, claims input, licensed external corpus or gold sample. */
final class ProductComparisonRealSecuritySource {
    final ProductComparisonRealSecurityEnvironment env;
    final String source="owned-security-material",version="owned:one",task,subject;
    ProductComparisonRealSecuritySource(ProductComparisonRealSecurityEnvironment env,String task,String subject){this.env=env;this.task=task;this.subject=subject;}
    Map<String,Object> verifyEnableScopeAndWithdraw(JsonNode goal,String token) throws Exception {
        String contract="Owned engineering source created in this test. TITLE metadata only. Permit QUERY/DISPLAY/COMPARISON metadata in owned isolated environment. No model disclosure, professional assertion or external business rights. Expires with engineering Task.";
        String rights=file("owned-rights.txt",contract),capability=file("owned-capability.txt","One owned engineering record, stable raw SHA, manual withdrawal; no claims or professional completeness.");
        String original=env.json.writeValueAsString(Map.of("sourceId",source,"title","Owned engineering security boundary record","purpose","HTTPS source control engineering validation only"));
        String raw=file("owned-original.json",original);
        Map<String,Object> row=Map.of("sourceId",source,"sourceRecordId","owned-record","sourceVersion","owned-record-v1","capturedAt",Instant.now().toString(),"sourceRef","owned-original.json","detailRawFile","owned-original.json","title","Owned engineering security boundary record");
        String records=file("owned-records.json",env.json.writeValueAsString(Map.of("datasetId","owned","generatedAt","one","records",List.of(row))));
        String manifest=file("owned-manifest.json",env.json.writeValueAsString(Map.of("datasetId","owned","generatedAt","one","source",Map.of("apiBaseUrl","owned-engineering"),"recordsFile","owned-records.json","recordsSha256",records,"recordCount",1,"rawResponses",List.of(Map.of("type","DETAIL","file","owned-original.json","sha256",raw)))));
        var declaration=new ProductSourceCandidates.Declaration(source,"v1",version,"owned-engineering","owned-engineering-maintainer",
                "owned-rights.txt",rights,Map.of("purpose",List.of("engineering-security")),List.of("TITLE"),Map.of("TITLE",List.of("QUERY","DISPLAY","COMPARISON")),List.of("TITLE"),false,false,true,true,true,Instant.now().minusSeconds(1),env.until);
        String base="/v2/consultation/source-contracts/candidates";
        env.post(env.browser,base,declaration,"owned-source",null,403);
        var candidate=env.post(env.maintainer,base,declaration,"owned-source",null,200);
        assertEquals("CANDIDATE",candidate.path("status").asText());
        var verification=env.post(env.maintainer,base+"/"+source+"/v1/verify",Map.of("expectedVersion",1,"manifestRef","owned-manifest.json","manifestSha",manifest),"owned-verify",null,200);
        String retention=ProductComparisonRealSecurityEnvironment.sha(Files.readAllBytes(env.root.resolve("engineering-retention.txt")));
        var decision=new ProductSourceAdmission.Decision("owned-decision-v1",source,"v1",verification.path("verificationId").asText(),env.organization,task,
                env.primary.queryForObject("SELECT scope_id FROM con3_data_scope WHERE task_id=?",String.class,task),"UNRESOLVED","owned-rights.txt",rights,"owned-capability.txt",capability,
                "owned-engineering-retention",1,retention,"owned-engineering-maintainer",env.until,Map.of("TITLE",List.of("QUERY","DISPLAY","COMPARISON")));
        String sha=file("owned-decision.json",env.json.writeValueAsString(decision));
        var enable=Map.of("expectedVersion",1,"decisionRef","owned-decision.json","decisionSha",sha);
        var admitted=env.post(env.maintainer,"/v2/consultation/source-contracts/"+source+"/v1/product-enable",enable,"owned-enable",null,200);
        assertEquals("PRODUCT_ENABLED",admitted.path("productStatus").asText());
        var scope=Map.of("taskId",task,"memberSubject",subject,"taskVersion",env.primary.queryForObject("SELECT version FROM con3_task WHERE task_id=?",Long.class,task),"sourceRowVersion",1,"expiresAt",env.until);
        var receipt=env.post(env.maintainer,"/v2/consultation/source-contracts/scopes/"+source+"/authorize",scope,"owned-scope",null,200);
        assertFalse(receipt.path("queryGrantId").asText().isBlank());assertFalse(receipt.path("displayGrantId").asText().isBlank());
        assertEquals(receipt,env.post(env.maintainer,"/v2/consultation/source-contracts/scopes/"+source+"/authorize",scope,"owned-scope",null,200));
        // Exercise the actual preparation product route: a real TITLE license never implies CLAIMS.
        var exact = new ProductComparisonContracts.Prepare(goal.path("taskVersion").asLong(),
                goal.path("goalId").asText(),1,goal.path("inputId").asText(),"CLAIM_ALIGNMENT",
                new ProductComparisonContracts.Target(goal.path("messageId").asText(),null),
                List.of(new ProductComparisonContracts.DocumentRef(source,"owned-record","owned-record-v1",List.of(1),null,null,null,null)));
        var denied=env.post(env.browser,"/v2/consultation/tasks/"+task+"/patent-comparisons/prepare",exact,"owned-claims-not-permitted",token,503);
        assertEquals("COMPARISON_CURRENT_EVIDENCE_UNAVAILABLE",denied.path("code").asText());
        assertEquals(0,env.primary.queryForObject("SELECT COUNT(*) FROM con5_preparation",Integer.class));
        assertEquals(0,env.primary.queryForObject("SELECT COUNT(*) FROM con5_comparison_input",Integer.class));
        var availability=env.app.getBean(ProductSourceAvailability.class);
        var titles=env.app.getBean(ProductCollectedPatentTitles.class);
        var active=availability.requireFields(source,Set.of("TITLE"),"QUERY");
        assertEquals(active,availability.requireFields(source,Set.of("TITLE"),"DISPLAY"));
        var documents=titles.loadFields(active,ProductPatentSearchFilters.none(),Set.of("TITLE"));
        assertEquals(1,documents.size());
        assertEquals("owned-record",documents.getFirst().documentId());
        assertEquals(Map.of("TITLE","Owned engineering security boundary record"),documents.getFirst().fields());
        assertEquals(active,availability.requireFields(source,Set.of("TITLE"),"QUERY"));
        var withdrawn=env.post(env.maintainer,"/v2/consultation/source-contracts/"+source+"/withdraw-product-use",Map.of("expectedEpoch",admitted.path("sourceEpoch").asLong()),"owned-source-revoke",null,200);
        assertEquals("REVOKED",withdrawn.path("productStatus").asText());
        env.post(env.maintainer,"/v2/consultation/source-contracts/scopes/"+source+"/authorize",scope,"owned-scope",null,503);
        assertEquals("WITHDRAWN",env.primary.queryForObject("SELECT status FROM con4_source_current WHERE source_id=?",String.class,source));
        for(String use:List.of("QUERY","DISPLAY","COMPARISON"))
            assertThrows(IllegalStateException.class,()->availability.requireFields(source,Set.of("TITLE"),use));
        var current=env.app.getBean(ProductSourceWatermark.class).current(source);
        assertEquals("WITHDRAWN",current.status());
        assertTrue(current.epoch()>active.epoch());
        assertEquals(raw,ProductComparisonRealSecurityEnvironment.sha(Files.readAllBytes(env.root.resolve("owned-original.json"))));
        assertEquals(1,env.primary.queryForObject("SELECT COUNT(*) FROM con4_source_verification WHERE source_id=?",Integer.class,source));
        String output=System.getProperty("comparison.security.evidence");
        if(output!=null) {
            var receiptPath=java.nio.file.Path.of(output);
            var originals=receiptPath.getParent().resolve(receiptPath.getFileName()+"-source-originals");
            Files.createDirectory(originals);
            for(String ref:List.of("owned-original.json","owned-records.json","owned-manifest.json",
                    "owned-rights.txt","owned-capability.txt","owned-decision.json","engineering-retention.txt"))
                Files.copy(env.root.resolve(ref),originals.resolve(ref));
        }
        return Map.of("layer","PRODUCT_CURRENT_SOURCE_GUARD_AND_CAPTURE_PROJECTION_NOT_HTTP_SEARCH",
                "sourceId",source,"admission",admitted,"withdrawal",withdrawn,
                "titleDocumentsConsumedBeforeWithdrawal",documents.size(),"withdrawnUsesRejected",List.of("QUERY","DISPLAY","COMPARISON"),
                "sourceEpochBefore",active.epoch(),"sourceEpochAfter",current.epoch(),
                "originalShaUnchanged",raw,"verificationHistoryPreserved",true);
    }
    String file(String ref,String content) throws Exception {
        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);Files.write(env.root.resolve(ref),bytes);
        return ProductComparisonRealSecurityEnvironment.sha(bytes);
    }
}


