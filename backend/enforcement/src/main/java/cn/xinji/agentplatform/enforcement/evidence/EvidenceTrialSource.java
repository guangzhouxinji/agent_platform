package cn.xinji.agentplatform.enforcement.evidence;

import java.util.List;

/** Adapter-supplied, exact and licensed trial inputs; never an EvidenceSet. */
public interface EvidenceTrialSource {
    record Ref(String id, String version, String sha256) { }
    record Material(Ref ref, String text, String eventRef, String collectionTime, String uploadTime,
                    String parseIssue) {
        public Material(Ref ref, String text, String eventRef) {
            this(ref, text, eventRef, null, null, null);
        }
        public Material(Ref ref, String text, String eventRef, String collectionTime, String uploadTime) {
            this(ref, text, eventRef, collectionTime, uploadTime, null);
        }
    }
    record Snapshot(String caseRef, String version, String caseType, String organization, String eventRef,
                    List<Material> materials) { }

    Snapshot read(String caseRef, String version, List<Ref> refs);
}
