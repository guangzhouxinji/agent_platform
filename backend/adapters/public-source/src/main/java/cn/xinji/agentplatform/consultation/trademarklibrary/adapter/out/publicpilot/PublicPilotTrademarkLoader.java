package cn.xinji.agentplatform.consultation.trademarklibrary.adapter.out.publicpilot;

import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord;
import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord.ClassDetail;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class PublicPilotTrademarkLoader {
    private static final String RESOURCE = "/public-pilot/public-trademarks-v1.json";
    private final List<TrademarkLibraryRecord> records;

    public PublicPilotTrademarkLoader(ObjectMapper mapper) {
        try (InputStream input = PublicPilotTrademarkLoader.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing public pilot trademark resource: " + RESOURCE);
            records = mapper.readValue(input, new TypeReference<List<Item>>() {}).stream().map(PublicPilotTrademarkLoader::map).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load public pilot trademarks", exception);
        }
    }

    public List<TrademarkLibraryRecord> records() { return records; }

    private static TrademarkLibraryRecord map(Item item) {
        List<ClassDetail> classes = item.classes().stream()
                .map(value -> new ClassDetail(value.code(), value.name(), value.goodsServices(), value.similarGroups())).toList();
        String displayStatus = item.gazetteEvent() == null ? "来源事件未采集" : "公报事件：" + item.gazetteEvent();
        return new TrademarkLibraryRecord(item.sourceId(), item.sourceRecordId(), item.sourceVersion(),
                item.registrationNumber(), item.name(), item.imageRef(), item.applicants(), item.applicantAddress(), classes,
                date(item.registrationDate()), date(item.validUntil()), item.sourceStatusCode(), displayStatus,
                item.sourceUpdatedAt(), item.sourceRef(), "PUBLIC_PILOT");
    }

    private static LocalDate date(String value) { return value == null || value.isBlank() ? null : LocalDate.parse(value); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Item(String sourceId, String sourceRecordId, String sourceVersion, String registrationNumber,
                        String name, String imageRef, List<String> applicants, String applicantAddress,
                        List<ClassItem> classes, String registrationDate, String validUntil, String gazetteEvent,
                        String sourceStatusCode, String sourceUpdatedAt, String sourceRef) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClassItem(String code, String name, List<String> goodsServices, List<String> similarGroups) {}
}
