package cn.xinji.agentplatform.consultation.trademarklibrary.domain;

import java.time.LocalDate;
import java.util.List;

public record TrademarkLibraryRecord(
        String sourceId,
        String sourceRecordId,
        String sourceVersion,
        String registrationNumber,
        String name,
        String imageRef,
        List<String> applicants,
        String applicantAddress,
        List<ClassDetail> classes,
        LocalDate registrationDate,
        LocalDate validUntil,
        String sourceStatus,
        String displayStatus,
        String sourceUpdatedAt,
        String sourceRef,
        String dataMode) {
    public TrademarkLibraryRecord {
        applicants = List.copyOf(applicants);
        classes = List.copyOf(classes);
    }

    public record ClassDetail(String code, String name, List<String> goodsServices, List<String> similarGroups) {
        public ClassDetail {
            goodsServices = List.copyOf(goodsServices);
            similarGroups = List.copyOf(similarGroups);
        }
    }

    public record HistoryResult(String type, String state, List<HistoryItem> records, String error) {
        public HistoryResult { records = List.copyOf(records); }
    }

    public record HistoryItem(String sourceRecordId, String businessDate, String matter, String status,
                              String result, String beforeValue, String afterValue, String authority, String sourceRef) {}
}
