package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patenttask.source.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Mechanical query tests only: constructed text is not a patent corpus or source-admission evidence. */
class PatentFieldQueryTest {
    private final PatentQuerySyntax syntax = new PatentQuerySyntax();
    private final PatentQueryRetrieval retrieval = new PatentQueryRetrieval();
    private PatentLexicalQuery.Document doc(String id, Map<String,String> fields) {
        return new PatentLexicalQuery.Document("mechanical-source", id, "raw-v1", "capture-v1",
                "a".repeat(64), "original.json", fields);
    }

    @Test void titleHistoryIsUnchangedAndNewPlanIsDistinct() {
        var documents = List.of(doc("CN104900909A", Map.of("TITLE", "lithium battery coating")),
                doc("CN104900910A", Map.of("TITLE", "solar coating")));
        for (String query : List.of("lithium battery", "TITLE:battery AND NOT solar", "CN104900909A")) {
            var old = retrieval.prepare(query, documents, 100);
            var selected = retrieval.prepareFields(query, documents, Set.of("TITLE"), 100);
            assertThat(selected.documents()).isEqualTo(old.documents());
            assertThat(selected.lexical()).isEqualTo(old.lexical());
            assertThat(selected.expansion().retrievalQuery()).isEqualTo(old.expansion().retrievalQuery());
            assertThat(selected.expansion().version()).contains(PatentQuerySyntax.FIELD_VERSION);
            assertThat(old.expansion().version()).doesNotContain(PatentQuerySyntax.FIELD_VERSION);
        }
        assertThat(syntax.parse("TITLE:battery", Set.of("TITLE")).version()).isEqualTo("PATENT_QUERY/v1");
    }

    @Test void unqualifiedOperandsAreScopedAndPhrasesNeverCrossFields() {
        var document = doc("CN104900909A", Map.of("TITLE", "lithium", "ABSTRACT", "battery system"));
        var fields = Set.of("TITLE", "ABSTRACT");
        assertThat(syntax.parseFields("lithium AND battery", fields).matches(document)).isTrue();
        assertThat(syntax.parseFields("\"lithium battery\"", fields).matches(document)).isFalse();
        assertThat(syntax.parseFields("ABSTRACT:\"battery system\"", fields).matches(document)).isTrue();
        assertThat(syntax.parseFields("TITLE:(battery OR system)", fields).matches(document)).isFalse();
        assertThat(syntax.parseFields("battery OR system AND lithium", fields).matches(document)).isTrue();
    }

    @Test void onlySelectedFieldsParticipateInSoftRetrievalAndLeaveTheDocumentPool() {
        var documents = List.of(doc("CN104900909A", Map.of("TITLE", "battery", "ABSTRACT", "optical circuit",
                        "CLAIMS", "battery battery battery", "DESCRIPTION", "solar")),
                doc("CN104900910A", Map.of("TITLE", "optical", "ABSTRACT", "battery control")));
        var result = retrieval.prepareFields("battery", documents, Set.of("ABSTRACT"), 10);
        assertThat(result.lexical().matches()).extracting(PatentBm25fQuery.Match::documentId).containsExactly("CN104900910A");
        assertThat(result.documents()).allSatisfy(d -> assertThat(d.fields()).containsOnlyKeys("ABSTRACT"));
        assertThat(result.lexical().matches().getFirst().matchFields()).containsExactly("ABSTRACT");
        assertThat(result.lexical().matches().getFirst().fieldScores()).containsOnlyKeys("ABSTRACT");
        assertThat(documents.getFirst().fields()).containsKeys("TITLE", "CLAIMS", "DESCRIPTION");
    }

    @Test void negativeConstraintsAndExpansionRemainInsideTheHardAst() {
        var documents = List.of(doc("CN104900909A", Map.of("CLAIMS", "battery control", "DESCRIPTION", "optical")),
                doc("CN104900910A", Map.of("CLAIMS", "accumulator control", "DESCRIPTION", "optical")),
                doc("CN104900911A", Map.of("CLAIMS", "battery control", "DESCRIPTION", "solar")));
        var result = retrieval.prepareFields("CLAIMS:battery AND NOT DESCRIPTION:solar", documents,
                Set.of("CLAIMS", "DESCRIPTION"), 10);
        assertThat(result.documents()).extracting(PatentLexicalQuery.Document::documentId).containsExactly("CN104900909A");
        assertThat(result.expansion().retrievalQuery()).contains("battery", "accumulator")
                .doesNotContain("CLAIMS", "DESCRIPTION", "solar", "NOT");
        assertThat(result.lexical().matches()).hasSize(1);
    }

    @Test void explicitUnselectedUnknownAndEmptyFieldScopesRejectRatherThanFallingBack() {
        for (String query : List.of("TITLE:battery", "CLAIMS:battery", "LEGAL_STATUS:active", "IPC:H01M"))
            assertThatThrownBy(() -> syntax.parseFields(query, Set.of("ABSTRACT")))
                    .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
        for (Set<String> fields : List.of(Set.<String>of(), Set.of("LEGAL_STATUS"), Set.of("title")))
            assertThatThrownBy(() -> syntax.parseFields("battery", fields))
                    .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
        assertThatThrownBy(() -> syntax.parseFields("battery", null))
                .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
        assertThatThrownBy(() -> syntax.parse("CLAIMS:battery", Set.of("TITLE", "CLAIMS")))
                .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
    }

    @Test void missingSelectedContentIsNotSubstitutedWithAnotherField() {
        var result = retrieval.prepareFields("battery", List.of(doc("CN104900909A", Map.of("TITLE", "battery"))),
                Set.of("ABSTRACT"), 10);
        assertThat(result.lexical().matches()).isEmpty();
        assertThat(result.documents().getFirst().fields()).isEmpty();
        assertThat(retrieval.prepareFields("battery", List.of(), Set.of("CLAIMS"), 10).lexical().candidateCount()).isZero();
    }

    @Test void exactNumberAndBoundedBooleanFailuresKeepTheirExistingMeaning() {
        var documents = List.of(doc("CN104900909A", Map.of("CLAIMS", "battery")));
        var result = retrieval.prepareFields("DOCUMENT_ID:CN104900909A", documents, Set.of("CLAIMS"), 10);
        assertThat(result.lexical().methodVersion()).isEqualTo("EXACT_PUBLICATION/v1");
        assertThat(result.lexical().matches()).hasSize(1);
        for (String query : List.of("NOT battery", "battery OR NOT coating", "CLAIMS:", "\"battery",
                "DOCUMENT_ID:CN104900909A OR battery", "battery AND", "battery*", "x ".repeat(129)))
            assertThatThrownBy(() -> syntax.parseFields(query, Set.of("CLAIMS")))
                    .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
    }

    @Test void invalidDocumentsAndDuplicatesRejectEvenIfHardConstraintsWouldExcludeThem() {
        var document = doc("CN104900909A", Map.of("TITLE", "solar"));
        assertThatThrownBy(() -> retrieval.prepareFields("TITLE:battery", List.of(document, document), Set.of("TITLE"), 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
        var invalid = new PatentLexicalQuery.Document("source", "id", "v", "v", "bad-sha", "raw", Map.of("TITLE", "solar"));
        assertThatThrownBy(() -> retrieval.prepareFields("TITLE:battery", List.of(invalid), Set.of("TITLE"), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retrieval.prepareFields("battery", null, Set.of("TITLE"), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retrieval.prepareFields("battery", List.of(), Set.of("TITLE"), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
