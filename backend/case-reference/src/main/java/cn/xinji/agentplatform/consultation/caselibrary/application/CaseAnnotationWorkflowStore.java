package cn.xinji.agentplatform.consultation.caselibrary.application;

import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Annotation;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Audit;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Task;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CaseAnnotationWorkflowStore {
    Task createIfAbsent(String requester, Task task, Annotation initial, Audit audit);
    Optional<Annotation> current(String annotationId);
    Annotation append(int expectedVersion, Annotation next, Audit audit);
    List<Annotation> annotationsForCase(String caseId);
    List<Audit> auditsForCase(String caseId);
    Optional<Annotation> published(String caseId, String sourceVersion);
    int failInterruptedTasks(Instant now);
    String storageMode();

    final class VersionConflictException extends RuntimeException {
        public VersionConflictException(String annotationId) { super("case annotation version conflict: " + annotationId); }
    }
}
