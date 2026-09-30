package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductPatentSearchFiltersTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void parsesCanonicalMetadataFiltersAndAppliesAndSemantics() throws Exception {
        var filters = ProductPatentSearchFilters.parse(mapper, """
                {"publicationFrom":"2020-01-01","publicationTo":"2023-12-31",
                 "ipcPrefixes":["h01m"],"cpcPrefixes":["Y02E 60"]}
                """);

        assertThat(filters.version()).isEqualTo("PATENT_FILTER/v1");
        assertThat(filters.requiredSourceFields())
                .containsExactlyInAnyOrder("PUBLICATION_DATE", "IPC", "CPC");
        assertThat(filters.matches(LocalDate.parse("2022-05-27"),
                List.of("H01M 10/0525 20100101"), List.of("Y02E 60/10"))).isTrue();
        assertThat(filters.matches(LocalDate.parse("2019-05-27"),
                List.of("H01M 10/0525"), List.of("Y02E 60/10"))).isFalse();
        assertThat(filters.matches(LocalDate.parse("2022-05-27"),
                List.of("G06F 1/00"), List.of("Y02E 60/10"))).isFalse();
    }

    @Test void rejectsReversedDatesAndDuplicateOrMalformedClassifications() {
        assertThatThrownBy(() -> ProductPatentSearchFilters.parse(mapper,
                "{\"publicationFrom\":\"2024-01-01\",\"publicationTo\":\"2023-01-01\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductPatentSearchFilters.validated(null, null,
                List.of("H01M", "h01m"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductPatentSearchFilters.validated(null, null,
                List.of("H01M;DROP"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
