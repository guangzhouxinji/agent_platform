package cn.xinji.agentplatform.consultation.patentcomparison;

/** Exact original evidence supplied by an authorized source adapter, never normalized-text offsets. */
public interface OriginalClaimText {
    record Evidence(String documentVersion, String originalRef, String originalSha,
                    String jsonPointer, int byteStart, int byteEnd, String spanSha, String text) { }
    String text();
    Evidence evidence(int characterStart, int characterEnd);
}
