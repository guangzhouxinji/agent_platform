package cn.xinji.agentplatform.enforcement.material;

import java.util.List;
import java.util.Optional;

public interface MaterialRepository {
    Optional<MaterialWork.Source> source(String id);
    String syncCursor(String sourceId);
    void beginSyncAttempt(String id, String sourceId, String actor, String start);
    void finishSyncAttempt(String id, String cursor, int processed, String state,
                           String failureCode, String failureCursor);
    record SyncRecord(String attemptId, int ordinal, String recordId, String sourceVersion,
                      String beforeCursor, String nextCursor, String state, String failureCode) { }
    void recordSyncRecord(SyncRecord record);
    List<SyncRecord> syncRecords(String attemptId, String sourceId);
    int successfulSyncRecords(String attemptId);
    MaterialWork.Receipt atSyncCursor(String sourceId, String expected, String next, MaterialCommand body);
    Optional<MaterialWork.Observation> observation(String id);
    List<MaterialWork.ActorMention> actors(String observationId);
    Optional<MaterialWork.Rule> rule(String id);
    Optional<MaterialWork.Clue> clue(String id);
    List<MaterialWork.RuleRef> clueRules(String clueId);
    Optional<MaterialWork.Material> material(String id);
    List<MaterialWork.MaterialAction> materialActions(String materialId);
    List<MaterialWork.Revision> revisions(String clueId);
    MaterialWork.Receipt command(String requestId, String fingerprint, String actor,
                                 String operation, MaterialCommand body);

    @FunctionalInterface interface MaterialCommand {
        MaterialWork.Receipt apply();
    }
    void addObservation(MaterialWork.Observation observation);
    void addActors(List<MaterialWork.ActorMention> actors);
    void addSource(MaterialWork.Source source);
    void changeSourceConfiguration(String id, long expectedVersion, String credentialRef,
                                   boolean enabled, String actor);
    void addRule(MaterialWork.Rule rule);
    void changeRule(MaterialWork.Rule rule, String actor, MaterialWork.RuleReview review);
    void addClue(MaterialWork.Clue clue);
    void lockObservation(String observationId);
    void addClueRule(String clueId, String ruleId, long revision);
    void addRevision(MaterialWork.Revision revision);
    void addMaterial(MaterialWork.Material material);
    void appendMaterialAction(MaterialWork.MaterialAction action, String nextState);
    boolean fileReferenced(String fileKey);
    void recordCleanup(String fileKey, String state);
}
