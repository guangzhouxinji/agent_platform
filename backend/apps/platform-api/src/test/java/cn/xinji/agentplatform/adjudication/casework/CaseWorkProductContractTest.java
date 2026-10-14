package cn.xinji.agentplatform.adjudication.casework;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.Scope;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Independent counterfactual product exit checks; no permissions or results are replaced by a mock. */
class CaseWorkProductContractTest {
    @Test void releasedPolicyCannotDropMandatoryRulesAndAnyByteChangeChangesStamp()throws Exception{
        try(var f=new CaseApiFixture(false)){
            var base=CaseReportPolicy.load(f.codec,"",100000);var lower=CaseReportPolicy.load(f.codec,"",99999);assertNotEquals(base.version(),lower.version());
            Path file=Files.createTempFile("adj-report-policy-",".json");
            try{
                var config=(ObjectNode)f.mapper.readTree(Files.readString(Path.of("backend/apps/platform-api/src/main/resources/adjudication/report-policy-v2.json")));
                config.put("method","SECOND_VERSIONED_METHOD_REVIEW");Files.writeString(file,config.toString());var changed=CaseReportPolicy.load(f.codec,file.toString(),100000);assertNotEquals(base.version(),changed.version());
                config.put("opposingRequiresResolution",true);Files.writeString(file,config.toString());assertTrue(CaseReportPolicy.load(f.codec,file.toString(),100000).opposingRequiresResolution());
                config.putArray("mandatoryRules").add("EXACT_ORIGINAL");Files.writeString(file,config.toString());assertThrows(RuntimeException.class,()->CaseReportPolicy.load(f.codec,file.toString(),100000));
            }finally{Files.deleteIfExists(file);}
        }
    }
    @Test void changedPolicySurvivesRealRestartOldReportsAreVisibleButCannotBeSubmittedOrReviewed()throws Exception{
        try(var f=new CaseApiFixture(false)){
            CaseApiLifecycle.run(f);String path="/v2/adjudication/case-works/"+f.caseId;var old=f.current();String policy=old.path("reports").get(3).path("report").path("policyVersion").asText();
            f.configurationOverrides.put("adjudication.v2.report-max-text-length",99999);f.restart();
            var projected=f.current();assertTrue(projected.path("reports").get(3).path("policyStale").asBoolean());assertFalse(projected.path("reports").get(3).path("current").asBoolean());
            var body=f.mapper.createObjectNode().put("expectedVersion",17).put("reportSha256",old.path("reports").get(3).path("report").path("contentSha256").asText());
            var rejected=f.send("case-author","POST",path+"/reports/4/submit",body,409);assertEquals("REPORT_POLICY_CONFLICT",rejected.path("code").asText());
            var review=(ObjectNode)body.deepCopy();review.put("outcome","RETURNED").put("reason","规则已更新");assertEquals("REPORT_POLICY_CONFLICT",f.send("independent-reviewer","POST",path+"/reports/4/reviews",review,409).path("code").asText());
            assertEquals(17,f.current().path("version").asInt());assertEquals(17,f.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_receipt",Integer.class));
            f.send("case-author","POST",path+"/reports",f.mapper.createObjectNode().put("expectedVersion",17),200);f.restart();
            var latest=f.current();assertEquals(18,latest.path("version").asInt());assertEquals(policy,latest.path("reports").get(3).path("report").path("policyVersion").asText());assertNotEquals(policy,latest.path("reports").get(4).path("report").path("policyVersion").asText());assertTrue(latest.path("reports").get(4).path("current").asBoolean());
        }
    }
    @Test void publicOriginalMustBindCaptureRightsPurposeAndManifestBeforeIndependentApproval()throws Exception{
        try(var f=new CaseApiFixture(false)){
            var captured=f.mapper.readTree(Files.readString(Path.of("data/adjudication/public-legal-sources-v1/citation.json")));var source=f.codec.request(captured.path("source"),Source.class);var provenance=f.codec.request(captured.path("provenance"),CaseSourceAuthority.Provenance.class);
            var scope=Scope.of(f.codec.request(f.pack.path("caseInput"),CaseInput.class));long count=f.databases.independent.queryForObject("SELECT COUNT(*) FROM adj2_source_use",Long.class);
            var old=new CaseSourceAuthority.Use(scope,source,Set.of("READ","SAVE","ANALYZE"),Instant.now().plusSeconds(600),"ACCOUNTABLE_SOURCE_REVIEW",provenance.manifestSha256());
            var missing=f.send(f.maintenance,null,"POST","/v2/adjudication/source-uses",f.mapper.valueToTree(old),null,400);assertEquals("PUBLIC_SOURCE_PROVENANCE_REQUIRED",missing.path("code").asText());
            var wrong=new CaseSourceAuthority.Use(scope,source,old.allowedUses(),old.validUntil(),old.evidenceRef(),AdjudicationCaseWork.sha256("wrong-manifest"),provenance);
            var drift=f.send(f.maintenance,null,"POST","/v2/adjudication/source-uses",f.mapper.valueToTree(wrong),null,400);assertEquals("PUBLIC_SOURCE_PROVENANCE_MISMATCH",drift.path("code").asText());
            assertEquals(count,f.databases.independent.queryForObject("SELECT COUNT(*) FROM adj2_source_use",Long.class));
            var correct=new CaseSourceAuthority.Use(scope,source,old.allowedUses(),old.validUntil(),old.evidenceRef(),provenance.manifestSha256(),provenance);
            var receipt=f.send(f.maintenance,null,"POST","/v2/adjudication/source-uses",f.mapper.valueToTree(correct),null,200);String key=receipt.path("sourceKey").asText();
            String payload=f.databases.independent.queryForObject("SELECT payload FROM adj2_source_use WHERE source_key=?",String.class,key);var stored=f.codec.decode(payload,CaseSourceAuthority.Use.class);assertEquals(provenance,stored.provenance());
            f.restart();assertEquals(provenance.artifactSha256(),f.codec.decode(f.databases.independent.queryForObject("SELECT payload FROM adj2_source_use WHERE source_key=?",String.class,key),CaseSourceAuthority.Use.class).provenance().artifactSha256());
        }
    }
}
