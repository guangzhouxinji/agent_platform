package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.RecoveryPoint;
import cn.xinji.agentplatform.consultation.patenttask.domain.ResultFocusAction;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentCorrectionAction;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentEvidenceAction;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentRetrievalAction;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentComparisonAction;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentHumanAction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class TaskCommandFingerprint {
    private TaskCommandFingerprint() {}

    static String forChange(TaskCommand command, TaskChange change) {
        StringBuilder value = metadata(command);
        appendValue(value, "change");
        change.subTaskChanges().stream().sorted(Comparator.comparing(SubTaskChange::subTaskId))
                .forEach(item -> append(value, item));
        appendMap(value, "objectUpdates", change.objectVersionUpdates());
        appendMap(value, "dependencyUpdates", change.dependencyVersionUpdates());
        appendFocus(value, change.resultFocusAction());
        appendCorrection(value, change.correctionAction());
        appendEvidence(value, change.evidenceAction());
        appendRetrieval(value, change.retrievalAction());
        appendComparison(value, change.comparisonAction());
        appendHuman(value, change.humanAction());
        return sha256(value.toString());
    }

    static String forSimple(TaskCommand command, String payloadReference) {
        StringBuilder value = metadata(command);
        appendValue(value, "payload");
        appendValue(value, payloadReference);
        return sha256(value.toString());
    }

    private static StringBuilder metadata(TaskCommand command) {
        StringBuilder value = new StringBuilder();
        appendValue(value, command.commandIdentity());
        appendValue(value, command.idempotencyKey());
        appendValue(value, command.type().name());
        appendValue(value, command.taskId());
        appendValue(value, Long.toString(command.expectedTaskVersion()));
        appendMap(value, "referencedObjects", command.referencedObjectVersions());
        appendMap(value, "dependencies", command.dependencyVersions());
        return value;
    }

    private static void append(StringBuilder value, SubTaskChange item) {
        appendValue(value, "subtask");
        appendValue(value, item.subTaskId());
        appendValue(value, Long.toString(item.expectedSubTaskVersion()));
        appendValue(value, item.operation().name());
        appendValue(value, Boolean.toString(item.primary()));
        appendValue(value, item.reasonCode() == null ? null : item.reasonCode().name());
        appendValue(value, item.responsibilitySource().name());
        appendValue(value, item.responsibilityReference());
        appendList(value, "completionConditions", item.completionConditionReferences());
        appendRecovery(value, item.recoveryPoint());
    }

    private static void appendRecovery(StringBuilder value, RecoveryPoint point) {
        appendValue(value, point == null ? "no-recovery" : "recovery");
        if (point == null) return;
        appendValue(value, Long.toString(point.failedFromTaskVersion()));
        appendValue(value, point.subTaskId());
        appendValue(value, point.journeyStep());
        appendValue(value, point.failedExecutionReference());
        appendValue(value, point.retryCommandReference());
        appendMap(value, "recoveryContext", point.contextVersionReferences());
        appendMap(value, "recoveryDependencies", point.dependencyVersions());
        appendList(value, "committedArtifacts", point.committedArtifactReferences());
        appendList(value, "incompleteActions", point.incompleteActionReferences());
        appendList(value, "sourcesToValidate", point.sourceReferencesRequiringValidation());
    }

    private static void appendFocus(StringBuilder value, ResultFocusAction action) {
        appendValue(value, action == null ? "no-focus-action" : "focus-action");
        if (action == null) return;
        appendValue(value, action.kind().name());
        appendValue(value, action.branchId());
        appendValue(value, action.resultSetId());
        appendValue(value, Long.toString(action.resultSetVersion()));
        appendValue(value, action.parentVersion() == null ? null : action.parentVersion().toString());
        appendValue(value, action.reason());
        if (action.origin() == null) {
            appendValue(value, "no-origin");
        } else {
            appendValue(value, "origin");
            appendValue(value, action.origin().actualScopeReference());
            appendValue(value, action.origin().methodReference());
            appendList(value, "sources", action.origin().sourceReferences());
            appendList(value, "executions", action.origin().executionReferences());
            appendList(value, "limitations", action.origin().limitations());
        }
        appendValue(value, Integer.toString(action.ordinal()));
        appendValue(value, action.referenceId());
        appendValue(value, Boolean.toString(action.available()));
        appendValue(value, Integer.toString(action.candidates().size()));
        action.candidates().forEach(candidate -> {
            appendValue(value, candidate.candidateId());
            appendValue(value, candidate.documentVersionReference());
        });
        appendList(value, "ambiguityCandidates", action.ambiguityCandidates());
    }

    private static void appendCorrection(StringBuilder value, PatentCorrectionAction action) {
        appendValue(value, action == null ? "no-correction" : "correction");
        if (action == null) return;
        appendValue(value, action.kind().name());
        appendValue(value, action.artifact().id());
        appendValue(value, Long.toString(action.artifact().version()));
        appendValue(value, action.artifact().type().name());
        appendValue(value, action.artifact().branchId());
        appendList(value, "artifactDependencies", action.artifact().dependsOn());
        appendValue(value, action.replacedArtifactId());
        appendValue(value, Long.toString(action.replacedVersion()));
        appendValue(value, action.reason());
    }

    private static void appendEvidence(StringBuilder value, PatentEvidenceAction action) {
        appendValue(value, action == null ? "no-evidence" : "evidence");
        if (action == null) return;
        appendValue(value, action.kind().name());
        if (action.evidence() != null) {
            var item = action.evidence();
            appendValue(value, item.id()); appendValue(value, Long.toString(item.version()));
            appendValue(value, item.branchId()); appendValue(value, item.sourceId());
            appendValue(value, item.sourceVersion()); appendValue(value, item.locator());
            appendValue(value, item.supportsArtifactKey());
            appendValue(value, Boolean.toString(item.currentlyAccessible()));
            appendList(value, "evidenceLimitations", item.limitations());
        }
        if (action.evidenceSet() != null) {
            var set = action.evidenceSet();
            appendValue(value, set.id()); appendValue(value, Long.toString(set.version()));
            appendValue(value, set.branchId()); appendList(value, "evidenceKeys", set.evidenceKeys());
        }
        if (action.assessment() != null) {
            var item = action.assessment();
            appendValue(value, item.id()); appendValue(value, Long.toString(item.version()));
            appendValue(value, item.branchId()); appendValue(value, item.targetArtifactKey());
            appendValue(value, item.evidenceSetKey()); appendValue(value, item.matrixReference());
            appendValue(value, item.status().name()); appendValue(value, item.reason());
            appendList(value, "missingOrConflicting", item.missingOrConflicting());
            appendList(value, "assessmentLimitations", item.limitations());
            appendValue(value, item.responsibilityReference());
        }
    }

    private static void appendRetrieval(StringBuilder value, PatentRetrievalAction action) {
        appendValue(value, action == null ? "no-retrieval" : "retrieval");
        if (action == null) return;
        appendValue(value, action.executionId()); appendValue(value, action.branchId());
        appendValue(value, action.actualScopeReference()); appendValue(value, action.methodReference());
        appendValue(value, action.queryReference()); appendValue(value, action.gapReference());
        appendValue(value, action.scopeConfirmationReference()); appendValue(value, action.outcome().name());
        appendValue(value, action.resultSetId()); appendValue(value, Long.toString(action.resultSetVersion()));
        appendList(value, "retrievalSources", action.sourceReferences());
        appendList(value, "retrievalLimitations", action.limitations());
        appendValue(value, action.detail());
    }

    private static void appendComparison(StringBuilder value, PatentComparisonAction action) {
        appendValue(value, action == null ? "no-comparison" : "comparison");
        if (action == null) return;
        appendValue(value, action.id()); appendValue(value, Long.toString(action.version()));
        appendValue(value, action.branchId()); appendValue(value, action.subTaskId());
        appendValue(value, action.selectionReference()); appendValue(value, action.purposeReference());
        appendList(value, "comparisonLimitations", action.limitations());
        appendValue(value, Integer.toString(action.items().size()));
        action.items().stream().sorted(Comparator.comparing(PatentComparisonAction.Item::featureKey)).forEach(item -> {
            appendValue(value, item.featureKey()); appendValue(value, item.evidenceKey());
            appendValue(value, item.relationship().name()); appendValue(value, item.reason());
            appendList(value, "missing", item.missing());
            appendValue(value, item.responsibilityReference());
        });
    }

    private static void appendHuman(StringBuilder value, PatentHumanAction action) {
        appendValue(value, action == null ? "no-human" : "human");
        if (action == null) return;
        appendValue(value, action.kind().name());
        if (action.handoff() != null) {
            var item = action.handoff();
            appendValue(value, item.id()); appendValue(value, Long.toString(item.version()));
            appendValue(value, item.branchId()); appendValue(value, item.subTaskId());
            appendValue(value, Long.toString(item.taskVersion())); appendValue(value, item.goalReference());
            appendList(value, "facts", item.factVersions()); appendList(value, "results", item.resultReferences());
            appendList(value, "evidence", item.evidenceReferences());
            appendList(value, "comparisons", item.comparisonReferences());
            appendList(value, "unresolved", item.unresolved());
            appendValue(value, item.scopeReference()); appendValue(value, item.requestedAction());
            appendValue(value, item.availability());
        }
        if (action.receipt() != null) {
            var item = action.receipt();
            appendValue(value, item.authoritativeId()); appendValue(value, item.handoffKey());
            appendValue(value, item.actorReference()); appendValue(value, item.scopeReference());
            appendValue(value, item.resultReference()); appendValue(value, item.authorityEvidenceReference());
        }
    }

    private static void appendMap(StringBuilder value, String name, Map<String, Long> map) {
        appendValue(value, name);
        appendValue(value, Integer.toString(map.size()));
        map.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    appendValue(value, entry.getKey());
                    appendValue(value, Long.toString(entry.getValue()));
                });
    }

    private static void appendList(StringBuilder value, String name, List<String> list) {
        appendValue(value, name);
        appendValue(value, Integer.toString(list.size()));
        list.stream().sorted().forEach(item -> appendValue(value, item));
    }

    private static void appendValue(StringBuilder value, String item) {
        if (item == null) {
            value.append("-1:");
        } else {
            value.append(item.length()).append(':').append(item);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

}
