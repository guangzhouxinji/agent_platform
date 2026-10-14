package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearApproval.Evidence;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Request;
import cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;

/** Owned finite scalar materials, with no business body or authority assertion. */
final class ProductPromptBodyClearRecordsFixture {
    static final Instant AT=Instant.parse("2026-10-14T00:00:00.123Z");
    static final String WORKSPACE="10000000-0000-0000-0000-000000000001";
    static final String ORGANIZATION="20000000-0000-0000-0000-000000000002";
    static final String PROMPT="30000000-0000-0000-0000-000000000003";
    static final String REVISION="40000000-0000-0000-0000-000000000004";
    static final String HEAD="50000000-0000-0000-0000-000000000005";
    private ProductPromptBodyClearRecordsFixture() { }
    static ProductComparisonJson json(){return new ProductComparisonJson(new ObjectMapper().registerModule(new JavaTimeModule()));}
    static ProductPromptBodyClearCodec codec(){return new ProductPromptBodyClearCodec(json());}
    static Request fixed(){
        Workspace workspace=new Workspace(WORKSPACE,ORGANIZATION,7,"ACTIVE","policy.json","1".repeat(64),"2".repeat(64),AT.plusSeconds(3600));
        Evidence evidence=new Evidence("maintainer",ORGANIZATION,"3".repeat(64),9,"4".repeat(64),"5".repeat(64),"clear.json","6".repeat(64),AT.plusSeconds(1800));
        Head head=new Head(PROMPT,WORKSPACE,PromptRevision.Kind.GUIDE,HEAD,2,"a".repeat(64),AT.plusSeconds(600),AT.plusSeconds(1800));
        Header target=new Header(REVISION,PROMPT,1,"7".repeat(64),"8".repeat(64),AT.minusSeconds(600),AT,AT.plusSeconds(1200),"PRESENT");
        Receipt original=new Receipt("60000000-0000-0000-0000-000000000006",WORKSPACE,"issuer","creator",Operation.CREATE,
                "9".repeat(64),"b".repeat(64),PROMPT,REVISION,1,target.contentSha(),"c".repeat(64),target.submittedAt(),target.auditUntil());
        var key=ProductPromptBodyClearRecords.key(workspace,evidence,PROMPT,REVISION,"owned-clear-key");
        return codec().prepare(workspace,evidence,head,target,original,key,"d".repeat(64),AT,AT.plusSeconds(900),AT.plusSeconds(30));
    }
}
