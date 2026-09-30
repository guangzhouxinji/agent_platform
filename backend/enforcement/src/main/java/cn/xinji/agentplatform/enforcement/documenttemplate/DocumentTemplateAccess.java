package cn.xinji.agentplatform.enforcement.documenttemplate;

@FunctionalInterface
public interface DocumentTemplateAccess {
    void require(String actor, String organization, String resourceId, long version, String action);
}
