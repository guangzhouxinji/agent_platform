package cn.xinji.agentplatform.adjudication.casework;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaseWorkHttpsNegativesTest {
    private CaseApiFixture f;
    private String base;
    @BeforeAll void prepare()throws Exception{f=new CaseApiFixture(false);CaseApiLifecycle.run(f);base="/v2/adjudication/case-works/"+f.caseId;}
    @AfterAll void close()throws Exception{if(f!=null)f.close();}
    @AfterEach void noBusinessWrite(){assertEquals(17,f.databases.db.queryForObject("SELECT head_version FROM adj2_case_work",Long.class));assertEquals(17,f.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_receipt",Integer.class));}
    @Test void invalidAndStaleVersionsAreRejected()throws Exception{
        var body=f.mapper.createObjectNode().put("expectedVersion",0).set("material",f.pack.path("materials").get(0));
        f.send("case-author","POST",base+"/materials",body,400);
        ((ObjectNode)body).put("expectedVersion",16);f.send("case-author","POST",base+"/materials",body,409);
        ((ObjectNode)body).put("expectedVersion",17.5);f.send("case-author","POST",base+"/materials",body,400);
    }
    @Test void nestedClaimRevisionCannotBeRoundedOrCoerced()throws Exception{
        var claim=(ObjectNode)f.pack.path("claim").deepCopy();claim.put("revision",3.5);
        f.send("case-author","POST",base+"/claims",f.mapper.createObjectNode().put("expectedVersion",17).set("claim",claim),400);
        claim.put("revision","3");f.send("case-author","POST",base+"/claims",f.mapper.createObjectNode().put("expectedVersion",17).set("claim",claim),400);
        claim.putNull("revision");f.send("case-author","POST",base+"/claims",f.mapper.createObjectNode().put("expectedVersion",17).set("claim",claim),400);
    }
    @Test void missingOriginalCannotEnterPersistentClaim()throws Exception{
        var claim=(ObjectNode)f.pack.path("claim").deepCopy();claim.remove("originalText");claim.put("revision",3);
        f.send("case-author","POST",base+"/claims",f.mapper.createObjectNode().put("expectedVersion",17).set("claim",claim),400);
    }
    @Test void citationOriginalDriftIsRejected()throws Exception{
        var citation=(ObjectNode)f.current().path("citations").get(0).deepCopy();citation.put("id","new-citation").put("originalText","篡改工程原文");
        f.send("case-author","POST",base+"/citations",f.mapper.createObjectNode().put("expectedVersion",17).set("citation",citation),400);
    }
    @Test void caseContextCannotSilentlyInvalidateSelectedCitation()throws Exception{
        var input=(ObjectNode)f.current().path("caseInput").deepCopy();
        f.send("case-author","POST",base+"/context-revisions",f.mapper.createObjectNode().put("expectedVersion",17).put("originalText","not-the-source").set("input",input),400);
    }
    @Test void validOriginalCannotReplaceContextAfterCitationSelection()throws Exception{
        var input=(ObjectNode)f.current().path("caseInput").deepCopy();String original="新增工程上下文，不是真实法律依据";
        var source=(ObjectNode)input.path("source");source.put("version","v-context-after-citation").put("sha256",AdjudicationCaseWork.sha256(original));
        f.register(f.codec.request(input,CaseWorkContract.CaseInput.class).source());
        var result=f.send("case-author","POST",base+"/context-revisions",f.mapper.createObjectNode().put("expectedVersion",17).put("originalText",original).set("input",input),409);
        assertEquals("CONTEXT_IN_USE",result.path("code").asText());
    }
    @Test void grantMaintenanceRejectsForeignIssuerAndAcceptsExactScope()throws Exception{
        var scope=new cn.xinji.agentplatform.security.product.ProductGrants.Scope(f.issuer,"independent-reviewer",f.pack.path("caseInput").path("organization").asText(),"ADJUDICATION",f.caseId,
                f.pack.path("caseInput").path("dataScope").asText(),"ADJ_CASE",f.caseId,1,"CREATE","CASE_AUXILIARY");
        var body=f.mapper.createObjectNode().put("expiresAt",java.time.Instant.now().plusSeconds(600).toString()).put("expectedEpoch",0).set("scope",f.mapper.valueToTree(scope));
        var foreign=(ObjectNode)body.deepCopy();((ObjectNode)foreign.path("scope")).put("issuer","https://foreign.example.invalid");
        f.send(f.maintenance,null,"POST","/v2/adjudication/grants",foreign,java.util.UUID.randomUUID().toString(),403);
        assertEquals(0,f.databases.db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE issuer=?",Integer.class,"https://foreign.example.invalid"));
        f.send(f.maintenance,null,"POST","/v2/adjudication/grants",body,java.util.UUID.randomUUID().toString(),200);
    }
    @Test void oldReportAndWrongHashCannotBeReused()throws Exception{
        var state=f.current();String old=state.path("reports").get(1).path("report").path("contentSha256").asText();
        f.send("case-author","POST",base+"/reports/2/submit",CaseApiLifecycle.reportBody(f,17,old),409);
        var revised=CaseApiLifecycle.reportBody(f,17,AdjudicationCaseWork.sha256("wrong")).put("sectionId","analysis").put("content","说明").put("reason","原因");
        f.send("case-author","POST",base+"/reports/4/revisions",revised,409);
    }
    @Test void duplicateSubmitAndIllegalReviewStateAreRejected()throws Exception{
        String hash=f.current().path("reports").get(3).path("report").path("contentSha256").asText();
        f.send("case-author","POST",base+"/reports/4/submit",CaseApiLifecycle.reportBody(f,17,hash),409);
        f.send("independent-reviewer","POST",base+"/reports/4/reviews",CaseApiLifecycle.reportBody(f,17,hash).put("outcome","OFFICIALLY_SIGNED").put("reason","越权"),400);
    }
    @Test void forgedActorAndWrongIdempotencyBodyAreRejected()throws Exception{
        f.send("case-author","POST",base+"/reports",f.mapper.createObjectNode().put("expectedVersion",17).put("actor","independent-reviewer"),400);
        String key=f.databases.db.queryForObject("SELECT request_key FROM adj2_case_receipt WHERE work_version=17",String.class);
        f.send(f.member,f.tokens.get("case-author"),"POST",base+"/reports",f.mapper.createObjectNode().put("expectedVersion",17),key,409);
    }
    @Test void missingKeyCannotCommit()throws Exception{
        f.send(f.member,f.tokens.get("case-author"),"POST",base+"/reports",f.mapper.createObjectNode().put("expectedVersion",17),null,400);
    }
    @Test void absentGrantAndCrossCaseInputsAreRejected()throws Exception{
        f.send("ungranted-user","GET",base,null,403);
        var other=(ObjectNode)f.pack.path("caseInput").deepCopy();other.put("id","another-case");
        f.send("case-author","POST","/v2/adjudication/case-works",f.mapper.createObjectNode().put("originalText",f.pack.path("caseOriginalText").asText()).set("input",other),403);
        var material=(ObjectNode)f.pack.path("materials").get(0).deepCopy();material.put("caseId","another-case").put("id","other-material");
        f.send("case-author","POST",base+"/materials",f.mapper.createObjectNode().put("expectedVersion",17).set("material",material),400);
    }
    @Test void signatureIsNotReplacedByUntrustedHeaders()throws Exception{
        f.send(f.member,"not-a-signed-token","GET",base,null,null,401);
        f.send(f.member,null,"GET",base,null,null,401);
    }
    @Test void memberCannotMaintainSourceRightsWithoutTlsCertificate()throws Exception{
        var source=f.current().path("caseInput").path("source");
        var use=f.mapper.createObjectNode().set("source",source);
        ((ObjectNode)use).set("scope",f.mapper.createObjectNode().put("caseId",f.caseId).put("organization",f.pack.path("caseInput").path("organization").asText()).put("dataScope",f.pack.path("caseInput").path("dataScope").asText()));
        ((ObjectNode)use).set("allowedUses",f.mapper.createArrayNode().add("READ"));((ObjectNode)use).put("validUntil",java.time.Instant.now().plusSeconds(600).toString())
                .put("evidenceRef","forged-authority").put("evidenceSha256",AdjudicationCaseWork.sha256("none"));
        f.send("case-author","POST","/v2/adjudication/source-uses",use,403);
    }
}
