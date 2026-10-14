package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

/** Offline compatibility evidence over previously compiled, fixed-value component inputs. */
public final class PromptSaveCanonicalProbe {
    static Object field(Object value,String name)throws Exception {
        var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    public static void main(String[] args)throws Exception {
        Class<?> type=Class.forName("cn.xinji.agentplatform.security.product.ProductPromptCreateRequestStoreTest$Fixture");
        var ctor=type.getDeclaredConstructor(String.class);ctor.setAccessible(true);
        Object fixture=ctor.newInstance("10000000-0000-0000-0000-000000000001");
        var method=ProductPromptCreateRequestStoreTest.class.getDeclaredMethod("canonicalWrite",type);method.setAccessible(true);
        Write original=(Write)method.invoke(null,fixture);
        var store=(ProductPromptStore)field(fixture,"store");
        var tx=(TransactionTemplate)field(fixture,"mainTx");
        var requests=(ProductPromptCreateRequestStore)field(fixture,"requests");
        var evidence=(ProductPromptCreateRequest.Evidence)field(fixture,"evidence");
        Instant now=(Instant)field(fixture,"now");
        tx.executeWithoutResult(status->store.append(store.prepare(original,"40000000-0000-0000-0000-000000000004")));
        var first=original.revision();
        var ref=PromptRevision.referenceFor(first.reference().promptId(),"60000000-0000-0000-0000-000000000006",2,
                first.kind(),first.name(),first.body(),first.scenarioIds(),first.intentIds(),first.audienceRef(),first.language(),first.displayOrder(),first.variables());
        var origin=new PromptRevision.Provenance(evidence.generationSourceRef(),"human",now,"human",now,"SAVE_DRAFT",List.of(),evidence.inputPermissionRef(),evidence.policyRef());
        var revision=new PromptRevision(ref,first.kind(),first.name(),first.body(),first.scenarioIds(),first.intentIds(),first.audienceRef(),first.language(),first.displayOrder(),first.variables(),origin);
        var write=new Write(original.workspaceId(),revision,1,Operation.SAVE,"issuer","human",ProductComparisonJson.hash("fixed-save-key"),
                ProductComparisonJson.hash("fixed-save-command"),original.bodyUntil(),original.auditUntil());
        tx.executeWithoutResult(status->{
            var prepared=store.prepare(write,"70000000-0000-0000-0000-000000000007");var r=prepared.receipt();var after=prepared.after();
            var pending=new Pending(r.promptId(),r.workspaceId(),revision.kind(),1,first.reference().revisionId(),prepared.before().scalarSha(),after.scalarSha(),
                    r.revisionId(),"80000000-0000-0000-0000-000000000008",Operation.SAVE,r.keySha(),r.requestSha(),now.plusSeconds(600),after.containerUntil(),after.controlUntil());
            var request=requests.prepared(pending,prepared,evidence);
            System.out.println("PROMPT_SAVE_CANONICAL_PROBE "+r.contentSha()+" "+prepared.header().recordSha()+" "+r.resultSha()+" "+after.scalarSha()+" "+request.preparedSha());
        });
    }
}
