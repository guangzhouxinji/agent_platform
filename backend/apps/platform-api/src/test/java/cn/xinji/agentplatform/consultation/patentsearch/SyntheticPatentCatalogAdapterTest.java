package cn.xinji.agentplatform.consultation.patentsearch;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.synthetic.SyntheticPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticPatentCatalogAdapterTest {
    private final SyntheticPatentCatalogAdapter catalog = new SyntheticPatentCatalogAdapter();

    @Test void searchesTechnicalSolutionWithStableScoreAndEvidence() {
        var command = command(PatentSearchCommand.InputMode.TECHNICAL_SOLUTION,
                "水性粘结剂、双区烘干、在线测厚反馈", List.of(), PatentSearchCommand.SearchScope.empty());
        var first = catalog.search(command);
        var second = catalog.search(command);
        assertFalse(first.candidates().isEmpty());
        assertEquals(first.candidates(), second.candidates());
        assertTrue(first.candidates().stream().anyMatch(candidate -> candidate.sourceId().equals("SYN-PAT-001")));
        for (int index = 1; index < first.candidates().size(); index++) {
            assertTrue(first.candidates().get(index - 1).score() >= first.candidates().get(index).score());
        }
        assertTrue(first.candidates().stream().allMatch(candidate -> candidate.score() > 0 && !candidate.matchingExcerpt().isBlank()));
        assertTrue(first.limitation().contains("120") && first.limitation().contains("拟真合成文献"));
    }

    @Test void confirmedFeaturesReplaceExtractedFeaturesAndScopeFiltersBeforeRanking() {
        var scope = new PatentSearchCommand.SearchScope(List.of("实用新型"), List.of("CN"), List.of("G01B"),
                null, null, LocalDate.parse("2021-01-01"), LocalDate.parse("2022-12-31"));
        var result = catalog.search(command(PatentSearchCommand.InputMode.CORE_FEATURES, "任意原文",
                List.of("在线测厚", "激光测量"), scope));
        assertEquals(List.of("SYN-PAT-002"), result.candidates().stream().map(candidate -> candidate.sourceId()).toList());
        assertTrue(result.actualScope().contains("实用新型"));
    }

    @Test void patentNumberUsesSelectedSyntheticVersionAsQueryAndExcludesItFromResults() {
        var result = catalog.search(command(PatentSearchCommand.InputMode.PATENT_NUMBER,
                "SYN202010001", List.of(), PatentSearchCommand.SearchScope.empty()));
        assertFalse(result.candidates().isEmpty());
        assertTrue(result.candidates().stream().noneMatch(candidate -> candidate.sourceId().equals("SYN-PAT-001")));
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.matchedFeatures().contains("电极涂布")
                || candidate.matchedFeatures().contains("双区烘干")));
    }

    @Test void realisticCatalogHasStableUniqueRecordsAndDiverseScopes() {
        var documents = catalog.documents();
        assertEquals(120, documents.size());
        assertEquals(120, documents.stream().map(value -> value.sourceId()).distinct().count());
        assertEquals(120, documents.stream().map(value -> value.applicationNumber()).distinct().count());
        assertTrue(documents.stream().map(value -> value.classification().substring(0, 1)).distinct().count() >= 6);
        assertTrue(documents.stream().map(value -> value.region()).distinct().count() >= 2);
    }

    private static PatentSearchCommand command(PatentSearchCommand.InputMode mode, String input,
                                                List<String> features, PatentSearchCommand.SearchScope scope) {
        return new PatentSearchCommand(mode, input, "TECHNICAL_REFERENCE", features, scope);
    }
}
