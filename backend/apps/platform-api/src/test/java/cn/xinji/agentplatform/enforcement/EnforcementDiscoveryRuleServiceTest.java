package cn.xinji.agentplatform.enforcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService;
import cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EnforcementDiscoveryRuleServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final String ORG = "org-a";

    @Test void createsOnlyValidatedDevelopmentRulesAgainstOrganizationSources() {
        Store store = mock(Store.class);
        when(store.source(ORG, "source-1")).thenReturn(Optional.of(new SourceState("source-1", true, "HEALTHY")));
        when(store.create(any(), eq("actor"), eq(NOW))).thenAnswer(call -> call.getArgument(0));

        RuleVersion created = service(store).create("actor", ORG, command(null, leaf("TEXT_CONTAINS", "content", "专利")));

        assertThat(created.ruleVersion()).isEqualTo(1);
        assertThat(created.revision()).isEqualTo(1);
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.engineType()).isEqualTo("DETERMINISTIC_V1");
        assertThat(created.usageScope()).isEqualTo("DEVELOPMENT_ONLY");
        verify(store).source(ORG, "source-1");
    }

    @Test void rejectsUnsupportedExpressionsBeforeReadingSources() {
        Store store = mock(Store.class);
        Condition expression = new Condition("root", "REGEX", "content", ".*", List.of());

        assertThatThrownBy(() -> service(store).create("actor", ORG, command(null, expression)))
                .isInstanceOf(RuleException.class).hasMessage("OPERATOR_NOT_ALLOWED");
        verifyNoInteractions(store);
    }

    @Test void rejectsDuplicateNodeIdsAndExcessiveDepth() {
        Store store = mock(Store.class);
        Condition duplicate = new Condition("root", "AND", null, null, List.of(
                new Condition("same", "EXISTS", "content", null, List.of()),
                new Condition("same", "EXISTS", "subject.name", null, List.of())));
        assertThatThrownBy(() -> service(store).create("actor", ORG, command(null, duplicate)))
                .isInstanceOf(RuleException.class).hasMessage("CONDITION_NODE_ID_DUPLICATE");

        Condition deep = leaf("EXISTS", "content", null);
        for (int index = 0; index < 8; index++) {
            deep = new Condition("group-" + index, "AND", null, null,
                    List.of(deep, leaf("EXISTS", "field" + index, null)));
        }
        Condition finalDeep = deep;
        assertThatThrownBy(() -> service(store).create("actor", ORG, command(null, finalDeep)))
                .isInstanceOf(RuleException.class).hasMessage("CONDITION_DEPTH_EXCEEDED");
        verifyNoInteractions(store);
    }

    @Test void producesDeterministicConditionResultsAndExplicitMissingReasons() {
        Store store = healthyStore();
        RuleVersion rule = rule(group("AND", leaf("TEXT_CONTAINS", "content", "专利"),
                leaf("EXISTS", "subject.name", null)));
        when(store.find(ORG, "rule-1", 1)).thenReturn(Optional.of(rule));
        when(store.saveRun(any(), anyMap(), eq("actor"), eq(NOW))).thenAnswer(call -> call.getArgument(0));
        DryRunCommand sample = new DryRunCommand("sample-1", "v1", "SYNTHETIC_LOCAL", Map.of("content", "专利商品"));

        DryRun first = service(store).dryRun("actor", ORG, "rule-1", 1, sample, "request-1");
        DryRun second = service(store).dryRun("actor", ORG, "rule-1", 1, sample, "request-2");

        assertThat(first.resultStatus()).isEqualTo("SUCCEEDED");
        assertThat(first.matched()).isFalse();
        assertThat(first.inputDigest()).isEqualTo(second.inputDigest());
        assertThat(first.conditionResults()).extracting(ConditionResult::reasonCode)
                .containsExactly("MATCHED", "FIELD_MISSING", "NOT_MATCHED");
        assertThat(second.conditionResults()).usingRecursiveComparison().isEqualTo(first.conditionResults());
    }

    @Test void reportsTypeErrorsWithoutTreatingTheRunAsSuccessful() {
        Store store = healthyStore();
        when(store.find(ORG, "rule-1", 1)).thenReturn(Optional.of(rule(leaf("NUMBER_GTE", "score", 80))));
        when(store.saveRun(any(), anyMap(), anyString(), any())).thenAnswer(call -> call.getArgument(0));

        DryRun run = service(store).dryRun("actor", ORG, "rule-1", 1,
                new DryRunCommand("sample-1", "v1", "SYNTHETIC_LOCAL", Map.of("score", "high")), "request-1");

        assertThat(run.resultStatus()).isEqualTo("TYPE_ERROR");
        assertThat(run.matched()).isFalse();
        assertThat(run.unhandledReason()).isEqualTo("FIELD_TYPE_MISMATCH");
    }

    @Test void requiresAHealthySourceAndCurrentSuccessfulRunBeforePublish() {
        Store store = healthyStore();
        RuleVersion rule = rule(leaf("EXISTS", "content", null));
        when(store.find(ORG, "rule-1", 1)).thenReturn(Optional.of(rule));
        when(store.hasSuccessfulRun(ORG, "rule-1", 1, 1, rule.contentDigest())).thenReturn(false, true);
        when(store.transition(ORG, "rule-1", 1, 1, "DRAFT", "PUBLISHED", "reviewer", NOW))
                .thenReturn(published(rule));

        assertThatThrownBy(() -> service(store).publish("reviewer", ORG, "rule-1", 1, 1))
                .isInstanceOf(RuleException.class).hasMessage("SUCCESSFUL_DRY_RUN_REQUIRED");
        assertThat(service(store).publish("reviewer", ORG, "rule-1", 1, 1).status()).isEqualTo("PUBLISHED");
    }

    @Test void distinguishesDisabledAndUnhealthySources() {
        RuleVersion rule = rule(leaf("EXISTS", "content", null));
        Store disabled = mock(Store.class);
        when(disabled.find(ORG, "rule-1", 1)).thenReturn(Optional.of(rule));
        when(disabled.source(ORG, "source-1")).thenReturn(Optional.of(new SourceState("source-1", false, "HEALTHY")));
        assertThatThrownBy(() -> service(disabled).publish("reviewer", ORG, "rule-1", 1, 1))
                .isInstanceOf(RuleException.class).hasMessage("SOURCE_DISABLED");

        Store unhealthy = mock(Store.class);
        when(unhealthy.find(ORG, "rule-1", 1)).thenReturn(Optional.of(rule));
        when(unhealthy.source(ORG, "source-1")).thenReturn(Optional.of(new SourceState("source-1", true, "UNHEALTHY")));
        assertThatThrownBy(() -> service(unhealthy).publish("reviewer", ORG, "rule-1", 1, 1))
                .isInstanceOf(RuleException.class).hasMessage("SOURCE_UNAVAILABLE");
    }

    @Test void copiesContentIntoANewDraftWithoutMutatingTheSourceVersion() {
        Store store = mock(Store.class);
        RuleVersion source = published(rule(leaf("EXISTS", "content", null)));
        when(store.find(ORG, "rule-1", 1)).thenReturn(Optional.of(source));
        when(store.nextVersion(ORG, "rule-1")).thenReturn(2);
        when(store.createVersion(any(), eq("actor"), eq(NOW), eq(1))).thenAnswer(call -> call.getArgument(0));

        RuleVersion copy = service(store).copy("actor", ORG, "rule-1", 1);

        assertThat(copy.ruleVersion()).isEqualTo(2);
        assertThat(copy.status()).isEqualTo("DRAFT");
        assertThat(copy.contentDigest()).isEqualTo(source.contentDigest());
        verify(store, never()).update(any(), anyInt(), anyString(), any());
    }

    @Test void keepsPublishedVersionsImmutableAndUsesTrustedOrganizationOnReads() {
        Store store = mock(Store.class);
        RuleVersion published = published(rule(leaf("EXISTS", "content", null)));
        when(store.find(ORG, "rule-1", 1)).thenReturn(Optional.of(published));

        assertThatThrownBy(() -> service(store).update("actor", ORG, "rule-1", 1,
                command(2, leaf("EXISTS", "content", null))))
                .isInstanceOf(RuleException.class).hasMessage("RULE_VERSION_IMMUTABLE");
        assertThatThrownBy(() -> service(store).read("actor", "org-b", "rule-1", 1))
                .isInstanceOf(RuleException.class).hasMessage("RULE_NOT_FOUND");
        verify(store).find("org-b", "rule-1", 1);
    }

    private static Store healthyStore() {
        Store store = mock(Store.class);
        when(store.source(ORG, "source-1")).thenReturn(Optional.of(new SourceState("source-1", true, "HEALTHY")));
        return store;
    }
    private static EnforcementDiscoveryRuleService service(Store store) {
        return new EnforcementDiscoveryRuleService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static SaveCommand command(Integer revision, Condition condition) {
        return new SaveCommand("rule-1", "开发测试规则", "COUNTERFEIT_PATENT", "不含专业结论",
                List.of("source-1"), condition, revision);
    }
    private static RuleVersion rule(Condition condition) {
        return new RuleVersion("rule-1", ORG, 1, 1, "DRAFT", "开发测试规则", "COUNTERFEIT_PATENT",
                "不含专业结论", "DETERMINISTIC_V1", "DEVELOPMENT_ONLY", List.of("source-1"), condition,
                "a".repeat(64), "actor", NOW, "actor", NOW, null, null);
    }
    private static RuleVersion published(RuleVersion rule) {
        return new RuleVersion(rule.ruleId(), rule.organizationRef(), rule.ruleVersion(), 2, "PUBLISHED",
                rule.ruleName(), rule.category(), rule.description(), rule.engineType(), rule.usageScope(),
                rule.sourceIds(), rule.condition(), rule.contentDigest(), rule.createdBy(), rule.createdAt(),
                "reviewer", NOW, "reviewer", NOW);
    }
    private static Condition leaf(String operator, String path, Object value) {
        return new Condition("node-" + operator + "-" + path, operator, path, value, List.of());
    }
    private static Condition group(String operator, Condition... children) {
        return new Condition("root", operator, null, null, List.of(children));
    }
}
