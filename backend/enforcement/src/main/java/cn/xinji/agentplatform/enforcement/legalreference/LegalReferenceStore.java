package cn.xinji.agentplatform.enforcement.legalreference;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static cn.xinji.agentplatform.enforcement.legalreference.LegalReferenceContract.*;

public interface LegalReferenceStore {
    Optional<SearchResult> search(String id, long revision);
    Optional<ReferenceRevision> reference(String id, long revision);
    List<ReferenceRevision> history(String id);
    long latestReferenceVersion(String id);
    void addSearch(SearchResult result);
    void addReference(ReferenceRevision reference);
    Receipt command(String key, String fingerprint, String actor, String operation,
                    Supplier<Receipt> action);
}
