package cn.xinji.agentplatform.enforcement.documenttemplate;

public interface DocumentTemplateFiles {
    String put(String category, String sha256, byte[] content);
    byte[] read(String key);
    void delete(String key);
}
