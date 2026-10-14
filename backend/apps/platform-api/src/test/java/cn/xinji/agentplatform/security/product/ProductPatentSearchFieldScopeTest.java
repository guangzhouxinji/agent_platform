package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Final field-request contract C, not a source admission or product request bypass. */
class ProductPatentSearchFieldScopeTest {
    @Test void explicitTextFieldsNeedNotIncludeTitleAndOrderingDoesNotChangeNewScope() {
        var one = ProductPatentSearchFieldScope.from(List.of("CLAIMS","ABSTRACT"),ProductPatentSearchFilters.none());
        var two = ProductPatentSearchFieldScope.from(List.of("ABSTRACT","CLAIMS"),ProductPatentSearchFilters.none());
        assertThat(one).isEqualTo(two); assertThat(one.legacyTitle()).isFalse();
        assertThat(one.expansion("CLAIMS:battery").version()).contains("PATENT_QUERY_FIELDS/v2");
        var codec = new ProductPatentSearchEvidenceCodec(new ObjectMapper());
        assertThat(one.scopeSha(codec,List.of("b","a"),"filter","{}"))
                .isEqualTo(two.scopeSha(codec,List.of("a","b"),"filter","{}"));
        assertThatThrownBy(() -> one.syntax("TITLE:battery"))
                .isInstanceOf(cn.xinji.agentplatform.consultation.patenttask.source.PatentQuerySyntax.InvalidQuery.class);
    }
    @Test void requestedFieldsMustBeExactTextPlusActualFilters() {
        var filters = ProductPatentSearchFilters.validated(LocalDate.of(2020,1,1),null,List.of("H01"),List.of());
        var scope = ProductPatentSearchFieldScope.from(List.of("CLAIMS","PUBLICATION_DATE","IPC"),filters);
        assertThat(scope.textFields()).containsExactly("CLAIMS");
        assertThat(scope.requestedFields()).containsExactly("CLAIMS","IPC","PUBLICATION_DATE");
        assertThatThrownBy(() -> ProductPatentSearchFieldScope.from(List.of("CLAIMS"),filters))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductPatentSearchFieldScope.from(List.of("CLAIMS","IPC"),ProductPatentSearchFilters.none()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void emptyUnknownDuplicateAndMetadataOnlyCannotFallBackToTitle() {
        for (var invalid : List.of(List.<String>of(),List.of("UNKNOWN"),List.of("CLAIMS","CLAIMS"),
                List.of("PUBLICATION_DATE"),List.of("TITLE","CLAIMS","PERSONAL_DATA")))
            assertThatThrownBy(() -> ProductPatentSearchFieldScope.from(invalid,ProductPatentSearchFilters.none()))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductPatentSearchFieldScope.from(null,ProductPatentSearchFilters.none()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyTitleScopeAndExpansionRemainByteCompatibleAndImmutable() {
        var scope = ProductPatentSearchFieldScope.from(List.of("TITLE"),ProductPatentSearchFilters.none());
        var codec = new ProductPatentSearchEvidenceCodec(new ObjectMapper());
        assertThat(scope.legacyTitle()).isTrue();
        assertThat(scope.scopeSha(codec,List.of("source"),"filter","{}"))
                .isEqualTo(codec.digest("source","TITLE","filter","{}"));
        assertThat(scope.expansion("battery")).isEqualTo(
                new cn.xinji.agentplatform.consultation.patenttask.source.PatentQueryExpansion().expand("battery"));
        assertThatThrownBy(() -> scope.textFields().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
