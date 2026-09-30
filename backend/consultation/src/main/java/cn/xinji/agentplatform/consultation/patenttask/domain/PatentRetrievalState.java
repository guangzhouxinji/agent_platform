package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.*;

public record PatentRetrievalState(List<PatentRetrievalAction> attempts) {
    public PatentRetrievalState { attempts = List.copyOf(attempts); }

    public static PatentRetrievalState empty() { return new PatentRetrievalState(List.of()); }

    public PatentRetrievalState apply(PatentRetrievalAction action, ResultFocusAction focus) {
        if (attempts.stream().anyMatch(previous -> previous.executionId().equals(action.executionId()))) {
            throw new IllegalArgumentException("retrieval execution identity already exists");
        }
        List<PatentRetrievalAction> prior = attempts.stream()
                .filter(item -> item.branchId().equals(action.branchId())
                        && item.outcome() != PatentRetrievalAction.Outcome.SCOPE_CONFIRMED).toList();
        if (action.outcome() == PatentRetrievalAction.Outcome.SCOPE_CONFIRMED) {
            if (prior.isEmpty() || !prior.get(prior.size() - 1).actualScopeReference()
                    .equals(action.queryReference()) || action.actualScopeReference().equals(action.queryReference())) {
                throw new IllegalArgumentException("scope confirmation must refer to the current scope and a new scope");
            }
        } else if (!prior.isEmpty() && !prior.get(prior.size() - 1).actualScopeReference()
                .equals(action.actualScopeReference())
                && attempts.stream().noneMatch(item -> item.outcome() == PatentRetrievalAction.Outcome.SCOPE_CONFIRMED
                    && item.executionId().equals(action.scopeConfirmationReference())
                    && item.branchId().equals(action.branchId())
                    && item.queryReference().equals(prior.get(prior.size() - 1).actualScopeReference())
                    && item.actualScopeReference().equals(action.actualScopeReference()))) {
            throw new IllegalArgumentException("changed search scope requires a recorded user confirmation");
        }
        if (action.outcome() == PatentRetrievalAction.Outcome.STOPPED) {
            try {
                PatentRetrievalAction.StopReason.valueOf(action.detail());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("retrieval stop needs an allowed reason");
            }
        }
        if (action.outcome() != PatentRetrievalAction.Outcome.SCOPE_CONFIRMED
                && prior.stream().anyMatch(item -> item.actualScopeReference().equals(action.actualScopeReference())
                && item.methodReference().equals(action.methodReference())
                && item.queryReference().equals(action.queryReference()))) {
            throw new IllegalArgumentException("retrieval must not repeat the same scope, method and query");
        }
        if (action.outcome() == PatentRetrievalAction.Outcome.RESULTS) {
            if (focus == null || focus.kind() != ResultFocusAction.Kind.PRODUCE
                    || !action.resultSetId().equals(focus.resultSetId())
                    || action.resultSetVersion() != focus.resultSetVersion()
                    || !action.actualScopeReference().equals(focus.origin().actualScopeReference())
                    || !action.methodReference().equals(focus.origin().methodReference())
                    || !focus.origin().sourceReferences().equals(action.sourceReferences())
                    || !focus.origin().executionReferences().contains(action.executionId())) {
                throw new IllegalArgumentException("retrieval result and produced snapshot must agree");
            }
        } else if (focus != null) {
            throw new IllegalArgumentException("non-result retrieval cannot produce a snapshot");
        }
        List<PatentRetrievalAction> next = new ArrayList<>(attempts);
        next.add(action);
        return new PatentRetrievalState(next);
    }
}
