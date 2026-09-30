package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevComparisonInputPreviewStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test-synthetic")
@ConditionalOnProperty(name = "consultation-v2.development.input-preview-enabled", havingValue = "true")
final class DevComparisonInputPreviewController {
    private final DevComparisonInputPreviewStore store;
    private final DevTaskCoreController core;
    private final DevComparisonInputPolicy comparison;
    private final SyntheticUsePolicy oldSources;

    DevComparisonInputPreviewController(DevComparisonInputPreviewStore store, DevTaskCoreController core,
                                        DevComparisonInputPolicy comparison, SyntheticUsePolicy oldSources) {
        this.store = store;
        this.core = core;
        this.comparison = comparison;
        this.oldSources = oldSources;
    }

    @GetMapping("/v2/consultation/tasks/{id}/feature-confirmations/{featureRevision}/focus/"
            + "{focusSequence}/input-preview")
    DevComparisonInputPreviewStore.Preview read(HttpServletRequest request, @PathVariable String id,
                                                 @PathVariable long featureRevision,
                                                 @PathVariable long focusSequence) {
        var human = core.actor(request);
        return store.read(human, id, featureRevision, focusSequence, core.guard(request), sources(request));
    }

    DevComparisonInputPreviewStore.Sources sources(HttpServletRequest request) {
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        return new DevComparisonInputPreviewStore.Sources() {
                    @Override public cn.xinji.agentplatform.consultation.taskcore.DevFeatureConfirmationStore.Source
                    features(cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor actor,
                             java.time.Instant now) {
                        return comparison.permittedComparisonFeatures(subject, actor, now);
                    }

                    @Override public cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore.Source
                    claim(cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor actor,
                          String record, String version, String digest, java.time.Instant now,
                          boolean comparisonUse) {
                        if (subject == null || !actor.principal().equals(subject.stableSubjectId())
                                || !actor.organization().equals(subject.organizationRef())) return null;
                        return comparisonUse
                                ? comparison.permittedComparisonClaim(subject, actor, record, version, digest, now)
                                : oldSources.claim(subject, record, version, digest, false, false, true, now);
                    }
                };
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }
}
