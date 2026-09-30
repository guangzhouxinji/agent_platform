package cn.xinji.agentplatform.enforcement.material;

/** Current, independent authorization and source-use checks. Never infer grants from a receipt. */
public interface MaterialAccess {
    void require(String actor, String organization, String resourceType, String resourceId,
                 long version, String action, String purpose);
    void requireSource(String sourceId, String use, long epoch);
    void prepareSource(String sourceId, long epoch);
    void activateSource(String sourceId, long epoch);
}
