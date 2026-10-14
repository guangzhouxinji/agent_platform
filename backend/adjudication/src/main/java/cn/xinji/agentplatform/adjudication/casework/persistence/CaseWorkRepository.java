package cn.xinji.agentplatform.adjudication.casework.persistence;

import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import java.util.List;
import java.util.function.UnaryOperator;

/** Persistence and current authority ports. Domain does not depend on JWT, JDBC or model adapters. */
public interface CaseWorkRepository {
    record Scope(String caseId, String organization, String dataScope) {
        public Scope {
            if(caseId==null || !caseId.matches("[A-Za-z0-9_-]{1,120}") || organization==null || organization.isBlank() || organization.length()>200
                    || dataScope==null || dataScope.isBlank() || dataScope.length()>200)throw new IllegalArgumentException("INVALID_CASE_SCOPE");
        }
        public static Scope of(CaseInput input) { return new Scope(input.id(), input.organization(), input.dataScope()); }
    }
    record Receipt(String requestId, String action, String caseId, long workVersion, long inputVersion,
                   Long reportRevision, String reportSha256, String snapshotSha256) { }
    record Original(Source source,String originalText) { }
    record Version(long workVersion, long inputVersion, String snapshotSha256, Audit audit) { }
    interface Access {
        String actor();
        String identity();
        void require(Scope scope, String action);
        long generation();
        void assertUnchanged(long generation);
        void sources(Scope scope, List<Source> sources, String use);
    }
    Receipt create(CaseInput input, String originalText, String key, String fingerprint, Access access);
    Receipt mutate(String caseId, long expectedVersion, String key, String fingerprint, String action,
                   List<Source> incoming, Original original, Access access, UnaryOperator<State> operation);
    State read(String caseId, Long version, Access access);
    List<Version> versions(String caseId, Access access);
    List<Original> originals(String caseId, Access access);
}
