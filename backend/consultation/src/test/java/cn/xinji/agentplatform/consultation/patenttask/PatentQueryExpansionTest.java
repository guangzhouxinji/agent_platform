package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentQueryExpansion;
import org.junit.jupiter.api.Test;

class PatentQueryExpansionTest {
    private final PatentQueryExpansion expansion = new PatentQueryExpansion();

    @Test void addsVersionedTraceableTermsWithoutReplacingOriginalQuery() {
        var plan = expansion.expand("Lithium battery energy storage");
        assertThat(plan.version()).isEqualTo("QUERY_EXPANSION/v1");
        assertThat(plan.originalQuery()).isEqualTo("Lithium battery energy storage");
        assertThat(plan.retrievalQuery()).isEqualTo("lithium battery energy storage accumulator");
        assertThat(plan.expansions()).singleElement().satisfies(term -> {
            assertThat(term.original()).isEqualTo("battery");
            assertThat(term.expansion()).isEqualTo("accumulator");
            assertThat(term.relation()).isEqualTo("RELATED_TERM");
            assertThat(term.source()).isEqualTo("PROJECT_PATENT_TERMS/2026-09-29");
        });
    }

    @Test void preservesUnknownQueriesAndRejectsMissingInput() {
        assertThat(expansion.expand("quantum telemetry").retrievalQuery())
                .isEqualTo("quantum telemetry");
        assertThatThrownBy(() -> expansion.expand(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
