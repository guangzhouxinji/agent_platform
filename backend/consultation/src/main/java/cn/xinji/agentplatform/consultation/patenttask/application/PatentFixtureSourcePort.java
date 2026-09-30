package cn.xinji.agentplatform.consultation.patenttask.application;

public interface PatentFixtureSourcePort extends PatentRetrievalPort {
    PatentFixtureDocument document(String candidateId, String documentVersion);
}
