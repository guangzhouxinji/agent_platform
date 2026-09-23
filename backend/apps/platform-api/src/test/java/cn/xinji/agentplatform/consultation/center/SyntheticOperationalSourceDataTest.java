package cn.xinji.agentplatform.consultation.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.SyntheticAdjudicationSourceAdapter;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.SyntheticEnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort.EvidenceData;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SyntheticOperationalSourceDataTest {
    @Test void enforcementDataHasStableIdsValidReferencesDatesAndExceptionDistribution() {
        var first = new SyntheticEnforcementSourceAdapter();
        var second = new SyntheticEnforcementSourceAdapter();
        var clues = first.clues();
        var evidence = first.evidence();
        Set<String> clueIds = clues.stream().map(value -> value.source().sourceId()).collect(Collectors.toSet());
        Set<String> evidenceIds = evidence.stream().map(value -> value.source().sourceId()).collect(Collectors.toSet());

        assertEquals(30, clues.size());
        assertEquals(clues.size(), clueIds.size());
        assertEquals(119, evidence.size());
        assertEquals(evidence.size(), evidenceIds.size());
        assertEquals(clues, second.clues());
        assertEquals(evidence, second.evidence());
        assertTrue(clues.stream().anyMatch(value -> "v2".equals(value.source().version())));
        assertTrue(clues.stream().anyMatch(value -> !value.missingItems().isEmpty()));
        assertTrue(clues.stream().flatMap(value -> value.conflicts().stream()).findAny().isPresent());

        clues.forEach(clue -> clue.evidenceMentions().forEach(mention -> {
            assertTrue(evidenceIds.contains(mention.value()), "missing evidence " + mention.value());
            assertEquals(clue.source().sourceId(), first.findEvidence(mention.value()).orElseThrow().relatedClueId());
        }));
        evidence.forEach(value -> {
            assertTrue(clueIds.contains(value.relatedClueId()), "missing clue " + value.relatedClueId());
            assertDateOrder(value);
        });
        assertTrue(evidence.stream().anyMatch(value -> "SOURCE_UNAVAILABLE".equals(value.availability())));
        assertTrue(evidence.stream().anyMatch(value -> "UNSUPPORTED_CONTENT".equals(value.availability())));
        assertTrue(evidence.stream().anyMatch(value -> "CONFLICT".equals(value.relationType())));
        assertTrue(evidence.stream().anyMatch(value -> value.collector() == null && value.collectionMissingReason() != null));
    }

    @Test void adjudicationMaterialsHaveStableCaseVersionLinksAndDiverseExceptions() {
        var first = new SyntheticAdjudicationSourceAdapter();
        var second = new SyntheticAdjudicationSourceAdapter();
        var cases = first.cases();
        var materials = first.materialItems();
        Set<String> caseIds = cases.stream().map(value -> value.id()).collect(Collectors.toSet());
        Set<String> materialIds = materials.stream().map(value -> value.source().id()).collect(Collectors.toSet());

        assertEquals(20, cases.size());
        assertEquals(cases.size(), caseIds.size());
        assertEquals(80, materials.size());
        assertEquals(materials.size(), materialIds.size());
        assertEquals(cases, second.cases());
        assertEquals(materials, second.materialItems());
        assertTrue(cases.stream().anyMatch(value -> "v2".equals(value.version())));
        materials.forEach(value -> {
            assertTrue(caseIds.contains(value.caseRef().id()), "missing case " + value.caseRef().id());
            assertEquals(first.findCase(value.caseRef().id(), value.caseRef().version()).orElseThrow(), value.caseRef());
            assertEquals(first.materialData(value.source().id()).orElseThrow(), value.data());
        });
        assertEquals(Set.of(4L), materials.stream().collect(Collectors.groupingBy(value -> value.caseRef().id(), Collectors.counting())).values().stream().collect(Collectors.toSet()));
        assertTrue(materials.stream().anyMatch(value -> !value.data().available()));
        assertTrue(materials.stream().anyMatch(value -> !value.data().missingItems().isEmpty()));
        assertTrue(materials.stream().anyMatch(value -> !value.data().conflicts().isEmpty()));
        assertFalse(materials.stream().allMatch(value -> value.data().partyStatements().isEmpty()));
    }

    private static void assertDateOrder(EvidenceData value) {
        LocalDate occurrence = date(value.occurrenceTime());
        LocalDate collection = date(value.collectionTime());
        LocalDate upload = date(value.uploadTime());
        if (occurrence != null && collection != null) assertFalse(collection.isBefore(occurrence), value.source().sourceId());
        if (collection != null && upload != null) assertFalse(upload.isBefore(collection), value.source().sourceId());
        if (occurrence != null && upload != null) assertFalse(upload.isBefore(occurrence), value.source().sourceId());
    }

    private static LocalDate date(String value) { return value == null ? null : LocalDate.parse(value); }
}
