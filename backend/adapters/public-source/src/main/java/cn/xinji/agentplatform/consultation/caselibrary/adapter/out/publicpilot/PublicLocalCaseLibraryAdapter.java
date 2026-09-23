package cn.xinji.agentplatform.consultation.caselibrary.adapter.out.publicpilot;

import cn.xinji.agentplatform.consultation.caselibrary.application.CaseLibraryPort;
import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@Profile("!test-synthetic")
@ConditionalOnProperty(name = "knowledge-base.provider", havingValue = "classpath")
public final class PublicLocalCaseLibraryAdapter implements CaseLibraryPort {
    private final List<CaseLibraryRecord> records;
    public PublicLocalCaseLibraryAdapter(PublicPilotCaseLoader loader) { records = List.copyOf(loader.records()); }
    @Override public List<CaseLibraryRecord> records() { return records; }
    @Override public Optional<CaseLibraryRecord> find(String sourceId) { return records.stream().filter(v -> v.sourceRecordId().equals(sourceId)).findFirst(); }
    @Override public String sourceVersion() { return "public-local-case-v1"; }
    @Override public String dataMode() { return "PUBLIC_LOCAL"; }
}
