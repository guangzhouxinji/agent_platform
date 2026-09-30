package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.PatentResultSet;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentRetrievalAction;

import java.util.List;

/** Read-only authorized retrieval implementation; no production provider is wired by this contract. */
public interface PatentRetrievalPort {
    Response search(String scopeReference, String methodReference, String queryReference);

    record Response(PatentRetrievalAction.Outcome outcome, String actualScopeReference,
                    String methodReference, List<String> sourceReferences,
                    List<PatentResultSet.Candidate> candidates, List<String> limitations, String detail) {
        public Response {
            sourceReferences = List.copyOf(sourceReferences);
            candidates = List.copyOf(candidates);
            limitations = List.copyOf(limitations);
        }
    }
}
