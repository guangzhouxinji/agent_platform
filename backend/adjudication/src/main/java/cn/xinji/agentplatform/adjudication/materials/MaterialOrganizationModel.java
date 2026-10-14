package cn.xinji.agentplatform.adjudication.materials;

import java.util.List;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.Source;

/** A model proposes material statements only; it cannot write findings, grants or human decisions. */
public interface MaterialOrganizationModel {
    enum Side { APPLICANT, RESPONDENT }
    enum Kind { PARTY_ASSERTION, RECORDED_STATEMENT, DISPUTE_CLUE, MISSING_ITEM }
    record Input(String tag, Side side, Source source, String text) { }
    record Usage(long inputTokens, long outputTokens) { }
    record Reply(String model, String rawContent, Usage usage) { }
    record Candidate(String id, Kind kind, Side providerSide, String statement, String exactQuote,
                     int start, int end, String sourceId, String sourceVersion, String sourceSha256,
                     String originalLocator, String sourceTag) { }
    String request(List<Input> inputs, int maxOutputTokens);
    Reply invoke(String exactRequest);
}
