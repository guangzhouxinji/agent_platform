package cn.xinji.agentplatform.enforcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.xinji.agentplatform.enforcement.application.EnforcementClueDiscoveryService;
import cn.xinji.agentplatform.enforcement.application.EnforcementClueDiscoveryService.*;
import cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService.Condition;
import cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService.RuleVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EnforcementClueDiscoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final String ORG = "org-a";

    @Test void mergesMultipleRuleMatchesIntoOnePendingDevelopmentLead() {
        Store store = mock(Store.class);
        DiscoveryCommand command = command();
        when(store.begin(eq(ORG), eq("actor"), eq("idem-1"), anyString(), eq("request-1"), eq(command), eq(NOW)))
                .thenReturn(task("queued"));
        SourceSnapshot snapshot = snapshot("v1", "商品宣传含专利标识");
        when(store.prepare(ORG, "task-1", command)).thenReturn(new PreparedInput(snapshotList(snapshot),
                List.of(rule("rule-1", "COUNTERFEIT_PATENT", "专利"), rule("rule-2", "PUBLICITY_ISSUE", "宣传")), List.of()));
        Lead lead = lead("lead-1", "PENDING_VERIFICATION", 1);
        when(store.upsertLead(eq(ORG), eq("actor"), eq("request-1"), eq(snapshot), anyString(), anyList(), eq(NOW)))
                .thenReturn(lead);
        when(store.complete(eq("task-1"), eq(ORG), anyList(), eq(List.of("lead-1")), eq(NOW))).thenReturn(task("succeeded"));

        DiscoveryTask result = service(store).discover("actor", ORG, "idem-1", "request-1", command);

        assertThat(result.status()).isEqualTo("succeeded");
        ArgumentCaptor<List<RuleMatch>> matches = ArgumentCaptor.forClass(List.class);
        verify(store).upsertLead(eq(ORG), eq("actor"), eq("request-1"), eq(snapshot),
                eq("商品宣传含专利标识"), matches.capture(), eq(NOW));
        assertThat(matches.getValue()).extracting(RuleMatch::suspectedType)
                .containsExactly("COUNTERFEIT_PATENT", "PUBLICITY_ISSUE");
        ArgumentCaptor<List<TaskItem>> items = ArgumentCaptor.forClass(List.class);
        verify(store).complete(eq("task-1"), eq(ORG), items.capture(), eq(List.of("lead-1")), eq(NOW));
        assertThat(items.getValue()).extracting(TaskItem::status).containsExactly("MATCHED", "MATCHED");
    }

    @Test void recordsNotApplicableMissingFieldTypeErrorAndUpstreamFailureWithoutCreatingLeads() {
        Store store = mock(Store.class);
        DiscoveryCommand command = command();
        when(store.begin(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(task("queued"));
        SourceSnapshot unrelated = new SourceSnapshot("snap-0", "other", "record-0", "v1", "d", "ACTIVE", Map.of("content", "text"));
        SourceSnapshot missing = snapshot("v1", "ordinary text");
        SourceSnapshot wrongType = new SourceSnapshot("snap-2", "source-1", "record-2", "v1", "d", "ACTIVE", Map.of("score", "high"));
        TaskItem upstream = new TaskItem(1, null, null, "upstream-record", null, null, null,
                "FAILED", "UPSTREAM_TIMEOUT", true, null);
        when(store.prepare(eq(ORG), eq("task-1"), eq(command))).thenReturn(new PreparedInput(
                List.of(unrelated, missing, wrongType),
                List.of(rule("rule-1", "COUNTERFEIT_PATENT", "needle"), numericRule()), List.of(upstream)));
        when(store.complete(eq("task-1"), eq(ORG), anyList(), eq(List.of()), eq(NOW))).thenReturn(task("partial_failed"));

        service(store).discover("actor", ORG, "idem-1", "request-1", command);

        ArgumentCaptor<List<TaskItem>> items = ArgumentCaptor.forClass(List.class);
        verify(store).complete(eq("task-1"), eq(ORG), items.capture(), eq(List.of()), eq(NOW));
        assertThat(items.getValue()).extracting(TaskItem::reasonCode)
                .contains("UPSTREAM_TIMEOUT", "RULE_NOT_APPLICABLE", "CONDITIONS_NOT_MATCHED", "FIELD_TYPE_MISMATCH");
        verify(store, never()).upsertLead(anyString(), anyString(), anyString(), any(), any(), anyList(), any());
    }

    @Test void propagatesWithdrawalWithoutChangingLeadProcessingState() {
        Store store = mock(Store.class);
        DiscoveryCommand command = command();
        when(store.begin(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(task("queued"));
        SourceSnapshot withdrawn = new SourceSnapshot("snap-w", "source-1", "record-1", "v2", "d", "WITHDRAWN", Map.of());
        when(store.prepare(ORG, "task-1", command)).thenReturn(new PreparedInput(List.of(withdrawn), List.of(rule("rule-1", "COUNTERFEIT_PATENT", "x")), List.of()));
        when(store.complete(eq("task-1"), eq(ORG), anyList(), eq(List.of()), eq(NOW))).thenReturn(task("succeeded"));

        service(store).discover("actor", ORG, "idem-1", "request-1", command);

        verify(store).propagateWithdrawal(ORG, withdrawn, "actor", "request-1", NOW);
        verify(store, never()).transition(anyString(), anyString(), anyInt(), anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test void enforcesHumanStateMachineReasonsAndOptimisticVersion() {
        Store store = mock(Store.class);
        when(store.lead(ORG, "lead-1")).thenReturn(Optional.of(new LeadDetails(
                lead("lead-1", "PENDING_VERIFICATION", 3), List.of(), List.of(), List.of(), "LOCAL_QUERY_ONLY")));
        when(store.transition(ORG, "lead-1", 3, "PENDING_VERIFICATION", "VERIFYING", null, "actor", "request-1", NOW))
                .thenReturn(lead("lead-1", "VERIFYING", 4));

        Lead started = service(store).transition("actor", ORG, "lead-1",
                new StatusCommand("START_VERIFICATION", 3, null), "request-1");
        assertThat(started.processingStatus()).isEqualTo("VERIFYING");

        assertThatThrownBy(() -> service(store).transition("actor", ORG, "lead-1",
                new StatusCommand("EXCLUDE", 3, " "), "request-2"))
                .isInstanceOf(DiscoveryException.class).hasMessage("REASON_REQUIRED");
        assertThatThrownBy(() -> service(store).transition("actor", ORG, "lead-1",
                new StatusCommand("TRANSFER", 3, null), "request-3"))
                .isInstanceOf(DiscoveryException.class).hasMessage("INVALID_STATE_TRANSITION");
    }

    @Test void usesOrganizationForAllLeadAndDuplicateReads() {
        Store store = mock(Store.class);
        when(store.lead("org-b", "lead-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(store).lead("actor", "org-b", "lead-1"))
                .isInstanceOf(DiscoveryException.class).hasMessage("LEAD_NOT_FOUND");
        verify(store).lead("org-b", "lead-1");
    }

    private static EnforcementClueDiscoveryService service(Store store) {
        return new EnforcementClueDiscoveryService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static DiscoveryCommand command() {
        return new DiscoveryCommand(List.of("sync-1"), List.of(new RuleRef("rule-1", 1), new RuleRef("rule-2", 1)));
    }
    private static DiscoveryTask task(String status) {
        return new DiscoveryTask("task-1", ORG, "actor", status, 0, 0, 0, 0,
                "digest", "request-1", NOW, NOW, "queued".equals(status) ? null : NOW, List.of(), List.of());
    }
    private static SourceSnapshot snapshot(String version, String content) {
        return new SourceSnapshot("snap-1", "source-1", "record-1", version, "digest", "ACTIVE", Map.of("content", content));
    }
    private static List<SourceSnapshot> snapshotList(SourceSnapshot snapshot) { return List.of(snapshot); }
    private static RuleVersion rule(String id, String category, String needle) {
        return new RuleVersion(id, ORG, 1, 2, "PUBLISHED", id, category, null,
                "DETERMINISTIC_V1", "DEVELOPMENT_ONLY", List.of("source-1"),
                new Condition(id + "-node", "TEXT_CONTAINS", "content", needle, List.of()),
                id + "-digest", "author", NOW, "reviewer", NOW, "reviewer", NOW);
    }
    private static RuleVersion numericRule() {
        return new RuleVersion("rule-2", ORG, 1, 2, "PUBLISHED", "numeric", "PUBLICITY_ISSUE", null,
                "DETERMINISTIC_V1", "DEVELOPMENT_ONLY", List.of("source-1"),
                new Condition("number-node", "NUMBER_GTE", "score", 80, List.of()),
                "numeric-digest", "author", NOW, "reviewer", NOW, "reviewer", NOW);
    }
    private static Lead lead(String id, String status, int version) {
        return new Lead(id, ORG, "source-1", "record-1", "v1", "snap-1", "digest", "AVAILABLE",
                "excerpt", List.of("COUNTERFEIT_PATENT"), status, false, "DEVELOPMENT_ONLY",
                "NOT_AN_INFRINGEMENT_FINDING", NOW, version, "actor", NOW, "request-1");
    }
}
