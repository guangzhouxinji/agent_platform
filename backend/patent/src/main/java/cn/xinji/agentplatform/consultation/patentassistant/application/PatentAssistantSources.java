package cn.xinji.agentplatform.consultation.patentassistant.application;

public record PatentAssistantSources(
        PatentAssistantSourcePort analysisSource,
        PatentAssistantSourcePort searchSource) {}
