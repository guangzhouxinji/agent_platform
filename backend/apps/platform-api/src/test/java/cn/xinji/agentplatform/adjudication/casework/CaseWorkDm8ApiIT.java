package cn.xinji.agentplatform.adjudication.casework;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Explicit IT: absence of real independent DM8 settings fails, never silently counts a skip as verification. */
class CaseWorkDm8ApiIT {
    @Test void realDm8TlsLifecycleRestartLargeUnicodeAndSourceRevocation()throws Exception{
        Path output=Path.of(CaseApiDatabases.required("ADJ_DM8_EVIDENCE"));Files.createDirectories(output);
        Map<String,Object> evidence;
        try(var f=new CaseApiFixture(true)){
            var before=CaseApiLifecycle.run(f);String payloadHash=f.databases.db.queryForObject("SELECT head_sha256 FROM adj2_case_work",String.class);
            f.restart();var after=f.current();assertEquals(before,after);
            assertEquals(payloadHash,f.databases.db.queryForObject("SELECT head_sha256 FROM adj2_case_work",String.class));
            String text="公开标识为原创工程材料；非真实证据。".repeat(2500);
            var source=new Source("large-engineering-material","v1","/engineering-material/unicode",AdjudicationCaseWork.sha256(text),InputKind.PROJECT_AUTHORED);f.register(source);
            var material=new Material("large-engineering-material",f.caseId,Side.APPLICANT,"工程输入申请方",text,"仅UTF-8/CLOB持久校验",source,true,null);
            var receipt=f.command("/materials",17,f.mapper.valueToTree(material),"material");assertEquals(18,receipt.path("workVersion").asLong());
            f.restart();var reloaded=f.current();assertEquals(text,reloaded.path("materials").get(2).path("content").asText());
            assertTrue(reloaded.path("reports").get(3).path("stale").asBoolean());
            String hash=before.path("reports").get(3).path("report").path("contentSha256").asText();
            f.send("case-author","POST","/v2/adjudication/case-works/"+f.caseId+"/reports/4/submit",CaseApiLifecycle.reportBody(f,18,hash),409);
            var authority=f.context.getBean(CaseSourceAuthority.class);var input=f.codec.request(f.pack.path("caseInput"),CaseInput.class);
            String key=authority.key(cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.Scope.of(input),input.source());
            f.send(f.maintenance,null,"POST","/v2/adjudication/source-uses/"+key+"/revoke",f.mapper.createObjectNode().put("expectedVersion",1),null,200);
            f.restart();f.send("case-author","GET","/v2/adjudication/case-works/"+f.caseId+"/versions",null,403);
            // Latest view has a new case-context source but old report/original versions still require live source rights.
            f.send("case-author","GET","/v2/adjudication/case-works/"+f.caseId+"/versions/1",null,403);
            evidence=new LinkedHashMap<>();evidence.put("schema","ADJ_API_DM8_EVIDENCE/v1");evidence.put("completedAt",Instant.now().toString());
            evidence.put("inputNature","PROJECT_AUTHORED_NON_REAL_CASE_NON_PATENT_NON_LAW");evidence.put("isolatedSchema",f.databases.schema);
            evidence.put("primaryDatabase","DM8");evidence.put("independentAuthorityDatabase","DM8");evidence.put("apiAndRecoveryPassed",true);
            evidence.put("caseId",f.caseId);evidence.put("lifecycleVersion",17);evidence.put("afterUnicodeVersion",18);evidence.put("restoredStateEqualsSaved",true);
            evidence.put("originalSnapshotSha256",payloadHash);evidence.put("unicodeLength",text.length());evidence.put("unicodeSha256",AdjudicationCaseWork.sha256(text));
            evidence.put("receipts",f.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_receipt",Integer.class));
            evidence.put("revisions",f.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_revision",Integer.class));
            evidence.put("audits",f.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_audit",Integer.class));
            evidence.put("currentState",reloaded);evidence.put("httpExchanges",f.exchanges);
            Files.writeString(output.resolve("dm8-api-before-cleanup.json"),f.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
        evidence.put("isolatedSchemasRemoved",true);evidence.put("passed",true);Files.writeString(output.resolve("dm8-api-verification.json"),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
}
