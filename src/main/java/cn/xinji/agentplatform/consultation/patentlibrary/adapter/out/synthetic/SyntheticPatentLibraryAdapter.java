package cn.xinji.agentplatform.consultation.patentlibrary.adapter.out.synthetic;

import cn.xinji.agentplatform.consultation.patentlibrary.application.PatentLibraryPort;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.*;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.synthetic.SyntheticPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentDocument;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public final class SyntheticPatentLibraryAdapter implements PatentLibraryPort {
    private static final String VERSION = "synthetic-patent-library-v1";
    private static final String[] APPLICANTS = {"宁江瑞衡智能装备有限公司", "临川知禾新材料研究院", "东港澄岳科技股份有限公司", "海陵启辰工业技术有限公司"};
    private static final String[] INVENTORS = {"陈启明", "周若衡", "沈知远", "梁清和", "顾宁川", "许文岚"};
    private final List<PatentLibraryRecord> records;

    public SyntheticPatentLibraryAdapter(SyntheticPatentCatalogAdapter catalog) {
        records = catalog.documents().stream().map(SyntheticPatentLibraryAdapter::record).toList();
    }

    @Override public List<PatentLibraryRecord> records() { return records; }
    @Override public Optional<PatentLibraryRecord> find(String sourceId) { return records.stream().filter(value -> value.sourceRecordId().equals(sourceId)).findFirst(); }
    @Override public String sourceVersion() { return VERSION; }

    @Override public EventResult events(String sourceId) {
        int index = index(sourceId);
        if (index % 13 == 0) return new EventResult("SOURCE_NOT_PROVIDED", List.of(), "合成来源未提供该记录的历史事件范围");
        PatentLibraryRecord record = find(sourceId).orElseThrow();
        String versionId = record.versions().getLast().versionId();
        List<PatentEvent> values = new ArrayList<>();
        values.add(event(sourceId, "APPLICATION_FILED", record.applicationDate(), "申请已提交", versionId, 1));
        values.add(event(sourceId, "PUBLICATION", record.publicationDate(), "文献公开", versionId, 2));
        if (record.grantDate() != null) values.add(event(sourceId, "GRANT", record.grantDate(), "授权公告", versionId, 3));
        if (index % 5 == 0) values.add(event(sourceId, "INVALIDATION_REQUEST", LocalDate.of(2025, 3, 12).plusDays(index), "无效宣告请求已受理，尚无决定", versionId, 4));
        if (index % 7 == 0) values.add(event(sourceId, "INVALIDATION_DECISION", LocalDate.of(2025, 7, 18).plusDays(index), "无效宣告请求审查决定：维持有效", versionId, 5));
        values.sort(Comparator.comparing(PatentEvent::businessDate).reversed().thenComparing(PatentEvent::eventId));
        return new EventResult(values.isEmpty() ? "NO_RECORDS" : "HAS_RECORDS", values, null);
    }

    private static PatentLibraryRecord record(PatentDocument value) {
        int index = index(value.sourceId());
        boolean design = "外观设计".equals(value.patentType());
        LocalDate grantDate = index % 6 == 0 ? null : value.publicationDate().plusMonths(design ? 3 : 10);
        String status = grantDate == null ? "申请公开" : index % 9 == 0 ? "专利权终止" : "专利权有效";
        String grantNumber = grantDate == null ? null : value.publicationNumber().replaceAll("[A-Z]$", design ? "S" : "B");
        List<DocumentVersion> versions = new ArrayList<>();
        if (index % 10 == 0) versions.add(version(value, index, value.version() + "-A", value.publicationNumber(), value.publicationDate(), "公开文本", false));
        versions.add(version(value, index, value.version() + (index % 10 == 0 ? "-B" : ""), grantNumber == null ? value.publicationNumber() : grantNumber,
                grantDate == null ? value.publicationDate() : grantDate, grantDate == null ? "公开文本" : "授权文本", true));
        return new PatentLibraryRecord("SYNTHETIC_PATENT_CATALOG", value.sourceId(), value.version(), value.applicationNumber(),
                value.publicationNumber(), grantNumber, value.title(), value.patentType(),
                index % 12 == 0 ? List.of(APPLICANTS[index % APPLICANTS.length], "云岑联合创新中心") : List.of(APPLICANTS[index % APPLICANTS.length]),
                List.of(INVENTORS[index % INVENTORS.length], INVENTORS[(index + 2) % INVENTORS.length]),
                index % 17 == 0 ? null : "宁江市高新区创新大道" + (100 + index) + "号", value.applicationDate(), value.publicationDate(), grantDate,
                index % 4 == 0 ? List.of(new PriorityItem("SYN-PRIORITY-" + String.format("%04d", index), value.applicationDate().minusMonths(8), "CN")) : List.of(),
                design ? List.of() : List.of(value.classification()), design ? List.of() : List.of(cpc(value.classification())),
                design ? value.classification() : null, "SYNTHETIC_SOURCE_" + status, status, grantDate == null ? value.publicationDate() : grantDate,
                value.publicationDate().plusDays(21).atTime(9, 30).toString(), "synthetic://patents/" + value.sourceId(), "SYNTHETIC_V0", versions);
    }

    private static DocumentVersion version(PatentDocument value, int index, String id, String number, LocalDate date, String kind, boolean latest) {
        String base = "synthetic://patents/" + value.sourceId() + "/documents/" + id;
        ContentSection abstractSection = complete("摘要", "ABS-001", value.abstractText(), base + "#abstract");
        ContentSection claims;
        if ("外观设计".equals(value.patentType())) claims = state(index % 2 == 0 ? "NOT_APPLICABLE" : "SOURCE_NOT_PROVIDED", base + "#claims", "该文献类型不适用或合成来源未提供权利要求书");
        else claims = complete("权利要求1", "CLM-001", latest ? value.claimsText() : value.claimsText().replace("权利要求1", "权利要求1（公开文本）"), base + "#claims");
        ContentSection description = index % 17 == 0
                ? state("PARSE_FAILED", base + "#description", "合成解析失败；保留原文引用，未标记为完整全文")
                : new ContentSection(index % 11 == 0 ? "PARTIAL" : "COMPLETE", List.of(
                        new LocatedText("[0001]", "DESC-001", "本申请涉及" + value.title() + "的技术领域。"),
                        new LocatedText("[0002]", "DESC-002", value.abstractText()),
                        new LocatedText("[0003]", "DESC-003", "本段为可定位的合成实施方式文本。")), base + "#description", index % 11 == 0 ? "合成来源仅提供部分段落" : null);
        ContentSection figures = index % 8 == 0 ? state("SOURCE_NOT_PROVIDED", base + "#figures", "合成来源未提供附图")
                : new ContentSection("COMPLETE", List.of(new LocatedText("图1", "FIG-001", "结构示意图元数据（不开放图片下载）")), base + "#figures", null);
        return new DocumentVersion(id, number, date, kind, abstractSection, claims, description, figures, base);
    }

    private static ContentSection complete(String label, String locator, String text, String ref) { return new ContentSection("COMPLETE", List.of(new LocatedText(label, locator, text)), ref, null); }
    private static ContentSection state(String state, String ref, String message) { return new ContentSection(state, List.of(), ref, message); }
    private static PatentEvent event(String sourceId, String type, LocalDate date, String result, String versionId, int order) { return new PatentEvent(sourceId + "-EVT-" + order, type, date, result, versionId, "合成专利业务来源", "synthetic://patents/" + sourceId + "/events/" + order); }
    private static int index(String sourceId) { return Integer.parseInt(sourceId.substring(sourceId.lastIndexOf('-') + 1)); }
    private static String cpc(String ipc) { return ipc.matches(".*[A-Z]$") ? ipc.substring(0, ipc.length() - 1) : ipc; }
}
