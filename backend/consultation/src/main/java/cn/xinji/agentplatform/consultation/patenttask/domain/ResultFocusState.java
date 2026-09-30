package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.*;

public record ResultFocusState(String activeBranchId, Map<String, PatentResultSet> resultSets,
                               Map<String, List<FocusVersion>> focusHistory,
                               List<ReferenceBinding> bindings, Map<String, List<String>> ambiguities,
                               List<Activity> activities) {
    public ResultFocusState {
        resultSets = Map.copyOf(resultSets);
        Map<String, List<FocusVersion>> history = new HashMap<>();
        focusHistory.forEach((branch, versions) -> history.put(branch, List.copyOf(versions)));
        focusHistory = Map.copyOf(history);
        bindings = List.copyOf(bindings);
        Map<String, List<String>> unresolved = new HashMap<>();
        ambiguities.forEach((reference, alternatives) -> unresolved.put(reference, List.copyOf(alternatives)));
        ambiguities = Map.copyOf(unresolved);
        activities = List.copyOf(activities);
    }

    public static ResultFocusState empty() {
        return new ResultFocusState(null, Map.of(), Map.of(), List.of(), Map.of(), List.of());
    }

    public FocusVersion currentFocus(String branch) {
        List<FocusVersion> history = focusHistory.getOrDefault(branch, List.of());
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    public ReferenceBinding usableBinding(String referenceId) {
        ReferenceBinding binding = bindings.stream().filter(item -> item.referenceId().equals(referenceId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown reference binding"));
        if (!binding.branchId().equals(activeBranchId)) {
            throw new IllegalStateException("bound result belongs to another task branch");
        }
        PatentResultSet set = resultSets.get(binding.resultSetId() + "@" + binding.resultSetVersion());
        if (set == null || !set.available()) throw new IllegalStateException("bound result is unavailable");
        return binding;
    }

    public ResultFocusState apply(ResultFocusAction action, long taskVersion) {
        Map<String, PatentResultSet> sets = new HashMap<>(resultSets);
        Map<String, List<FocusVersion>> history = new HashMap<>(focusHistory);
        List<ReferenceBinding> nextBindings = new ArrayList<>(bindings);
        Map<String, List<String>> nextAmbiguities = new HashMap<>(ambiguities);
        List<Activity> nextActivities = new ArrayList<>(activities);
        String active = activeBranchId;
        PatentResultSet set = action.kind() == ResultFocusAction.Kind.PRODUCE
                || action.kind() == ResultFocusAction.Kind.SWITCH_BRANCH
                || action.kind() == ResultFocusAction.Kind.BIND_CURRENT
                || action.kind() == ResultFocusAction.Kind.BIND_LATEST_DELIVERED
                || action.kind() == ResultFocusAction.Kind.AMBIGUOUS ? null : requireSet(action, sets);
        switch (action.kind()) {
            case PRODUCE -> {
                PatentResultSet created = new PatentResultSet(action.resultSetId(), action.resultSetVersion(),
                        action.parentVersion(), action.branchId(), action.reason(), action.origin(),
                        action.candidates(), false, 0, true);
                if (sets.containsKey(created.key())) throw new IllegalArgumentException("result snapshot already exists");
                if (created.parentVersion() != null) {
                    PatentResultSet parent = sets.get(created.resultSetId() + "@" + created.parentVersion());
                    if (parent == null || !parent.branchId().equals(created.branchId())) {
                        throw new IllegalArgumentException("parent result snapshot is missing in this branch");
                    }
                }
                for (PatentResultSet earlier : sets.values()) {
                    if (!earlier.resultSetId().equals(created.resultSetId())) continue;
                    for (PatentResultSet.Candidate candidate : created.candidates()) {
                        if (earlier.candidates().stream().anyMatch(previous ->
                                previous.candidateId().equals(candidate.candidateId())
                                        && !previous.documentVersionReference().equals(candidate.documentVersionReference()))) {
                            throw new IllegalArgumentException("candidate identity cannot change document version");
                        }
                    }
                }
                history.putIfAbsent(action.branchId(), List.of());
                if (active == null) active = action.branchId();
                sets.put(created.key(), created);
            }
            case DELIVER -> {
                if (set.delivered() || !set.available()) throw new IllegalArgumentException("result cannot be delivered");
                long order = sets.values().stream().mapToLong(PatentResultSet::deliveryOrder).max().orElse(0) + 1;
                sets.put(set.key(), set.deliveredAt(order));
            }
            case DISPLAY, VIEW -> requireUsable(set);
            case SELECT -> {
                requireActive(action.branchId(), active);
                requireUsable(set);
                addFocus(history, action.branchId(), set, taskVersion);
            }
            case BIND_EXPLICIT -> {
                requireActive(action.branchId(), active);
                requireUsable(set);
                resolveAmbiguity(nextAmbiguities, action, set);
                addBinding(nextBindings, action, set);
                addFocus(history, action.branchId(), set, taskVersion);
            }
            case BIND_CURRENT -> {
                requireActive(action.branchId(), active);
                FocusVersion focus = currentFocus(action.branchId());
                if (focus == null || !focus.available()) throw new IllegalArgumentException("explicit result focus is required");
                PatentResultSet focused = sets.get(focus.resultSetId() + "@" + focus.resultSetVersion());
                requireUsable(focused);
                addBinding(nextBindings, action, focused);
                set = focused;
            }
            case BIND_LATEST_DELIVERED -> {
                requireActive(action.branchId(), active);
                PatentResultSet latest = sets.values().stream()
                        .filter(item -> item.branchId().equals(activeBranchId) && item.delivered())
                        .max(Comparator.comparingLong(PatentResultSet::deliveryOrder))
                        .orElseThrow(() -> new IllegalArgumentException("no delivered result in this branch"));
                requireUsable(latest);
                resolveAmbiguity(nextAmbiguities, action, latest);
                addBinding(nextBindings, action, latest);
                addFocus(history, action.branchId(), latest, taskVersion);
                set = latest;
            }
            case AVAILABILITY -> {
                if (set.available() != action.available()) {
                    sets.put(set.key(), set.withAvailability(action.available()));
                    FocusVersion focus = currentFocus(action.branchId());
                    if (focus != null && focus.resultSetId().equals(set.resultSetId())
                            && focus.resultSetVersion() == set.version()) {
                        addFocus(history, action.branchId(), set.withAvailability(action.available()), taskVersion);
                    }
                }
            }
            case SWITCH_BRANCH -> {
                if (!history.containsKey(action.branchId())) throw new IllegalArgumentException("unknown task branch");
                active = action.branchId();
            }
            case AMBIGUOUS -> {
                requireActive(action.branchId(), active);
                if (action.referenceId() == null || action.referenceId().isBlank()
                        || action.ambiguityCandidates().size() < 2
                        || action.ambiguityCandidates().stream().distinct().count() != action.ambiguityCandidates().size()
                        || nextAmbiguities.containsKey(action.referenceId())) {
                    throw new IllegalArgumentException("ambiguous reference requires distinct alternatives");
                }
                for (String key : action.ambiguityCandidates()) {
                    PatentResultSet alternative = sets.get(key);
                    if (alternative == null || !alternative.branchId().equals(active)
                            || !alternative.delivered() || !alternative.available()) {
                        throw new IllegalArgumentException("ambiguous alternative is unavailable");
                    }
                }
                nextAmbiguities.put(action.referenceId(), action.ambiguityCandidates());
            }
        }
        nextActivities.add(new Activity(action.kind().name(), action.branchId(),
                set == null ? action.resultSetId() : set.resultSetId(),
                set == null ? action.resultSetVersion() : set.version(), action.referenceId(), taskVersion));
        return new ResultFocusState(active, sets, history, nextBindings, nextAmbiguities, nextActivities);
    }

    private PatentResultSet requireSet(ResultFocusAction action, Map<String, PatentResultSet> sets) {
        PatentResultSet set = sets.get(action.resultSetId() + "@" + action.resultSetVersion());
        if (set == null || !set.branchId().equals(action.branchId())) {
            throw new IllegalArgumentException("result snapshot is not in the requested branch");
        }
        return set;
    }

    private static void requireUsable(PatentResultSet set) {
        if (set == null || !set.delivered() || !set.available()) {
            throw new IllegalArgumentException("result snapshot is not available for use");
        }
    }

    private static void requireActive(String branch, String active) {
        if (!branch.equals(active)) throw new IllegalArgumentException("task branch is not active");
    }

    private static void addFocus(Map<String, List<FocusVersion>> history, String branch,
                                 PatentResultSet set, long taskVersion) {
        List<FocusVersion> versions = new ArrayList<>(history.getOrDefault(branch, List.of()));
        versions.add(new FocusVersion(versions.size() + 1, set.resultSetId(), set.version(),
                set.available(), taskVersion));
        history.put(branch, versions);
    }

    private static void addBinding(List<ReferenceBinding> bindings, ResultFocusAction action, PatentResultSet set) {
        if (action.referenceId() == null || action.referenceId().isBlank() || action.ordinal() < 1
                || action.ordinal() > set.candidates().size()) {
            throw new IllegalArgumentException("reference and ordinal must identify a candidate");
        }
        if (bindings.stream().anyMatch(item -> item.referenceId().equals(action.referenceId()))) {
            throw new IllegalArgumentException("reference identity already exists");
        }
        bindings.add(new ReferenceBinding(action.referenceId(), action.branchId(), set.resultSetId(),
                set.version(), action.ordinal(), set.candidates().get(action.ordinal() - 1).candidateId()));
    }

    private static void resolveAmbiguity(Map<String, List<String>> ambiguities,
                                         ResultFocusAction action, PatentResultSet set) {
        List<String> alternatives = ambiguities.get(action.referenceId());
        if (alternatives != null && !alternatives.contains(set.key())) {
            throw new IllegalArgumentException("clarification does not select an offered result");
        }
        ambiguities.remove(action.referenceId());
    }

    public record FocusVersion(long version, String resultSetId, long resultSetVersion,
                               boolean available, long taskVersion) {}
    public record ReferenceBinding(String referenceId, String branchId, String resultSetId,
                                   long resultSetVersion, int ordinal, String candidateId) {}
    public record Activity(String type, String branchId, String resultSetId,
                           long resultSetVersion, String referenceId, long taskVersion) {}
}
