package cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Protocol-neutral view of the development center catalog. */
public interface MockCenterCatalogClient {
    Metadata metadata();
    Page search(String text, String publicationNumber, int page, int pageSize);
    Optional<PatentRecord> find(String recordId);

    record Metadata(long recordCount, String catalogVersion, String latestImportBatch) {}
    record Page(List<PatentRecord> items, int page, int pageSize, long total) {
        public Page { items = List.copyOf(items); }
    }
    record PatentRecord(String recordId, String sourceId, String sourceRecordId, String sourceVersion,
                        String publicationNumber, String applicationNumber, String title, List<String> applicants,
                        List<String> inventors, LocalDate applicationDate, LocalDate publicationDate, List<String> ipc,
                        String abstractText, String abstractState, String sourceUrl, String collectedAt,
                        String contentDigest, String catalogVersion) {
        public PatentRecord {
            applicants = List.copyOf(applicants);
            inventors = List.copyOf(inventors);
            ipc = List.copyOf(ipc);
        }
    }
}
