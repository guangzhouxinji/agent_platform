package cn.xinji.agentplatform.consultation.patentlibrary.adapter.out.publicpilot;

import cn.xinji.agentplatform.consultation.patentlibrary.application.PatentLibraryPort;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@Profile({"public-local-test", "dm8-integration"})
@ConditionalOnProperty(name = "knowledge-base.provider", havingValue = "classpath")
public final class PublicLocalPatentLibraryAdapter implements PatentLibraryPort {
    private final List<PatentLibraryRecord> records;
    public PublicLocalPatentLibraryAdapter(LocalPublicPatentLoader loader) { records = List.copyOf(loader.records()); }
    @Override public List<PatentLibraryRecord> records() { return records; }
    @Override public Optional<PatentLibraryRecord> find(String sourceId) { return records.stream().filter(v -> v.sourceRecordId().equals(sourceId)).findFirst(); }
    @Override public String sourceVersion() { return "public-local-patent-v1"; }
    @Override public PatentLibraryRecord.EventResult events(String sourceId) {
        if (find(sourceId).isEmpty()) throw new IllegalArgumentException("patent not found");
        return new PatentLibraryRecord.EventResult("SOURCE_NOT_PROVIDED", List.of(), "当前公开快照未采集法律事件");
    }
}
