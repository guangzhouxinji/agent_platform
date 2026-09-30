package cn.xinji.agentplatform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MemoryStorageProfileBoundaryTest {
    private static final List<String> MEMORY_BEANS = List.of(
            "inMemoryConsultationStore", "inMemoryEnforcementStore", "inMemoryAdjudicationStore",
            "inMemoryCaseAnnotationWorkflowStore", "inMemoryPatentComparisonTaskRepository",
            "inMemoryTrademarkComparisonTaskRepository");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConsultationStorageConfiguration.class, EnforcementStorageConfiguration.class,
                    AdjudicationStorageConfiguration.class, CaseAnnotationStorageConfiguration.class,
                    PatentComparisonStorageConfiguration.class, TrademarkComparisonStorageConfiguration.class);

    @Test
    void ordinaryProfileNeverFallsBackToMemoryEvenWhenExplicitlyRequested() {
        runner.withPropertyValues(
                "consultation.storage=memory", "enforcement.storage=memory", "adjudication.storage=memory",
                "case-annotation.storage=memory", "patent-comparison.storage=memory",
                "trademark-comparison.storage=memory", "patent-search.storage=memory",
                "trademark-search.storage=memory")
                .run(context -> MEMORY_BEANS.forEach(name -> assertThat(context.containsBean(name)).as(name).isFalse()));
        new ApplicationContextRunner().withUserConfiguration(PatentSearchConfiguration.class)
                .withPropertyValues("patent-search.storage=memory", "trademark-search.storage=memory")
                .run(context -> {
                    assertThat(context.containsBean("inMemoryPatentSearchTaskRepository")).isFalse();
                    assertThat(context.containsBean("inMemoryTrademarkSearchTaskRepository")).isFalse();
                    assertThat(context.containsBean("patentSearchCatalog")).isFalse();
                });
    }

    @Test
    void historicalTestProfileRetainsMemoryRepositories() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test-synthetic"))
                .run(context -> MEMORY_BEANS.forEach(name -> assertThat(context.containsBean(name)).as(name).isTrue()));
    }
}
