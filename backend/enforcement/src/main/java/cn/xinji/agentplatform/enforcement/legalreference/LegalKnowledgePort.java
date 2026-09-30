package cn.xinji.agentplatform.enforcement.legalreference;

import java.util.List;

import static cn.xinji.agentplatform.enforcement.legalreference.LegalReferenceContract.*;

public interface LegalKnowledgePort {
    List<SourceResult> search(SearchRequest request);
    Candidate exact(String sourceId, String sourceVersion, String candidateId);
    Candidate latest(String sourceId, String articleId);
}
