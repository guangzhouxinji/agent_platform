package cn.xinji.agentplatform.consultation.caselibrary.adapter.out.memory;

import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Annotation;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Audit;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Task;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowStore;
import java.time.Instant;
import java.util.*;

public final class InMemoryCaseAnnotationWorkflowStore implements CaseAnnotationWorkflowStore {
    private final Map<String, Task> tasks = new HashMap<>();
    private final Map<String, List<Annotation>> annotations = new HashMap<>();
    private final Map<String, List<Audit>> audits = new HashMap<>();

    @Override public synchronized Task createIfAbsent(String requester, Task task, Annotation initial, Audit audit) {
        String key = key(requester, task.caseId(), task.sourceVersion(), task.config());
        Task existing = tasks.get(key);
        if (existing != null) return existing;
        tasks.put(key, task);
        annotations.put(initial.annotationId(), new ArrayList<>(List.of(initial)));
        audits.put(initial.annotationId(), new ArrayList<>(List.of(audit)));
        return task;
    }

    @Override public synchronized Optional<Annotation> current(String annotationId) {
        List<Annotation> versions = annotations.get(annotationId);
        return versions == null ? Optional.empty() : Optional.of(versions.getLast());
    }

    @Override public synchronized Annotation append(int expectedVersion, Annotation next, Audit audit) {
        List<Annotation> versions = annotations.get(next.annotationId());
        if (versions == null) throw new IllegalArgumentException("annotation not found");
        if (versions.getLast().version() != expectedVersion) throw new VersionConflictException(next.annotationId());
        versions.add(next);
        audits.get(next.annotationId()).add(audit);
        return next;
    }

    @Override public synchronized List<Annotation> annotationsForCase(String caseId) {
        return annotations.values().stream().flatMap(Collection::stream).filter(value -> value.caseId().equals(caseId))
                .sorted(Comparator.comparing(Annotation::annotationId).thenComparingInt(Annotation::version)).toList();
    }

    @Override public synchronized List<Audit> auditsForCase(String caseId) {
        Set<String> ids = annotations.values().stream().filter(values -> !values.isEmpty() && values.getFirst().caseId().equals(caseId))
                .map(values -> values.getFirst().annotationId()).collect(java.util.stream.Collectors.toSet());
        return audits.entrySet().stream().filter(entry -> ids.contains(entry.getKey())).flatMap(entry -> entry.getValue().stream())
                .sorted(Comparator.comparing(Audit::createdAt).thenComparingInt(Audit::annotationVersion)).toList();
    }

    @Override public synchronized Optional<Annotation> published(String caseId, String sourceVersion) {
        return annotations.values().stream().map(List::getLast)
                .filter(value -> value.caseId().equals(caseId) && value.sourceVersion().equals(sourceVersion) && value.state().equals("PUBLISHED"))
                .max(Comparator.comparing(Annotation::updatedAt));
    }

    @Override public synchronized int failInterruptedTasks(Instant now) { return 0; }
    @Override public String storageMode() { return "IN_MEMORY"; }
    private static String key(String requester, String caseId, String version, String config) { return requester + "|" + caseId + "|" + version + "|" + config; }
}
