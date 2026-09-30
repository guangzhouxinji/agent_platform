package cn.xinji.agentplatform.enforcement.material;

import java.util.List;

/** A source adapter returns ordered, opaque continuation positions, never an inferred empty page. */
public interface MaterialPageSource {
    final class Failure extends IllegalStateException {
        private final String code;
        public Failure(String code, String message) { super(message); this.code = code; }
        public Failure(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
        public String code() { return code; }
    }
    record Entry(String nextCursor, MaterialProductService.ObservationInput observation) { }
    record Page(List<Entry> entries, boolean complete, String failureCode) { }
    Page fetch(String sourceId, String afterCursor);
    default Page fetch(String sourceId, String afterCursor, String credentialRef) {
        return fetch(sourceId, afterCursor);
    }
}
