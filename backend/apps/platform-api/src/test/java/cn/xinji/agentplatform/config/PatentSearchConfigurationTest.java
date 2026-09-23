package cn.xinji.agentplatform.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import cn.xinji.agentplatform.consultation.adapter.out.dm8.Dm8ConsultationPatentSearchAdapter;
import cn.xinji.agentplatform.knowledgebase.adapter.out.dm8.Dm8KnowledgeBaseJsonReader;
import org.junit.jupiter.api.Test;

class PatentSearchConfigurationTest {
    private final PatentSearchConfiguration configuration = new PatentSearchConfiguration();
    private final Dm8KnowledgeBaseJsonReader reader = mock(Dm8KnowledgeBaseJsonReader.class);

    @Test
    void databaseProviderRequiresDm8KnowledgeBase() {
        assertThrows(IllegalArgumentException.class,
                () -> configuration.databaseConsultationPatentSearch(reader, "classpath"));
    }

    @Test
    void databaseProviderBuildsReadOnlyLibraryAdapterForDm8() {
        assertInstanceOf(Dm8ConsultationPatentSearchAdapter.class,
                configuration.databaseConsultationPatentSearch(reader, "dm8"));
    }
}
