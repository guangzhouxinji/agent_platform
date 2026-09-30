package cn.xinji.agentplatform.consultation.patenttask.application;

public record PatentFixtureDocument(String candidateId, String documentVersion, String title,
                                    String sourceId, String locator, String excerpt, String limitation) {}
