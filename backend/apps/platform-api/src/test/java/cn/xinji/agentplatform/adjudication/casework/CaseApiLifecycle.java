package cn.xinji.agentplatform.adjudication.casework;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.*;

/** Runs the same page-facing API with a mechanical non-real packet; no fixed result candidates or auth mocks. */
final class CaseApiLifecycle {
    static JsonNode run(CaseApiFixture f)throws Exception{
        long version=f.create().path("workVersion").asLong();
        for(var material:f.pack.path("materials"))version=f.command("/materials",version,material,"material").path("workVersion").asLong();
        version=f.command("/claims",version,f.pack.path("claim"),"claim").path("workVersion").asLong();
        var incomplete=f.command("/reports",version,null,null);version=incomplete.path("workVersion").asLong();
        String base="/v2/adjudication/case-works/"+f.caseId;
        var incompleteView=f.send("case-author","GET",base+"/reports/1",null,200);
        assertTrue(incompleteView.path("report").path("diagnostics").toString().contains("LEGAL_CONTEXT_MISSING"));
        f.send("case-author","POST",base+"/reports/1/submit",reportBody(f,version,incomplete.path("reportSha256").asText()),409);
        var input=(ObjectNode)f.pack.path("caseInput").deepCopy();
        input.put("region","工程地域（非法定管辖）").put("procedure","辅助工程工作（非法定程序）").put("relevantDate","2026-09-30");
        String original="原创工程上下文补正：2026-09-30；工程地域；辅助工程工作。不是现实案件/法律事实。";
        var source=new Source(input.path("source").path("id").asText(),"v2",input.path("source").path("locator").asText(),AdjudicationCaseWork.sha256(original),InputKind.PROJECT_AUTHORED);
        input.set("source",f.mapper.valueToTree(source));f.register(source);
        var context=f.mapper.createObjectNode().put("expectedVersion",version).put("originalText",original).set("input",input);
        version=f.send("case-author","POST",base+"/context-revisions",context,200).path("workVersion").asLong();
        var claim=(ObjectNode)f.pack.path("claim").deepCopy();claim.put("revision",2);
        version=f.command("/claims",version,claim,"claim").path("workVersion").asLong();
        for(var element:claim.path("elements")){
            var finding=new Finding("finding-"+element.path("id").asText(),f.caseId,2,element.path("id").asText(),
                    f.pack.path("materials").get(0).path("id").asText(),Relation.SUPPORT,element.path("exactText").asText(),"人工工程对应；非采信或侵权结论");
            version=f.command("/findings",version,f.mapper.valueToTree(finding),"finding").path("workVersion").asLong();
        }
        String text="原创工程引用校验文本：此文本不是现实法规，不构成正式法律依据，仅用于检查引用绑定。";
        var lawSource=new Source("engineering-citation-original","v1","/engineering-original/citation",AdjudicationCaseWork.sha256(text),InputKind.PROJECT_AUTHORED);f.register(lawSource);
        var citation=new Citation("engineering-citation",f.caseId,lawSource,text,"人工工程解释，不是法律适用判断","机械引用映射验证",input.path("region").asText(),input.path("procedure").asText(),LocalDate.of(2026,9,30));
        version=f.command("/citations",version,f.mapper.valueToTree(citation),"citation").path("workVersion").asLong();
        var generated=f.command("/reports",version,null,null);version=generated.path("workVersion").asLong();
        assertEquals(2,generated.path("reportRevision").asLong());
        var edit=reportBody(f,version,generated.path("reportSha256").asText()).put("sectionId","analysis").put("content","人工修订工程对应解释；未作现实专业结论").put("reason","补充工程说明");
        var edited=f.send("case-author","POST",base+"/reports/2/revisions",edit,200);version=edited.path("workVersion").asLong();
        assertEquals(3,edited.path("reportRevision").asLong());String hash=edited.path("reportSha256").asText();
        String key=UUID.randomUUID().toString();var submitBody=reportBody(f,version,hash);
        var submitted=f.send(f.member,f.tokens.get("case-author"),"POST",base+"/reports/3/submit",submitBody,key,200);version=submitted.path("workVersion").asLong();
        var replay=f.send(f.member,f.tokens.get("case-author"),"POST",base+"/reports/3/submit",submitBody,key,200);assertEquals(submitted,replay);
        var review=reportBody(f,version,hash).put("outcome","RETURNED").put("reason","请澄清工程解释，不改变现实事实");
        f.send("case-author","POST",base+"/reports/3/reviews",review,409);
        version=f.send("independent-reviewer","POST",base+"/reports/3/reviews",review,200).path("workVersion").asLong();
        f.send("case-author","POST",base+"/reports/3/submit",reportBody(f,version,hash),409);
        var revision=reportBody(f,version,hash).put("sectionId","analysis").put("content","已按退回意见澄清原创工程输入关系").put("reason","针对确切版本退回新修订");
        var newReport=f.send("case-author","POST",base+"/reports/3/revisions",revision,200);version=newReport.path("workVersion").asLong();hash=newReport.path("reportSha256").asText();
        assertEquals(4,newReport.path("reportRevision").asLong());
        version=f.send("case-author","POST",base+"/reports/4/submit",reportBody(f,version,hash),200).path("workVersion").asLong();
        version=f.send("independent-reviewer","POST",base+"/reports/4/reviews",reportBody(f,version,hash).put("outcome","REVIEWED_NOT_OFFICIAL").put("reason","已复核工程输入，不是正式裁决"),200).path("workVersion").asLong();
        f.send("independent-reviewer","POST",base+"/reports/4/reviews",reportBody(f,version,hash).put("outcome","RETURNED").put("reason","尝试重复复核"),409);
        var state=f.current();assertEquals(version,state.path("version").asLong());assertEquals(17,version);
        assertEquals("REVIEWED_NOT_OFFICIAL",state.path("reports").get(3).path("report").path("reviewState").asText());
        assertEquals("RETURNED",state.path("reports").get(2).path("report").path("reviewState").asText());
        assertTrue(state.path("reports").get(0).path("stale").asBoolean());assertEquals(2,state.path("claims").size());
        assertEquals(2,state.path("findings").size());assertEquals(1,state.path("citations").size());assertEquals(17,state.path("audits").size());
        assertFalse(state.has("officialState"));assertTrue(state.path("caseInput").path("name").asText().contains("非真实案件"));
        assertEquals(17,f.send("case-author","GET",base+"/versions",null,200).size());
        assertEquals(17,f.send("case-author","GET",base+"/audits",null,200).size());
        var originals=f.send("case-author","GET",base+"/originals",null,200);assertEquals(2,originals.size());assertEquals(f.pack.path("caseOriginalText").asText(),originals.get(0).path("originalText").asText());
        assertEquals(1,f.send("case-author","GET",base+"/versions/1",null,200).path("version").asLong());
        return state;
    }
    static ObjectNode reportBody(CaseApiFixture f,long version,String hash){return f.mapper.createObjectNode().put("expectedVersion",version).put("reportSha256",hash);}
}
