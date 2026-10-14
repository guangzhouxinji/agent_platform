package cn.xinji.agentplatform.adjudication.casework;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class CaseWorkRecoveryFailuresTest {
    @Test void snapshotAuditReceiptAndChainCorruptionFailClosedWithoutRepairingHistory()throws Exception{
        try(var f=new CaseApiFixture(false)){
            CaseApiLifecycle.run(f);String base="/v2/adjudication/case-works/"+f.caseId;
            var snapshot=f.databases.db.queryForMap("SELECT payload,payload_sha256,previous_sha256 FROM adj2_case_revision WHERE work_version=17");
            String payload=f.databases.db.queryForObject("SELECT payload FROM adj2_case_revision WHERE work_version=17",String.class);
            f.databases.db.update("UPDATE adj2_case_revision SET payload=? WHERE work_version=17",payload+" ");
            f.send("case-author","GET",base,null,503);f.databases.db.update("UPDATE adj2_case_revision SET payload=? WHERE work_version=17",payload);
            String audit=f.databases.db.queryForObject("SELECT payload FROM adj2_case_audit WHERE work_version=17",String.class);
            var changed=(ObjectNode)f.mapper.readTree(audit);changed.put("actor","forged-reviewer");String wrong=f.codec.encode(changed);
            f.databases.db.update("UPDATE adj2_case_audit SET payload=?,payload_sha256=? WHERE work_version=17",wrong,AdjudicationCaseWork.sha256(wrong));
            f.send("case-author","GET",base,null,503);f.databases.db.update("UPDATE adj2_case_audit SET payload=?,payload_sha256=? WHERE work_version=17",audit,AdjudicationCaseWork.sha256(audit));
            String receipt=f.databases.db.queryForObject("SELECT payload FROM adj2_case_receipt WHERE work_version=17",String.class);
            var badReceipt=(ObjectNode)f.mapper.readTree(receipt);badReceipt.put("reportSha256",AdjudicationCaseWork.sha256("fake"));String bad=f.codec.encode(badReceipt);
            f.databases.db.update("UPDATE adj2_case_receipt SET payload=?,payload_sha256=? WHERE work_version=17",bad,AdjudicationCaseWork.sha256(bad));
            f.send("case-author","GET",base,null,503);f.databases.db.update("UPDATE adj2_case_receipt SET payload=?,payload_sha256=? WHERE work_version=17",receipt,AdjudicationCaseWork.sha256(receipt));
            f.databases.db.update("UPDATE adj2_case_revision SET previous_sha256=? WHERE work_version=17",AdjudicationCaseWork.sha256("fake-parent"));
            f.send("case-author","GET",base,null,503);f.databases.db.update("UPDATE adj2_case_revision SET previous_sha256=? WHERE work_version=17",snapshot.get("previous_sha256"));
            assertEquals(17,f.current().path("version").asLong());f.restart();assertEquals(17,f.current().path("audits").size());
        }
    }
    @Test void authorityRevocationInsideCommandRollsBackEveryBusinessRowAndPersistsAcrossRestart()throws Exception{
        try(var f=new CaseApiFixture(false)){
            CaseApiLifecycle.run(f);
            var repository=f.context.getBean(cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.class);
            var jwt=f.context.getBean(JwtDecoder.class).decode(f.tokens.get("case-author"));
            var grants=f.context.getBean(cn.xinji.agentplatform.security.product.ProductGrantWatermark.class);
            var sources=f.context.getBean(CaseSourceAuthority.class);
            var real=new CaseWorkAccess(f.databases.db,grants,sources,jwt,f.issuer,Clock.systemUTC());
            var state=repository.read(f.caseId,null,real);var original=state.materials().getFirst();var scope=Scope.of(state.caseInput());
            var material=new Material("late-material",f.caseId,original.side(),original.submitter(),original.content(),original.assertedPurpose(),original.source(),true,null);
            String sourceKey=sources.key(scope,original.source());
            var injected=new Access(){
                public String actor(){return real.actor();}public String identity(){return real.identity();}
                public long generation(){return real.generation();}public void assertUnchanged(long generation){real.assertUnchanged(generation);}
                public void require(Scope s,String action){real.require(s,action);}public void sources(Scope s,List<Source> inputs,String use){real.sources(s,inputs,use);}
            };
            assertThrows(SecurityException.class,()->repository.mutate(f.caseId,17,UUID.randomUUID().toString(),AdjudicationCaseWork.sha256("rollback-request"),"MATERIAL",List.of(material.source()),null,injected,current->{
                var next=AdjudicationCaseWork.addMaterial(current,17,material,real.actor(),Instant.now());
                sources.revoke(sourceKey,1,"accountable-engineering-maintainer");return next;
            }));
            assertEquals(17,f.databases.db.queryForObject("SELECT head_version FROM adj2_case_work",Long.class));
            for(var table:List.of("adj2_case_revision","adj2_case_audit","adj2_case_receipt"))assertEquals(17,f.databases.db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
            f.send("case-author","GET","/v2/adjudication/case-works/"+f.caseId,null,403);
            f.restart();f.send("case-author","GET","/v2/adjudication/case-works/"+f.caseId,null,403);
            assertEquals("REVOKED",f.databases.independent.queryForObject("SELECT state FROM adj2_source_use WHERE source_key=?",String.class,sourceKey));
        }
    }
}
