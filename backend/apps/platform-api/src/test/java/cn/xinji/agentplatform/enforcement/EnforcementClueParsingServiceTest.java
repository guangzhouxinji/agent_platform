package cn.xinji.agentplatform.enforcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static cn.xinji.agentplatform.enforcement.application.EnforcementClueParsingService.PARSER_VERSION;

import cn.xinji.agentplatform.enforcement.application.EnforcementClueParsingService;
import cn.xinji.agentplatform.enforcement.application.EnforcementClueParsingService.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EnforcementClueParsingServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void extractsOnlyExplicitStructuredFieldsAndKeepsEvidencePending() {
        Store store = mock(Store.class);
        ParseInput input = new ParseInput("lead-1", 2, "snapshot-1", "a".repeat(64),
                List.of(classification("PUBLICITY_ISSUE", "rule-2", 3),
                        classification("COUNTERFEIT_PATENT", "rule-1", 2)), Map.of(
                "content", "这里出现的自由文本主体不能被推断",
                "subjects", List.of(
                        Map.of("role", "投诉人", "name", "甲公司"),
                        Map.of("role", "销售方", "name", "乙公司", "creditCode", "91310000TEST000001")),
                "facts", List.of(Map.of("rawValue", "包装标注专利字样")),
                "eventTimes", List.of(
                        Map.of("rawValue", "2026年9月19日", "normalizedValue", "2026-09-19"),
                        Map.of("rawValue", "无法标准化", "normalizedValue", "昨天")),
                "places", List.of(Map.of("rawValue", "某市某区", "normalizedValue", "某市某区")),
                "evidenceRefs", List.of(Map.of("referenceId", "E-001"))));
        ParseTask running = task("running");
        when(store.prepare("org-a", "actor-a", "lead-1", "idem-1", "request-1", PARSER_VERSION, NOW))
                .thenReturn(new PreparedTask(running, input, false));
        when(store.complete(eq("task-1"), eq("org-a"), eq("actor-a"), eq("request-1"), any(), eq(NOW)))
                .thenReturn(task("partial_failed"));

        new EnforcementClueParsingService(store, CLOCK)
                .parse("actor-a", "org-a", "lead-1", "idem-1", "request-1");

        ArgumentCaptor<Extraction> capture = ArgumentCaptor.forClass(Extraction.class);
        verify(store).complete(eq("task-1"), eq("org-a"), eq("actor-a"), eq("request-1"), capture.capture(), eq(NOW));
        Extraction extraction = capture.getValue();
        assertThat(extraction.modelUsage()).isEqualTo("NO_MODEL_USED");
        assertThat(extraction.modelId()).isNull();
        assertThat(extraction.priorityLevel()).isEqualTo("TO_BE_EVALUATED");
        assertThat(extraction.notLegalProbability()).isTrue();
        assertThat(extraction.fields().stream().filter(field -> field.kind().equals("SUBJECT")))
                .extracting(FieldValue::role).containsExactly("投诉人", "销售方");
        assertThat(extraction.fields().stream().filter(field -> field.kind().equals("SUBJECT")))
                .extracting(FieldValue::creditCode).containsExactly(null, "91310000TEST000001");
        assertThat(extraction.fields()).anySatisfy(field -> {
            assertThat(field.kind()).isEqualTo("TIME");
            assertThat(field.rawValue()).isEqualTo("2026年9月19日");
            assertThat(field.normalizedValue()).isEqualTo("2026-09-19");
            assertThat(field.fieldPath()).isEqualTo("eventTimes[0]");
        });
        assertThat(extraction.fields()).anySatisfy(field -> {
            assertThat(field.kind()).isEqualTo("EVIDENCE_REFERENCE");
            assertThat(field.referenceStatus()).isEqualTo("PENDING_CONNECTION");
        });
        assertThat(extraction.fields()).filteredOn(field -> field.kind().equals("CLASSIFICATION"))
                .extracting(FieldValue::rawValue).containsExactly("COUNTERFEIT_PATENT", "PUBLICITY_ISSUE");
        assertThat(extraction.fields()).filteredOn(field -> field.kind().equals("CLASSIFICATION"))
                .extracting(FieldValue::sourceRef, FieldValue::sourceVersion, FieldValue::sourceDigest)
                .containsExactly(tuple("rule-1", "2.1", "c".repeat(64)), tuple("rule-2", "3.1", "c".repeat(64)));
        assertThat(extraction.fields()).filteredOn(field -> field.sourceKind().equals("SOURCE_SNAPSHOT"))
                .allSatisfy(field -> {
                    assertThat(field.sourceRef()).isEqualTo("snapshot-1");
                    assertThat(field.sourceDigest()).isEqualTo("a".repeat(64));
                });
        assertThat(extraction.issues()).contains(new ParseIssue("NORMALIZATION_FAILED",
                "eventTimes[1].normalizedValue", "TIME_NORMALIZED_VALUE_INVALID"));
        assertThat(extraction.fields()).noneSatisfy(field -> assertThat(field.rawValue()).contains("自由文本主体"));
    }

    @Test void reportsMissingTypeAndUnprocessedValuesWithoutInventingData() {
        Store store = mock(Store.class);
        ParseInput input = new ParseInput("lead-1", 1, "snapshot-1", "b".repeat(64), List.of(), Map.of(
                "subjects", "not-an-array", "facts", List.of(Map.of("other", "value")),
                "places", List.of(), "evidenceRefs", List.of()));
        when(store.prepare(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new PreparedTask(task("running"), input, false));
        when(store.complete(anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(task("partial_failed"));

        new EnforcementClueParsingService(store, CLOCK).parse("actor", "org", "lead-1", "idem", "request");

        ArgumentCaptor<Extraction> capture = ArgumentCaptor.forClass(Extraction.class);
        verify(store).complete(anyString(), anyString(), anyString(), anyString(), capture.capture(), any());
        assertThat(capture.getValue().issues()).extracting(ParseIssue::kind)
                .contains("TYPE_ERROR", "UNPROCESSED", "MISSING");
    }

    @Test void idempotentReplayDoesNotRunExtractionOrCreateAnotherVersion() {
        Store store = mock(Store.class);
        ParseTask completed = task("succeeded");
        when(store.prepare(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new PreparedTask(completed, null, true));
        assertThat(new EnforcementClueParsingService(store, CLOCK)
                .parse("actor", "org", "lead-1", "idem", "request")).isSameAs(completed);
        verify(store, never()).complete(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test void revisionRequiresRoleReasonAndKnownFieldKinds() {
        EnforcementClueParsingService service = new EnforcementClueParsingService(mock(Store.class), CLOCK);
        assertThatThrownBy(() -> service.revise("actor", "org", "lead", new RevisionCommand(1, " ", List.of()), "r"))
                .isInstanceOf(ParsingException.class).extracting("code").isEqualTo("INVALID_INPUT");
        assertThatThrownBy(() -> service.revise("actor", "org", "lead",
                new RevisionCommand(1, "修正", List.of(new FieldInput("SUBJECT", null, "甲公司", null, null))), "r"))
                .isInstanceOf(ParsingException.class).extracting("code").isEqualTo("ROLE_REQUIRED");
        assertThatThrownBy(() -> service.revise("actor", "org", "lead",
                new RevisionCommand(1, "修正", List.of(new FieldInput("LEGAL_FINDING", null, "侵权", null, null))), "r"))
                .isInstanceOf(ParsingException.class).extracting("code").isEqualTo("INVALID_FIELD");
    }

    @Test void reviewRequiresMatchingVersionAndReasonForReturn() {
        EnforcementClueParsingService service = new EnforcementClueParsingService(mock(Store.class), CLOCK);
        assertThatThrownBy(() -> service.review("actor", "org", "lead", 2,
                new ReviewCommand("CONFIRM", 1, null), "r"))
                .isInstanceOf(ParsingException.class).extracting("code").isEqualTo("INVALID_INPUT");
        assertThatThrownBy(() -> service.review("actor", "org", "lead", 2,
                new ReviewCommand("RETURN", 2, ""), "r"))
                .isInstanceOf(ParsingException.class).extracting("code").isEqualTo("REASON_REQUIRED");
    }

    private static ParseTask task(String status) {
        return new ParseTask("task-1", "lead-1", status, 1, 8, 1, null, PARSER_VERSION,
                "request-1", NOW, NOW);
    }

    private static ClassificationInput classification(String type, String ruleId, int ruleVersion) {
        return new ClassificationInput(type, ruleId, ruleVersion, 1, "c".repeat(64));
    }
}
