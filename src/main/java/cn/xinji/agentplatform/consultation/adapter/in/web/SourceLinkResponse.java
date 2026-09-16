package cn.xinji.agentplatform.consultation.adapter.in.web;

import cn.xinji.agentplatform.consultation.domain.ConsultationSourceLink;

public record SourceLinkResponse(String sourceLinkId, String sourceId, String version, String location) {
    static SourceLinkResponse from(ConsultationSourceLink link) { return new SourceLinkResponse(link.sourceLinkId(), link.sourceId(), link.version(), link.location()); }
}
