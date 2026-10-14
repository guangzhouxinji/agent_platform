package cn.xinji.agentplatform.adjudication.casework;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.Scope;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Actual browser, PKCE login and product UI on isolated real DM8, not an API mock or token injection. */
class CaseWorkspaceBrowserIT {
    @Test void browserOperatesOneCaseThroughReturnResubmitAndWithdrawal()throws Exception{
        Path output=Path.of(CaseApiDatabases.required("ADJ_WORKSPACE_EVIDENCE"));Files.createDirectories(output);
        Path dist=Path.of("frontend/dist");assertTrue(Files.exists(dist.resolve("index.html")),"Build the actual frontend first");
        Path classes=Path.of(System.getenv().getOrDefault("ADJ_WORKSPACE_TEST_CLASSES","backend/apps/platform-api/target/test-classes")).toAbsolutePath().normalize();Path resource=classes.resolve("static/app");
        assertTrue(resource.startsWith(classes));
        try(var paths=Files.walk(dist)){for(var path:paths.toList()){Path dest=resource.resolve(dist.relativize(path));if(Files.isDirectory(path))Files.createDirectories(dest);else Files.copy(path,dest,StandardCopyOption.REPLACE_EXISTING);}}
        Map<String,Object> result=new LinkedHashMap<>();
        try(var f=new CaseApiFixture(true,Path.of("backend/apps/platform-api/src/test/resources/adjudication/browser-case-input.json"))){
            String password="U9-browser-"+UUID.randomUUID();
            var encoder=f.context.getBean(PasswordEncoder.class);
            for(var subject:List.of("case-author","independent-reviewer"))f.databases.db.update("UPDATE id2_subject SET password_hash=? WHERE issuer=? AND subject_id=?",encoder.encode(password),f.issuer,subject);
            f.databases.independent.update("UPDATE id2_maintainer SET allowed_roles='SOURCES,GRANTS,SUBJECTS' WHERE singleton_key=1");
            var client=f.mapper.createObjectNode().put("clientId","adjudication-workspace-browser").put("name","Adjudication workspace access").put("redirectUri",f.endpoint+"/app/adjudication");
            f.send(f.maintenance,null,"POST","/v2/identity/clients",client,UUID.randomUUID().toString(),200);
            var input=(com.fasterxml.jackson.databind.node.ObjectNode)f.pack.path("caseInput").deepCopy();
            input.put("region","中国").put("procedure","专利侵权纠纷行政裁决").put("relevantDate","2026-10-03");
            String original="案件上下文补正：中国；专利侵权纠纷行政裁决；相关时点2026-10-03。";
            var source=new Source(input.path("source").path("id").asText(),"v2",input.path("source").path("locator").asText(),AdjudicationCaseWork.sha256(original),InputKind.USER_PROVIDED);
            input.set("source",f.mapper.valueToTree(source));f.register(source);
            var legal=f.mapper.readTree(Files.readString(Path.of("data/adjudication/public-legal-sources-v1/citation.json")));
            String citationText=legal.path("originalText").asText();var citationSource=f.codec.request(legal.path("source"),Source.class);
            var provenance=f.codec.request(legal.path("provenance"),CaseSourceAuthority.Provenance.class);
            var sourceUse=new CaseSourceAuthority.Use(Scope.of(f.codec.request(input,CaseInput.class)),citationSource,Set.of("READ","SAVE","ANALYZE"),Instant.now().plusSeconds(3600),"PUBLIC_LEGAL_CAPTURE_VERIFIED",provenance.manifestSha256(),provenance);
            f.send(f.maintenance,null,"POST","/v2/adjudication/source-uses",f.mapper.valueToTree(sourceUse),null,200);
            var citation=new Citation("selected-legal-reference",f.caseId,citationSource,citationText,"以第38、39条核对确认权项及必要特征对应，具体适用由承办人员审查","采用官方发布原文及确切版本",input.path("region").asText(),input.path("procedure").asText(),LocalDate.of(2026,10,3));
            var prepared=f.mapper.createObjectNode();prepared.set("pack",f.pack);prepared.set("contextInput",input);prepared.put("contextOriginal",original);var citationInput=(com.fasterxml.jackson.databind.node.ObjectNode)f.mapper.valueToTree(citation);citationInput.put("relevantDate",citation.relevantDate().toString());prepared.set("citation",citationInput);
            prepared.put("origin",f.endpoint);prepared.put("sourceKey",f.context.getBean(CaseSourceAuthority.class).key(Scope.of(f.codec.request(input,CaseInput.class)),source));
            Path inputFile=output.resolve("browser-inputs.json");Files.writeString(inputFile,f.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(prepared));
            var process=new ProcessBuilder("node","frontend/e2e/adjudication-workspace-product.mjs").redirectErrorStream(true).redirectOutput(output.resolve("browser-process.log").toFile());
            process.environment().put("ADJ_BROWSER_INPUT",inputFile.toAbsolutePath().toString());process.environment().put("ADJ_BROWSER_EVIDENCE",output.toAbsolutePath().toString());
            process.environment().put("ADJ_BROWSER_PASSWORD",password);process.environment().put("ADJ_BROWSER_PFX",f.tls.store.toString());process.environment().put("ADJ_BROWSER_PFX_PASSWORD",f.tls.password);
            boolean recovery="true".equals(System.getenv("ADJ_WORKSPACE_RECOVERY"));if(recovery)process.environment().put("ADJ_BROWSER_RECOVERY","true");
            var browser=process.start();
            try{
                if(recovery){
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(6);
                    while(!Files.exists(output.resolve("recovery-ready.json"))&&browser.isAlive()&&System.nanoTime()<deadline)Thread.sleep(100);
                    assertTrue(Files.exists(output.resolve("recovery-ready.json")),"Browser must create the case before real restart");
                    var ready=f.mapper.readTree(Files.readString(output.resolve("recovery-ready.json")));assertEquals(f.caseId,ready.path("caseId").asText());assertEquals(22,ready.path("headVersion").asInt());
                    var before=storedBytes(f);f.restart();var after=storedBytes(f);assertEquals(before,after,"Restart must not reconstruct or alter persisted case bytes");
                    var evidence=f.mapper.createObjectNode();evidence.put("caseId",f.caseId);evidence.put("headVersion",22);evidence.put("actualSpringServiceRestart",true);evidence.put("caseBytesUnchanged",true);evidence.set("persistedDigests",f.mapper.valueToTree(after));
                    Files.writeString(output.resolve("service-restarted.json"),f.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));result.put("sameCaseServiceRestart",evidence);
                }
                boolean ended=browser.waitFor(12,java.util.concurrent.TimeUnit.MINUTES);assertTrue(ended,"Browser deadline exceeded");assertEquals(0,browser.exitValue(),"Read retained browser-process.log");
            }finally{if(browser.isAlive())browser.destroyForcibly();}
            var browserResult=f.mapper.readTree(Files.readString(output.resolve("browser-result.json")));assertTrue(browserResult.path("passed").asBoolean());
            long head=f.databases.db.queryForObject("SELECT head_version FROM adj2_case_work",Long.class);
            result.put("schema","ADJ_WORKSPACE_BROWSER_DM8/v1");result.put("completedAt",Instant.now().toString());result.put("inputNature","APPROVED_SUBSTITUTE_CASE_WITH_REAL_PUBLIC_STATUTORY_CITATION");
            result.put("isolatedSchema",f.databases.schema);result.put("caseId",f.caseId);result.put("headVersion",head);
            for(var table:List.of("adj2_case_revision","adj2_case_audit","adj2_case_receipt")){int rows=f.databases.db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);assertEquals(head,rows);result.put(table+"Rows",rows);}
            int sessions=f.databases.db.queryForObject("SELECT COUNT(*) FROM id2_session s JOIN oauth2_registered_client c ON s.client_id=c.id WHERE c.client_id='adjudication-workspace-browser'",Integer.class);assertTrue(sessions>=2);result.put("realPkceSessionCount",sessions);
            assertEquals("REVOKED",f.databases.independent.queryForObject("SELECT state FROM adj2_source_use WHERE source_key=?",String.class,prepared.path("sourceKey").asText()));
            result.put("sourceRevoked",true);result.put("publicLawProvenancePersisted",f.databases.independent.queryForObject("SELECT COUNT(*) FROM adj2_source_use WHERE source_key=?",Integer.class,f.context.getBean(CaseSourceAuthority.class).key(Scope.of(f.codec.request(input,CaseInput.class)),citationSource))==1);result.put("browserResult",browserResult);
            Files.writeString(output.resolve("before-cleanup.json"),f.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
        result.put("isolatedSchemasRemoved",true);result.put("passed",true);
        Files.writeString(output.resolve("verification.json"),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    private static Map<String,List<String>> storedBytes(CaseApiFixture f){
        var result=new TreeMap<String,List<String>>();
        for(var table:List.of("adj2_case_revision","adj2_case_audit","adj2_case_receipt"))result.put(table,f.databases.db.query("SELECT work_version,payload,payload_sha256 FROM "+table+" ORDER BY work_version",(rs,row)->rs.getLong(1)+":"+AdjudicationCaseWork.sha256(rs.getString(2))+":"+rs.getString(3)));
        result.put("adj2_case_original",f.databases.db.query("SELECT source_sha256,original_text FROM adj2_case_original ORDER BY source_sha256",(rs,row)->rs.getString(1)+":"+AdjudicationCaseWork.sha256(rs.getString(2))));
        result.put("adj2_case_work",f.databases.db.query("SELECT case_id,head_version,head_sha256 FROM adj2_case_work",(rs,row)->rs.getString(1)+":"+rs.getLong(2)+":"+rs.getString(3)));
        assertEquals(22,result.get("adj2_case_revision").size());return result;
    }
}
