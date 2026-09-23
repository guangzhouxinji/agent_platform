package cn.xinji.agentplatform.enforcement.domain;

public record SourceLocator(String sourceSystem, String sourceId, String version, String location) {
    public SourceLocator {
        if (blank(sourceSystem) || blank(sourceId) || blank(version)) {
            throw new IllegalArgumentException("Source system, id, and version are required");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
