package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.List;

/** Versioned query-document scoring port used only after candidate retrieval. */
public interface PatentReranker {
    record Model(String provider, String modelId, String revision, String modelSha256,
                 String tokenizerSha256, int maxTokens, String licenseId) { }
    record Pair(String candidateId, String query, String documentText) { }
    record Score(String candidateId, double value) { }

    Model model();
    List<Score> score(List<Pair> pairs);
}
