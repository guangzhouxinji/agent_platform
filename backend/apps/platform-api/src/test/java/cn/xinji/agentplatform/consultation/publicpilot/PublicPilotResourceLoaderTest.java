package cn.xinji.agentplatform.consultation.publicpilot;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.consultation.caselibrary.adapter.out.publicpilot.PublicPilotCaseLoader;
import cn.xinji.agentplatform.consultation.patentlibrary.adapter.out.publicpilot.CnTopicPublicPatentLoader;
import cn.xinji.agentplatform.consultation.trademarklibrary.adapter.out.publicpilot.PublicPilotTrademarkLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PublicPilotResourceLoaderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void loadsExactlyOneHundredRecordsForEachImportedLibrary() {
        var patents = new CnTopicPublicPatentLoader(mapper).records();
        var trademarks = new PublicPilotTrademarkLoader(mapper).records();
        var cases = new PublicPilotCaseLoader(mapper).records();

        assertThat(patents).hasSize(100).allMatch(record -> "PUBLIC_PILOT".equals(record.dataMode()));
        assertThat(trademarks).hasSize(100).allMatch(record -> "PUBLIC_PILOT".equals(record.dataMode()));
        assertThat(cases).hasSize(100).allMatch(record -> "PUBLIC_PILOT".equals(record.dataMode()));
        assertThat(patents).allMatch(record -> record.applicationNumber() == null && record.versions().getFirst().claimsSection().texts().isEmpty());
        assertThat(trademarks).allMatch(record -> record.registrationNumber() == null && record.registrationDate() == null);
        assertThat(cases).allMatch(record -> record.decisionDate() == null && record.annotations().isEmpty());
    }
}
