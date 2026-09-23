package cn.xinji.agentplatform.consultation.domain;

public record ConsultationCandidate(
        String sourceId,
        String version,
        String location,
        String matchBasis,
        String evidenceText) {

    public ConsultationCandidate(String sourceId, String version, String location, String matchBasis) {
        this(sourceId, version, location, matchBasis, "");
    }

    public ConsultationCandidate {
        evidenceText = evidenceText == null ? "" : evidenceText.trim();
    }
}
