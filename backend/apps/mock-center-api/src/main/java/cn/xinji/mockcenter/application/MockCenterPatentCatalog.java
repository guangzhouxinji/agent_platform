package cn.xinji.mockcenter.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Development-only catalog boundary. It never represents the real center system. */
public interface MockCenterPatentCatalog {
    Metadata metadata();
    Page search(Query query);
    Optional<PatentRecord> find(String recordId);

    record PatentRecord(String recordId, String sourceId, String sourceRecordId, String sourceVersion,
                        String publicationNumber, String applicationNumber, String title, List<String> applicants,
                        List<String> inventors, LocalDate applicationDate, LocalDate publicationDate, List<String> ipc,
                        String abstractText, String abstractState, String sourceUrl, String collectedAt,
                        String contentDigest, String catalogVersion) {
        public PatentRecord { applicants = List.copyOf(applicants); inventors = List.copyOf(inventors); ipc = List.copyOf(ipc); }
    }
    record Query(String text, String publicationNumber, int page, int pageSize) {}
    record Page(List<PatentRecord> items, int page, int pageSize, long total) { public Page { items = List.copyOf(items); } }
    record Metadata(long recordCount, String catalogVersion, String latestImportBatch) {}
}
