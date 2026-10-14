package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Read transport omits deployment evidence and preserves numeric defaults without JS Number rounding. */
final class ProductPromptReadView {
    private ProductPromptReadView() { }
    record Variable(String name,String type,boolean required,String sourceRef,boolean allowDefault,Object defaultValue,boolean allowEmpty){
        static Variable from(VariableDefinition value){
            Object original=value.defaultValue();
            Object exact=original instanceof Number?original.toString():original;
            return new Variable(value.name(),value.type().name(),value.required(),value.sourceRef(),value.allowDefault(),exact,value.allowEmpty());
        }
    }
    record Revision(String workspaceId,String contentSha,String promptId,String revisionId,long version,String kind,String name,String body,
                    Set<String> scenarioIds,Set<String> intentIds,String audienceRef,String language,int displayOrder,
                    List<Variable> variables,Instant submittedAt,Instant bodyUntil){
        static Revision from(String workspaceId,PromptRevision revision,ProductPromptRecords.Header header){
            ProductPromptRecords.uuid(workspaceId);
            return new Revision(workspaceId,header.contentSha(),revision.reference().promptId(),revision.reference().revisionId(),revision.reference().version(),
                    revision.kind().name(),revision.name(),revision.body(),revision.scenarioIds(),revision.intentIds(),revision.audienceRef(),
                    revision.language(),revision.displayOrder(),revision.variables().stream().map(Variable::from).toList(),
                    header.submittedAt(),header.bodyUntil());
        }
    }
    record Summary(String revisionId,long version,Instant submittedAt,Instant bodyUntil){
        static Summary from(ProductPromptRecords.Header header){
            return new Summary(header.revisionId(),header.number(),header.submittedAt(),header.bodyUntil());
        }
    }
    record History(String promptId,int page,int size,long total,List<Summary> revisions){
        History{revisions=List.copyOf(revisions);}
    }
}
